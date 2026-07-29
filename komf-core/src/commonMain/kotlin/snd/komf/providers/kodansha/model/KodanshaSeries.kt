package snd.komf.providers.kodansha.model

/**
 * Series id. The redesigned kodansha.us addresses a series by slug
 * (/series/{seriesSlug}/), so the id is the slug rather than a numeric value.
 */
@JvmInline
value class KodanshaSeriesId(val id: String)

/**
 * A series, assembled from the scraped /series/{slug}/ detail page (optionally enriched
 * with a [KodanshaSearchResult] hint for fields the HTML omits, such as the uuid).
 */
data class KodanshaSeries(
    val slug: String,
    val uuid: String? = null,
    val title: String,
    val description: String? = null,
    val creators: List<KodanshaCreator> = emptyList(),
    val genres: List<String> = emptyList(),
    val ageRating: Int? = null,
    /** Raw label as shown on the site, e.g. "Ongoing" / "Completed". */
    val status: String? = null,
    val publisher: String? = null,
    val coverUrl: String? = null,
    val volumes: List<KodanshaSeriesVolume> = emptyList(),
)

/** A volume entry as listed on the series page's "All Volumes" grid. */
data class KodanshaSeriesVolume(
    /** Path segment relative to the series, e.g. "volume-9". Used as the book id. */
    val slug: String,
    val number: Int? = null,
    val name: String,
    val coverUrl: String? = null,
)
