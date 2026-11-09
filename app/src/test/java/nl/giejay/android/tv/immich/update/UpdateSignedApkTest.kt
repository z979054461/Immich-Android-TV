package nl.giejay.android.tv.immich.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.android.apksig.ApkVerifier
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Public, fixed release fixture only. No signing keys or binary fixtures belong in this repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
class UpdateSignedApkTest {
    private lateinit var fixture: File
    private lateinit var context: Context
    private lateinit var archive: PackageInfo
    private lateinit var installed: PackageInfo
    private lateinit var candidate: UpdateCandidate
    private val real = ApplicationProvider.getApplicationContext<Context>()

    @Before @Suppress("DEPRECATION")
    fun setUp() {
        val supplied = System.getenv("IMMICH_UPDATE_TEST_APK")
        assertFalse("IMMICH_UPDATE_TEST_APK must name the fixed public 1.1.1 APK; missing fixtures must not skip signature tests",
            supplied.isNullOrBlank())
        fixture = File(supplied!!)
        assertTrue("IMMICH_UPDATE_TEST_APK must be an existing readable file", fixture.isFile && fixture.canRead())
        assertEquals("Wrong public APK fixture: verify the pinned SHA256 before running tests", FIXTURE_SHA256, hash(fixture))
        candidate = UpdateCandidate("1.1.1-private", 10101,
            "https://github.com/z979054461/Immich-Android-TV/releases/download/v1.1.1-private/ImmichTV-Private-1.1.1.apk",
            fixture.length(), FIXTURE_SHA256)
        archive = PackageInfo().apply {
            packageName = UpdateApkVerifier.PACKAGE
            versionName = "1.1.1-private"
            versionCode = 10101
            applicationInfo = ApplicationInfo().apply { minSdkVersion = 24 }
        }
        installed = PackageInfo().apply {
            packageName = UpdateApkVerifier.PACKAGE
            versionCode = 10100
        }
        val manager = mock(PackageManager::class.java)
        `when`(manager.getPackageArchiveInfo(anyString(), eq(0))).thenReturn(archive)
        `when`(manager.getPackageInfo(UpdateApkVerifier.PACKAGE, 0)).thenReturn(installed)
        context = mock(Context::class.java)
        `when`(context.packageName).thenReturn(UpdateApkVerifier.PACKAGE)
        `when`(context.packageManager).thenReturn(manager)
        `when`(context.cacheDir).thenReturn(real.cacheDir)
        UpdatePendingStore.clear(context)
    }

    @Test fun realOriginalApkCryptographicallyVerifiesWithPinnedSigner() {
        val result = verifier(fixture).verify()
        assertTrue("The fixed release must cryptographically verify", result.isVerified)
        assertTrue(result.isVerifiedUsingV2Scheme || result.isVerifiedUsingV3Scheme)
        assertEquals(1, result.signerCertificates.size)
        assertEquals(UpdateApkVerifier.SIGNER_SHA256,
            hex(MessageDigest.getInstance("SHA-256").digest(result.signerCertificates.single().encoded)))
        UpdateApkVerifier.verify(context, fixture, candidate)
    }

    @Test fun alteredDexWithRecomputedDownloadHashFailsActualSignatureVerification() {
        val changed = File.createTempFile("tampered-release", ".apk", real.cacheDir)
        try {
            changed.writeBytes(tamperedDexBytes(fixture))
            val tamperedCandidate = candidate.copy(size = changed.length(), sha256 = hash(changed))
            assertNotEquals(candidate.sha256, tamperedCandidate.sha256)
            // This must pass. The rejection below must not merely be a download-hash mismatch.
            UpdateApkVerifier.verifyDigest(changed, tamperedCandidate)
            assertFalse("Original signing block must reject changed DEX contents", verifier(changed).verify().isVerified)
            rejected { UpdateApkVerifier.verify(context, changed, tamperedCandidate) }
        } finally { changed.delete() }
    }

    @Test @Suppress("DEPRECATION")
    fun validSignatureDoesNotBypassWrongPackageOrVersion() {
        archive.packageName = "other.package"
        rejected { UpdateApkVerifier.verify(context, fixture, candidate) }
        archive.packageName = UpdateApkVerifier.PACKAGE
        archive.versionName = "1.1.2-private"
        rejected { UpdateApkVerifier.verify(context, fixture, candidate) }
        archive.versionName = candidate.versionName
        archive.versionCode = 10102
        rejected { UpdateApkVerifier.verify(context, fixture, candidate) }
    }

    @Test @Suppress("DEPRECATION")
    fun validSignatureDoesNotBypassInstalledVersionDowngradeGuard() {
        installed.versionCode = 10101
        rejected { UpdateApkVerifier.verify(context, fixture, candidate) }
        installed.versionCode = 10200
        rejected { UpdateApkVerifier.verify(context, fixture, candidate) }
    }

    @Test fun pendingStoreRoundTripUsesRealSignatureChecksAndIndependentBytes() {
        try {
            UpdatePendingStore.persist(context, fixture, candidate)
            val loaded = UpdatePendingStore.load(context)
            assertNotNull("A verified pending release should be reusable offline", loaded)
            assertEquals(candidate, loaded!!.second)
            assertNotEquals(fixture.canonicalPath, loaded.first.canonicalPath)
            assertEquals(FIXTURE_SHA256, hash(loaded.first))
            UpdateApkVerifier.verify(context, loaded.first, loaded.second)
        } finally { UpdatePendingStore.clear(context) }
    }

    @Test fun pendingStoreRejectsTamperingEvenWithRewrittenHashMetadata() {
        try {
            UpdatePendingStore.persist(context, fixture, candidate)
            val directory = File(context.cacheDir, "update_pending/current")
            val apk = File(directory, "update.apk")
            val metadata = File(directory, "candidate.json")
            val changed = tamperedDexBytes(apk)
            assertTrue(apk.setWritable(true, true))
            apk.writeBytes(changed)
            assertTrue(apk.setReadOnly())
            val json = JsonParser.parseString(metadata.readText()).asJsonObject
            json.addProperty("sha256", hash(apk))
            assertTrue(metadata.setWritable(true, true))
            metadata.writeText(json.toString())
            assertTrue(metadata.setReadOnly())
            // The exact replacement bytes match metadata but no longer match the APK signature.
            UpdateApkVerifier.verifyDigest(apk, candidate.copy(sha256 = hash(apk)))
            assertNull(UpdatePendingStore.load(context))
            assertFalse(directory.exists())
        } finally { UpdatePendingStore.clear(context) }
    }

    @Test fun installerPreparationVerifiesAndFreezesTheRealReleaseAgain() {
        val prepared = UpdateInstaller.prepare(context, fixture, candidate)
        try {
            assertEquals(FIXTURE_SHA256, hash(prepared.file))
            assertNotEquals(fixture.canonicalPath, prepared.file.canonicalPath)
            UpdateApkVerifier.verify(context, prepared.file, candidate)
        } finally { UpdateInstaller.discard(prepared) }
        assertFalse(prepared.file.exists())
    }

    private fun verifier(file: File) = ApkVerifier.Builder(file)
        .setMinCheckedPlatformVersion(24).setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build()

    /** Flip one DEX payload byte in-place, retaining the ZIP directory and original APK signing block. */
    private fun tamperedDexBytes(file: File): ByteArray {
        val bytes = file.readBytes()
        val name = "classes.dex".toByteArray(Charsets.US_ASCII)
        val compressed = ZipFile(file).use { it.getEntry("classes.dex").compressedSize }
        assertTrue("Fixture requires a nonempty DEX payload", compressed > 128)
        for (offset in 0 until bytes.size - 30 - name.size) {
            if (bytes[offset] != 0x50.toByte() || bytes[offset + 1] != 0x4b.toByte() ||
                bytes[offset + 2] != 3.toByte() || bytes[offset + 3] != 4.toByte()) continue
            val nameLength = u16(bytes, offset + 26)
            val extraLength = u16(bytes, offset + 28)
            if (nameLength != name.size || !name.indices.all { bytes[offset + 30 + it] == name[it] }) continue
            val payload = offset + 30 + nameLength + extraLength
            val position = payload + (compressed / 2).toInt()
            assertTrue(position in bytes.indices)
            bytes[position] = (bytes[position].toInt() xor 1).toByte()
            return bytes
        }
        throw AssertionError("Fixture DEX local ZIP header was not found")
    }

    private fun u16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected verification rejection") }
        catch (expected: UpdateVerificationException) { assertEquals("Update verification failed", expected.message) }
    }
    private companion object {
        const val FIXTURE_SHA256 = "21153d9c46bf7865f3337af7e74a067f0b5bd7ff462ec53e1e693894d88630a3"
    }
}
