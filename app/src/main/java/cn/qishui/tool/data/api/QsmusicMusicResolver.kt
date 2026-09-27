package cn.qishui.tool.data.api

import cn.qishui.tool.domain.MusicResolver
import cn.qishui.tool.domain.model.ResolvedTrack
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

class QsmusicMusicResolver(
    private val shareLinkExtractor: ShareLinkExtractor,
    private val apiClient: QsmusicApiClient,
    private val responseParser: QsmusicResponseParser,
) : MusicResolver {
    override suspend fun resolve(shareInput: String): Result<ResolvedTrack> = try {
        val shareUrl = shareLinkExtractor.extract(shareInput)
        Result.success(
            responseParser.parse(apiClient.get(shareUrl)).copy(sourceShareUrl = shareUrl),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error.toUserFacingException())
    }
}

class MusicResolveException(message: String) : Exception(message)

private fun Exception.toUserFacingException(): Exception = when (this) {
    is ShareLinkException -> this
    is ApiFormatException -> MusicResolveException("接口返回格式异常：${message.orEmpty()}")
    is QsmusicRequestException -> MusicResolveException(message.orEmpty())
    is SocketTimeoutException -> MusicResolveException("网络请求超时，请重试")
    is IOException -> MusicResolveException("网络请求失败，请检查网络后重试")
    else -> MusicResolveException("解析失败，请稍后重试")
}
