package nl.giejay.android.tv.immich.update

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UpdateRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val apk = byteArrayOf(1, 7, 9, 20)
    private val digest = UpdateRepository.hex(MessageDigest.getInstance("SHA-256").digest(apk))
    private val assetUrl = "https://github.com/z979054461/Immich-Android-TV/releases/download/v1.3.0-private/ImmichTV-Private-1.3.0.apk"
    private val candidate = UpdateCandidate("1.3.0-private", 10300, assetUrl, apk.size.toLong(), digest)
    private val redirected = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abcdef?synthetic=signature"

    private fun release(version: String = "1.3.0-private", draft: Boolean = false,
                        assets: String = asset()): String = """{"tag_name":"v$version","draft":$draft,"prerelease":true,"assets":[$assets]}"""
    private fun asset(url: String = assetUrl, hash: String = "sha256:$digest", size: Long = apk.size.toLong()): String =
        """{"name":"ImmichTV-Private-1.3.0.apk","browser_download_url":"$url","size":$size,"digest":"$hash","state":"uploaded"}"""

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IOException) { }
    }

    @Test fun privateSemverIsNumericCanonicalAndBounded() {
        assertEquals(10999L, UpdateRepository.versionCode("1.9.99-private"))
        assertEquals(11000L, UpdateRepository.versionCode("1.10.0-private"))
        listOf("1.100.0-private", "1.0.100-private", "01.2.0-private", "1.02.0-private",
            "1.2-private", "1.2.0", "1.2.0-beta", "1.2.0-private+1", "-1.0.0-private",
            "2147483648.0.0-private", "v1.2.0-private").forEach { assertNull(it, UpdateRepository.versionCode(it)) }
    }

    @Test fun prereleasesAreSelectedButDraftEqualAndDowngradeAreNot() {
        assertEquals(candidate, UpdateRepository.parseCandidates("[${release()}]", 10200))
        assertNull(UpdateRepository.parseCandidates("[${release()}]", 10300))
        assertNull(UpdateRepository.parseCandidates("[${release()}]", 10400))
        assertNull(UpdateRepository.parseCandidates("[${release(draft = true)}]", 10200))
        assertNull(UpdateRepository.parseCandidates("[${release(version = "1.3.0")}]", 10200))
    }

    @Test fun onlyOneUploadedApkWithSizeDigestAndExactRepositoryIsEligible() {
        val bad = listOf(
            asset(hash = ""), asset(hash = "sha256:bad"), asset(hash = "sha512:$digest"),
            asset(size = 0), asset(size = UpdateRepository.MAX_APK + 1),
            asset(url = assetUrl.replace("z979054461", "someone")),
            asset(url = assetUrl.replace("v1.3.0-private", "v1.4.0-private")),
            asset(url = "$assetUrl?token=secret"), asset(url = "$assetUrl#fragment"),
            asset(url = assetUrl.replace("https://", "http://")),
            asset().replace("uploaded", "new"), asset() + "," + asset()
        )
        bad.forEach { assertNull(it, UpdateRepository.parseCandidates("[${release(assets = it)}]", 10200)) }
        // GitHub automatic archives and unrelated text assets do not become APK candidates.
        assertEquals(candidate, UpdateRepository.parseCandidates("[${release(assets = asset() + ",{\"name\":\"source.zip\"}")}]", 10200))
    }

    @Test fun releaseListIsBoundedAndRequiresArray() {
        rejected { UpdateRepository.parseCandidates("{}", 0) }
        rejected { UpdateRepository.parseCandidates("[" + List(101) { release() }.joinToString(",") + "]", 0) }
        rejected { UpdateRepository.parseCandidates(" ".repeat(UpdateRepository.MAX_JSON.toInt() + 1), 0) }
    }

    @Test fun redirectsAreExactHttpsReleaseAssetHostOnly() {
        UpdateRepository.requireRedirect(assetUrl.toHttpUrl(), redirected.toHttpUrl())
        listOf(
            redirected.replace("https://", "http://"),
            redirected.replace("release-assets.githubusercontent.com", "evil.release-assets.githubusercontent.com"),
            redirected.replace("release-assets.githubusercontent.com", "raw.githubusercontent.com"),
            redirected.replace("release-assets.githubusercontent.com", "api.github.com"),
            redirected.replace("release-assets.githubusercontent.com", "release-assets.githubusercontent.com:444"),
            redirected.replace("https://", "https://name:secret@"),
            "$redirected#fragment", "https://release-assets.githubusercontent.com/arbitrary/file"
        ).forEach { bad -> rejected { UpdateRepository.requireRedirect(assetUrl.toHttpUrl(), bad.toHttpUrl()) } }
    }

    @Test fun productionClientHasNoCookiesCredentialsProxyCacheOrAutomaticRedirects() {
        val repository = UpdateRepository()
        val field = UpdateRepository::class.java.getDeclaredField("calls").apply { isAccessible = true }
        val client = field.get(repository) as OkHttpClient
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.callTimeoutMillis in 1..120000)
    }

    private fun localRepository(server: MockWebServer, seen: MutableList<Request> = mutableListOf()): UpdateRepository {
        // Rewrite only in the injected test factory; production never accepts loopback or a custom host.
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                synchronized(seen) { seen.add(chain.request()) }
                chain.proceed(chain.request().newBuilder().url(server.url("/synthetic")).build())
            }.build()
        return UpdateRepository(client)
    }

    @Test fun checkMakesOneTokenFreeRequestAndNeverFollowsMetadataRedirect() {
        MockWebServer().use { server ->
            val seen = mutableListOf<Request>()
            val repository = localRepository(server, seen)
            server.enqueue(MockResponse().setBody("[${release()}]").addHeader("Set-Cookie", "credential=synthetic"))
            assertEquals(candidate, repository.check(10200))
            val request = seen.single()
            assertEquals(UpdateRepository.RELEASES, request.url.toString())
            assertEquals(setOf("Accept", "User-Agent"), request.headers.names())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", redirected))
            rejected { repository.check(10200) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun downloadVerifiesExactBytesAndFollowsOnlyApprovedRedirect() {
        MockWebServer().use { server ->
            val seen = mutableListOf<Request>()
            val repository = localRepository(server, seen)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", redirected))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)))
            val file = temporary.root.resolve("verified.apk")
            repository.download(candidate, file)
            assertArrayEquals(apk, file.readBytes())
            assertEquals(listOf(assetUrl, redirected), seen.map { it.url.toString() })
            assertTrue(seen.all { it.header("Authorization") == null && it.header("Cookie") == null && it.header("Referer") == null })
        }
    }

    @Test fun wrongHashWrongSizeAndHostFailuresDeletePartialFiles() {
        MockWebServer().use { server ->
            val repository = localRepository(server)
            val file = temporary.root.resolve("partial.apk")
            server.enqueue(MockResponse().setBody("xxxx"))
            rejected { repository.download(candidate, file) }
            assertFalse(file.exists())
            server.enqueue(MockResponse().setBody("longer-than-expected"))
            rejected { repository.download(candidate, file) }
            assertFalse(file.exists())
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.invalid/secret"))
            rejected { repository.download(candidate, file) }
            assertFalse(file.exists())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun noOverwriteAndTerminalCancelPreventAnyRequest() {
        MockWebServer().use { server ->
            val repository = localRepository(server)
            val file = temporary.newFile("existing.apk").apply { writeText("preserve") }
            rejected { repository.download(candidate, file) }
            assertEquals("preserve", file.readText())
            repository.cancel()
            rejected { repository.check(0) }
            rejected { repository.download(candidate, temporary.root.resolve("cancelled.apk")) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun cancellationInterruptsInFlightReadAndRemovesPartialFile() {
        MockWebServer().use { server ->
            val repository = localRepository(server)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)).throttleBody(1, 1, TimeUnit.SECONDS))
            val file = temporary.root.resolve("cancel.apk")
            val finished = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            val thread = Thread {
                try { repository.download(candidate, file) } catch (failure: Throwable) { error.set(failure) }
                finally { finished.countDown() }
            }
            thread.start()
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            rejected { repository.check(0) } // Repeated taps cannot start a concurrent request.
            repository.cancel()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue(error.get() is IOException)
            assertFalse(file.exists())
            rejected { repository.check(0) }
        }
    }

    @Test fun assetRedirectLoopsAreBoundedAndNeverLeaveApprovedHost() {
        MockWebServer().use { server ->
            val repository = localRepository(server)
            repeat(4) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", redirected)) }
            val file = temporary.root.resolve("loop.apk")
            rejected { repository.download(candidate, file) }
            assertFalse(file.exists())
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun highestNumericVersionWinsRegardlessOfResponseOrder() {
        val newer = release().replace("1.3.0", "1.10.0")
        val older = release().replace("1.3.0", "1.9.0")
        assertEquals(11000L, UpdateRepository.parseCandidates("[$older,$newer]", 10200)?.versionCode)
        assertEquals(11000L, UpdateRepository.parseCandidates("[$newer,$older]", 10200)?.versionCode)
    }

    @Test fun oversizedChunkedMetadataAndHttpErrorsAreSanitized() {
        MockWebServer().use { server ->
            val repository = localRepository(server)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(UpdateRepository.MAX_JSON.toInt() + 1), 8192))
            rejected { repository.check(0) }
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret server error"))
            try { repository.check(0); fail() } catch (failure: IOException) {
                assertFalse(failure.toString().contains("secret"))
                assertNull(failure.cause)
            }
        }
    }
}
