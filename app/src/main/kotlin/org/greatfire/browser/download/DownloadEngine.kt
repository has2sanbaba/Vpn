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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
        reconcilePausedProgressOnStartup()
        refreshSnapshot()
    }

    private fun reconcilePausedProgressOnStartup() {
        for (rec in dataStore.getDownloads()) {
            if (rec.state == DownloadState.PAUSED && rec.stagingPath.isNotBlank()) {
                val diskBytes = computePersistedBytesForStaging(File(rec.stagingPath))
                if (diskBytes > 0L && diskBytes != rec.bytesRead) {
                    dataStore.upsertDownload(rec.copy(bytesRead = diskBytes))
                }
            }
        }
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
        suggestedFileNameOverride: String = "",
        secondaryAudioUrl: String = ""
    ): DownloadRecord? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("data:", ignoreCase = true)) {
            saveDataUri(trimmed)
            return null
        }

        val initialMime = cleanMimeOrNull(mimeType)
        val isHlsRequest = isHlsUrlOrMime(trimmed, initialMime) || secondaryAudioUrl.isNotBlank()
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
            secondaryAudioUrl = secondaryAudioUrl.trim(),
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
        launchWorker(record)
        return record
    }

    fun pauseDownload(id: String) {
        cancelCallsForId(id)
        activeJobs.remove(id)?.cancel()
        val existing = dataStore.getDownloads().firstOrNull { it.id == id } ?: return
        if (existing.state == DownloadState.DOWNLOADING) {
            val diskBytes = existing.stagingPath.takeIf { it.isNotBlank() }?.let {
                computePersistedBytesForStaging(File(it))
            } ?: 0L
            val accurateBytes = if (diskBytes > 0L) diskBytes else existing.bytesRead
            persistAndPublish(
                existing.copy(
                    bytesRead = accurateBytes,
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
        val persistedBytes = computePersistedBytesForStaging(stFile)
        val savedPlan = loadShardPlan(stFile)
        val total = when {
            savedPlan != null && savedPlan.totalBytes > 0L -> savedPlan.totalBytes
            existing.totalBytes > 0L && persistedBytes > 0L -> existing.totalBytes
            else -> -1L
        }

        val updated = existing.copy(
            bytesRead = persistedBytes,
            totalBytes = total,
            speedBps = 0L,
            state = DownloadState.DOWNLOADING,
            stagingPath = stFile.absolutePath,
            localUriOrPath = stFile.absolutePath,
            errorMsg = ""
        )
        persistAndPublish(updated)
        launchWorker(updated)
    }

    fun cancelDownload(id: String) {
        cancelCallsForId(id)
        activeJobs.remove(id)?.cancel()
        val existing = dataStore.getDownloads().firstOrNull { it.id == id } ?: return
        if (existing.stagingPath.isNotBlank()) {
            cleanupStagingArtifacts(File(existing.stagingPath), deleteTargetFile = true)
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
                cleanupStagingArtifacts(File(existing.stagingPath), deleteTargetFile = true)
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

    private fun launchWorker(initialRecord: DownloadRecord) {
        val job = scope.launch {
            var current = initialRecord
            var stFile = File(current.stagingPath)
            stFile.parentFile?.mkdirs()

            try {
                tunnelManager.ensureRunning()

                // 1. HLS Transfer (Resumable via .hlsdir segment checkpoints + Split Audio/Video muxing!)
                if (isHlsUrlOrMime(current.url, current.mimeType) ||
                    current.secondaryAudioUrl.isNotBlank() ||
                    hlsStagingDirFor(stFile).exists()
                ) {
                    runHlsStreamDownload(current, stFile)
                    return@launch
                }

                // 2. Resume an existing Multi-Part HTTP Range checkpoint (.shards.json + .part0..N)
                val existingShardPlan = loadShardPlan(stFile)
                if (existingShardPlan != null && existingShardPlan.totalBytes > 0L) {
                    runParallelRangeDownload(current, stFile, existingShardPlan)
                    return@launch
                }

                // 3. Resume an existing Single-Connection partial file (stFile.length() > 0)
                if (stFile.exists() && stFile.length() > 0L) {
                    runSingleStreamDownload(current, stFile, resumeFromExisting = true)
                    return@launch
                }

                // 4. Fresh Direct Transfer: Probe server Range & Content-Type (also detects extensionless HLS!)
                val probe = probeRangeSupport(current)
                if (probe != null && isHlsUrlOrMime(probe.finalUrl, probe.contentType)) {
                    val hlsName = stFile.nameWithoutExtension + ".mp4"
                    stFile = maybeUpgradeFileNameFromProbe(current, stFile, probe.finalUrl, "", "video/mp4")
                    if (!stFile.name.endsWith(".mp4", true)) {
                        stFile = allocateUniqueStagingFile(hlsName)
                    }
                    current = current.copy(
                        fileName = stFile.name,
                        mimeType = "application/vnd.apple.mpegurl",
                        localUriOrPath = stFile.absolutePath,
                        stagingPath = stFile.absolutePath
                    )
                    persistAndPublish(current)
                    runHlsStreamDownload(current, stFile)
                    return@launch
                }

                if (probe != null && probe.supportsRange && probe.totalLength >= MIN_MULTI_THREAD_BYTES) {
                    stFile = maybeUpgradeFileNameFromProbe(
                        current,
                        stFile,
                        probe.finalUrl,
                        probe.contentDisposition,
                        probe.contentType
                    )
                    val finalMime = resolveMimeForFileName(
                        stFile.name,
                        probe.contentType ?: cleanMimeOrNull(current.mimeType)
                    )
                    val partCount = if (probe.totalLength >= 32L * 1024 * 1024) 6 else 4
                    val plan = ParallelShardPlan(
                        totalBytes = probe.totalLength,
                        finalMime = finalMime,
                        shards = buildParallelShards(probe.totalLength, partCount)
                    )
                    saveShardPlan(stFile, plan)
                    current = current.copy(
                        fileName = stFile.name,
                        mimeType = finalMime,
                        totalBytes = probe.totalLength,
                        localUriOrPath = stFile.absolutePath,
                        stagingPath = stFile.absolutePath
                    )
                    persistAndPublish(current)
                    runParallelRangeDownload(current, stFile, plan)
                    return@launch
                }

                runSingleStreamDownload(current, stFile, resumeFromExisting = false)
            } catch (_: CancellationException) {
                // Paused or canceled cleanly; exact checkpoint progress remains on disk!
                val latest = dataStore.getDownloads().firstOrNull { it.id == current.id }
                if (latest != null && latest.state == DownloadState.PAUSED) {
                    val exactBytes = computePersistedBytesForStaging(stFile)
                    if (exactBytes > 0L && exactBytes != latest.bytesRead) {
                        persistAndPublish(latest.copy(bytesRead = exactBytes, speedBps = 0L))
                    }
                }
            } catch (t: Throwable) {
                val latest = dataStore.getDownloads().firstOrNull { it.id == current.id }
                if (latest != null && latest.state != DownloadState.PAUSED && latest.state != DownloadState.CANCELED) {
                    val savedBytes = computePersistedBytesForStaging(stFile).let {
                        if (it > 0L) it else latest.bytesRead
                    }
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
                        resp.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
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
     * IDM Resumable Multi-Connection Parallel HTTP Range Downloader!
     * Each shard writes to its own `<stFile>.part{i}` file so pausing, resuming, or recovering after
     * process death ALWAYS continues from `shard.start + partFile.length()` without losing a single byte!
     */
    private suspend fun runParallelRangeDownload(
        initialRecord: DownloadRecord,
        stFile: File,
        plan: ParallelShardPlan
    ) {
        val totalBytes = plan.totalBytes
        val initialBytesOnDisk = computeShardBytesOnDisk(stFile, plan)
        val totalReadAtomic = AtomicLong(initialBytesOnDisk)
        val reportLock = Any()
        var lastReportMs = System.currentTimeMillis()
        var bytesAtLastReport = initialBytesOnDisk

        persistAndPublish(
            initialRecord.copy(
                bytesRead = initialBytesOnDisk,
                totalBytes = totalBytes,
                speedBps = 0L,
                state = DownloadState.DOWNLOADING
            )
        )

        val limiter = Semaphore(plan.shards.size.coerceAtLeast(1))
        coroutineScope {
            plan.shards.map { shard ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        downloadShardToPartFile(
                            rec = initialRecord,
                            partFile = partFileFor(stFile, shard.index),
                            shard = shard,
                            onChunkWritten = { written ->
                                val nowTotal = totalReadAtomic.addAndGet(written.toLong())
                                val now = System.currentTimeMillis()
                                if (now - lastReportMs >= 350L) {
                                    synchronized(reportLock) {
                                        val elapsed = now - lastReportMs
                                        if (elapsed >= 350L) {
                                            val delta = (nowTotal - bytesAtLastReport).coerceAtLeast(0L)
                                            val bps = (delta * 1000L) / elapsed.coerceAtLeast(1L)
                                            lastReportMs = now
                                            bytesAtLastReport = nowTotal
                                            persistAndPublish(
                                                initialRecord.copy(
                                                    bytesRead = nowTotal.coerceAtMost(totalBytes),
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
            }.awaitAll()
        }

        coroutineContext.ensureActive()

        // Verify all shards completed cleanly before joining
        val assembledSum = computeShardBytesOnDisk(stFile, plan)
        if (assembledSum != totalBytes) {
            throw IllegalStateException("قطعات دانلود کامل نشدند ($assembledSum از $totalBytes بایت)")
        }

        val joinTemp = File("${stFile.absolutePath}.joining")
        FileOutputStream(joinTemp, false).use { out ->
            val copyBuf = ByteArray(256 * 1024)
            for (shard in plan.shards) {
                coroutineContext.ensureActive()
                partFileFor(stFile, shard.index).inputStream().use { inp ->
                    while (true) {
                        val n = inp.read(copyBuf)
                        if (n <= 0) break
                        out.write(copyBuf, 0, n)
                    }
                }
            }
            out.flush()
        }

        if (stFile.exists()) stFile.delete()
        if (!joinTemp.renameTo(stFile)) {
            joinTemp.copyTo(stFile, overwrite = true)
            joinTemp.delete()
        }

        // Remove part checkpoints now that the joined target file is verified complete
        cleanupStagingArtifacts(stFile, deleteTargetFile = false)

        exportCopyToPublicDownloadsQuietly(stFile, plan.finalMime)
        val completed = initialRecord.copy(
            bytesRead = totalBytes,
            totalBytes = totalBytes,
            speedBps = 0L,
            state = DownloadState.COMPLETED,
            mimeType = plan.finalMime,
            localUriOrPath = stFile.absolutePath,
            stagingPath = stFile.absolutePath,
            errorMsg = ""
        )
        persistAndPublish(completed)
        onDownloadEventToast?.invoke("✅ دانلود پرسرعت کامل شد: ${completed.fileName}", true)
    }

    private suspend fun downloadShardToPartFile(
        rec: DownloadRecord,
        partFile: File,
        shard: ByteShard,
        onChunkWritten: (Int) -> Unit
    ) {
        val expected = shard.expectedBytes
        var have = if (partFile.exists()) partFile.length() else 0L
        if (have > expected) {
            // Truncate any corrupt excess bytes beyond shard boundary
            RandomAccessFile(partFile, "rw").use { it.setLength(expected) }
            have = expected
        }
        if (have == expected) return

        var attempt = 0
        val buf = ByteArray(64 * 1024)
        while (have < expected) {
            coroutineContext.ensureActive()
            val reqStart = shard.start + have
            try {
                val req = buildAuthenticatedRequest(
                    rec.url,
                    rec.userAgent,
                    rec.refererUrl,
                    "bytes=$reqStart-${shard.endInclusive}"
                )
                val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(req)
                registerCall(rec.id, call)
                try {
                    call.execute().use { resp ->
                        if (resp.code != 206) {
                            throw IllegalStateException("سرور از ادامه قطعه پشتیبانی نکرد (HTTP ${resp.code})")
                        }
                        val ins = resp.body?.byteStream() ?: throw IllegalStateException("پاسخ قطعه خالی بود")
                        FileOutputStream(partFile, have > 0L).use { fos ->
                            ins.use { stream ->
                                while (have < expected) {
                                    coroutineContext.ensureActive()
                                    val maxToRead = minOf(buf.size.toLong(), expected - have).toInt()
                                    val n = stream.read(buf, 0, maxToRead)
                                    if (n <= 0) break
                                    fos.write(buf, 0, n)
                                    have += n
                                    onChunkWritten(n)
                                }
                            }
                            fos.flush()
                        }
                    }
                } finally {
                    unregisterCall(rec.id, call)
                }
                if (have < expected) {
                    throw IllegalStateException("اتصال قطعه پیش از پایان قطع شد")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (++attempt > 5) throw e
                delay(550L * attempt)
            }
        }
    }

    private data class HlsSegmentTask(
        val fileName: String,
        val url: String,
        val sequence: Long,
        val byteRange: HlsPlaylistParser.ByteRangeSpec?,
        val aesKeyUrl: String?,
        val aesIv: ByteArray?,
        val stripPackedAudioId3: Boolean
    )

    /**
     * IDM Resumable HLS (`.m3u8`) Video + Separate Audio Concurrent Segment Downloader & MP4 Merger!
     * - Supports `#EXT-X-MEDIA:TYPE=AUDIO` separate audio renditions (never downloads mute video!).
     * - Supports `#EXT-X-MAP` (`fMP4`), `#EXT-X-BYTERANGE`, Packed-Audio `ID3`/ADTS, and `METHOD=AES-128`.
     * - Persists every completed segment atomically as `<targetFile>.hlsdir/*.done` so Pause -> Resume
     *   continues immediately from the exact segment where it stopped!
     */
    private suspend fun runHlsStreamDownload(initialRecord: DownloadRecord, targetFile: File) {
        var videoPlaylistUrl = initialRecord.url
        var audioPlaylistUrl = initialRecord.secondaryAudioUrl.trim().ifBlank { null }

        var manifestText = fetchTextThroughTunnel(initialRecord, videoPlaylistUrl)
        if (!HlsPlaylistParser.looksLikeHlsManifest(manifestText)) {
            throw IllegalStateException("لیست پخش استریم (.m3u8) معتبر نیست یا منقضی شده است")
        }

        if (HlsPlaylistParser.isMasterPlaylist(manifestText)) {
            val variants = HlsPlaylistParser.parseMasterPlaylist(manifestText, videoPlaylistUrl)
            if (variants.isEmpty()) {
                throw IllegalStateException("هیچ کیفیتی در استریم یافت نشد")
            }
            val chosen = variants.first()
            videoPlaylistUrl = chosen.url
            if (audioPlaylistUrl == null && !chosen.audioUrl.isNullOrBlank()) {
                audioPlaylistUrl = chosen.audioUrl
            }
            manifestText = fetchTextThroughTunnel(initialRecord, videoPlaylistUrl)
        }

        val videoPlaylist = HlsPlaylistParser.parseMediaPlaylist(manifestText, videoPlaylistUrl)
        if (videoPlaylist.isDrmOrEncrypted) {
            throw IllegalStateException("این ویدیو دارای قفل DRM (${videoPlaylist.encryptionMethod}) است")
        }
        if (videoPlaylist.segments.isEmpty()) {
            throw IllegalStateException("قطعات ویدیو در لیست پخش یافت نشد")
        }

        val audioPlaylist = if (!audioPlaylistUrl.isNullOrBlank() && audioPlaylistUrl != videoPlaylistUrl) {
            runCatching {
                val aBody = fetchTextThroughTunnel(initialRecord, audioPlaylistUrl)
                if (HlsPlaylistParser.looksLikeHlsManifest(aBody) && !HlsPlaylistParser.isMasterPlaylist(aBody)) {
                    val parsedA = HlsPlaylistParser.parseMediaPlaylist(aBody, audioPlaylistUrl)
                    if (!parsedA.isDrmOrEncrypted && parsedA.segments.isNotEmpty()) parsedA else null
                } else {
                    null
                }
            }.getOrNull()
        } else {
            null
        }

        val hlsDir = hlsStagingDirFor(targetFile).apply { mkdirs() }

        val videoTasks = buildHlsTasksForPlaylist("v", videoPlaylist, stripPackedId3 = videoPlaylist.initMapUrl == null)
        val audioTasks = if (audioPlaylist != null) {
            buildHlsTasksForPlaylist("a", audioPlaylist, stripPackedId3 = audioPlaylist.initMapUrl == null)
        } else {
            emptyList()
        }
        val allTasks = ArrayList<HlsSegmentTask>(videoTasks.size + audioTasks.size).apply {
            addAll(videoTasks)
            addAll(audioTasks)
        }
        val totalUnits = allTasks.size.coerceAtLeast(1)

        var preCompletedCount = 0
        var preCompletedBytes = 0L
        for (t in allTasks) {
            val done = File(hlsDir, t.fileName)
            if (done.exists() && done.length() > 0L) {
                preCompletedCount++
                preCompletedBytes += done.length()
            }
        }

        val completedUnitsAtomic = AtomicInteger(preCompletedCount)
        val bytesWrittenAtomic = AtomicLong(preCompletedBytes)
        val reportLock = Any()
        var lastReportMs = System.currentTimeMillis()
        var bytesAtLastReport = preCompletedBytes

        val initEstTotal = if (preCompletedCount > 0) {
            (preCompletedBytes * totalUnits) / preCompletedCount
        } else {
            -1L
        }
        persistAndPublish(
            initialRecord.copy(
                secondaryAudioUrl = audioPlaylistUrl.orEmpty(),
                bytesRead = preCompletedBytes,
                totalBytes = initEstTotal,
                speedBps = 0L,
                state = DownloadState.DOWNLOADING,
                errorMsg = "قطعه $preCompletedCount از $totalUnits"
            )
        )

        val aesKeyCache = ConcurrentHashMap<String, ByteArray>()
        val limiter = Semaphore(4)

        coroutineScope {
            allTasks.map { task ->
                async(Dispatchers.IO) {
                    val doneFile = File(hlsDir, task.fileName)
                    if (doneFile.exists() && doneFile.length() > 0L) {
                        return@async
                    }
                    limiter.withPermit {
                        coroutineContext.ensureActive()
                        var bytes = fetchSegmentBytesWithRetry(
                            initialRecord,
                            task.url,
                            task.byteRange?.toHttpRangeHeader()
                        )
                        if (!task.aesKeyUrl.isNullOrBlank()) {
                            val key = aesKeyCache[task.aesKeyUrl] ?: run {
                                val fetchedKey = fetchSegmentBytesWithRetry(initialRecord, task.aesKeyUrl, null)
                                if (fetchedKey.size == 16) {
                                    aesKeyCache[task.aesKeyUrl] = fetchedKey
                                }
                                fetchedKey
                            }
                            bytes = HlsPlaylistParser.decryptAes128(
                                cipherBytes = bytes,
                                keyBytes = key,
                                explicitIv = task.aesIv,
                                sequence = task.sequence
                            )
                        }
                        if (task.stripPackedAudioId3) {
                            bytes = HlsPlaylistParser.stripPackedAudioId3Headers(bytes)
                        }

                        val tmpFile = File(hlsDir, "${task.fileName}.tmp")
                        FileOutputStream(tmpFile, false).use { out ->
                            out.write(bytes)
                            out.flush()
                        }
                        if (doneFile.exists()) doneFile.delete()
                        if (!tmpFile.renameTo(doneFile)) {
                            tmpFile.copyTo(doneFile, overwrite = true)
                            tmpFile.delete()
                        }

                        val doneCnt = completedUnitsAtomic.incrementAndGet()
                        val nowBytes = bytesWrittenAtomic.addAndGet(bytes.size.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 320L || doneCnt == totalUnits) {
                            synchronized(reportLock) {
                                val elapsed = now - lastReportMs
                                if (elapsed >= 320L || doneCnt == totalUnits) {
                                    val delta = (nowBytes - bytesAtLastReport).coerceAtLeast(0L)
                                    val bps = (delta * 1000L) / elapsed.coerceAtLeast(1L)
                                    lastReportMs = now
                                    bytesAtLastReport = nowBytes
                                    val estTotal = if (doneCnt > 0) (nowBytes * totalUnits) / doneCnt else -1L
                                    persistAndPublish(
                                        initialRecord.copy(
                                            secondaryAudioUrl = audioPlaylistUrl.orEmpty(),
                                            bytesRead = nowBytes,
                                            totalBytes = estTotal,
                                            speedBps = bps,
                                            state = DownloadState.DOWNLOADING,
                                            errorMsg = "قطعه $doneCnt از $totalUnits"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        coroutineContext.ensureActive()

        // Concatenate video segments (+ audio segments if separate #EXT-X-MEDIA:TYPE=AUDIO exists)
        val assembledVideo = File(hlsDir, "assembled_video.stream")
        concatenateHlsTaskFiles(hlsDir, videoTasks, assembledVideo)

        val assembledAudio = if (audioTasks.isNotEmpty()) {
            File(hlsDir, "assembled_audio.stream").also {
                concatenateHlsTaskFiles(hlsDir, audioTasks, it)
            }
        } else {
            null
        }

        // Losslessly mux Video + Audio into fast-seeking MP4 container!
        val remuxedOk = MediaRemuxer.remuxOrMergeToMp4(assembledVideo, assembledAudio, targetFile)

        val finalFile: File
        val finalMime: String
        if (remuxedOk && targetFile.exists() && targetFile.length() > 0L) {
            finalFile = targetFile
            finalMime = "video/mp4"
        } else if (videoPlaylist.initMapUrl != null) {
            // Already valid fMP4 container
            if (targetFile.exists()) targetFile.delete()
            if (!assembledVideo.renameTo(targetFile)) {
                assembledVideo.copyTo(targetFile, overwrite = true)
            }
            finalFile = targetFile
            finalMime = "video/mp4"
        } else {
            val fallbackTs = File(targetFile.parentFile, targetFile.nameWithoutExtension + ".ts")
            if (fallbackTs.exists()) fallbackTs.delete()
            if (!assembledVideo.renameTo(fallbackTs)) {
                assembledVideo.copyTo(fallbackTs, overwrite = true)
            }
            finalFile = fallbackTs
            finalMime = "video/mp2t"
        }

        cleanupStagingArtifacts(targetFile, deleteTargetFile = false)

        exportCopyToPublicDownloadsQuietly(finalFile, finalMime)
        val finalLen = finalFile.length()
        val doneRecord = initialRecord.copy(
            fileName = finalFile.name,
            secondaryAudioUrl = audioPlaylistUrl.orEmpty(),
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
        onDownloadEventToast?.invoke("✅ دانلود استریم با صدا کامل شد: ${doneRecord.fileName}", true)
    }

    private fun buildHlsTasksForPlaylist(
        prefix: String,
        playlist: HlsPlaylistParser.ParsedMediaPlaylist,
        stripPackedId3: Boolean
    ): List<HlsSegmentTask> {
        val list = ArrayList<HlsSegmentTask>(playlist.segments.size + 1)
        if (!playlist.initMapUrl.isNullOrBlank()) {
            list.add(
                HlsSegmentTask(
                    fileName = "${prefix}_init.done",
                    url = playlist.initMapUrl,
                    sequence = 0L,
                    byteRange = playlist.initMapByteRange,
                    aesKeyUrl = playlist.initMapAesKeyUrl,
                    aesIv = playlist.initMapAesIv,
                    stripPackedAudioId3 = false
                )
            )
        }
        playlist.segments.forEachIndexed { idx, seg ->
            list.add(
                HlsSegmentTask(
                    fileName = String.format(Locale.US, "%s_seg_%05d.done", prefix, idx),
                    url = seg.url,
                    sequence = seg.sequence,
                    byteRange = seg.byteRange,
                    aesKeyUrl = seg.aes128KeyUrl,
                    aesIv = seg.aes128Iv,
                    stripPackedAudioId3 = stripPackedId3
                )
            )
        }
        return list
    }

    private suspend fun concatenateHlsTaskFiles(
        hlsDir: File,
        tasks: List<HlsSegmentTask>,
        outFile: File
    ) {
        if (outFile.exists()) outFile.delete()
        val buf = ByteArray(256 * 1024)
        FileOutputStream(outFile, false).use { out ->
            for (t in tasks) {
                coroutineContext.ensureActive()
                val segFile = File(hlsDir, t.fileName)
                if (!segFile.exists()) {
                    throw IllegalStateException("قطعه دانلود شده یافت نشد: ${t.fileName}")
                }
                segFile.inputStream().use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            out.flush()
        }
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

    private suspend fun fetchSegmentBytesWithRetry(
        rec: DownloadRecord,
        segUrl: String,
        rangeHeader: String? = null
    ): ByteArray {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                val req = buildAuthenticatedRequest(segUrl, rec.userAgent, rec.refererUrl, rangeHeader)
                val call = tunnelManager.getStreamingDownloadOkHttpClient().newCall(req)
                registerCall(rec.id, call)
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 206) {
                            throw IllegalStateException("HTTP ${resp.code}")
                        }
                        val body = resp.body ?: return ByteArray(0)
                        val out = ByteArrayOutputStream(
                            body.contentLength().coerceIn(4096L, 16L * 1024 * 1024).toInt()
                        )
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

                    val buf = ByteArray(64 * 1024)
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
                            val deltaBytes = (bytesCopied - bytesAtLastReport).coerceAtLeast(0L)
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
                    sourceFile.inputStream().use { inp -> inp.copyTo(out, 64 * 1024) }
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
        if (!candidate.exists() && !shardMetaFileFor(candidate).exists() && !hlsStagingDirFor(candidate).exists()) {
            return candidate
        }

        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        for (i in 1..999) {
            candidate = File(dir, "${stem}_($i)$ext")
            if (!candidate.exists() && !shardMetaFileFor(candidate).exists() && !hlsStagingDirFor(candidate).exists()) {
                return candidate
            }
        }
        return File(dir, "${System.currentTimeMillis()}_$clean")
    }

    companion object {
        private const val MIN_MULTI_THREAD_BYTES = 2_621_440L // 2.5 MB
        const val DEFAULT_DOWNLOAD_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

        data class ByteShard(
            val index: Int,
            val start: Long,
            val endInclusive: Long
        ) {
            val expectedBytes: Long get() = endInclusive - start + 1L
        }

        data class ParallelShardPlan(
            val totalBytes: Long,
            val finalMime: String,
            val shards: List<ByteShard>
        )

        fun partFileFor(stagingFile: File, shardIndex: Int): File =
            File("${stagingFile.absolutePath}.part$shardIndex")

        fun shardMetaFileFor(stagingFile: File): File =
            File("${stagingFile.absolutePath}.shards.json")

        fun hlsStagingDirFor(stagingFile: File): File =
            File("${stagingFile.absolutePath}.hlsdir")

        fun buildParallelShards(totalBytes: Long, partCount: Int): List<ByteShard> {
            require(totalBytes > 0L) { "totalBytes must be positive" }
            val parts = partCount.coerceIn(1, 8)
            val baseSize = totalBytes / parts
            return (0 until parts).map { idx ->
                val start = idx * baseSize
                val end = if (idx == parts - 1) totalBytes - 1L else ((idx + 1) * baseSize) - 1L
                ByteShard(index = idx, start = start, endInclusive = end)
            }
        }

        fun saveShardPlan(stagingFile: File, plan: ParallelShardPlan) {
            val sb = StringBuilder()
            sb.append("v=1\n")
            sb.append("totalBytes=").append(plan.totalBytes).append('\n')
            sb.append("finalMime=").append(plan.finalMime.replace('\n', ' ')).append('\n')
            for (s in plan.shards) {
                sb.append("shard=").append(s.index).append(':').append(s.start).append('-').append(s.endInclusive).append('\n')
            }
            val metaFile = shardMetaFileFor(stagingFile)
            metaFile.parentFile?.mkdirs()
            val tmp = File("${metaFile.absolutePath}.tmp")
            tmp.writeText(sb.toString())
            if (metaFile.exists()) metaFile.delete()
            if (!tmp.renameTo(metaFile)) {
                tmp.copyTo(metaFile, overwrite = true)
                tmp.delete()
            }
        }

        fun loadShardPlan(stagingFile: File): ParallelShardPlan? {
            val metaFile = shardMetaFileFor(stagingFile)
            if (!metaFile.exists()) return null
            return runCatching {
                var total = -1L
                var mime = "application/octet-stream"
                val list = ArrayList<ByteShard>()
                for (rawLine in metaFile.readLines()) {
                    val line = rawLine.trim()
                    when {
                        line.startsWith("totalBytes=") -> {
                            total = line.substringAfter('=').trim().toLongOrNull() ?: -1L
                        }
                        line.startsWith("finalMime=") -> {
                            mime = line.substringAfter('=').trim().ifBlank { "application/octet-stream" }
                        }
                        line.startsWith("shard=") -> {
                            val spec = line.substringAfter('=')
                            val idx = spec.substringBefore(':').toIntOrNull() ?: continue
                            val rangePart = spec.substringAfter(':', "")
                            val st = rangePart.substringBefore('-').toLongOrNull() ?: continue
                            val en = rangePart.substringAfter('-', "").toLongOrNull() ?: continue
                            if (st >= 0L && en >= st) {
                                list.add(ByteShard(idx, st, en))
                            }
                        }
                    }
                }
                if (total <= 0L || list.isEmpty()) null else ParallelShardPlan(total, mime, list)
            }.getOrNull()
        }

        fun computeShardBytesOnDisk(stagingFile: File, plan: ParallelShardPlan): Long {
            var sum = 0L
            for (s in plan.shards) {
                val pf = partFileFor(stagingFile, s.index)
                if (pf.exists()) {
                    sum += pf.length().coerceIn(0L, s.expectedBytes)
                }
            }
            return sum
        }

        /**
         * Returns exact kernel-persisted progress across Multi-Part Shards, HLS `.done` segments,
         * or Single-Stream partial files so Pause/Resume NEVER resets to 0% or misreads sparse files!
         */
        fun computePersistedBytesForStaging(stagingFile: File): Long {
            val plan = loadShardPlan(stagingFile)
            if (plan != null) {
                return computeShardBytesOnDisk(stagingFile, plan)
            }
            val hlsDir = hlsStagingDirFor(stagingFile)
            if (hlsDir.exists() && hlsDir.isDirectory) {
                val doneBytes = hlsDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".done") }
                    ?.sumOf { it.length() }
                    ?: 0L
                if (doneBytes > 0L) return doneBytes
            }
            return if (stagingFile.exists() && stagingFile.isFile) stagingFile.length() else 0L
        }

        fun cleanupStagingArtifacts(stagingFile: File, deleteTargetFile: Boolean) {
            runCatching {
                val plan = loadShardPlan(stagingFile)
                if (plan != null) {
                    for (s in plan.shards) {
                        partFileFor(stagingFile, s.index).delete()
                    }
                } else {
                    for (idx in 0..8) {
                        partFileFor(stagingFile, idx).delete()
                    }
                }
                shardMetaFileFor(stagingFile).delete()
                File("${stagingFile.absolutePath}.joining").delete()
                File("${stagingFile.absolutePath}.hls.ts").delete()
                hlsStagingDirFor(stagingFile).deleteRecursively()
                if (deleteTargetFile && stagingFile.exists()) {
                    stagingFile.delete()
                }
            }
        }

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
