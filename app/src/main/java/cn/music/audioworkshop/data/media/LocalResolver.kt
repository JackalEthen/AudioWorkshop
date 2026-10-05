package cn.music.audioworkshop.data.media

import android.net.Uri
import cn.music.audioworkshop.domain.player.PlaybackQuality
import cn.music.audioworkshop.domain.player.QueueItem
import cn.music.audioworkshop.domain.player.UrlResolveException
import cn.music.audioworkshop.domain.player.UrlResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地文件解析器：地址就是文件路径本身，不需要任何网络请求。
 *
 * 阶段 1 唯一的实现。阶段 4 的 lx 音源会并排加一个 [cn.music.audioworkshop.domain.player.UrlResolver] 实现，
 * 播放链路只认接口，所以这边不用动。
 */
class LocalResolver : UrlResolver {

    override val sourceCode: String = SOURCE_LOCAL

    override suspend fun resolve(song: QueueItem, quality: PlaybackQuality): Uri = withContext(Dispatchers.IO) {
        val path = song.localPath
        if (path.isNullOrBlank()) {
            throw UrlResolveException("「${song.title}」没有本地文件路径")
        }
        val file = File(path)
        if (!file.isFile) {
            throw UrlResolveException("文件不存在：${file.name}")
        }
        Uri.fromFile(file)
    }

    companion object {
        const val SOURCE_LOCAL = "local"
    }
}
