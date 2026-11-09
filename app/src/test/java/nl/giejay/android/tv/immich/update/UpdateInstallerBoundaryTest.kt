package nl.giejay.android.tv.immich.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateInstallerBoundaryTest {
    private val real = ApplicationProvider.getApplicationContext<Context>()
    private fun context(allowed: Boolean): Context {
        val context = mock(Context::class.java)
        val manager = mock(PackageManager::class.java)
        `when`(context.cacheDir).thenReturn(real.cacheDir)
        `when`(context.packageManager).thenReturn(manager)
        `when`(context.packageName).thenReturn(UpdateApkVerifier.PACKAGE)
        `when`(manager.canRequestPackageInstalls()).thenReturn(allowed)
        return context
    }
    private fun prepared(): UpdateInstaller.PreparedUpdate {
        val directory = File(real.cacheDir, "update_installs").apply { mkdirs() }
        val file = File.createTempFile("test-staged", ".apk", directory).apply { writeText("test") }
        return UpdateInstaller.PreparedUpdate(file,
            UpdateCandidate("1.3.0-private", 10300, "https://github.com/unused", 4, "0".repeat(64)),
            file.lastModified(), newSnapshot = true)
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected installer rejection") } catch (_: IOException) { }
    }

    @Test fun missingPermissionNeverLaunchesAndCannotRetryConsumedToken() {
        val context = context(false)
        val prepared = prepared()
        rejects { UpdateInstaller.launch(context, prepared) }
        assertTrue(prepared.consumed)
        assertFalse(prepared.file.exists())
        rejects { UpdateInstaller.launch(context, prepared) }
        verify(context, never()).startActivity(any(Intent::class.java))
    }

    @Test fun snapshotMutationNeverLaunches() {
        val context = context(true)
        val prepared = prepared()
        prepared.file.writeText("changed length")
        rejects { UpdateInstaller.launch(context, prepared) }
        assertFalse(prepared.file.exists())
        verify(context, never()).startActivity(any(Intent::class.java))
    }

    @Test fun staleCallbackDiscardNeverLaunches() {
        val context = context(true)
        val prepared = prepared()
        UpdateInstaller.discard(prepared)
        UpdateInstaller.discard(prepared)
        rejects { UpdateInstaller.launch(context, prepared) }
        assertFalse(prepared.file.exists())
        verify(context, never()).startActivity(any(Intent::class.java))
    }
}
