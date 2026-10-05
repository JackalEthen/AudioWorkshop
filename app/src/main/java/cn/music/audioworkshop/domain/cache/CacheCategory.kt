package cn.music.audioworkshop.domain.cache

import android.content.Context
import java.io.File

/**
 * 可清理的缓存分类。
 *
 * 只列白名单目录 —— 清理是递归删除，出错就是用户文件没了，
 * 所以宁可漏掉一类，也不能扫到目录外面去。
 */
enum class CacheCategory(
    val label: String,
    val caption: String,
    /** 相对 [Context.getCacheDir] 或 [Context.getFilesDir]，null = 该分类暂无落盘缓存。 */
    private val relativeDirs: List<Pair<Boolean, String>>,
) {
    /** 编辑页：实时预览渲染 + 导出中间文件。 */
    EDIT(
        label = "编辑页缓存",
        caption = "实时预览与导出过程的临时文件",
        relativeDirs = listOf(
            true to "edit-preview",
            true to "exports",
            true to "export-jobs",
            true to "metadata",
        ),
    ),

    /**
     * 本地导入的音频副本。
     *
     * 编辑页和播放器页共用 `filesDir/imports`（导入器是同一个），
     * 文件名就是原始文件名，无法区分来源，所以合成一类。
     */
    IMPORT(
        label = "导入音频缓存",
        caption = "本地导入的歌曲副本",
        relativeDirs = listOf(false to "imports", false to "video-import"),
    ),

    /** 播放器搜索结果的封面图，Coil 的磁盘缓存。见 [coilDiskCacheDir]。 */
    PLAYER(
        label = "播放器搜索缓存",
        caption = "搜索结果封面",
        relativeDirs = listOf(true to "player-cover"),
    ),

    /**
     * 解析页缓存。
     *
     * 目前解析只走内存与网络，没有落盘中间文件；OkHttp 也没配磁盘缓存。
     * 保留分类是为了以后加了缓存有地方可清，也提醒用户这一项确实是空的。
     */
    PARSE(
        label = "解析页缓存",
        caption = "解析过程的中间数据",
        relativeDirs = emptyList(),
    ),
    ;

    /** 该分类对应的实际目录。PARSE 为空列表。 */
    fun dirs(context: Context): List<File> = relativeDirs.map { (useCache, name) ->
        File(if (useCache) context.cacheDir else context.filesDir, name)
    }

    companion object {
        /** Coil 磁盘缓存目录名，与 [coilDiskCacheDir] 保持一致。 */
        const val COIL_DIR = "player-cover"

        /**
         * 初始化 Coil 的磁盘缓存目录。
         *
         * Coil 默认写 `cacheDir/image_cache`，那是个内部约定、版本升级可能变。
         * 自己指定一个稳定路径，清理时才有确切目标。
         *
         * 必须由 [android.app.Application] 的 `onCreate` 调一次，早于任何图片加载。
         */
        fun initPlayerCoverCache(context: Context) {
            File(context.cacheDir, COIL_DIR).mkdirs()
        }

        /** 给 Coil 用的 [coil.disk.DiskCache] 目录，放在 cacheDir 下。 */
        fun playerCoverCacheDir(context: Context): File = File(context.cacheDir, COIL_DIR)
    }
}
