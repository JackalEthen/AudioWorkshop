package cn.music.audioworkshop.ui

import cn.music.audioworkshop.feature.edit.EditDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDestinationTest {
    @Test
    fun mainDestinationsHaveUniqueRoutes() {
        val routes = MainDestination.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
fun mainDestinationLabelsAreInOrder() {
        assertEquals(
            listOf("编辑", "播放器", "解析", "设置"),
            MainDestination.entries.map { it.label },
        )
    }

    @Test
    fun editBottomRouteMatchesEditHomeRoute() {
        assertEquals(EditDestination.HomeRoute, MainDestination.Edit.route)
    }
}
