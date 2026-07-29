package snd.komf.providers.kodansha.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cover image as returned by the search endpoint. The actual bitmaps are hosted on Azuki
 * (production.image.azuki.co) and provided in several fixed sizes for both webp and jpg.
 */
@Serializable
data class KodanshaThumbnail(
    val uuid: String? = null,
    @SerialName("aspect_ratio_decimal") val aspectRatio: Double? = null,
    @SerialName("max_width") val maxWidth: Int? = null,
    val webp: List<KodanshaThumbnailVariant> = emptyList(),
    val jpg: List<KodanshaThumbnailVariant> = emptyList(),
) {
    /** Largest available cover, preferring webp. */
    fun bestUrl(): String? =
        (webp + jpg).maxByOrNull { it.width ?: 0 }?.url
}

@Serializable
data class KodanshaThumbnailVariant(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)
