# 播放器方案

> 参考 [lecoix/mica-music](https://github.com/lecoix/mica-music)（Apache-2.0）的播放层、队列、歌单思路与播放页结构，
> 视觉语言保留本项目现有的圆角毛玻璃风格（`QishuiCardShape` / `FrostedBox`）。
> 音源（lx-music 自定义源）不引入 mica 的实现，自建。

## 1. 页面结构

底栏新增第 4 个 Tab「播放器」。所有内容放在这一个路由页里。

```
┌──────────────────────────────────┐
│  歌曲列表           [🔍] [☆]      │  ← 标题栏（QishuiTopBar.actionContent）
├──────────────────────────────────┤
│  [封面] 歌名              [▶] [⏭] │  ← MiniPlayer，吸顶在标题下方
│         歌手                       │    无歌时保留空条，不显示内容
├──────────────────────────────────┤
│  封面 歌名 歌手  时长              │  ← 歌曲列表
│  ...                              │    贯穿到底，不为底栏留白
│  ...                              │    （底栏是 alpha 0.98 毛玻璃浮层）
│  ...                              │
├──────────────────────────────────┤
│   解析   编辑   播放器   设置       │  ← FloatingBottomBar
└──────────────────────────────────┘
        点 MiniPlayer / 点列表行 ↓
┌──────────────────────────────────┐
│  ← 收起                          │
│  背景：主题色/封面渐变/封面模糊      │  ← 全屏播放页，同页覆盖 + 下滑收起
│  ┌──────────────────────────┐    │
│  │   大封面（圆角）           │    │
│  │   点封面 ⇄ 切换歌词        │    │
│  └──────────────────────────┘    │
│  歌名  歌手                       │
│  ●────────── 进度条 ──────────   │
│  ⏮  ▶/⏸  ⏭  🔁  ☰  均衡器        │
└──────────────────────────────────┘
```

**为什么 MiniPlayer 吸顶而不吸底**：它在标题和列表之间，天然不与底部导航栏重叠。
**为什么列表不留白**：底栏是毛玻璃浮层，列表内容透过去形成层次感，内容可划到底栏之下。

### 1.1 收藏的位置

不新增页面、不加 Tab 栏：

| 动作 | 位置 | 行为 |
|---|---|---|
| 看收藏 | 标题栏 ☆ 图标（搜索图标左侧） | 原地切筛选态，标题变「收藏的歌曲」、☆ 高亮；再点还原 |
| 改收藏 | 歌曲行长按 → 菜单 | 「收藏 / 取消收藏」，菜单样式复用 `DeleteSelectionSheet` |

不做「每行一个星标」：会让列表变挤，而且仍缺一个「查看我的收藏」入口，那个入口最终还是要落在标题栏。

### 1.2 搜索

搜索是**独立二级路由** `player/search`，由标题栏 🔍 进入，不是 Tab。

关键设计：**搜索结果与歌曲列表共用同一个 `SongRow` 和同一套点击处理**。
搜索只是 `Song` 的另一个数据来源，阶段 4 接上音源后 UI 零改动。

阶段 1 先建壳和路由，进去提示音源功能开发中。

## 2. 播放队列语义

没有歌单，队列是**手工累积的播放列表**。三种操作：

| 操作 | 行为 | Media3 实现 |
|---|---|---|
| 长按 → 下一首播放 | 插到当前播放曲的下一位，不打断当前播放 | `addMediaItem(currentIndex + 1, item)` |
| 直接点击 | 插到下一位，**并立即跳过去播放** | `addMediaItem(...)` → `seekTo(index, 0)` → `play()` |
| 清空队列 | 一键清空 | 队列面板右上角 |

配套规则：

- **队列上限 200 首**，超出后从最早的非当前项开始丢弃。
- **允许重复入队**（点歌台语义），UI 给「已添加」提示。
- 队列是攒出来的，因此必须有一键清空入口。

## 3. 试听 ≠ 播放：两个播放器并存

现有 `AudioPlayer`（`data/media/Media3AudioPlayer.kt`）是**单文件语义**——`loadFile` 直接 `setMediaItem` 覆盖。
编辑页的「试听」播的是临时渲染的 WAV，语义与正式播放不同，**不可合并**。

| | `AudioPlayer`（现有） | `PlaybackService`（新增） |
|---|---|---|
| 语义 | 单文件预览试听 | 正式播放、连续队列 |
| 位置 | 进程内单例 | Media3 `MediaSessionService` 前台服务 |
| 消费者 | 解析页 / 编辑页 / 音效页 | 播放器页 |
| 本次改动 | **不动** | 新建 |

## 4. 本地歌与 lx 音源的统一

不新建表，给 `source_tracks` 加两列，让两种来源在**同一张表**，歌曲列表一个查询搞定：

| 列 | 作用 |
|---|---|
| `source_code` | `local` / `kw` / `kg` / `tx` / `wy` / `mg` |
| `platform_song_id` | 音源求 URL 必需的 `songmid` / `hash` |

播放地址解析留一个接缝，**阶段 1 就要留好**：

```kotlin
sealed interface UrlResolver {
    suspend fun resolve(song: Song, quality: Quality): Uri
}
class LocalResolver : UrlResolver            // 阶段 1：直接给 local_path
class LxSourceResolver : UrlResolver         // 阶段 4：向 JS 脚本求直链
```

播放链路只认这个接口。阶段 4 只加一个实现类，**播放层不动**。

## 5. 播放页视觉

### 5.1 mica 的 5 种背景

官方 README 列 5 种，**代码里只确认到 3 种**：

| 名称 | 实现 | 状态 |
|---|---|---|
| 主题色 | 竖向渐变 `gradientStart → gradientEnd`，用应用主题色，不跟封面走 | 已确认 `PlayerLowerBackgroundMode.THEME` |
| 封面渐变 | 封面主色 → `accentuateCover()` 提饱和 → 以封面**下边缘为圆心**的径向渐变 + 竖向渐变叠加，色标分 junction / peak / hold | 已确认 `ARTWORK_GRADIENT` |
| 封面模糊 | 整张封面高斯模糊铺满 | 已确认 `COVER_GLOW` |
| 流光溢彩 | 动态流光 | **暂不实现**，后续阶段 |
| 星图 | 星点背景 | **暂不实现**，后续阶段 |

封面样式取 mica 的「标准」「粒子封面」两种。粒子封面可参考
`ui/screens/player/CoverFlowMath.kt` + `CoverFlowRails.kt` + `CoverGestureCoordinator.kt`。

### 5.2 性能闸

粒子封面与频谱条都在封面区域抢同一块像素，**互斥**，由设置控制。
连续 3 秒 < 45fps 自动关闭粒子。频谱走 `Choreographer` 帧驱动，不起独立协程。

### 5.3 频谱的数据来源：必须走 AudioProcessor

| 方案 | 权限 | 结论 |
|---|---|---|
| `Visualizer` API | 需 `RECORD_AUDIO`（麦克风） | ✗ 隐私敏感，用户看到「录音」权限会警惕，审核难过 |
| `AudioProcessor` 取播放链路 PCM | 无需权限 | ✓ 采用（mica 同做法） |

频谱与 EQ 共用同一个 `AudioProcessor`：一个出频段能量，一个改频段增益。

## 6. EQ 与音效

**实时，不保存**。参数跟随播放器，不按歌存表。

两条实现路径：

- **A. Media3 内置 `Equalizer`**（`androidx.media3:media3-effect`）——10 段标准 Graphic EQ，
  零开发成本，`ExoPlayer.setEqualizer()` 直接挂。**阶段 3 采用。**
- **B. 把 `audiofx` 的 C++ 效果链接成 Media3 `AudioProcessor`**——能复用已有 26 个效果，
  但 `audiofx` 现在是离线整段处理设计（`PcmBuffer` 进 / 出），改成实时流式等于重写数据模型。
  **作为阶段 3 的可选项单独评估。**

UI 参考 mica 的 `EqualizerBandBar.kt` + `EqualizerCurveChart.kt`，风格换成我们的圆角。

## 7. 从 mica 搬什么

**直接搬（纯逻辑，不影响外观）**
- MediaSessionService 结构
- 队列 / 自动切歌 / 播放模式（顺序·单曲·随机）
- 封面缓存 + 主色提取
- 逐字歌词解析（行内逐字标签 / 偏移 / 一行多标签）
- 冷启动恢复队列和进度

**搬结构、换皮肤**
- `NowPlayingScreen` → 只留布局骨架，封面换 `QishuiCardShape` 圆角，背景换 `FrostedBox` 毛玻璃
- `PlayerLowerPanel` → 换成 `PrimaryButton` / `SecondaryButton` 体系
- `MiniPlayer` → 用 `FloatingBottomBar` 同材质悬浮条
- `SongRow` → 抽公共组件（现在项目里 5 处各自 `private`）
- `SongActionMenuSheet` / `PlaybackQueueSheet` / `LyricsDisplay` → 参考交互，改视觉

**不搬**
- 极简直角设计 token、拍立得/复古立体/平行封面带
- Glance 桌面小组件
- ALAC / DSF / APE + FFmpeg + USB 独占
- Navidrome / OpenSubsonic / WebDAV / SMB 远端库
- 播放统计热力图

## 8. 依赖与权限

**加 2 个依赖**
```
androidx.media3:media3-session:1.5.1   // 现有 media3 1.5.1 只缺这个
io.coil-kt:coil-compose                  // 封面加载，现在全靠手搓 BitmapFactory
```

**加 3 个权限**（本地音乐用文件选择器逐个导入，不需要媒体库权限）
```
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK
android.permission.POST_NOTIFICATIONS
```
外加 `<service android:foregroundServiceType="mediaPlayback" android:exported="false" />`

**加 LICENSE（Apache-2.0）+ THIRD_PARTY_NOTICES.md** —— 引入 Apache 代码前必须先有许可声明和归属记录。

## 9. 数据层

`MIGRATION_8_9`：
```sql
ALTER TABLE source_tracks ADD COLUMN source_code TEXT;
ALTER TABLE source_tracks ADD COLUMN platform_song_id TEXT;
CREATE TABLE favorites (song_id TEXT NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL);
```

`SourceOrigin` 枚举扩展 `REMOTE_SOURCE`。
注册点唯一：`app/AppContainer.kt`。

**改测试**：`AppDestinationTest.kt` 断言了 `listOf("解析","编辑","设置")`，加第 4 个 tab 会挂，同步改。

## 10. 阶段划分

### 阶段 1：播放器地基 + 列表页
| # | 事项 |
|---|---|
| 1 | `LICENSE`(Apache-2.0) + `THIRD_PARTY_NOTICES.md` |
| 2 | 依赖 `media3-session` + `coil-compose` |
| 3 | 3 权限 + Service 声明 |
| 4 | `PlaybackService` + 队列 + 通知栏 |
| 5 | `PlaybackConnection` + `UrlResolver` 接缝 |
| 6 | `MIGRATION_8_9` |
| 7 | `SongRow` 公共组件 |
| 8 | `PlayerHomeScreen` + 标题栏 + 列表 |
| 9 | `MiniPlayer` |
| 10 | `SongActionSheet` 长按菜单 |
| 11 | `SearchScreen` 壳 + 「播放器音源」设置页（音质档位） |

### 阶段 2：全屏播放页 + 视觉
| # | 事项 |
|---|---|
| 12 | `PlayerSheet`（App 层覆盖 + 下滑收起） |
| 13 | 歌词区 + 点封面区切换 |
| 14 | 背景模式（3 种） |
| 15 | 封面样式：标准 + 粒子封面 |
| 16 | 队列面板 |

### 阶段 3：EQ + 音效 + 频谱
| # | 事项 |
|---|---|
| 17 | `AudioProcessor`：频段能量分析 + 频段增益 |
| 18 | EQ 面板（10 段 + 曲线图） |
| 19 | 音效面板 |
| 20 | 「播放器外观」设置组（背景 / 封面 / 频谱开关 / 掉帧降级） |
| 21 | 评估 `audiofx` C++ 实时化 |

### 阶段 4：lx 音源 + 搜索
| # | 事项 |
|---|---|
| 22 | WebView JS 沙箱 + `lx` 桥接 |
| 23 | 音源脚本导入/管理（并入「播放器音源」设置页） |
| 24 | `LxSourceResolver` 接入 |
| 25 | 搜索：移植 lx musicSdk + esbuild 打包，点亮 🔍 |
