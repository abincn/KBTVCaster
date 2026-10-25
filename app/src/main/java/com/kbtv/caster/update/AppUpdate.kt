package com.kbtv.caster.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.kbtv.caster.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import timber.log.Timber

/**
 * Fork auto-update: checks this fork's own GitHub Releases, never upstream.
 *
 * Upstream packages are signed by the upstream author, so downloading one
 * would fail to install over a fork-signed build with a signature conflict.
 * Fork Releases are signed with the fork key, so they install over this
 * build directly. Asset naming convention (set by the fork release
 * workflow): kbtvcaster-<versionName>+<versionCode>-armeabi-v7a.apk
 */
object AppUpdate {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/abincn/KBTVCaster/releases/latest"
    private const val PREFS_NAME = "kbtv_update_prefs"
    private const val KEY_PROMPTED_CODE = "prompted_version_code"

    sealed class CheckResult {
        object UpToDate : CheckResult()
        data class Available(
            val remoteVersionCode: Int?,
            val remoteVersionName: String,
            val downloadUrl: String,
            val assetName: String
        ) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    /** Runs on the calling thread; callers must call from a background thread. */
    fun check(): CheckResult {
        return try {
            val conn = (URL(RELEASES_LATEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "KBTVCaster")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            if (code == 404) {
                // No fork Release published yet: nothing to update to, not an error.
                return CheckResult.UpToDate
            }
            if (code != 200) {
                return CheckResult.Failed("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val remoteName = tag.removePrefix("v").substringBefore("-")
            var downloadUrl: String? = null
            var assetName: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.contains("armeabi-v7a") && name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        assetName = name
                        break
                    }
                }
            }
            if (downloadUrl.isNullOrEmpty() || assetName.isNullOrEmpty()) {
                return CheckResult.UpToDate
            }
            // Primary comparison: versionCode embedded in the asset name (+N-).
            val codeMatch = Regex("""\+(\d+)-armeabi-v7a\.apk$""").find(assetName)
            val remoteCode = codeMatch?.groupValues?.get(1)?.toIntOrNull()
            if (remoteCode != null) {
                return if (remoteCode > BuildConfig.VERSION_CODE) {
                    CheckResult.Available(remoteCode, remoteName, downloadUrl, assetName)
                } else {
                    CheckResult.UpToDate
                }
            }
            // Fallback: compare version names when the asset carries no code.
            return if (compareVersionNames(remoteName, BuildConfig.VERSION_NAME) > 0) {
                CheckResult.Available(null, remoteName, downloadUrl, assetName)
            } else {
                CheckResult.UpToDate
            }
        } catch (e: Exception) {
            Timber.e(e, "Update check failed")
            CheckResult.Failed(e.message ?: "unknown error")
        }
    }

    private fun compareVersionNames(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val x = pa.getOrNull(i) ?: 0
            val y = pb.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun wasPromptedFor(context: Context, remoteCode: Int?): Boolean {
        if (remoteCode == null) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PROMPTED_CODE, -1) == remoteCode
    }

    fun markPrompted(context: Context, remoteCode: Int?) {
        if (remoteCode == null) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PROMPTED_CODE, remoteCode).apply()
    }

    /** Downloads the APK to cacheDir. Returns the file, or null on failure. */
    fun downloadApk(context: Context, downloadUrl: String): File? {
        return try {
            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "KBTVCaster")
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            if (conn.responseCode != 200) return null
            val outFile = File(context.cacheDir, "kbtvcaster-update.apk")
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        } catch (e: Exception) {
            Timber.e(e, "Update download failed")
            null
        }
    }

    /**
     * Starts the package installer for [apkFile].
     * Returns false when the user must first allow "install unknown apps"
     * for this app; the caller should then open the system settings page.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return false
        }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
