package com.siso.pixelisland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private typealias FLP = FrameLayout.LayoutParams
private typealias LLP = LinearLayout.LayoutParams

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The island itself: a black pill that sits exactly on the camera cutout and springs
 * out of it. Three shapes (idle = the cutout itself, compact, expanded) and three kinds
 * of content (media, notification, charging).
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class IslandView(
    context: Context,
    private val g: Geometry,
    private val listener: Listener
) : FrameLayout(context) {

    interface Listener {
        fun onWindowSize(w: Int, h: Int)
        fun onTap()
        fun onLongPress()
        fun onSwipeUp()
        fun onOutside()
        fun onPrev()
        fun onPlayPause()
        fun onNext()
        fun onSeek(fraction: Float)
    }

    private class Target(val w: Float, val h: Float, val top: Float, val r: Float)

    // ---------- geometry (px) ----------
    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val holeW = g.holeW.toFloat()
    private val holeH = g.holeH.toFloat()
    private val holeTop = g.holeTop.toFloat()
    private val compactH = max(dp(36f), holeH + dp(10f))
    private val earW = dp(44f)
    private val compactW = holeW + 2f * earW
    private val expW = min(g.screenW - dp(24f), dp(372f))
    private val compactTop = max(0f, holeTop + holeH / 2f - compactH / 2f)

    private val green = Color.parseColor("#30D158")
    private val gray = Color.argb(170, 255, 255, 255)

    // ---------- state ----------
    var shape = IslandShape.IDLE
        private set
    var kind = Kind.NONE
        private set

    private var media: MediaInfo? = null
    private var notifIconDrawable: Drawable? = null

    private var curW = holeW
    private var curH = holeH
    private var curTop = holeTop
    private var curR = holeH / 2f

    // ---------- views ----------
    private val bg = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = holeH / 2f
    }
    private val pill = FrameLayout(context)

    // compact content
    private val compactRow = FrameLayout(context)
    private val leftIcon = roundedImage(7f)
    private val bars = BarsView(context)
    private val rightText = TextView(context)

    // expanded content
    private val expanded = FrameLayout(context)

    private val mediaPanel = LinearLayout(context)
    private val artView = roundedImage(10f)
    private val titleTv = TextView(context)
    private val artistTv = TextView(context)
    private val mediaBars = BarsView(context)
    private val timeL = TextView(context)
    private val timeR = TextView(context)
    private val seek = SeekView(context)
    private val prevBtn = iconButton(R.drawable.ic_prev)
    private val playBtn = iconButton(R.drawable.ic_pause)
    private val nextBtn = iconButton(R.drawable.ic_next)

    private val notifPanel = LinearLayout(context)
    private val notifIcon = roundedImage(10f)
    private val notifApp = TextView(context)
    private val notifTitle = TextView(context)
    private val notifText = TextView(context)

    private val chargePanel = LinearLayout(context)
    private val chargeIcon = iconButton(R.drawable.ic_bolt)
    private val chargeTitle = TextView(context)
    private val chargePct = TextView(context)

    // ---------- springs ----------
    private val wHolder = FloatValueHolder(holeW)
    private val hHolder = FloatValueHolder(holeH)
    private val tHolder = FloatValueHolder(holeTop)
    private val rHolder = FloatValueHolder(holeH / 2f)

    private val wAnim = newSpring(wHolder, 380f, 0.74f) { curW = it; applyLayout() }
    private val hAnim = newSpring(hHolder, 380f, 0.74f) { curH = it; applyLayout() }
    private val tAnim = newSpring(tHolder, 380f, 0.80f) { curTop = it; applyLayout() }
    private val rAnim = newSpring(rHolder, 420f, 0.85f) { curR = it; applyLayout() }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (shape == IslandShape.EXPANDED && kind == Kind.MEDIA && media?.isPlaying == true) {
                postDelayed(this, 500)
            }
        }
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            listener.onTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            listener.onLongPress()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (velocityY < -700f && abs(velocityY) > abs(velocityX)) {
                listener.onSwipeUp()
                return true
            }
            return false
        }
    })

    init {
        clipChildren = false

        pill.background = bg
        pill.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, bg.cornerRadius)
            }
        }
        pill.clipToOutline = true
        pill.visibility = INVISIBLE
        pill.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
        addView(pill, FLP(holeW.toInt(), holeH.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        buildCompact()
        buildMediaPanel()
        buildNotifPanel()
        buildChargePanel()

        expanded.addView(mediaPanel, FLP(MATCH, MATCH))
        expanded.addView(notifPanel, FLP(MATCH, MATCH))
        expanded.addView(chargePanel, FLP(MATCH, MATCH))
        notifPanel.visibility = GONE
        chargePanel.visibility = GONE

        pill.addView(compactRow, FLP(compactW.toInt(), compactH.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pill.addView(expanded, FLP(expW.toInt(), panelH(Kind.MEDIA).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        compactRow.alpha = 0f
        expanded.alpha = 0f
        expanded.visibility = INVISIBLE
    }

    // ---------- public API ----------

    fun bindMedia(m: MediaInfo?) {
        media = m
        if (m == null) return

        titleTv.text = m.title.ifBlank { "Playing" }
        artistTv.text = m.artist
        if (m.art != null) {
            artView.setPadding(0, 0, 0, 0)
            artView.setImageBitmap(m.art)
        } else {
            val p = dp(12f).toInt()
            artView.setPadding(p, p, p, p)
            artView.setImageResource(R.drawable.ic_music)
            artView.setBackgroundColor(Color.argb(60, 255, 255, 255))
        }
        playBtn.setImageResource(if (m.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        bars.playing = m.isPlaying
        mediaBars.playing = m.isPlaying
        if (kind == Kind.MEDIA) updateCompactContent()
        removeCallbacks(tick)
        post(tick)
    }

    fun bindNotif(n: NotifInfo) {
        notifApp.text = n.appName
        notifTitle.text = n.title
        notifText.text = n.text
        notifText.visibility = if (n.text.isEmpty()) GONE else VISIBLE
        notifIconDrawable = n.icon
        notifIcon.setImageDrawable(copyOf(n.icon))
        if (kind == Kind.NOTIF) updateCompactContent()
    }

    fun bindCharge(percent: Int) {
        chargePct.text = "$percent%"
        chargeTitle.text = if (percent >= 100) "Fully charged" else "Charging"
        rightText.text = "$percent%"
        if (kind == Kind.CHARGE) updateCompactContent()
    }

    fun setShape(newShape: IslandShape, newKind: Kind) {
        if (newShape == shape && newKind == kind) return
        shape = newShape
        kind = newKind

        if (newKind != Kind.NONE) {
            updatePanels()
            updateCompactContent()
            if (newShape == IslandShape.EXPANDED) {
                expanded.layoutParams =
                    FLP(expW.toInt(), panelH(newKind).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            }
        }

        val t = targetFor(newShape, newKind)

        // Make the window big enough for the whole move (plus a little overshoot room).
        val reqW = min(max(curW, t.w) + dp(20f), g.screenW.toFloat())
        val reqH = max(curTop + curH, t.top + t.h) + dp(16f)
        listener.onWindowSize(ceil(reqW).toInt(), ceil(reqH).toInt())

        pill.visibility = VISIBLE
        springTo(wAnim, wHolder, curW, t.w)
        springTo(hAnim, hHolder, curH, t.h)
        springTo(tAnim, tHolder, curTop, t.top)
        springTo(rAnim, rHolder, curR, t.r)

        removeCallbacks(tick)
        post(tick)
        postDelayed({ settleIfDone() }, 1200)
    }

    // ---------- touch ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            listener.onOutside()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        wAnim.cancel(); hAnim.cancel(); tAnim.cancel(); rAnim.cancel()
        super.onDetachedFromWindow()
    }

    // ---------- layout / animation ----------

    private fun targetFor(s: IslandShape, k: Kind): Target = when (s) {
        IslandShape.IDLE -> Target(holeW, holeH, holeTop, holeH / 2f)
        IslandShape.COMPACT -> Target(compactW, compactH, compactTop, compactH / 2f)
        IslandShape.EXPANDED -> {
            val h = panelH(k)
            Target(expW, h, compactTop, min(h / 2f, dp(38f)))
        }
    }

    private fun panelH(k: Kind): Float = when (k) {
        Kind.NOTIF -> dp(120f)
        Kind.CHARGE -> dp(98f)
        else -> dp(190f)
    }

    private fun newSpring(
        holder: FloatValueHolder,
        stiffness: Float,
        damping: Float,
        onUpdate: (Float) -> Unit
    ): SpringAnimation {
        val a = SpringAnimation(holder)
        a.setSpring(SpringForce().setStiffness(stiffness).setDampingRatio(damping))
        a.setMinimumVisibleChange(0.5f)
        a.addUpdateListener { _, value, _ -> onUpdate(value) }
        a.addEndListener { _, canceled, _, _ -> if (!canceled) settleIfDone() }
        return a
    }

    private fun springTo(anim: SpringAnimation, holder: FloatValueHolder, from: Float, to: Float) {
        if (!anim.isRunning) holder.value = from
        anim.animateToFinalPosition(to)
    }

    private fun settleIfDone() {
        if (wAnim.isRunning || hAnim.isRunning || tAnim.isRunning || rAnim.isRunning) return
        listener.onWindowSize(ceil(curW).toInt(), ceil(curTop + curH).toInt())
        if (shape == IslandShape.IDLE) pill.visibility = INVISIBLE
    }

    private fun clamp01(v: Float) = max(0f, min(1f, v))

    private fun applyLayout() {
        val w = max(1f, curW)
        val h = max(1f, curH)

        val lp = pill.layoutParams as FLP
        lp.width = w.roundToInt()
        lp.height = h.roundToInt()
        lp.topMargin = max(0f, curTop).roundToInt()
        pill.layoutParams = lp

        bg.cornerRadius = min(max(curR, 0f), min(w, h) / 2f)
        pill.invalidateOutline()

        val tCompact = clamp01((w - holeW) / max(1f, compactW - holeW))
        val tExp = clamp01((w - compactW) / max(1f, expW - compactW))
        compactRow.alpha = tCompact * tCompact * (1f - clamp01(tExp * 3f))
        val ea = clamp01((tExp - 0.55f) * 2.2f)
        expanded.alpha = ea
        expanded.visibility = if (ea <= 0.01f) INVISIBLE else VISIBLE
    }

    // ---------- content ----------

    private fun updatePanels() {
        mediaPanel.visibility = if (kind == Kind.MEDIA) VISIBLE else GONE
        notifPanel.visibility = if (kind == Kind.NOTIF) VISIBLE else GONE
        chargePanel.visibility = if (kind == Kind.CHARGE) VISIBLE else GONE
    }

    private fun updateCompactContent() {
        val p3 = dp(3f).toInt()
        when (kind) {
            Kind.MEDIA -> {
                leftIcon.clearColorFilter()
                val art = media?.art
                if (art != null) {
                    leftIcon.setPadding(0, 0, 0, 0)
                    leftIcon.setImageBitmap(art)
                } else {
                    leftIcon.setPadding(p3, p3, p3, p3)
                    leftIcon.setImageResource(R.drawable.ic_music)
                }
                bars.visibility = VISIBLE
                rightText.visibility = GONE
            }
            Kind.NOTIF -> {
                leftIcon.clearColorFilter()
                leftIcon.setPadding(0, 0, 0, 0)
                leftIcon.setImageDrawable(copyOf(notifIconDrawable))
                bars.visibility = GONE
                rightText.visibility = GONE
            }
            Kind.CHARGE -> {
                leftIcon.setPadding(p3, p3, p3, p3)
                leftIcon.setImageResource(R.drawable.ic_bolt)
                leftIcon.setColorFilter(green)
                bars.visibility = GONE
                rightText.visibility = VISIBLE
            }
            Kind.NONE -> Unit
        }
    }

    private fun copyOf(d: Drawable?): Drawable? =
        d?.constantState?.newDrawable()?.mutate() ?: d

    private fun updateProgress() {
        val m = media ?: return
        val dur = m.durationMs
        var pos = m.positionMs
        if (m.isPlaying) {
            pos += ((SystemClock.elapsedRealtime() - m.updatedAt) * m.speed).toLong()
        }
        pos = if (dur > 0) pos.coerceIn(0L, dur) else pos.coerceAtLeast(0L)
        seek.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        timeL.text = fmt(pos)
        timeR.text = if (dur > 0) "-" + fmt(dur - pos) else ""
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ---------- view construction ----------

    private fun roundedImage(radiusDp: Float): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp))
            }
        }
        clipToOutline = true
    }

    private fun iconButton(res: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp(10f).toInt()
        setPadding(p, p, p, p)
    }

    private fun style(tv: TextView, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        tv.maxLines = lines
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.includeFontPadding = false
    }

    private fun buildCompact() {
        compactRow.addView(
            leftIcon,
            FLP(dp(24f).toInt(), dp(24f).toInt(), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = dp(10f).toInt()
            }
        )
        compactRow.addView(
            bars,
            FLP(dp(18f).toInt(), dp(14f).toInt(), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(13f).toInt()
            }
        )
        style(rightText, 12f, green, bold = true)
        compactRow.addView(
            rightText,
            FLP(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(10f).toInt()
            }
        )
        rightText.visibility = GONE
    }

    private fun buildMediaPanel() {
        mediaPanel.orientation = LinearLayout.VERTICAL
        mediaPanel.setPadding(
            dp(22f).toInt(), (compactH + dp(2f)).toInt(), dp(22f).toInt(), dp(14f).toInt()
        )

        // row 1: artwork, title/artist, bars
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(artView, LLP(dp(52f).toInt(), dp(52f).toInt()))
        style(titleTv, 16f, Color.WHITE, bold = true)
        style(artistTv, 13f, gray)
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleTv, LLP(MATCH, WRAP))
        texts.addView(artistTv, LLP(MATCH, WRAP).apply { topMargin = dp(3f).toInt() })
        row1.addView(
            texts,
            LLP(0, WRAP, 1f).apply {
                marginStart = dp(12f).toInt()
                marginEnd = dp(10f).toInt()
            }
        )
        row1.addView(mediaBars, LLP(dp(22f).toInt(), dp(18f).toInt()))
        mediaPanel.addView(row1, LLP(MATCH, WRAP))

        // row 2: time, seek bar, remaining time
        style(timeL, 11f, gray)
        style(timeR, 11f, gray)
        timeL.gravity = Gravity.START
        timeR.gravity = Gravity.END
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row2.addView(timeL, LLP(dp(34f).toInt(), WRAP))
        row2.addView(
            seek,
            LLP(0, dp(16f).toInt(), 1f).apply {
                marginStart = dp(6f).toInt()
                marginEnd = dp(6f).toInt()
            }
        )
        row2.addView(timeR, LLP(dp(38f).toInt(), WRAP))
        mediaPanel.addView(row2, LLP(MATCH, WRAP).apply { topMargin = dp(12f).toInt() })
        seek.onSeek = { listener.onSeek(it) }

        // row 3: transport controls
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row3.addView(prevBtn, LLP(dp(46f).toInt(), dp(46f).toInt()))
        row3.addView(
            playBtn,
            LLP(dp(54f).toInt(), dp(54f).toInt()).apply {
                marginStart = dp(20f).toInt()
                marginEnd = dp(20f).toInt()
            }
        )
        row3.addView(nextBtn, LLP(dp(46f).toInt(), dp(46f).toInt()))
        mediaPanel.addView(row3, LLP(MATCH, WRAP).apply { topMargin = dp(6f).toInt() })

        prevBtn.setOnClickListener { listener.onPrev() }
        playBtn.setOnClickListener { listener.onPlayPause() }
        nextBtn.setOnClickListener { listener.onNext() }
    }

    private fun buildNotifPanel() {
        notifPanel.orientation = LinearLayout.HORIZONTAL
        notifPanel.gravity = Gravity.CENTER_VERTICAL
        notifPanel.setPadding(
            dp(20f).toInt(), compactH.toInt(), dp(20f).toInt(), dp(14f).toInt()
        )
        notifPanel.addView(notifIcon, LLP(dp(42f).toInt(), dp(42f).toInt()))

        style(notifApp, 11f, gray)
        style(notifTitle, 15f, Color.WHITE, bold = true)
        style(notifText, 13f, Color.argb(220, 255, 255, 255), lines = 2)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(notifApp, LLP(MATCH, WRAP))
        col.addView(notifTitle, LLP(MATCH, WRAP).apply { topMargin = dp(2f).toInt() })
        col.addView(notifText, LLP(MATCH, WRAP).apply { topMargin = dp(2f).toInt() })
        notifPanel.addView(col, LLP(0, WRAP, 1f).apply { marginStart = dp(12f).toInt() })
    }

    private fun buildChargePanel() {
        chargePanel.orientation = LinearLayout.HORIZONTAL
        chargePanel.gravity = Gravity.CENTER_VERTICAL
        chargePanel.setPadding(
            dp(26f).toInt(), compactH.toInt(), dp(26f).toInt(), dp(14f).toInt()
        )
        chargeIcon.setColorFilter(green)
        chargePanel.addView(chargeIcon, LLP(dp(40f).toInt(), dp(40f).toInt()))
        style(chargeTitle, 16f, Color.WHITE, bold = true)
        chargePanel.addView(
            chargeTitle,
            LLP(0, WRAP, 1f).apply { marginStart = dp(8f).toInt() }
        )
        style(chargePct, 26f, green, bold = true)
        chargePanel.addView(chargePct, LLP(WRAP, WRAP))
    }
}
