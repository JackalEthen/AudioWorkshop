<div align="center">

# 音频工坊 · AudioWorkshop

**一个能获取，能剪、能调、能导出，也能好好听歌的 Android 音频工具箱**

<p>
  <a href="README.md"><img src="https://img.shields.io/badge/README-English-lightgrey?style=flat-square" alt="English"></a>
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple?style=flat-square" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack%20Compose-64ff00?style=flat-square" alt="Compose">
  <img src="https://img.shields.io/badge/minSdk-26-green?style=flat-square" alt="minSdk">
</p>

<p>
  <img src="https://img.shields.io/badge/build-passing-brightgreen?style=flat-square" alt="build">
  <img src="https://img.shields.io/badge/tests-641%20passed-brightgreen?style=flat-square" alt="tests">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21%20%7C%20AGP-8.11.2%20%7C%20SDK%2036-informational?style=flat-square" alt="stack">
  <img src="https://img.shields.io/badge/native-LAME%20%2B%20RNNoise%20%2B%20Signalsmith-informational?style=flat-square" alt="native">
</p>

</div>

---

## 这是什么

**一个音频工具箱 + 一个播放器。**

- 🎛️ **19 个音频工具** — 剪切、变速、降噪、混响、格式转换…改完直接导出成品
- 🎧 **一个播放器** — 曲库、队列、10 种实时音效预设、逐字歌词、后台播放
- 🔗 **顺手能解析链接** — 粘个分享链接把音视频拉下来（附带功能，非必需）

所有音频处理都在设备上完成，音频数据不出手机。**不内置曲库，不内置解析接口，没有账号。**

---

## 🎛️ 音频工具箱

### 剪辑

| 工具 | 说明 |
|---|---|
| **剪切** | 波形拖拽选区，首尾淡入淡出斜坡可调 |
| **合成** | 多首拼接，可设接缝交叉过渡与首尾静音 |
| **变速变调** | Signalsmith Stretch（WSOLA）—— 变速不变调 |
| **淡入淡出** | 线性 / 等功率曲线，时长精确到采样 |
| **修改音量** | dB 增益，可开软限幅防削波 |
| **Lrc 歌词编辑** | 逐行改时间与文本，导出带时间轴 |
| **修改音乐信息** | 标题 / 歌手 / 专辑 / 封面 / 歌词 |

### 音效

| 工具 | 说明 |
|---|---|
| **响度标准化** | EBU R128 LUFS 目标响度，多首歌音量拉齐 |
| **均衡器** | 8 段图示均衡（125 Hz – 16 kHz） |
| **降噪** | RNNoise（人声）/ 频段压制（通用） |
| **音频修复** | 恢复被削波污染的样本 |
| **立体声环绕** | 信号随时间走过半圈 |
| **立体声分离 / 合成** | 左右声道拆分 / 两个文件合成立体声 |
| **回声 / 合唱 / 混响** | Freeverb 风格混响，梳状滤波器定 RT-60 |

### 格式

- **格式转换** — MP3 / WAV / FLAC
- **视频提取音频** — 从视频抽音轨
- **标签写入** — ID3v2.4，含封面与逐字歌词（SYLT）

### 更多功能正在赶来...

---

## 🎧 播放器

这个项目的最佳拍档。

**曲库与队列**

- 曲库管理、收藏、播放队列（28 处队列操作）
- 三种播放模式：顺序 / 单曲循环 / 全部循环
- MediaSession 前台服务 —— 锁屏、通知栏、耳机按键都能控
- 冷启动恢复队列与播放进度

**实时音效链**

播放时可在 EQ + 混响链上实时切换预设，**不需要重新导出**：

```
source → EQ → 干路(mainGain) ─────────────┐
              空间感 → 湿路(sendGain) ─┴→ 限幅 → 输出
```

10 个预设，靠**尾音 / 阻尼 / 尺度**三个维度一起拉开，耳朵能明确分辨：

| 预设 | 尾音 | 特征 |
|---|---|---|
| 卫生间 | 极短 | 瓷砖小空间 |
| 室内 | 短 | 中性参照，日常听感 |
| 餐厅 | 中 | 嘈杂中等混响 |
| 电影院 | 中长 | 厚地毯座椅，高频先死 |
| 演唱会 | 中长 | 满场观众吸声，包裹感强 |
| 大厅 | 长 | 硬质墙面，尾音透亮 |
| 教堂 | 极长 | 石壁，尾巴拖到散掉 |
| 电话 | — | 听筒窄带，沙哑 |
| 磁性立体声 | 几乎无 | 声场缓慢环绕，**不是房间** |
| 原声 | — | 关掉音效链 |

> 「电影院」和「大厅」是最容易混的一对 —— 特意做成**尾音同样长、一个闷一个亮**，不靠长短区分。
>
> 混响不是采样 IR，而是按声学参数实时合成（RT-60 取自常见声学参考值）。素材不落地，没有授权问题。

**逐字歌词**

- 标准 LRC 与 QRC 逐字都认
- 歌词来自网易云官方接口，冷门歌有兜底词库

**支持导入lx自定义音源脚本**

- 支持音乐搜索和收藏

---

## 链接解析

粘贴分享文本 → 自动提取链接 → 解析成媒体地址 → 下载。

- 音频 / 视频 / 图片分别处理，图文源一次返回多张图时可一次性全下
- 断点续传、并发数可调
- 两条链路，都由你自己配置：
  - **解析源** — 你填的 HTTP 接口，字段名对不上就写映射
  - **音乐源** — 你导入的 lx 自定义源脚本（JS），供搜索与取播放地址

**不内置任何解析接口或曲库。** 用哪个源，你说了算。

---

## 预览即导出

这个项目最在意的一件事：**你听到的就是你导出的。**

预览渲染和导出共用同一套时间轴计算（`EditTimelineCalculator` / `ExportPlanner`）、同一套效果顺序（`ExportEngine.applyEffects`）、同一套歌词重映射（`LyricTimelineMapper`）。

- 变速时歌词时间戳同步除以倍率，不会逐句漂移
- 剪切 / 拼接后歌词跟随保留区间重排
- 效果顺序在预览与导出之间逐项对齐，改一处必须改另一处

---

## 技术栈

```
Kotlin 2.0.21 · Jetpack Compose (Material 3) · Room · DataStore
OkHttp · Coil · Media3 (ExoPlayer + MediaSessionService)
AGP 8.11.2 · NDK 28 · CMake · JDK 17 target · minSdk 26 / targetSdk 36
```

音频处理是自研的 PCM 流水线 + JNI：

| 组件 | 许可 | 用途 |
|---|---|---|
| [LAME](https://lame.sourceforge.io/) | LGPL-2.0 | MP3 编码 |
| [RNNoise](https://github.com/xiph/rnnoise) | BSD-3-Clause | 人声降噪 |
| [Signalsmith Stretch](https://github.com/SignalsmithAudio/TimeStretch) | MIT | 变速变调 |
| [Signalsmith DSP](https://github.com/SignalsmithAudio/DSP) | MIT | 滤波器（EQ 等） |
| [libebur128](https://github.com/jiixyj/libebur128) | BSD-2-Clause | LUFS 测量 |

全部编进**一个** `libmp3lame_jni.so` —— 分开 `loadLibrary` 会让每个桥的首次调用抛 `UnsatisfiedLinkError`。

---

## 构建

```bash
git clone https://github.com/JackalEthen/AudioWorkshop.git
cd qishui

# 需要 JDK 21 + Android SDK (compileSdk 36) + NDK 28.2.13676358
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 641 个单元测试
./gradlew installDebug
```

> **NDK 是必需的。** `audiofx` 模块编译 LAME / RNNoise / Signalsmith，没有 native 工具链会直接失败。

---

## 致谢

本项目站在几个优秀开源项目的肩膀上。

| 项目 | 许可 | 借鉴了什么 |
|---|---|---|
| [lx-music/lx-music-mobile](https://github.com/lx-music/lx-music-mobile) | Apache-2.0 | **lx 自定义音源协议的事实标准** —— `userApi` 脚本契约、双向桥、linuxapi AES、脚本 HTTP 回包形状 |
| [lx-music/lx-music-desktop](https://github.com/lx-music/lx-music-desktop) | Apache-2.0 | **播放器音效链的建模思路** —— 干路/湿路结构、预设的 RT-60 取值 |
| [lecoix/mica-music](https://github.com/lecoix/mica-music) | Apache-2.0 | MediaSessionService 前台服务结构、播放队列与冷启动恢复、长按操作菜单交互 |
| [jitwxs/163MusicLyrics](https://github.com/jitwxs/163MusicLyrics) | Apache-2.0 | 网易云歌词接口的请求参数（`lv`/`kv`/`tv`/`rv`/`yv`） |
| [amll-dev/amll-ttml-db](https://github.com/amll-dev/amll-ttml-db) | CC0-1.0 | 冷门歌曲词库的兜底来源 |
| [PaulBatchelor/Soundpipe](https://github.com/PaulBatchelor/Soundpipe) | MIT | 混响梳状滤波器（`CombFilter`，移植自 `modules/comb.c`，RT-60 增益公式） |

> `guohuiyuan/music-lib`（AGPL-3.0）仅用于**阅读**各平台请求格式以确认协议事实，未复制任何代码 —— AGPL 的传染性使这成为硬约束。

完整的借鉴范围、许可证义务与逐项对照表见 **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**。

---

## 许可

本项目基于 [Apache License 2.0](LICENSE) 发布。

```
third_party/ 下编译进 APK 的源码各自遵循其原始许可（LAME 为 LGPL-2.0，
以动态链接方式使用）。
```

---

## 声明

本工具**不提供**任何音乐、音频或视频内容。

- 不内置曲库
- 不内置解析接口
- 不绕过任何平台的付费墙或 DRM

你用它下载什么、下载得到什么，取决于你自己配置的源，以及你自己的所在地法律。请遵守版权法。
