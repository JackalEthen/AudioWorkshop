package cn.music.audioworkshop.media.export

import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportSource
import cn.music.audioworkshop.domain.model.EditTimeSegment
import cn.music.audioworkshop.domain.model.FadeCurve
import cn.music.audioworkshop.domain.model.JoinTransition

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
    /** 变速倍率，1.0 = 原速。只改时长不改音高。 */
    val speed: Float = 1f,
    /** 变调半音数，0 = 原调。只改音高不改时长。 */
    val semitones: Float = 0f,
) {
    val outputDurationUs: Long
        get() = outputEndUs - outputStartUs

    /** 真正需要跑时间轴拉伸时才为 true —— 原速原调要跳过整条 native 链路。 */
    val needsSpeedPitch: Boolean
        get() = speed.needsSpeedPitch(semitones)
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
        // 变速只改时长不改音高，所以音频本体要除以 speed。
        // 斜坡和空白是用户给的「秒数」，听起来也是秒，不跟着变速缩 ——
        // 用户设了 3 秒淡入就是要 3 秒淡入，哪怕歌被拉快了 2 倍。
        val speed = job.speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        var cursorUs = 0L
        val lastIndex = job.sources.lastIndex
        val steps = job.sources.mapIndexed { index, source ->
            val outputStartUs = cursorUs
            var sourceCursorUs = outputStartUs
            val segments = source.segments.map { segment ->
                val sourceLengthUs = segment.sourceEndUs - segment.sourceStartUs
                val outputEndUs = sourceCursorUs + (sourceLengthUs / speed).toLong()
                EditTimeSegment(
                    sourceStartUs = segment.sourceStartUs,
                    sourceEndUs = segment.sourceEndUs,
                    outputStartUs = sourceCursorUs,
                    outputEndUs = outputEndUs,
                ).also { sourceCursorUs = outputEndUs }
            }
            val isFirst = index == 0
            val isLast = index == lastIndex
            // 空白有两处来源：用户在这一项后面手动插的空白，
            // 以及 PRESERVE 模式为了不裁样本而自动补的等长空白。两者相加。
            val manualGapUs = (job.perSourceGapMs.getOrNull(index) ?: 0L)
                .coerceAtLeast(0L) * MICROS_PER_MILLI
            val preserveGapUs = if (!isLast && job.joinTransition == JoinTransition.PRESERVE) transitionUs else 0L
            val gapAfterUs = manualGapUs + preserveGapUs
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
                // 流水线要按变速后的时间轴算淡入淡出，所以把倍率传下去
                speed = speed,
                semitones = job.semitones,
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

/**
 * 是否真的需要跑时间轴拉伸。
 *
 * 原速原调时整条 native 链路要跳过 —— 它是逐样本计算的，
 * 原速原调跑一遍纯属白费 CPU（一首三分钟的歌要几百毫秒）。
 */
fun Float.needsSpeedPitch(semitones: Float): Boolean =
    kotlin.math.abs(this - 1f) > 0.001f || kotlin.math.abs(semitones) > 0.01f
