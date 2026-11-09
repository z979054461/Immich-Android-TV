package nl.giejay.android.tv.immich.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** No exception carries a path, response body, certificate, or account information. */
internal class UpdateVerificationException : IOException("Update verification failed")

internal object UpdateApkVerifier {
    const val PACKAGE = "io.github.z979054461.immichtv"
    const val SIGNER_SHA256 = "276fac8da58ea9cf2ad0f32a7a3e989a9c4f46084aea351569b944027c1bb330"
    const val MAX_APK_BYTES = 150L * 1024 * 1024

    /** Called after download and again on the immutable installation snapshot. */
    fun verify(context: Context, file: File, candidate: UpdateCandidate) {
        try {
            verifyDigest(file, candidate)
            verifyZipBounds(file)
            // getPackageArchiveInfo(GET_SIGNING_CERTIFICATES) alone is NOT a content verifier.
            // apksig validates signed ZIP sections and Android signature scheme semantics.
            val result = ApkVerifier.Builder(file)
                .setMinCheckedPlatformVersion(24)
                .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT)
                .build().verify()
            verifySignatureFacts(result.isVerified,
                result.isVerifiedUsingV2Scheme || result.isVerifiedUsingV3Scheme,
                result.signerCertificates.map { digest(it.encoded) })
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: reject()
            val installed = context.packageManager.getPackageInfo(context.packageName, 0)
            verifyIdentity(archive.packageName, archive.versionName, versionCode(archive),
                versionCode(installed), candidate)
            if (context.packageName != PACKAGE ||
                archive.applicationInfo?.minSdkVersion?.let { it > Build.VERSION.SDK_INT } == true) reject()
            // The package parser opens the same app-owned immutable file; detect unexpected mutation.
            verifyDigest(file, candidate)
        } catch (_: Exception) {
            throw UpdateVerificationException()
        } catch (_: LinkageError) {
            // A missing verifier runtime primitive must fail closed, never fall back to metadata.
            throw UpdateVerificationException()
        }
    }

    private fun verifyZipBounds(file: File) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            var count = 0
            var expanded = 0L
            var manifests = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > 10000 || entry.size < 0 || entry.size > MAX_APK_BYTES) reject()
                expanded += entry.size
                if (expanded > 500L * 1024 * 1024) reject()
                if (entry.name == "AndroidManifest.xml") {
                    if (++manifests != 1 || entry.size !in 1..(1024L * 1024)) reject()
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(8192)
                        var read = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) reject()
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > 1024L * 1024) reject()
                        }
                        if (read != entry.size) reject()
                    }
                }
            }
            if (manifests != 1) reject()
        }
    }

    internal fun verifySignatureFacts(verified: Boolean, modernScheme: Boolean, signerDigests: List<String>) {
        if (!verified || !modernScheme || signerDigests != listOf(SIGNER_SHA256)) reject()
    }

    internal fun verifyIdentity(packageName: String?, versionName: String?, versionCode: Long,
                                installedCode: Long, candidate: UpdateCandidate) {
        if (packageName != PACKAGE || versionName != candidate.versionName ||
            versionCode != candidate.versionCode || versionCode <= installedCode) reject()
    }

    internal fun verifyDigest(file: File, candidate: UpdateCandidate) {
        if (candidate.size !in 1..MAX_APK_BYTES || !candidate.sha256.matches(Regex("[0-9a-f]{64}")) ||
            !file.isFile || file.length() != candidate.size) reject()
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) reject()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                if (count > candidate.size || count > MAX_APK_BYTES) reject()
                hash.update(buffer, 0, read)
            }
        }
        if (count != candidate.size || hex(hash.digest()) != candidate.sha256) reject()
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun reject(): Nothing = throw UpdateVerificationException()
}
