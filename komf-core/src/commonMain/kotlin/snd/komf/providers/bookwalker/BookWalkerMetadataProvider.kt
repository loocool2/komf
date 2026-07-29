package snd.komf.providers.bookwalker

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.github.reactivecircus.cache4k.Cache
import snd.komf.model.Image
import snd.komf.model.MatchQuery
import snd.komf.model.MediaType
import snd.komf.model.ProviderBookId
import snd.komf.model.ProviderBookMetadata
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.SeriesSearchResult
import snd.komf.providers.CoreProviders
import snd.komf.providers.CoreProviders.BOOK_WALKER
import snd.komf.providers.MetadataProvider
import snd.komf.providers.bookwalker.db.BookWalkerDbDataSource
import snd.komf.providers.bookwalker.model.BookWalkerBook
import snd.komf.providers.bookwalker.model.BookWalkerBookId
import snd.komf.providers.bookwalker.model.BookWalkerSeries
import snd.komf.providers.bookwalker.model.BookWalkerSeriesId
import snd.komf.providers.bookwalker.model.BookWalkerSeriesType
import snd.komf.util.NameSimilarityMatcher
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger { }

/**
 * BookWalker metadata, read from the published catalog export rather than
 * scraped from bookwalker.com.
 *
 * The site moved behind a Cloudflare JavaScript challenge on its search
 * endpoint, which made the scraper depend on a FlareSolverr sidecar to do
 * anything at all. The export removes the site from the request path entirely:
 * every lookup here is a local SQL query, so there is no rate limiting, no
 * challenge solving, and no HTML contract to break on the next redeploy.
 *
 * Two behaviours the scraper could not manage come for free:
 *
 *  - **Media-type scoping works again.** The export types each series, so a
 *    manga-configured provider no longer sees novel and audiobook hits. This was
 *    a known regression after the site rewrite.
 *  - **Series metadata is real.** Titles, summary, genres and tags come from
 *    series rows instead of being inferred from the first volume's page.
 *
 * Covers are the one thing still fetched over HTTP, and only when the export's
 * image rows can be turned into URLs — see
 * [snd.komf.providers.bookwalker.db.BookWalkerCoverUrl].
 */
class BookWalkerMetadataProvider(
    private val dataSource: BookWalkerDbDataSource,
    private val metadataMapper: BookWalkerMapper,
    private val nameMatcher: NameSimilarityMatcher,
    private val ktor: HttpClient,
    private val fetchSeriesCovers: Boolean,
    private val fetchBookCovers: Boolean,
    mediaType: MediaType,
) : MetadataProvider {

    /**
     * `series.type` values eligible for the configured media type.
     *
     * Audiobooks (type 4) are never eligible — they have no Komf media type, and
     * including them would put an audio edition in competition with the print
     * edition of the same title.
     *
     * Webtoon accepts manga as well as webtoon. Only 60 series are typed webtoon
     * in the export, so a webtoon-configured library filtered to type 3 alone
     * would match almost nothing; vertical-scroll titles are overwhelmingly
     * filed as manga.
     */
    private val seriesTypes = when (mediaType) {
        MediaType.MANGA -> listOf(BookWalkerSeriesType.MANGA)
        MediaType.WEBTOON -> listOf(BookWalkerSeriesType.WEBTOON, BookWalkerSeriesType.MANGA)
        MediaType.NOVEL -> listOf(BookWalkerSeriesType.NOVEL)
        MediaType.COMIC -> throw IllegalStateException("Comics media type is not supported")
    }

    // Queries are local and cheap, so these caches exist only to avoid repeating
    // the same joins across the volumes of one series during a scan.
    private val seriesCache = Cache.Builder<BookWalkerSeriesId, BookWalkerSeries>()
        .expireAfterWrite(30.minutes)
        .build()

    private val bookCache = Cache.Builder<BookWalkerBookId, BookWalkerBook>()
        .expireAfterWrite(30.minutes)
        .build()

    override fun providerName(): CoreProviders = BOOK_WALKER

    override suspend fun getSeriesMetadata(seriesId: ProviderSeriesId): ProviderSeriesMetadata {
        val series = requireSeries(BookWalkerSeriesId(seriesId.value))
        val firstBook = getFirstBook(series)
        val cover = if (fetchSeriesCovers) fetchCover(series.coverUrl(firstBook)) else null
        return metadataMapper.toSeriesMetadata(series, firstBook, cover)
    }

    override suspend fun getSeriesCover(seriesId: ProviderSeriesId): Image? {
        val series = requireSeries(BookWalkerSeriesId(seriesId.value))
        return fetchCover(series.coverUrl(getFirstBook(series)))
    }

    /**
     * Series rows carry their own cover, which the scraper had no access to — it
     * could only reuse the first volume's. Prefer it, falling back to volume 1
     * for the rare series with no image of its own.
     */
    private fun BookWalkerSeries.coverUrl(firstBook: BookWalkerBook?) =
        imageUrl ?: firstBook?.imageUrl

    override suspend fun getBookMetadata(
        seriesId: ProviderSeriesId,
        bookId: ProviderBookId,
    ): ProviderBookMetadata {
        val id = BookWalkerBookId(bookId.id)
        val book = getBook(id) ?: throw IllegalStateException("Book $bookId not found in BookWalker database")
        val cover = if (fetchBookCovers) fetchCover(book.imageUrl) else null
        return metadataMapper.toBookMetadata(book, cover)
    }

    override suspend fun searchSeries(seriesName: String, limit: Int): Collection<SeriesSearchResult> {
        val results = dataSource.search(sanitizeSearchInput(seriesName.take(150)), seriesTypes,limit)
        return results.mapNotNull { result ->
            result.seriesId?.let { metadataMapper.toSeriesSearchResult(result, it) }
        }
    }

    override suspend fun matchSeriesMetadata(matchQuery: MatchQuery): ProviderSeriesMetadata? {
        val seriesName = matchQuery.seriesName
        // Fetch more candidates than a search would show: FTS ranking is a
        // relevance ordering, not a match, and the name matcher makes the call.
        val results = dataSource.search(sanitizeSearchInput(seriesName.take(100)), seriesTypes,MATCH_CANDIDATES)

        val matched = results.firstOrNull { nameMatcher.matches(seriesName, it.seriesName) }
            ?: return null

        val series = getSeries(matched.seriesId) ?: return null
        val firstBook = getFirstBook(series)
        val cover = if (fetchSeriesCovers) fetchCover(series.coverUrl(firstBook)) else null
        return metadataMapper.toSeriesMetadata(series, firstBook, cover)
    }

    private suspend fun requireSeries(id: BookWalkerSeriesId): BookWalkerSeries =
        getSeries(id) ?: throw IllegalStateException("Series ${id.id} not found in BookWalker database")

    // cache4k's loader overload requires a non-null value, so misses are looked
    // up and stored explicitly. A row that does not exist is simply not cached —
    // the export is replaced daily and an id absent today may be present
    // tomorrow, so caching the absence would be wrong.
    private suspend fun getSeries(id: BookWalkerSeriesId): BookWalkerSeries? {
        seriesCache.get(id)?.let { return it }
        val series = dataSource.getSeries(id) ?: return null
        seriesCache.put(id, series)
        return series
    }

    private suspend fun getBook(id: BookWalkerBookId): BookWalkerBook? {
        bookCache.get(id)?.let { return it }
        val book = dataSource.getBook(id) ?: return null
        bookCache.put(id, book)
        return book
    }

    /**
     * The lowest-numbered volume, used for credits, release date and the series
     * cover. Volumes are already ordered by `display_order`; entries without one
     * sort last so a numbered volume is always preferred.
     */
    private suspend fun getFirstBook(series: BookWalkerSeries): BookWalkerBook? {
        val first = series.books
            .sortedWith(compareBy(nullsLast()) { it.number?.start })
            .firstOrNull() ?: return null
        return getBook(first.id)
    }

    private fun sanitizeSearchInput(name: String): String =
        name.replace("[(]([^)]+)[)]".toRegex(), "")
            .replace("\"", "")
            .trim()

    private suspend fun fetchCover(url: String?): Image? {
        if (url == null) return null
        return runCatching { Image(ktor.get(url).body<ByteArray>()) }
            .onFailure { logger.warn { "failed to fetch BookWalker cover $url: ${it.message}" } }
            .getOrNull()
    }

    companion object {
        private const val MATCH_CANDIDATES = 25
    }
}
