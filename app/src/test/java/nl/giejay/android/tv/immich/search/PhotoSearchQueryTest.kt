package nl.giejay.android.tv.immich.search

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class PhotoSearchQueryTest {
    private val ids = setOf(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222")
    )

    @Test fun `new structured DTO never mixes old page order visibility or date fields`() {
        val q = PhotoSearchQuery(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 9), ZoneId.of("Asia/Shanghai"), ids)
        val root = JsonParser.parseString(Gson().toJson(q.request())).asJsonObject
        assertEquals(setOf("filter", "orderBy", "size"), root.keySet())
        assertEquals(100, root["size"].asInt)
        val filter = root.getAsJsonObject("filter")
        assertEquals(setOf("takenAt", "personIds", "visibility"), filter.keySet())
        assertEquals("2026-11-07T16:00:00Z", filter.getAsJsonObject("takenAt")["gte"].asString)
        assertEquals("2026-11-09T16:00:00Z", filter.getAsJsonObject("takenAt")["lt"].asString)
        assertEquals("timeline", filter.getAsJsonObject("visibility")["eq"].asString)
        assertEquals(setOf("all"), filter.getAsJsonObject("personIds").keySet())
        assertEquals(2, filter.getAsJsonObject("personIds").getAsJsonArray("all").size())
        assertEquals("fileCreatedAt", root.getAsJsonObject("orderBy")["field"].asString)
        assertEquals("desc", root.getAsJsonObject("orderBy")["direction"].asString)
    }

    @Test fun `ANY emits only any and opaque cursor is preserved verbatim`() {
        val cursor = "opaque/+token=="
        val request = PhotoSearchQuery(people = ids, match = PeopleMatch.ANY).request(cursor)
        val json = JsonParser.parseString(Gson().toJson(request)).asJsonObject
        assertEquals(cursor, json["cursor"].asString)
        assertFalse(json.getAsJsonObject("filter").has("takenAt"))
        assertEquals(setOf("any"), json.getAsJsonObject("filter").getAsJsonObject("personIds").keySet())
    }

    @Test fun `inclusive local dates honor spring and autumn DST day lengths`() {
        for ((day, hours) in listOf("2026-03-08" to 23L, "2026-11-01" to 25L)) {
            val date = LocalDate.parse(day)
            val range = PhotoSearchQuery(date, date, ZoneId.of("America/New_York")).request().filter.takenAt!!
            assertEquals(hours, Duration.between(Instant.parse(range.gte), Instant.parse(range.lt)).toHours())
        }
    }

    @Test fun `one sided date bounds omit unused JSON fields`() {
        val start = PhotoSearchQuery(start = LocalDate.of(2026, 1, 1)).request().filter
        val json = JsonParser.parseString(Gson().toJson(start)).asJsonObject
        assertEquals(setOf("gte"), json.getAsJsonObject("takenAt").keySet())
        assertFalse(json.has("personIds"))
        val end = PhotoSearchQuery(endInclusive = LocalDate.of(2026, 1, 1)).request().filter.takenAt!!
        assertNull(end.gte)
        assertNotNull(end.lt)
    }

    @Test fun `no condition or reversed dates cannot start an unfiltered search`() {
        assertFalse(PhotoSearchQuery().hasCondition)
        assertTrue(runCatching { PhotoSearchQuery().request() }.isFailure)
        val query = PhotoSearchQuery(LocalDate.of(2026, 2, 2), LocalDate.of(2026, 1, 1))
        assertFalse(query.valid)
        assertTrue(runCatching { query.request() }.isFailure)
    }

    @Test fun `debug strings do not expose dates or person IDs`() {
        val query = PhotoSearchQuery(people = ids)
        assertEquals("PhotoSearchQuery(redacted)", query.toString())
        assertEquals("FilterSearchRequest(redacted)", query.request().toString())
    }
}
