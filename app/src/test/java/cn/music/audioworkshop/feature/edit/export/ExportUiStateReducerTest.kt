package cn.music.audioworkshop.feature.edit.export

import cn.music.audioworkshop.domain.media.ExportProgress
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportUiStateReducerTest {

    @Test
    fun idleStateIsIdleAndShowsExportLabel() {
        val state = ExportUiState()
        assertTrue(state.isIdle)
        assertNull(state.stage)
        assertEquals("开始导出", state.statusLabel)
    }

    @Test
    fun startedMovesToPreparing() {
        val state = reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET))
        assertTrue(state.isRunning)
        assertFalse(state.isCopying)
        assertEquals(ExportStage.PREPARING, state.stage)
        assertEquals("job-1", state.jobId)
        assertEquals(TARGET, state.fileName)
    }

    @Test
    fun progressUpdatesStageAndFraction() {
        val running = reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET))
        val state = reduceExport(
            running,
            ExportEvent.Progressed(ExportProgress("job-1", ExportStage.ENCODING, 0.42f)),
        )
        assertEquals(ExportStage.ENCODING, state.stage)
        assertEquals(0.42f, state.fraction, 0.0001f)
        assertTrue(state.isRunning)
    }

    @Test
    fun progressFromAnotherJobIsIgnored() {
        val running = reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET))
        val state = reduceExport(
            running,
            ExportEvent.Progressed(ExportProgress("job-2", ExportStage.TAGGING, 0.9f)),
        )
        assertEquals(running, state)
    }

    @Test
    fun progressWhileIdleIsIgnored() {
        val idle = ExportUiState()
        assertEquals(
            idle,
            reduceExport(idle, ExportEvent.Progressed(ExportProgress("job-1", ExportStage.ENCODING, 0.5f))),
        )
    }

    @Test
    fun completedSwitchesToCopyingStage() {
        val running = reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET))
        val state = reduceExport(running, ExportEvent.Completed(completed()))
        assertFalse(state.isRunning)
        assertTrue(state.isCopying)
        assertFalse(state.isIdle)
        assertEquals(1f, state.fraction, 0.0001f)
    }

    @Test
    fun succeededClosesTheRun() {
        val copying = reduceExport(
            reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET)),
            ExportEvent.Completed(completed()),
        )
        val state = reduceExport(copying, ExportEvent.Succeeded(2_048L))
        assertTrue(state.isIdle)
        assertTrue(state.isSuccess)
        assertNull(state.error)
        assertEquals("导出完成", state.statusLabel)
    }

    @Test
    fun failedStopsTheRunAndKeepsTheReason() {
        val running = reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET))
        val state = reduceExport(running, ExportEvent.Failed("编码失败"))
        assertTrue(state.isIdle)
        assertFalse(state.isSuccess)
        assertEquals("编码失败", state.error)
        assertEquals("编码失败", state.statusLabel)
    }

    @Test
    fun cancelledReturnsToIdleWithNotice() {
        val running = reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET))
        val state = reduceExport(running, ExportEvent.Cancelled)
        assertTrue(state.isIdle)
        assertFalse(state.isSuccess)
        assertEquals("导出已取消", state.error)
    }

    @Test
    fun resetClearsEveryField() {
        val dirty = ExportUiState(jobId = "job-1", error = "boom", isSuccess = true, fraction = 0.8f)
        assertEquals(ExportUiState(), reduceExport(dirty, ExportEvent.Reset))
    }

    @Test
    fun statusLabelReportsRunningStageAndProgress() {
        val running = reduceExport(ExportUiState(), ExportEvent.Started("job-1", TARGET))
        val state = reduceExport(
            running,
            ExportEvent.Progressed(ExportProgress("job-1", ExportStage.VALIDATING, 0.99f)),
        )
        assertEquals("校验中 99%", state.statusLabel)
    }

    private fun completed() = ExportResult.Completed(
        jobId = "job-1",
        outputPath = "/tmp/export.mp3",
        durationMs = 1_000L,
        sizeBytes = 2_048L,
    )

    private companion object {
        const val TARGET = "歌曲.mp3"
    }
}
