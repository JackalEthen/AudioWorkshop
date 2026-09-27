package cn.qishui.tool.domain.media

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ExportJobFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ExportJobCodec {

    private const val KEY_JOB_ID = "jobId"
    private const val KEY_EDIT_PROJECT_ID = "editProjectId"
    private const val KEY_OUTPUT_TEMP_PATH = "outputTempPath"
    private const val KEY_GAIN_DB = "gainDb"
    private const val KEY_FADE_IN_MS = "fadeInMs"
    private const val KEY_FADE_OUT_MS = "fadeOutMs"
    private const val KEY_LYRIC_OFFSET_MS = "lyricOffsetMs"
    private const val KEY_SOURCES = "sources"
    private const val KEY_SOURCE_ID = "id"
    private const val KEY_SOURCE_PATH = "localPath"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ALBUM = "album"
    private const val KEY_LYRICS = "lyrics"
    private const val KEY_SEGMENTS = "segments"
    private const val KEY_SOURCE_START_US = "sourceStartUs"
    private const val KEY_SOURCE_END_US = "sourceEndUs"

    fun encode(job: ExportJob): String {
        val sources = JSONArray()
        job.sources.forEach { source ->
            val segments = JSONArray()
            source.segments.forEach { segment ->
                segments.put(
                    JSONObject()
                        .put(KEY_SOURCE_START_US, segment.sourceStartUs)
                        .put(KEY_SOURCE_END_US, segment.sourceEndUs),
                )
            }
            sources.put(
                JSONObject()
                    .put(KEY_SOURCE_ID, source.id)
                    .put(KEY_SOURCE_PATH, source.localPath)
                    .put(KEY_TITLE, source.title.orNull())
                    .put(KEY_ARTIST, source.artist.orNull())
                    .put(KEY_ALBUM, source.album.orNull())
                    .put(KEY_LYRICS, source.lyrics.orNull())
                    .put(KEY_SEGMENTS, segments),
            )
        }
        return JSONObject()
            .put(KEY_JOB_ID, job.jobId)
            .put(KEY_EDIT_PROJECT_ID, job.editProjectId)
            .put(KEY_OUTPUT_TEMP_PATH, job.outputTempPath)
            .put(KEY_GAIN_DB, job.gainDb.toDouble())
            .put(KEY_FADE_IN_MS, job.fadeInMs)
            .put(KEY_FADE_OUT_MS, job.fadeOutMs)
            .put(KEY_LYRIC_OFFSET_MS, job.lyricOffsetMs)
            .put(KEY_SOURCES, sources)
            .toString()
    }

    fun decode(json: String): ExportJob {
        val root = parseObject(json)
        val gainDb = root.double(KEY_GAIN_DB).toFloat()
        if (!gainDb.isFinite()) throw ExportJobFormatException("$KEY_GAIN_DB 必须是有限数值")
        val fadeInMs = root.nonNegativeLong(KEY_FADE_IN_MS)
        val fadeOutMs = root.nonNegativeLong(KEY_FADE_OUT_MS)
        val lyricOffsetMs = root.long(KEY_LYRIC_OFFSET_MS)
        val sources = root.array(KEY_SOURCES)
        if (sources.length() == 0) throw ExportJobFormatException("$KEY_SOURCES 不能为空")
        return ExportJob(
            jobId = root.string(KEY_JOB_ID).requireNotBlank(KEY_JOB_ID),
            editProjectId = root.string(KEY_EDIT_PROJECT_ID).requireNotBlank(KEY_EDIT_PROJECT_ID),
            outputTempPath = root.string(KEY_OUTPUT_TEMP_PATH).requireAbsolutePath(KEY_OUTPUT_TEMP_PATH),
            sources = (0 until sources.length()).map { index -> decodeSource(sources.objectAt(index), index) },
            gainDb = gainDb,
            fadeInMs = fadeInMs,
            fadeOutMs = fadeOutMs,
            lyricOffsetMs = lyricOffsetMs,
        )
    }

    private fun decodeSource(source: JSONObject, index: Int): ExportSource {
        val prefix = "$KEY_SOURCES[$index]"
        val segments = source.array(KEY_SEGMENTS)
        if (segments.length() == 0) throw ExportJobFormatException("$prefix.$KEY_SEGMENTS 不能为空")
        return ExportSource(
            id = source.string(KEY_SOURCE_ID).requireNotBlank("$prefix.$KEY_SOURCE_ID"),
            localPath = source.string(KEY_SOURCE_PATH).requireAbsolutePath("$prefix.$KEY_SOURCE_PATH"),
            title = source.optionalString(KEY_TITLE),
            artist = source.optionalString(KEY_ARTIST),
            album = source.optionalString(KEY_ALBUM),
            lyrics = source.optionalString(KEY_LYRICS),
            segments = (0 until segments.length()).map { segmentIndex ->
                val segment = segments.objectAt(segmentIndex)
                val startUs = segment.nonNegativeLong(KEY_SOURCE_START_US)
                val endUs = segment.nonNegativeLong(KEY_SOURCE_END_US)
                if (endUs <= startUs) {
                    throw ExportJobFormatException("$prefix.$KEY_SEGMENTS[$segmentIndex] 区间非法: $startUs..$endUs")
                }
                ExportSegment(startUs, endUs)
            },
        )
    }

    private fun String?.orNull(): Any = this ?: JSONObject.NULL

    private fun String.requireNotBlank(field: String): String {
        if (isBlank()) throw ExportJobFormatException("$field 不能为空")
        return this
    }

    private fun String.requireAbsolutePath(field: String): String {
        requireNotBlank(field)
        if (!startsWith("/")) throw ExportJobFormatException("$field 必须是绝对路径: $this")
        return this
    }

    private fun parseObject(json: String): JSONObject = try {
        JSONObject(json)
    } catch (error: JSONException) {
        throw ExportJobFormatException("job JSON 解析失败", error)
    }

    private fun JSONObject.value(key: String): Any {
        if (!has(key) || isNull(key)) throw ExportJobFormatException("缺少字段: $key")
        return get(key)
    }

    private fun JSONObject.string(key: String): String =
        value(key) as? String ?: throw ExportJobFormatException("字段类型错误, 需要字符串: $key")

    private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else string(key)

    private fun JSONObject.long(key: String): Long =
        (value(key) as? Number)?.toLong() ?: throw ExportJobFormatException("字段类型错误, 需要整数: $key")

    private fun JSONObject.nonNegativeLong(key: String): Long = long(key).also {
        if (it < 0L) throw ExportJobFormatException("字段不能为负数: $key")
    }

    private fun JSONObject.double(key: String): Double =
        (value(key) as? Number)?.toDouble() ?: throw ExportJobFormatException("字段类型错误, 需要数值: $key")

    private fun JSONObject.array(key: String): JSONArray =
        value(key) as? JSONArray ?: throw ExportJobFormatException("字段类型错误, 需要数组: $key")

    private fun JSONArray.objectAt(index: Int): JSONObject = try {
        getJSONObject(index)
    } catch (error: JSONException) {
        throw ExportJobFormatException("数组元素不是对象: [$index]", error)
    }
}
