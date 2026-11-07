package dev.herdr.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.herdr.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR
}

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
)

data class AppUpdateState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val status: UpdateStatus = UpdateStatus.IDLE,
    val latestRelease: ReleaseUpdateInfo? = null,
    val downloadProgressPercent: Int = 0,
    val downloadedFilePath: String? = null,
    val statusMessage: String = "当前版本 v${BuildConfig.VERSION_NAME} · 支持从 GitHub Release 检查更新"
)

object AppUpdateManager {
    const val GITHUB_OWNER = "DracaeneSunbringer"
    const val GITHUB_REPO = "HerdrAndroid"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    suspend fun fetchLatestRelease(): Result<ReleaseUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "HerdrAndroid/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@withContext Result.success(null)
                }
                if (code !in 200..299) {
                    throw IOException("GitHub Release 查询失败 (HTTP $code)")
                }
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Result.success(parseReleaseJson(body))
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    internal fun parseReleaseJson(jsonText: String): ReleaseUpdateInfo {
        val root = JSONObject(jsonText)
        val tagName = root.optString("tag_name", "").trim()
        require(tagName.isNotBlank()) { "Release 缺少 tag_name" }
        val normalizedVersion = normalizeVersionString(tagName)
        val releaseTitle = root.optString("name", "").trim().ifBlank { tagName }
        val releaseNotes = root.optString("body", "").trim()
        val htmlUrl = root.optString("html_url", "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")

        var apkName = ""
        var apkUrl = ""
        var apkSize = 0L
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val contentType = asset.optString("content_type", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                if (downloadUrl.isNotBlank() && (name.endsWith(".apk", ignoreCase = true) || contentType == "application/vnd.android.package-archive")) {
                    apkName = name
                    apkUrl = downloadUrl
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }
        return ReleaseUpdateInfo(
            tagName = tagName,
            versionName = normalizedVersion,
            releaseTitle = releaseTitle,
            releaseNotes = releaseNotes,
            apkAssetName = apkName,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = apkSize,
            htmlUrl = htmlUrl
        )
    }

    internal fun normalizeVersionString(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    internal fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteClean = normalizeVersionString(remoteVersion).substringBefore('-').substringBefore('+')
        val currentClean = normalizeVersionString(currentVersion).substringBefore('-').substringBefore('+')
        if (remoteClean.isBlank() || currentClean.isBlank()) return false
        val remoteParts = remoteClean.split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = currentClean.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    internal fun buildCandidateDownloadUrls(rawUrl: String): List<String> {
        val clean = rawUrl.trim()
        if (clean.isBlank()) return emptyList()
        if (!clean.startsWith("https://github.com/", ignoreCase = true)) {
            return listOf(clean)
        }
        return listOf(
            "https://gh-proxy.com/$clean",
            "https://ghfast.top/$clean",
            "https://ghproxy.net/$clean",
            clean
        )
    }

    suspend fun downloadReleaseApk(
        context: Context,
        release: ReleaseUpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val candidateUrls = buildCandidateDownloadUrls(release.apkDownloadUrl)
            require(candidateUrls.isNotEmpty()) { "该 Release 未附带可下载的 APK 安装包" }
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.isDirectory && !updatesDir.mkdirs()) {
                throw IOException("无法创建更新缓存目录")
            }
            val safeFileName = release.apkAssetName
                .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
                .ifBlank { "HerdrAndroid-${release.versionName}.apk" }
            val targetFile = File(updatesDir, safeFileName)
            val tempFile = File(updatesDir, "$safeFileName.part")

            var lastError: Throwable? = null
            for (candidateUrl in candidateUrls) {
                try {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    downloadFromSingleUrl(
                        initialUrl = candidateUrl,
                        expectedSizeBytes = release.apkSizeBytes,
                        tempFile = tempFile,
                        onProgress = onProgress
                    )
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    onProgress(100)
                    return@withContext Result.success(targetFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            throw (lastError ?: IOException("所有下载通道均不可用"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun downloadFromSingleUrl(
        initialUrl: String,
        expectedSizeBytes: Long,
        tempFile: File,
        onProgress: (Int) -> Unit
    ) {
        var currentUrl = initialUrl
        var redirects = 0
        var conn: HttpURLConnection? = null
        try {
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "HerdrAndroid/${BuildConfig.VERSION_NAME}")
                }
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("下载重定向缺少 Location 头")
                    conn.disconnect()
                    currentUrl = URL(URL(currentUrl), location).toString()
                    if (++redirects > 6) throw IOException("下载重定向次数过多")
                    continue
                }
                if (status !in 200..299) {
                    throw IOException("APK 下载失败 (HTTP $status)")
                }
                break
            }

            val activeConn = conn ?: throw IOException("下载连接未建立")
            val totalBytes = activeConn.contentLengthLong.takeIf { it > 0L } ?: expectedSizeBytes
            var downloadedBytes = 0L
            var lastReportedPercent = -1

            activeConn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0L) {
                            val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPercent) {
                                lastReportedPercent = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            if (tempFile.length() < 4L) {
                throw IOException("下载的 APK 文件不完整")
            }
            val header = ByteArray(4)
            tempFile.inputStream().use { it.read(header) }
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                throw IOException("下载的文件不是有效的 APK (ZIP) 包")
            }
        } finally {
            conn?.disconnect()
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File): Result<Boolean> {
        return runCatching {
            require(apkFile.isFile && apkFile.length() > 0L) { "未找到已下载的更新包文件" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(permIntent)
                return@runCatching false
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            true
        }
    }
}
