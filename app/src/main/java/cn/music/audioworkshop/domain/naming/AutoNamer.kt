package cn.music.audioworkshop.domain.naming

import cn.music.audioworkshop.domain.model.DEFAULT_AUTO_NAME_PATTERN

data class NamingInput(
    val title: String?,
    val artist: String?,
    val album: String?,
    val date: String,
)

sealed interface NamingResult {
    val baseName: String?

    data class Success(override val baseName: String) : NamingResult

    data class Failure(val message: String) : NamingResult {
        override val baseName: String? = null
    }
}

object AutoNamer {
    const val REGEX_PREFIX = "re:"

    fun render(pattern: String, input: NamingInput): NamingResult {
        val trimmed = pattern.trim()
        if (trimmed.startsWith(REGEX_PREFIX, ignoreCase = true)) {
            return renderWithRegex(trimmed.substring(REGEX_PREFIX.length).trim(), input)
        }
        val expanded = expandTokens(trimmed, input)
            ?: return NamingResult.Failure(unknownTokenMessage(trimmed))
        return NamingResult.Success(sanitize(expanded, input))
    }

    private fun renderWithRegex(body: String, input: NamingInput): NamingResult {
        if (body.isEmpty()) return NamingResult.Failure("正则表达式不能为空")
        val breakIndex = body.indexOfFirst(Char::isWhitespace)
        val regexSource = if (breakIndex < 0) body else body.substring(0, breakIndex)
        val replacementTemplate = if (breakIndex < 0) {
            null
        } else {
            body.substring(breakIndex).trim().takeIf(String::isNotEmpty)
        }
        val regex = runCatching { Regex(regexSource) }.getOrElse { failure ->
            return NamingResult.Failure("正则表达式无效：${failure.message ?: "无法解析"}")
        }
        val rendered = expandTokens(DEFAULT_AUTO_NAME_PATTERN, input).orEmpty()
        val match = regex.find(rendered) ?: return NamingResult.Failure("正则未匹配到歌名")
        val groups = match.groupValues.drop(1)
        val named = if (replacementTemplate != null) {
            applyGroups(replacementTemplate, groups)
        } else {
            groups.filter(String::isNotEmpty).ifEmpty { listOf(match.value) }.joinToString("-")
        }
        if (named == null) return NamingResult.Failure("引用了不存在的捕获组")
        return NamingResult.Success(sanitize(named, input))
    }

    private fun applyGroups(template: String, groups: List<String>): String? {
        val result = StringBuilder()
        var index = 0
        while (index < template.length) {
            val char = template[index]
            if (char != '{') {
                result.append(char)
                index++
                continue
            }
            val close = template.indexOf('}', index + 1)
            if (close < 0) {
                result.append(char)
                index++
                continue
            }
            val groupIndex = template.substring(index + 1, close).trim().toIntOrNull()
            val value = groupIndex
                ?.takeIf { it in 1..groups.size }
                ?.let { groups[it - 1] }
                ?: return null
            result.append(value)
            index = close + 1
        }
        return result.toString()
    }

    private fun expandTokens(template: String, input: NamingInput): String? {
        val result = StringBuilder()
        var index = 0
        while (index < template.length) {
            val char = template[index]
            if (char != '{') {
                result.append(char)
                index++
                continue
            }
            val close = template.indexOf('}', index + 1)
            if (close < 0) {
                result.append(char)
                index++
                continue
            }
            val value = tokenValue(template.substring(index + 1, close), input) ?: return null
            result.append(value)
            index = close + 1
        }
        return result.toString()
    }

    private fun tokenValue(token: String, input: NamingInput): String? = when (token.trim()) {
        "歌名" -> input.title?.takeIf(String::isNotBlank) ?: "未知歌曲"
        "歌手" -> input.artist?.takeIf(String::isNotBlank) ?: "未知歌手"
        "专辑" -> input.album?.takeIf(String::isNotBlank) ?: "未知专辑"
        "日期" -> input.date.takeIf(String::isNotBlank) ?: "未知日期"
        else -> null
    }

    private fun unknownTokenMessage(pattern: String): String {
        val token = TokenPattern.findAll(pattern)
            .map { it.groupValues.get(1).trim() }
            .firstOrNull { it.isNotEmpty() && it !in SupportedTokens }
            .orEmpty()
        return if (token.isEmpty()) "格式包含无法识别的占位符" else "未知占位符：{$token}"
    }

    private fun sanitize(value: String, input: NamingInput): String {
        val cleaned = value
            .replace(IllegalCharacters, "_")
            .replace(RepeatedSeparators) { match ->
                if (match.value.first() == '_') "_" else "-"
            }
            .trim()
            .trim('.', ' ', '-', '_')
            .take(MAX_BASE_NAME_LENGTH)
            .trim()
        return cleaned.ifEmpty { tokenValue("歌名", input).orEmpty().ifEmpty { FALLBACK_BASE_NAME } }
    }

    private val TokenPattern = Regex("""\{([^{}]*)\}""")
    private val SupportedTokens = setOf("歌名", "歌手", "专辑", "日期")
    private val IllegalCharacters = Regex("""[<>:"/\\|?*\u0000-\u001F\u007F]""")
    private val RepeatedSeparators = Regex("""[-_\s]{2,}""")
    private const val MAX_BASE_NAME_LENGTH = 120
    private const val FALLBACK_BASE_NAME = "未命名"
}
