<div align="center">

# 音频工坊 · AudioWorkshop

**An Android audio toolbox that can get, trim, tune, export — and play music properly**

<p>
  <a href="README-zh.md"><img src="https://img.shields.io/badge/README-中文-lightgrey?style=flat-square" alt="Chinese"></a>
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

## What it is

**An audio toolbox plus a music player.**

- 🎛️ **19 audio tools** — trim, time-stretch, denoise, reverb, convert… export the result
- 🎧 **A player** — library, queue, 10 realtime sound-effect presets, synced lyrics, background playback
- 🔗 **Also resolves links** — paste a share URL to pull media down (a side feature, entirely optional)

All audio processing runs on-device; audio never leaves the phone. **No bundled library, no bundled parse endpoints, no accounts.**

---

## 🎛️ The toolbox

### Cutting

| Tool | Notes |
|---|---|
| **Trim** | Waveform selection with adjustable fade ramps |
| **Join** | Concatenate tracks, with crossfade transitions and head/tail silence |
| **Speed & pitch** | Signalsmith Stretch (WSOLA) — change speed without shifting pitch |
| **Fade in/out** | Linear or equal-power curves, sample-accurate length |
| **Gain** | dB boost with optional soft limiting against clipping |
| **LRC editor** | Edit line timing and text, export with timestamps |
| **Metadata** | Title / artist / album / artwork / lyrics |

### Effects

| Tool | Notes |
|---|---|
| **Loudness normalization** | EBU R128 LUFS target — even out tracks of different levels |
| **Equalizer** | 8-band graphic EQ (125 Hz – 16 kHz) |
| **Denoise** | RNNoise for speech, band limiting for general material |
| **Repair** | Recover clipped samples |
| **Stereo orbit** | Sweeps the signal across the stereo field over time |
| **Stereo split / compose** | Extract channels, or merge two files into a stereo pair |
| **Echo / choir / reverb** | Freeverb-style reverb, comb-filter RT-60 |

### Formats

- **Conversion** — MP3 / WAV / FLAC
- **Video audio extraction** — pull the audio track out of a video
- **Tag writing** — ID3v2.4, including artwork and synced lyrics (SYLT)

### More features on the way...

---

## 🎧 The player

The perfect companion to the toolbox.

**Library & queue**

- Library management, favorites, and a full playback queue
- Three repeat modes: sequential / one / all
- MediaSession foreground service — lock screen, notification shade, headset buttons
- Queue and playback position survive a cold start

**Realtime sound effects**

Switch between presets on an EQ + reverb chain **while playing** — nothing to re-export:

```
source → EQ → dry(mainGain) ─────────────────┐
              spatial → wet(sendGain) ────┴→ limiter → out
```

Ten presets, separated across **three dimensions at once** (tail / damping / scale) so they're genuinely distinguishable by ear:

| Preset | Tail | Character |
|---|---|---|
| Bathroom | very short | tiled small space |
| Indoor | short | neutral reference |
| Restaurant | medium | noisy mid-sized room |
| Cinema | long | thick carpet seating, highs die first |
| Concert hall | long | crowd absorption, enveloping |
| Hall | long | hard surfaces, bright tail |
| Cathedral | very long | stone walls, tail drags until it scatters |
| Telephone | — | narrowband handset, raspy |
| Magnetic stereo | almost none | slow orbit — **not a room** |
| Original | — | bypass the chain |

> Cinema and Hall are the easiest pair to confuse — deliberately built with the **same tail length, one dull one bright**, so they don't rely on length alone.
>
> Reverb isn't sampled IR. It's synthesized live from acoustic parameters (RT-60 drawn from common room-acoustics references). No assets on disk, no licensing question.

**Synced lyrics**

- Understands both standard LRC and QRC word-level format
- Lyrics from the NetEase official API, with a fallback database for obscure tracks

**Bring your own lx music source**

- Import an lx custom-source script to get music search and favorites

---

## Link resolving

Paste share text → extract the link → resolve to a media URL → download.

- Audio, video, and images handled separately — a gallery source returning several images downloads all of them in one tap
- Resumable downloads, configurable concurrency
- Two pipelines, both configured by you:
  - **Parse sources** — HTTP endpoints you supply; write a mapping if the field names don't line up
  - **Music sources** — lx custom-source scripts (JS) you import, for search and playback URLs

**No parse endpoint or music library is built in.** You choose the sources.

---

## Preview = export

The thing this project cares most about: **what you hear is what you export.**

Preview rendering and export share one timeline calculation (`EditTimelineCalculator` / `ExportPlanner`), one effect ordering (`ExportEngine.applyEffects`), and one lyric remapping (`LyricTimelineMapper`).

- Under speed changes, lyric timestamps are divided by the same factor — no drift, line by line
- After a trim or join, lyrics are remapped to follow the retained intervals
- Effect order is item-for-item aligned between preview and export; changing one means changing the other

---

## Stack

```
Kotlin 2.0.21 · Jetpack Compose (Material 3) · Room · DataStore
OkHttp · Coil · Media3 (ExoPlayer + MediaSessionService)
AGP 8.11.2 · NDK 28 · CMake · JDK 17 target · minSdk 26 / targetSdk 36
```

Audio processing is a hand-written PCM pipeline plus JNI:

| Component | License | Role |
|---|---|---|
| [LAME](https://lame.sourceforge.io/) | LGPL-2.0 | MP3 encoding |
| [RNNoise](https://github.com/xiph/rnnoise) | BSD-3-Clause | Speech denoising |
| [Signalsmith Stretch](https://github.com/SignalsmithAudio/TimeStretch) | MIT | Time-stretch / pitch shift |
| [Signalsmith DSP](https://github.com/SignalsmithAudio/DSP) | MIT | Filters (EQ, etc.) |
| [libebur128](https://github.com/jiixyj/libebur128) | BSD-2-Clause | LUFS measurement |

All of it compiles into a **single** `libmp3lame_jni.so` — separate `loadLibrary` calls mean every bridge throws `UnsatisfiedLinkError` on first use, because `loadLibrary` is lazy.

---

## Build

```bash
git clone https://github.com/JackalEthen/AudioWorkshop.git
cd qishui

# Requires JDK 21, Android SDK (compileSdk 36), NDK 28.2.13676358
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 641 unit tests
./gradlew installDebug
```

> **The NDK is mandatory.** The `audiofx` module compiles LAME, RNNoise, and Signalsmith; without the native toolchain the build fails outright.

---

## Acknowledgements

This project stands on the shoulders of several excellent open-source projects.

| Project | License | What we took from it |
|---|---|---|
| [lx-music/lx-music-mobile](https://github.com/lx-music/lx-music-mobile) | Apache-2.0 | **The de-facto standard for the lx custom-source protocol** — `userApi` script contract, the bidirectional bridge, linuxapi AES, script HTTP response shape |
| [lx-music/lx-music-desktop](https://github.com/lx-music/lx-music-desktop) | Apache-2.0 | **The player's sound-effect chain topology** — dry/wet parallel structure, preset RT-60 values |
| [lecoix/mica-music](https://github.com/lecoix/mica-music) | Apache-2.0 | MediaSessionService foreground structure, queue and cold-start restore, long-press action menu interactions |
| [jitwxs/163MusicLyrics](https://github.com/jitwxs/163MusicLyrics) | Apache-2.0 | NetEase lyric API parameters (`lv`/`kv`/`tv`/`rv`/`yv`) |
| [amll-dev/amll-ttml-db](https://github.com/amll-dev/amll-ttml-db) | CC0-1.0 | Fallback lyric database for obscure tracks |
| [PaulBatchelor/Soundpipe](https://github.com/PaulBatchelor/Soundpipe) | MIT | Comb filter for reverb (`CombFilter`, ported from `modules/comb.c`, RT-60 gain formula) |

> `guohuiyuan/music-lib` (AGPL-3.0) was used **for reading only**, to confirm protocol facts across platforms. No code was copied — AGPL's copyleft makes that a hard constraint.

The full scope of borrowing, license obligations, and an item-by-item mapping table live in **[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)**.

---

## License

Released under the [Apache License 2.0](LICENSE).

```
Sources under third_party/ that get compiled into the APK retain their original
licenses. LAME is LGPL-2.0, used as a dynamically linked library.
```

---

## Disclaimer

This tool **ships no** music, audio, or video content.

- No bundled library
- No bundled parse endpoints
- No paywall or DRM circumvention

What you download, and whether you may, depends entirely on the sources you configure and on the law where you live. Please respect copyright.
