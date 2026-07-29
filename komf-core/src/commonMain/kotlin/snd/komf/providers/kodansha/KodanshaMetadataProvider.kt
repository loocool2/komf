package snd.komf.providers.kodansha

import snd.komf.model.Image
import snd.komf.model.MatchQuery
import snd.komf.model.ProviderBookId
import snd.komf.model.ProviderBookMetadata
import snd.komf.model.ProviderSeriesId
import snd.komf.model.ProviderSeriesMetadata
import snd.komf.model.SeriesSearchResult
import snd.komf.providers.CoreProviders
import snd.komf.providers.MetadataProvider
import snd.komf.providers.kodansha.model.KodanshaBookId
import snd.komf.providers.kodansha.model.KodanshaSeriesId
import snd.komf.util.NameSimilarityMatcher

class KodanshaMetadataProvider(
    private val client: KodanshaClient,
    private val metadataMapper: KodanshaMetadataMapper,
    private val nameMatcher: NameSimilarityMatcher,
    private val fetchSeriesCovers: Boolean,
    private val fetchBookCovers: Boolean,
) : MetadataProvider {

    override fun providerName(): CoreProviders {
        return CoreProviders.KODANSHA
    }

    override suspend fun getSeriesMetadata(seriesId: ProviderSeriesId): ProviderSeriesMetadata {
        val series = client.getSeries(KodanshaSeriesId(seriesId.value))
        val thumbnail = if (fetchSeriesCovers) getThumbnail(series.coverUrl) else null
        return metadataMapper.toSeriesMetadata(series, thumbnail)
    }

    override suspend fun getSeriesCover(seriesId: ProviderSeriesId): Image? {
        val series = client.getSeries(KodanshaSeriesId(seriesId.value))
        return getThumbnail(series.coverUrl)
    }

    override suspend fun getBookMetadata(
        seriesId: ProviderSeriesId,
        bookId: ProviderBookId,
    ): ProviderBookMetadata {
        val book = client.getBook(KodanshaSeriesId(seriesId.value), KodanshaBookId(bookId.id))
        val thumbnail = if (fetchBookCovers) getThumbnail(book.coverUrl) else null
        return metadataMapper.toBookMetadata(book, thumbnail)
    }

    override suspend fun searchSeries(seriesName: String, limit: Int): Collection<SeriesSearchResult> {
        val searchResults = client.search(sanitizeSearchInput(seriesName)).data.take(limit)
        return searchResults.map { metadataMapper.toSeriesSearchResult(it) }
    }

    override suspend fun matchSeriesMetadata(matchQuery: MatchQuery): ProviderSeriesMetadata? {
        val seriesName = matchQuery.seriesName
        val searchResults = client.search(sanitizeSearchInput(seriesName)).data

        return searchResults
            .firstOrNull { nameMatcher.matches(seriesName, it.name.removeSuffix(" (manga)")) }
            ?.let { hint ->
                val series = client.getSeries(KodanshaSeriesId(hint.slug), hint)
                val thumbnail = if (fetchSeriesCovers) getThumbnail(series.coverUrl) else null
                metadataMapper.toSeriesMetadata(series, thumbnail)
            }
    }

    private suspend fun getThumbnail(url: String?): Image? {
        if (url == null || url.contains("kodansha_placeholder")) return null
        return client.getThumbnail(url)
    }

    private fun sanitizeSearchInput(input: String): String {
        return input.take(300)
            .replace("\"", "")
    }
}
