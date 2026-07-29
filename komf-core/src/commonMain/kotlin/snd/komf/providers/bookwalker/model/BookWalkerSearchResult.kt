package snd.komf.providers.bookwalker.model

import kotlin.jvm.JvmInline

@JvmInline
value class BookWalkerSeriesId(val id: String)

/**
 * A search hit.
 *
 * [seriesId] is non-null and [bookId] is gone: search now runs against the
 * catalog's series rows directly, so every hit *is* a series. The scraper's
 * search returned a mix of series cards and volume cards and had to resolve a
 * volume to its series with a second request, which is what those nullable
 * fields existed for.
 */
data class BookWalkerSearchResult(
    val seriesId: BookWalkerSeriesId,
    val seriesName: String,
    val imageUrl: String?,
)
