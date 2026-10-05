package cn.music.audioworkshop.media.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * nativeInit 的返回值判定。
 *
 * 回归测试：以前写的是 `created <= 0` 判失败。Android 的 MTE 堆指针带标签
 * （形如 `0xb40000748beeaf00`），最高字节让符号位为 1，作为有符号 Long 看就是
 * 负数 —— 于是 LAME 每次都初始化成功，却每次都被判成失败，
 * 用户看到的是「LAME 初始化失败」。
 *
 * 真机上抓到过：
 * ```
 * I qishui/lame: init ok flags=0xb40000748beeaf00
 * E qishui/lame: nativeInit returned b40000748beeaf00 (signed=-5476376646318641408)
 * ```
 */
class LameHandleTest {

    /** 真机抓到的 MTE tagged pointer。 */
    private val mtePointer = java.lang.Long.parseUnsignedLong("b40000748beeaf00", 16)

    private fun isErrorCode(value: Long): Boolean =
        value != 0L && kotlin.math.abs(value) <= 2_000L

    @Test
    fun `带 tag 的指针不是错误码`() {
        assertFalse("MTE 指针必须当成功处理", isErrorCode(mtePointer))
        assertTrue("它的有符号值确实是负的", mtePointer < 0L)
    }

    @Test
    fun `普通堆指针也不是错误码`() {
        assertFalse(isErrorCode(0x7f0000000000L))
        assertFalse(isErrorCode(0x0000007fa1b2c3d4L))
    }

    @Test
    fun `真实错误码都被识别`() {
        // nativeInit: QS_STEP_ERROR_BASE(-1000) - step，step = 1..10 → -1001..-1010
        for (step in 1..10) {
            val code = -1000L - step
            assertTrue("step=$step (code=$code) 应判为错误", isErrorCode(code))
        }
        assertTrue(isErrorCode(-900L)) // QS_HANDLE_INVALID
        assertTrue(isErrorCode(-1L))
    }

    @Test
    fun `零既不当成功也不当错误，而是未初始化`() {
        // handle == 0 表示「还没 init」，不能进错误分支去报错
        assertFalse(isErrorCode(0L))
    }
}