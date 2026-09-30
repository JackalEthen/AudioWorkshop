package cn.qishui.tool.media.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchPercentTest {

    @Test
    fun `100 percent is the original pitch`() {
        assertEquals(0f, PcmEffects.pitchPercentToSemitones(100f), 0.001f)
    }

    @Test
    fun `one semitone up is about 106 percent`() {
        val semitones = PcmEffects.pitchPercentToSemitones(105.9463f)

        assertEquals(1f, semitones, 0.01f)
    }

    @Test
    fun `one semitone down is about 94 percent`() {
        val semitones = PcmEffects.pitchPercentToSemitones(94.3913f)

        assertEquals(-1f, semitones, 0.01f)
    }

    @Test
    fun `doubling is exactly one octave`() {
        assertEquals(12f, PcmEffects.pitchPercentToSemitones(200f), 0.01f)
        assertEquals(-12f, PcmEffects.pitchPercentToSemitones(50f), 0.01f)
    }

    @Test
    fun `the reference 96 percent lands slightly flat`() {
        // 参考示例当前值就是 96.0%，换算过去应该是降半音多一点
        val semitones = PcmEffects.pitchPercentToSemitones(96f)

        assertTrue("96% 应该是降调，实际 $semitones", semitones < 0f)
        assertTrue("96% 不该超过降一个半音，实际 $semitones", semitones > -1.5f)
    }

    @Test
    fun `pitch and speed are independent`() {
        // 变速变调一次完成，两者不能互相影响换算
        assertEquals(0f, PcmEffects.pitchPercentToSemitones(100f), 0.001f)
    }
}
