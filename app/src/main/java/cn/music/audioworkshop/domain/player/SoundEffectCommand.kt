package cn.music.audioworkshop.domain.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * UI 和 [cn.music.audioworkshop.service.PlaybackService] 之间约定：切音效预设。
 *
 * 音效处理器长在服务进程的播放管线里，UI 碰不到，只能走 Media3 自定义命令。
 * 预设名用枚举名传，字符串短且两边一致，比序号稳。
 */
object SoundEffectCommand {

    const val ACTION_SET_SOUND_EFFECT = "cn.music.audioworkshop.SET_SOUND_EFFECT"

    private const val EXTRA_PRESET = "preset"

    val SET_SOUND_EFFECT: SessionCommand = SessionCommand(ACTION_SET_SOUND_EFFECT, Bundle.EMPTY)

    fun argsOf(preset: SoundEffectPreset): Bundle = Bundle().apply {
        putString(EXTRA_PRESET, preset.name)
    }

    /** 名字对不上就当没开音效，绝不因此崩。 */
    fun read(args: Bundle): SoundEffectPreset {
        val name = args.getString(EXTRA_PRESET) ?: return SoundEffectPreset.NONE
        return runCatching { SoundEffectPreset.valueOf(name) }.getOrDefault(SoundEffectPreset.NONE)
    }
}
