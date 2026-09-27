# Android UI Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:implement to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable Android 16 project using the existing `T:\Android` toolchain and implement the approved light Material 3 navigation shell, three main pages, record-center entry, settings entries, parsing animation, and five selectable themes.

**Architecture:** Use one Android application module with a single Activity, MVVM/UDF state, Navigation Compose, and small feature-focused Composables. API, Room, Media3, and native audio code are intentionally deferred until their independent phases have concrete inputs and tests.

**Tech Stack:** Kotlin 2.0.21, AGP 8.11.2, Gradle 8.13, Jetpack Compose, Material 3, Navigation Compose, Android SDK 36, JDK 17.

---

## Fixed environment

```powershell
$env:GRADLE_USER_HOME = "T:\Android\ASDate\.gradle"
$env:JAVA_HOME = "T:\Android\Android Studio\jbr"
```

Expected wrapper version: Gradle 8.13. The wrapper distribution is already cached at `T:\Android\ASDate\.gradle\wrapper\dists\gradle-8.13-bin`.

## File map

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
local.properties
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/cn/qishui/tool/MainActivity.kt
app/src/main/java/cn/qishui/tool/ui/QishuiApp.kt
app/src/main/java/cn/qishui/tool/ui/AppDestination.kt
app/src/main/java/cn/qishui/tool/ui/components/FloatingCard.kt
app/src/main/java/cn/qishui/tool/ui/components/ParsingIndicator.kt
app/src/main/java/cn/qishui/tool/ui/theme/ThemeCatalog.kt
app/src/main/java/cn/qishui/tool/ui/theme/Theme.kt
app/src/main/java/cn/qishui/tool/ui/theme/Type.kt
app/src/main/java/cn/qishui/tool/feature/resolve/ResolveScreen.kt
app/src/main/java/cn/qishui/tool/feature/records/RecordsScreen.kt
app/src/main/java/cn/qishui/tool/feature/edit/EditScreen.kt
app/src/main/java/cn/qishui/tool/feature/settings/SettingsScreen.kt
app/src/main/java/cn/qishui/tool/feature/settings/AppearanceScreen.kt
app/src/main/res/values/strings.xml
app/src/main/res/values/themes.xml
app/src/main/res/values/colors.xml
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/test/java/cn/qishui/tool/ui/AppDestinationTest.kt
app/src/test/java/cn/qishui/tool/ui/theme/ThemeCatalogTest.kt
```

## Task 1: Bootstrap the build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `local.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

- [ ] **Step 1: Create the project directories**

```powershell
$paths = @(
  "gradle\wrapper",
  "app\src\main\java\cn\qishui\tool",
  "app\src\main\res\values",
  "app\src\main\res\drawable",
  "app\src\main\res\mipmap-anydpi-v26",
  "app\src\test\java\cn\qishui\tool"
)
$paths | ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
```

Expected: all directories exist under `T:\Zfile\qishui`.

- [ ] **Step 2: Reuse the existing Gradle wrapper files**

```powershell
Copy-Item "T:\Android\AndroidStudioProjects\Yueyao-Mobile-skill-manager\gradlew" ".\gradlew"
Copy-Item "T:\Android\AndroidStudioProjects\Yueyao-Mobile-skill-manager\gradlew.bat" ".\gradlew.bat"
Copy-Item "T:\Android\AndroidStudioProjects\Yueyao-Mobile-skill-manager\gradle\wrapper\gradle-wrapper.jar" ".\gradle\wrapper\gradle-wrapper.jar"
```

Expected: wrapper files exist and no Gradle distribution is downloaded.

- [ ] **Step 3: Write Gradle configuration**

Use these locked versions:

```toml
[versions]
agp = "8.11.2"
kotlin = "2.0.21"
coreKtx = "1.15.0"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
navigationCompose = "2.8.5"
composeBom = "2024.12.01"
junit = "4.13.2"
```

Configure the app module with:

```kotlin
android {
    namespace = "cn.qishui.tool"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "cn.qishui.tool"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}
```

- [ ] **Step 4: Point the project at the existing SDK**

`local.properties`:

```properties
sdk.dir=T\:\\Android\\Sdk
```

- [ ] **Step 5: Verify the wrapper without downloading Gradle**

Run:

```powershell
$env:GRADLE_USER_HOME = "T:\Android\ASDate\.gradle"
$env:JAVA_HOME = "T:\Android\Android Studio\jbr"
& .\gradlew.bat --version
```

Expected: Gradle `8.13`, JVM 17 or a compatible newer JDK.

## Task 2: Add destination and theme contracts

**Files:**
- Create: `app/src/main/java/cn/qishui/tool/ui/AppDestination.kt`
- Create: `app/src/main/java/cn/qishui/tool/ui/theme/ThemeCatalog.kt`
- Test: `app/src/test/java/cn/qishui/tool/ui/AppDestinationTest.kt`
- Test: `app/src/test/java/cn/qishui/tool/ui/theme/ThemeCatalogTest.kt`

- [ ] **Step 1: Write failing destination tests**

```kotlin
class AppDestinationTest {
    @Test
    fun mainDestinationsHaveUniqueRoutes() {
        val routes = MainDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun mainDestinationLabelsAreStable() {
        assertEquals("解析", MainDestination.Resolve.label)
        assertEquals("编辑", MainDestination.Edit.label)
        assertEquals("设置", MainDestination.Settings.label)
    }
}
```

- [ ] **Step 2: Write failing theme catalog tests**

```kotlin
class ThemeCatalogTest {
    @Test
    fun containsFiveApprovedThemesInOrder() {
        assertEquals(
            listOf("元气橙", "晴空蓝", "薄荷青", "星云紫", "桃桃粉"),
            ThemeCatalog.themes.map { it.name },
        )
    }
}
```

- [ ] **Step 3: Run unit tests and verify failure**

Run:

```powershell
& .\gradlew.bat :app:testDebugUnitTest
```

Expected: compilation fails because the production classes do not exist.

- [ ] **Step 4: Implement destination and theme contracts**

```kotlin
enum class MainDestination(val route: String, val label: String) {
    Resolve("resolve", "解析"),
    Edit("edit", "编辑"),
    Settings("settings", "设置"),
}
```

`ThemeCatalog` contains exactly the five approved OKLCH specifications from the design document and Android `Color` values converted for Compose.

- [ ] **Step 5: Run unit tests and verify pass**

Run:

```powershell
& .\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

## Task 3: Implement the application shell

**Files:**
- Create: `app/src/main/java/cn/qishui/tool/MainActivity.kt`
- Create: `app/src/main/java/cn/qishui/tool/ui/QishuiApp.kt`
- Create: `app/src/main/java/cn/qishui/tool/ui/theme/Theme.kt`
- Create: `app/src/main/java/cn/qishui/tool/ui/theme/Type.kt`
- Create: `app/src/main/java/cn/qishui/tool/ui/components/FloatingCard.kt`
- Create: `app/src/main/java/cn/qishui/tool/ui/components/ParsingIndicator.kt`

- [ ] **Step 1: Implement the Activity**

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QishuiTheme(themeSpec = ThemeCatalog.themes.first()) {
                QishuiApp()
            }
        }
    }
}
```

- [ ] **Step 2: Implement navigation**

`QishuiApp` uses `NavHost`, three bottom destinations, and secondary routes:

```text
resolve
records
edit
settings
appearance
```

System back returns from secondary routes before leaving the app.

- [ ] **Step 3: Implement the light theme**

Create five light `ColorScheme` values with shared error colors and white/near-white surfaces. Use Material type roles and 48dp minimum touch targets.

- [ ] **Step 4: Implement shared cards and parsing animation**

`FloatingCard` uses 20dp rounded corners, low elevation, optional alpha, and no decorative border. `ParsingIndicator` animates five equalizer bars with `rememberInfiniteTransition`.

- [ ] **Step 5: Compile the shell**

Run:

```powershell
& .\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

## Task 4: Implement the approved page skeleton

**Files:**
- Create: `app/src/main/java/cn/qishui/tool/feature/resolve/ResolveScreen.kt`
- Create: `app/src/main/java/cn/qishui/tool/feature/records/RecordsScreen.kt`
- Create: `app/src/main/java/cn/qishui/tool/feature/edit/EditScreen.kt`
- Create: `app/src/main/java/cn/qishui/tool/feature/settings/SettingsScreen.kt`
- Create: `app/src/main/java/cn/qishui/tool/feature/settings/AppearanceScreen.kt`

- [ ] **Step 1: Build the Resolve page**

Include the top menu, share-input card, parsing animation, field-based sample result, preview button, and download button. UI callbacks are inert in this phase and do not call an API.

- [ ] **Step 2: Build the Records page**

Include two top segments, sample download rows, pause/continue/delete controls, and the two-checkbox deletion sheet. The confirm button is disabled when neither checkbox is selected.

- [ ] **Step 3: Build the Edit page**

Use a top “记录” action, no banner, and a two-column grid of six text-only cards. Each card opens a route placeholder for its editor.

- [ ] **Step 4: Build the Settings pages**

Settings contains four text-first cards. Appearance contains five theme swatches, four font sizes, wallpaper controls, and the approved labels. Download, Other, and About receive basic navigable placeholder pages inside the Settings route.

- [ ] **Step 5: Compile all screens**

Run:

```powershell
& .\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL` with no warnings treated as errors.

## Task 5: Verify the first deliverable

**Files:**
- Verify all files from Tasks 1–4.

- [ ] **Step 1: Run unit tests**

```powershell
& .\gradlew.bat :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 2: Build the debug APK**

```powershell
& .\gradlew.bat :app:assembleDebug
```

Expected: `app\build\outputs\apk\debug\app-debug.apk` exists.

- [ ] **Step 3: Run Android lint**

```powershell
& .\gradlew.bat :app:lintDebug
```

Expected: `BUILD SUCCESSFUL` and no fatal lint errors.

- [ ] **Step 4: Inspect the APK**

```powershell
& "T:\Android\Sdk\build-tools\36.1.0\aapt.exe" dump badging ".\app\build\outputs\apk\debug\app-debug.apk"
```

Expected: package `cn.qishui.tool`, target SDK 36, min SDK 26.

- [ ] **Step 5: Record the next phase boundary**

The next independent phase starts only after the API contract is supplied and covers the resolver adapter, Room, DataStore, OkHttp resumable downloads, and Media3 playback. Native `libmp3lame`, `libFLAC`, and ID3v2.4 follow as separate media/export phases.

## Commit policy

The workspace is not a Git repository, and the user did not request repository initialization or commits. Do not initialize Git or create commits during this phase.
