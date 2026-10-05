package cn.music.audioworkshop.feature.effect

import cn.music.audioworkshop.domain.model.ChoirPreset
import cn.music.audioworkshop.domain.model.EchoPreset
import cn.music.audioworkshop.feature.choir.ChoirUiState
import cn.music.audioworkshop.feature.echo.EchoUiState
import cn.music.audioworkshop.feature.equalizer.EqPreset
import cn.music.audioworkshop.feature.equalizer.EqualizerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回声预设。 */
class EchoPresetTest {

    @Test
    fun `三个预设的参数互不相同`() {
        // 参数都一样的话界面上就分不出区别
        val keys = EchoPreset.entries.map { listOf(it.delayMs, it.feedback, it.mix, it.kind) }
        assertEquals("有回声预设参数重复", keys.size, keys.toSet().size)
    }

    @Test
    fun `延迟递增湿声递增`() {
        // 三档之间要有明确梯度，否则用户听不出区别
        val sorted = EchoPreset.entries.sortedBy { it.delayMs }
        sorted.zipWithNext { a, b ->
            assertTrue("${a.label} 到 ${b.label} 湿声没变多", b.mix > a.mix)
        }
    }

    @Test
    fun `山谷回声是唯一的多重回声类型`() {
        // kind 0 才是多重反射的算法分支，不能和房间/双重混用
        assertEquals(0, EchoPreset.VALLEY.kind)
        assertEquals(1, EchoPreset.ROOM.kind)
        assertEquals(1, EchoPreset.DOUBLE.kind)
    }

    @Test
    fun `反馈都不能超过上限否则会自激`() {
        // feedback >= 1 时延迟线里的能量会指数增长，输出直接爆掉
        EchoPreset.entries.forEach {
            assertTrue("${it.label} 反馈会自激", it.feedback < 0.95f)
        }
    }

    @Test
    fun `延迟在引擎允许的范围内`() {
        // PcmEffects.echo 把延迟夹在 20~2000ms，超出会被静默改掉
        EchoPreset.entries.forEach {
            assertTrue("${it.label} 延迟太短", it.delayMs >= 20f)
            assertTrue("${it.label} 延迟太长", it.delayMs <= 2000f)
        }
    }

    @Test
    fun `没选预设时不能导出`() {
        assertFalse(EchoUiState(track = null).canExport)
        assertFalse(EchoUiState().canExport)
        assertTrue(EchoUiState().copy(preset = EchoPreset.ROOM).canExport.not())
    }
}

/** 合唱预设。 */
class ChoirPresetTest {

    @Test
    fun `声部数量和标签一致`() {
        assertEquals(2, ChoirPreset.DUET.voiceCount)
        assertEquals(3, ChoirPreset.TRIO.voiceCount)
        assertEquals(6, ChoirPreset.CHOIR.voiceCount)
    }

    @Test
    fun `人越多铺得越宽`() {
        // 声部数上去了但铺宽不变，声音会挤在一个位置，听感上人没变多
        assertTrue("三人应比两人宽", ChoirPreset.TRIO.spreadMs > ChoirPreset.DUET.spreadMs)
        assertTrue("合唱团应比三人宽", ChoirPreset.CHOIR.spreadMs > ChoirPreset.TRIO.spreadMs)
    }

    @Test
    fun `人越多湿声越多`() {
        assertTrue(ChoirPreset.CHOIR.mix > ChoirPreset.TRIO.mix)
        assertTrue(ChoirPreset.TRIO.mix > ChoirPreset.DUET.mix)
    }

    @Test
    fun `铺宽在引擎允许的范围内`() {
        // PcmEffects.choir 夹在 5~80ms
        ChoirPreset.entries.forEach {
            assertTrue("${it.label} 铺宽太窄", it.spreadMs >= 5f)
            assertTrue("${it.label} 铺宽太宽", it.spreadMs <= 80f)
        }
    }

    @Test
    fun `kind 和声部数量对得上`() {
        // 引擎按 kind 决定人数，两边不一致会听成「说两人其实是六人」
        assertEquals(ChoirPreset.DUET.voiceCount, if (ChoirPreset.DUET.kind == 0) 2 else 0)
        assertEquals(ChoirPreset.CHOIR.voiceCount, if (ChoirPreset.CHOIR.kind == 2) 6 else 0)
        assertEquals(ChoirPreset.TRIO.voiceCount, if (ChoirPreset.TRIO.kind == 1) 3 else 0)
    }
}

/** 均衡器的预设改名后的行为。 */
class EqualizerPresetNamingTest {

    @Test
    fun `没有平直这个预设`() {
        // 把「什么都不做」摆成一个按钮，会让人以为选它等于开启均衡
        assertFalse(EqPreset.entries.any { it.label.contains("平直") })
    }

    @Test
    fun `自定义就是全平直`() {
        assertTrue(EqPreset.CUSTOM.gains.all { it == 0f })
    }

    @Test
    fun `人声标签就是两个字`() {
        assertEquals("人声", EqPreset.VOCAL.label)
    }

    @Test
    fun `默认状态落在自定义上`() {
        assertEquals(EqPreset.CUSTOM, EqualizerUiState().matchedPreset)
    }

    @Test
    fun `手动调过之后匹配自定义除非调回某个预设`() {
        val base = EqualizerUiState()
        // 没碰过时（全是 0）匹配自定义
        assertEquals(EqPreset.CUSTOM, base.matchedPreset)
        // 碰过但不是任何预设的数值 -> 自定义
        val touched = base.copy(gainsDb = base.gainsDb.toMutableList().also { it[2] = 1.7f }, touchedByUser = true)
        assertEquals(EqPreset.CUSTOM, touched.matchedPreset)
        // 碰过但数值正好等于某个预设 -> 显示那个预设更准确
        val backToPreset = base.copy(
            gainsDb = EqPreset.BASS.gains.toList(),
            touchedByUser = true,
        )
        assertEquals(EqPreset.BASS, backToPreset.matchedPreset)
    }

    @Test
    fun `所有预设都是八段`() {
        EqPreset.entries.forEach {
            assertEquals("${it.label} 段数不对", EqualizerUiState.BAND_COUNT, it.gains.size)
        }
    }
}