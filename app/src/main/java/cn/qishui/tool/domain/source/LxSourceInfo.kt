package cn.qishui.tool.domain.source

/**
 * 引擎初始化后拿到的音源能力。
 *
 * 对应 lx 协议的 `init` 消息。平台键是 `kw/kg/tx/wy/mg/local`。
 */
data class LxSourceInfo(
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val homepage: String,
    /** 平台 -> 该平台支持的音质。 */
    val qualitys: Map<String, List<String>>,
    /** 平台 -> 该平台支持的 action（musicUrl / lyric / pic）。 */
    val actions: Map<String, List<String>>,
) {
    fun supports(platform: String, action: String): Boolean =
        actions[platform]?.contains(action) == true

    /** 能不能当搜索/播放来源用：至少有一个远端平台支持 musicUrl。 */
    val isUsable: Boolean
        get() = qualitys.keys.any { supports(it, ACTION_MUSIC_URL) }

    val platforms: Set<String>
        get() = qualitys.keys

    companion object {
        const val ACTION_MUSIC_URL = "musicUrl"
        const val ACTION_LYRIC = "lyric"
        const val ACTION_PIC = "pic"

        /** lx 协议里所有合法的平台键。 */
        val PLATFORMS = listOf("kw", "kg", "tx", "wy", "mg", "local")

        /** 远端平台的音质档位，和 lx preload 里的 supportQualitys 一致。 */
        val REMOTE_QUALITIES = listOf("128k", "320k", "flac", "flac24bit")

        val PLATFORM_LABELS = mapOf(
            "kw" to "酷我",
            "kg" to "酷狗",
            "tx" to "QQ 音乐",
            "wy" to "网易云",
            "mg" to "咪咕",
            "local" to "本地",
        )
    }
}

/** 引擎状态。用来在 UI 上显示为什么没生效。 */
sealed interface LxSourceState {
    data object Idle : LxSourceState
    data object Loading : LxSourceState
    data class Ready(val info: LxSourceInfo) : LxSourceState
    data class Failed(val message: String) : LxSourceState
}
