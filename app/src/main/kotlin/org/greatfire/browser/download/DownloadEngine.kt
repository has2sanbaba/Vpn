package org.greatfire.browser.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.data.DownloadRecord
import org.greatfire.browser.data.DownloadState
import org.greatfire.browser.vpn.TunnelManager
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DownloadEngine private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = BrowserDataStore.getInstance(appContext)
    private val tunnelManager = TunnelManager.getInstance(appContext)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, Call>()

    private val _downloadsFlow = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadRecord>> = _downloadsFlow.asStateFlow()

    var onDownloadEventToast: ((message: String, completed: Boolean) -> Unit)? = null

    init {
        dataStore.recoverInterruptedDownloadsOnStartup()
        refreshSnapshot()
    }

    fun refreshSnapshot() {
        _downloadsFlow.value = dataStore.getDownloads()
    }

    fun enqueueDownload(
        url: String,
        userAgent: String = "",
        contentDisposition: String = "",
        mimeType: String = "",
        refererUrl: String = ""
    ): DownloadRecord? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("data:", ignoreCase = true)) {
            saveDataUri(trimmed)
            return null
        }

        val initialMime = cleanMimeOrNull(mimeType)
        val initialName = sanitizeFileName(
            guessBestFileName(trimmed, contentDisposition, initialMime)
        )
        val stagingFile = allocateUniqueStagingFile(initialName)

        val record = DownloadRecord(
            fileName = stagingFile.name,
            url = trimmed,
            mimeType = resolveMimeForFileName(stagingFile.name, initialMime),
            bytesRead = 0L,
            totalBytes = -1L,
            speedBps = 0L,
            state = DownloadState.DOWNLOADING,
            localUriOrPath = stagingFile.absolutePath,
            stagingPath = stagingFile.absolutePath,
            refererUrl = refererUrl,
            userAgent = userAgent.ifBlank { DEFAULT_DOWNLOAD_UA }
        )
        persistAndPublish(record)
        launchWorker(record, resumeFromExisting = false)
        return record
    }

    fun pauseDownload(id: String) {
        activeCalls.remove(id)?.cancel()
        activeJobs.remove(id)?.cancel()
        val existing = dataStore.getDownloads().firstOrNull { it.id == id } ?: return
        if (existing.state == DownloadState.DOWNLOADING) {
            val fileLen = existing.stagingPath.takeIf { it.isNotBlank() }?.let { File(it).length() } ?: existing.bytesRead
            persistAndPublish(
                existing.copy(
                    bytesRead = maxOf(existing.bytesRead, fileLen),
                    speedBps = 0L,
                    state = DownloadState.PAUSED
                )
            )
        }
    }

    fun resumeOrRetryDownload(id: String) {
        if (activeJobs.containsKey(id)) return
        val existing = dataStore.getDownloads().firstOrNull { it.id == id } ?: return
        if (existing.url.isBlank()) return

        val stFile = if (existing.stagingPath.isNotBlank()) {
            File(existing.stagingPath)
        } else {
            allocateUniqueStagingFile(existing.fileName)
        }
        val canResume = stFile.exists() && stFile.length() > 0L
        val updated = existing.copy(
            bytesRead = if (canResume) stFile.length() else 0L,
            speedBps = 0L,
            state = DownloadState.DOWNLOADING,
            stagingPath = stFile.absolutePath,
            localUriOrPath = stFile.absolutePath,
            errorMsg = ""
        )
        persistAndPublish(updated)
        launchWorker(updated, resumeFromExisting = canResume)
    }

    fun cancelDownload(id: String) {
        activeCalls.remove(id)?.cancel()
        activeJobs.remove(id)?.cancel()
        val existing = dataStore.getDownloads().firstOrNull { it.id == id } ?: return
        runCatching {
            if (existing.stagingPath.isNotBlank()) File(existing.stagingPath).delete()
        }
        dataStore.deleteDownloadRecord(id)
        refreshSnapshot()
    }

    fun deleteDownload(id: String, deleteDiskFile: Boolean = false) {
        activeCalls.remove(id)?.cancel()
        activeJobs.remove(id)?.cancel()
        if (deleteDiskFile) {
            val existing = dataStore.getDownloads().firstOrNull { it.id == id }
            if (existing != null && existing.stagingPath.isNotBlank()) {
                runCatching { File(existing.stagingPath).delete() }
            }
        }
        dataStore.deleteDownloadRecord(id)
        refreshSnapshot()
    }

    fun clearFinishedDownloads() {
        val activeOnly = dataStore.getDownloads().filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED }
        dataStore.clearDownloads()
        for (item in activeOnly.asReversed()) {
            dataStore.upsertDownload(item)
        }
        refreshSnapshot()
    }

    fun saveBlobBase64Payload(base64OrDataUrl: String, suggestedName: String, mimeHint: String) {
        scope.launch {
            runCatching {
                val rawB64 = if (base64OrDataUrl.contains(",")) {
                    base64OrDataUrl.substringAfter(",")
                } else {
                    base64OrDataUrl
                }
                val bytes = Base64.decode(rawB64, Base64.DEFAULT)
                val resolvedMime = cleanMimeOrNull(mimeHint)
                    ?: extractMimeFromDataUri(base64OrDataUrl)
                    ?: "application/octet-stream"
                val baseName = suggestedName.ifBlank {
                    val ext = extensionForMime(resolvedMime)
                    "download_${System.currentTimeMillis()}.$ext"
                }
                val stFile = allocateUniqueStagingFile(sanitizeFileName(baseName))
                stFile.writeBytes(bytes)
                exportCopyToPublicDownloadsQuietly(stFile, resolvedMime)

                val rec = DownloadRecord(
                    fileName = stFile.name,
                    url = "",
                    mimeType = resolveMimeForFileName(stFile.name, resolvedMime),
                    bytesRead = bytes.size.toLong(),
                    totalBytes = bytes.size.toLong(),
                    speedBps = 0L,
                    state = DownloadState.COMPLETED,
                    localUriOrPath = stFile.absolutePath,
                    stagingPath = stFile.absolutePath
                )
                persistAndPublish(rec)
                onDownloadEventToast?.invoke("✅ دانلود کامل شد: ${stFile.name}", true)
            }.onFailure {
                onDownloadEventToast?.invoke("خطا در ذخیره فایل: ${it.message}", false)
            }
        }
    }

    private fun saveDataUri(dataUri: String) {
        scope.launch {
            runCatching {
                val comma = dataUri.indexOf(',')
                require(comma > 5) { "فرمت داده معتبر نیست" }
                val meta = dataUri.substring(5, comma)
                val mime = meta.substringBefore(';').ifBlank { "image/png" }
                val ext = extensionForMime(mime)
                val bytes = if (meta.contains("base64", ignoreCase = true)) {
                    Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
                } else {
                    Uri.decode(dataUri.substring(comma + 1)).toByteArray()
                }
                val stFile = allocateUniqueStagingFile("file_${System.currentTimeMillis()}.$ext")
                stFile.writeBytes(bytes)
                exportCopyToPublicDownloadsQuietly(stFile, mime)

                val rec = DownloadRecord(
                    fileName = stFile.name,
                    url = "",
                    mimeType = resolveMimeForFileName(stFile.name, mime),
                    bytesRead = bytes.size.toLong(),
                    totalBytes = bytes.size.toLong(),
                    state = DownloadState.COMPLETED,
                    localUriOrPath = stFile.absolutePath,
                    stagingPath = stFile.absolutePath
                )
                persistAndPublish(rec)
                onDownloadEventToast?.invoke("✅ ذخیره شد: ${stFile.name}", true)
            }.onFailure {
                onDownloadEventToast?.invoke("خطا در ذخیره فایل", false)
            }
        }
    }

    private fun launchWorker(initialRecord: DownloadRecord, resumeFromExisting: Boolean) {
        val job = scope.launch {
            var current = initialRecord
            var stFile = File(current.stagingPath)
            stFile.parentFile?.mkdirs()

            try {
                tunnelManager.ensureRunning()

                val startOffset = if (resumeFromExisting && stFile.exists()) stFile.length() else 0L
                val reqBuilder = Request.Builder()
                    .url(current.url)
                    .header("User-Agent", current.userAgent.ifBlank { DEFAULT_DOWNLOAD_UA })
                    .header("Accept", "*/*")

                // Forward WebView session Cookies & Referer so authenticated/CDN downloads NEVER get 403!
                runCatching {
                    val cookie = CookieManager.getInstance().getCookie(current.url)
                    if (!cookie.isNullOrBlank()) {
                        reqBuilder.header("Cookie", cookie)
                    }
                }
                if (current.refererUrl.startsWith("http", ignoreCase = true)) {
                    reqBuilder.header("Referer", current.refererUrl)
                }
                if (startOffset > 0L) {
                    reqBuilder.header("Range", "bytes=$startOffset-")
                }

                val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(reqBuilder.build())
                activeCalls[current.id] = call

                call.execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IllegalStateException("خطای سرور HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("پاسخ سرور خالی بود")

                    val serverHonoredRange = (resp.code == 206 && startOffset > 0L)
                    val writeOffset = if (serverHonoredRange) startOffset else 0L

                    val respMime = cleanMimeOrNull(body.contentType()?.let { "${it.type}/${it.subtype}" })
                    val respCd = resp.header("Content-Disposition").orEmpty()
                    val finalUrl = resp.request.url.toString()

                    // Upgrade filename if server returned real Content-Disposition or redirected to actual filename!
                    if (writeOffset == 0L) {
                        val betterName = sanitizeFileName(
                            guessBestFileName(finalUrl, respCd, respMime ?: cleanMimeOrNull(current.mimeType))
                        )
                        if (betterName.isNotBlank() &&
                            !betterName.equals(stFile.name, ignoreCase = true) &&
                            (stFile.name.startsWith("downloadfile", true) ||
                                stFile.name.endsWith(".bin", true) ||
                                respCd.isNotBlank())
                        ) {
                            val renamedFile = allocateUniqueStagingFile(betterName)
                            if (!stFile.exists() || stFile.renameTo(renamedFile)) {
                                stFile = renamedFile
                            }
                        }
                    }

                    val contentLen = body.contentLength()
                    val totalBytes = when {
                        contentLen > 0 && serverHonoredRange -> writeOffset + contentLen
                        contentLen > 0 -> contentLen
                        else -> -1L
                    }
                    val finalMime = resolveMimeForFileName(stFile.name, respMime ?: cleanMimeOrNull(current.mimeType))

                    current = current.copy(
                        fileName = stFile.name,
                        mimeType = finalMime,
                        bytesRead = writeOffset,
                        totalBytes = totalBytes,
                        localUriOrPath = stFile.absolutePath,
                        stagingPath = stFile.absolutePath
                    )
                    persistAndPublish(current)

                    RandomAccessFile(stFile, "rw").use { raf ->
                        if (writeOffset == 0L) {
                            raf.setLength(0L)
                        }
                        raf.seek(writeOffset)

                        val buf = ByteArray(32 * 1024)
                        val ins = body.byteStream()
                        var bytesCopied = writeOffset
                        var lastReportMs = System.currentTimeMillis()
                        var bytesAtLastReport = writeOffset

                        while (true) {
                            ensureActive()
                            val n = ins.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            bytesCopied += n

                            val now = System.currentTimeMillis()
                            val elapsedMs = now - lastReportMs
                            if (elapsedMs >= 350L) {
                                val deltaBytes = bytesCopied - bytesAtLastReport
                                val bps = (deltaBytes * 1000L) / elapsedMs.coerceAtLeast(1L)
                                lastReportMs = now
                                bytesAtLastReport = bytesCopied

                                current = current.copy(
                                    bytesRead = bytesCopied,
                                    totalBytes = totalBytes,
                                    speedBps = bps,
                                    state = DownloadState.DOWNLOADING
                                )
                                persistAndPublish(current)
                            }
                        }

                        exportCopyToPublicDownloadsQuietly(stFile, finalMime)

                        current = current.copy(
                            bytesRead = bytesCopied,
                            totalBytes = if (totalBytes > 0) totalBytes else bytesCopied,
                            speedBps = 0L,
                            state = DownloadState.COMPLETED,
                            localUriOrPath = stFile.absolutePath,
                            stagingPath = stFile.absolutePath,
                            errorMsg = ""
                        )
                        persistAndPublish(current)
                    }
                }

                onDownloadEventToast?.invoke("✅ دانلود کامل شد: ${current.fileName}", true)
            } catch (_: CancellationException) {
                // Paused or canceled cleanly by user
            } catch (t: Throwable) {
                val latest = dataStore.getDownloads().firstOrNull { it.id == current.id }
                if (latest != null && latest.state != DownloadState.PAUSED && latest.state != DownloadState.CANCELED) {
                    val savedBytes = if (stFile.exists()) stFile.length() else current.bytesRead
                    val failed = current.copy(
                        bytesRead = savedBytes,
                        speedBps = 0L,
                        state = DownloadState.FAILED,
                        errorMsg = t.message?.take(80).orEmpty().ifBlank { "خطا در اتصال شبکه" }
                    )
                    persistAndPublish(failed)
                    onDownloadEventToast?.invoke("⚠ خطا در دانلود ${current.fileName}: ${failed.errorMsg}", false)
                }
            } finally {
                activeCalls.remove(current.id)
                activeJobs.remove(current.id)
            }
        }
        activeJobs[initialRecord.id] = job
    }

    private fun exportCopyToPublicDownloadsQuietly(sourceFile: File, mimeType: String) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = appContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(itemUri)?.use { out ->
                    sourceFile.inputStream().use { inp -> inp.copyTo(out, 32 * 1024) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            } else if (ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                @Suppress("DEPRECATION")
                val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                pubDir.mkdirs()
                val dest = File(pubDir, sourceFile.name)
                if (dest.absolutePath != sourceFile.absolutePath) {
                    sourceFile.copyTo(dest, overwrite = true)
                }
            }
        }
    }

    private fun persistAndPublish(record: DownloadRecord) {
        dataStore.upsertDownload(record)
        _downloadsFlow.value = dataStore.getDownloads()
    }

    private fun stagingDir(): File {
        val extDir = runCatching { appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        val base = extDir ?: File(appContext.filesDir, "downloads")
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun allocateUniqueStagingFile(desiredName: String): File {
        val dir = stagingDir()
        val clean = sanitizeFileName(desiredName)
        var candidate = File(dir, clean)
        if (!candidate.exists()) return candidate

        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        for (i in 1..999) {
            candidate = File(dir, "${stem}_($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "${System.currentTimeMillis()}_$clean")
    }

    companion object {
        private const val DEFAULT_DOWNLOAD_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var INSTANCE: DownloadEngine? = null

        fun getInstance(context: Context): DownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun cleanMimeOrNull(mime: String?): String? {
            val m = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ENGLISH).orEmpty()
            if (m.isEmpty() || m == "*/*" || m == "image/*" || m == "video/*" || m == "application/octet-stream") {
                return null
            }
            return m
        }

        private fun extractMimeFromDataUri(s: String): String? {
            if (!s.startsWith("data:", true)) return null
            return s.substringAfter("data:").substringBefore(';').substringBefore(',').trim().ifBlank { null }
        }

        fun guessBestFileName(url: String, contentDisposition: String, mimeType: String?): String {
            // 1. Parse RFC 5987 filename*=UTF-8''... or filename="..." explicitly
            if (contentDisposition.isNotBlank()) {
                val utf8Match = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;\r\n]+)""")
                    .find(contentDisposition)?.groupValues?.getOrNull(1)
                if (!utf8Match.isNullOrBlank()) {
                    val decoded = runCatching { URLDecoder.decode(utf8Match.trim().trim('"', '\''), "UTF-8") }.getOrNull()
                    if (!decoded.isNullOrBlank()) return ensureExtensionMatchesMime(decoded, mimeType)
                }
                val stdMatch = Regex("""filename\s*=\s*"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
                    .find(contentDisposition)?.groupValues?.getOrNull(1)
                if (!stdMatch.isNullOrBlank()) {
                    val decoded = runCatching { URLDecoder.decode(stdMatch.trim(), "UTF-8") }.getOrDefault(stdMatch.trim())
                    if (decoded.isNotBlank()) return ensureExtensionMatchesMime(decoded, mimeType)
                }
            }

            // 2. Pure Kotlin/Java URL path segment extraction (ignores domain when path is empty!)
            val pathPart = url
                .substringBefore('#')
                .substringBefore('?')
                .let { cleanUrl ->
                    if (cleanUrl.contains("://")) cleanUrl.substringAfter("://").substringAfter('/', "")
                    else cleanUrl.substringAfter('/', "")
                }
                .trimEnd('/')
            val rawPathSeg = (if (pathPart.contains('/')) pathPart.substringAfterLast('/') else pathPart)
                .let { seg -> runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg) }
                .trim()
            if (rawPathSeg.isNotBlank() && rawPathSeg.contains('.') && !rawPathSeg.contains(':') && rawPathSeg.length <= 120) {
                return ensureExtensionMatchesMime(rawPathSeg, mimeType)
            }

            val guessed = runCatching { URLUtil.guessFileName(url, contentDisposition, mimeType) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "download_${System.currentTimeMillis()}"
            return ensureExtensionMatchesMime(guessed, mimeType)
        }

        private fun ensureExtensionMatchesMime(name: String, mimeType: String?): String {
            if (mimeType.isNullOrBlank()) return name
            val lower = name.lowercase(Locale.ENGLISH)
            if (lower.endsWith(".bin") || !lower.contains('.')) {
                val ext = extensionForMime(mimeType)
                val stem = if (lower.endsWith(".bin")) name.dropLast(4) else name
                return "$stem.$ext"
            }
            return name
        }

        fun extensionForMime(mime: String): String {
            val m = mime.lowercase(Locale.ENGLISH).trim()
            return when (m) {
                "application/vnd.android.package-archive" -> "apk"
                "video/mp4" -> "mp4"
                "video/webm" -> "webm"
                "video/x-matroska" -> "mkv"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/aac" -> "m4a"
                "audio/ogg" -> "ogg"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "application/pdf" -> "pdf"
                "application/zip" -> "zip"
                "application/x-rar-compressed", "application/vnd.rar" -> "rar"
                "text/plain" -> "txt"
                "text/html" -> "html"
                else -> runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(m) }.getOrNull() ?: "bin"
            }
        }

        fun resolveMimeForFileName(fileName: String, fallbackMime: String?): String {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ENGLISH)
            val byExt = when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "ogg", "opus" -> "audio/ogg"
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "zip" -> "application/zip"
                "mht", "mhtml" -> "multipart/related"
                else -> runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
            }
            return byExt ?: fallbackMime ?: "application/octet-stream"
        }

        fun sanitizeFileName(raw: String): String {
            val cleaned = raw
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), "_")
                .trim()
                .trim('.')
            if (cleaned.isEmpty()) return "download_${System.currentTimeMillis()}.bin"
            return if (cleaned.length > 110) {
                val ext = cleaned.substringAfterLast('.', "")
                if (ext.isNotEmpty() && ext.length <= 8) {
                    cleaned.take(96) + "." + ext
                } else {
                    cleaned.take(105)
                }
            } else {
                cleaned
            }
        }
    }
}
