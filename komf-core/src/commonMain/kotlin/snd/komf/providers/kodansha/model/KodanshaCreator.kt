package snd.komf.providers.kodansha.model

/**
 * A single credited creator, parsed from the "By ..." byline on the series/volume pages.
 * The site does not expose per-creator roles, so [role] is left null and callers default
 * to writer.
 */
data class KodanshaCreator(
    val name: String,
    val role: String? = null,
)
