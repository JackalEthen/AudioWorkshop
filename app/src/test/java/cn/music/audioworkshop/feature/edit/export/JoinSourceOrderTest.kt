package cn.music.audioworkshop.feature.edit.export

import cn.music.audioworkshop.domain.model.SourceOrigin
import cn.music.audioworkshop.domain.model.SourceTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class JoinSourceOrderTest {

    @Test
    fun selectionOrderIsPreserved() {
        assertEquals(
            listOf("c", "a", "b"),
            JoinSourceOrder.order(listOf(track("c"), track("a"), track("b"))).map { it.id },
        )
    }

    @Test
    fun duplicatedIdsAreCollapsedKeepingFirstPosition() {
        assertEquals(
            listOf("a", "b"),
            JoinSourceOrder.order(listOf(track("a"), track("b"), track("a"))).map { it.id },
        )
    }

    @Test
    fun selectionIsCappedAtMaxSources() {
        val preselected = (1..12).map { track("t$it") }
        assertEquals(
            preselected.take(MAX_JOIN_SOURCES).map { it.id },
            JoinSourceOrder.order(preselected).map { it.id },
        )
    }

    @Test
    fun emptySelectionProducesNoSources() {
        assertEquals(emptyList<SourceTrack>(), JoinSourceOrder.order(emptyList()))
    }

    private fun track(id: String): SourceTrack = SourceTrack(
        id = id,
        origin = SourceOrigin.API,
        sourceShareUrl = null,
        title = "标题-$id",
        artist = "歌手-$id",
        album = null,
        localPath = "/tmp/$id.mp3",
        format = "mp3",
        durationMs = 1_000L,
        bitrateBps = 128_000L,
        sizeBytes = 1_024L,
        sampleRateHz = 44_100L,
        lyrics = null,
        fileHash = null,
        coverUri = null,
    )
}
