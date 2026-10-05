package cn.music.audioworkshop.data.cache

import android.content.Context
import cn.music.audioworkshop.domain.cache.CacheCategory
import java.io.File

/**
 * 路径删除守卫。
 *
 * 抽成不依赖 [Context] 的纯函数，才能在 JVM 单元测试里验证 ——
 * 这是递归删除的闸门，不能只靠「分类表里写对了路径」来保证。
 */
object CachePathGuard {

    /**
     * [target] 是否允许删除。
     *
     * 必须严格位于 [root] 之下，且不是 [root] 本身。用 canonicalPath 比较
     * 而不是字符串前缀 —— 前缀比对会被 `cache_evil` 这类同前缀的兄弟目录骗过去。
     */
    fun isDeletable(root: File, target: File): Boolean {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return false
        if (targetPath == rootPath) return false
        return targetPath.startsWith(rootPath + File.separator)
    }
}

/**
 * 缓存大小统计与清理。
 *
 * 所有删除都要过 [CachePathGuard]：目标必须落在 [Context.getCacheDir] 或
 * [Context.getFilesDir] 之内。分类里写错一个路径、或将来有人改成外部目录，
 * 都会被挡下来，而不是把用户文件删掉。
 */
class CacheCleaner(context: Context) {

    private val appContext = context.applicationContext
    private val cacheRoot = appContext.cacheDir
    private val filesRoot = appContext.filesDir

    /** 某个分类当前占用的字节数。 */
    fun sizeOf(category: CacheCategory): Long =
        category.dirs(appContext).sumOf { dir -> dir.sizeRecursive() }

    /** 全部缓存合计。 */
    fun totalSize(): Long = CacheCategory.entries.sumOf { sizeOf(it) }

    /**
     * 清空某一类。
     *
     * @return 实际释放的字节数；目录不存在时返回 0。
     */
    fun clear(category: CacheCategory): Long {
        var freed = 0L
        for (dir in category.dirs(appContext)) {
            if (!dir.exists()) continue
            val size = dir.sizeRecursive()
            if (deleteIfAllowed(dir)) freed += size
        }
        // 删完把目录本身建回来，省得下次渲染预览还要重新 mkdir
        for (dir in category.dirs(appContext)) {
            if (!dir.exists()) runCatching { dir.mkdirs() }
        }
        return freed
    }

    /** 目录必须位于两个私有根目录之一，才允许删除。 */
    private fun deleteIfAllowed(target: File): Boolean {
        val allowed = CachePathGuard.isDeletable(cacheRoot, target) ||
            CachePathGuard.isDeletable(filesRoot, target)
        if (!allowed) return false
        return runCatching {
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        }.getOrDefault(false)
    }
}

/** 递归累计目录大小。读不进去的条目按 0 算，不让一个坏文件毁掉整次统计。 */
private fun File.sizeRecursive(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    val children = listFiles() ?: return 0L
    return children.sumOf { it.sizeRecursive() }
}
