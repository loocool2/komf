package snd.komf.providers.kodansha

import snd.komf.model.Author
import snd.komf.model.AuthorRole
import snd.komf.model.BookMetadata
import snd.komf.model.BookRange
import snd.komf.model.Image
import snd.komf.model.ProviderBookId
import snd.komf.model.ProviderBookMetadata
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.Publisher
import snd.komf.model.PublisherType
import snd.komf.model.SeriesBook
import snd.komf.model.SeriesMetadata
import snd.komf.model.SeriesSearchResult
import snd.komf.model.SeriesStatus
import snd.komf.model.SeriesTitle
import snd.komf.model.TitleType
import snd.komf.model.WebLink
import snd.komf.providers.BookMetadataConfig
import snd.komf.providers.CoreProviders
import snd.komf.providers.MetadataConfigApplier
import snd.komf.providers.SeriesMetadataConfig
import snd.komf.providers.kodansha.model.KodanshaBook
import snd.komf.providers.kodansha.model.KodanshaCreator
import snd.komf.providers.kodansha.model.KodanshaSearchResult
import snd.komf.providers.kodansha.model.KodanshaSeries

const val kodanshaBaseUrl = "https://kodansha.us"

class KodanshaMetadataMapper(
    private val seriesMetadataConfig: SeriesMetadataConfig,
    private val bookMetadataConfig: BookMetadataConfig,
) {

    fun toSeriesMetadata(
        series: KodanshaSeries,
        thumbnail: Image? = null,
    ): ProviderSeriesMetadata {
        val status = when (series.status?.lowercase()?.trim()) {
            "ongoing" -> SeriesStatus.ONGOING
            "completed", "complete" -> SeriesStatus.ENDED
            else -> null
        }

        val metadata = SeriesMetadata(
            status = status,
            titles = listOf(SeriesTitle(series.title, TitleType.LOCALIZED, "en")),
            summary = series.description,
            publisher = series.publisher?.let { Publisher(it, PublisherType.LOCALIZED) },
            ageRating = series.ageRating,
            genres = series.genres,
            totalBookCount = series.volumes.size.takeIf { it > 0 },
            thumbnail = thumbnail,
            authors = series.creators.toAuthors(),
            links = listOf(WebLink("Kodansha", seriesUrl(series.slug))),
        )

        val providerMetadata = ProviderSeriesMetadata(
            id = ProviderSeriesId(series.slug),
            metadata = metadata,
            books = series.volumes.map { volume ->
                SeriesBook(
                    id = ProviderBookId(volume.slug),
                    number = volume.number?.let { BookRange(it.toDouble()) },
                    name = volume.name,
                    type = null,
                    edition = null,
                )
            },
        )
        return MetadataConfigApplier.apply(providerMetadata, seriesMetadataConfig)
    }

    fun toBookMetadata(book: KodanshaBook, thumbnail: Image? = null): ProviderBookMetadata {
        val metadata = BookMetadata(
            title = book.title,
            summary = book.description,
            number = book.number?.let { BookRange(it.toDouble()) },
            releaseDate = book.releaseDate,
            isbn = book.isbn,
            authors = book.creators.toAuthors(),
            thumbnail = thumbnail,
            links = listOf(WebLink("Kodansha", bookUrl(book.seriesSlug, book.slug))),
        )

        val providerMetadata = ProviderBookMetadata(
            id = ProviderBookId(book.slug),
            metadata = metadata,
        )
        return MetadataConfigApplier.apply(providerMetadata, bookMetadataConfig)
    }

    fun toSeriesSearchResult(result: KodanshaSearchResult): SeriesSearchResult {
        return SeriesSearchResult(
            url = seriesUrl(result.slug),
            imageUrl = result.image?.bestUrl(),
            title = result.name,
            resultId = result.slug,
            provider = CoreProviders.KODANSHA,
        )
    }

    private fun List<KodanshaCreator>.toAuthors(): List<Author> =
        map { Author(it.name, AuthorRole.WRITER) }

    private fun seriesUrl(seriesSlug: String) = "$kodanshaBaseUrl/series/$seriesSlug"
    private fun bookUrl(seriesSlug: String, volumeSlug: String) =
        "$kodanshaBaseUrl/series/$seriesSlug/$volumeSlug"
}
