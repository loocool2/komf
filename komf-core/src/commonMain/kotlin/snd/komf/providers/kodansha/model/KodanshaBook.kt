package snd.komf.providers.kodansha.model

import kotlinx.datetime.LocalDate

/**
 * Book (volume) id. A volume is addressed as /series/{seriesSlug}/volume-{n}/, so the id is
 * the volume path segment (e.g. "volume-9"). It is unique within a series, which is enough
 * because Komf always passes the series id alongside the book id.
 */
@JvmInline
value class KodanshaBookId(val id: String)

/** A volume, assembled from the scraped /series/{seriesSlug}/{volumeSlug}/ detail page. */
data class KodanshaBook(
    val seriesSlug: String,
    /** Path segment, e.g. "volume-9". */
    val slug: String,
    val number: Int? = null,
    val title: String,
    val description: String? = null,
    val creators: List<KodanshaCreator> = emptyList(),
    val genres: List<String> = emptyList(),
    val ageRating: Int? = null,
    val pageCount: Int? = null,
    val releaseDate: LocalDate? = null,
    val isbn: String? = null,
    val coverUrl: String? = null,
)
