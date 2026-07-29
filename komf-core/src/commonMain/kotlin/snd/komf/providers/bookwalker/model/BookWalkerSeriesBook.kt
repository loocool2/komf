package snd.komf.providers.bookwalker.model

import snd.komf.model.BookRange

/**
 * A volume entry in a series' book list.
 *
 * `BookWalkerBookListPage` and its pagination are gone: the scraper paged
 * through series pages, whereas the catalog export returns every volume of a
 * series in a single query.
 */
data class BookWalkerSeriesBook(
    val id: BookWalkerBookId,
    val number: BookRange?,
    val name: String
)
