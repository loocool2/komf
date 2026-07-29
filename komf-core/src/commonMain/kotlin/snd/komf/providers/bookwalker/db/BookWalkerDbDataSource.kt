package snd.komf.providers.bookwalker.db

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcResult
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import snd.komf.model.BookRange
import snd.komf.providers.bookwalker.model.BookWalkerBook
import snd.komf.providers.bookwalker.model.BookWalkerBookId
import snd.komf.providers.bookwalker.model.BookWalkerSearchResult
import snd.komf.providers.bookwalker.model.BookWalkerSeries
import snd.komf.providers.bookwalker.model.BookWalkerSeriesBook
import snd.komf.providers.bookwalker.model.BookWalkerSeriesId
import snd.komf.providers.bookwalker.model.BookWalkerSeriesType
import java.sql.ResultSet

private val json = Json { ignoreUnknownKeys = true }

/** `product_contributors.role` — confirmed against a known volume. */
private const val ROLE_AUTHOR = 1
private const val ROLE_ARTIST = 2

/** `tags.namespace` — 1 is the 13 top-level genres, 2 the ~590 descriptive tags. */
private const val NAMESPACE_GENRE = 1
private const val NAMESPACE_TAG = 2
private const val NAMESPACE_FLAG = 3

/** `product_external_ids.type` 3 is a bare ISBN-13. */
private const val EXTERNAL_ID_ISBN = 3

/**
 * Reads metadata out of the downloaded BookWalker catalog export.
 *
 * ### Identifiers
 *
 * The export's primary keys are prefixed forms of the ids bookwalker.com uses in
 * its URLs, so no id mapping table is needed — the prefix is simply stripped:
 *
 *  - series URL id = `series.id` without `CNT_`
 *  - volume URL id = `products.content_id` without `CNT_`
 *
 * Note that a volume's URL id comes from `content_id`, **not** from
 * `products.id` (the `PRD_` key). Using the `PRD_` value produces links that
 * 404. This was confirmed against Infinite Dendrogram vol. 23, whose
 * `content_id` `CNT_36A3NJ6Z9PK0` matches the id in its live volume URL.
 *
 * ### Ordering
 *
 * Volumes are ordered by `display_order` only. `level` and `content_type` look
 * like they should identify volumes but are not consistent between series (the
 * same field is 2 for one series and 3 for another), so they are not used.
 */
class BookWalkerDbDataSource(
    private val database: BookWalkerDatabase,
) {

    /**
     * [types] is the set of `series.type` values eligible for the configured
     * media type. An empty set means no filter.
     */
    suspend fun search(
        title: String,
        types: Collection<BookWalkerSeriesType>,
        limit: Int,
    ): List<BookWalkerSearchResult> {
        val db = database.database()
        val sanitized = sanitizeMatchQuery(title) ?: return emptyList()

        // Interpolated rather than bound: the values are enum ids, never user
        // input, and a bound IN list would need a variable placeholder count.
        val typeFilter = types
            .filter { it != BookWalkerSeriesType.UNKNOWN }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.id.toString() }
            ?.let { "AND s.type IN ($it)" }
            ?: ""

        return transaction(db) {
            query(
                sql = searchSql(typeFilter),
                params = listOf(sanitized),
                limit = limit,
            ) { rs ->
                BookWalkerSearchResult(
                    seriesId = BookWalkerSeriesId(stripPrefix(rs.getString("series_id"))),
                    seriesName = rs.getString("title"),
                    imageUrl = BookWalkerCoverUrl.build(rs.getString("image_id")),
                )
            }
        }
    }

    suspend fun getSeries(id: BookWalkerSeriesId): BookWalkerSeries? {
        val db = database.database()
        val rowId = addPrefix(id.id)

        return transaction(db) {
            val base = query(SERIES_SQL, listOf(rowId)) { rs ->
                SeriesRow(
                    title = rs.getString("title"),
                    altTitles = parseJsonStringArray(rs.getString("alt_titles")),
                    description = rs.getString("description")?.ifBlank { null },
                    imageId = rs.getString("image_id"),
                    type = BookWalkerSeriesType.from(rs.getInt("type")),
                )
            }.firstOrNull() ?: return@transaction null

            val genres = seriesTagNames(rowId, NAMESPACE_GENRE)
            val tags = seriesTagNames(rowId, NAMESPACE_TAG)
            val flags = seriesTagNames(rowId, NAMESPACE_FLAG)
            val books = query(SERIES_BOOKS_SQL, listOf(rowId)) { rs -> toSeriesBook(rs) }
            val imprint = query(SERIES_IMPRINT_SQL, listOf(rowId)) { rs ->
                rs.getString("label") to rs.getString("publisher")
            }.firstOrNull()

            BookWalkerSeries(
                id = id,
                title = base.title,
                altTitles = base.altTitles,
                japaneseTitle = base.altTitles.firstOrNull { it.containsCjk() },
                description = base.description,
                imageUrl = BookWalkerCoverUrl.build(base.imageId),
                type = base.type,
                genres = genres,
                tags = tags,
                flags = flags,
                imprint = imprint?.first,
                publisher = imprint?.second,
                books = books,
            )
        }
    }

    suspend fun getBook(id: BookWalkerBookId): BookWalkerBook? {
        val db = database.database()
        val contentId = addPrefix(id.id)

        return transaction(db) {
            val row = query(BOOK_SQL, listOf(contentId)) { rs ->
                BookRow(
                    productId = rs.getString("id"),
                    seriesId = rs.getString("series_id"),
                    title = rs.getString("title"),
                    description = rs.getString("description")?.ifBlank { null },
                    displayOrder = rs.getObject("display_order")?.let { (it as Number).toDouble() },
                    onSaleAt = rs.getString("on_sale_at"),
                    imageId = rs.getString("image_id"),
                    imprint = rs.getString("label"),
                    publisher = rs.getString("publisher"),
                    isbn = rs.getString("isbn"),
                )
            }.firstOrNull() ?: return@transaction null

            val contributors = query(BOOK_CONTRIBUTORS_SQL, listOf(row.productId)) { rs ->
                rs.getInt("role") to rs.getString("name")
            }
            val seriesRowId = row.seriesId
            val seriesTitles = query(SERIES_TITLES_SQL, listOf(seriesRowId)) { rs ->
                rs.getString("title") to parseJsonStringArray(rs.getString("alt_titles"))
            }.firstOrNull()

            BookWalkerBook(
                id = id,
                seriesId = BookWalkerSeriesId(stripPrefix(seriesRowId)),
                name = row.title,
                number = bookNumber(row.title, row.displayOrder),
                seriesTitle = seriesTitles?.first,
                japaneseTitle = seriesTitles?.second?.firstOrNull { it.containsCjk() },
                // The export carries only localized and native-script titles;
                // there is no separate romaji field.
                romajiTitle = null,
                artists = contributors.filter { it.first == ROLE_ARTIST }.map { it.second },
                authors = contributors.filter { it.first == ROLE_AUTHOR }.map { it.second },
                publisher = row.imprint ?: row.publisher ?: "",
                genres = seriesTagNames(seriesRowId, NAMESPACE_GENRE),
                tags = seriesTagNames(seriesRowId, NAMESPACE_TAG),
                isbn = row.isbn,
                availableSince = row.onSaleAt?.let { parseDate(it) },
                synopsis = row.description,
                imageUrl = BookWalkerCoverUrl.build(row.imageId),
            )
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun JdbcTransaction.seriesTagNames(
        seriesRowId: String,
        namespace: Int,
    ): List<String> = query(SERIES_TAGS_SQL, listOf(seriesRowId, namespace.toString())) { rs ->
        rs.getString("name")
    }

    private fun toSeriesBook(rs: ResultSet): BookWalkerSeriesBook {
        val title = rs.getString("title")
        return BookWalkerSeriesBook(
            id = BookWalkerBookId(stripPrefix(rs.getString("content_id"))),
            number = bookNumber(title, rs.getObject("display_order")?.let { (it as Number).toDouble() }),
            name = title,
        )
    }

    /**
     * Runs a parameterised query and maps every row.
     *
     * Exposed has no typed table definitions here — the export's schema is
     * external and join-heavy, so raw SQL is clearer than DSL. This mirrors the
     * prepared-statement approach [snd.komf.providers.mangabaka.db.MangaBakaDbDataSource]
     * uses for its FTS query.
     *
     * Every parameter binds as text. A null entry binds an empty string, which
     * is what [SEARCH_SQL]'s `? = ''` test uses to mean "no filter" — SQLite's
     * driver here has no typed null binding through this API, and the columns
     * being compared are never legitimately empty.
     *
     * [limit] is interpolated rather than bound because SQLite does not accept a
     * parameter in `LIMIT` via this path. It is always an internal int, never
     * user input.
     */
    private fun <T> JdbcTransaction.query(
        sql: String,
        params: List<String?> = emptyList(),
        limit: Int? = null,
        map: (ResultSet) -> T,
    ): List<T> {
        val statement = connection.prepareStatement(
            if (limit != null) "$sql LIMIT $limit" else sql,
            false,
        )
        var result: JdbcResult? = null
        try {
            params.forEachIndexed { index, value ->
                statement.set(index + 1, value ?: "", TextColumnType())
            }
            result = statement.executeQuery()
            val rs = result.result
            return buildList { while (rs.next()) add(map(rs)) }
        } finally {
            result?.close()
            statement.closeIfPossible()
        }
    }

    /**
     * FTS5's `trigram` tokenizer cannot match fewer than three characters, and
     * bare query text would be parsed as FTS syntax. The term is quoted (so
     * operators such as `:` and `-` in titles like `Re:ZERO` are literal) and
     * anything shorter than a trigram is rejected rather than sent to SQLite,
     * which would raise.
     */
    private fun sanitizeMatchQuery(title: String): String? {
        val trimmed = title.trim()
        if (trimmed.length < 3) return null
        return "\"" + trimmed.replace("\"", "\"\"") + "\""
    }

    private data class SeriesRow(
        val title: String,
        val altTitles: List<String>,
        val description: String?,
        val imageId: String?,
        val type: BookWalkerSeriesType,
    )

    private data class BookRow(
        val productId: String,
        val seriesId: String,
        val title: String,
        val description: String?,
        val displayOrder: Double?,
        val onSaleAt: String?,
        val imageId: String?,
        val imprint: String?,
        val publisher: String?,
        val isbn: String?,
    )
}

// ---- id handling -----------------------------------------------------------

private const val CONTENT_PREFIX = "CNT_"

/** `CNT_1NJDQA5BR3YG` -> `1NJDQA5BR3YG` (the form used in site URLs). */
private fun stripPrefix(id: String) = id.removePrefix(CONTENT_PREFIX)

private fun addPrefix(id: String) =
    if (id.startsWith(CONTENT_PREFIX)) id else CONTENT_PREFIX + id

// ---- parsing ---------------------------------------------------------------

/** `series.alt_titles` / `products.alt_titles` are JSON arrays of strings. */
private fun parseJsonStringArray(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        (json.parseToJsonElement(raw) as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.content.ifBlank { null } }
            ?: emptyList()
    }.getOrDefault(emptyList())
}

/**
 * Matches an omnibus volume range in a product title: `Vols. 1-2`,
 * `Volume 9-10`.
 *
 * Deliberately anchored on a volume token immediately followed by `N-N`. The
 * export contains 1,478 `Episode N-N` and 955 `Chapter N-N` titles which are
 * *not* ranges — `Episode 1-1`, `Episode 2-3` are season/episode numbering — so
 * a bare digit-dash-digit pattern would corrupt them. `Volume 2 (2-3)` and
 * `Volume 1 Part 2` are likewise excluded: both require something between the
 * number and the dash, which this will not match.
 */
private val volumeRangeRegex = Regex(
    """(?:vols?\.?|volumes?)\s*(\d+(?:\.\d+)?)\s*[-–—]\s*(\d+(?:\.\d+)?)""",
    RegexOption.IGNORE_CASE,
)

/**
 * Volume number, widened to a range for omnibus editions.
 *
 * `display_order` alone is not enough: an omnibus of volumes 1-2 has
 * `display_order` 1, the next has 3, and so on, so relying on it collapses the
 * range to its first volume and leaves gaps that a media server reads as missing
 * books. The range exists only in the title.
 *
 * `display_order` remains the source for ordinary single volumes — it is
 * already correct for the 30,388 plain `Volume N` products and is more reliable
 * than re-parsing the title.
 */
private fun bookNumber(title: String?, displayOrder: Double?): BookRange? {
    val match = title?.let { volumeRangeRegex.find(it) }
    if (match != null) {
        val start = match.groupValues[1].toDoubleOrNull()
        val end = match.groupValues[2].toDoubleOrNull()
        if (start != null && end != null && end >= start) return BookRange(start, end)
    }
    return displayOrder?.let { BookRange(it) }
}

/** Dates in the export are ISO-8601 instants, e.g. `2017-07-23T15:00:00.000Z`. */
private fun parseDate(raw: String): LocalDate? =
    runCatching { LocalDate.parse(raw.substringBefore('T')) }.getOrNull()

private fun String.containsCjk() = any { char ->
    val code = char.code
    // CJK Unified Ideographs, Hiragana, Katakana.
    code in 0x4E00..0x9FFF || code in 0x3040..0x309F || code in 0x30A0..0x30FF
}

// ---- SQL -------------------------------------------------------------------

/**
 * Trigram FTS over series titles plus flattened `alt_titles`, built by the
 * downloader's search-index step.
 *
 * [typeFilter] is a pre-built `AND s.type IN (...)` clause, or empty for no
 * filter. Ranking is left to FTS; the caller applies Komf's name matcher on top,
 * exactly as the MangaBaka provider does.
 */
private fun searchSql(typeFilter: String) = """
    SELECT f.series_id AS series_id,
           s.title     AS title,
           s.image_id  AS image_id
    FROM series_fts f
    JOIN series s ON s.id = f.series_id
    WHERE series_fts MATCH ?
      $typeFilter
    ORDER BY rank
""".trimIndent()

private val SERIES_SQL = """
    SELECT s.title, s.alt_titles, s.description, s.type, s.image_id
    FROM series s
    WHERE s.id = ?
""".trimIndent()

private val SERIES_TITLES_SQL = """
    SELECT title, alt_titles FROM series WHERE id = ?
""".trimIndent()

private val SERIES_TAGS_SQL = """
    SELECT t.name
    FROM series_tags st
    JOIN tags t ON t.id = st.tag_id
    WHERE st.series_id = ? AND t.namespace = CAST(? AS INTEGER)
    ORDER BY t.priority
""".trimIndent()

private val SERIES_BOOKS_SQL = """
    SELECT p.content_id, p.title, p.display_order
    FROM products p
    WHERE p.series_id = ?
    ORDER BY p.display_order
""".trimIndent()

/** Imprint/publisher for a series, taken from its earliest volume. */
private val SERIES_IMPRINT_SQL = """
    SELECT l.name AS label, pub.display_name AS publisher
    FROM products p
    JOIN labels l ON l.id = p.label_id
    LEFT JOIN publishers pub ON pub.id = l.publisher_id
    WHERE p.series_id = ?
    ORDER BY p.display_order
    LIMIT 1
""".trimIndent()

private val BOOK_SQL = """
    SELECT p.id, p.series_id, p.title, p.description, p.display_order, p.on_sale_at,
           p.image_id,
           l.name AS label, pub.display_name AS publisher,
           (SELECT e.external_id FROM product_external_ids e
             WHERE e.product_id = p.id AND e.type = $EXTERNAL_ID_ISBN LIMIT 1) AS isbn
    FROM products p
    LEFT JOIN labels l ON l.id = p.label_id
    LEFT JOIN publishers pub ON pub.id = l.publisher_id
    WHERE p.content_id = ?
""".trimIndent()

/** `name_override` wins when present — the export uses it for per-title credits. */
private val BOOK_CONTRIBUTORS_SQL = """
    SELECT pc.role AS role,
           coalesce(nullif(pc.name_override, ''), c.name) AS name
    FROM product_contributors pc
    JOIN contributors c ON c.id = pc.contributor_id
    WHERE pc.product_id = ?
    ORDER BY pc.role
""".trimIndent()
