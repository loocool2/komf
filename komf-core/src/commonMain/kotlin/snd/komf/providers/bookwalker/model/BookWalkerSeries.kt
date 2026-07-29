package snd.komf.providers.bookwalker.model

/**
 * A series row from the BookWalker catalog export.
 *
 * The HTML scraper had no equivalent: series metadata used to be reconstructed
 * from the first volume's page because that was the only place it appeared. The
 * export has real series rows, so series-level fields (description, alternate
 * titles, genres, tags) now come from the series itself rather than being
 * inferred from a volume.
 */
data class BookWalkerSeries(
    val id: BookWalkerSeriesId,
    val title: String,
    val altTitles: List<String>,
    val japaneseTitle: String?,
    val description: String?,
    /** Series-level cover, independent of any volume's. */
    val imageUrl: String?,
    val type: BookWalkerSeriesType,
    val genres: List<String>,
    val tags: List<String>,
    val flags: List<String>,
    val publisher: String?,
    val imprint: String?,
    val books: List<BookWalkerSeriesBook>,
)

/**
 * `series.type` in the export.
 *
 * Identified from the data rather than documented:
 *
 * | id | meaning | evidence |
 * |---|---|---|
 * | 1 | manga | 12,655 series — the bulk of the catalogue. Infinite Dendrogram and Tanya each have a type-1 row alongside their novel row. |
 * | 2 | novel | 844 series. Confirmed against the Infinite Dendrogram and Tanya novel rows. |
 * | 3 | webtoon | 60 series, 38 of them on KADOKAWA TATESC (tatescroll, i.e. vertical scroll). |
 * | 4 | audiobook | 158 series, every top imprint an audio one: Seven Seas Siren (90), Yen Audio (47), Tantor Media (12), One Peace Books (Audiobooks), JNC Audio. |
 *
 * Note that `product_external_ids.type = 8` (`.mp3`) is *not* an audio marker
 * despite appearances — 692 type-1 manga series have it on every product. The
 * imprint evidence above is what identifies type 4.
 *
 * [AUDIOBOOK] has no Komf [snd.komf.model.MediaType] counterpart and is never
 * requested, so audiobook rows are excluded from every search rather than
 * competing with the print editions of the same title.
 */
enum class BookWalkerSeriesType(val id: Int) {
    MANGA(1),
    NOVEL(2),
    WEBTOON(3),
    AUDIOBOOK(4),
    UNKNOWN(-1);

    companion object {
        fun from(id: Int?) = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}
