package nl.giejay.android.tv.immich.api.service

import kotlinx.coroutines.runBlocking
import nl.giejay.android.tv.immich.api.model.toTimelineAssets
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Synthetic fixture based on immich-app/immich v3.3.5 time-bucket.dto.ts and enum.ts. */
class TimelineV335ContractTest {
    @Test
    fun `timeline requests match v335 routes and response types`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/api/"))
                .addConverterFactory(GsonConverterFactory.create()).build()
                .create(ApiService::class.java)
            server.enqueue(MockResponse().setBody("""[{"timeBucket":"2026-07-01","count":1}]"""))
            val bucket = api.getTimeBuckets().body()!!.single()
            val indexUrl = server.takeRequest().requestUrl!!
            assertEquals("/api/timeline/buckets", indexUrl.encodedPath)
            assertEquals("desc", indexUrl.queryParameter("order"))
            assertEquals("timeline", indexUrl.queryParameter("visibility"))
            server.enqueue(MockResponse().setBody("""{
                "id":["synthetic-video"],"ownerId":["synthetic-owner"],"ratio":[1.5],
                "isFavorite":[false],"visibility":["timeline"],"isTrashed":[false],
                "isImage":[false],"thumbhash":[null],"createdAt":["2026-07-01T12:00:00Z"],
                "fileCreatedAt":["2026-07-01T10:00:00.000"],"localOffsetHours":[5.5],
                "duration":[48113],"projectionType":[null],"livePhotoVideoId":[null]
            }"""))
            val asset = api.getTimeBucket(bucket.timeBucket).body()!!.toTimelineAssets().single()
            val assetUrl = server.takeRequest().requestUrl!!
            assertEquals("/api/timeline/bucket", assetUrl.encodedPath)
            assertEquals("2026-07-01", assetUrl.queryParameter("timeBucket"))
            assertEquals("desc", assetUrl.queryParameter("order"))
            assertEquals("timeline", assetUrl.queryParameter("visibility"))
            assertEquals("48113", asset.duration)
            assertEquals(5.5, asset.localOffsetHours, 0.0)
        } finally {
            server.shutdown()
        }
    }
}
