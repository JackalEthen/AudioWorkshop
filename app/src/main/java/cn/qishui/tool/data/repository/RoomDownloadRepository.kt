package cn.qishui.tool.data.repository

import cn.qishui.tool.data.download.DownloadException
import cn.qishui.tool.data.download.ResumableDownloader
import cn.qishui.tool.data.download.managedFilesToDelete
import cn.qishui.tool.data.download.md5HexMatches
import cn.qishui.tool.data.download.safeDownloadFileName
import cn.qishui.tool.data.local.DownloadTaskEntity
import cn.qishui.tool.data.local.DownloadTaskDao
import cn.qishui.tool.data.local.toDomain
import cn.qishui.tool.data.local.toEntity
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.domain.DownloadRepository
import cn.qishui.tool.domain.MusicResolver
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.download.DownloadConcurrencyGate
import cn.qishui.tool.domain.download.DownloadTargetResolver
import cn.qishui.tool.domain.download.downloadTargetOf
import cn.qishui.tool.domain.model.DownloadStatus
import cn.qishui.tool.domain.model.DownloadTask
import cn.qishui.tool.domain.model.ResolvedTrack
import cn.qishui.tool.domain.model.SourceOrigin
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.domain.naming.AutoNamer
import cn.qishui.tool.domain.naming.NamingInput
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomDownloadRepository(
    private val dao: DownloadTaskDao,
    private val musicResolver: MusicResolver,
    private val downloader: ResumableDownloader,
    private val targetResolver: DownloadTargetResolver,
    private val concurrencyGate: DownloadConcurrencyGate,
    private val autoNamePatternProvider: () -> String,
    private val applicationScope: CoroutineScope,
    private val audioFileProbe: AudioFileProbe,
    private val sourceTrackRepository: SourceTrackRepository,
) : DownloadRepository {
    private val stateMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    init {
        applicationScope.launch {
            dao.pauseInterrupted(System.currentTimeMillis())
        }
    }

    override fun observeDownloads(): Flow<List<DownloadTask>> = dao.observeAll().map { tasks ->
        tasks.map { it.toDomain() }
    }

    override suspend fun enqueue(track: ResolvedTrack): Result<String> = runCatching {
        val sourceShareUrl = track.sourceShareUrl?.takeIf(String::isNotBlank)
            ?: throw DownloadException("来源分享链接不可用")
        val temporaryUrl = track.audioUrl?.takeIf(String::isNotBlank)
            ?: throw DownloadException("音频下载地址不可用")
        val format = track.format?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            ?: throw DownloadException("音频格式未知")
        val expectedMd5 = normalizeMd5(track.fileHash)
        val id = stateMutex.withLock {
            dao.findByIdentity(expectedMd5, temporaryUrl)?.let { existing ->
                if (existing.shouldReuse()) return@withLock existing.id
            }
            val taskId = UUID.randomUUID().toString()
            val finalTarget = targetResolver.finalTarget(
                downloadFileName(track.title, track.artist, null, format),
            )
            if (targetResolver.exists(finalTarget)) throw DownloadException("目标文件已存在，未覆盖")
            val now = System.currentTimeMillis()
            dao.insert(
                DownloadTask(
                    id = taskId,
                    title = track.title,
                    artist = track.artist,
                    sourceShareUrl = sourceShareUrl,
                    temporaryUrl = temporaryUrl,
                    format = format,
                    expectedSizeBytes = track.sizeBytes?.takeIf { it > 0L },
                    expectedMd5 = expectedMd5,
                    downloadedBytes = 0L,
                    eTag = null,
                    lastModified = null,
                    partPath = targetResolver.partTarget(taskId).stableKey,
                    finalPath = finalTarget.stableKey,
                    status = DownloadStatus.QUEUED,
                    errorMessage = null,
                    sampleRateHz = track.sampleRateHz,
                    lyrics = track.lyrics,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ).toEntity(),
            )
            taskId
        }
        start(id)
        id
    }

    override suspend fun pause(id: String): Result<Unit> = runCatching {
        val job = stateMutex.withLock {
            val current = dao.getById(id)?.toDomain() ?: throw DownloadException("下载任务不存在")
            if (current.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) {
                throw DownloadException("当前任务不可暂停")
            }
            dao.update(
                current.copy(
                    status = DownloadStatus.PAUSED,
                    errorMessage = null,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ).toEntity(),
            )
            jobs[id]
        }
        job?.cancelAndJoin()
    }

    override suspend fun resume(id: String): Result<Unit> = refreshAndQueue(
        id = id,
        allowedStatuses = setOf(DownloadStatus.PAUSED),
        clearPart = false,
    )

    override suspend fun retry(id: String): Result<Unit> = refreshAndQueue(
        id = id,
        allowedStatuses = setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED),
        clearPart = true,
    )

    override suspend fun delete(id: String, deleteFile: Boolean): Result<Unit> = runCatching {
        val task = dao.getById(id)?.toDomain() ?: throw DownloadException("下载任务不存在")
        jobs[id]?.cancelAndJoin()
        if (deleteFile) {
            managedFilesToDelete(deleteFile, task.partPath, task.finalPath)
                .forEach { key ->
                    if (!targetResolver.delete(downloadTargetOf(key))) {
                        throw DownloadException("文件删除失败")
                    }
                }
            // 文件没了就不要再留一条打不开的编辑记录
            task.finalPath?.let { key ->
                sourceTrackRepository.getById("api:$key")?.let { track ->
                    File(track.localPath).takeIf { it.isFile }?.delete()
                    sourceTrackRepository.delete(track.id)
                }
            }
        }
        dao.deleteById(id)
    }

    private suspend fun refreshAndQueue(
        id: String,
        allowedStatuses: Set<DownloadStatus>,
        clearPart: Boolean,
    ): Result<Unit> = runCatching {
        val current = dao.getById(id)?.toDomain() ?: throw DownloadException("下载任务不存在")
        if (current.status !in allowedStatuses) {
            throw DownloadException("当前任务状态不允许此操作")
        }
        val track = withContext(Dispatchers.IO) {
            musicResolver.resolve(current.sourceShareUrl).getOrElse { error ->
                markFailed(current, error.message ?: "重新解析失败")
                throw DownloadException(error.message ?: "重新解析失败")
            }
        }
        val temporaryUrl = track.audioUrl?.takeIf(String::isNotBlank)
            ?: throw DownloadException("重新解析后音频地址不可用")
        val format = track.format?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            ?: throw DownloadException("重新解析后音频格式未知")
        val expectedMd5 = normalizeMd5(track.fileHash)
        val sourceChanged = current.expectedMd5 != null && expectedMd5 != null && current.expectedMd5 != expectedMd5
        val resetTransfer = clearPart || sourceChanged
        val partTarget = current.partPath?.let(::downloadTargetOf) ?: targetResolver.partTarget(id)
        if (resetTransfer && targetResolver.exists(partTarget) && !targetResolver.delete(partTarget)) {
            throw DownloadException("无法清理临时文件")
        }
        val finalTarget = targetResolver.finalTarget(
            downloadFileName(track.title, track.artist, null, format),
        )
        if (targetResolver.exists(finalTarget) && finalTarget.stableKey != current.finalPath) {
            throw DownloadException("目标文件已存在，未覆盖")
        }
        val updated = current.copy(
            title = track.title,
            artist = track.artist,
            temporaryUrl = temporaryUrl,
            format = format,
            sampleRateHz = track.sampleRateHz,
            lyrics = track.lyrics,
            expectedSizeBytes = track.sizeBytes?.takeIf { it > 0L },
            expectedMd5 = expectedMd5,
            downloadedBytes = if (resetTransfer) 0L else targetResolver.lengthOf(partTarget),
            eTag = if (resetTransfer) null else current.eTag,
            lastModified = if (resetTransfer) null else current.lastModified,
            partPath = partTarget.stableKey,
            finalPath = finalTarget.stableKey,
            status = DownloadStatus.QUEUED,
            errorMessage = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        stateMutex.withLock {
            val latest = dao.getById(id)?.toDomain() ?: throw DownloadException("下载任务不存在")
            if (latest.status !in allowedStatuses) {
                throw DownloadException("任务状态已变化，请刷新后重试")
            }
            dao.update(updated.toEntity())
        }
        start(id)
    }

    private fun start(id: String) {
        val job = synchronized(jobs) {
            if (jobs[id]?.isActive == true) {
                null
            } else {
                applicationScope.launch(start = CoroutineStart.LAZY) {
                    runTask(id)
                }.also { jobs[id] = it }
            }
        } ?: return
        job.invokeOnCompletion {
            jobs.remove(id, job)
        }
        job.start()
    }

    private suspend fun runTask(id: String) {
        val queued = dao.getById(id)?.toDomain()?.takeIf { it.status == DownloadStatus.QUEUED } ?: return
        val downloading = queued.copy(
            status = DownloadStatus.DOWNLOADING,
            errorMessage = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        dao.update(downloading.toEntity())
        try {
            val completed = concurrencyGate.withPermit {
                downloader.download(downloading) { progress ->
                    dao.update(progress.toEntity())
                }
            }
            val finished = completed.copy(
                status = DownloadStatus.COMPLETED,
                errorMessage = null,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            dao.update(finished.toEntity())
            registerSourceTrack(finished)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val current = dao.getById(id)?.toDomain()
                if (current != null && current.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) {
                    dao.update(
                        current.copy(
                            status = DownloadStatus.PAUSED,
                            downloadedBytes = current.partPath?.let { targetResolver.lengthOf(downloadTargetOf(it)) }
                                ?: current.downloadedBytes,
                            errorMessage = null,
                            updatedAtEpochMillis = System.currentTimeMillis(),
                        ).toEntity(),
                    )
                }
            }
            throw error
        } catch (error: Exception) {
            val current = dao.getById(id)?.toDomain() ?: return
            val message = if (error is DownloadException) {
                error.message ?: "下载失败"
            } else {
                "下载失败，请重试"
            }
            dao.update(
                current.copy(
                    status = DownloadStatus.FAILED,
                    downloadedBytes = current.partPath?.let { targetResolver.lengthOf(downloadTargetOf(it)) }
                        ?: current.downloadedBytes,
                    partPath = current.partPath?.takeIf { targetResolver.exists(downloadTargetOf(it)) },
                    errorMessage = message,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ).toEntity(),
            )
        }
    }

    private suspend fun registerSourceTrack(task: DownloadTask) {
        val finalKey = task.finalPath ?: return
        val target = downloadTargetOf(finalKey)
        // SAF 目录里的文件编辑器用不了，统一取一份应用私有副本
        val file = targetResolver.editableCopy(target) ?: return
        val probed = runCatching { audioFileProbe.probeFile(file) }.getOrNull()
        try {
            sourceTrackRepository.upsert(
                SourceTrack(
                    id = "api:$finalKey",
                    origin = SourceOrigin.API,
                    sourceShareUrl = task.sourceShareUrl,
                    title = task.title,
                    artist = task.artist,
                    album = probed?.album,
                    localPath = file.absolutePath,
                    format = task.format,
                    durationMs = probed?.durationMs,
                    bitrateBps = probed?.bitrateBps,
                    sizeBytes = file.length(),
                    sampleRateHz = task.sampleRateHz ?: probed?.sampleRateHz,
                    lyrics = task.lyrics,
                    fileHash = task.expectedMd5,
                    coverUri = null,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Unit
        }
    }

    private suspend fun markFailed(task: DownloadTask, message: String) {
        dao.update(
            task.copy(
                status = DownloadStatus.FAILED,
                errorMessage = message.take(160),
                updatedAtEpochMillis = System.currentTimeMillis(),
            ).toEntity(),
        )
    }

    private fun downloadFileName(title: String?, artist: String?, album: String?, format: String): String {
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val rendered = AutoNamer.render(
            pattern = autoNamePatternProvider(),
            input = NamingInput(title = title, artist = artist, album = album, date = today),
        ).baseName ?: safeDownloadFileName(title, artist, format).substringBeforeLast('.')
        val extension = format.trim().trimStart('.').lowercase()
            .takeIf { it.matches(SAFE_EXTENSION) } ?: "audio"
        return "$rendered.$extension"
    }

    private fun normalizeMd5(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { md5HexMatches(it, it) }

    private fun DownloadTaskEntity.shouldReuse(): Boolean = when (status) {
        DownloadStatus.QUEUED.name,
        DownloadStatus.DOWNLOADING.name,
        DownloadStatus.PAUSED.name,
        -> true
        DownloadStatus.COMPLETED.name ->
            final_path?.let { targetResolver.exists(downloadTargetOf(it)) } == true
        else -> false
    }
}

// ponytail: 远端 vtype 不可信，仅允许纯字母数字扩展名，挡住 ../ 与斜杠
private val SAFE_EXTENSION = Regex("[a-z0-9]{1,8}")
