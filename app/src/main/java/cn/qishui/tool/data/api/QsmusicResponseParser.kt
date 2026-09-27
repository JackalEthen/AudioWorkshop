package cn.qishui.tool.data.api

import cn.qishui.tool.domain.model.ResolvedTrack
import org.json.JSONException
import org.json.JSONObject

class QsmusicResponseParser {
    fun parse(responseBody: String): ResolvedTrack {
        val root = try {
            JSONObject(responseBody)
        } catch (error: JSONException) {
            throw ApiFormatException("接口响应不是有效 JSON", error)
        }
        val code = root.intOrNull("code")
            ?: throw ApiFormatException("接口响应缺少有效 code")
        if (code != 200) {
            val message = root.stringOrNull("msg") ?: "未知错误"
            throw ApiFormatException("接口返回错误（code=$code）：$message")
        }
        val data = root.optJSONObject("data")
            ?: throw ApiFormatException("接口响应缺少 data")
        val videoMeta = data.optJSONObject("video_meta")

        return ResolvedTrack(
            title = data.stringOrNull("albumname"),
            artist = data.stringOrNull("artistsname"),
            audioUrl = data.stringOrNull("url"),
            format = videoMeta?.stringOrNull("vtype"),
            codec = videoMeta?.stringOrNull("codec_type"),
            quality = videoMeta?.stringOrNull("quality"),
            bitrateBps = videoMeta?.longOrNull("real_bitrate") ?: videoMeta?.longOrNull("bitrate"),
            sizeBytes = videoMeta?.longOrNull("size"),
            sampleRateHz = videoMeta?.longOrNull("audio_sample_rate"),
            lyrics = data.stringOrNull("lyric"),
            artistAvatarUrls = data.stringListOrEmpty("artistsmedium_avatar_url"),
            cacheExpiresAtEpochSeconds = root.longOrNull("cache_expire_at"),
            fileHash = videoMeta?.stringOrNull("file_hash"),
        )
    }
}

class ApiFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun JSONObject.intOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return opt(name)?.toString()?.toIntOrNull()
}

private fun JSONObject.longOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return opt(name)?.toString()?.toLongOrNull()
}

private fun JSONObject.stringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return opt(name)
        ?.takeIf { it is String }
        ?.toString()
        ?.takeIf { it.isNotBlank() }
}

private fun JSONObject.stringListOrEmpty(name: String): List<String> {
    val values = optJSONArray(name) ?: return emptyList()
    return buildList {
        repeat(values.length()) { index ->
            if (!values.isNull(index)) {
                values.optString(index)
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }
}
