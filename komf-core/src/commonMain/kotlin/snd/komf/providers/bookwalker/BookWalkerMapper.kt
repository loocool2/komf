package snd.komf.providers.bookwalker

import kotlinx.datetime.number
import snd.komf.model.Author
import snd.komf.model.AuthorRole
import snd.komf.model.BookMetadata
import snd.komf.model.Image
import snd.komf.model.ProviderBookId
import snd.komf.model.ProviderBookMetadata
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.Publisher
import snd.komf.model.PublisherType
import snd.komf.model.ReleaseDate
import snd.komf.model.SeriesBook
import snd.komf.model.SeriesMetadata
import snd.komf.model.SeriesSearchResult
import snd.komf.model.SeriesStatus
import snd.komf.model.SeriesTitle
import snd.komf.model.TitleType.LOCALIZED
import snd.komf.model.TitleType.NATIVE
import snd.komf.model.WebLink
import snd.komf.providers.BookMetadataConfig
import snd.komf.providers.CoreProviders.BOOK_WALKER
import snd.komf.providers.MetadataConfigApplier
import snd.komf.providers.SeriesMetadataConfig
import snd.komf.providers.bookwalker.model.BookWalkerBook
import snd.komf.providers.bookwalker.model.BookWalkerBookId
import snd.komf.providers.bookwalker.model.BookWalkerSearchResult
import snd.komf.providers.bookwalker.model.BookWalkerSeries
import snd.komf.providers.bookwalker.model.BookWalkerSeriesId

const val bookWalkerBaseUrl = "https://bookwalker.com"

/**
 * Maps catalog rows onto Komf's metadata model.
 *
 * ### URLs
 *
 * Links are built as `/series/{id}` and `/volume/{id}` without the canonical
 * slug, which the export does not store. The scraper needed the slug because
 * bookwalker.com only server-renders the full page for the canonical URL — but
 * that constraint was about *parsing*. These URLs are only ever followed by a
 * person in a browser, where the slugless page hydrates client-side and renders
 * normally. Reconstructing a slug from the title would mean emitting a guessed
 * value in place of a sourced one.
 *
 * ### Series-level fields
 *
 * Titles, summary, genres and tags come from the series row. The scraper had to
 * infer all of these from the first volume's page because that was the only
 * place they appeared; the export has them directly, so [toSeriesMetadata] takes
 * a series and uses a book only for credits and release date.
 */
class BookWalkerMapper(
    private val seriesMetadataConfig: SeriesMetadataConfig,
    private val bookMetadataConfig: BookMetadataConfig,
    private val authorRoles: Collection<AuthorRole>,
    private val artistRoles: Collection<AuthorRole>,
) {

    fun toSeriesMetadata(
        series: BookWalkerSeries,
        firstBook: BookWalkerBook?,
        thumbnail: Image? = null,
    ): ProviderSeriesMetadata {
        val titles = buildList {
            add(SeriesTitle(series.title, LOCALIZED, "en"))
            series.japaneseTitle?.let { add(SeriesTitle(it, NATIVE, "ja")) }
            series.altTitles
                .filter { it != series.title && it != series.japaneseTitle }
                .forEach { add(SeriesTitle(it, null, null)) }
        }

        val metadata = SeriesMetadata(
            status = series.status(),
            titles = titles,
            summary = series.description ?: firstBook?.synopsis,
            publisher = (series.imprint ?: series.publisher)
                ?.let { Publisher(it, PublisherType.LOCALIZED) },
            genres = series.genres,
            tags = series.tags,
            ageRating = series.ageRating(),
            totalBookCount = series.books.size.takeIf { it > 0 },
            authors = firstBook?.let { getAuthors(it) } ?: emptyList(),
            thumbnail = thumbnail,
            releaseDate = firstBook?.availableSince?.let {
                ReleaseDate(year = it.year, month = it.month.number, day = it.day)
            },
            links = listOf(WebLink("BookWalker", seriesUrl(series.id))),
        )

        val providerMetadata = ProviderSeriesMetadata(
            id = ProviderSeriesId(series.id.id),
            metadata = metadata,
            books = series.books.map {
                SeriesBook(
                    id = ProviderBookId(it.id.id),
                    number = it.number,
                    name = it.name,
                    type = null,
                    edition = null,
                )
            },
        )

        return MetadataConfigApplier.apply(providerMetadata, seriesMetadataConfig)
    }

    fun toBookMetadata(book: BookWalkerBook, thumbnail: Image? = null): ProviderBookMetadata {
        val metadata = BookMetadata(
            title = book.name,
            summary = book.synopsis,
            number = book.number,
            releaseDate = book.availableSince,
            authors = getAuthors(book),
            startChapter = null,
            endChapter = null,
            thumbnail = thumbnail,
            links = listOf(WebLink("BookWalker", bookUrl(book.id))),
        )

        val providerMetadata = ProviderBookMetadata(
            id = ProviderBookId(book.id.id),
            metadata = metadata,
        )
        return MetadataConfigApplier.apply(providerMetadata, bookMetadataConfig)
    }

    fun toSeriesSearchResult(result: BookWalkerSearchResult, seriesId: BookWalkerSeriesId) =
        SeriesSearchResult(
            url = seriesUrl(seriesId),
            imageUrl = result.imageUrl,
            title = result.seriesName,
            provider = BOOK_WALKER,
            resultId = seriesId.id,
        )

    private fun getAuthors(book: BookWalkerBook): List<Author> {
        val artists = book.artists.flatMap { name -> artistRoles.map { role -> Author(name, role) } }
        val authors = book.authors.flatMap { name -> authorRoles.map { role -> Author(name, role) } }
        return artists + authors
    }

    /**
     * `tags.namespace` 3 carries `complete` / `adult` / `safe` flags. Only
     * `complete` maps to a status — the absence of that flag is not evidence a
     * series is ongoing, so everything else stays null rather than being guessed.
     */
    private fun BookWalkerSeries.status(): SeriesStatus? =
        if (flags.any { it.equals("complete", ignoreCase = true) }) SeriesStatus.ENDED else null

    /** `adult` is the only rating signal the export carries. */
    private fun BookWalkerSeries.ageRating(): Int? =
        if (flags.any { it.equals("adult", ignoreCase = true) }) 18 else null

    private fun seriesUrl(seriesId: BookWalkerSeriesId) = "$bookWalkerBaseUrl/series/${seriesId.id}"

    private fun bookUrl(bookId: BookWalkerBookId) = "$bookWalkerBaseUrl/volume/${bookId.id}"
}
