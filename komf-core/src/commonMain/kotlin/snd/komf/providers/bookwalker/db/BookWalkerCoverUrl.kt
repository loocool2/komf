package snd.komf.providers.bookwalker.db

/**
 * Builds a cover URL from an `images.id`.
 *
 * The export stores no host or path, so the layout was derived from a live cover
 * URL. For image id `01KNST2FHJQ5RA6TR29RFFR7MV` the CDN serves:
 *
 * ```
 * https://img.sos-dan.net/600/01K/N/S/T2FHJQ5RA6TR29RFFR7MV.webp
 *                        └─┬─┘└┬┘ ┬ ┬ └────────┬──────────┘
 *                        size  │  │ │       remainder
 *                          id[0:3] │ id[4]
 *                              id[3]
 * ```
 *
 * The id is a 26-character ULID split positionally into `3 / 1 / 1 / rest`.
 * Across all 85,611 images, character 3 takes 16 distinct values and character 4
 * takes 32 — a two-level fan-out, which is what identifies this as positional
 * sharding rather than a fixed prefix. (Every id currently begins `01K`, since
 * they share a generation epoch, so that segment alone could not distinguish the
 * two.)
 *
 * Two fields in the `images` table are deliberately unused:
 *  - **`name`** never appears in the URL, so the inconsistent naming in the
 *    export (`coverImage_2546824.jpg` for one volume, `9781718323209.jpg` for
 *    another) does not matter.
 *  - **`mime`** says `image/jpeg` for all but two rows, but the CDN serves
 *    `.webp` regardless — it is a transcoding endpoint, not a file store.
 */
object BookWalkerCoverUrl {

    private const val HOST = "https://img.sos-dan.net"

    /**
     * Width in pixels. The CDN resizes on request; 600 is the value observed on
     * a volume page. Other buckets very likely exist but none were confirmed, so
     * this is left as the one known-good value rather than a guess at the
     * largest available.
     */
    private const val WIDTH = 600

    private const val ULID_LENGTH = 26

    fun build(imageId: String?): String? {
        if (imageId == null || imageId.length != ULID_LENGTH) return null
        val shard = "${imageId.substring(0, 3)}/${imageId[3]}/${imageId[4]}"
        return "$HOST/$WIDTH/$shard/${imageId.substring(5)}.webp"
    }
}
