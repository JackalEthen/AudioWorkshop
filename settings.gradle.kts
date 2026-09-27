pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Qishui"
include(":app")
// 音频 DSP + JNI 单独成模块：kapt（Room）不去碰这个模块，
// 绕开中文 Windows 上 kapt stub 分析的编码问题。
include(":audiofx")
