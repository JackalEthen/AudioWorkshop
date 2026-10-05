package cn.music.audioworkshop.feature.reverb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混响的参数与预设规则。
 *
 * 关键点：预设是参数的快照，改动任一参数都要能立刻反映成「自定义」，
 * 否则界面会显示一个已经不准的空间名字。
 */
class ReverbUiStateTest {

    @Test
    fun `默认匹配录音棚预设`() {
        assertEquals(ReverbPreset.STUDIO, ReverbUiState().matchedPreset)
    }

    @Test
    fun `每个空间预设都有明确参数除了自定义`() {
        ReverbPreset.entries.forEach { preset ->
            if (preset.isCustom) {
                assertEquals("自定义不该有固定参数", null, preset.params)
            } else {
                val p = preset.params!!
                assertTrue("${preset.label} 的湿声太小", p.mix > 0f)
                assertTrue("${preset.label} 的湿声太大", p.mix <= 0.5f)
            }
        }
    }

    @Test
    fun `套用预设后能匹配回该预设`() {
        ReverbPreset.entries.filter { !it.isCustom }.forEach { preset ->
            val p = preset.params!!
            val state = ReverbUiState(
                mix = p.mix,
                roomSize = p.roomSize,
                decay = p.decay,
                predelayMs = p.predelayMs,
                damping = p.damping,
            )
            assertEquals(preset.label, preset, state.matchedPreset)
        }
    }

    @Test
    fun `大厅的尾音比小房间长`() {
        // 这是空间听感的核心区别，不能反
        val hall = ReverbPreset.HALL.params!!
        val small = ReverbPreset.SMALL_ROOM.params!!
        assertTrue("大厅衰减应更长", hall.decay > small.decay)
        assertTrue("大厅空间应更大", hall.roomSize > small.roomSize)
        assertTrue("大厅预延迟应更长", hall.predelayMs > small.predelayMs)
    }

    @Test
    fun `浴室的高频阻尼最小最亮`() {
        // 阻尼小 = 高频留得多 = 浴室那种亮且有金属味的感觉
        val bathroom = ReverbPreset.BATHROOM.params!!
        val others = ReverbPreset.entries
            .filter { !it.isCustom && it != ReverbPreset.BATHROOM }
            .map { it.params!!.damping }
        assertTrue("浴室阻尼应最低", others.all { bathroom.damping < it })
    }

    @Test
    fun `录音棚的房间感最轻`() {
        val studio = ReverbPreset.STUDIO.params!!
        ReverbPreset.entries.filter { !it.isCustom && it != ReverbPreset.STUDIO }.forEach {
            assertTrue("${it.label} 的湿声应比录音棚多", studio.mix < it.params!!.mix)
        }
    }

    @Test
    fun `改任一参数都会变成自定义`() {
        val base = ReverbUiState()
        listOf(
            base.copy(mix = 0.9f),
            base.copy(roomSize = 0.9f),
            base.copy(decay = 0.9f),
            base.copy(predelayMs = 99f),
            base.copy(damping = 0.9f),
        ).forEach { assertEquals(ReverbPreset.CUSTOM, it.matchedPreset) }
    }

    @Test
    fun `调回预设的参数又变回那个预设`() {
        val studio = ReverbPreset.STUDIO.params!!
        val tweaked = ReverbUiState(mix = studio.mix + 0.2f)
        assertEquals(ReverbPreset.CUSTOM, tweaked.matchedPreset)
        val restored = tweaked.copy(mix = studio.mix)
        assertEquals(ReverbPreset.STUDIO, restored.matchedPreset)
    }

    @Test
    fun `参数被夹在合法范围`() {
        val s = ReverbUiState(
            mix = 5f,
            roomSize = -1f,
            decay = 9f,
            predelayMs = 9999f,
            damping = -3f,
        ).clamped()
        assertEquals(1f, s.mix, 0.001f)
        assertEquals(0f, s.roomSize, 0.001f)
        assertEquals(1f, s.decay, 0.001f)
        assertEquals(ReverbUiState.MAX_PREDELAY_MS, s.predelayMs, 0.001f)
        assertEquals(0f, s.damping, 0.001f)
    }

    @Test
    fun `预延迟上限之内都合法`() {
        assertEquals(200f, ReverbUiState(predelayMs = 200f).clamped().predelayMs, 0.001f)
    }

    @Test
    fun `各预设参数互不相同`() {
        // 五个预设如果有两个参数一样，界面上就分不出区别，没意义
        val snapshots = ReverbPreset.entries
            .filter { !it.isCustom }
            .map { it.params.toString() }
        assertEquals("有预设参数重复", snapshots.size, snapshots.toSet().size)
    }

    @Test
    fun `湿声比例不超过一半`() {
        // 超过一半就成了「效果声」而不是「加空间」，听感会变成另一种东西
        ReverbPreset.entries.filter { !it.isCustom }.forEach {
            assertTrue("${it.label} 湿声过半", it.params!!.mix <= 0.5f)
        }
    }
}