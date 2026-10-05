package cn.music.audioworkshop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockMsParseTest {

    @Test
    fun `纯数字按毫秒解析`() {
        assertEquals(2000L, parseClockMs("2000"))
        assertEquals(0L, parseClockMs("0"))
    }

    @Test
    fun `分秒毫秒格式`() {
        assertEquals(3_000L, parseClockMs("00:03.000"))
        assertEquals(3_500L, parseClockMs("00:03.5"))
        assertEquals(65_000L, parseClockMs("01:05"))
    }

    @Test
    fun `显示和解析能来回还原`() {
        listOf(0L, 500L, 3_000L, 65_432L, 3_600_000L).forEach { ms ->
            assertEquals(ms, parseClockMs(formatClockMs(ms)))
        }
    }

    @Test
    fun `非法输入返回 null 而不是乱猜`() {
        assertNull(parseClockMs(""))
        assertNull(parseClockMs("   "))
        assertNull(parseClockMs("abc"))
        assertNull(parseClockMs("00:99.000"))
        assertNull(parseClockMs("-5"))
    }
}
