package nl.giejay.android.tv.immich.update

import android.app.Activity
import android.app.AlertDialog
import nl.giejay.android.tv.immich.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ManualUpdateDialogTest {
    private class QueueExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) { tasks.add(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun openingAndDismissingNeverChecksNetwork() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val repository = mock(UpdateRepository::class.java)
        val queue = QueueExecutor()
        val owner = ManualUpdateDialog(host.get(), repository, queue).show()
        verifyNoInteractions(repository)
        owner.dismiss()
        queue.drain()
        ShadowLooper.idleMainLooper()
        verify(repository).cancel()
        verifyNoMoreInteractions(repository)
        assertTrue(queue.isShutdown)
        host.pause().stop().destroy()
    }

    @Test fun repeatedCheckIsSingleFlightAndResultAfterDismissCannotReviveDialog() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val repository = mock(UpdateRepository::class.java)
        val queue = QueueExecutor()
        val owner = ManualUpdateDialog(host.get(), repository, queue).show()
        queue.drain()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.performClick()
        button.performClick()
        assertFalse(button.isEnabled)
        queue.drain()
        verify(repository, times(1)).check(anyLong())
        // The result is queued on the main looper, then background/Back invalidates its owner.
        owner.dismiss()
        ShadowLooper.idleMainLooper()
        assertFalse(dialog.isShowing)
        verify(repository).cancel()
        queue.drain()
        host.pause().stop().destroy()
    }

    @Test fun checkAndDownloadRequireSeparateClicksAndNewDialogDoesNotResume() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val repository = mock(UpdateRepository::class.java)
        `when`(repository.check(anyLong())).thenReturn(UpdateCandidate("1.3.0-private",10300,
            "https://github.com/z979054461/Immich-Android-TV/releases/download/v1.3.0-private/a.apk",1,"0".repeat(64)))
        val queue = QueueExecutor()
        val owner = ManualUpdateDialog(host.get(), repository, queue).show()
        queue.drain()
        ShadowLooper.idleMainLooper()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        queue.drain()
        ShadowLooper.idleMainLooper()
        assertEquals(host.get().getString(R.string.update_download),
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        verify(repository).check(anyLong())
        verifyNoMoreInteractions(repository)
        owner.dismiss()
        queue.drain()
        val replacement = mock(UpdateRepository::class.java)
        val newQueue = QueueExecutor()
        val next = ManualUpdateDialog(host.get(), replacement, newQueue).show()
        newQueue.drain()
        ShadowLooper.idleMainLooper()
        assertEquals(host.get().getString(R.string.update_check),
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        verifyNoInteractions(replacement)
        next.dismiss()
        newQueue.drain()
        host.pause().stop().destroy()
    }
    @Test fun preparedCallbackQueuedBeforeOwnerDismissIsDiscardedWithoutCompletion() {
        assertPreparedCallbackDiscarded { owner, _ -> owner.dismiss() }
    }

    @Test fun preparedCallbackQueuedBeforeBackCancelIsDiscardedWithoutCompletion() {
        assertPreparedCallbackDiscarded { _, dialog -> dialog.cancel() }
    }

    private fun assertPreparedCallbackDiscarded(dismiss: (ManualUpdateDialog, AlertDialog) -> Unit) {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val queue = QueueExecutor()
        val owner = ManualUpdateDialog(host.get(), mock(UpdateRepository::class.java), queue).show()
        queue.drain()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val staged = java.io.File.createTempFile("stale-prepared", ".apk", host.get().cacheDir)
        val candidate = UpdateCandidate("1.3.0-private", 10300,
            "https://github.com/z979054461/Immich-Android-TV/releases/download/v1.3.0-private/a.apk", 0, "0".repeat(64))
        val prepared = UpdateInstaller.PreparedUpdate(staged, candidate, staged.lastModified(), newSnapshot = true)
        var completed = false
        val operation: () -> UpdateInstaller.PreparedUpdate = { prepared }
        val completion: (UpdateInstaller.PreparedUpdate) -> Unit = { completed = true }
        val work = ManualUpdateDialog::class.java.getDeclaredMethod("work", Int::class.javaPrimitiveType,
            kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function1::class.java)
        work.isAccessible = true
        work.invoke(owner, R.string.update_verifying, operation, completion)
        queue.drain() // Worker has finished, but delivery remains queued before the dismiss message.
        dismiss(owner, dialog)
        ShadowLooper.idleMainLooper()
        assertFalse("A stale callback must not reach installer launch", completed)
        assertFalse("Unhanded prepared APK must be deleted", staged.exists())
        queue.drain()
        host.pause().stop().destroy()
    }

}
