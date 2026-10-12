package com.siso.pixelisland

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Follows the active media sessions on the phone and reports the one that matters
 * (the one playing, otherwise a paused one). Needs notification-listener access.
 */
class MediaWatcher(
    private val context: Context,
    private val onChange: (MediaInfo?) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, IslandNotificationListener::class.java)

    private var controllers: List<MediaController> = emptyList()
    private var registered = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun start() {
        try {
            if (!registered) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                registered = true
            }
            attach(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Notification access has not been granted yet.
            onChange(null)
        }
    }

    fun refresh() {
        try {
            attach(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            onChange(null)
        }
    }

    fun stop() {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        if (registered) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (e: Exception) {
            }
            registered = false
        }
    }

    /** The controller whose buttons the island should drive. */
    fun controller(): MediaController? = best()

    private fun attach(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        controllers.forEach { it.registerCallback(callback, handler) }
        publish()
    }

    private fun best(): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull {
                val s = it.playbackState?.state
                s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING
            }

    private fun publish() {
        val c = best()
        if (c == null) {
            onChange(null)
            return
        }
        val md = c.metadata
        val st = c.playbackState

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val duration = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)

        onChange(
            MediaInfo(
                title = title,
                artist = artist,
                art = art,
                isPlaying = st?.state == PlaybackState.STATE_PLAYING,
                positionMs = (st?.position ?: 0L).coerceAtLeast(0L),
                durationMs = duration,
                updatedAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                speed = st?.playbackSpeed ?: 1f,
                openIntent = c.sessionActivity
            )
        )
    }
}
