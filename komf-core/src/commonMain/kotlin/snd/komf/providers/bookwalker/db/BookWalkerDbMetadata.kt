package snd.komf.providers.bookwalker.db

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.time.Instant

/**
 * Sidecar state for the downloaded BookWalker database, mirroring
 * [snd.komf.providers.mangabaka.db.MangaBakaDbMetadata].
 *
 * Two values are persisted next to the database file:
 *  - [timestamp] — when the current database was last *checked* against the
 *    remote (not necessarily last downloaded). Drives the 24h refresh interval.
 *  - [validator] — the remote `ETag` or `Last-Modified` from the last successful
 *    download. Lets a stale-but-unchanged database skip a ~52 MB download.
 *  - [databaseFileName] — the generation file currently in use. The downloader
 *    writes each new database to a fresh file rather than overwriting, so
 *    in-flight readers on the old file are never disturbed (and Windows, which
 *    refuses to replace an open file, is not a special case).
 */
class BookWalkerDbMetadata(
    private val timestampFile: Path,
    private val validatorFile: Path,
    private val databaseNameFile: Path,
) {
    private val logger = KotlinLogging.logger { }

    @Volatile
    var timestamp: Instant? = null
        private set

    @Volatile
    var validator: String? = null
        private set

    @Volatile
    var databaseFileName: String? = null
        private set

    init {
        timestamp = runCatching { Instant.parse(timestampFile.readText().trim()) }
            .onFailure { logger.debug { "no BookWalker timestamp file, treating database as absent" } }
            .getOrNull()
        validator = runCatching { validatorFile.readText().trim().ifBlank { null } }.getOrNull()
        databaseFileName = runCatching { databaseNameFile.readText().trim().ifBlank { null } }.getOrNull()
    }

    fun setTimestamp(timestamp: Instant) {
        this.timestamp = timestamp
        Files.writeString(timestampFile, timestamp.toString())
    }

    fun setValidator(validator: String?) {
        this.validator = validator
        if (validator == null) validatorFile.deleteIfExists()
        else Files.writeString(validatorFile, validator)
    }

    fun setDatabaseFileName(name: String) {
        this.databaseFileName = name
        Files.writeString(databaseNameFile, name)
    }

    fun delete() {
        timestampFile.deleteIfExists()
        validatorFile.deleteIfExists()
        databaseNameFile.deleteIfExists()
        timestamp = null
        validator = null
        databaseFileName = null
    }
}
