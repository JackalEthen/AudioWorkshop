package cn.music.audioworkshop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.music.audioworkshop.app.AppContainer
import cn.music.audioworkshop.data.media.AudioFileProbe
import cn.music.audioworkshop.feature.convert.ConvertScreen
import cn.music.audioworkshop.feature.convert.ConvertViewModel
import cn.music.audioworkshop.feature.fade.FadeScreen
import cn.music.audioworkshop.feature.fade.FadeViewModel
import cn.music.audioworkshop.feature.denoise.DenoiseScreen
import cn.music.audioworkshop.feature.denoise.DenoiseViewModel
import cn.music.audioworkshop.feature.echo.EchoScreen
import cn.music.audioworkshop.feature.echo.EchoViewModel
import cn.music.audioworkshop.feature.equalizer.EqualizerScreen
import cn.music.audioworkshop.feature.choir.ChoirScreen
import cn.music.audioworkshop.feature.choir.ChoirViewModel
import cn.music.audioworkshop.feature.loudness.LoudnessScreen
import cn.music.audioworkshop.feature.loudness.LoudnessViewModel
import cn.music.audioworkshop.feature.stereocompose.StereoComposeScreen
import cn.music.audioworkshop.feature.stereocompose.StereoComposeViewModel
import cn.music.audioworkshop.feature.stereoorbit.StereoOrbitScreen
import cn.music.audioworkshop.feature.stereoorbit.StereoOrbitViewModel
import cn.music.audioworkshop.feature.stereosplit.StereoSplitScreen
import cn.music.audioworkshop.feature.stereosplit.StereoSplitViewModel
import cn.music.audioworkshop.feature.repair.RepairScreen
import cn.music.audioworkshop.feature.repair.RepairViewModel
import cn.music.audioworkshop.feature.videoaudio.VideoAudioScreen
import cn.music.audioworkshop.feature.videoaudio.VideoAudioViewModel
import cn.music.audioworkshop.feature.equalizer.EqualizerViewModel
import cn.music.audioworkshop.feature.reverb.ReverbScreen
import cn.music.audioworkshop.feature.reverb.ReverbViewModel
import cn.music.audioworkshop.feature.join.JoinScreen
import cn.music.audioworkshop.feature.join.JoinViewModel
import cn.music.audioworkshop.feature.lrc.LrcEditScreen
import cn.music.audioworkshop.feature.lrc.LrcEditViewModel
import cn.music.audioworkshop.feature.speedpitch.SpeedPitchScreen
import cn.music.audioworkshop.feature.speedpitch.SpeedPitchViewModel
import cn.music.audioworkshop.feature.metadata.MetadataScreen
import cn.music.audioworkshop.feature.trim.TrimScreen
import cn.music.audioworkshop.feature.trim.TrimViewModel
import cn.music.audioworkshop.feature.volume.VolumeScreen
import cn.music.audioworkshop.feature.volume.VolumeViewModel
import cn.music.audioworkshop.feature.metadata.MetadataViewModel
import cn.music.audioworkshop.feature.player.PlayerFavoritesScreen
import cn.music.audioworkshop.feature.player.PlayerHomeScreen
import cn.music.audioworkshop.feature.player.PlayerRoute
import cn.music.audioworkshop.feature.player.PlayerSearchScreen
import cn.music.audioworkshop.feature.source.MusicSourceViewModel
import java.io.File
import cn.music.audioworkshop.domain.model.AppSettings
import cn.music.audioworkshop.domain.model.EditOperation
import cn.music.audioworkshop.feature.edit.EditDestination
import cn.music.audioworkshop.feature.edit.EditHomeScreen
import cn.music.audioworkshop.feature.edit.EditHomeViewModel
import cn.music.audioworkshop.feature.edit.EditRecordsScreen
import cn.music.audioworkshop.feature.edit.EditRecordsViewModel
import cn.music.audioworkshop.feature.edit.EditWorkspaceScreen
import cn.music.audioworkshop.feature.edit.EditWorkspaceViewModel
import cn.music.audioworkshop.feature.records.RecordsScreen
import cn.music.audioworkshop.feature.records.RecordsViewModel
import cn.music.audioworkshop.feature.resolve.ResolveScreen
import cn.music.audioworkshop.feature.resolve.ResolveViewModel
import cn.music.audioworkshop.feature.settings.AboutScreen
import cn.music.audioworkshop.feature.settings.AppearanceScreen
import cn.music.audioworkshop.feature.settings.DownloadSettingsScreen
import cn.music.audioworkshop.feature.settings.NamingSettingsScreen
import cn.music.audioworkshop.feature.settings.PlayerSourceSettingsScreen
import cn.music.audioworkshop.feature.settings.SettingsHomeScreen
import cn.music.audioworkshop.feature.settings.SettingsRoute
import cn.music.audioworkshop.feature.settings.SettingsViewModel
import cn.music.audioworkshop.feature.settings.cache.CacheScreen
import cn.music.audioworkshop.feature.settings.parseapi.ParseApiSettingsScreen
import cn.music.audioworkshop.feature.settings.parseapi.ParseApiViewModel
import cn.music.audioworkshop.feature.settings.cache.CacheViewModel
import cn.music.audioworkshop.ui.components.AppBackground
import cn.music.audioworkshop.ui.components.FloatingBottomBar
import cn.music.audioworkshop.ui.components.FunctionShell
import cn.music.audioworkshop.ui.components.PrimaryButton
import cn.music.audioworkshop.ui.components.WallpaperCropScreen
import cn.music.audioworkshop.ui.components.decodeWallpaper
import cn.music.audioworkshop.ui.components.wallpaperUri
import cn.music.audioworkshop.ui.theme.CardStyle
import cn.music.audioworkshop.ui.theme.LocalCardStyle

@Composable
fun QishuiApp(
    container: AppContainer,
    settings: AppSettings,
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 全屏播放页盖住整屏时隐藏底栏
    var playerSheetVisible by remember { mutableStateOf(false) }

    // 冷启动就把勾选的音源引擎拉起来。不做这一步的话，用户重启 App 后第一次
    // 播放远端曲会静默失败 —— 引擎没起，求直链直接返回 null。
    LaunchedEffect(container) {
        container.musicSourceRepository.activateCurrent()
    }

    CompositionLocalProvider(
        LocalCardStyle provides CardStyle(alpha = settings.cardAlpha, blurDp = settings.cardBlurDp),
    ) {
        AppBackground(
            wallpaperUri = settings.wallpaperUri,
            wallpaperAlpha = settings.wallpaperAlpha,
            wallpaperBlurDp = settings.wallpaperBlurDp,
        ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                    navController = navController,
                    startDestination = MainDestination.Edit.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(MainDestination.Resolve.route) {
                        val factory = remember(container) {
                            ResolveViewModel.factory(
                                musicResolver = container.resolver,
                                parseApiSourceRepository = container.parseApiSourceRepository,
                                downloadRepository = container.downloadRepository,
                                parseRecordRepository = container.parseRecordRepository,
                                audioPlayer = container.audioPlayer,
                            )
                        }
                        val viewModel: ResolveViewModel = viewModel(factory = factory)
                        ResolveScreen(
                            viewModel = viewModel,
                            onOpenRecords = { navController.navigate("records") },
                        )
                    }
                    composable("records") {
                        val factory = remember(container) {
                            RecordsViewModel.factory(
                                downloadRepository = container.downloadRepository,
                                parseRecordRepository = container.parseRecordRepository,
                            )
                        }
                        val viewModel: RecordsViewModel = viewModel(factory = factory)
                        RecordsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(EditDestination.HomeRoute) {
                        val factory = remember(container) {
                            EditHomeViewModel.factory(
                                container.sourceTrackRepository,
                                container.editProjectRepository,
                            )
                        }
                        val viewModel: EditHomeViewModel = viewModel(factory = factory)
                        EditHomeScreen(
                            viewModel = viewModel,
                            onOpenWorkspace = { operation, trackId, joinedIds ->
                                navController.navigate(EditDestination.workspace(operation, trackId, joinedIds))
                            },
onOpenTrim = { navController.navigate("trim") },
                            onOpenVolume = { navController.navigate("volume") },
                            onOpenFade = { navController.navigate("fade") },
                            onOpenJoin = { navController.navigate("join") },
                            onOpenSpeedPitch = { navController.navigate("speed_pitch") },
                            onOpenLrc = { navController.navigate("lrc") },
                            onOpenEqualizer = { navController.navigate("equalizer") },
                            onOpenDenoise = { navController.navigate("denoise") },
                            onOpenReverb = { navController.navigate("reverb") },
                            onOpenEcho = { navController.navigate("echo") },
                            onOpenChoir = { navController.navigate("choir") },
    onOpenRepair = { navController.navigate("repair") },
    onOpenVideoAudio = { navController.navigate("video_audio") },
        onOpenStereoOrbit = { navController.navigate("stereo_orbit") },
        onOpenLoudness = { navController.navigate("loudness") },
        onOpenStereoSplit = { navController.navigate("stereo_split") },
        onOpenStereoCompose = { navController.navigate("stereo_compose") },
                            onOpenConvert = { navController.navigate("convert") },
                            onOpenMetadata = { navController.navigate("metadata") },
                        )
                    }
                    composable(MainDestination.Playback.route) {
                        PlayerHomeScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenSearch = { navController.navigate(PlayerRoute.Search) },
                            onOpenFavorites = { navController.navigate(PlayerRoute.Favorites) },
                            // 播放页由这一屏承载，所以开关状态提到外面 ——
                            // 搜索页点唱片要能直接把它拉起来。
                            sheetVisible = playerSheetVisible,
                            onSheetVisibleChange = { playerSheetVisible = it },
                        )
                    }
                    composable(PlayerRoute.Favorites) {
                        PlayerFavoritesScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(PlayerRoute.Search) {
                        PlayerSearchScreen(
                            container = container,
                            onBack = { navController.popBackStack() },
                            onOpenSourceSettings = { navController.navigate(SettingsRoute.PlayerSource) },
                            onOpenPlayer = {
                                // 播放页挂在播放页那一屏，先切回去再把它拉起来
                                navController.popBackStack()
                                playerSheetVisible = true
                            },
                        )
                    }
                    composable("metadata") {
                        val factory = remember(container) {
                            MetadataViewModel.factory(
                                container.sourceTrackRepository,
                                AudioFileProbe(),
                                container.exportTargetWriter,
                            )
                        }
                        val metadataViewModel: MetadataViewModel = viewModel(factory = factory)
                        MetadataScreen(
                            viewModel = metadataViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("convert") {
                        val factory = remember(container) {
                            ConvertViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val convertViewModel: ConvertViewModel = viewModel(factory = factory)
                        ConvertScreen(
                            viewModel = convertViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("fade") {
                        val factory = remember(container) {
                            FadeViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val fadeViewModel: FadeViewModel = viewModel(factory = factory)
                        FadeScreen(
                            viewModel = fadeViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("join") {
                        val factory = remember(container) {
                            JoinViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val joinViewModel: JoinViewModel = viewModel(factory = factory)
                        JoinScreen(
                            viewModel = joinViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("speed_pitch") {
                        val factory = remember(container) {
                            SpeedPitchViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val speedPitchViewModel: SpeedPitchViewModel = viewModel(factory = factory)
                        SpeedPitchScreen(
                            viewModel = speedPitchViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("lrc") {
                        val factory = remember(container) {
                            LrcEditViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val lrcViewModel: LrcEditViewModel = viewModel(factory = factory)
                        LrcEditScreen(
                            viewModel = lrcViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("equalizer") {
                        val factory = remember(container) {
                            EqualizerViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val eqViewModel: EqualizerViewModel = viewModel(factory = factory)
                        EqualizerScreen(
                            viewModel = eqViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("denoise") {
                        val factory = remember(container) {
                            DenoiseViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val denoiseViewModel: DenoiseViewModel = viewModel(factory = factory)
                        DenoiseScreen(
                            viewModel = denoiseViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("reverb") {
                        val factory = remember(container) {
                            ReverbViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val reverbViewModel: ReverbViewModel = viewModel(factory = factory)
                        ReverbScreen(
                            viewModel = reverbViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("echo") {
                        val factory = remember(container) {
                            EchoViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val echoViewModel: EchoViewModel = viewModel(factory = factory)
                        EchoScreen(
                            viewModel = echoViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("choir") {
                        val factory = remember(container) {
                            ChoirViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val choirViewModel: ChoirViewModel = viewModel(factory = factory)
                        ChoirScreen(
                            viewModel = choirViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("loudness") {
        val factory = remember(container) {
            LoudnessViewModel.factory(
                sourceTrackRepository = container.sourceTrackRepository,
                audioPlayer = container.audioPlayer,
                previewRenderer = container.editPreviewRenderer,
                encoderClient = container.encoderClient,
                exportPublisher = container.exportPublisher,
                exportTempDirectory = container.exportTempDirectory,
            )
        }
        val viewModel: LoudnessViewModel = viewModel(factory = factory)
        LoudnessScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            bottomBarSlot = { canExport, label ->
                // enabled 只看忙碌状态：没导入时按钮保持常态色，
                // 「请先导入音频」只是文案，点下去由 ViewModel 拒绝并提示。
                val busy = viewModel.uiState.value.isWorking ||
                    viewModel.exportState.value.isRunning
                actionButton(label = label, enabled = !busy, onClick = { requestExport() })
            },
        )
    }
    composable("stereo_split") {
        val factory = remember(container) {
            StereoSplitViewModel.factory(
                sourceTrackRepository = container.sourceTrackRepository,
                audioPlayer = container.audioPlayer,
                previewRenderer = container.editPreviewRenderer,
                encoderClient = container.encoderClient,
                exportPublisher = container.exportPublisher,
                exportTempDirectory = container.exportTempDirectory,
            )
        }
        val viewModel: StereoSplitViewModel = viewModel(factory = factory)
        StereoSplitScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            bottomBarSlot = { canExport, label ->
                // 两态共用一个按钮：没拆时是「开始分离」，拆完是「导出」。
                // enabled 只看忙碌状态 —— 没导入时按钮保持常态色，
                // 「请先导入音频」只是文案，点下去由 ViewModel 拒绝并提示。
                val state = viewModel.uiState.value
                val busy = state.isWorking || viewModel.exportState.value.isRunning
                if (!state.isSplit) {
                    PrimaryButton(
                        text = label,
                        onClick = { viewModel.split() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    actionButton(label = label, enabled = !busy, onClick = { requestExport() })
                }
            },
        )
    }
    composable("stereo_compose") {
        val factory = remember(container) {
            StereoComposeViewModel.factory(
                sourceTrackRepository = container.sourceTrackRepository,
                audioPlayer = container.audioPlayer,
                previewRenderer = container.editPreviewRenderer,
                encoderClient = container.encoderClient,
                exportPublisher = container.exportPublisher,
                exportTempDirectory = container.exportTempDirectory,
            )
        }
        val viewModel: StereoComposeViewModel = viewModel(factory = factory)
        StereoComposeScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            bottomBarSlot = { canExport, label ->
                // 同上：enabled 只看忙碌状态
                val state = viewModel.uiState.value
                val busy = state.isWorking || viewModel.exportState.value.isRunning
                if (!state.isComposed) {
                    PrimaryButton(
                        text = label,
                        onClick = { viewModel.compose() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    actionButton(label = label, enabled = !busy, onClick = { requestExport() })
                }
            },
        )
    }
    composable("stereo_orbit") {
        val factory = remember(container) {
            StereoOrbitViewModel.factory(
                sourceTrackRepository = container.sourceTrackRepository,
                audioPlayer = container.audioPlayer,
                previewRenderer = container.editPreviewRenderer,
                encoderClient = container.encoderClient,
                exportPublisher = container.exportPublisher,
                exportTempDirectory = container.exportTempDirectory,
            )
        }
        val viewModel: StereoOrbitViewModel = viewModel(factory = factory)
        StereoOrbitScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            bottomBarSlot = { canExport, label ->
                val busy = viewModel.uiState.value.isWorking ||
                    viewModel.exportState.value.isRunning
                actionButton(label = label, enabled = !busy, onClick = { requestExport() })
            },
        )
    }
    composable("repair") {
        val factory = remember(container) {
            RepairViewModel.factory(
                sourceTrackRepository = container.sourceTrackRepository,
                probe = AudioFileProbe(),
                audioPlayer = container.audioPlayer,
                previewRenderer = container.editPreviewRenderer,
                encoderClient = container.encoderClient,
                exportPublisher = container.exportPublisher,
                exportTempDirectory = container.exportTempDirectory,
            )
        }
        val repairViewModel: RepairViewModel = viewModel(factory = factory)
        RepairScreen(
            viewModel = repairViewModel,
            onBack = { navController.popBackStack() },
            bottomBarSlot = { canExport, label ->
                val busy = repairViewModel.uiState.value.isWorking ||
                    repairViewModel.exportState.value.isRunning
                actionButton(label = label, enabled = !busy, onClick = { requestExport() })
            },
        )
    }
    composable("video_audio") {
        val factory = remember(container) {
            VideoAudioViewModel.factory(
                context = container.applicationContext,
                probe = AudioFileProbe(),
                audioPlayer = container.audioPlayer,
                previewRenderer = container.editPreviewRenderer,
                encoderClient = container.encoderClient,
                exportPublisher = container.exportPublisher,
                exportTempDirectory = container.exportTempDirectory,
                importDirectory = File(container.applicationContext.filesDir, "video-import"),
            )
        }
        val videoAudioViewModel: VideoAudioViewModel = viewModel(factory = factory)
        VideoAudioScreen(
            viewModel = videoAudioViewModel,
            onBack = { navController.popBackStack() },
            bottomBarSlot = { canExport, label ->
                val busy = videoAudioViewModel.uiState.value.isWorking ||
                    videoAudioViewModel.exportState.value.isRunning
                actionButton(label = label, enabled = !busy, onClick = { requestExport() })
            },
        )
    }
    composable("volume") {
                        val factory = remember(container) {
                            VolumeViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                previewRenderer = container.editPreviewRenderer,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val volumeViewModel: VolumeViewModel = viewModel(factory = factory)
                        VolumeScreen(
                            viewModel = volumeViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("trim") {
                        val factory = remember(container) {
                            TrimViewModel.factory(
                                sourceTrackRepository = container.sourceTrackRepository,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                waveformExtractor = container.waveformExtractor,
                                encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val trimViewModel: TrimViewModel = viewModel(factory = factory)
                        TrimScreen(
                            viewModel = trimViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(EditDestination.RecordsRoute) {
                        val context = LocalContext.current
                        val factory = remember(container) {
                            EditRecordsViewModel.factory(
                                container.editProjectRepository,
                                container.exportPackageRepository,
                                deleteExportFile = { outputPath -> deleteExportedFile(context, outputPath) },
                            )
                        }
                        val viewModel: EditRecordsViewModel = viewModel(factory = factory)
                        EditRecordsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(EditDestination.WorkspaceRoute) { entry ->
                        val operation = entry.arguments
                            ?.getString(EditDestination.OperationArg)
                            ?.let { name -> runCatching { EditOperation.valueOf(name) }.getOrNull() }
                        val trackId = entry.arguments?.getString(EditDestination.TrackIdArg)
                        if (operation == null) {
                            LaunchedEffect(Unit) { navController.popBackStack() }
                        } else {
                            val joinedIds = remember(entry) {
                                EditDestination.joinedIdsOf(entry.arguments?.getString(EditDestination.JoinedIdsArg))
                            }
                            val factory = remember(container, operation, trackId, joinedIds) {
                                EditWorkspaceViewModel.factory(
                                    operation = operation,
                                    sourceTrackId = trackId.orEmpty(),
                                    joinedTrackIds = joinedIds,
                                    exportTempDirectory = container.exportTempDirectory,
                                    sourceTrackRepository = container.sourceTrackRepository,
                                    editProjectRepository = container.editProjectRepository,
                                    exportPackageRepository = container.exportPackageRepository,
                                    audioPlayer = container.audioPlayer,
                                    waveformSource = container.waveformExtractor,
                                    lyricsParser = container.lyricsParser,
                                    encoderClient = container.encoderClient,
                                exportPublisher = container.exportPublisher,
                                previewRenderer = container.editPreviewRenderer,
                                )
                            }
                            val viewModel: EditWorkspaceViewModel = viewModel(factory = factory)
                            EditWorkspaceScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                    composable(MainDestination.Settings.route) {
                        val factory = remember(container) { SettingsViewModel.factory(container.settingsRepository) }
                        val viewModel: SettingsViewModel = viewModel(factory = factory)
                        SettingsHomeScreen(
                            viewModel = viewModel,
                            onOpen = { route -> navController.navigate(route) },
                        )
                    }
                    composable(SettingsRoute.Appearance) {
                        val factory = remember(container) { SettingsViewModel.factory(container.settingsRepository) }
                        val viewModel: SettingsViewModel = viewModel(factory = factory)
                        val context = LocalContext.current
                        var cropSource by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                        val wallpaperPicker = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument(),
                        ) { uri ->
                            if (uri == null) return@rememberLauncherForActivityResult
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                            cropSource = decodeWallpaper(context, uri, 0)
                        }
                        val cropping = cropSource
                        if (cropping != null) {
                            WallpaperCropScreen(
                                source = cropping,
                                onCancel = { cropSource = null },
                                onConfirm = { file ->
                                    viewModel.setWallpaper(wallpaperUri(file))
                                    cropSource = null
                                },
                            )
                        } else {
                            AppearanceScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onPickWallpaper = {
                                    wallpaperPicker.launch(arrayOf("image/*"))
                                },
                            )
                        }
                    }
                    composable(SettingsRoute.Download) {
                        val factory = remember(container) { SettingsViewModel.factory(container.settingsRepository) }
                        val viewModel: SettingsViewModel = viewModel(factory = factory)
                        val context = LocalContext.current
                        val directoryPicker = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocumentTree(),
                        ) { uri ->
                            if (uri == null) return@rememberLauncherForActivityResult
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }
                            viewModel.setDownloadDirectory(uri.toString())
                        }
                        DownloadSettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onPickDirectory = { directoryPicker.launch(null) },
                        )
                    }
                    composable(SettingsRoute.PlayerSource) {
                        val factory = remember(container) { SettingsViewModel.factory(container.settingsRepository) }
                        val viewModel: SettingsViewModel = viewModel(factory = factory)
                        val sourceFactory = remember(container) {
                            MusicSourceViewModel.factory(
                                repository = container.musicSourceRepository,
                                engineState = container.lxSourceRuntime.state,
                                contentResolver = context.contentResolver,
                            )
                        }
                        val sourceViewModel: MusicSourceViewModel = viewModel(factory = sourceFactory)
                        PlayerSourceSettingsScreen(
                            viewModel = viewModel,
                            sourceViewModel = sourceViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(SettingsRoute.Naming) {
                        val factory = remember(container) { SettingsViewModel.factory(container.settingsRepository) }
                        val viewModel: SettingsViewModel = viewModel(factory = factory)
                        NamingSettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(SettingsRoute.Cache) {
        val factory = remember(container) {
            CacheViewModel.factory(
                cleaner = container.cacheCleaner,
                encoderClient = container.encoderClient,
            )
        }
        val viewModel: CacheViewModel = viewModel(factory = factory)
        CacheScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(SettingsRoute.ParseApi) {
        val factory = remember(container) {
            ParseApiViewModel.factory(container.parseApiSourceRepository)
        }
        val viewModel: ParseApiViewModel = viewModel(factory = factory)
        ParseApiSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable(SettingsRoute.About) {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                }
                // ponytail: 只在一级页面显示悬浮胶囊，内容从它下面穿过
                // 全屏播放页盖住整屏时也要把底栏藏掉，否则会浮在播放页上面
                if (currentRoute in MainDestination.entries.map { it.route } && !playerSheetVisible) {
                    FloatingBottomBar(
                        destinations = MainDestination.entries,
                        currentRoute = currentRoute,
                        onSelect = { destination ->
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/** 导出既可能是普通路径也可能是 SAF 文档，两种都试一下。 */
private fun deleteExportedFile(context: Context, outputPath: String): Boolean {
    val uri = runCatching { Uri.parse(outputPath) }.getOrNull() ?: return false
    return runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            File(uri.path ?: outputPath).takeIf(File::exists)?.delete() ?: true
        }
    }.getOrDefault(false)
}
