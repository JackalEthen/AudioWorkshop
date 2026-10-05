package cn.music.audioworkshop.media.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁住音质降级逻辑。
 *
 * 之前是无脑把用户选的档位发给音源。grass 源只声明 `128k`，
 * 我们硬发 `320k`，服务端直接 404，报错完全看不出是音质选错了。
 */
class LxSourceResolverQualityTest {

    @Test
    fun `源只支持 128k 时把 320k 降下来`() {
        assertEquals("128k", degradeQuality("320k", listOf("128k")))
    }

    @Test
    fun `源只支持 128k 时 flac 也降下来`() {
        assertEquals("128k", degradeQuality("flac", listOf("128k")))
        assertEquals("128k", degradeQuality("flac24bit", listOf("128k")))
    }

    @Test
    fun `支持的档位里挑最高的可用`() {
        assertEquals("flac", degradeQuality("flac", listOf("128k", "320k", "flac")))
    }

    @Test
    fun `源声明了 320k 就给 320k`() {
        assertEquals("320k", degradeQuality("320k", listOf("128k", "320k")))
    }

    @Test
    fun `源没声明音质就按请求的给`() {
        // 不猜。有些源不报 qualitys，拦下来反而坏事。
        assertEquals("320k", degradeQuality("320k", emptyList()))
    }

    @Test
    fun `声明里没有请求档位时往下找而不是往上抬`() {
        assertEquals("320k", degradeQuality("flac24bit", listOf("128k", "320k")))
    }

    @Test
    fun `未知档位名按最高档处理`() {
        assertEquals("320k", degradeQuality("999k", listOf("128k", "320k")))
    }
}
