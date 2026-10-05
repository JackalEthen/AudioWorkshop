package cn.music.audioworkshop.feature.effect

import cn.music.audioworkshop.domain.model.DenoiseMode
import cn.music.audioworkshop.feature.denoise.DenoiseUiState
import cn.music.audioworkshop.feature.equalizer.EqPreset
import cn.music.audioworkshop.feature.equalizer.EqualizerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 均衡器的参数规则。 */
class EqualizerUiStateTest {

    @Test
    fun `默认是平直的`() {
        assertFalse(EqualizerUiState().isModified)
        assertEquals(List(8) { 0f }, EqualizerUiState().gainsDb)
    }

    @Test
    fun `任意一段非零就算改过`() {
        val gains = List(8) { 0f }.toMutableList().also { it[3] = 2f }
        assertTrue(EqualizerUiState(gainsDb = gains).isModified)
    }

    @Test
    fun `浮点误差不算改动`() {
        // 滑杆回调可能给出 0.000001 这种值，不该因此触发整条 biquad 链
        assertFalse(EqualizerUiState(gainsDb = List(8) { 0.000001f }).isModified)
    }

    @Test
    fun `每个预设都是八段`() {
        EqPreset.entries.forEach { preset ->
            assertEquals("${preset.label} 段数不对", EqualizerUiState.BAND_COUNT, preset.gains.size)
        }
    }

    @Test
    fun `自定义预设就是全零`() {
        // 平直对应「自定义」，全部归零靠它；没有单独的平直按钮
        assertTrue(EqPreset.CUSTOM.gains.all { it == 0f })
    }

    @Test
    fun `每个预设都不超增益上限`() {
        EqPreset.entries.forEach { preset ->
            preset.gains.forEach { gain ->
                assertTrue(
                    "${preset.label} 的 $gain 超过上限",
                    kotlin.math.abs(gain) <= EqualizerUiState.MAX_GAIN_DB,
                )
            }
        }
    }

    @Test
    fun `重低音预设低频增益高高频为零`() {
        // 预设的形状本身是产品决策，锁住它免得以后被无意改掉
        val bass = EqPreset.BASS.gains
        assertTrue(bass.first() > bass.last())
        assertEquals(0f, bass.last(), 0.001f)
    }
}

/** 降噪的参数规则。 */
class DenoiseUiStateTest {

    @Test
    fun `默认用通用降噪且是推荐频率`() {
        val state = DenoiseUiState()
        assertEquals(DenoiseMode.GENERAL, state.mode)
        assertEquals(80, state.lowHz)
        assertEquals(8_000, state.highHz)
    }

    @Test
    fun `通用模式显示频率参数录音模式不显示`() {
        assertTrue(DenoiseUiState(mode = DenoiseMode.GENERAL).showsFrequency)
        assertFalse(DenoiseUiState(mode = DenoiseMode.SPEECH).showsFrequency)
    }

    @Test
    fun `录音降噪没有频率参数所以填什么都不显示`() {
        // 用户从通用切到录音模式后，之前设的频率仍在但不该影响效果，也不该被看见
        val state = DenoiseUiState(mode = DenoiseMode.SPEECH, lowHz = 200, highHz = 4000)
        assertFalse(state.showsFrequency)
    }

    @Test
    fun `起始频率高于结束频率时把结束频率顶上去`() {
        // 不然会把要保留的中频整段切掉，人声消失只剩下的声音 —— 比不降噪还糟
        val state = DenoiseUiState(lowHz = 5_000, highHz = 1_000).clamped()
        assertEquals(5_000, state.lowHz)
        assertEquals(5_000, state.highHz)
    }

    @Test
    fun `频率被夹在可听范围内`() {
        assertEquals(DenoiseMode.MIN_FREQ_HZ, DenoiseUiState(lowHz = 0).clamped().lowHz)
        assertEquals(
            DenoiseMode.MAX_FREQ_HZ,
            DenoiseUiState(highHz = 999_999).clamped().highHz,
        )
    }

    @Test
    fun `强度被夹在零到一`() {
        assertEquals(0f, DenoiseUiState(strength = -1f).clamped().strength, 0.001f)
        assertEquals(1f, DenoiseUiState(strength = 5f).clamped().strength, 0.001f)
    }

    @Test
    fun `恢复默认回到推荐频率`() {
        val edited = DenoiseUiState(lowHz = 300, highHz = 12_000)
        val defaults = edited.copy(
            lowHz = DenoiseMode.DEFAULT_LOW_HZ,
            highHz = DenoiseMode.DEFAULT_HIGH_HZ,
        )
        assertEquals(80, defaults.lowHz)
        assertEquals(8_000, defaults.highHz)
    }

    @Test
    fun `默认起始频率不碰人声基频`() {
        // 男声基频低到约 85Hz，起始频率压到 80Hz 以下才可能伤到说话声
        assertTrue("起始频率应低于人声基频", DenoiseMode.DEFAULT_LOW_HZ <= 85)
    }

    @Test
    fun `默认结束频率保留齿音区`() {
        // 齿音辅音在 4k~8k，压太低会把「s」「sh」削没
        assertTrue("结束频率应覆盖齿音区", DenoiseMode.DEFAULT_HIGH_HZ >= 8_000)
    }
}