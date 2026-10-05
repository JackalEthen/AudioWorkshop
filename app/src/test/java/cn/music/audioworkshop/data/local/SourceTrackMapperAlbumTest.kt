package cn.music.audioworkshop.data.local

import cn.music.audioworkshop.domain.model.SourceOrigin
import cn.music.audioworkshop.domain.model.SourceTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceTrackMapperAlbumTest {

    @Test
    fun entityRoundTripKeepsAlbum() {
        val entity = track(album = "十一月的萧邦").toEntity(createdAt = 42L)
        assertEquals("十一月的萧邦", entity.album)
        assertEquals("十一月的萧邦", entity.toDomain().album)
    }

    @Test
    fun missingAlbumStaysNullThroughTheMapper() {
        val entity = track(album = null).toEntity(createdAt = 1L)
        assertNull(entity.album)
        assertNull(entity.toDomain().album)
    }

    @Test
    fun albumIsIndependentOfTitleAndArtist() {
        val domain = track(album = "范特西").toEntity(createdAt = 7L).toDomain()
        assertEquals("夜曲", domain.title)
        assertEquals("周杰伦", domain.artist)
        assertEquals("范特西", domain.album)
    }

    private fun track(album: String?) = SourceTrack(
        id = "local:/tmp/a.mp3",
        origin = SourceOrigin.LOCAL_IMPORT,
        sourceShareUrl = null,
        title = "夜曲",
        artist = "周杰伦",
        album = album,
        localPath = "/tmp/a.mp3",
        format = "mp3",
        durationMs = 1_000L,
        bitrateBps = 128_000L,
        sizeBytes = 2_048L,
        sampleRateHz = 44_100L,
        lyrics = null,
        fileHash = null,
        coverUri = null,
    )
}
