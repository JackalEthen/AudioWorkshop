package cn.qishui.tool.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import cn.qishui.tool.app.AppContainer
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.feature.effect.EffectWorkspaceScreen
import cn.qishui.tool.feature.effect.EffectWorkspaceViewModel
import cn.qishui.tool.feature.metadata.MetadataScreen
import cn.qishui.tool.feature.video.VideoEditScreen
import cn.qishui.tool.feature.video.VideoExtractAudioScreen
import cn.qishui.tool.feature.video.VideoExtractAudioViewModel
import cn.qishui.tool.feature.video.VideoTool
import cn.qishui.tool.feature.metadata.MetadataViewModel
import cn.qishui.tool.feature.player.PlayerFavoritesScreen
import cn.qishui.tool.feature.player.PlayerHomeScreen
import cn.qishui.tool.feature.player.PlayerRoute
import cn.qishui.tool.feature.player.PlayerSearchScreen
import cn.qishui.tool.feature.source.MusicSourceViewModel
import java.io.File
import cn.qishui.tool.domain.model.AppSettings
import cn.qishui.tool.domain.model.EditOperation
import cn.qishui.tool.feature.edit.EditDestination
import cn.qishui.tool.feature.edit.EditHomeScreen
import cn.qishui.tool.feature.edit.EditHomeViewModel
import cn.qishui.tool.feature.edit.EditRecordsScreen
import cn.qishui.tool.feature.edit.EditRecordsViewModel
import cn.qishui.tool.feature.edit.EditWorkspaceScreen
import cn.qishui.tool.feature.edit.EditWorkspaceViewModel
import cn.qishui.tool.feature.records.RecordsScreen
import cn.qishui.tool.feature.records.RecordsViewModel
import cn.qishui.tool.feature.resolve.ResolveScreen
import cn.qishui.tool.feature.resolve.ResolveViewModel
import cn.qishui.tool.feature.settings.AboutScreen
import cn.qishui.tool.feature.settings.AppearanceScreen
import cn.qishui.tool.feature.settings.DownloadSettingsScreen
import cn.qishui.tool.feature.settings.NamingSettingsScreen
import cn.qishui.tool.feature.settings.PlayerSourceSettingsScreen
import cn.qishui.tool.feature.settings.SettingsHomeScreen
import cn.qishui.tool.feature.settings.SettingsRoute
import cn.qishui.tool.feature.settings.SettingsViewModel
import cn.qishui.tool.ui.components.AppBackground
import cn.qishui.tool.ui.components.FloatingBottomBar
import cn.qishui.tool.ui.components.WallpaperCropScreen
import cn.qishui.tool.ui.components.decodeWallpaper
import cn.qishui.tool.ui.components.wallpaperUri
import cn.qishui.tool.ui.theme.CardStyle
import cn.qishui.tool.ui.theme.LocalCardStyle

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
                    startDestination = MainDestination.Resolve.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(MainDestination.Resolve.route) {
                        val factory = remember(container) {
                            ResolveViewModel.factory(
                                musicResolver = container.resolver,
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
                            onOpenRecords = { navController.navigate(EditDestination.RecordsRoute) },
                            onOpenEffect = { effectId -> navController.navigate("effect/$effectId") },
                            onExtractVideoAudio = { navController.navigate("video_extract") },
                            onVideoTrim = { navController.navigate("video_trim") },
                            onVideoJoin = { navController.navigate("video_join") },
                            onVideoSpeed = { navController.navigate("video_speed") },
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
                    composable("video_extract") {
                        val factory = remember(container) {
                            VideoExtractAudioViewModel.factory(
                                videoTools = container.videoTools,
                                encoderClient = container.encoderClient,
                                exportTargetWriter = container.exportTargetWriter,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val extractViewModel: VideoExtractAudioViewModel = viewModel(factory = factory)
                        VideoExtractAudioScreen(
                            viewModel = extractViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    listOf(
                    VideoTool.TRIM to "video_trim",
                    VideoTool.JOIN to "video_join",
                    VideoTool.SPEED to "video_speed",
                ).forEach { (tool, route) ->
                    composable(route) {
                        VideoEditScreen(
                            tool = tool,
                            onBack = { navController.popBackStack() },
                        )
                    }
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
                    composable("effect/{effectId}") { entry ->
                        val effectId = entry.arguments?.getString("effectId").orEmpty()
                        val context = LocalContext.current
                        val factory = remember(effectId) {
                            EffectWorkspaceViewModel.factory(
                                context = context,
                                effectId = effectId,
                                sourceTrackRepository = container.sourceTrackRepository,
                                encoderClient = container.encoderClient,
                                exportTargetWriter = container.exportTargetWriter,
                                targetResolver = container.downloadTargetResolver,
                                probe = AudioFileProbe(),
                                audioPlayer = container.audioPlayer,
                                pcmChunkReader = container.pcmChunkReader,
                                exportTempDirectory = container.exportTempDirectory,
                            )
                        }
                        val effectViewModel: EffectWorkspaceViewModel = viewModel(factory = factory)
                        EffectWorkspaceScreen(
                            viewModel = effectViewModel,
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
                                exportTargetWriter = container.exportTargetWriter,
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
