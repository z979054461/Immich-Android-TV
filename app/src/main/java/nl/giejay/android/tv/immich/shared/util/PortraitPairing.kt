package nl.giejay.android.tv.immich.shared.util

import nl.giejay.android.tv.immich.api.model.Asset
import java.time.LocalDate
import java.time.ZoneId
import java.util.LinkedList
import java.util.Locale
import java.util.UUID

/** A slide contains one asset or two portrait images. No network or preference access. */
internal data class PortraitAssetPair(val first: Asset, val second: Asset? = null)

/**
 * Keep ordered browsing strictly in order. In an unordered slideshow, prefer matching full
 * person sets, then shared people, capture day, city and nearest capture time. Matching uses
 * at most [PORTRAIT_LOOKAHEAD] remaining assets per slide, avoiding quadratic library scans.
 * Missing metadata is not a match; creation time is a fallback, modification time is not.
 * Slide anchors retain input order; only the selected partner is moved next to its anchor.
 * Ties retain input order. A repeated server asset ID is shown only once.
 */
internal fun List<Asset>.pairPortraitAssets(
    keepOrder: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<PortraitAssetPair> {
    val unique = distinctBy { it.id }
    if (keepOrder) {
        val result = ArrayList<PortraitAssetPair>(unique.size)
        var index = 0
        while (index < unique.size) {
            val first = unique[index++]
            val second = unique.getOrNull(index)
            if (first.isPortraitImage() && second?.isPortraitImage() == true) {
                result.add(PortraitAssetPair(first, second))
                index++
            } else result.add(PortraitAssetPair(first))
        }
        return result
    }

    val result = ArrayList<PortraitAssetPair>(unique.size)
    val portraits = LinkedList<PortraitMetadata>()
    unique.forEach { asset -> portraits.add(PortraitMetadata.of(asset, zoneId)) }
    while (portraits.isNotEmpty()) {
        val first = portraits.removeFirst()
        if (!first.asset.isPortraitImage()) {
            result.add(PortraitAssetPair(first.asset))
            continue
        }
        var best: PortraitMetadata? = null
        var bestRank: PairRank? = null
        val candidates = portraits.iterator()
        var inspected = 0
        while (candidates.hasNext() && inspected++ < PORTRAIT_LOOKAHEAD) {
            val candidate = candidates.next()
            if (!candidate.asset.isPortraitImage()) continue
            val rank = first.rank(candidate)
            if (bestRank == null || rank > bestRank) {
                best = candidate
                bestRank = rank
            }
        }
        if (best != null) portraits.remove(best)
        result.add(PortraitAssetPair(first.asset, best?.asset))
    }
    return result
}

private const val PORTRAIT_LOOKAHEAD = 64

private data class PortraitMetadata(
    val asset: Asset,
    val people: Set<UUID>,
    val day: LocalDate?,
    val timestamp: Long?,
    val city: String?
) {
    fun rank(other: PortraitMetadata): PairRank = PairRank(
        exactPeople = people.isNotEmpty() && people == other.people,
        sharedPeople = people.count { it in other.people },
        sameDay = day != null && day == other.day,
        sameCity = city != null && city == other.city,
        distance = timestampDistance(timestamp, other.timestamp)
    )

    companion object {
        fun of(asset: Asset, zoneId: ZoneId): PortraitMetadata {
            val date = asset.exifInfo?.dateTimeOriginal ?: asset.fileCreatedAt
            return PortraitMetadata(
                asset,
                asset.people.orEmpty().mapTo(mutableSetOf()) { it.id },
                date?.toInstant()?.atZone(zoneId)?.toLocalDate(),
                date?.time,
                asset.exifInfo?.city?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
            )
        }
    }
}

/** Higher is better; for equally related assets, smaller time distance wins. */
private data class PairRank(
    val exactPeople: Boolean,
    val sharedPeople: Int,
    val sameDay: Boolean,
    val sameCity: Boolean,
    val distance: Long
) : Comparable<PairRank> {
    override fun compareTo(other: PairRank): Int =
        compareValuesBy(this, other, { it.exactPeople }, { it.sharedPeople }, { it.sameDay }, { it.sameCity })
            .takeIf { it != 0 } ?: other.distance.compareTo(distance)
}

private fun timestampDistance(first: Long?, second: Long?): Long {
    if (first == null || second == null) return Long.MAX_VALUE
    val high = maxOf(first, second)
    val low = minOf(first, second)
    // Saturate deliberately rather than allowing abs(Long.MIN_VALUE) to win a match.
    return if (low < 0 && high > Long.MAX_VALUE + low) Long.MAX_VALUE else high - low
}
