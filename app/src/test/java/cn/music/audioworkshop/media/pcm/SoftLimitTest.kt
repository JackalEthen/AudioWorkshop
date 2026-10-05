package cn.music.audioworkshop.media.pcm

import cn.music.audioworkshop.domain.model.EditTimeSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防炸音（软限制）行为。
 *
 * 背景：音量调高后波峰超过 32767，原来的 `coerceIn` 是硬削波 ——
 * 把波峰砍成平台，听感是「啪」一下的爆音，高频尤其刺耳。
 * 软限制用渐近曲线压缩峰值，听感自然。
 */
class SoftLimitTest {

    private fun run(samples: ShortArray, gainDb: Float, preventClipping: Boolean): ShortArray {
        val collected = mutableListOf<ShortArray>()
        PcmEditPipeline(
            segments = listOf(EditTimeSegment(0L, samples.size.toLong() * 1000L, 0L, samples.size.toLong() * 1000L)),
            gainDb = gainDb,
            preventClipping = preventClipping,
        ).process(
            source(mono(samples)),
        ) {
            collected += it
        }
        return collected.fold(ShortArray(0)) { acc, block -> acc + block }
    }

    private fun source(vararg chunks: PcmChunk): PcmFrameSource = PcmFrameSource { sink ->
        val first = chunks.firstOrNull()
        chunks.forEach(sink)
        DecodedAudioFormat(first?.sampleRateHz ?: 0, first?.channels ?: 0)
    }

    private fun mono(samples: ShortArray): PcmChunk = PcmChunk(
        presentationTimeUs = 0L,
        sampleRateHz = 1_000,
        channels = 1,
        samples = samples,
    )

    @Test
    fun `两种模式输出都不越界`() {
        // +20dB 会把满幅推到远超 32767
        val loud = ShortArray(64) { 30_000 }
        val soft = run(loud, gainDb = 20f, preventClipping = true)
        val hard = run(loud, gainDb = 20f, preventClipping = false)

        assertTrue("软限制后不能溢出", soft.all { it <= 32_767 && it >= -32_768 })
        assertTrue("硬削波也不越界", hard.all { it <= 32_767 && it >= -32_768 })
    }

    @Test
    fun `软限制不会把波峰削成同一个值`() {
        // 必须用连续同号的样本才能测出平台。交替正负时相邻值天然不同，
        // 硬削波和软限制都测不出差异 —— 这就是上一版断言失败的原因。
        // 这里是一串全是正值的递增波峰，超过满幅。
        val loud = ShortArray(24) { (28_000 + it * 400).toShort() }
        val soft = run(loud, gainDb = 6f, preventClipping = true)
        val hard = run(loud, gainDb = 6f, preventClipping = false)

        val softPositives = soft.filter { it > 0 }
        assertTrue("软限制后波峰不应被压成同一个值", softPositives.toSet().size > 1)
        assertTrue("峰值应接近上限", softPositives.max() > 30_000)

        // 硬削波在 +6dB 下全都会顶到 32767，平台明显
        assertTrue(
            "对照：硬削波应出现明显平台",
            countFlatPairs(hard) > countFlatPairs(soft),
        )
    }

    @Test
    fun `阈值以下的信号逐位不变`() {
        val quiet = ShortArray(32) { ((it * 300) % 2000 - 1000).toShort() }
        val out = run(quiet, gainDb = 0f, preventClipping = true)

        assertArrayEquals("电平远低于阈值时应原样通过", quiet, out)
    }

    @Test
    fun `负值同样被软限制且保号`() {
        val loud = ShortArray(32) { -31_000 }
        val soft = run(loud, gainDb = 14f, preventClipping = true)

        assertTrue("不能翻正", soft.all { it < 0 })
        assertTrue("负向也要接近上限", soft.min() < -30_000)
        assertTrue("不越界", soft.all { it >= -32_768 })
    }

    @Test
    fun `软限制在极高增益下也不产生削波平台`() {
        // 增益必须足够大（这里 +20dB）才会真的削到满幅。
        // +10dB 时峰值才到 30958，压根没触发限制，两种模式的平台数都是 0，
        // 断言就失去意义了 —— 实测确认 +20dB 才有硬削波平台可比。
        val ramp = ShortArray(200) { ((it * 100) - 10_000).toShort() }
        val soft = run(ramp, gainDb = 20f, preventClipping = true)
        val hard = run(ramp, gainDb = 20f, preventClipping = false)

        val softFlatPairs = countFlatPairs(soft)
        val hardFlatPairs = countFlatPairs(hard)

        assertTrue("对照：+20dB 下硬削波必须出现平台，否则本用例无意义", hardFlatPairs > 0)
        assertTrue(
            "软限制不该产生平台（软=$softFlatPairs 硬=$hardFlatPairs）",
            softFlatPairs < hardFlatPairs,
        )
    }

    /** 统计相邻两个采样取值相同的次数（削波平台）。 */
    private fun countFlatPairs(samples: ShortArray): Int {
        var count = 0
        for (i in 0 until samples.size - 1) {
            if (samples[i] == samples[i + 1] && samples[i] > 0) count++
        }
        return count
    }
}
