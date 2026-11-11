package nl.giejay.android.tv.immich.shared.util

import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.android.tv.immich.api.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.UUID

class PortraitPairingTest {
    private val utc = ZoneId.of("UTC")
    private fun photo(
        id: String, people: List<Int> = emptyList(), time: String? = "2026-01-01T12:00:00Z",
        city: String? = null, portrait: Boolean = true, type: String = "IMAGE"
    ) = Asset(
        id = id, type = type, deviceAssetId = null,
        exifInfo = AssetExifInfo(null, 1, if (portrait) 750 else 1600, 1000, city, null,
            time?.let { Date.from(Instant.parse(it)) }, null, null),
        fileModifiedAt = Date.from(Instant.parse("2026-09-01T00:00:00Z")),
        albumName = null,
        people = people.map { Person(null, UUID(0, it.toLong()), "") },
        tags = null, originalPath = null, originalFileName = null
    )
    private fun List<Asset>.second(zone: ZoneId = utc) = pairPortraitAssets(false, zone).first().second?.id

    @Test fun `nearest capture time wins not furthest or file modification time`() {
        val assets = listOf(photo("first", listOf(1)),
            photo("far", listOf(1), "2026-01-01T18:00:00Z"),
            photo("near", listOf(1), "2026-01-01T12:01:00Z"))
        assertEquals("near", assets.second())
    }
    @Test fun `full person set matches independent of server ordering`() {
        assertEquals("same", listOf(photo("first", listOf(1, 2)),
            photo("different", listOf(1, 3)), photo("same", listOf(2, 1))).second())
    }
    @Test fun `duplicate person entries do not change matching`() {
        assertEquals("same", listOf(photo("first", listOf(1, 1)),
            photo("different", listOf(1, 2)), photo("same", listOf(1))).second())
    }
    @Test fun `shared people beat unrelated photos`() {
        assertEquals("shared", listOf(photo("first", listOf(1, 2)),
            photo("unrelated", listOf(3, 4)), photo("shared", listOf(2, 3))).second())
    }
    @Test fun `same capture day wins before city`() {
        assertEquals("day", listOf(photo("first", city = "Example City"),
            photo("city", time = "2026-01-02T00:00:00Z", city = "Example City"),
            photo("day", time = "2026-01-01T23:59:00Z", city = "Other City")).second())
    }
    @Test fun `city matching ignores case and surrounding spaces`() {
        assertEquals("city", listOf(photo("first", city = "Example City"),
            photo("different", city = "Other City"), photo("city", city = " example CITY ")).second())
    }
    @Test fun `missing or blank city is not a shared location`() {
        assertEquals("near", listOf(photo("first", city = " "),
            photo("far", time = "2026-01-01T20:00:00Z", city = ""),
            photo("near", time = "2026-01-01T12:01:00Z", city = "Somewhere")).second())
    }
    @Test fun `capture day uses supplied timezone`() {
        val assets = listOf(photo("first", time = "2026-01-01T23:30:00Z"),
            photo("utc-day", time = "2026-01-01T22:00:00Z", city = "Somewhere"),
            photo("local-day", time = "2026-01-02T00:01:00Z"))
        assertEquals("utc-day", assets.second())
        assertEquals("local-day", assets.second(ZoneId.of("Asia/Shanghai")))
    }
    @Test fun `creation date is fallback but modification date is ignored`() {
        val first = photo("first", time = null).copy(fileCreatedAt = Date(1000))
        val far = photo("far", time = null).copy(fileCreatedAt = Date(100000))
        val near = photo("near", time = null).copy(fileCreatedAt = Date(1001))
        assertEquals("near", listOf(first, far, near).second())
        assertEquals("first-candidate", listOf(photo("first", time = null),
            photo("first-candidate", time = null).copy(fileModifiedAt = Date(0)),
            photo("second-candidate", time = null)).second())
    }
    @Test fun `equally ranked photos preserve candidate order deterministically`() {
        val assets = listOf(photo("first"), photo("b"), photo("a"))
        assertEquals("b", assets.second())
        assertEquals(assets.pairPortraitAssets(false, utc), assets.pairPortraitAssets(false, utc))
    }
    @Test fun `ordered browsing pairs only adjacent portraits without crossing landscape`() {
        val input = listOf(photo("a"), photo("b"), photo("land", portrait = false), photo("c"))
        val pairs = input.pairPortraitAssets(true, utc)
        assertEquals(listOf("a", "land", "c"), pairs.map { it.first.id })
        assertEquals("b", pairs[0].second?.id)
        assertNull(pairs[1].second)
        assertNull(pairs[2].second)
        assertEquals(input.map { it.id }, pairs.flatMap { listOfNotNull(it.first.id, it.second?.id) })
    }
    @Test fun `video is never paired even with portrait dimensions`() {
        val pairs = listOf(photo("video", type = "VIDEO"), photo("a"), photo("b")).pairPortraitAssets(false, utc)
        assertEquals("video", pairs[0].first.id)
        assertNull(pairs[0].second)
        assertEquals("b", pairs[1].second?.id)
    }
    @Test fun `duplicate server IDs are shown once and odd portrait remains`() {
        val input = listOf(photo("a"), photo("a"), photo("b"), photo("c"), photo("land", portrait = false))
        for (ordered in listOf(true, false)) {
            val pairs = input.pairPortraitAssets(ordered, utc)
            val ids = pairs.flatMap { listOfNotNull(it.first.id, it.second?.id) }
            assertEquals(setOf("a", "b", "c", "land"), ids.toSet())
            assertEquals(4, ids.size)
        }
    }
    @Test fun `empty and singleton inputs work`() {
        assertEquals(emptyList<PortraitAssetPair>(), emptyList<Asset>().pairPortraitAssets(false, utc))
        assertNull(listOf(photo("a")).pairPortraitAssets(false, utc).single().second)
    }
    @Test fun `lookahead is bounded and does not lose later assets`() {
        val input = listOf(photo("first", listOf(1))) + (1..64).map { photo("$it", listOf(2)) } + photo("beyond", listOf(1))
        val pairs = input.pairPortraitAssets(false, utc)
        assertFalse(pairs.first().second?.id == "beyond")
        val ids = pairs.flatMap { listOfNotNull(it.first.id, it.second?.id) }
        assertEquals(input.size, ids.size)
        assertEquals(input.map { it.id }.toSet(), ids.toSet())
    }
    @Test fun `smart pairing preserves slide anchors and never shuffles the album`() {
        val assets = listOf(photo("a", listOf(1)), photo("land", portrait = false),
            photo("b", listOf(2)), photo("match", listOf(1)), photo("tail", portrait = false))
        val pairs = assets.pairPortraitAssets(false, utc)
        assertEquals(listOf("a", "land", "b", "tail"), pairs.map { it.first.id })
        assertEquals("match", pairs.first().second?.id)
    }
    @Test fun `extreme date distances cannot overflow and win a match`() {
        val first = photo("first", time = null).copy(fileCreatedAt = Date(Long.MIN_VALUE))
        val far = photo("far", time = null).copy(fileCreatedAt = Date(Long.MAX_VALUE))
        val near = photo("near", time = null).copy(fileCreatedAt = Date(Long.MIN_VALUE + 1000))
        assertEquals("near", listOf(first, far, near).second())
    }
    @Test fun `large album emits each asset exactly once`() {
        val input = (0 until 10_001).map { photo("$it", listOf(it % 7)) }
        val pairs = input.pairPortraitAssets(false, utc)
        val ids = pairs.flatMap { listOfNotNull(it.first.id, it.second?.id) }
        assertEquals(5001, pairs.size)
        assertEquals(input.size, ids.size)
        assertEquals(input.size, ids.toSet().size)
    }
}
