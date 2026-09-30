package cn.qishui.tool.service

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import cn.qishui.tool.media.reverb.RoomReverbProcessor

/**
 * 往播放管线里挂音效处理。
 *
 * Media3 的 [DefaultAudioSink] 把自定义 AudioProcessor 放在静音跳过和变速之前，
 * 也就是拿原始采样率处理，这正是混响该待的位置。
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
