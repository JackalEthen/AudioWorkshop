package cn.music.audioworkshop.media.source

import android.net.Uri
import cn.music.audioworkshop.data.source.LxSourceRuntime
import cn.music.audioworkshop.domain.model.SourceCode
import cn.music.audioworkshop.domain.player.PlaybackQuality
import cn.music.audioworkshop.domain.player.QueueItem
import cn.music.audioworkshop.domain.player.UrlResolveException
import cn.music.audioworkshop.domain.player.UrlResolver
import cn.music.audioworkshop.domain.source.LxSourceInfo
import cn.music.audioworkshop.util.SourceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * lx 自定义源的播放地址解析器。
 *
 * 做的事只有一件：把队列项按 lx 音源协议要求的形状发给 JS 脚本，换回直链。
 * 协议见 `assets/lx/user-api-preload.js` 与 `Lx_SOURCE_INTEGRATION.md`。
 *
 * 一个实例服务一个平台，但五个平台共用同一个 [LxSourceRuntime]（引擎单例）。
 * 平台为什么分开而不用一个实例遍历：解析器是按 `sourceCode` 查表分发的，
 * 每平台一个 key 是最省事的注册方式。
 */
class LxSourceResolver(
    override val sourceCode: String,
    private val runtime: LxSourceRuntime,
) : UrlResolver {

    override suspend fun resolve(song: QueueItem, quality: PlaybackQuality): Uri = withContext(Dispatchers.IO) {
        SourceLog.i(TAG, "resolve 进入 platform=$sourceCode title=${song.title} id=${song.platformSongId}")
        val platformSongId = song.platformSongId
        if (platformSongId.isNullOrBlank()) {
            // 搜索结果会带 id，库里手改过的数据可能没有。别静默失败。
            throw UrlResolveException("「${song.title}」缺少平台歌曲 id，无法向音源求直链")
        }

        val info = runtime.sourceInfo() ?: throw UrlResolveException(
            "没有可用的音源。请在右上角播放设置里勾选一个音源脚本"
        )
        if (!info.supports(sourceCode, LxSourceInfo.ACTION_MUSIC_URL)) {
            throw UrlResolveException("当前音源不支持 $sourceCode 平台")
        }

        val musicInfo = song.toLxMusicInfo()
        val targetQuality = degradeQuality(quality.lxType, info.qualitys[sourceCode].orEmpty())
        SourceLog.i(
            TAG,
            "求直链 源=${info.name}($sourceCode) quality=$targetQuality " +
                "id=${song.platformSongId} info=$musicInfo"
        )

        val url = runtime.resolveMusicUrl(
            platform = sourceCode,
            musicInfo = musicInfo,
            quality = targetQuality,
        ) ?: throw UrlResolveException(
            "音源没能解析出「${song.title}」。" +
                "请确认右上角播放设置里已勾选一个音源，且该音源支持 $sourceCode 平台"
        )

        SourceLog.i(TAG, "拿到直链 url=$url")
        Uri.parse(url)
    }

    private companion object {
        const val TAG = "LxSourceResolver"
    }
}

/**
 * 从请求的档位往下找一个该音源支持的。
 *
 * 对应 lx 的 `getPlayQuality`（`src/core/music/utils.ts`）。**必须降级**：
 * 有些音源只声明了部分档位（比如只支持 128k），硬要 320k 的话
 * 服务端会直接 404，报错还完全看不出是音质选错了。
 *
 * 顺序是「从高往低」，所以先拿到能用的高音质。
 */
internal fun degradeQuality(requested: String, supported: List<String>): String {
    if (supported.isEmpty()) return requested
    val ladder = LxSourceInfo.REMOTE_QUALITIES
    val start = ladder.indexOf(requested).takeIf { it >= 0 } ?: ladder.lastIndex
    for (index in start downTo 0) {
        val candidate = ladder[index]
        if (candidate in supported) return candidate
    }
    return supported.first()
}

/**
 * 转成 lx 音源脚本要的那个扁平结构。
 *
 * 对应 lx-music-mobile 的 `src/utils/tools.ts` → `toOldMusicInfo`。
 * 关键点：平台特有字段**平铺在顶层**（`hash` / `strMediaMid` / `copyrightId`），
 * 不是嵌在 `meta` 里 —— 脚本直接读 `musicInfo.hash`，嵌一层就全拿不到。
 */
internal fun QueueItem.toLxMusicInfo(): Map<String, Any?> {
    val platform = sourceCode ?: SourceCode.LOCAL
    val id = platformSongId.orEmpty()

    return buildMap {
        put("name", title)
        put("singer", artist.orEmpty())
        put("source", platform)
        put("songmid", id)
        put("albumName", album.orEmpty())
        put("img", coverUri.orEmpty())
        put("interval", formatInterval(durationMs))
        put("albumId", "")
        put("typeUrl", emptyMap<String, Any>())
        // types/_types 是音源判断「这首歌有没有这个音质」的依据。
        // 搜索时拿不到就先给空，音源会退到它自己的默认档位。
        put("types", emptyList<String>())
        put("_types", emptyMap<String, Any>())

        when (platform) {
            SourceCode.KUGOU -> put("hash", id)
            SourceCode.TENCENT -> {
                put("strMediaMid", id)
                put("songId", id)
            }
            SourceCode.MIGU -> put("copyrightId", id)
        }
    }
}

/** lx 的 `formatPlayTime`：分:秒，一小时内不显示小时位。 */
private fun formatInterval(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
