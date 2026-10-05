package cn.music.audioworkshop.domain

import cn.music.audioworkshop.domain.model.ChoirPreset
import cn.music.audioworkshop.domain.model.EchoPreset
import cn.music.audioworkshop.media.effect.PcmBuffer
import cn.music.audioworkshop.media.effect.PcmEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修复强度必须真的改变处理范围。
 *
 * 只测「传进去不报错」没用 —— 那样三档强度听起来一样也测得过去。
 *
 * 注意 [PcmEffects.repair] 是**原地修改**：它返回的就是传进去的那个 buffer。
 * 所以每次比较都得重新造一份输入，不能拿结果和自己比。
 */
class RepairStrengthTest {

    /** 造一段带爆破音（削波平台）和咔嗒声的信号。 */
    private fun noisyBuffer(): PcmBuffer {
        val rate = 44100
        val frames = rate // 1 秒
        val samples = ShortArray(frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            // 底噪 + 一个持续的正弦
            val tone = kotlin.math.sin(2.0 * Math.PI * 220.0 * t) * 6000.0
            samples[i] = (tone + (Math.random() - 0.5) * 400).toInt().toShort()
        }
        // 削波：把峰值直接钉在满量程上，形成平台
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            if (kotlin.math.sin(2.0 * Math.PI * 220.0 * t) > 0.98) {
                samples[i] = Short.MAX_VALUE
            }
        }
        // 咔嗒声：几处瞬时跳变
        listOf(4000, 12000, 20000, 33000).forEach { index ->
            if (index < frames) samples[index] = Short.MIN_VALUE
        }
        return PcmBuffer(sampleRateHz = rate, channels = 1, samples = samples)
    }

    /** 相邻样本的跳变总量 —— 咔嗒声和爆破音都会让它变大。 */
    private fun totalJitter(buffer: PcmBuffer): Long {
        var sum = 0L
        for (i in 1 until buffer.samples.size) {
            val delta = kotlin.math.abs(buffer.samples[i].toInt() - buffer.samples[i - 1].toInt())
            sum += delta.toLong()
        }
        return sum
    }

    /** 造一份输入，跑到指定强度，返回跳变量。 */
    private fun jitterAfterRepair(strength: Float): Long =
        totalJitter(PcmEffects.repair(noisyBuffer(), strength))

    @Test
    fun `轻度修复比不修复干净`() {
        val baseline = totalJitter(noisyBuffer())
        assertTrue(
            "轻度修复应降低瞬时跳变: baseline=$baseline repaired=${jitterAfterRepair(0.3f)}",
            jitterAfterRepair(0.3f) < baseline,
        )
    }

    @Test
    fun `强度越高处理范围越大`() {
        val light = jitterAfterRepair(0.3f)
        val standard = jitterAfterRepair(0.6f)
        val heavy = jitterAfterRepair(0.95f)
        // 阈值越宽松 → 判定为瑕疵的样本越多 → 剩余跳变越少
        assertTrue("标准应比轻度更干净: light=$light standard=$standard", standard < light)
        assertTrue("重度应比标准更干净: standard=$standard heavy=$heavy", heavy < standard)
    }

    @Test
    fun `零强度等于不处理`() {
        val input = noisyBuffer()
        val before = totalJitter(input)
        PcmEffects.repair(input, 0f)
        assertEquals("强度 0 不应改动任何样本", before, totalJitter(input))
    }
}

/**
 * 回声和合唱的预设必须真的有效果，不能调了个寂寞。
 *
 * 注意 [PcmEffects.echo] 是**原地修改**：返回的就是传进去的那个 buffer。
 * 所以每次比较都得重新造一份输入，否则「输出减输入」永远是 0。
 */
class EffectPresetStrengthTest {

    private fun tone(seconds: Int = 1, rate: Int = 44100): PcmBuffer {
        val samples = ShortArray(rate * seconds)
        for (i in samples.indices) {
            val t = i.toDouble() / rate
            samples[i] = (kotlin.math.sin(2.0 * Math.PI * 440.0 * t) * 8000.0).toInt().toShort()
        }
        return PcmBuffer(sampleRateHz = rate, channels = 1, samples = samples)
    }

    /**
     * 输出与输入的差异量 —— 也就是「这个效果往信号里加了多少东西」。
     *
     * 不能用尾部的绝对能量：输入本身就是持续正弦，尾部能量本来就高，
     * 回声加的那点量淹没在里面，三个档位测出来会一模一样。
     */
    private fun addedEnergy(input: PcmBuffer, output: PcmBuffer): Long {
        var sum = 0L
        for (i in input.samples.indices) {
            val delta = output.samples[i].toLong() - input.samples[i].toLong()
            sum += delta * delta
        }
        return sum
    }

    /**
 * 每个预设各自造一份输入，跑完立刻量差异。
 *
 * [PcmEffects.echo] 原地改并返回同一个 buffer，
 * [PcmEffects.choir] 返回**新** buffer 不动输入 —— 两个函数的契约不一样，
 * 所以一个比 original vs input，另一个比 original vs 返回值。
 */
private fun echoAddedEnergy(preset: EchoPreset): Long {
        val input = tone(seconds = 2)
        val original = PcmBuffer(input.sampleRateHz, input.channels, input.samples.copyOf())
        PcmEffects.echo(input, preset.delayMs, preset.feedback, preset.mix, preset.kind)
        return addedEnergy(original, input)
    }

    private fun choirAddedEnergy(preset: ChoirPreset): Long {
        val input = tone(seconds = 2)
        val original = PcmBuffer(input.sampleRateHz, input.channels, input.samples.copyOf())
        val out = PcmEffects.choir(input, preset.spreadMs, preset.mix, preset.kind)
        return addedEnergy(original, out)
    }

    @Test
    fun `回声三档都能听出来`() {
        EchoPreset.entries.forEach { preset ->
            assertTrue("${preset.label} 应真的改动信号", echoAddedEnergy(preset) > 0)
        }
    }

    @Test
    fun `合唱三档都能听出来`() {
        ChoirPreset.entries.forEach { preset ->
            assertTrue("${preset.label} 应真的改动信号", choirAddedEnergy(preset) > 0)
        }
    }

    /**
     * 三档都比「增强前」更强 —— 这是用户提的需求本身。
     *
     * 基线是用户反馈「都不不是很强」时的取值：回声
     * 180/0.25/0.28、320/0.45/0.35、480/0.6/0.4；合唱
     * 12/0.45、18/0.5、30/0.55。
     */
    @Test
    fun `回声三档参数都比增强前更高`() {
        assertTrue("房间混响量", EchoPreset.ROOM.mix > 0.28f)
        assertTrue("房间反馈率", EchoPreset.ROOM.feedback > 0.25f)
        assertTrue("双重回声混响量", EchoPreset.DOUBLE.mix > 0.35f)
        assertTrue("双重回声反馈率", EchoPreset.DOUBLE.feedback > 0.45f)
        assertTrue("山谷回声混响量", EchoPreset.VALLEY.mix > 0.4f)
        assertTrue("山谷回声反馈率", EchoPreset.VALLEY.feedback > 0.6f)
    }

    @Test
    fun `合唱三档参数都比增强前更高`() {
        assertTrue("两人混响量", ChoirPreset.DUET.mix > 0.45f)
        assertTrue("两人展宽", ChoirPreset.DUET.spreadMs > 12f)
        assertTrue("三人混响量", ChoirPreset.TRIO.mix > 0.5f)
        assertTrue("三人展宽", ChoirPreset.TRIO.spreadMs > 18f)
        assertTrue("合唱团混响量", ChoirPreset.CHOIR.mix > 0.55f)
        assertTrue("合唱团展宽", ChoirPreset.CHOIR.spreadMs > 30f)
    }

    @Test
    fun `预设参数都在引擎安全范围内`() {
        EchoPreset.entries.forEach { preset ->
            assertTrue("${preset.label} 反馈率过高会自激", preset.feedback < 0.95f)
            assertTrue("${preset.label} 延迟超上限会被夹掉", preset.delayMs <= 2000f)
        }
        ChoirPreset.entries.forEach { preset ->
            assertTrue("${preset.label} 展宽超上限会被夹掉", preset.spreadMs <= 80f)
            assertTrue("${preset.label} 混响量应小于 1", preset.mix <= 1f)
        }
    }

    @Test
    fun `不同预设确实走了不同参数`() {
        // 防止「三个预设参数写重了」，看起来是一个效果
        val echoes = EchoPreset.entries.map { Triple(it.delayMs, it.feedback, it.mix) }.toSet()
        assertEquals("回声三个预设参数应各不相同", 3, echoes.size)
        val choirs = ChoirPreset.entries.map { Triple(it.spreadMs, it.mix, it.kind) }.toSet()
        assertEquals("合唱三个预设参数应各不相同", 3, choirs.size)
        assertNotEquals(EchoPreset.entries[0], EchoPreset.entries[1])
    }
}
