package cn.music.audioworkshop.data.parse

import cn.music.audioworkshop.domain.model.FieldMapping
import cn.music.audioworkshop.domain.model.MediaKind
import cn.music.audioworkshop.domain.model.FieldSlot
import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.model.ResolvedTrack
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 兜底搜索的深度与节点上限，防止畸形响应导致长时间卡顿。 */
private const val MAX_SEARCH_DEPTH = 5
private const val MAX_SEARCH_NODES = 400

/**
 * 通用解析响应匹配。
 *
 * 市面上的解析接口返回结构差异极大，探测过的几个就出现：
 * 成功标志有 `status:200` 也有 `code:200`；错误信息有 `msg` 也有 `message`；
 * 错误时 `data` 可能是 `null` 也可能是 `[]`；正文可能在 `data` 里，
 * 也可能在 `data.data`，甚至就在根对象上。
 *
 * 所以这里不写死路径，而是按 [FieldSlot] 的候选字段名逐个尝试，
 * 并递归下钻找到第一个「有实质内容」的对象当正文。
 */
class ParseResponseMatcher(private val mapping: FieldMapping = FieldMapping()) {

    /** 解析失败。抛这个而不是别的，UI 才能给出可操作的提示。 */
    class ParseFailure(message: String) : Exception(message)

fun match(body: String): ResolvedTrack {
        val root = try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw ParseFailure("接口返回的不是有效 JSON")
        }
        throwIfError(root)
        val payload = findPayload(root)
            ?: throw ParseFailure("返回结构里找不到内容（data 为空或缺少播放地址）")

        // 播放地址单独找，不能只认 payload 自己身上有。
        // B 站那样 title/author 在 data 上、地址在 data.dash.audio[0].baseUrl 的结构，
        // 只认 payload 会因为「向下找到的节点字段更少」而丢掉歌名。
        val audioUrl = payload.string(FieldSlot.AUDIO_URL, mapping)
            ?: findAudioUrlDeep(root)
            ?: throw ParseFailure("返回结构里没有播放地址字段")

        // format 字段常缺失，但 URL 后缀往往有扩展名 —— 视频/图片的判定
        // 和下载时的文件名都要靠它，所以缺失时从 URL 补一个。
        val declaredFormat = payload.string(FieldSlot.FORMAT, mapping)
        val resolvedFormat = declaredFormat ?: extensionOfUrl(audioUrl)
        val mediaKind = MediaKind.detect(resolvedFormat, audioUrl)

        // 图文源一次可能返回一组图，下载要一次全下，所以把同类地址都收起来。
        // 音频/视频源只有一个地址，这里返回单元素。
        val mediaUrls = if (mediaKind == MediaKind.IMAGE) {
            collectMediaUrls(root, audioUrl, mediaKind)
        } else {
            listOf(audioUrl)
        }

        return ResolvedTrack(
            title = payload.string(FieldSlot.TITLE, mapping),
            artist = payload.string(FieldSlot.ARTIST, mapping),
            audioUrl = audioUrl,
            format = resolvedFormat,
            codec = payload.string(FieldSlot.CODEC, mapping),
            quality = payload.string(FieldSlot.QUALITY, mapping),
            bitrateBps = payload.long(FieldSlot.BITRATE, mapping),
            sizeBytes = payload.parseSize(payload.string(FieldSlot.SIZE, mapping)),
            sampleRateHz = payload.long(FieldSlot.SAMPLE_RATE, mapping),
            lyrics = payload.string(FieldSlot.LYRICS, mapping),
            artistAvatarUrls = payload.stringList(FieldSlot.COVER, mapping),
            cacheExpiresAtEpochSeconds = root.anyLong(listOf("cache_expire_at", "cacheExpireAt", "expire_at")),
            sourceShareUrl = audioUrl,
            mediaKind = mediaKind,
            mediaUrls = mediaUrls,
        )
    }

    /**
     * 收集返回结构里所有属于 [kind] 的媒体地址，[primary] 排第一个。
     *
     * 复用 [findAudioUrlDeep] 的遍历方式（限深限节点），但收集全部而不是取第一个。
     * 按出现顺序去重：图文源给的是有序数组，顺序就是用户期望的下载顺序。
     */
    private fun collectMediaUrls(root: Any?, primary: String, kind: MediaKind): List<String> {
        val found = LinkedHashSet<String>()
        found += primary
        var frontier = listOf(root)
        var visited = 0
        repeat(MAX_SEARCH_DEPTH) {
            val next = mutableListOf<Any?>()
            for (node in frontier) {
                when (node) {
                    is JSONObject -> {
                        if (++visited > MAX_SEARCH_NODES) return found.toList()
                        node.keys().forEach { key -> collect(node.opt(key), found, next, kind) }
                    }
                    is JSONArray -> {
                        if (++visited > MAX_SEARCH_NODES) return found.toList()
                        for (index in 0 until minOf(node.length(), 50)) {
                            collect(node.opt(index), found, next, kind)
                        }
                    }
                }
            }
            frontier = next
            if (frontier.isEmpty()) return found.toList()
        }
        return found.toList()
    }

    /** 字符串叶子直接收，容器节点放进 [next] 等下一轮展开。 */
    private fun collect(value: Any?, found: MutableSet<String>, next: MutableList<Any?>, kind: MediaKind) {
        if (value is String) {
            if (isMediaUrl(value, kind)) found += value.trim()
        } else if (value != null && (value is JSONObject || value is JSONArray)) {
            next += value
        }
    }

    /** 是不是 [kind] 的 http(s) 媒体地址。 */
    private fun isMediaUrl(value: String, kind: MediaKind): Boolean {
        val text = value.trim()
        if (!text.startsWith("http://") && !text.startsWith("https://")) return false
        return MediaKind.detect(extensionOfUrl(text), text) == kind
    }

    /** 取 URL 的扩展名，剥掉查询参数和片段。 */
    private fun extensionOfUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.lastIndex) return null
        return path.substring(dot + 1).takeIf { it.length in 1..5 }
    }

    /**
     * 接口报错时抛出可读信息。
     *
     * 探测到的实例：`{"code":400,"msg":"缺少type参数…"}`、
     * `{"code":1002,"message":"无法从URL中提取歌曲ID"}`。两种都认。
     */
    private fun throwIfError(root: JSONObject) {
        val code = root.anyLong(listOf("code", "status", "statusCode", "status_code", "errno"))
        val message = root.stringOrNull(listOf("msg", "message", "errMsg", "err_msg", "error", "errmsg", "desc", "bktip"))
            ?: errorMessageFromNested(root)
        if (code != null && !isSuccessCode(code)) {
            throw ParseFailure("接口返回 code=$code${message?.let { "：$it" } ?: ""}")
        }
        if (code == null && message != null && !hasUsablePayload(root)) {
            throw ParseFailure("接口返回错误：$message")
        }
    }

    /** 200 / 0 / 1 都算成功，各家约定不一。 */
    private fun isSuccessCode(code: Long): Boolean = code == 200L || code == 0L || code == 1L || code == 20000L

    private fun hasUsablePayload(root: JSONObject): Boolean =
        (root.opt("data") as? JSONObject)?.optString("url").orEmpty().isNotBlank()

    private fun errorMessageFromNested(root: JSONObject): String? {
        val tip = root.opt("bktip") as? JSONObject ?: return null
        return tip.optString("tip").takeIf { it.isNotBlank() }
    }

/**
     * 找到承载正文的 JSON 对象。
     *
     * 依次尝试 `data`、`data.data`、`data.list[0]`、根对象自身 ——
     * 探测到的 163_music 是根对象直出，其余大多裹在 `data` 里。
     *
     * 多个候选都含播放地址时，选**命中字段最多**的那个：B 站那样
     * title/author 在 `data`、地址在 `data.dash.audio[0]`，
     * 谁更深不代表谁对，要挑信息全的那个。
     */
    private fun findPayload(root: JSONObject): JSONObject? {
        val candidates = mutableListOf<JSONObject>()
        val direct = root.opt("data")
        if (direct is JSONObject) {
            candidates += direct
            for (key in listOf("data", "info", "result", "song", "music", "item")) {
                (direct.opt(key) as? JSONObject)?.let { candidates += it }
            }
            for (key in listOf("list", "items", "songs", "data")) {
                (direct.opt(key) as? JSONArray)?.optJSONObject(0)?.let { candidates += it }
            }
        } else if (direct is JSONArray) {
            direct.optJSONObject(0)?.let { candidates += it }
        }
        candidates += root
        return candidates
            .filter { it.hasAnyContent(FieldSlot.AUDIO_URL, mapping) }
            .maxByOrNull { it.hitCount() }
            ?: candidates.maxByOrNull { it.hitCount() }
    }

    /** 该对象命中了多少个已知语义，用来在多个候选里挑信息最全的。 */
    private fun JSONObject.hitCount(): Int = FieldSlot.entries.count { it.candidatesFrom(mapping.valueOf(it)).any { key -> readString(key) != null } }

    /**
     * 兜底：在整棵 JSON 里搜播放地址。
     *
     * 固定路径覆盖不了所有形态 —— B 站返回的是 `data.dash.audio[0].baseUrl`，
     * 三层嵌套且在数组里。限深度 5、节点数 400，避免畸形响应卡住。
     */
    private fun findAudioUrlDeep(root: Any?): String? {
        var frontier = listOf(root)
        var visited = 0
        repeat(MAX_SEARCH_DEPTH) {
            val next = mutableListOf<Any?>()
            for (node in frontier) {
                when (node) {
                    is JSONObject -> {
                        if (++visited > MAX_SEARCH_NODES) return null
                        val hit = slotString(node, FieldSlot.AUDIO_URL, mapping)
                        if (hit != null) return hit
                        node.keys().forEach { key -> next += node.opt(key) }
                    }
                    is JSONArray -> {
                        if (++visited > MAX_SEARCH_NODES) return null
                        for (index in 0 until minOf(node.length(), 20)) next += node.opt(index)
                    }
                }
            }
            frontier = next
            if (frontier.isEmpty()) return null
        }
        return null
    }

    // ---- 取值 ----

private fun JSONObject.string(slot: FieldSlot, mapping: FieldMapping): String? =
        slotString(this, slot, mapping)

private fun slotString(json: JSONObject, slot: FieldSlot, mapping: FieldMapping): String? =
        slot.candidatesFrom(mapping.valueOf(slot)).firstNotNullOfOrNull { json.readString(it) }

    private fun JSONObject.stringList(slot: FieldSlot, mapping: FieldMapping): List<String> {
        val key = slot.candidatesFrom(mapping.valueOf(slot)).firstOrNull { readString(it) != null } ?: return emptyList()
        val value = opt(key)
        return when (value) {
            is String -> value.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
            is JSONArray -> buildList {
                repeat(value.length()) { index ->
                    value.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            else -> emptyList()
        }
    }

    private fun JSONObject.long(slot: FieldSlot, mapping: FieldMapping): Long? =
        slot.candidatesFrom(mapping.valueOf(slot)).firstNotNullOfOrNull { key ->
            if (!has(key) || isNull(key)) return@firstNotNullOfOrNull null
            opt(key)?.toString()?.trim()?.toLongOrNull()
                ?: opt(key)?.toString()?.trim()?.toDoubleOrNull()?.toLong()
        }

    private fun JSONObject.anyLong(keys: List<String>): Long? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            opt(key)?.toString()?.trim()?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun JSONObject.hasAnyContent(slot: FieldSlot, mapping: FieldMapping): Boolean =
        slot.candidatesFrom(mapping.valueOf(slot)).any { readString(it) != null }

    /** 只接受 http(s) 开头的非空字符串，避免把数字或对象误当成链接。 */
    private fun JSONObject.readString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val text = opt(key) as? String ?: return null
        val trimmed = text.trim()
        return trimmed.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.stringOrNull(keys: List<String>): String? {
        for (key in keys) {
            if (has(key) && !isNull(key) && opt(key) is String) {
                optString(key).trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private fun FieldMapping.valueOf(slot: FieldSlot): String = when (slot) {
        FieldSlot.TITLE -> title
        FieldSlot.ARTIST -> artist
        FieldSlot.ALBUM -> album
        FieldSlot.COVER -> cover
        FieldSlot.AUDIO_URL -> audioUrl
        FieldSlot.LYRICS -> lyrics
        FieldSlot.BITRATE -> bitrate
        FieldSlot.SIZE -> size
        FieldSlot.QUALITY -> quality
        FieldSlot.SAMPLE_RATE -> sampleRate
        FieldSlot.FORMAT -> format
        FieldSlot.CODEC -> codec
    }

    companion object {
        /**
         * `"0B"`、`"8.5 MB"`、`"1048576"` 这类都能解析。
         *
         * 探测到的 163_music 返回的是字符串 `"0B"`，直接 toLongOrNull 会得到 null。
         *
         * 单位判断必须先长后短：`"MB"` 里包含 `"B"`，先判 B 会把 8.5MB 算成 8 字节。
         */
        fun JSONObject.parseSize(raw: String?): Long? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            text.toLongOrNull()?.let { return it }
            val number = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
            val unit = text.uppercase()
            return when {
                "TB" in unit -> (number * 1024 * 1024 * 1024 * 1024).toLong()
                "GB" in unit -> (number * 1024 * 1024 * 1024).toLong()
                "MB" in unit -> (number * 1024 * 1024).toLong()
                "KB" in unit -> (number * 1024).toLong()
                else -> number.toLong()
            }
        }
    }
}


