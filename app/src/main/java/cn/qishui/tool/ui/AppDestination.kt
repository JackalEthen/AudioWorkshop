package cn.qishui.tool.ui

enum class NavGlyph {
    Resolve,
    Edit,
    Settings,
}

enum class MainDestination(val route: String, val label: String, val glyph: NavGlyph) {
    Resolve("resolve", "解析", NavGlyph.Resolve),
    Edit("edit", "编辑", NavGlyph.Edit),
    Settings("settings", "设置", NavGlyph.Settings),
}
