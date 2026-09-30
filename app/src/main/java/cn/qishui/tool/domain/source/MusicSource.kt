package cn.qishui.tool.domain.source

/**
 * 一个已导入的 lx 音源。
 *
 * 可以导入多个，但同一时刻只有一个 [selected] 为 true ——
 * 唯一性由数据层保证（选中时事务内先清其余行），不要靠 UI 拦。
 */
data class MusicSource(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val homepage: String,
    /** 脚本原文。 */
    val script: String,
    /** 从链接导入还是从文件导入，只作展示。 */
    val origin: String,
    val selected: Boolean,
    /** 最近一次初始化失败原因，成功时为空。 */
    val lastError: String?,
) {
    companion object {
        const val ORIGIN_URL = "链接"
        const val ORIGIN_FILE = "文件"
    }
}

/** 从脚本头部注释解析出来的元信息。 */
data class MusicSourceMeta(
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val homepage: String,
) {
    companion object {
        /**
         * lx 音源脚本在头部注释里声明自己，格式固定：
         *
         * ```
         * @name 某某源
         * @version 1.0.0
         * @author someone
         * @description 说明
         * @homepage https://...
         * ```
         *
         * 解析不出来就用文件名兜底，不能因为缺注释就不给导入。
         */
        fun parse(script: String, fallbackName: String): MusicSourceMeta {
            fun field(tag: String): String? {
                val pattern = Regex("""@$tag\s+(.+)""")
                return pattern.find(script)
                    ?.groupValues?.get(1)
                    ?.trim()
                    ?.trim('"', '\'')
                    ?.takeIf { it.isNotBlank() }
            }
            val name = field("name") ?: fallbackName
            return MusicSourceMeta(
                name = name,
                description = field("description").orEmpty(),
                author = field("author").orEmpty(),
                version = field("version").orEmpty(),
                homepage = field("homepage").orEmpty(),
            )
        }

        /** 同一份脚本重复导入时用它做主键。 */
        fun stableId(meta: MusicSourceMeta, script: String): String {
            val seed = "${meta.name}|${meta.version}|${script.length}"
            return absHash(seed).toString(16).padStart(8, '0')
        }

        private fun absHash(text: String): Int {
            var hash = 0
            text.forEach { hash = 31 * hash + it.code }
            return hash and 0x7fffffff
        }
    }
}
