package cn.music.audioworkshop.service

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import cn.music.audioworkshop.media.reverb.RoomReverbProcessor

/**
 * 带播放音效的渲染器工厂。
 *
 * 音效全部在 PCM 域自己算（[RoomReverbProcessor]），不用系统原生效果。
 *
 * **走过一段弯路，留个记录：** 曾经改成挂 `android.media.audiofx.PresetReverb`
 * （拿 AudioTrack 的 audioSessionId，Media3 用 `setAudioTrackProvider` 能拿到）。
 * 但 `PresetReverb` 只认 7 档公开预设，而且**这台设备上它建出来也没区别** ——
 * 更糟的是当时把 PCM 关掉去交给它，结果五个预设里四个彻底没声音。
 * 原生效果的音质和可用性不由我们决定，不如自己算。
 */
class SoundEffectRenderersFactory(
    context: Context,
    private val reverbProcessor: RoomReverbProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf<AudioProcessor>(reverbProcessor))
        .build()
}
