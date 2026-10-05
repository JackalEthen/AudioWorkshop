package cn.music.audioworkshop.domain.media

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import cn.music.audioworkshop.domain.model.ChoirPreset
import cn.music.audioworkshop.domain.model.DenoiseMode
import cn.music.audioworkshop.domain.model.EchoPreset
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.domain.model.JoinTransition

class ExportJobFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ExportJobCodec {

    /** LAME 支持的码率区间，界面上也只能选这个范围。 */
    const val MIN_BITRATE_KBPS = 32
    const val MAX_BITRATE_KBPS = 320

    /** MP3 标准里的采样率，格式转换页的下拉就是这一组。 */
    val SAMPLE_RATES = listOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)

    val BITRATES = listOf(64, 96, 128, 160, 192, 224, 256, 320)

    private const val KEY_JOB_ID = "jobId"
    private const val KEY_EDIT_PROJECT_ID = "editProjectId"
    private const val KEY_OUTPUT_TEMP_PATH = "outputTempPath"
    private const val KEY_GAIN_DB = "gainDb"
private const val KEY_PREVENT_CLIPPING = "preventClipping"
    private const val KEY_FORMAT = "format"
    private const val KEY_FADE_IN_MS = "fadeInMs"
    private const val KEY_FADE_OUT_MS = "fadeOutMs"
    private const val KEY_FADE_CURVE = "fadeCurve"
    private const val KEY_LYRIC_OFFSET_MS = "lyricOffsetMs"
    private const val KEY_JOIN_TRANSITION = "joinTransition"
    private const val KEY_TRANSITION_MS = "transitionMs"
    private const val KEY_NORMALIZE_SOURCES = "normalizeSources"
    private const val KEY_TRAILING_SILENCE_MS = "trailingSilenceMs"
    private const val KEY_SPEED = "speed"
    private const val KEY_SEMITONES = "semitones"
    private const val KEY_EQ_GAINS = "eqGainsDb"
    private const val KEY_DENOISE_MODE = "denoiseMode"
    private const val KEY_DENOISE_LOW_HZ = "denoiseLowHz"
    private const val KEY_DENOISE_HIGH_HZ = "denoiseHighHz"
    private const val KEY_DENOISE_STRENGTH = "denoiseStrength"
    private const val KEY_REVERB_MIX = "reverbMix"
    private const val KEY_REVERB_ROOM_SIZE = "reverbRoomSize"
    private const val KEY_REVERB_DECAY = "reverbDecay"
    private const val KEY_REVERB_PREDELAY_MS = "reverbPredelayMs"
    private const val KEY_REVERB_DAMPING = "reverbDamping"
    private const val KEY_ECHO_PRESET = "echoPreset"
    private const val KEY_CHOIR_PRESET = "choirPreset"
    private const val KEY_BITRATE_KBPS = "bitrateKbps"
    private const val KEY_SAMPLE_RATE_HZ = "sampleRateHz"
    private const val KEY_CHANNEL_MODE = "channelMode"
    private const val KEY_SOURCES = "sources"
    private const val KEY_SOURCE_ID = "id"
    private const val KEY_SOURCE_PATH = "localPath"
    private const val KEY_TITLE = "title"
    private const val KEY_ARTIST = "artist"
    private const val KEY_ALBUM = "album"
    private const val KEY_LYRICS = "lyrics"
    private const val KEY_LYRICS_OVERRIDE = "lyricsOverride"
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
                    .put(KEY_LYRICS_OVERRIDE, source.lyricsOverride.orNull())
                    .put(KEY_SEGMENTS, segments),
            )
        }
        return JSONObject()
            .put(KEY_JOB_ID, job.jobId)
            .put(KEY_EDIT_PROJECT_ID, job.editProjectId)
            .put(KEY_OUTPUT_TEMP_PATH, job.outputTempPath)
            .put(KEY_GAIN_DB, job.gainDb.toDouble())
            .put(KEY_PREVENT_CLIPPING, job.preventClipping)
            .put(KEY_FORMAT, job.format.name)
            .put(KEY_FADE_IN_MS, job.fadeInMs)
            .put(KEY_FADE_OUT_MS, job.fadeOutMs)
            .put(KEY_FADE_CURVE, job.fadeCurve.name)
            .put(KEY_LYRIC_OFFSET_MS, job.lyricOffsetMs)
            .put(KEY_JOIN_TRANSITION, job.joinTransition.name)
            .put(KEY_TRANSITION_MS, job.transitionMs)
            .put(KEY_NORMALIZE_SOURCES, job.normalizeSources)
            .put(KEY_TRAILING_SILENCE_MS, job.trailingSilenceMs)
            .put(KEY_SPEED, job.speed.toDouble())
            .put(KEY_SEMITONES, job.semitones.toDouble())
            .put(KEY_EQ_GAINS, job.eqGainsDb.toFloatArray())
            .put(KEY_DENOISE_MODE, job.denoiseMode.name)
            .put(KEY_DENOISE_LOW_HZ, job.denoiseLowHz)
            .put(KEY_DENOISE_HIGH_HZ, job.denoiseHighHz)
            .put(KEY_DENOISE_STRENGTH, job.denoiseStrength.toDouble())
            .put(KEY_REVERB_MIX, job.reverbMix.toDouble())
            .put(KEY_REVERB_ROOM_SIZE, job.reverbRoomSize.toDouble())
            .put(KEY_REVERB_DECAY, job.reverbDecay.toDouble())
            .put(KEY_REVERB_PREDELAY_MS, job.reverbPredelayMs.toDouble())
            .put(KEY_REVERB_DAMPING, job.reverbDamping.toDouble())
            .put(KEY_ECHO_PRESET, job.echoPreset?.name ?: JSONObject.NULL)
            .put(KEY_CHOIR_PRESET, job.choirPreset?.name ?: JSONObject.NULL)
            .put(KEY_BITRATE_KBPS, job.bitrateKbps)
            .put(KEY_SAMPLE_RATE_HZ, job.sampleRateHz)
            .put(KEY_CHANNEL_MODE, job.channelMode)
            .put(KEY_SOURCES, sources)
            .toString()
    }

    fun decode(json: String): ExportJob {
        val root = parseObject(json)
        val gainDb = root.double(KEY_GAIN_DB).toFloat()
        if (!gainDb.isFinite()) throw ExportJobFormatException("$KEY_GAIN_DB 必须是有限数值")
        // 老 job JSON 没有这个键，缺省 MP3，别用 value() 否则整批旧任务会反序列化失败
        val format = ExportFormat.fromName(root.optionalString(KEY_FORMAT))
        val fadeInMs = root.nonNegativeLong(KEY_FADE_IN_MS)
        val fadeOutMs = root.nonNegativeLong(KEY_FADE_OUT_MS)
        // 老 job JSON 没有这个键，缺省按线性处理，不要用 value() 否则整批旧任务会反序列化失败
        val fadeCurve = root.optionalString(KEY_FADE_CURVE)
            ?.let { name -> FadeCurve.entries.firstOrNull { it.name == name } }
            ?: FadeCurve.LINEAR
        val lyricOffsetMs = root.long(KEY_LYRIC_OFFSET_MS)
        // 下面四个是拼接选项，老 job JSON 里没有，缺省成「硬接、不格式化、不加空白」
        val joinTransition = root.optionalString(KEY_JOIN_TRANSITION)
            ?.let { name -> JoinTransition.entries.firstOrNull { it.name == name } }
            ?: JoinTransition.NORMAL
        val transitionMs = root.optionalLong(KEY_TRANSITION_MS) ?: 0L
        val normalizeSources = root.optionalBoolean(KEY_NORMALIZE_SOURCES) ?: false
        val trailingSilenceMs = root.optionalLong(KEY_TRAILING_SILENCE_MS) ?: 0L
        // 变速变调同样是后加的，老 job JSON 里没有就缺省成「原速原调」
        val speed = (root.optionalDouble(KEY_SPEED) ?: 1.0).toFloat()
        if (!speed.isFinite() || speed <= 0f) throw ExportJobFormatException("$KEY_SPEED 必须为正数: $speed")
        val semitones = (root.optionalDouble(KEY_SEMITONES) ?: 0.0).toFloat()
        if (!semitones.isFinite()) throw ExportJobFormatException("$KEY_SEMITONES 必须是有限数值")
        // 均衡和降噪同样可能不在老 JSON 里，缺省成「不处理」
        val eqGains = root.optJSONArray(KEY_EQ_GAINS)?.let { array ->
            (0 until array.length()).map { array.optDouble(it, 0.0).toFloat() }
        } ?: emptyList()
        val denoiseMode = root.optionalString(KEY_DENOISE_MODE)
            ?.let { name -> DenoiseMode.entries.firstOrNull { it.name == name } }
            ?: DenoiseMode.NONE
        val denoiseLowHz = root.optionalInt(KEY_DENOISE_LOW_HZ) ?: DenoiseMode.DEFAULT_LOW_HZ
        val denoiseHighHz = root.optionalInt(KEY_DENOISE_HIGH_HZ) ?: DenoiseMode.DEFAULT_HIGH_HZ
        val denoiseStrength = (root.optionalDouble(KEY_DENOISE_STRENGTH) ?: 1.0).toFloat()
        val reverbMix = (root.optionalDouble(KEY_REVERB_MIX) ?: 0.0).toFloat()
        val reverbRoomSize = (root.optionalDouble(KEY_REVERB_ROOM_SIZE) ?: 0.5).toFloat()
        val reverbDecay = (root.optionalDouble(KEY_REVERB_DECAY) ?: 0.6).toFloat()
        val reverbPredelayMs = (root.optionalDouble(KEY_REVERB_PREDELAY_MS) ?: 0.0).toFloat()
        val reverbDamping = (root.optionalDouble(KEY_REVERB_DAMPING) ?: 0.5).toFloat()
        val echoPreset = root.optionalString(KEY_ECHO_PRESET)
            ?.let { name -> EchoPreset.entries.firstOrNull { it.name == name } }
        val choirPreset = root.optionalString(KEY_CHOIR_PRESET)
            ?.let { name -> ChoirPreset.entries.firstOrNull { it.name == name } }
        // 转换参数同样是后加的，老 job JSON 里没有就全部缺省成「跟随源、不限码率」
        val bitrateKbps = root.optionalInt(KEY_BITRATE_KBPS) ?: 0
        if (bitrateKbps != 0 && bitrateKbps !in MIN_BITRATE_KBPS..MAX_BITRATE_KBPS) {
            throw ExportJobFormatException("$KEY_BITRATE_KBPS 超出范围: $bitrateKbps")
        }
        val sampleRateHz = root.optionalInt(KEY_SAMPLE_RATE_HZ) ?: 0
        if (sampleRateHz < 0) throw ExportJobFormatException("$KEY_SAMPLE_RATE_HZ 不能为负数: $sampleRateHz")
        val channelMode = root.optionalInt(KEY_CHANNEL_MODE) ?: 0
        if (channelMode !in 0..2) throw ExportJobFormatException("$KEY_CHANNEL_MODE 超出范围: $channelMode")
        // 老 job JSON 没有防炸音这个键，缺省关掉，保持原行为
        val preventClipping = root.optionalBoolean(KEY_PREVENT_CLIPPING) ?: false
        val sources = root.array(KEY_SOURCES)
        if (sources.length() == 0) throw ExportJobFormatException("$KEY_SOURCES 不能为空")
        return ExportJob(
            jobId = root.string(KEY_JOB_ID).requireNotBlank(KEY_JOB_ID),
            editProjectId = root.string(KEY_EDIT_PROJECT_ID).requireNotBlank(KEY_EDIT_PROJECT_ID),
            outputTempPath = root.string(KEY_OUTPUT_TEMP_PATH).requireAbsolutePath(KEY_OUTPUT_TEMP_PATH),
            sources = (0 until sources.length()).map { index -> decodeSource(sources.objectAt(index), index) },
            gainDb = gainDb,
            preventClipping = preventClipping,
            format = format,
            fadeInMs = fadeInMs,
            fadeOutMs = fadeOutMs,
            fadeCurve = fadeCurve,
            lyricOffsetMs = lyricOffsetMs,
            joinTransition = joinTransition,
            transitionMs = transitionMs,
            normalizeSources = normalizeSources,
            trailingSilenceMs = trailingSilenceMs,
            speed = speed,
            semitones = semitones,
            eqGainsDb = eqGains,
            denoiseMode = denoiseMode,
            denoiseLowHz = denoiseLowHz,
            denoiseHighHz = denoiseHighHz,
            denoiseStrength = denoiseStrength,
            reverbMix = reverbMix,
            reverbRoomSize = reverbRoomSize,
            reverbDecay = reverbDecay,
            reverbPredelayMs = reverbPredelayMs,
            reverbDamping = reverbDamping,
            echoPreset = echoPreset,
            choirPreset = choirPreset,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            channelMode = channelMode,
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
            lyricsOverride = source.optionalString(KEY_LYRICS_OVERRIDE),
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

    private fun JSONObject.optionalLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else (get(key) as? Number)?.toLong()

    private fun JSONObject.optionalBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else (get(key) as? Boolean)

    private fun JSONObject.optionalInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else (get(key) as? Number)?.toInt()

    private fun JSONObject.optionalDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else (get(key) as? Number)?.toDouble()

    private fun JSONObject.optJSONArray(key: String): JSONArray? =
        if (!has(key) || isNull(key)) null else optJSONArray(key)

    private fun List<Float>.toFloatArray(): JSONArray = JSONArray().also { array ->
        forEach { array.put(it.toDouble()) }
    }

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

