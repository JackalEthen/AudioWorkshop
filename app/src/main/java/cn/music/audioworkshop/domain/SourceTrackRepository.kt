package cn.music.audioworkshop.domain

import cn.music.audioworkshop.domain.model.SourceTrack
import kotlinx.coroutines.flow.Flow

interface SourceTrackRepository {
    fun observeAll(): Flow<List<SourceTrack>>
    suspend fun getById(id: String): SourceTrack?
    suspend fun upsert(track: SourceTrack)

    /**
     * 导入本地音频并**写入库**，播放器列表会看到它。
     * 只给播放器的两个入口用：右上角加号导入、搜索页添加。
     */
    suspend fun importLocalAudio(uri: String): Result<SourceTrack>

    /**
     * 导入本地音频但**不写库**，只在本次会话里可用。
     *
     * 编辑页的格式转换、音乐信息、剪辑等功能走这条：编辑页的音频是一次性的，
     * 退出功能就没了，不该污染播放器的歌曲列表。
     *
     * 文件仍然会复制到应用私有目录（编辑链路只认本地路径），只是不入库。
     */
    suspend fun importLocalAudioEphemeral(uri: String): Result<SourceTrack>

    suspend fun delete(id: String)
}
