package cn.music.audioworkshop.domain

import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.model.ResolvedTrack

interface MusicResolver {
    /**
     * 解析分享链接。
     *
     * @param source 用哪个解析源。null 表示用户未配置任何可用源 ——
     *   这不是错误状态以外的分支，而是「空壳」：调用方应先提示用户去设置页添加源。
     */
    suspend fun resolve(
        shareInput: String,
        source: ParseApiSource?,
    ): Result<ResolvedTrack>
}
