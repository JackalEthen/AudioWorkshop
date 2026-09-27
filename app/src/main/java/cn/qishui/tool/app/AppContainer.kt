package cn.qishui.tool.app

import android.content.Context
import androidx.room.Room
import cn.qishui.tool.data.api.QsmusicApiClient
import cn.qishui.tool.data.api.QsmusicMusicResolver
import cn.qishui.tool.data.api.QsmusicResponseParser
import cn.qishui.tool.data.api.ShareLinkExtractor
import cn.qishui.tool.data.download.ResumableDownloader
import cn.qishui.tool.data.download.StorageDownloadTargetResolver
import cn.qishui.tool.data.encoder.EncoderClient
import cn.qishui.tool.data.local.MIGRATION_1_2
import cn.qishui.tool.data.local.MIGRATION_2_3
import cn.qishui.tool.data.local.MIGRATION_3_4
import cn.qishui.tool.data.local.QishuiDatabase
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.data.media.LocalAudioImporter
import cn.qishui.tool.data.media.Media3AudioPlayer
import cn.qishui.tool.data.media.QsmusicLyricParser
import cn.qishui.tool.data.repository.RoomDownloadRepository
import cn.qishui.tool.data.repository.RoomEditProjectRepository
import cn.qishui.tool.data.repository.RoomExportPackageRepository
import cn.qishui.tool.data.repository.RoomParseRecordRepository
import cn.qishui.tool.data.repository.RoomSourceTrackRepository
import cn.qishui.tool.data.settings.SettingsDataStore
import cn.qishui.tool.domain.AudioPlayer
import cn.qishui.tool.domain.DownloadRepository
import cn.qishui.tool.domain.EditProjectRepository
import cn.qishui.tool.domain.ExportPackageRepository
import cn.qishui.tool.domain.MusicResolver
import cn.qishui.tool.domain.ParseRecordRepository
import cn.qishui.tool.domain.SettingsRepository
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.download.DownloadConcurrencyGate
import cn.qishui.tool.domain.lyrics.LyricsParser
import cn.qishui.tool.domain.waveform.WaveformSource
import cn.qishui.tool.feature.edit.export.ExportTargetWriter
import cn.qishui.tool.media.pcm.PcmChunkReader
import cn.qishui.tool.feature.video.VideoEditEngineHolder
import cn.qishui.tool.media.video.VideoEditEngine
import cn.qishui.tool.media.video.VideoTools
import cn.qishui.tool.media.waveform.WaveformExtractor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val okHttpClient = OkHttpClient.Builder().build()
    val database = Room.databaseBuilder(
        applicationContext,
        QishuiDatabase::class.java,
        "qishui.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    val resolver: MusicResolver = QsmusicMusicResolver(
        shareLinkExtractor = ShareLinkExtractor(),
        apiClient = QsmusicApiClient(okHttpClient),
        responseParser = QsmusicResponseParser(),
    )
    val settingsRepository: SettingsRepository = SettingsDataStore.create(applicationContext, applicationScope)
    val parseRecordRepository: ParseRecordRepository = RoomParseRecordRepository(database.parseRecordDao())
    private val audioFileProbe = AudioFileProbe()
    private val localAudioImporter = LocalAudioImporter(applicationContext, audioFileProbe)
    val sourceTrackRepository: SourceTrackRepository =
        RoomSourceTrackRepository(database.sourceTrackDao(), localAudioImporter)
    val editProjectRepository: EditProjectRepository = RoomEditProjectRepository(database.editProjectDao())
    val exportPackageRepository: ExportPackageRepository = RoomExportPackageRepository(database.exportPackageDao())
    val downloadsDirectory: File = File(applicationContext.filesDir, "downloads")
    private val downloadTargetResolver = StorageDownloadTargetResolver(
        context = applicationContext,
        appDownloadsDirectory = downloadsDirectory,
        treeUriProvider = { settingsRepository.settings.value.downloadDirectoryUri },
    )
    private val downloadConcurrencyGate = DownloadConcurrencyGate(
        scope = applicationScope,
        limit = settingsRepository.settings.value.downloadConnections,
    )
    val downloadRepository: DownloadRepository = RoomDownloadRepository(
        dao = database.downloadTaskDao(),
        musicResolver = resolver,
        downloader = ResumableDownloader(okHttpClient, downloadTargetResolver),
        targetResolver = downloadTargetResolver,
        concurrencyGate = downloadConcurrencyGate,
        autoNamePatternProvider = { settingsRepository.settings.value.autoNamePattern },
        applicationScope = applicationScope,
        audioFileProbe = audioFileProbe,
        sourceTrackRepository = sourceTrackRepository,
    )
    val audioPlayer: AudioPlayer by lazy { Media3AudioPlayer(applicationContext) }
    val encoderClient by lazy { EncoderClient(applicationContext, exportPackageRepository) }
    val pcmChunkReader by lazy { PcmChunkReader(applicationContext) }
    val waveformExtractor: WaveformSource by lazy {
        WaveformExtractor(pcmChunkReader) { file ->
            audioFileProbe.probeFile(file).durationMs?.times(1000L) ?: 0L
        }
    }
    val lyricsParser: LyricsParser = QsmusicLyricParser()
    val exportTempDirectory: File = File(applicationContext.cacheDir, "exports")
    val exportTargetWriter by lazy { ExportTargetWriter(applicationContext) }
    val videoTools by lazy { VideoTools(applicationContext, pcmChunkReader) }
    val videoEditEngine by lazy { VideoEditEngine(applicationContext).also { VideoEditEngineHolder.instance = it } }

    init {
        applicationScope.launch {
            settingsRepository.settings.collect { settings ->
                downloadConcurrencyGate.updateLimit(settings.downloadConnections)
            }
        }
    }
}
