package cn.qishui.tool.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import cn.qishui.tool.util.SourceLog
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import cn.qishui.tool.MainActivity
import cn.qishui.tool.domain.player.SoundEffectCommand
import cn.qishui.tool.media.reverb.RoomReverbProcessor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 正式播放的前台服务：后台播放、通知栏、锁屏、蓝牙控制都在这里。
 *
 * 和编辑页/解析页试听的 [cn.qishui.tool.domain.AudioPlayer] 是两套东西：
 * 那个播一次临时 WAV 就完事，这个管连续队列。两者互不干扰。
 *
 * 这里只管「建播放器 + 挂 MediaSession + 挂音效处理」，
 * 队列语义全在 [cn.qishui.tool.domain.player.QueueItem] 和
 * [cn.qishui.tool.data.media.PlaybackConnection]，不在服务端堆业务。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * 音效处理器活在这个对象的生命周期里。UI 通过自定义命令让它换预设，
     * 跨进程也拿不到实例，只能走 MediaSession。
     */
    private val reverbProcessor = RoomReverbProcessor()



    private companion object {
        /**
         * 音源后端大多会对浏览器 UA 做校验，ExoPlayer 默认 UA 会被拒。
         * 与 lx 的 `src/core/init/userApi/request.js` 里的 defaultHeaders 保持一致。
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(
            this,
            SoundEffectRenderersFactory(this, reverbProcessor),
        )
            // 必须显式允许跨协议重定向。
            //
            // 音源给的直链经常是跳转地址，比如长青源返回
            // http://yinyue.haitangw.net/kw/kw.php?...
            // 而它 301 到 https://yinyue.haitangw.net/kw/kw.php?...
            //
            // DefaultHttpDataSource 底层是 HttpURLConnection，**默认不跟跨协议重定向**，
            // 301 会被原样交给 Media3，判定非 2xx 就报 Source error。
            // 浏览器能自动跟随，所以「链接在浏览器能下、App 播不了」就是这个原因。
            // 同一台机器上 qdy 能播，是因为它给的是 CDN 直链、不重定向。
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(
                        this,
                        DefaultHttpDataSource.Factory()
                            .setAllowCrossProtocolRedirects(true)
                            .setDefaultRequestProperties(mapOf("User-Agent" to DESKTOP_UA)),
                    )
                )
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()

        // 播放失败必须留下完整原因链。系统日志里只有 Media3 自己打的
        // "Source error" 包装，cause 才是真相（DNS / TLS / 超时 / 解码）。
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                SourceLog.e("Playback", "播放失败 code=${error.errorCode}", error)
                var cause: Throwable? = error.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    SourceLog.e("Playback", "  cause[$depth] ${cause.javaClass.name}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(activityIntent())
            .setCallback(sessionCallback)
            .build()
    }

    private val sessionCallback = object : MediaSession.Callback {

        /**
         * 默认只放行标准会话命令，自定义命令要在这里显式加，
         * 否则 [MediaController.sendCustomCommand] 会被拒。
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SoundEffectCommand.SET_SOUND_EFFECT)
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SoundEffectCommand.ACTION_SET_SOUND_EFFECT) {
                reverbProcessor.setPreset(SoundEffectCommand.read(args))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App，但正在播就继续；没在播就自杀，别留个空服务
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {

        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
