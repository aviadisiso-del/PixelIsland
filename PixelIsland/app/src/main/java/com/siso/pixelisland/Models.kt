package com.siso.pixelisland

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/** What the island is currently showing. */
enum class IslandShape { IDLE, COMPACT, EXPANDED }

/** Which content the island is showing. */
enum class Kind { NONE, MEDIA, NOTIF, CHARGE }

/** Camera cutout position and screen width, all in pixels. */
data class Geometry(
    val holeW: Int,
    val holeH: Int,
    val holeTop: Int,
    val holeCenterX: Int,
    val screenW: Int
)

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val openIntent: PendingIntent?
)

data class NotifInfo(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Drawable?,
    val isCall: Boolean,
    val contentIntent: PendingIntent?
)

/** Tiny in-process bridge between the notification listener and the island service. */
object IslandBus {
    @Volatile
    var notifications: ((NotifInfo) -> Unit)? = null

    @Volatile
    var listenerConnected: (() -> Unit)? = null
}
