package cn.music.audioworkshop

import android.app.Application
import cn.music.audioworkshop.app.AppContainer
import cn.music.audioworkshop.app.ProcessIdentity
import cn.music.audioworkshop.domain.cache.CacheCategory
import cn.music.audioworkshop.util.SourceLog
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.request.CachePolicy

class QishuiApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (!ProcessIdentity.isMainProcess(this)) return
        CacheCategory.initPlayerCoverCache(this)
        SourceLog.init(this)
        container = AppContainer(this)
        container.encoderClient.bind()
    }

    /**
     * 显式指定 Coil 的磁盘缓存目录。
     *
     * 默认是 `cacheDir/image_cache` —— 内部约定，升级可能变，
     * 设置页的「清理播放器搜索缓存」就没有确切目标可指。
     * 换成自己命名的 [CacheCategory.COIL_DIR]，路径稳定。
     *
     * 上限取 2% 可用空间：封面只是列表缩略图，缓存太大没有意义。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCachePolicy(CachePolicy.ENABLED)
        .diskCache {
            DiskCache.Builder()
                .directory(CacheCategory.playerCoverCacheDir(this@QishuiApplication))
                .maxSizePercent(0.02)
                .build()
        }
        .build()

    override fun onTerminate() {
        if (ProcessIdentity.isMainProcess(this)) {
            container.encoderClient.unbind()
        }
        super.onTerminate()
    }
}
