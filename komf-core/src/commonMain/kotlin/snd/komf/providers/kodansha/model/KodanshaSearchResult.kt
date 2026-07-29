package snd.komf.providers.kodansha.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KodanshaSearchResult(
    val uuid: String,
    val slug: String,
    val name: String,
    /** "comic" for manga, "novel" for light novels / prose books. */
    val type: String? = null,
    val color: String? = null,
    @SerialName("short_description") val shortDescription: String? = null,
    @SerialName("last_updated_at") val lastUpdatedAt: String? = null,
    @SerialName("is_complete") val isComplete: Boolean = false,
    @SerialName("age_rating") val ageRating: KodanshaAgeRating? = null,
    val image: KodanshaThumbnail? = null,
    @SerialName("publisher_uuid") val publisherUuid: String? = null,
)

@Serializable
data class KodanshaAgeRating(
    val rating: Int? = null,
    val label: String? = null,
)
