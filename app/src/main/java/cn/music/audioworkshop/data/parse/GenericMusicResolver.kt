package cn.music.audioworkshop.data.parse

import cn.music.audioworkshop.data.api.ShareLinkException
import cn.music.audioworkshop.data.api.ShareLinkExtractor
import cn.music.audioworkshop.domain.MusicResolveException
import cn.music.audioworkshop.domain.MusicResolver
import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.model.ResolvedTrack
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 通用解析器：由用户配置的源驱动。
 *
 * 不内置任何源 —— [source] 为 null 时直接返回「未配置解析源」，
 * 解析页据此提示用户去设置页添加。
 */
class GenericMusicResolver(
    private val shareLinkExtractor: ShareLinkExtractor,
    private val apiClient: GenericParseApiClient,
) : MusicResolver {

    override suspend fun resolve(
        shareInput: String,
        source: ParseApiSource?,
    ): Result<ResolvedTrack> {
        if (source == null) {
            return Result.failure(
                MusicResolveException("未配置解析源，请到设置 → 解析源添加后再试"),
            )
        }
        return try {
            val shareUrl = shareLinkExtractor.extract(shareInput)
            val body = apiClient.fetch(source, shareUrl)
            val track = ParseResponseMatcher(source.fields).match(body)
                .copy(sourceShareUrl = shareUrl)
            Result.success(track)
        } catch (error: Exception) {
            Result.failure(error.toUserFacing())
        }
    }

    private fun Exception.toUserFacing(): Exception = when (this) {
        is MusicResolveException -> this
        is ShareLinkException -> MusicResolveException(message ?: "无法识别链接")
        is ParseResponseMatcher.ParseFailure -> MusicResolveException(message ?: "接口返回格式异常")
        is SocketTimeoutException -> MusicResolveException("请求超时，请稍后重试")
        is GenericParseException -> MusicResolveException(message ?: "请求失败")
        is IOException -> MusicResolveException("网络失败，请检查网络或解析源地址")
        else -> MusicResolveException("解析失败：${message ?: "未知错误"}")
    }
}
