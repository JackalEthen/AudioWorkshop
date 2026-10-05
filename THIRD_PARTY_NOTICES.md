# 第三方来源声明

本项目基于 [Apache License 2.0](LICENSE) 发布。
以下第三方项目或素材被用于本项目，版权归各自作者所有。

---

## lecoix/mica-music

- 来源：https://github.com/lecoix/mica-music
- 许可：Apache License 2.0
- 用途：播放器层的实现参考

借鉴内容（以 Apache-2.0 条款使用，保留版权声明）：

| 借鉴内容 | 本项目对应位置 |
|---|---|
| MediaSessionService 前台服务与通知栏结构 | `service/PlaybackService.kt` |
| 播放队列、自动切歌、播放模式 | `service/PlaybackService.kt` |
| 冷启动恢复队列与播放进度 | `service/PlaybackService.kt` |
| 播放页背景混合（主题色 / 封面渐变 / 封面模糊） | `ui/theme/`（阶段 2） |
| 封面主色提取与缓存 | `ui/theme/`（阶段 2） |
| 逐字歌词解析（行内逐字标签、偏移、一行多标签） | `feature/player/Lyrics*`（阶段 2） |
| EQ 面板交互（频段条 + 曲线图） | `feature/player/Equalizer*`（阶段 3） |
| 频谱条数据获取方式（AudioProcessor 取 PCM） | `media/`（阶段 3） |
| 长按歌曲操作菜单、播放队列面板的交互结构 | `feature/player/SongActionSheet.kt`、`QueueSheet.kt` |

说明： mica-music 的视觉语言为「极简直角」，本项目保留自身的圆角毛玻璃风格，
仅借鉴其结构与逻辑，未复制其设计 token。

---

## lx-music-mobile

- 来源：https://github.com/lx-music/lx-music-mobile
- 许可证：Apache License 2.0
- 用途：音源协议的事实标准

本项目在实现 lx 自定义音源协议时参考其源码，本项目独立实现，未复制其代码。

| 参考内容 | 本项目对应位置 |
|---|---|
| `userApi` 脚本契约（`request` / `musicUrl` / `lyric` / `pic` action、双向桥） | `assets/lx/user-api-preload.js`、`media/source/LxSourceEngine.kt` |
| linuxapi 加密（`AES-128-ECB/PKCS5`，固定密钥 `rFgB&h#%2?^eDg:Q`） | `data/search/SearchHttp.kt` 的 `encryptLinuxApi` |
| `musicInfo` 旧结构（`toOldMusicInfo`）与音质降级顺序 | `media/source/LxSourceResolver.kt` |
| 脚本 HTTP 的请求头与回包形状 | `media/source/HttpBodyParse.kt` |

lx-music-mobile 采用 Apache-2.0 许可证，保留其版权声明与许可条件。

---

## lx-music-desktop

- 来源：https://github.com/lx-music/lx-music-desktop
- 许可证：Apache License 2.0
- 用途：**播放器音效链的建模思路**

本项目播放器的实时音效链（EQ → 干湿并联 → 限幅）按其结构建模，
混响的 RT-60 取值也参照了常见声学参考值。本项目独立实现，未复制其代码。

| 参考内容 | 本项目对应位置 |
|---|---|
| 音效链拓扑：`source → EQ → 干路(mainGain) ∥ 湿路(sendGain) → 限幅 → 输出` | `domain/player/SoundEffectPreset.kt`、`feature/player/PlayerPanels.kt` |
| 干湿并联 + 末端限幅的混响建模 | `media/reverb/ReverbEngine.kt` |
| 预设的 RT-60 取值区间（小房间 0.3–0.6s，音乐厅 1.8–2.2s，教堂 4–8s） | `domain/player/SoundEffectPreset.kt` |
| 「磁性立体声」对应其声像旋转（`AudioPanner`）而非房间混响 | `domain/player/SoundEffectPreset.kt` 的 `MAGNETIC` |

与 lx-music-mobile 的区别：lx 桌面版混响用 15 个真实 IR wav 采样，
本项目按声学参数实时合成（`ReverbEngine`），素材不落地、无授权问题。

---

## jitwxs/163MusicLyrics

- 来源：https://github.com/jitwxs/163MusicLyrics
- 许可证：Apache License 2.0
- 用途：网易云歌词接口的请求参数参考

`NetEaseMusicNativeApi.GetLyric` 说明了取全量歌词需要哪几个参数
（`lv` / `kv` / `tv` / `rv` / `yv`），据此确定 `song/lyric` 的调用方式。
本项目未使用其代码，也未部署其桌面程序。

**注意其走的是 `weapi`（RSA + 双层 AES）；本项目走 `api/linux/forward`
代理，只需要 linuxapi 的 AES，不涉及 RSA。**

---

## amll-dev/amll-ttml-db

- 来源：https://github.com/amll-dev/amll-ttml-db
- 许可证：**CC0-1.0**（公共领域，无署名要求）
- 用途：网易云歌词的兜底来源

官方接口没有收录的冷门歌在这里可能有。仓库直接放文件，
按歌名 id 精确匹配 `ncm-lyrics/{id}.lrc`，不需要部署服务、Cookie 或解密。

本项目只取 `.lrc`，不取同目录的 `.yrc` / `.ttml`（逐字格式要另写解析器）。

---

## guohuiyuan/music-lib

- 来源：https://github.com/guohuiyuan/music-lib
- 许可证：AGPL-3.0
- 用途：**仅阅读**各平台请求格式，未复制任何代码

本项目用它确认了网易云 AES 密钥、QQ/酷狗/酷我接口的请求形状。
AGPL-3.0 具有传染性，**因此本项目没有复制其任何代码**，
所有实现均独立编写，仅参考协议事实。

若后续需要引入其代码，必须先评估 AGPL-3.0 对本项目授权的影响。

---

## PaulBatchelor/Soundpipe

- 来源：https://github.com/PaulBatchelor/Soundpipe
- 许可：MIT License, Copyright (c) 2020 Paul Batchelor
- 用途：实时混响的梳状滤波器算法

`media/reverb/RoomReverbProcessor.kt` 中的 `CombFilter` 移植自
`modules/comb.c`，只改了命名并去掉了 malloc。Soundpipe 那份本身提取自
Csound 的 comb 算子（Barry Vercoe / John ffitch, 1991, OOps/ugens6.c）。

移植的核心是它的增益公式：

```
coef = exp(ln(0.001) * looptime / revtime) = 0.001^(looptime/revtime)
```

梳状滤波器每 `looptime` 秒重复一次，所以 `revtime` 秒之后电平是
`coef^(revtime/looptime) = 0.001`，正好 -60dB —— 这就是 RT-60 的定义。
混响增益因此**不需要手调**，给定房间的混响时间就唯一确定了。

本项目在此基础上加了两处 Soundpipe 没有的东西：

1. 反馈回路里的一阶低通（阻尼）。Soundpipe 的 comb 没有，
   但真实房间对高频的吸收远强于低频，没有它所有房间听起来会一样亮。
   参见 Freeverb 论文（Esqueda/Daud/Nishikawa, DAFx-05）的 damped comb filter。
   低通必须单位直流增益，写成教科书的 `store = out*(1-d) + store*d`
   会让尾音每反射一次就被乘一遍 `(1-d)`，房间再大也起不来。
2. 预延迟、立体声展宽、电话带通与软限幅。

---

## 第三方依赖

### 编译进 APK 的原生源码（`third_party/`）

| 目录 | 上游 | 许可 | 用途 |
|---|---|---|---|
| `third_party/lame` | https://lame.sourceforge.io/ | LGPL-2.0 | MP3 编码 |
| `third_party/rnnoise` | https://github.com/xiph/rnnoise | BSD-3-Clause | 人声降噪（RNNoise） |
| `third_party/signalsmith-stretch` | https://github.com/SignalsmithAudio/TimeStretch | MIT | 变速变调（WSOLA） |
| `third_party/signalsmith-linear` | https://github.com/SignalsmithAudio/DSP | MIT | TimeStretch 的依赖 |
| `third_party/signalsmith-dsp` | https://github.com/SignalsmithAudio/DSP | MIT | 滤波器（EQ 等） |
| `third_party/libebur128` | https://github.com/jiixyj/libebur128 | BSD-2-Clause | LUFS 测量 |

全部编进**同一个** `libmp3lame_jni.so`（`audiofx/src/main/cpp/CMakeLists.txt` 的单一
`add_library`）。分开成多个 `.so` 会让每个 JNI 桥的首次调用抛
`UnsatisfiedLinkError` —— 因为 `loadLibrary` 是懒加载的。

> LAME 为 LGPL-2.0，以**动态链接**方式使用（`System.loadLibrary` 加载独立 `.so`），
> 满足 LGPL 对「用户可替换该库」的要求。

### JVM 侧依赖

| 依赖 | 许可 |
|---|---|
| AndroidX / Jetpack（Compose、Room、Media3、Navigation、DataStore、DocumentFile） | Apache-2.0 |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | Apache-2.0 |
| OkHttp | Apache-2.0 |
| Coil | Apache-2.0 |
| `wang.harlon.quickjs:wrapper-android` 2.4.5（跑音源脚本，引入 `libquickjs-android-wrapper.so`） | **待确认** ⚠️ |

> ⚠️ QuickJS wrapper 是通过 Maven 引入的预编译 `.so`，仓库内不含其 LICENSE 文件，
> 无法在本仓库内核实许可。上游 QuickJS 本体（Bellard）及其 Android 移植的许可需另行确认。
> **分发 APK 前请核实此项。**

注：`third_party/signalsmith-dsp` 当前只引入了 `filters.h`（EQ）等模块，
**不包含 `reverb.h`**，所以本项目的混响没有用到它 —— 混响是自己实现的
（`media/reverb/RoomReverbProcessor.kt`，算法移植自 Soundpipe）。

