package cn.qishui.tool.data.source

import android.content.Context
import cn.qishui.tool.domain.source.LxSourceInfo
import cn.qishui.tool.domain.source.LxSourceState
import cn.qishui.tool.media.source.LxSourceEngine
import cn.qishui.tool.media.source.LxSourceMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * lx 音源的运行时：管引擎实例的生死。
 *
 * 同一时刻只跑一个引擎（协议上也只能有一个生效），
 * 换音源就是换脚本、销毁旧引擎、重新初始化。
 */
class LxSourceRuntime(
    private val context: Context,
    private val client: OkHttpClient,
    private val scriptLoader: LxSourceScriptLoader,
) {
    private val engine = LxSourceEngine(client)

    private val mutableState = MutableStateFlow<LxSourceState>(LxSourceState.Idle)
    val state: StateFlow<LxSourceState> = mutableState.asStateFlow()

    /** 用选中的脚本重建引擎。传 null 表示没有选中任何音源。 */
    suspend fun activate(
        script: String?,
        id: String = "",
        name: String = "",
        description: String = "",
        version: String = "",
        author: String = "",
        homepage: String = "",
    ) = withContext(Dispatchers.IO) {
        // 换音源用 reset()，不是 release() —— release 会关掉线程池，之后再也起不来
        engine.reset()
        if (script.isNullOrBlank()) {
            mutableState.value = LxSourceState.Idle
            return@withContext
        }
        mutableState.value = LxSourceState.Loading
        val preload = scriptLoader.loadPreload()
        val result = runCatching {
            engine.start(
                preload = preload,
                script = script,
                meta = LxSourceMeta(
                    id = id,
                    name = name,
                    description = description,
                    version = version,
                    author = author,
                    homepage = homepage,
                ),
            )
        }
        mutableState.value = result.fold(
            onSuccess = { LxSourceState.Ready(it) },
            onFailure = { LxSourceState.Failed(it.message ?: "音源初始化失败") },
        )
    }

    /** 当前引擎的能力；没就绪返回 null。 */
    fun sourceInfo(): LxSourceInfo? = (mutableState.value as? LxSourceState.Ready)?.info

    /**
     * 问当前引擎要播放地址。
     *
     * 返回 null = 引擎没就绪或不支持这个平台（属于「没配置」，调用方给引导提示）。
     * 音源**明确回了失败**时抛 [cn.qishui.tool.media.source.LxSourceRequestException]，
     * 原因要透给用户。
     */
    suspend fun resolveMusicUrl(
        platform: String,
        musicInfo: Map<String, Any?>,
        quality: String,
    ): String? = withContext(Dispatchers.IO) {
        val info = (mutableState.value as? LxSourceState.Ready)?.info ?: return@withContext null
        if (!info.supports(platform, LxSourceInfo.ACTION_MUSIC_URL)) return@withContext null
        // 刻意不加 runCatching：音源给的原因比「解析失败」有用得多。
        engine.requestAction(platform, LxSourceInfo.ACTION_MUSIC_URL, musicInfo, quality)
            .extractMusicUrl()
    }

    /**
     * 问当前引擎要歌词。
     *
     * 注意 lx 协议里 lyric 只对 `local` 平台开放（见 preload 的 supportActions），
     * 所以远端平台的歌这里一定拿不到歌词。
     */
    suspend fun resolveLyric(platform: String, musicInfo: Map<String, Any?>): String? =
        withContext(Dispatchers.IO) {
            val info = (mutableState.value as? LxSourceState.Ready)?.info ?: return@withContext null
            if (!info.supports(platform, LxSourceInfo.ACTION_LYRIC)) return@withContext null
            engine.requestAction(platform, LxSourceInfo.ACTION_LYRIC, musicInfo, null)
                .extractLyric()
        }

    /**
     * 问当前引擎要封面。
     *
     * pic 的形状和另外两个不一样：`data` **本身就是一个 URL 字符串**，
     * 不是 `{url: ...}`。见 preload 的 handleRequest。
     */
    suspend fun resolvePic(platform: String, musicInfo: Map<String, Any?>): String? =
        withContext(Dispatchers.IO) {
            val info = (mutableState.value as? LxSourceState.Ready)?.info ?: return@withContext null
            if (!info.supports(platform, LxSourceInfo.ACTION_PIC)) return@withContext null
            engine.requestAction(platform, LxSourceInfo.ACTION_PIC, musicInfo, null)
                .extractPic()
        }

    fun release() = engine.release()
}

// ---- 音源回包的取值 ----
//
// 这三个的形状**不一样**，且写错路径不会报错、只会静默返回 null，
// 曾导致「音源明明返回了地址，App 却说解析失败」。所以抽成纯函数并加了单测。
//
// 形状来自 preload 的 handleRequest（assets/lx/user-api-preload.js）：
//   musicUrl → result: { source, action, data: { type, url } }
//   lyric    → result: { source, action, data: { lyric, tlyric, ... } }
//   pic      → result: { source, action, data: "https://…" }   ← data 本身就是字符串

internal fun JSONObject?.extractMusicUrl(): String? =
    this?.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }

internal fun JSONObject?.extractLyric(): String? =
    this?.optJSONObject("data")?.optString("lyric")?.takeIf { it.isNotBlank() }

internal fun JSONObject?.extractPic(): String? =
    this?.optString("data")?.takeIf { it.startsWith("http") }

/** 读 assets 里的 preload 脚本。 */
class LxSourceScriptLoader(private val context: Context) {
    fun loadPreload(): String = context.assets.open(PRELOAD_ASSET).bufferedReader().use { it.readText() }

    companion object {
        const val PRELOAD_ASSET = "lx/user-api-preload.js"
    }
}
