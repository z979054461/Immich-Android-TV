package nl.giejay.android.tv.immich.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateApkVerifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val content = "Synthetic update data, not an APK".toByteArray()
    private fun candidate(bytes: ByteArray = content) = UpdateCandidate("1.3.0-private", 10300,
        "https://github.com/z979054461/Immich-Android-TV/releases/download/v1.3.0-private/app.apk",
        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun file() = File.createTempFile("synthetic-update", ".apk", context.cacheDir).apply { writeBytes(content) }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected verification rejection") }
        catch (expected: UpdateVerificationException) { assertEquals("Update verification failed", expected.message) }
    }

    @Test fun digestAcceptsOnlyExactBytesAndLength() {
        val file = file()
        try {
            UpdateApkVerifier.verifyDigest(file, candidate())
            rejected { UpdateApkVerifier.verifyDigest(file, candidate().copy(size = content.size + 1L)) }
            rejected { UpdateApkVerifier.verifyDigest(file, candidate().copy(sha256 = "0".repeat(64))) }
            rejected { UpdateApkVerifier.verifyDigest(file, candidate().copy(sha256 = "../secret")) }
            rejected { UpdateApkVerifier.verifyDigest(file, candidate().copy(size = Long.MAX_VALUE)) }
        } finally { file.delete() }
    }

    @Test fun matchingDigestDoesNotMakeAnUnsignedApkTrusted() {
        val file = file()
        try { rejected { UpdateApkVerifier.verify(context, file, candidate()) } }
        finally { file.delete() }
    }

    @Test fun signatureFactsRejectInvalidLegacyMissingMultipleAndWrongSigners() {
        val pin = UpdateApkVerifier.SIGNER_SHA256
        UpdateApkVerifier.verifySignatureFacts(true, true, listOf(pin))
        rejected { UpdateApkVerifier.verifySignatureFacts(false, true, listOf(pin)) }
        rejected { UpdateApkVerifier.verifySignatureFacts(true, false, listOf(pin)) }
        rejected { UpdateApkVerifier.verifySignatureFacts(true, true, emptyList()) }
        rejected { UpdateApkVerifier.verifySignatureFacts(true, true, listOf(pin, pin)) }
        rejected { UpdateApkVerifier.verifySignatureFacts(true, true, listOf("0".repeat(64))) }
    }

    @Test fun identityRequiresExactPackageVersionAndUpgrade() {
        val c = candidate()
        UpdateApkVerifier.verifyIdentity(UpdateApkVerifier.PACKAGE, c.versionName, c.versionCode, 10200, c)
        rejected { UpdateApkVerifier.verifyIdentity("other.package", c.versionName, c.versionCode, 10200, c) }
        rejected { UpdateApkVerifier.verifyIdentity(UpdateApkVerifier.PACKAGE, "1.3.0", c.versionCode, 10200, c) }
        rejected { UpdateApkVerifier.verifyIdentity(UpdateApkVerifier.PACKAGE, c.versionName, 10301, 10200, c) }
        rejected { UpdateApkVerifier.verifyIdentity(UpdateApkVerifier.PACKAGE, c.versionName, c.versionCode, 10300, c) }
        rejected { UpdateApkVerifier.verifyIdentity(UpdateApkVerifier.PACKAGE, c.versionName, c.versionCode, 10400, c) }
    }

    @Test fun snapshotIsIndependentAndReadOnly() {
        val source = file()
        val snapshot = UpdateInstaller.snapshot(context, source, candidate())
        try {
            assertNotEquals(source.canonicalPath, snapshot.canonicalPath)
            source.writeText("changed after preparation")
            UpdateApkVerifier.verifyDigest(snapshot, candidate())
            assertEquals("update_installs", snapshot.parentFile!!.name)
        } finally { source.delete(); snapshot.delete() }
    }

    @Test fun failedSnapshotLeavesNoFile() {
        val source = file()
        val directory = File(context.cacheDir, "update_installs")
        val before = directory.list()?.toSet().orEmpty()
        try { rejected { UpdateInstaller.snapshot(context, source, candidate().copy(sha256 = "0".repeat(64))) } }
        finally { source.delete() }
        assertEquals(before, directory.list()?.toSet().orEmpty())
    }

    @Test fun unsignedPreparationCleansSnapshot() {
        val source = file()
        val directory = File(context.cacheDir, "update_installs")
        val before = directory.list()?.toSet().orEmpty()
        try { rejected { UpdateInstaller.prepare(context, source, candidate()) } }
        finally { source.delete() }
        assertEquals(before, directory.list()?.toSet().orEmpty())
    }

    @Test fun pendingRejectsUnsignedBytesAndMalformedMetadataOffline() {
        UpdatePendingStore.clear(context)
        val source = file()
        try { rejected { UpdatePendingStore.persist(context, source, candidate()) } }
        finally { source.delete() }
        assertNull(UpdatePendingStore.load(context))
        val current = File(context.cacheDir, "update_pending/current").apply { mkdirs() }
        File(current, "candidate.json").writeText("x".repeat(4097))
        assertNull(UpdatePendingStore.load(context))
        assertFalse(current.exists())
        current.mkdirs()
        File(current, "candidate.json").writeText("{\"schema\":1,\"server\":\"must not be accepted\"}")
        assertNull(UpdatePendingStore.load(context))
        assertFalse(current.exists())
        UpdatePendingStore.clear(context)
    }

    @Test fun pendingLoadRemovesAbandonedStagingWithoutNetworking() {
        UpdatePendingStore.clear(context)
        val stale = File(context.cacheDir, "update_pending/abandoned").apply { mkdirs() }
        File(stale, "update.apk").writeBytes(content)
        assertNull(UpdatePendingStore.load(context))
        assertFalse(stale.exists())
    }

    @Test fun discardInvalidatesPreparedTokenAndDeletesOnlyItsSnapshot() {
        val source = file()
        val copied = UpdateInstaller.snapshot(context, source, candidate())
        val token = UpdateInstaller.PreparedUpdate(copied, candidate(), copied.lastModified(), newSnapshot = true)
        UpdateInstaller.discard(token)
        assertTrue(token.consumed)
        assertFalse(copied.exists())
        assertTrue(source.exists())
        try {
            UpdateInstaller.launch(context, token)
            fail("Discarded token must never launch")
        } catch (_: java.io.IOException) { }
        source.delete()
    }

    @Test fun repeatedSameCandidateReusesSnapshotAndStaleTokensCannotDeleteIt() {
        val source = file()
        val original = UpdateInstaller.acquireSnapshot(context, source, candidate())
        val repeated = (1..5).map { UpdateInstaller.acquireSnapshot(context, source, candidate()) }
        try {
            repeated.forEach { assertEquals(original.file.canonicalPath, it.file.canonicalPath) }
            assertEquals(1, original.file.parentFile!!.listFiles()!!.size)
            UpdateInstaller.discard(original)
            assertTrue(original.file.exists())
            repeated.dropLast(1).forEach { UpdateInstaller.discard(it) }
            assertTrue(original.file.exists())
            UpdateInstaller.discard(repeated.last())
            assertFalse(original.file.exists())
        } finally {
            UpdateInstaller.discard(original)
            repeated.forEach { UpdateInstaller.discard(it) }
            source.delete()
        }
    }

    @Test fun preexistingHandoffIsReusedButNeverDeletedByStaleDiscard() {
        val source = file()
        // A disk snapshot without an in-memory record models a previous process/system handoff.
        val handedOff = UpdateInstaller.snapshot(context, source, candidate())
        try {
            repeat(5) {
                val token = UpdateInstaller.acquireSnapshot(context, source, candidate())
                assertEquals(handedOff.canonicalPath, token.file.canonicalPath)
                UpdateInstaller.discard(token)
                assertTrue(handedOff.exists())
            }
            assertEquals(1, handedOff.parentFile!!.listFiles()!!.size)
        } finally { handedOff.delete(); source.delete() }
    }

    @Test fun cleanupCannotDeleteAnOldSnapshotWithLiveReferences() {
        val source = file()
        val token = UpdateInstaller.acquireSnapshot(context, source, candidate())
        try {
            token.file.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000)
            UpdateInstaller.cleanup(context)
            assertTrue(token.file.exists())
        } finally { UpdateInstaller.discard(token); source.delete() }
    }

    @Test fun installerStagingIsBoundedWithoutDeletingActiveHandoffs() {
        val source = file()
        val first = UpdateInstaller.snapshot(context, source, candidate())
        val second = UpdateInstaller.snapshot(context, source, candidate())
        try {
            try { UpdateInstaller.snapshot(context, source, candidate()); fail("Capacity must reject") }
            catch (_: java.io.IOException) { }
            assertTrue(first.exists()); assertTrue(second.exists())
        } finally { first.delete(); second.delete(); source.delete() }
    }

    @Test fun cleanupKeepsRecentInstallerFilesAndUnrelatedFiles() {
        val directory = File(context.cacheDir, "update_installs").apply { mkdirs() }
        val old = File(directory, "00000000-0000-0000-0000-000000000001.apk").apply {
            writeText("old"); setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        }
        val recent = File(directory, "00000000-0000-0000-0000-000000000002.apk").apply { writeText("recent") }
        val unrelated = File(directory, "unrelated").apply { writeText("other") }
        try {
            UpdateInstaller.cleanup(context)
            assertFalse(old.exists()); assertTrue(recent.exists()); assertTrue(unrelated.exists())
        } finally { old.delete(); recent.delete(); unrelated.delete() }
    }
}
