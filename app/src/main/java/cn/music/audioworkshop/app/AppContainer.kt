package cn.music.audioworkshop.app

import android.content.Context
import androidx.room.Room
import cn.music.audioworkshop.data.parse.GenericMusicResolver
import cn.music.audioworkshop.data.parse.GenericParseApiClient
import cn.music.audioworkshop.data.api.ShareLinkExtractor
import cn.music.audioworkshop.data.download.ResumableDownloader
import cn.music.audioworkshop.data.download.StorageDownloadTargetResolver
import cn.music.audioworkshop.domain.download.DownloadTargetResolver
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.local.MIGRATION_1_2
import cn.music.audioworkshop.data.local.MIGRATION_2_3
import cn.music.audioworkshop.data.local.MIGRATION_3_4
import cn.music.audioworkshop.data.local.MIGRATION_4_5
import cn.music.audioworkshop.data.local.MIGRATION_5_6
import cn.music.audioworkshop.data.local.MIGRATION_6_7
import cn.music.audioworkshop.data.local.MIGRATION_7_8
import cn.music.audioworkshop.data.local.MIGRATION_8_9
import cn.music.audioworkshop.data.local.MIGRATION_9_10
import cn.music.audioworkshop.data.local.MIGRATION_10_11
import cn.music.audioworkshop.data.local.QishuiDatabase
import cn.music.audioworkshop.data.media.AudioFileProbe
import cn.music.audioworkshop.data.media.LocalAudioImporter
import cn.music.audioworkshop.data.source.LxSourceRuntime
import cn.music.audioworkshop.data.source.LxSourceScriptLoader
import cn.music.audioworkshop.data.source.MusicSourceRepository
import cn.music.audioworkshop.data.media.LocalResolver
import cn.music.audioworkshop.data.media.Media3AudioPlayer
import cn.music.audioworkshop.data.media.PlaybackConnection
import cn.music.audioworkshop.data.media.LyricsCodec
import cn.music.audioworkshop.data.repository.RoomDownloadRepository
import cn.music.audioworkshop.data.repository.RoomEditProjectRepository
import cn.music.audioworkshop.data.repository.RoomExportPackageRepository
import cn.music.audioworkshop.data.repository.RoomParseApiSourceRepository
import cn.music.audioworkshop.data.repository.RoomParseRecordRepository
import cn.music.audioworkshop.data.repository.RoomSourceTrackRepository
import cn.music.audioworkshop.data.settings.SettingsDataStore
import cn.music.audioworkshop.domain.AudioPlayer
import cn.music.audioworkshop.domain.DownloadRepository
import cn.music.audioworkshop.domain.EditProjectRepository
import cn.music.audioworkshop.domain.ExportPackageRepository
import cn.music.audioworkshop.domain.parse.ParseApiSourceRepository
import cn.music.audioworkshop.domain.MusicResolver
import cn.music.audioworkshop.domain.ParseRecordRepository
import cn.music.audioworkshop.domain.SettingsRepository
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.domain.download.DownloadConcurrencyGate
import cn.music.audioworkshop.domain.lyrics.LyricsParser
import cn.music.audioworkshop.domain.model.SourceCode
import cn.music.audioworkshop.domain.player.UrlResolver
import cn.music.audioworkshop.domain.waveform.WaveformSource
import cn.music.audioworkshop.data.search.MusicSearchRepository
import cn.music.audioworkshop.data.lyrics.LyricsRepository
import cn.music.audioworkshop.feature.edit.export.ExportPublisher
import cn.music.audioworkshop.feature.edit.export.ExportTargetWriter
import cn.music.audioworkshop.media.pcm.EditPreviewRenderer
import cn.music.audioworkshop.media.pcm.PcmChunkReader
import cn.music.audioworkshop.media.source.LxSourceResolver
import cn.music.audioworkshop.media.waveform.WaveformExtractor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val okHttpClient = OkHttpClient.Builder().build()
    val database = Room.databaseBuilder(
        applicationContext,
        QishuiDatabase::class.java,
        "audioworkshop.db",
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
        MIGRATION_10_11,
    ).build()
    val resolver: MusicResolver = GenericMusicResolver(
        shareLinkExtractor = ShareLinkExtractor(),
        apiClient = GenericParseApiClient(okHttpClient),
    )
    val parseApiSourceRepository: ParseApiSourceRepository by lazy {
        RoomParseApiSourceRepository(database.parseApiSourceDao())
    }
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
        parseApiSourceRepository = parseApiSourceRepository,
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

    val cacheCleaner: cn.music.audioworkshop.data.cache.CacheCleaner by lazy {

        cn.music.audioworkshop.data.cache.CacheCleaner(applicationContext)

    }


    val encoderClient by lazy { EncoderClient(applicationContext) }
    val pcmChunkReader by lazy { PcmChunkReader(applicationContext) }
    val editPreviewRenderer by lazy { EditPreviewRenderer(applicationContext, pcmChunkReader) }
    val waveformExtractor: WaveformSource by lazy {
        WaveformExtractor(pcmChunkReader) { file ->
            audioFileProbe.probeFile(file).durationMs?.times(1000L) ?: 0L
        }
    }
    val lyricsParser: LyricsParser = LyricsCodec
    val exportTempDirectory: File = File(applicationContext.cacheDir, "exports")

    /**
     * 导出落盘走设置里指定的下载目录，不再每次弹 SAF 选择框。
     *
     * 保留 [exportTargetWriter] 是因为「修改音乐信息」那条路要写回原文件，
     * 它拿到的是内容 Uri，不是下载目录。
     */
    val exportPublisher by lazy { ExportPublisher(downloadTargetResolver, exportPackageRepository) }
val exportTargetWriter by lazy { ExportTargetWriter(applicationContext) }

    init {
        applicationScope.launch {
            settingsRepository.settings.collect { settings ->
                downloadConcurrencyGate.updateLimit(settings.downloadConnections)
            }
        }
    }
}
