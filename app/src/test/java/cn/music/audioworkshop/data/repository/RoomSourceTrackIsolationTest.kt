package cn.music.audioworkshop.data.repository

import android.net.Uri
import cn.music.audioworkshop.data.local.SourceTrackDao
import cn.music.audioworkshop.data.local.SourceTrackEntity
import cn.music.audioworkshop.data.media.AudioImporter
import cn.music.audioworkshop.domain.model.SourceOrigin
import cn.music.audioworkshop.domain.model.SourceTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页与播放器页的导入隔离。
 *
 * 回归重点：编辑页导入的音频**不能**出现在播放器列表。
 * 之前两边共用 importLocalAudio()，都往 source_tracks 写，source_code 与入库
 * 路径完全一致，播放器分不出是哪来的 —— 编辑页导入的歌全跑进了播放列表。
 */
class RoomSourceTrackIsolationTest {

    private class FakeDao : SourceTrackDao {
        val rows = MutableStateFlow<List<SourceTrackEntity>>(emptyList())
        var insertCount = 0
            private set

        override fun observeAll(): Flow<List<SourceTrackEntity>> = rows

        override suspend fun getById(id: String): SourceTrackEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun insert(track: SourceTrackEntity) {
            insertCount++
            rows.value = rows.value.filterNot { it.id == track.id } + track
        }

        override suspend fun update(track: SourceTrackEntity) = insert(track)

        override suspend fun deleteById(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private class FakeImporter(private val track: SourceTrack) : AudioImporter {
        override suspend fun import(uriString: String): Result<SourceTrack> = Result.success(track)
    }

    private fun track(id: String) = SourceTrack(
        id = id,
        origin = SourceOrigin.LOCAL_IMPORT,
        sourceShareUrl = null,
        title = "song-$id",
        artist = "artist",
        album = null,
        localPath = "/data/imports/$id.mp3",
        format = "mp3",
        durationMs = 1000L,
        bitrateBps = null,
        sizeBytes = 100L,
        sampleRateHz = 44100L,
        lyrics = null,
        fileHash = null,
        coverUri = null,
        sourceCode = "local",
        platformSongId = null,
    )

    @Test
    fun `player entry imports into the library`() = runBlocking {
        val dao = FakeDao()
        val repository = RoomSourceTrackRepository(dao, FakeImporter(track("p1")))

        val imported = repository.importLocalAudio("/data/local/1.mp3").getOrThrow()

        assertEquals("p1", imported.id)
        assertEquals(1, dao.insertCount)
        assertEquals(listOf("p1"), repository.observeAll().first().map { it.id })
    }

    @Test
    fun `editor entry stays out of the player library`() = runBlocking {
        val dao = FakeDao()
        val repository = RoomSourceTrackRepository(dao, FakeImporter(track("e1")))

        val imported = repository.importLocalAudioEphemeral("/data/local/2.mp3").getOrThrow()

        assertEquals("import itself must succeed so the editor gets a file", "e1", imported.id)
        assertEquals("regression: no insert at all", 0, dao.insertCount)
        assertTrue(
            "regression: editor import must not show up in the player list",
            repository.observeAll().first().isEmpty(),
        )
    }

    @Test
    fun `both entries coexist without cross contamination`() = runBlocking {
        val dao = FakeDao()
        val player = RoomSourceTrackRepository(dao, FakeImporter(track("p1")))
        val editor = RoomSourceTrackRepository(dao, FakeImporter(track("e1")))

        editor.importLocalAudioEphemeral("/data/local/e.mp3")
        player.importLocalAudio("/data/local/p.mp3")

        assertEquals("only the player import is in the library", listOf("p1"), player.observeAll().first().map { it.id })
    }

    @Test
    fun `ephemeral import does not delete existing rows`() = runBlocking {
        val dao = FakeDao()
        val repository = RoomSourceTrackRepository(dao, FakeImporter(track("e1")))

        // 先用写入型入口插一首
        repository.importLocalAudio("/data/local/p.mp3")
        // 换成编辑侧的 track，再导一次
        val editor = RoomSourceTrackRepository(dao, FakeImporter(track("e1")))
        editor.importLocalAudioEphemeral("/data/local/e.mp3")

        assertEquals(
            "编辑侧导入既不该写库也不该动已有行",
            1,
            repository.observeAll().first().size,
        )
        assertEquals("只有播放器那一次 insert", 1, dao.insertCount)
    }
}
