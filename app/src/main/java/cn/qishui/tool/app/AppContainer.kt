package cn.qishui.tool.app

import android.content.Context
import androidx.room.Room
import cn.qishui.tool.data.api.QsmusicApiClient
import cn.qishui.tool.data.api.QsmusicMusicResolver
import cn.qishui.tool.data.api.QsmusicResponseParser
import cn.qishui.tool.data.api.ShareLinkExtractor
import cn.qishui.tool.data.download.ResumableDownloader
import cn.qishui.tool.data.download.StorageDownloadTargetResolver
import cn.qishui.tool.domain.download.DownloadTargetResolver
import cn.qishui.tool.data.encoder.EncoderClient
import cn.qishui.tool.data.local.MIGRATION_1_2
import cn.qishui.tool.data.local.MIGRATION_2_3
import cn.qishui.tool.data.local.MIGRATION_3_4
import cn.qishui.tool.data.local.MIGRATION_4_5
import cn.qishui.tool.data.local.MIGRATION_5_6
import cn.qishui.tool.data.local.MIGRATION_6_7
import cn.qishui.tool.data.local.MIGRATION_7_8
import cn.qishui.tool.data.local.MIGRATION_8_9
import cn.qishui.tool.data.local.MIGRATION_9_10
import cn.qishui.tool.data.local.QishuiDatabase
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.data.media.LocalAudioImporter
import cn.qishui.tool.data.source.LxSourceRuntime
import cn.qishui.tool.data.source.LxSourceScriptLoader
import cn.qishui.tool.data.source.MusicSourceRepository
import cn.qishui.tool.data.media.LocalResolver
import cn.qishui.tool.data.media.Media3AudioPlayer
import cn.qishui.tool.data.media.PlaybackConnection
import cn.qishui.tool.data.media.LyricsCodec
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
import cn.qishui.tool.domain.model.SourceCode
import cn.qishui.tool.domain.player.UrlResolver
import cn.qishui.tool.domain.waveform.WaveformSource
import cn.qishui.tool.data.search.MusicSearchRepository
import cn.qishui.tool.data.lyrics.LyricsRepository
import cn.qishui.tool.feature.edit.export.ExportTargetWriter
import cn.qishui.tool.media.pcm.EditPreviewRenderer
import cn.qishui.tool.media.pcm.PcmChunkReader
import cn.qishui.tool.media.source.LxSourceResolver
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
    ).addMigrations(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
    ).build()
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
    val favoritesDao = database.favoritesDao()
    val editProjectRepository: EditProjectRepository = RoomEditProjectRepository(database.editProjectDao())
    val exportPackageRepository: ExportPackageRepository = RoomExportPackageRepository(database.exportPackageDao())
    val downloadsDirectory: File = File(applicationContext.filesDir, "downloads")
    val downloadTargetResolver: DownloadTargetResolver = StorageDownloadTargetResolver(
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
    /** 正式播放的控制端。编辑页试听用 [audioPlayer]，两者语义不同，不要混。 */
    val playbackConnection: PlaybackConnection by lazy { PlaybackConnection(applicationContext) }
    /**
     * 播放地址解析器。lx 音源注册在旁边，[LxSourceRuntime] 装着引擎。
     *
     * 必须是 [lazy]：lx 解析器要引用 [lxSourceRuntime]，非 lazy 会在容器构造期
     * 就把 QuickJS 引擎拉起来，而引擎要读 assets 和 Room。
     *
     * 五个远端平台各一个 key，指向同一个引擎单例。
     */
    val urlResolvers: Map<String, UrlResolver> by lazy {
        buildMap {
            put(LocalResolver.SOURCE_LOCAL, LocalResolver())
            val remote = listOf(
                SourceCode.KUWO,
                SourceCode.KUGOU,
                SourceCode.TENCENT,
                SourceCode.NETEASE,
                SourceCode.MIGU,
            )
            for (platform in remote) {
                put(platform, LxSourceResolver(platform, lxSourceRuntime))
            }
        }
    }

    /** lx 自定义源运行时。QuickJS 引擎在这里。 */
    val lxSourceRuntime: LxSourceRuntime by lazy {
        LxSourceRuntime(
            context = applicationContext,
            client = okHttpClient,
            scriptLoader = LxSourceScriptLoader(applicationContext),
        )
    }

    /** 音源的存储与生命周期。勾选哪个音源由它管。 */
    val musicSourceRepository: MusicSourceRepository by lazy {
        MusicSourceRepository(
            dao = database.musicSourceDao(),
            runtime = lxSourceRuntime,
            client = okHttpClient,
        )
    }
    /** 歌曲搜索。平台接口只给歌 id，播放地址仍要问 lx 音源要。 */
    val musicSearchRepository by lazy { MusicSearchRepository(okHttpClient) }

    /**
     * 歌词。
     *
     * **跟音源没关系** —— lx 协议里 `lyric` action 只对 `local` 平台开放。
     * 走网易/QQ 官方接口，本平台没有就按歌名反查到这两个平台，所以要复用
     * [musicSearchRepository]。
     */
    val lyricsRepository by lazy {
        LyricsRepository(okHttpClient, lyricsParser, musicSearchRepository)
    }

    val encoderClient by lazy { EncoderClient(applicationContext, exportPackageRepository) }
    val pcmChunkReader by lazy { PcmChunkReader(applicationContext) }
    val editPreviewRenderer by lazy { EditPreviewRenderer(applicationContext, pcmChunkReader) }
    val waveformExtractor: WaveformSource by lazy {
        WaveformExtractor(pcmChunkReader) { file ->
            audioFileProbe.probeFile(file).durationMs?.times(1000L) ?: 0L
        }
    }
    val lyricsParser: LyricsParser = LyricsCodec
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
