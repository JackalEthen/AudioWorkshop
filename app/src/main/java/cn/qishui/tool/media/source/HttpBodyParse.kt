package cn.qishui.tool.media.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * 代脚本发 HTTP 时，响应体怎么变成脚本看到的东西。
 *
 * 对齐 lx-music-mobile 的 `src/core/init/userApi/request.js`：
 * ```js
 * try { resp.body = JSON.parse(resp.body) } catch {}
 * ```
 *
 * **这是整条音源链路最容易漏的一条。** 脚本普遍直接写 `r.body.code`、
 * `r.body.data.url`，body 要是给了字符串，这些字段全是 `undefined`，
 * 于是音源初始化就失败 —— 表现像「源坏了」，实际是宿主回包形状不对。
 */
internal object HttpBodyParse {

    fun parse(text: String): Any {
        val trimmed = text.trim()
        return try {
            when {
                trimmed.isEmpty() -> JSONObject.NULL
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed == "true" -> true
                trimmed == "false" -> false
                trimmed == "null" -> JSONObject.NULL
                trimmed.toDoubleOrNull() != null -> trimmed.toDouble()
                // 纯文本 / HTML / 二进制乱码，原样给，至少不炸
                else -> text
            }
        } catch (error: Exception) {
            // 截断的 JSON 在真实网络里很常见，parseBody 抛了也不能连累整个回包
            text
        }
    }
}
