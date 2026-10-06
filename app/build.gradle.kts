import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}

// 签名口令只从 local.properties 读，不写进本文件 —— 它会进 git。
// 没配就退化成未签名的 release（assembleRelease 产出 unsigned apk，不影响 CI 编译）。
val releaseStoreFile: String? = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("RELEASE_STORE_FILE") }

android {
    namespace = "cn.music.audioworkshop"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                val props = Properties().apply {
                    rootProject.file("local.properties").inputStream().use { load(it) }
                }
                storeFile = file(releaseStoreFile)
                storePassword = props.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = props.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = props.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "cn.music.audioworkshop"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 可用 -PqishuiAbis=arm64-v8a 覆盖，用来出单 ABI 的包（体积约为全量包的三分之一）。
            abiFilters += (providers.gradleProperty("qishuiAbis").orNull ?: "arm64-v8a,armeabi-v7a,x86_64")
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // 关键：跳过 stripDebugSymbols。
            // libmp3lame_jni 里的 JNI 入口（StretchBridge / LoudnessBridge）只能被
            // Java 通过 dlsym 找到，没有任何 C++ 侧引用，strip 会把它们当无用符号删掉，
            // 结果是运行时 UnsatisfiedLinkError —— 而编译、链接、APK 打包全都正常，
            // 只有真机上才炸。已实测：strip 前 10336264 字节含符号，
            // strip 后 6332080 字节符号全丢。
            // ponytail: so 体积增加约 40%，换 JNI 一定可用。
            // 真要减体积，应在 CMake 里加 version-script 精确导出 JNI 符号，
            // 而不是整体关掉 strip —— 那是发布前的体积优化，不影响功能。
            keepDebugSymbols += "**/libmp3lame_jni.so"
        }
    }

    sourceSets {
        // 单测要直接读 assets 里那份真实的 lx preload 来验证协议
        getByName("test").assets.srcDir("src/main/assets")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // android.util.Log 在 JVM 单测里没有实现，不开这个会直接抛
            // "Method i in android.util.Log not mocked"。导出/发布链路里有正常的
            // 诊断日志，不开这个就没法对它们写单测。
            isReturnDefaultValues = true
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":audiofx"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.okhttp)
    implementation(libs.quickjs)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    kapt(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.json)
}

