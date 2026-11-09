package nl.giejay.android.tv.immich.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PrivateThumbnailRequestTest {
    private val server = "https://synthetic.invalid/photos"
    private val id = "00000000-0000-0000-0000-000000000001"
    private fun key(path: String) = PrivateThumbnailRequest.cacheKey(server, "$server$path".toHttpUrl())

    @Test fun onlyExplicitThumbnailEndpointsAndVariantsAreAllowed() {
        assertNotNull(key("/api/assets/$id/thumbnail?size=thumbnail&edited=false"))
        assertNotNull(key("/api/assets/$id/thumbnail?size=preview&edited=true"))
        assertNotNull(key("/api/assets/$id/thumbnail?size=thumbnail"))
        assertNotNull(key("/api/people/$id/thumbnail"))
    }

    @Test fun originalVideoArbitraryEndpointsAndQueriesNeverCache() {
        listOf(
            "/api/assets/$id/original",
            "/api/assets/$id/original?edited=true",
            "/api/assets/$id/video/playback",
            "/api/assets/$id/thumbnail?size=original",
            "/api/assets/$id/thumbnail?size=thumbnail&size=preview",
            "/api/assets/$id/thumbnail?size=thumbnail&edited=true&edited=false",
            "/api/assets/$id/thumbnail?size=thumbnail&edited=1",
            "/api/assets/$id/thumbnail?size=thumbnail&download=true",
            "/api/assets/$id/thumbnail?size=thumbnail&apiKey=synthetic",
            "/api/assets/$id/thumbnail",
            "/api/assets/$id/thumbnail/extra?size=thumbnail",
            "/api/people/$id/thumbnail?size=thumbnail",
            "/api/assets/1-1-1-1-1/thumbnail?size=thumbnail",
            "/api/assets/%30${id.drop(1)}/thumbnail?size=thumbnail",
            "/api/albums/$id/thumbnail?size=thumbnail",
            "/other/assets/$id/thumbnail?size=thumbnail",
        ).forEach { assertNull(it, key(it)) }
    }

    @Test fun noCrossOriginPrefixEscapeUserInfoFragmentsOrCredentials() {
        listOf(
            "https://other.invalid/photos/api/people/$id/thumbnail",
            "http://synthetic.invalid/photos/api/people/$id/thumbnail",
            "https://synthetic.invalid:444/photos/api/people/$id/thumbnail",
            "https://synthetic.invalid/api/people/$id/thumbnail",
            "https://synthetic.invalid/photos/api/../people/$id/thumbnail",
            "https://synthetic.invalid/photos/api/people/$id/thumbnail#fragment",
            "https://user:password@synthetic.invalid/photos/api/people/$id/thumbnail",
        ).forEach { assertNull(PrivateThumbnailRequest.cacheKey(server, it.toHttpUrl())) }
    }
}
