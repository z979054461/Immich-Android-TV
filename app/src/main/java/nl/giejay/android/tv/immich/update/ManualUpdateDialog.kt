package nl.giejay.android.tv.immich.update

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import nl.giejay.android.tv.immich.BuildConfig
import nl.giejay.android.tv.immich.R
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/** All work begins at an explicit button click. Owner must dismiss in onStop. */
internal class ManualUpdateDialog(
    private val context: Context,
    private val repository: UpdateRepository = UpdateRepository(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Future<*>? = null
    private var closed = false
    private var busy = false
    private var candidate: UpdateCandidate? = null
    private var downloaded = false
    private val file = File(context.cacheDir, "update-download-${UUID.randomUUID()}.apk")
    private var sourceFile = file
    private val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.update_title)
        .setMessage(R.string.update_manual)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.update_check, null).create()

    fun show(): ManualUpdateDialog {
        // Remove only old public installation artifacts on this explicit opening.
        executor.execute {
            UpdateInstaller.cleanup(context)
            context.cacheDir.listFiles()?.filter {
                it.name.matches(Regex("update-download-[0-9a-f-]{36}\\.apk")) && it != file
            }?.forEach { it.delete() }
        }
        dialog.setOnDismissListener { close() }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { act() }
        // Offline only. Returning from permission settings never invokes this unless the user reopens.
        work(R.string.update_verifying, { UpdatePendingStore.load(context) }) { pending ->
            if (pending != null) {
                sourceFile = pending.first
                candidate = pending.second
                downloaded = true
                dialog.setMessage(context.getString(R.string.update_ready, pending.second.versionName))
                button(R.string.update_install)
            } else {
                dialog.setMessage(context.getString(R.string.update_manual))
                button(R.string.update_check)
            }
        }
        return this
    }

    fun dismiss() {
        close() // Invalidate synchronously; Android dispatches the dismiss listener later.
        dialog.dismiss()
    }

    private fun act() {
        if (closed || busy || !dialog.isShowing) return
        val selected = candidate
        if (selected == null) {
            work(R.string.update_checking, { repository.check(BuildConfig.VERSION_CODE.toLong()) }) { result ->
                candidate = result
                if (result == null) {
                    dialog.setMessage(context.getString(R.string.update_current))
                    button(R.string.update_check)
                } else {
                    dialog.setMessage(context.getString(R.string.update_available, result.versionName))
                    button(R.string.update_download)
                }
            }
        } else if (!downloaded) {
            work(R.string.update_downloading, {
                repository.download(selected, file)
                UpdateApkVerifier.verify(context, file, selected)
                UpdatePendingStore.persist(context, file, selected)
            }) {
                downloaded = true
                dialog.setMessage(context.getString(R.string.update_ready, selected.versionName))
                button(R.string.update_install)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                // The return from Settings never resumes this action. Background dismissal is intentional.
                busy = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")))
                }.onSuccess { dismiss() }.onFailure { fail(R.string.update_permission_failed) }
                return
            }
            // Final verification/copy is off the main thread; launch only while this dialog is current.
            work(R.string.update_verifying, { UpdateInstaller.prepare(context, sourceFile, selected) }) { prepared ->
                runCatching { UpdateInstaller.launch(context, prepared) }
                    .onSuccess { dismiss() }
                    .onFailure { fail(R.string.update_install_failed) }
            }
        }
    }

    private fun button(label: Int) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { setText(label); isEnabled = true }
    }

    private fun fail(message: Int = R.string.update_failed) {
        busy = false
        candidate = null
        sourceFile = file
        downloaded = false
        file.delete()
        dialog.setMessage(context.getString(message))
        button(R.string.update_check)
    }

    private fun <T> work(message: Int, operation: () -> T, complete: (T) -> Unit) {
        busy = true
        dialog.setMessage(context.getString(message))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        pending = executor.submit {
            val result = runCatching(operation)
            main.post {
                if (!closed && dialog.isShowing) {
                    busy = false
                    result.onSuccess(complete).onFailure { fail() }
                } else {
                    (result.getOrNull() as? UpdateInstaller.PreparedUpdate)?.let(UpdateInstaller::discard)
                }
            }
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        repository.cancel()
        pending?.cancel(true)
        // Serial cleanup follows the worker, including a cancelled network write.
        executor.execute { file.delete() }
        executor.shutdown()
    }
}
