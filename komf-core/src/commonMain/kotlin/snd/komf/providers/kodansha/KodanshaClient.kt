package snd.komf.providers.kodansha

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import snd.komf.model.Image
import snd.komf.providers.kodansha.model.KodanshaBook
import snd.komf.providers.kodansha.model.KodanshaBookId
import snd.komf.providers.kodansha.model.KodanshaResponse
import snd.komf.providers.kodansha.model.KodanshaSearchResult
import snd.komf.providers.kodansha.model.KodanshaSeries
import snd.komf.providers.kodansha.model.KodanshaSeriesId

/**
 * Client for the redesigned kodansha.us.
 *
 * There is no longer a single JSON detail API (the old api.kodansha.us is gone), so this
 * mixes two sources:
 *  - Search uses the site's WordPress REST endpoint (JSON).
 *  - Series/volume detail is scraped from the server-rendered HTML pages via [KodanshaHtmlParser].
 */
class KodanshaClient(
    private val ktor: HttpClient,
    private val baseUrl: String = kodanshaBaseUrl,
) {
    private val searchUrl = "$baseUrl/wp-json/kodansha/v1/search-series"

    /** Full-text search over series. The query parameter is `q`. */
    suspend fun search(query: String): KodanshaResponse {
        return ktor.get(searchUrl) {
            parameter("q", query)
        }.body()
    }

    /**
     * Fetches and parses a series detail page.
     * @param hint optional search result to fill in fields the HTML omits (uuid, etc.).
     */
    suspend fun getSeries(
        seriesId: KodanshaSeriesId,
        hint: KodanshaSearchResult? = null,
    ): KodanshaSeries {
        val html: String = ktor.get("$baseUrl/series/${seriesId.id}/").body()
        return KodanshaHtmlParser.parseSeries(seriesId.id, html, hint)
    }

    /** Fetches and parses a single volume page. */
    suspend fun getBook(
        seriesId: KodanshaSeriesId,
        bookId: KodanshaBookId,
    ): KodanshaBook {
        val html: String = ktor.get("$baseUrl/series/${seriesId.id}/${bookId.id}/").body()
        return KodanshaHtmlParser.parseBook(seriesId.id, bookId.id, html)
    }

    suspend fun getThumbnail(url: String): Image {
        val bytes: ByteArray = ktor.get(url).body()
        return Image(bytes)
    }
}
