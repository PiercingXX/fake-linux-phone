package com.piercingxx.linux.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a release APK from GitHub and hands it to the system installer.
 *
 * Android only accepts an update signed with the installed app's key, so the APK's certificate
 * is compared before installing; a mismatch is reported instead of a generic "App not installed".
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val DIR = "updates"

    class UpdateException(val code: String, message: String) : Exception(message)

    @Volatile private var cancelled = false

    fun currentVersion(context: Context): Map<String, Any> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return mapOf("versionName" to (info.versionName ?: ""), "versionCode" to code)
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun cancel() {
        cancelled = true
    }

    /** Removes APKs left over from earlier update attempts. */
    fun cleanup(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * Streams [url] to app cache and verifies size and signature.
     * [onProgress] receives (downloadedBytes, totalBytes). Returns the APK file.
     */
    fun download(
        context: Context,
        url: String,
        expectedSize: Long,
        tag: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        cancelled = false
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val apk = File(dir, "FakeLinux-$safeTag.apk")
        val part = File(dir, "${apk.name}.part")
        dir.listFiles()?.filter { it != apk && it != part }?.forEach { it.delete() }

        if (!(apk.isFile && (expectedSize <= 0 || apk.length() == expectedSize))) {
            part.delete()
            fetch(url, part, expectedSize, onProgress)
            if (cancelled) {
                part.delete()
                throw UpdateException("cancelled", "Download cancelled")
            }
            if (expectedSize > 0 && part.length() != expectedSize) {
                part.delete()
                throw UpdateException("size_mismatch", "Downloaded file is incomplete")
            }
            apk.delete()
            if (!part.renameTo(apk)) throw UpdateException("storage", "Could not save the update")
        }
        verify(context, apk)
        return apk
    }

    private fun fetch(url: String, target: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "FakeLinux-Updater")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateException("network", "Redirect without location")
                    if (++redirects > 5) throw UpdateException("network", "Too many redirects")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw UpdateException("network", "Server returned $code")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
                var done = 0L
                var lastReported = -1L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (!cancelled) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            // Report about every 1%.
                            if (total <= 0 || done - lastReported >= total / 100) {
                                lastReported = done
                                onProgress(done, total)
                            }
                        }
                    }
                }
                onProgress(done, total)
                return
            } catch (error: IOException) {
                Log.w(TAG, "Update download failed", error)
                throw UpdateException("network", error.message ?: "Network error")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw UpdateException("invalid", "The downloaded file is not a valid app")
        if (archive.packageName != context.packageName) {
            throw UpdateException("invalid", "The downloaded app is not FakeLinux")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (fingerprints(archive).intersect(fingerprints(installed)).isEmpty()) {
            throw UpdateException(
                "signature_mismatch",
                "This release is signed with a different key than the installed app",
            )
        }
    }

    private fun fingerprints(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION") info.signatures?.toList().orEmpty()
        }
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.map { sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** Opens the system installer for a verified APK in the app cache. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
