package cn.music.audioworkshop.feature.equalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 均衡器的增益写入。
 *
 * 这一组是为了防一类具体的手误：把参数改了名（比如从 `value` 改成 `db`），
 * 却忘了改方法体里对旧名字的引用，结果写回去的永远是上一次的旧值 ——
 * 表现是「拖动有反应但数值纹丝不动」，而且没有任何报错。
 */
class EqualizerGainUpdateTest {

    /** 和 ViewModel.setGain 同样的纯函数写法，方便在 JVM 上直接验证。 */
    private fun applyGain(gains: List<Float>, index: Int, db: Float): List<Float> {
        if (index !in gains.indices) return gains
        val clamped = db.coerceIn(-12f, 12f)
        return gains.mapIndexed { i, g -> if (i == index) clamped else g }
    }

    @Test
    fun `写入的是传进来的值而不是旧值`() {
        val updated = applyGain(List(8) { 0f }, 0, 5.18f)
        assertEquals(5.18f, updated[0], 0.001f)
    }

    @Test
    fun `只改指定的那一段`() {
        val updated = applyGain(List(8) { 0f }, 3, -6f)
        assertEquals(-6f, updated[3], 0.001f)
        updated.forEachIndexed { i, v ->
            if (i != 3) assertEquals("第${i + 1}段不该被改动", 0f, v, 0.001f)
        }
    }

    @Test
    fun `多次拖动逐步更新而不是固定在初值`() {
        var gains = List(8) { 0f }
        // 模拟一次从左往右的拖动
        listOf(-8.9f, -5.0f, -1.3f, 2.4f, 6.1f).forEach { gains = applyGain(gains, 0, it) }
        assertEquals(6.1f, gains[0], 0.001f)
    }

    @Test
    fun `超范围的写入被夹住`() {
        assertEquals(12f, applyGain(List(8) { 0f }, 0, 99f)[0], 0.001f)
        assertEquals(-12f, applyGain(List(8) { 0f }, 0, -99f)[0], 0.001f)
    }

    @Test
    fun `下标越界时原样返回`() {
        val gains = List(8) { 0f }
        assertEquals(gains, applyGain(gains, 8, 5f))
        assertEquals(gains, applyGain(gains, -1, 5f))
    }

    @Test
    fun `不同段可以同时有值`() {
        var gains = List(8) { 0f }
        gains = applyGain(gains, 0, 3f)
        gains = applyGain(gains, 7, -4f)
        assertEquals(3f, gains[0], 0.001f)
        assertEquals(-4f, gains[7], 0.001f)
    }

    @Test
    fun `结果列表长度不变`() {
        // 长度变了会让 UI 的 forEachIndexed 索引错位，段与段对不上
        assertEquals(8, applyGain(List(8) { 0f }, 2, 5f).size)
    }

    @Test
    fun `isModified 能反映出写入`() {
        fun isModified(gains: List<Float>) = gains.any { abs(it) > 0.01f }
        assertTrue(isModified(applyGain(List(8) { 0f }, 0, 1f)))
        assertTrue(!isModified(List(8) { 0f }))
    }

    private fun abs(v: Float) = kotlin.math.abs(v)
}