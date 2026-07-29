package snd.komf.providers.bookwalker.db

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.counted
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.io.IOUtils
import java.io.BufferedInputStream
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.time.Clock

private const val DOWNLOAD_BUFFER_SIZE = 1024L * 1024L
private val logger = KotlinLogging.logger { }

const val bookWalkerDatabaseUrl = "https://static.bookwalker.com/data/bkwk-db.sqlite.zst"

/**
 * Downloads and prepares the BookWalker catalog export.
 *
 * The export is a zstd-compressed SQLite file (~52 MB compressed, ~233 MB on
 * disk). Two things about the handling are deliberate:
 *
 *  - **Conditional download.** A `HEAD` is issued first and the `ETag` /
 *    `Last-Modified` compared against the stored validator. BookWalker
 *    regenerates the export daily, but a Komf instance that has been down for a
 *    while (or a user with a short refresh interval) should not re-pull 52 MB to
 *    discover nothing changed. On a match only the timestamp is bumped.
 *
 *  - **Generation files, not overwrite.** Each download lands in a uniquely
 *    named `bkwk-db-{epochMillis}.sqlite`. The caller swaps its connection to the
 *    new file and deletes older generations once no longer referenced. Replacing
 *    the file in place would break in-flight readers, and on Windows would fail
 *    outright while the old file is open.
 *
 * The search index is built after extraction — the published export ships no FTS
 * table, so [createSearchIndex] adds a trigram index over series titles. It costs
 * ~0.5 s and ~14 MB.
 */
class BookWalkerDbDownloader(
    private val ktor: HttpClient,
    private val databaseDir: Path,
    private val dbMetadata: BookWalkerDbMetadata,
) {

    /**
     * Result of a refresh attempt.
     *  - [Unchanged] — remote validator matched; the existing database is current.
     *  - [Downloaded] — a new generation file was written; [path] is it.
     */
    sealed interface Result {
        data object Unchanged : Result
        data class Downloaded(val path: Path) : Result
    }

    suspend fun refresh(force: Boolean = false): Result {
        databaseDir.createParentDirectories()
        databaseDir.toFile().mkdirs()

        val remoteValidator = fetchValidator()
        val storedValidator = dbMetadata.validator
        if (!force && remoteValidator != null && remoteValidator == storedValidator) {
            logger.info { "BookWalker database unchanged (validator $remoteValidator); skipping download" }
            dbMetadata.setTimestamp(Clock.System.now())
            return Result.Unchanged
        }

        val stamp = Clock.System.now().toEpochMilliseconds()
        val archive = databaseDir.resolve("bkwk-db-$stamp.sqlite.zst")
        val database = databaseDir.resolve("bkwk-db-$stamp.sqlite")

        try {
            downloadArchive(archive)
            // Extraction and index building are blocking and long (~233 MB), so
            // they are kept off whichever dispatcher triggered the refresh.
            withContext(Dispatchers.IO) {
                extract(archive, database)
                // Drop the archive before indexing rather than in `finally`.
                // Holding it through the index build costs 52 MB of peak disk
                // for no reason, and the index step is the space-hungry one.
                archive.deleteIfExists()
                createSearchIndex(database)
            }

            dbMetadata.setValidator(remoteValidator)
            dbMetadata.setDatabaseFileName(database.name)
            dbMetadata.setTimestamp(Clock.System.now())
            logger.info { "BookWalker database refreshed into ${database.name}" }
            return Result.Downloaded(database)
        } catch (e: Exception) {
            // Leave any previously working generation untouched — a failed
            // refresh must not take the provider down.
            database.deleteIfExists()
            throw e
        } finally {
            archive.deleteIfExists()
        }
    }

    /**
     * `ETag` if the origin sends one, otherwise `Last-Modified`. Null if neither
     * is present or the HEAD fails, in which case we always download.
     */
    private suspend fun fetchValidator(): String? = runCatching {
        val response = ktor.head(bookWalkerDatabaseUrl)
        response.headers["ETag"] ?: response.headers["Last-Modified"]
    }.onFailure {
        logger.warn { "HEAD on $bookWalkerDatabaseUrl failed (${it.message}); will download unconditionally" }
    }.getOrNull()

    private suspend fun downloadArchive(target: Path) {
        logger.info { "downloading $bookWalkerDatabaseUrl" }
        ktor.prepareGet(bookWalkerDatabaseUrl).execute { response ->
            val channel = response.bodyAsChannel().counted()
            target.outputStream().buffered().use { output ->
                while (!channel.isClosedForRead) {
                    val packet = channel.readRemaining(DOWNLOAD_BUFFER_SIZE)
                    while (!packet.exhausted()) output.write(packet.readByteArray())
                }
                output.flush()
            }
            logger.info { "downloaded ${channel.totalBytesRead} bytes" }
        }
    }

    private fun extract(archive: Path, target: Path) {
        logger.info { "extracting ${archive.name}" }
        ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())).use { input ->
            target.outputStream().buffered().use { output -> IOUtils.copyLarge(input, output) }
        }
    }

    /**
     * The export has no FTS table. Build a trigram index over series titles so
     * search is a normal query rather than a full scan.
     *
     * `alt_titles` is a JSON array; it is flattened to a delimited string so the
     * tokenizer indexes the titles rather than the JSON punctuation. The trigram
     * tokenizer is used (as MangaBaka does) because it matches substrings and
     * handles CJK titles, which have no whitespace to tokenize on.
     */
    private fun createSearchIndex(databaseFile: Path) {
        logger.info { "building BookWalker search index" }

        // Plain JDBC rather than Exposed. Exposed retries a failed statement
        // three times, and for a failure like SQLITE_FULL every retry re-runs the
        // whole insert and rebuilds the journal — turning one disk-space error
        // into three, plus three misleading "cannot rollback" warnings. It also
        // registers the connection globally and never releases it, which would
        // leave a handle open on a file we may be about to delete.
        DriverManager.getConnection("jdbc:sqlite:$databaseFile").use { connection ->
            connection.createStatement().use { statement ->
                // No rollback journal. This file is a scratch copy that is
                // deleted outright if anything fails, so crash-safety during
                // construction buys nothing and the journal is a large chunk of
                // the peak disk requirement.
                statement.execute("PRAGMA journal_mode = OFF;")
                // Keep FTS sort scratch out of /tmp, which on a container or a
                // small VPS is often a tmpfs far smaller than the data volume.
                // 13,717 rows of titles is a few MB.
                statement.execute("PRAGMA temp_store = MEMORY;")

                statement.executeUpdate(
                    """
                    CREATE VIRTUAL TABLE series_fts USING fts5
                    (
                        series_id UNINDEXED,
                        title,
                        alt_titles,
                        tokenize = 'trigram'
                    );
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    INSERT INTO series_fts (series_id, title, alt_titles)
                    SELECT s.id,
                           s.title,
                           coalesce(
                               (SELECT group_concat(j.value, ' | ') FROM json_each(s.alt_titles) j),
                               ''
                           )
                    FROM series s;
                    """.trimIndent()
                )
            }
        }
    }
}
