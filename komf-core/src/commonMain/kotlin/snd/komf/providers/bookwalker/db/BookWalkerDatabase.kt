package snd.komf.providers.bookwalker.db

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger { }

/**
 * Owns the connection to the downloaded BookWalker catalog and keeps it fresh.
 *
 * ### Refresh policy
 *
 * The requested flow is "on use, check age, redownload if older than 24h, then
 * query". This implements that with one deliberate change: a stale database
 * triggers the refresh **in the background** and the caller is served from the
 * existing copy immediately.
 *
 * Awaiting the refresh inline would make whichever metadata request happens to
 * cross the 24h boundary block on a ~52 MB download plus a 233 MB decompress —
 * during a library scan that is an arbitrary request stalling for tens of
 * seconds, and Komga's matcher waits on it. Serving data that is 24h + ε old is
 * a far better failure mode than a stalled scan, and the window is one request
 * wide.
 *
 * The single case that *must* block is a cold start with no database on disk at
 * all, where there is nothing to serve from. [database] blocks only there.
 *
 * ### Swapping without disturbing readers
 *
 * Each refresh writes a new `bkwk-db-{millis}.sqlite` rather than overwriting.
 * On completion the [Database] reference is replaced atomically; queries already
 * running keep using the old [Database] and old file, which stays valid until
 * closed. Older generations are deleted on the next successful swap and at
 * startup. This avoids both the torn-read risk of overwriting a live SQLite file
 * and Windows' refusal to replace a file that is currently open.
 */
class BookWalkerDatabase(
    private val databaseDir: Path,
    private val downloader: BookWalkerDbDownloader,
    private val dbMetadata: BookWalkerDbMetadata,
    private val refreshInterval: Duration = 24.hours,
) {
    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var current: Database? = null

    @Volatile
    private var currentFile: Path? = null

    /** Generation kept one swap as grace for queries that started before it. */
    @Volatile
    private var retiredDatabase: Database? = null

    @Volatile
    private var retiredFile: Path? = null

    init {
        // Adopt an existing generation if one is on disk, so a restart does not
        // re-download. Anything else in the directory is a leftover from an
        // interrupted refresh.
        val existing = dbMetadata.databaseFileName?.let { databaseDir.resolve(it) }
        if (existing != null && existing.exists()) {
            currentFile = existing
            current = Database.connect("jdbc:sqlite:$existing")
            logger.info { "using existing BookWalker database ${existing.name}" }
        }
        deleteOtherGenerations(keep = existing)
    }

    /** True when a database is available without downloading one first. */
    fun isInitialized() = current != null

    /**
     * Returns a usable database, refreshing according to the policy above.
     * Blocks only on a cold start with nothing on disk.
     */
    suspend fun database(): Database {
        val existing = current
        if (existing == null) {
            refreshMutex.withLock {
                // Another caller may have completed the cold start while we waited.
                current?.let { return it }
                logger.info { "no BookWalker database present, downloading before first query" }
                doRefresh()
            }
            return current ?: error("BookWalker database unavailable after download")
        }

        if (isStale()) launchBackgroundRefresh()
        return existing
    }

    private fun isStale(): Boolean {
        val timestamp = dbMetadata.timestamp ?: return true
        return Clock.System.now() - timestamp >= refreshInterval
    }

    private fun launchBackgroundRefresh() {
        // tryLock rather than withLock: if a refresh is already running, this
        // request simply serves from the current database instead of queueing.
        if (!refreshMutex.tryLock()) return
        scope.launch {
            try {
                if (isStale()) doRefresh()
            } catch (e: Exception) {
                // A failed refresh is not fatal — the existing database stays in
                // use and the next request will retry.
                logger.error(e) { "BookWalker database refresh failed; continuing with existing copy" }
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    /** Caller must hold [refreshMutex]. */
    private suspend fun doRefresh() {
        when (val result = downloader.refresh()) {
            is BookWalkerDbDownloader.Result.Unchanged -> {
                // Validator matched. If we somehow have no connection (metadata
                // present but file missing), fall through to a forced download.
                if (current == null) {
                    val forced = downloader.refresh(force = true)
                    if (forced is BookWalkerDbDownloader.Result.Downloaded) swapTo(forced.path)
                }
            }

            is BookWalkerDbDownloader.Result.Downloaded -> swapTo(result.path)
        }
    }

    private fun swapTo(newFile: Path) {
        val previousDb = current
        val previousFile = currentFile

        current = Database.connect("jdbc:sqlite:$newFile")
        currentFile = newFile
        logger.info { "BookWalker database swapped to ${newFile.name}" }

        // The generation from *two* swaps ago is now certainly idle: nothing has
        // been able to obtain it since the previous swap. Close it and drop its
        // file. Closing matters for more than tidiness — an open handle keeps the
        // inode alive on Linux, so deleting the file without closing first would
        // not actually reclaim the 233 MB.
        retiredDatabase?.let { runCatching { TransactionManager.closeAndUnregister(it) } }
        retiredFile?.let { stale ->
            runCatching { stale.deleteIfExists() }
                .onFailure { logger.debug { "could not delete ${stale.name}: ${it.message}" } }
        }

        // The immediately previous generation is kept one cycle as grace: a query
        // that started before the swap still holds it.
        retiredDatabase = previousDb
        retiredFile = previousFile?.takeIf { it != newFile }

        deleteOtherGenerations(keep = newFile, alsoKeep = retiredFile)
    }

    private fun deleteOtherGenerations(keep: Path?, alsoKeep: Path? = null) {
        runCatching {
            if (!databaseDir.exists()) return
            databaseDir.listDirectoryEntries()
                .filter { it.name.startsWith("bkwk-db-") }
                .filter { it != keep && it != alsoKeep }
                .forEach { stale ->
                    runCatching { stale.deleteIfExists() }
                        .onFailure { logger.debug { "could not delete ${stale.name}: ${it.message}" } }
                }
        }
    }
}
