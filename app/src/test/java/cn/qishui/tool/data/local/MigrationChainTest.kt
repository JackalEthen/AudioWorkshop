package cn.qishui.tool.data.local

import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 迁移版本号必须连成一条链，中间断一环 Room 会直接拒绝启动。
 * 这里只校验链，不跑真库（跑真库要 Robolectric，不值当）。
 *
 * [Migration] 的 targetVersion 是构造参数不是公开属性，所以链用字面量记录一份，
 * 再和实际的迁移对象逐条比对，防止加迁移时忘了更新这条链。
 */
class MigrationChainTest {

    private data class Step(val from: Int, val to: Int, val migration: Migration)

    private val steps = listOf(
        Step(1, 2, MIGRATION_1_2),
        Step(2, 3, MIGRATION_2_3),
        Step(3, 4, MIGRATION_3_4),
        Step(4, 5, MIGRATION_4_5),
        Step(5, 6, MIGRATION_5_6),
        Step(6, 7, MIGRATION_6_7),
        Step(7, 8, MIGRATION_7_8),
        Step(8, 9, MIGRATION_8_9),
        Step(9, 10, MIGRATION_9_10),
    )

    @Test
    fun `记录链本身首尾相接`() {
        steps.forEachIndexed { index, step ->
            if (index == 0) {
                assertEquals("第一条迁移必须从 1 开始", 1, step.from)
            } else {
                assertEquals(
                    "第 $index 步起点接不上",
                    steps[index - 1].to,
                    step.from,
                )
            }
        }
    }

    @Test
    fun `记录的版本号和迁移对象一致`() {
        steps.forEach { step ->
            assertEquals("${step.from}->${step.to} 的 startVersion 对不上", step.from, step.migration.startVersion)
        }
    }

    @Test
    fun `最新一步是9到10`() {
        val last = steps.last()
        assertEquals(9, last.from)
        assertEquals(10, last.to)
    }
}
