package com.astrocompass.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 15_000
private const val DOWNLOAD_BUFFER_SIZE = 8 * 1024

/**
 * Hits the public GitHub REST API directly via [HttpURLConnection] rather than adding a client
 * dependency -- this app has no HTTP client anywhere (only `ktor-network` raw sockets, for the
 * mount protocol; see [com.astrocompass.telescope.TcpTelescopeTransport]), and this is the only
 * caller, entirely confined to Android where the update itself (an APK install) is unavoidably
 * Android-only.
 *
 * The APK is downloaded to [Context.getCacheDir] under one constant filename, overwritten on each
 * attempt -- there's no reason to keep more than the most recent download around.
 */
class AndroidAppUpdater(private val context: Context) : AppUpdater {

    override val isSupported = true

    override val currentVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

    override suspend fun fetchReleases(): Result<List<AppRelease>> = withContext(Dispatchers.IO) {
        runCatching { parseReleases(httpGet("https://api.github.com/repos/$UPDATE_REPO/releases")) }
    }

    override fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override suspend fun downloadAndInstall(release: AppRelease, onProgress: (Float) -> Unit): InstallOutcome {
        if (!canInstallPackages()) return InstallOutcome.PermissionRequired
        val apkUrl = release.apkDownloadUrl ?: return InstallOutcome.Failed("This release has no APK to install")
        return withContext(Dispatchers.IO) {
            runCatching {
                val destination = File(context.cacheDir, "update.apk")
                downloadTo(apkUrl, destination, onProgress)
                installApk(destination)
            }.fold(
                onSuccess = { InstallOutcome.Started },
                onFailure = { InstallOutcome.Failed(it.message ?: "Download failed") },
            )
        }
    }

    private fun downloadTo(urlString: String, destination: File, onProgress: (Float) -> Unit) {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IOException("Download failed with HTTP ${connection.responseCode}")
        }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) onProgress(downloaded.toFloat() / total)
                }
            }
        }
    }

    /** [FileProvider] rather than a raw `file://` `Uri`: Android N+ throws `FileUriExposedException`
     *  on any `file://` handed to another app (the system installer, here) outside this process. */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** GitHub's REST API refuses requests with no `User-Agent` at all; [browserDownloadUrl] asset
     *  links need neither this header nor auth (they're the same public CDN redirect a browser
     *  follows), so only this call sets it. */
    private fun httpGet(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AstroCompass-Android")
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IOException("GitHub API returned HTTP ${connection.responseCode}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
