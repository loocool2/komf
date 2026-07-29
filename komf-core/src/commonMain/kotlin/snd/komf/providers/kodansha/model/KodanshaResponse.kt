package snd.komf.providers.kodansha.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope returned by the search endpoint
 * (GET https://kodansha.us/wp-json/kodansha/v1/search-series?q=...).
 */
@Serializable
data class KodanshaResponse(
    val success: Boolean = false,
    val data: List<KodanshaSearchResult> = emptyList(),
    val count: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)
