package nl.giejay.android.tv.immich.search

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import nl.giejay.android.tv.immich.api.ApiClientFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate

/** Synthetic fixtures only. Contract references: Immich v3.3.5 search.dto.ts/person.dto.ts,
 * search.service.ts and utils/database.ts. No live server, photos, people or credentials. */
class PhotoSearchContractTest {
    private val query = PhotoSearchQuery(start = LocalDate.of(2026, 1, 1))
    private suspend fun fixture(block: suspend (MockWebServer, PhotoSearchRepository) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val client = ApiClientFactory.getClient(false, "synthetic-test-key", false, server.url("/synthetic").toString())
            val service = Retrofit.Builder().baseUrl(server.url("/synthetic/api/"))
                .client(client).addConverterFactory(GsonConverterFactory.create()).build().create(PhotoSearchService::class.java)
            block(server, PhotoSearchRepository(service) { true })
        } finally { server.shutdown() }
    }
    private fun version(minor: Int = 3) = MockResponse().setBody("""{"major":3,"minor":$minor,"patch":5}""")
    private fun page(cursor: String) = MockResponse().setBody("""{
        "albums":{"total":0,"count":0,"items":[],"facets":[]},
        "assets":{"total":1,"count":1,"items":[{"id":"synthetic-asset","type":"IMAGE","fileCreatedAt":"2026-01-01T00:00:00Z"}],"facets":[],"nextPage":null,"nextCursor":$cursor}
    }""")

    @Test fun `v335 request uses new contract fixed page size and header only synthetic key`() = runBlocking {
        fixture { server, repository ->
            server.enqueue(version())
            server.enqueue(page("\"opaque/+==\""))
            val first = repository.search(query, null)
            assertEquals("synthetic-asset", first.items.single().id)
            assertEquals("opaque/+==", first.nextCursor)
            val capability = server.takeRequest()
            assertEquals("/synthetic/api/server/version", capability.path)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/synthetic/api/search/metadata", request.path)
            assertEquals("synthetic-test-key", request.getHeader("x-api-key"))
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(setOf("filter", "orderBy", "size"), json.keySet())
            assertEquals(100, json["size"].asInt)
            assertFalse(request.path!!.contains("key", ignoreCase = true))
            server.enqueue(page("null"))
            assertNull(repository.search(query, first.nextCursor).nextCursor)
            val second = server.takeRequest()
            assertEquals("opaque/+==", JsonParser.parseString(second.body.readUtf8()).asJsonObject["cursor"].asString)
            assertEquals(3, server.requestCount) // Capability is cached only inside this session repository.
        }
    }

    @Test fun `pre32 server cannot silently run an unfiltered fallback`() = runBlocking {
        fixture { server, repository ->
            server.enqueue(version(minor = 1))
            val failure = runCatching { repository.search(query, null) }.exceptionOrNull() as SearchException
            assertEquals(SearchFailure.UNSUPPORTED, failure.failure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `unknown version stops before metadata search`() = runBlocking {
        fixture { server, repository ->
            server.enqueue(MockResponse().setResponseCode(404))
            val failure = runCatching { repository.search(query, null) }.exceptionOrNull() as SearchException
            assertEquals(SearchFailure.VERSION_UNAVAILABLE, failure.failure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `missing nextCursor is not interpreted as a successful final page`() = runBlocking {
        fixture { server, repository ->
            server.enqueue(version())
            server.enqueue(MockResponse().setBody("""{"assets":{"items":[],"nextPage":"2"}}"""))
            val error = runCatching { repository.search(query, null) }.exceptionOrNull() as SearchException
            assertEquals(SearchFailure.INVALID_RESPONSE, error.failure)
        }
    }

    @Test fun `people request page and size and keep hasNextPage for unnamed pages`() = runBlocking {
        fixture { server, repository ->
            server.enqueue(MockResponse().setBody("""{"total":500,"hasNextPage":true,"people":[{"id":"11111111-1111-4111-8111-111111111111","name":"","thumbnailPath":"synthetic"}]}"""))
            val page = repository.people(1)
            assertTrue(page.hasNextPage)
            assertEquals(1, page.items.size)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/synthetic/api/people", url.encodedPath)
            assertEquals("1", url.queryParameter("page"))
            assertEquals("100", url.queryParameter("size"))
        }
    }

    @Test fun `person permission failure does not poison date search capability`() = runBlocking {
        fixture { server, repository ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("do not expose server error bodies"))
            val error = runCatching { repository.people(1) }.exceptionOrNull() as SearchException
            assertEquals(SearchFailure.PEOPLE_PERMISSION, error.failure)
            assertFalse(error.message!!.contains("server error bodies"))
            server.enqueue(version())
            server.enqueue(page("null"))
            assertEquals(1, repository.search(query, null).items.size)
        }
    }

    @Test fun `redirect response is not followed outside the configured API`() = runBlocking {
        fixture { server, repository ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://synthetic.invalid/redirect"))
            val error = runCatching { repository.people(1) }.exceptionOrNull() as SearchException
            assertEquals(SearchFailure.CONNECTION, error.failure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `ended session does not issue any request`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val service = Retrofit.Builder().baseUrl(server.url("/api/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(PhotoSearchService::class.java)
            val repository = PhotoSearchRepository(service) { false }
            assertTrue(runCatching { repository.search(query, null) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
