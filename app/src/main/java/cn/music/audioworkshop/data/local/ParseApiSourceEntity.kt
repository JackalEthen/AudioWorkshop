package cn.music.audioworkshop.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户手动添加的解析源。
 *
 * 不内置任何默认源 —— 没有记录时解析页就是空壳，由用户自行添加。
 * [urlFields] 等字段名映射可留空，留空则用 [cn.music.audioworkshop.domain.model.FieldSlot]
 * 的内置候选列表；只有字段名特别离奇的接口才需要手动指定。
 */
@Entity(tableName = "parse_api_sources")
data class ParseApiSourceEntity(
    @PrimaryKey val id: String,
    /** 展示名，用户自己起。 */
    val name: String,
    /** 接口地址，形如 `https://api.bugpk.com/api/163_music`。 */
    val url: String,
    /** 部分接口要求 `?type=json` 才返回结构化数据。留空表示不附加。 */
    val type_param: String,
    /** 需要鉴权时填。同时以 `key` 和 `apikey` 两个参数名附加。 */
    val api_key: String,
    val enabled: Boolean,
    /** 备注，仅用于设置页区分同名源。 */
    val note: String,
    // ---- 字段名映射，均为逗号分隔的候选键名，空串表示用内置候选 ----
    val field_title: String,
    val field_artist: String,
    val field_album: String,
    val field_cover: String,
    val field_audio_url: String,
    val field_lyrics: String,
    val field_bitrate: String,
    val field_size: String,
    val field_quality: String,
    val field_sample_rate: String,
    val field_format: String,
    val field_codec: String,
    /** 排序用，越小越靠前。 */
    val sort_order: Int,
    val created_at: Long,
)
