package com.siso.pixelisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Owns the overlay window. Decides what the island shows (idle / compact / expanded)
 * from media, notification and charging events.
 */
class IslandService : Service(), IslandView.Listener {

    companion object {
        const val ACTION_RELOAD = "com.siso.pixelisland.RELOAD"
        const val ACTION_TEST = "com.siso.pixelisland.TEST"
        const val PREFS = "island"
        const val KEY_W = "adj_w"
        const val KEY_H = "adj_h"
        const val KEY_TOP = "adj_top"
        private const val CHANNEL = "island_running"
        private const val NOTIF_ID = 42

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var media: MediaWatcher
    private val handler = Handler(Looper.getMainLooper())

    private var view: IslandView? = null
    private var params: WindowManager.LayoutParams? = null

    private var mediaInfo: MediaInfo? = null
    private var notif: NotifInfo? = null
    private var transient = Kind.NONE
    private var mediaExpanded = false
    private var lastNotifKey = ""
    private var lastNotifTime = 0L

    private val endTransient = Runnable {
        transient = Kind.NONE
        refresh()
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_CONNECTED) showCharging()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WindowManager::class.java)
        startAsForeground()

        media = MediaWatcher(this) { info ->
            mediaInfo = info
            view?.bindMedia(info)
            if (info == null) mediaExpanded = false
            refresh()
        }

        IslandBus.notifications = { n -> handler.post { onNotification(n) } }
        IslandBus.listenerConnected = { handler.post { media.start() } }

        val filter = IntentFilter(Intent.ACTION_POWER_CONNECTED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(powerReceiver, filter)
        }

        buildView()
        media.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD -> buildView()
            ACTION_TEST -> showCharging(forcePercent = 78)
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildView()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        IslandBus.notifications = null
        IslandBus.listenerConnected = null
        try {
            unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
        }
        media.stop()
        removeView()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Island running", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Pixel Island is running")
            .setSmallIcon(R.drawable.ic_island)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- overlay window

    private fun removeView() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
        params = null
    }

    private fun buildView() {
        removeView()
        if (!Settings.canDrawOverlays(this)) return

        val g = computeGeometry()
        val v = IslandView(this, g, this)

        val lp = WindowManager.LayoutParams(
            g.holeW,
            g.holeTop + g.holeH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = g.holeCenterX - g.screenW / 2
        lp.y = 0
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.setFitInsetsTypes(0)
        lp.title = "PixelIsland"

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) v.visibility = View.GONE

        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            return
        }
        view = v
        params = lp

        mediaInfo?.let { v.bindMedia(it) }
        refresh()
    }

    /** Finds the camera hole; falls back to a Pixel-7-like default, then applies the user's calibration. */
    private fun computeGeometry(): Geometry {
        val d = resources.displayMetrics.density
        val metrics = wm.currentWindowMetrics
        val screenW = metrics.bounds.width()

        var rects: List<Rect>? = null
        try {
            rects = getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.cutout?.boundingRects
        } catch (e: Exception) {
        }
        if (rects.isNullOrEmpty()) {
            try {
                rects = metrics.windowInsets.displayCutout?.boundingRects
            } catch (e: Exception) {
            }
        }
        val rect: Rect? = rects
            ?.filter { it.top < (60 * d) && it.width() < screenW / 2 }
            ?.minByOrNull { it.top }

        var w = rect?.width() ?: (13 * d).toInt()
        var h = rect?.height() ?: (13 * d).toInt()
        var top = rect?.top ?: (9 * d).toInt()
        val cx = rect?.centerX() ?: (screenW / 2)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        w += (prefs.getInt(KEY_W, 0) * d).toInt()
        h += (prefs.getInt(KEY_H, 0) * d).toInt()
        top += (prefs.getInt(KEY_TOP, 0) * d).toInt()

        return Geometry(
            holeW = w.coerceAtLeast(4),
            holeH = h.coerceAtLeast(4),
            holeTop = top.coerceAtLeast(0),
            holeCenterX = cx,
            screenW = screenW
        )
    }

    override fun onWindowSize(w: Int, h: Int) {
        val lp = params ?: return
        val v = view ?: return
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- what to show

    private fun refresh() {
        val v = view ?: return
        val m = mediaInfo
        val (shape, kind) = when {
            transient != Kind.NONE -> IslandShape.EXPANDED to transient
            mediaExpanded && m != null -> IslandShape.EXPANDED to Kind.MEDIA
            m != null && m.isPlaying -> IslandShape.COMPACT to Kind.MEDIA
            else -> IslandShape.IDLE to Kind.NONE
        }
        v.setShape(shape, kind)
    }

    private fun startTransient(kind: Kind, durationMs: Long) {
        transient = kind
        handler.removeCallbacks(endTransient)
        handler.postDelayed(endTransient, durationMs)
        refresh()
    }

    private fun onNotification(n: NotifInfo) {
        val now = SystemClock.elapsedRealtime()
        if (n.key == lastNotifKey && now - lastNotifTime < 3000) return
        lastNotifKey = n.key
        lastNotifTime = now
        notif = n
        view?.bindNotif(n)
        startTransient(Kind.NOTIF, if (n.isCall) 10_000 else 4_500)
    }

    private fun showCharging(forcePercent: Int = -1) {
        val pct = if (forcePercent >= 0) {
            forcePercent
        } else {
            val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) level * 100 / scale else 0
        }
        view?.bindCharge(pct)
        startTransient(Kind.CHARGE, 3_200)
    }

    private fun collapse() {
        handler.removeCallbacks(endTransient)
        transient = Kind.NONE
        mediaExpanded = false
        refresh()
    }

    private fun send(pi: PendingIntent?) {
        try {
            pi?.send()
        } catch (e: PendingIntent.CanceledException) {
        }
    }

    // ---------------------------------------------------------------- touch + controls

    override fun onTap() {
        when (transient) {
            Kind.NOTIF -> {
                send(notif?.contentIntent)
                collapse()
            }
            Kind.CHARGE -> collapse()
            else -> {
                val open = mediaInfo?.openIntent
                if (open != null) {
                    send(open)
                } else {
                    media.controller()?.packageName?.let { pkg ->
                        packageManager.getLaunchIntentForPackage(pkg)?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(it)
                        }
                    }
                }
                if (mediaExpanded) collapse()
            }
        }
    }

    override fun onLongPress() {
        if (transient == Kind.NONE && mediaInfo != null) {
            mediaExpanded = true
            refresh()
        }
    }

    override fun onSwipeUp() = collapse()

    override fun onOutside() {
        if (transient != Kind.NONE || mediaExpanded) collapse()
    }

    override fun onPrev() {
        media.controller()?.transportControls?.skipToPrevious()
    }

    override fun onNext() {
        media.controller()?.transportControls?.skipToNext()
    }

    override fun onPlayPause() {
        val c = media.controller() ?: return
        if (mediaInfo?.isPlaying == true) c.transportControls.pause() else c.transportControls.play()
    }

    override fun onSeek(fraction: Float) {
        val dur = mediaInfo?.durationMs ?: return
        if (dur <= 0) return
        media.controller()?.transportControls?.seekTo((dur * fraction).toLong())
    }
}
