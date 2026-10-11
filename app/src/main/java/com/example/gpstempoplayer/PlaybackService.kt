package com.example.gpstempoplayer

import android.content.Intent
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player independently from the Activity. Media3 publishes system media
 * controls/notification for this MediaSessionService while playback is active.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var playerListener: Player.Listener? = null

    override fun onCreate() {
        super.onCreate()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audio, true) // handles audio focus changes
            .setHandleAudioBecomingNoisy(true) // pause when headphones disconnect
            .build()

        session = MediaSession.Builder(this, player).build()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:playback").apply {
                setReferenceCounted(false)
            }

        playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    try {
                        if (wakeLock?.isHeld != true) wakeLock?.acquire(3 * 60 * 60 * 1000L)
                    } catch (_: RuntimeException) { }
                } else {
                    releaseWakeLock()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    if (playbackState == Player.STATE_IDLE) releaseWakeLock()
                }
            }
        }
        player.addListener(playerListener!!)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // Do not stop the service just because the app task was swiped away.
        if (player == null || (!player.playWhenReady && player.mediaItemCount == 0)) stopSelf()
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: RuntimeException) { }
    }

    override fun onDestroy() {
        releaseWakeLock()
        session?.player?.let { player ->
            playerListener?.let { player.removeListener(it) }
            player.release()
        }
        session?.release()
        session = null
        playerListener = null
        wakeLock = null
        super.onDestroy()
    }
}
