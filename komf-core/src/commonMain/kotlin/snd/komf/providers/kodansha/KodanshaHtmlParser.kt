package snd.komf.providers.kodansha

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlinx.datetime.LocalDate
import snd.komf.providers.kodansha.model.KodanshaBook
import snd.komf.providers.kodansha.model.KodanshaCreator
import snd.komf.providers.kodansha.model.KodanshaSearchResult
import snd.komf.providers.kodansha.model.KodanshaSeries
import snd.komf.providers.kodansha.model.KodanshaSeriesVolume

/**
 * Parses the server-rendered HTML of kodansha.us series and volume pages.
 *
 * Selector strategy: the series page and the volume page use different BEM prefixes
 * (`series__single__*` vs `volume__hero__*` / `volume-info__*`), but they share two stable
 * anchor classes on every info block:
 *   - `kodansha--label` on each field label (Status, Rating, Pages, ISBN, ...)
 *   - `kodansha--tag`   on each individual genre/tag
 * We key off those shared anchors so one code path handles both pages, and we fall back to
 * OpenGraph `<meta>` tags for title/description/cover/isbn, which are the most stable part
 * of the markup. Verified live against the "Fungus and Iron" series and its Volume 9.
 */
object KodanshaHtmlParser {

    private val volumeNumberRegex = Regex("""/volume-(\d+)/?$""")
    private val bylineRegex = Regex("""^\s*By\s+(.+)$""", RegexOption.IGNORE_CASE)
    // Matches M/D/YYYY as shown in the "Digital Release" field, e.g. 7/21/2026
    private val usDateRegex = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""")

    fun parseSeries(
        slug: String,
        html: String,
        hint: KodanshaSearchResult? = null,
    ): KodanshaSeries {
        val doc = Ksoup.parse(html)

        val title = heroTitle(doc)
            ?: hint?.name
            ?: metaTitle(doc)
            ?: slug

        val description = heroDescription(doc)
            ?: meta(doc, "og:description")
            ?: hint?.shortDescription

        val info = infoItems(doc)

        val status = info["Status"]?.firstOrNull()
            ?: hint?.let { if (it.isComplete) "Completed" else "Ongoing" }

        val ageRating = info["Rating"]?.firstOrNull()?.let { parseAgeRating(it) }
            ?: hint?.ageRating?.rating

        val cover = meta(doc, "og:image") ?: hint?.image?.bestUrl()

        return KodanshaSeries(
            slug = slug,
            uuid = hint?.uuid,
            title = cleanTitle(title),
            description = description?.let { cleanText(it) },
            creators = creators(doc),
            genres = genres(doc, info),
            ageRating = ageRating,
            status = status,
            publisher = "Kodansha",
            coverUrl = cover,
            volumes = volumes(doc, cleanTitle(title)),
        )
    }

    fun parseBook(
        seriesSlug: String,
        volumeSlug: String,
        html: String,
    ): KodanshaBook {
        val doc = Ksoup.parse(html)

        val title = heroTitle(doc) ?: metaTitle(doc) ?: volumeSlug
        // Prefer the on-page hero description: the og:description meta tag on a volume page
        // carries the generic *series* blurb, not this volume's unique synopsis.
        val description = heroDescription(doc) ?: meta(doc, "og:description")
        val info = infoItems(doc)

        val number = volumeNumberRegex.find("/$volumeSlug/")?.groupValues?.get(1)?.toIntOrNull()

        val isbn = info["ISBN"]?.firstOrNull()?.filter { it.isLetterOrDigit() }
            ?.ifBlank { null }
            ?: meta(doc, "book:isbn")

        val releaseDate = (info["Digital Release"]?.firstOrNull()
            ?: info["Print Release"]?.firstOrNull()
            ?: info["Release Date"]?.firstOrNull())
            ?.let { parseUsDate(it) }

        return KodanshaBook(
            seriesSlug = seriesSlug,
            slug = volumeSlug,
            number = number,
            title = cleanTitle(title),
            description = description?.let { cleanText(it) },
            creators = creators(doc),
            genres = genres(doc, info),
            ageRating = info["Rating"]?.firstOrNull()?.let { parseAgeRating(it) },
            pageCount = info["Pages"]?.firstOrNull()?.filter { it.isDigit() }?.toIntOrNull(),
            releaseDate = releaseDate,
            isbn = isbn,
            coverUrl = heroCover(doc) ?: meta(doc, "og:image"),
        )
    }

    // ---- field helpers -----------------------------------------------------

    /**
     * Reads the label/value info fields. Both page types wrap each field's label in a
     * `kodansha--label` element and put the value in a sibling `*-info__value` element,
     * e.g.:
     *   <div class="series__single__series-info__info-item">
     *     <h3 class="series__single__series-info__info-item__title kodansha--label">Status</h3>
     *     <div class="series__single__series-info__value">Ongoing</div>
     *   </div>
     * The "Tags"/"Genres" field is skipped here (handled by [genres]).
     * Returns label -> list of values.
     */
    private fun infoItems(doc: Document): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for (label in doc.select(".kodansha--label")) {
            val name = label.text().trim()
            if (name.isEmpty() || name.equals("Tags", ignoreCase = true) ||
                name.equals("Genres", ignoreCase = true)
            ) continue

            val fromValue = label.parent()
                ?.select("[class*=info__value]")
                ?.map { it.text().trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            val values = fromValue.ifEmpty {
                listOfNotNull(label.nextElementSibling()?.text()?.trim()?.ifBlank { null })
            }
            if (values.isEmpty()) continue
            result.getOrPut(name) { mutableListOf() }.addAll(values)
        }
        return result
    }

    /**
     * Each genre is a `kodansha--tag` element inside the tags container, which carries the
     * `info__tags` substring on both pages (`volume-info__tags` and
     * `series-info__tags-section`). The container scope is important: the volume page's
     * "Digital Retailers" buttons reuse the bare `kodansha--tag` class, so an unscoped
     * selector would pull in retailer names. Falls back to a "Tags"/"Genres" label entry.
     */
    private fun genres(doc: Document, info: Map<String, List<String>>): List<String> {
        val tags = doc.select("[class*=info__tags] .kodansha--tag")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (tags.isNotEmpty()) return tags
        return info["Tags"] ?: info["Genres"] ?: emptyList()
    }

    /** Parses the "By Author One, Author Two" byline. */
    private fun creators(doc: Document): List<KodanshaCreator> {
        val raw = doc.selectFirst("[class*=__creator]")?.text()?.trim()
            ?: doc.select("p").firstOrNull { bylineRegex.matches(it.ownText().trim()) }?.ownText()?.trim()
            ?: return emptyList()

        val names = bylineRegex.find(raw)?.groupValues?.get(1) ?: raw
        return names.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { KodanshaCreator(it) }
    }

    /** All volume cards on the page (both the series "All Volumes" grid and the
     *  volume page "Other Volumes" strip use `/series/{slug}/volume-{n}/` links). */
    private fun volumes(doc: Document, seriesTitle: String): List<KodanshaSeriesVolume> {
        val seen = LinkedHashMap<String, KodanshaSeriesVolume>()
        for (link in doc.select("a[href*=/volume-]")) {
            val href = link.attr("href")
            val match = volumeNumberRegex.find(href.removeSuffix("/") + "/") ?: continue
            val number = match.groupValues[1].toIntOrNull()
            val volumeSlug = "volume-${match.groupValues[1]}"
            if (seen.containsKey(volumeSlug)) continue

            val cover = link.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?.ifBlank { null }
            seen[volumeSlug] = KodanshaSeriesVolume(
                slug = volumeSlug,
                number = number,
                name = if (number != null) "$seriesTitle Volume $number" else seriesTitle,
                coverUrl = cover,
            )
        }
        return seen.values.sortedBy { it.number ?: Int.MAX_VALUE }
    }

    /** The page's main title heading. Skips the site-logo `h1.site-title`. */
    private fun heroTitle(doc: Document): String? =
        doc.select("h1").firstOrNull { !it.hasClass("site-title") }
            ?.text()?.trim()?.ifBlank { null }

    /**
     * The unique on-page synopsis: `volume__hero__description` on a volume page,
     * `series__single__description` on a series page. Targeted explicitly because several
     * other blocks also carry a `*__description` class — the footer (`site-footer__description`)
     * and, on volume pages, the buy box (`volume__product-card__description`, which just says
     * "Purchase to read this volume online.").
     */
    private fun heroDescription(doc: Document): String? =
        doc.selectFirst("[class*=hero__description], [class*=single__description]")
            ?.wholeText()?.trim()?.ifBlank { null }

    private fun heroCover(doc: Document): String? =
        doc.selectFirst("img[class*=hero__cover]")?.attr("src")?.ifBlank { null }

    private fun metaTitle(doc: Document): String? = meta(doc, "og:title")

    private fun meta(doc: Document, property: String): String? =
        (doc.selectFirst("meta[property=$property]") ?: doc.selectFirst("meta[name=$property]"))
            ?.attr("content")?.trim()?.ifBlank { null }

    // ---- value parsing -----------------------------------------------------

    /** "16+" -> 16 */
    private fun parseAgeRating(value: String): Int? =
        value.filter { it.isDigit() }.ifBlank { null }?.toIntOrNull()

    private fun parseUsDate(value: String): LocalDate? {
        val m = usDateRegex.find(value) ?: return null
        val month = m.groupValues[1].toIntOrNull() ?: return null
        val day = m.groupValues[2].toIntOrNull() ?: return null
        val year = m.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate(year, month, day) }.getOrNull()
    }

    /** Drops the " | Kodansha" suffix that OpenGraph titles carry. */
    private fun cleanTitle(title: String): String =
        title.substringBeforeLast(" | Kodansha").trim()

    private fun cleanText(text: String): String =
        text.replace(" ", " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}