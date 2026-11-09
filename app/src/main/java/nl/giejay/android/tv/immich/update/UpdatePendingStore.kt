package nl.giejay.android.tv.immich.update

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One public APK retained for explicit offline reuse after the system permission screen. */
internal object UpdatePendingStore {
    private const val DIRECTORY = "update_pending"
    private const val RETENTION = 24L * 60 * 60 * 1000
    private const val MAX_METADATA = 4096L

    /** Worker thread only. Does not retain account state and never performs network requests. */
    @Synchronized fun persist(context: Context, downloaded: File, candidate: UpdateCandidate) {
        UpdateRepository.requireCandidate(candidate)
        val root = File(context.cacheDir, DIRECTORY).apply {
            if (!isDirectory && !mkdirs()) throw UpdateVerificationException()
        }
        val staging = File(root, UUID.randomUUID().toString())
        if (!staging.mkdir()) throw UpdateVerificationException()
        try {
            val apk = File(staging, "update.apk")
            downloaded.inputStream().use { input ->
                FileOutputStream(apk).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw UpdateVerificationException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > candidate.size || count > UpdateApkVerifier.MAX_APK_BYTES) {
                            throw UpdateVerificationException()
                        }
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            if (!apk.setReadOnly()) throw UpdateVerificationException()
            UpdateApkVerifier.verify(context, apk, candidate)
            val metadata = JsonObject().apply {
                addProperty("schema", 1)
                addProperty("savedAt", System.currentTimeMillis())
                addProperty("versionName", candidate.versionName)
                addProperty("versionCode", candidate.versionCode)
                addProperty("url", candidate.url)
                addProperty("size", candidate.size)
                addProperty("sha256", candidate.sha256)
            }.toString().toByteArray(Charsets.UTF_8)
            if (metadata.size > MAX_METADATA) throw UpdateVerificationException()
            val manifest = File(staging, "candidate.json")
            FileOutputStream(manifest).use { output -> output.write(metadata); output.fd.sync() }
            if (!manifest.setReadOnly()) throw UpdateVerificationException()
            if (Thread.currentThread().isInterrupted) throw UpdateVerificationException()
            // Never expose a partial entry. A crash before rename simply leaves no pending update.
            File(root, "current").deleteRecursively()
            if (!staging.renameTo(File(root, "current"))) throw UpdateVerificationException()
            root.listFiles()?.filter { it.name != "current" }?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
            staging.deleteRecursively()
            throw UpdateVerificationException()
        }
    }

    /** Only call on explicit updater opening, off the main thread. Expired/invalid entries are removed. */
    @Synchronized fun load(context: Context): Pair<File, UpdateCandidate>? {
        val root = File(context.cacheDir, DIRECTORY)
        root.listFiles()?.filter { it.name != "current" }?.forEach { it.deleteRecursively() }
        val current = File(root, "current")
        if (!current.exists()) return null
        return try {
            val metadata = File(current, "candidate.json")
            if (!metadata.isFile || metadata.length() !in 1..MAX_METADATA) throw UpdateVerificationException()
            val json = JsonParser.parseString(metadata.readText(Charsets.UTF_8)).asJsonObject
            if (json.keySet() != setOf("schema", "savedAt", "versionName", "versionCode", "url", "size", "sha256") ||
                json["schema"].asInt != 1) throw UpdateVerificationException()
            val saved = json["savedAt"].asLong
            val now = System.currentTimeMillis()
            if (saved > now || now - saved >= RETENTION) throw UpdateVerificationException()
            val candidate = UpdateCandidate(json["versionName"].asString, json["versionCode"].asLong,
                json["url"].asString, json["size"].asLong, json["sha256"].asString)
            UpdateRepository.requireCandidate(candidate)
            val apk = File(current, "update.apk")
            UpdateApkVerifier.verify(context, apk, candidate)
            apk to candidate
        } catch (_: Exception) {
            current.deleteRecursively()
            null
        }
    }

    @Synchronized fun clear(context: Context) {
        File(context.cacheDir, DIRECTORY).deleteRecursively()
    }
}
