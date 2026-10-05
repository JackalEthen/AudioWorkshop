package cn.music.audioworkshop.data.local

import androidx.room.migration.Migration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    Step(10, 11, MIGRATION_10_11),
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
    fun `最新一步是10到11`() {
        val last = steps.last()
        assertEquals(10, last.from)
        assertEquals(11, last.to)
    }

    /**
     * 迁移对象写出来了，还得真的注册给 database builder。
     *
     * 漏注册时 Room 只在运行时抛「migration from X to Y was required but not found」，
     * 链完整性测试全绿也照样漏过去 —— 装机后必崩。所以这里直接对源码断言。
     */
    @Test
    fun `每条迁移都注册进了AppContainer`() {
        // 按类名定位而不是硬编码路径：包名/目录一改，路径就失效了
        val containerSource = locateAppContainer()
        assertNotNull("找不到 AppContainer.kt", containerSource)
        val text = containerSource!!.readText()
        val addMigrations = text.substringAfter("addMigrations(").substringBefore(").build()")
        steps.forEach { step ->
            val name = when (step.to) {
                2 -> "MIGRATION_1_2"
                3 -> "MIGRATION_2_3"
                4 -> "MIGRATION_3_4"
                5 -> "MIGRATION_4_5"
                6 -> "MIGRATION_5_6"
                7 -> "MIGRATION_6_7"
                8 -> "MIGRATION_7_8"
                9 -> "MIGRATION_8_9"
                10 -> "MIGRATION_9_10"
                11 -> "MIGRATION_10_11"
                else -> null
            }
            assertNotNull("版本 ${step.to} 没有对应的迁移常量名", name)
            assertTrue(
                "$name 定义了但没注册进 addMigrations，装机后会崩",
                addMigrations.contains(name!!),
            )
        }
    }

    /**
     * 从模块根目录往上找 AppContainer.kt。
     *
     * Gradle 跑单测时工作目录是模块目录（`app/`），但 IDE 有时是仓库根，
     * 所以两种都试。不写死包名路径 —— 改包名就会失效。
     */
    private fun locateAppContainer(): File? {
        val relative = "src/main/java"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
        )
        for (root in candidates) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .firstOrNull { it.isFile && it.name == "AppContainer.kt" }
                ?.let { return it }
        }
        return null
    }
}


