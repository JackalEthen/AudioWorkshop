package cn.music.audioworkshop.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 导入的 lx 自定义源。
 *
 * 可以导入多个，但**同一时刻只有一个生效** —— 选中态存在这里，
 * 唯一性由 [MusicSourceDao.selectOnly] 在事务里保证，不要靠 UI 拦。
 *
 * 脚本原文整份存下来（[script]），运行期不落盘副本，
 * 每次启用时用当前这份重新初始化引擎。
 */
@Entity(
    tableName = "music_sources",
    // 索引必须声明在这里，迁移里只负责建表。
    // 反过来（迁移里 CREATE INDEX 而实体不声明）Room 校验会失败，直接闪退。
    indices = [Index(value = ["created_at"])],
)
data class MusicSourceEntity(
    /** lx 脚本自带的 id，或用名称+版本兜底生成。 */
    @PrimaryKey val id: String,
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
    val created_at: Long,
    /** 最近一次初始化失败的原因，成功时为空。 */
    val last_error: String?,
)
