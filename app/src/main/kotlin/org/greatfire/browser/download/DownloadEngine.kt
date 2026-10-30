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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Request
import org.greatfire.browser.data.BrowserDataStore
import org.greatfire.browser.data.DownloadRecord
import org.greatfire.browser.data.DownloadState
import org.greatfire.browser.media.HlsPlaylistParser
import org.greatfire.browser.vpn.TunnelManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class DownloadEngine private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = BrowserDataStore.getInstance(appContext)
    private val tunnelManager = TunnelManager.getInstance(appContext)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()

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
        refererUrl: String = "",
        suggestedFileNameOverride: String = ""
    ): DownloadRecord? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("data:", ignoreCase = true)) {
            saveDataUri(trimmed)
            return null
        }

        val initialMime = cleanMimeOrNull(mimeType)
        val isHlsRequest = isHlsUrlOrMime(trimmed, initialMime)
        val rawDesiredName = when {
            suggestedFileNameOverride.isNotBlank() -> suggestedFileNameOverride
            isHlsRequest -> {
                val base = guessBestFileName(trimmed, contentDisposition, "video/mp4")
                    .removeSuffix(".m3u8")
                    .removeSuffix(".m3u")
                    .removeSuffix(".bin")
                "$base.mp4"
            }
            else -> guessBestFileName(trimmed, contentDisposition, initialMime)
        }

        val initialName = sanitizeFileName(rawDesiredName)
        val stagingFile = allocateUniqueStagingFile(initialName)

        val record = DownloadRecord(
            fileName = stagingFile.name,
            url = trimmed,
            mimeType = if (isHlsRequest) "application/vnd.apple.mpegurl" else resolveMimeForFileName(stagingFile.name, initialMime),
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
        cancelCallsForId(id)
        activeJobs.remove(id)?.cancel()
        val existing = dataStore.getDownloads().firstOrNull { it.id == id } ?: return
        if (existing.state == DownloadState.DOWNLOADING) {
            val fileLen = existing.stagingPath.takeIf { it.isNotBlank() }?.let { f ->
                val file = File(f)
                if (file.exists()) file.length() else 0L
            } ?: existing.bytesRead
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
        val isHls = isHlsUrlOrMime(existing.url, existing.mimeType)
        val canResume = !isHls && stFile.exists() && stFile.length() > 0L
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
        cancelCallsForId(id)
        activeJobs.remove(id)?.cancel()
        val existing = dataStore.getDownloads().firstOrNull { it.id == id } ?: return
        runCatching {
            if (existing.stagingPath.isNotBlank()) {
                File(existing.stagingPath).delete()
                File("${existing.stagingPath}.hls.ts").delete()
            }
        }
        dataStore.deleteDownloadRecord(id)
        refreshSnapshot()
    }

    fun deleteDownload(id: String, deleteDiskFile: Boolean = false) {
        cancelCallsForId(id)
        activeJobs.remove(id)?.cancel()
        if (deleteDiskFile) {
            val existing = dataStore.getDownloads().firstOrNull { it.id == id }
            if (existing != null && existing.stagingPath.isNotBlank()) {
                runCatching {
                    File(existing.stagingPath).delete()
                    File("${existing.stagingPath}.hls.ts").delete()
                }
            }
        }
        dataStore.deleteDownloadRecord(id)
        refreshSnapshot()
    }

    fun clearFinishedDownloads() {
        val activeOnly = dataStore.getDownloads().filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED
        }
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

    private fun registerCall(id: String, call: Call) {
        val set = activeCalls.getOrPut(id) { ConcurrentHashMap.newKeySet() }
        set.add(call)
    }

    private fun unregisterCall(id: String, call: Call) {
        activeCalls[id]?.remove(call)
    }

    private fun cancelCallsForId(id: String) {
        activeCalls.remove(id)?.forEach { runCatching { it.cancel() } }
    }

    private fun buildAuthenticatedRequest(
        url: String,
        userAgent: String,
        refererUrl: String,
        rangeHeader: String? = null
    ): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_DOWNLOAD_UA })
            .header("Accept", "*/*")
        runCatching {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                b.header("Cookie", cookie)
            }
        }
        if (refererUrl.startsWith("http", ignoreCase = true)) {
            b.header("Referer", refererUrl)
        }
        if (!rangeHeader.isNullOrBlank()) {
            b.header("Range", rangeHeader)
        }
        return b.build()
    }

    private fun launchWorker(initialRecord: DownloadRecord, resumeFromExisting: Boolean) {
        val job = scope.launch {
            var current = initialRecord
            var stFile = File(current.stagingPath)
            stFile.parentFile?.mkdirs()

            try {
                tunnelManager.ensureRunning()

                if (isHlsUrlOrMime(current.url, current.mimeType)) {
                    runHlsStreamDownload(current, stFile)
                    return@launch
                }

                // IDM Multi-Connection Probe (for fresh downloads >= 2.5 MB that support HTTP 206 Range!)
                if (!resumeFromExisting) {
                    val probe = probeRangeSupport(current)
                    if (probe != null && probe.supportsRange && probe.totalLength >= MIN_MULTI_THREAD_BYTES) {
                        stFile = maybeUpgradeFileNameFromProbe(current, stFile, probe.finalUrl, probe.contentDisposition, probe.contentType)
                        val finalMime = resolveMimeForFileName(stFile.name, probe.contentType ?: cleanMimeOrNull(current.mimeType))
                        current = current.copy(
                            fileName = stFile.name,
                            mimeType = finalMime,
                            totalBytes = probe.totalLength,
                            localUriOrPath = stFile.absolutePath,
                            stagingPath = stFile.absolutePath
                        )
                        persistAndPublish(current)
                        runParallelRangeDownload(current, stFile, probe.totalLength, finalMime)
                        return@launch
                    }
                }

                runSingleStreamDownload(current, stFile, resumeFromExisting)
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
                        errorMsg = t.message?.take(90).orEmpty().ifBlank { "خطا در اتصال شبکه" }
                    )
                    persistAndPublish(failed)
                    onDownloadEventToast?.invoke("⚠ خطا در دانلود ${current.fileName}: ${failed.errorMsg}", false)
                }
            } finally {
                cancelCallsForId(current.id)
                activeJobs.remove(current.id)
            }
        }
        activeJobs[initialRecord.id] = job
    }

    private data class RangeProbeResult(
        val totalLength: Long,
        val supportsRange: Boolean,
        val contentType: String?,
        val contentDisposition: String,
        val finalUrl: String
    )

    private fun probeRangeSupport(rec: DownloadRecord): RangeProbeResult? {
        return runCatching {
            val req = buildAuthenticatedRequest(rec.url, rec.userAgent, rec.refererUrl, "bytes=0-0")
            val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(req)
            registerCall(rec.id, call)
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) return null
                    val ct = cleanMimeOrNull(resp.body?.contentType()?.let { "${it.type}/${it.subtype}" })
                    if (expectsMediaFile(rec) && ct != null && ct.startsWith("text/html")) {
                        throw IllegalStateException("لینک ویدیو منقضی شده یا صفحه وب برگردانده شد")
                    }
                    val cd = resp.header("Content-Disposition").orEmpty()
                    val finalUrl = resp.request.url.toString()
                    val isPartial = resp.code == 206
                    val total = if (isPartial) {
                        resp.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
                    } else {
                        -1L
                    }
                    RangeProbeResult(
                        totalLength = total,
                        supportsRange = isPartial && total > 0L,
                        contentType = ct,
                        contentDisposition = cd,
                        finalUrl = finalUrl
                    )
                }
            } finally {
                unregisterCall(rec.id, call)
            }
        }.onFailure { err ->
            if (err is IllegalStateException && err.message?.contains("منقضی") == true) {
                throw err
            }
        }.getOrNull()
    }

    private fun maybeUpgradeFileNameFromProbe(
        rec: DownloadRecord,
        stFile: File,
        finalUrl: String,
        respCd: String,
        respMime: String?
    ): File {
        val betterName = sanitizeFileName(
            guessBestFileName(finalUrl, respCd, respMime ?: cleanMimeOrNull(rec.mimeType))
        )
        if (betterName.isNotBlank() &&
            !betterName.equals(stFile.name, ignoreCase = true) &&
            (stFile.name.startsWith("downloadfile", true) ||
                stFile.name.endsWith(".bin", true) ||
                respCd.isNotBlank())
        ) {
            val renamedFile = allocateUniqueStagingFile(betterName)
            if (!stFile.exists() || stFile.renameTo(renamedFile)) {
                return renamedFile
            }
        }
        return stFile
    }

    /**
     * IDM 4-Connection Parallel HTTP Range Downloader!
     */
    private suspend fun runParallelRangeDownload(
        initialRecord: DownloadRecord,
        stFile: File,
        totalBytes: Long,
        finalMime: String
    ) {
        val parts = if (totalBytes >= 32L * 1024 * 1024) 6 else 4
        val partSize = totalBytes / parts
        val totalReadAtomic = AtomicLong(0L)
        val reportLock = Any()
        var lastReportMs = System.currentTimeMillis()
        var bytesAtLastReport = 0L

        RandomAccessFile(stFile, "rw").use { raf ->
            raf.setLength(totalBytes)
            val channel = raf.channel
            val limiter = Semaphore(parts)

            coroutineScope {
                (0 until parts).map { idx ->
                    val start = idx * partSize
                    val endInclusive = if (idx == parts - 1) totalBytes - 1 else (idx + 1) * partSize - 1
                    async(Dispatchers.IO) {
                        limiter.withPermit {
                            downloadByteRangePart(
                                rec = initialRecord,
                                channel = channel,
                                start = start,
                                endInclusive = endInclusive,
                                onChunkWritten = { written ->
                                    val nowTotal = totalReadAtomic.addAndGet(written.toLong())
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportMs >= 350L) {
                                        synchronized(reportLock) {
                                            val elapsed = now - lastReportMs
                                            if (elapsed >= 350L) {
                                                val delta = nowTotal - bytesAtLastReport
                                                val bps = (delta * 1000L) / elapsed.coerceAtLeast(1L)
                                                lastReportMs = now
                                                bytesAtLastReport = nowTotal
                                                persistAndPublish(
                                                    initialRecord.copy(
                                                        bytesRead = nowTotal,
                                                        totalBytes = totalBytes,
                                                        speedBps = bps,
                                                        state = DownloadState.DOWNLOADING
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }.forEach { it.await() }
            }
        }

        exportCopyToPublicDownloadsQuietly(stFile, finalMime)
        val completed = initialRecord.copy(
            bytesRead = totalBytes,
            totalBytes = totalBytes,
            speedBps = 0L,
            state = DownloadState.COMPLETED,
            mimeType = finalMime,
            localUriOrPath = stFile.absolutePath,
            stagingPath = stFile.absolutePath,
            errorMsg = ""
        )
        persistAndPublish(completed)
        onDownloadEventToast?.invoke("✅ دانلود پرسرعت کامل شد: ${completed.fileName}", true)
    }

    private suspend fun downloadByteRangePart(
        rec: DownloadRecord,
        channel: java.nio.channels.FileChannel,
        start: Long,
        endInclusive: Long,
        onChunkWritten: (Int) -> Unit
    ) {
        var pos = start
        var attempt = 0
        val buf = ByteArray(64 * 1024)
        while (pos <= endInclusive) {
            coroutineContext.ensureActive()
            try {
                val req = buildAuthenticatedRequest(
                    rec.url,
                    rec.userAgent,
                    rec.refererUrl,
                    "bytes=$pos-$endInclusive"
                )
                val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(req)
                registerCall(rec.id, call)
                try {
                    call.execute().use { resp ->
                        if (resp.code != 206) {
                            throw IllegalStateException("HTTP ${resp.code}")
                        }
                        val ins = resp.body?.byteStream() ?: throw IllegalStateException("Empty range body")
                        ins.use { stream ->
                            while (pos <= endInclusive) {
                                coroutineContext.ensureActive()
                                val maxToRead = minOf(buf.size.toLong(), endInclusive - pos + 1L).toInt()
                                val n = stream.read(buf, 0, maxToRead)
                                if (n <= 0) break
                                var writtenInSlice = 0
                                val bb = ByteBuffer.wrap(buf, 0, n)
                                while (bb.hasRemaining()) {
                                    writtenInSlice += channel.write(bb, pos + writtenInSlice)
                                }
                                pos += n
                                onChunkWritten(n)
                            }
                        }
                    }
                } finally {
                    unregisterCall(rec.id, call)
                }
                if (pos <= endInclusive) {
                    throw IllegalStateException("اتصال قطعه ناقص ماند")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (++attempt > 5) throw e
                delay(550L * attempt)
            }
        }
    }

    /**
     * IDM HLS (`.m3u8`) Concurrent Segment Downloader + Lossless MP4 Remuxer!
     */
    private suspend fun runHlsStreamDownload(initialRecord: DownloadRecord, targetFile: File) {
        var playlistUrl = initialRecord.url
        var manifestText = fetchTextThroughTunnel(initialRecord, playlistUrl)

        if (!HlsPlaylistParser.looksLikeHlsManifest(manifestText)) {
            throw IllegalStateException("لیست پخش استریم (.m3u8) معتبر نیست یا منقضی شده است")
        }

        if (HlsPlaylistParser.isMasterPlaylist(manifestText)) {
            val variants = HlsPlaylistParser.parseMasterPlaylist(manifestText, playlistUrl)
            if (variants.isEmpty()) {
                throw IllegalStateException("هیچ کیفیتی در استریم یافت نشد")
            }
            playlistUrl = variants.first().url
            manifestText = fetchTextThroughTunnel(initialRecord, playlistUrl)
        }

        val mediaPlaylist = HlsPlaylistParser.parseMediaPlaylist(manifestText, playlistUrl)
        if (mediaPlaylist.isDrmOrEncrypted) {
            throw IllegalStateException("این ویدیو دارای قفل DRM/رمزنگاری (${mediaPlaylist.encryptionMethod}) است")
        }
        if (mediaPlaylist.segments.isEmpty()) {
            throw IllegalStateException("قطعات ویدیو در لیست پخش یافت نشد")
        }

        val tempTsFile = File("${targetFile.absolutePath}.hls.ts")
        if (tempTsFile.exists()) tempTsFile.delete()

        val totalSegments = mediaPlaylist.segments.size
        var totalBytesWritten = 0L
        var lastReportMs = System.currentTimeMillis()
        var bytesAtLastReport = 0L

        FileOutputStream(tempTsFile).use { fos ->
            mediaPlaylist.initMapUrl?.let { initUrl ->
                val initBytes = fetchSegmentBytesWithRetry(initialRecord, initUrl)
                fos.write(initBytes)
                totalBytesWritten += initBytes.size
            }

            val limiter = Semaphore(4)
            coroutineScope {
                val window = ArrayDeque<Deferred<ByteArray>>()
                var nextIdx = 0
                var writtenCount = 0

                while (writtenCount < totalSegments) {
                    while (nextIdx < totalSegments && window.size < 8) {
                        val segUrl = mediaPlaylist.segments[nextIdx].url
                        window.addLast(
                            async(Dispatchers.IO) {
                                limiter.withPermit {
                                    fetchSegmentBytesWithRetry(initialRecord, segUrl)
                                }
                            }
                        )
                        nextIdx++
                    }

                    val segBytes = window.removeFirst().await()
                    coroutineContext.ensureActive()
                    fos.write(segBytes)
                    writtenCount++
                    totalBytesWritten += segBytes.size

                    val now = System.currentTimeMillis()
                    if (now - lastReportMs >= 350L || writtenCount == totalSegments) {
                        val elapsed = (now - lastReportMs).coerceAtLeast(1L)
                        val bps = ((totalBytesWritten - bytesAtLastReport) * 1000L) / elapsed
                        lastReportMs = now
                        bytesAtLastReport = totalBytesWritten

                        val estimatedTotal = if (writtenCount > 0) {
                            (totalBytesWritten * totalSegments) / writtenCount
                        } else -1L

                        persistAndPublish(
                            initialRecord.copy(
                                bytesRead = totalBytesWritten,
                                totalBytes = estimatedTotal,
                                speedBps = bps,
                                state = DownloadState.DOWNLOADING,
                                errorMsg = "قطعه $writtenCount از $totalSegments"
                            )
                        )
                    }
                }
            }
        }

        // Losslessly remux MPEG-TS -> MP4 via Android MediaMuxer so Gallery & all players support seeking!
        val finalFile: File
        val finalMime: String
        val remuxedOk = if (mediaPlaylist.initMapUrl == null) {
            MediaRemuxer.remuxTsToMp4(tempTsFile, targetFile)
        } else {
            tempTsFile.renameTo(targetFile)
        }

        if (remuxedOk && targetFile.exists() && targetFile.length() > 0L) {
            tempTsFile.delete()
            finalFile = targetFile
            finalMime = "video/mp4"
        } else {
            val fallbackTs = File(
                targetFile.parentFile,
                targetFile.nameWithoutExtension + ".ts"
            )
            if (tempTsFile.renameTo(fallbackTs)) {
                finalFile = fallbackTs
            } else {
                finalFile = tempTsFile
            }
            finalMime = "video/mp2t"
        }

        exportCopyToPublicDownloadsQuietly(finalFile, finalMime)
        val finalLen = finalFile.length()
        val doneRecord = initialRecord.copy(
            fileName = finalFile.name,
            mimeType = finalMime,
            bytesRead = finalLen,
            totalBytes = finalLen,
            speedBps = 0L,
            state = DownloadState.COMPLETED,
            localUriOrPath = finalFile.absolutePath,
            stagingPath = finalFile.absolutePath,
            errorMsg = ""
        )
        persistAndPublish(doneRecord)
        onDownloadEventToast?.invoke("✅ دانلود استریم کامل شد: ${doneRecord.fileName}", true)
    }

    private fun fetchTextThroughTunnel(rec: DownloadRecord, url: String): String {
        val req = buildAuthenticatedRequest(url, rec.userAgent, rec.refererUrl)
        val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(req)
        registerCall(rec.id, call)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطای سرور HTTP ${resp.code}")
                }
                return resp.body?.string().orEmpty()
            }
        } finally {
            unregisterCall(rec.id, call)
        }
    }

    private suspend fun fetchSegmentBytesWithRetry(rec: DownloadRecord, segUrl: String): ByteArray {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                val req = buildAuthenticatedRequest(segUrl, rec.userAgent, rec.refererUrl)
                val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(req)
                registerCall(rec.id, call)
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code}")
                        }
                        val body = resp.body ?: return ByteArray(0)
                        val out = ByteArrayOutputStream(body.contentLength().coerceIn(4096L, 16L * 1024 * 1024).toInt())
                        val buf = ByteArray(32 * 1024)
                        body.byteStream().use { ins ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = ins.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        return out.toByteArray()
                    }
                } finally {
                    unregisterCall(rec.id, call)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (++attempt > 5) throw e
                delay(500L * attempt)
            }
        }
    }

    private suspend fun runSingleStreamDownload(
        initialRecord: DownloadRecord,
        initialFile: File,
        resumeFromExisting: Boolean
    ) {
        var current = initialRecord
        var stFile = initialFile
        val startOffset = if (resumeFromExisting && stFile.exists()) stFile.length() else 0L
        val req = buildAuthenticatedRequest(
            current.url,
            current.userAgent,
            current.refererUrl,
            if (startOffset > 0L) "bytes=$startOffset-" else null
        )

        val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(req)
        registerCall(current.id, call)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IllegalStateException("خطای سرور HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("پاسخ سرور خالی بود")
                val respMime = cleanMimeOrNull(body.contentType()?.let { "${it.type}/${it.subtype}" })
                if (expectsMediaFile(current) && respMime != null && respMime.startsWith("text/html")) {
                    throw IllegalStateException("لینک ویدیو منقضی شده یا صفحه ورود برگردانده شد")
                }

                val serverHonoredRange = (resp.code == 206 && startOffset > 0L)
                val writeOffset = if (serverHonoredRange) startOffset else 0L
                val respCd = resp.header("Content-Disposition").orEmpty()
                val finalUrl = resp.request.url.toString()

                if (writeOffset == 0L) {
                    stFile = maybeUpgradeFileNameFromProbe(current, stFile, finalUrl, respCd, respMime)
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
                        coroutineContext.ensureActive()
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
        } finally {
            unregisterCall(current.id, call)
        }

        onDownloadEventToast?.invoke("✅ دانلود کامل شد: ${current.fileName}", true)
    }

    private fun expectsMediaFile(rec: DownloadRecord): Boolean {
        val m = rec.mimeType.lowercase(Locale.US)
        val n = rec.fileName.lowercase(Locale.US)
        return m.startsWith("video/") || m.startsWith("audio/") ||
            n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mp3") || n.endsWith(".m4a")
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
        private const val MIN_MULTI_THREAD_BYTES = 2_621_440L // 2.5 MB
        const val DEFAULT_DOWNLOAD_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var INSTANCE: DownloadEngine? = null

        fun getInstance(context: Context): DownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isHlsUrlOrMime(url: String, mimeType: String?): Boolean {
            val cleanPath = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
            if (cleanPath.endsWith(".m3u8") || cleanPath.contains(".m3u8/")) return true
            val m = mimeType?.lowercase(Locale.US).orEmpty()
            return m.contains("mpegurl")
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
                "video/mp4", "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "mp4"
                "video/mp2t" -> "ts"
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
                "apk" -> "application/octet-stream"
                "mp4", "m4v" -> "video/mp4"
                "ts" -> "video/mp2t"
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
