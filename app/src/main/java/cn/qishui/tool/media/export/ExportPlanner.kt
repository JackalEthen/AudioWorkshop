package cn.qishui.tool.media.export

import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportSource
import cn.qishui.tool.domain.model.EditTimeSegment
import cn.qishui.tool.domain.model.FadeCurve
import cn.qishui.tool.domain.model.JoinTransition

data class ExportSourceStep(
    val source: ExportSource,
    val outputStartUs: Long,
    val outputEndUs: Long,
    val segments: List<EditTimeSegment>,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val fadeCurve: FadeCurve,
    /** 无损衔接时这一步后面要补的静音。 */
    val gapAfterUs: Long = 0L,
) {
    val outputDurationUs: Long
        get() = outputEndUs - outputStartUs
}

data class ExportPlan(
    val steps: List<ExportSourceStep>,
    val totalOutputUs: Long,
    val durationMs: Long,
    /** 末尾追加的空白。 */
    val trailingSilenceUs: Long = 0L,
)

object ExportPlanner {

    fun plan(job: ExportJob): ExportPlan {
        val transitionUs = job.transitionMs.coerceAtLeast(0L) * MICROS_PER_MILLI
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
            val isFirst = index == 0
            val isLast = index == lastIndex
            val gapAfterUs = if (!isLast && job.joinTransition == JoinTransition.PRESERVE) transitionUs else 0L
            val step = ExportSourceStep(
                source = source,
                outputStartUs = outputStartUs,
                outputEndUs = sourceCursorUs,
                segments = segments,
                // 每个接缝都补一对斜坡：前一首淡出、后一首淡入，总时长不变
                fadeInUs = when {
                    isFirst -> job.fadeInMs * MICROS_PER_MILLI
                    job.joinTransition.ramps -> transitionUs
                    else -> 0L
                },
                fadeOutUs = when {
                    isLast -> job.fadeOutMs * MICROS_PER_MILLI
                    job.joinTransition.ramps -> transitionUs
                    else -> 0L
                },
                // 选了对接方式就整条拼接都用它的曲线：两首拼接时首尾两条既是 first 又是 last，
                // 没法按位置区分「自己的淡入淡出」和「接缝斜坡」，统一取接缝的更符合预期。
                fadeCurve = if (job.joinTransition.ramps) job.joinTransition.curve else job.fadeCurve,
                gapAfterUs = gapAfterUs,
            )
            cursorUs = sourceCursorUs + gapAfterUs
            step
        }
        val trailingSilenceUs = job.trailingSilenceMs.coerceAtLeast(0L) * MICROS_PER_MILLI
        val totalOutputUs = cursorUs + trailingSilenceUs
        return ExportPlan(
            steps = steps,
            totalOutputUs = totalOutputUs,
            durationMs = totalOutputUs / MICROS_PER_MILLI,
            trailingSilenceUs = trailingSilenceUs,
        )
    }

    private const val MICROS_PER_MILLI = 1_000L
}
