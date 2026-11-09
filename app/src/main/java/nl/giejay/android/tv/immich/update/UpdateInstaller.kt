package nl.giejay.android.tv.immich.update

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** The system owns confirmation. No result receiver, resume callback, or silent install path. */
internal object UpdateInstaller {
    private const val DIRECTORY = "update_installs"
    private const val RETENTION_MILLIS = 24L * 60 * 60 * 1000
    private val stagingLock = Any()
    private val leaseLock = Any()
    private class Lease(var references: Int = 0, var handedOff: Boolean)
    private val leases = mutableMapOf<String, Lease>()

    /** Explicit updater-open housekeeping only. Never called from startup or background work. */
    fun cleanup(context: Context) {
        synchronized(leaseLock) {
            leases.entries.removeAll { it.value.references == 0 && !File(it.key).exists() }
        }
        File(context.cacheDir, DIRECTORY).listFiles()?.forEach { file ->
            synchronized(leaseLock) {
                // Re-read expiry under the same lock as lease release. A handoff may have
                // renewed this file while cleanup was waiting to acquire the lock.
                val now = System.currentTimeMillis()
                val modifiedAt = file.lastModified()
                if (file.isFile && file.name.matches(Regex("[0-9a-f-]{36}\\.apk")) &&
                    now >= modifiedAt && now - modifiedAt > RETENTION_MILLIS &&
                    (leases[file.absolutePath]?.references ?: 0) == 0 && file.delete()) {
                    leases.remove(file.absolutePath)
                }
            }
        }
    }

    class PreparedUpdate internal constructor(
        internal val file: File,
        internal val candidate: UpdateCandidate,
        internal val modifiedAt: Long,
        internal val newSnapshot: Boolean = false
    ) {
        internal var consumed = false
        init {
            synchronized(leaseLock) {
                val lease = leases.getOrPut(file.absolutePath) { Lease(handedOff = !newSnapshot) }
                lease.references++
            }
        }
    }

    /** Worker-only preparation. The caller must discard results after cancellation or dismissal. */
    fun prepare(context: Context, downloaded: File, candidate: UpdateCandidate): PreparedUpdate {
        val prepared = acquireSnapshot(context, downloaded, candidate)
        try {
            UpdateApkVerifier.verify(context, prepared.file, candidate)
            return prepared
        } catch (_: Exception) {
            discard(prepared)
            throw UpdateVerificationException()
        }
    }

    /** Keep one reference during verification so a stale callback cannot delete a reused snapshot. */
    internal fun acquireSnapshot(context: Context, downloaded: File, candidate: UpdateCandidate): PreparedUpdate =
        synchronized(stagingLock) {
            val directory = File(context.cacheDir, DIRECTORY)
            for (file in directory.listFiles().orEmpty()) {
                if (!file.isFile || !file.name.matches(Regex("[0-9a-f-]{36}\\.apk")) ||
                    file.length() != candidate.size) continue
                val token = synchronized(leaseLock) {
                    if (!file.isFile) null else PreparedUpdate(file, candidate, file.lastModified())
                } ?: continue
                try {
                    UpdateApkVerifier.verifyDigest(file, candidate)
                    if (!file.setReadOnly()) throw UpdateVerificationException()
                    return@synchronized token
                } catch (_: Exception) {
                    discard(token)
                    if (Thread.currentThread().isInterrupted) throw UpdateVerificationException()
                }
            }
            val file = snapshot(context, downloaded, candidate)
            PreparedUpdate(file, candidate, file.lastModified(), newSnapshot = true)
        }

    /** Main-thread, explicit Install click only, after the caller checks its live UI generation. */
    fun launch(context: Context, prepared: PreparedUpdate) {
        synchronized(prepared) {
            if (prepared.consumed) throw IOException("Update already used")
            prepared.consumed = true
        }
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                throw IOException("Installation permission required")
            }
            val staged = prepared.file
            val directory = File(context.cacheDir, DIRECTORY).canonicalFile
            if (!staged.isFile || staged.canonicalFile.parentFile != directory ||
                staged.length() != prepared.candidate.size || staged.lastModified() != prepared.modifiedAt) {
                throw UpdateVerificationException()
            }
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode
                else @Suppress("DEPRECATION") installed.versionCode.toLong()
            if (prepared.candidate.versionCode <= code) throw UpdateVerificationException()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", staged)
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("Update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val installer = context.packageManager.resolveActivity(intent,
                PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo ?: throw IOException("No system installer available")
            intent.component = ComponentName(installer.packageName, installer.name)
            // Renew the retention window before each handoff, without rewriting any APK bytes.
            if (!staged.setLastModified(System.currentTimeMillis())) throw IOException("Unable to retain update")
            context.startActivity(intent)
            release(prepared, handedOff = true)
        } catch (_: Exception) {
            release(prepared, handedOff = false)
            throw IOException("Unable to open installer")
        }
    }

    /** Do not call after successful launch: the system still needs the file. */
    fun discard(prepared: PreparedUpdate) {
        synchronized(prepared) {
            if (!prepared.consumed) {
                prepared.consumed = true
                release(prepared, handedOff = false)
            }
        }
    }

    private fun release(prepared: PreparedUpdate, handedOff: Boolean) {
        synchronized(leaseLock) {
            val key = prepared.file.absolutePath
            val lease = leases[key] ?: return
            if (handedOff) lease.handedOff = true
            lease.references--
            if (lease.references == 0 && !lease.handedOff) {
                prepared.file.delete()
                leases.remove(key)
            }
        }
    }

    @Synchronized internal fun snapshot(context: Context, downloaded: File, candidate: UpdateCandidate): File {
        val directory = File(context.cacheDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to stage update")
        // Never remove a recent handoff while Android may still be reading it. Fail closed at capacity.
        if ((directory.listFiles()?.count { it.isFile } ?: 0) >= 2) {
            throw IOException("Pending installations must finish or expire before another update")
        }
        val file = File(directory, "${UUID.randomUUID()}.apk")
        try {
            if (!file.createNewFile()) throw IOException("Unable to stage update")
            downloaded.inputStream().use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Update cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > candidate.size || count > UpdateApkVerifier.MAX_APK_BYTES) {
                            throw UpdateVerificationException()
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (!file.setReadOnly()) throw IOException("Unable to protect update")
            UpdateApkVerifier.verifyDigest(file, candidate)
            return file
        } catch (_: Exception) {
            file.delete()
            throw UpdateVerificationException()
        }
    }
}
