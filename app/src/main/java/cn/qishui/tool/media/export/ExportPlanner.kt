package cn.qishui.tool.media.export

import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportSource
import cn.qishui.tool.domain.model.EditTimeSegment

data class ExportSourceStep(
    val source: ExportSource,
    val outputStartUs: Long,
    val outputEndUs: Long,
    val segments: List<EditTimeSegment>,
    val fadeInUs: Long,
    val fadeOutUs: Long,
) {
    val outputDurationUs: Long
        get() = outputEndUs - outputStartUs
}

data class ExportPlan(
    val steps: List<ExportSourceStep>,
    val totalOutputUs: Long,
    val durationMs: Long,
)

object ExportPlanner {

    fun plan(job: ExportJob): ExportPlan {
        var cursorUs = 0L
        val lastIndex = job.sources.lastIndex
        val steps = job.sources.mapIndexed { index, source ->
            val outputStartUs = cursorUs
            var sourceCursorUs = outputStartUs
            val segments = source.segments.map { segment ->
                val outputEndUs = sourceCursorUs + (segment.sourceEndUs - segment.sourceStartUs)
                EditTimeSegment(
                    sourceStartUs = segment.sourceStartUs,
                    sourceEndUs = segment.sourceEndUs,
                    outputStartUs = sourceCursorUs,
                    outputEndUs = outputEndUs,
                ).also { sourceCursorUs = outputEndUs }
            }
            val step = ExportSourceStep(
                source = source,
                outputStartUs = outputStartUs,
                outputEndUs = sourceCursorUs,
                segments = segments,
                fadeInUs = if (index == 0) job.fadeInMs * MICROS_PER_MILLI else 0L,
                fadeOutUs = if (index == lastIndex) job.fadeOutMs * MICROS_PER_MILLI else 0L,
            )
            cursorUs = sourceCursorUs
            step
        }
        val totalOutputUs = cursorUs
        return ExportPlan(steps = steps, totalOutputUs = totalOutputUs, durationMs = totalOutputUs / MICROS_PER_MILLI)
    }

    private const val MICROS_PER_MILLI = 1_000L
}
