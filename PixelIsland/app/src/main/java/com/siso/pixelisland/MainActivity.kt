package com.siso.pixelisland

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Setup screen: permissions, start/stop, and calibration of the pill around the camera hole. */
class MainActivity : Activity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusNotif: TextView
    private lateinit var statusRunning: TextView

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101014"))
            addView(root)
        }

        root.addView(text("Pixel Island", 28f, Color.WHITE, bold = true))
        root.addView(text("A Dynamic Island that grows out of your camera hole.", 14f, Color.LTGRAY, top = 4))

        root.addView(text("1. Permissions", 18f, Color.WHITE, bold = true, top = 24))
        statusOverlay = text("", 13f, Color.LTGRAY, top = 6)
        root.addView(statusOverlay)
        root.addView(button("Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        statusNotif = text("", 13f, Color.LTGRAY, top = 12)
        root.addView(statusNotif)
        root.addView(button("Allow notification access (music + previews)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Allow notifications (keeps the service alive)") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })

        root.addView(text("2. Run", 18f, Color.WHITE, bold = true, top = 24))
        statusRunning = text("", 13f, Color.LTGRAY, top = 6)
        root.addView(statusRunning)
        root.addView(button("Start island") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, IslandService::class.java))
            }
            refreshStatus()
        })
        root.addView(button("Stop island") {
            stopService(Intent(this, IslandService::class.java))
            refreshStatus()
        })
        root.addView(button("Test animation") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(
                    Intent(this, IslandService::class.java).setAction(IslandService.ACTION_TEST)
                )
            }
        })

        root.addView(text("3. Fit to your camera hole", 18f, Color.WHITE, bold = true, top = 24))
        root.addView(
            text(
                "Press Test animation, then adjust until the black pill lines up exactly with the hole when idle.",
                13f, Color.LTGRAY, top = 6
            )
        )
        root.addView(seekRow("Width", IslandService.KEY_W))
        root.addView(seekRow("Height", IslandService.KEY_H))
        root.addView(seekRow("Vertical position", IslandService.KEY_TOP))

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val notif = listeners?.contains(packageName) == true
        statusOverlay.text = if (overlay) "Display over other apps: allowed" else "Display over other apps: NOT allowed"
        statusNotif.text = if (notif) "Notification access: allowed" else "Notification access: NOT allowed"
        statusRunning.text = if (IslandService.running) "Island: running" else "Island: stopped"
    }

    // ------------------------------------------------------------------ small UI helpers

    private fun text(
        s: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
        top: Int = 0
    ): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    /** A -10..+10 dp slider saved to preferences; the running island reloads when released. */
    private fun seekRow(title: String, key: String): LinearLayout {
        val prefs = getSharedPreferences(IslandService.PREFS, Context.MODE_PRIVATE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val label = text("$title: ${prefs.getInt(key, 0)} dp", 14f, Color.WHITE)
        val bar = SeekBar(this).apply {
            max = 20
            progress = prefs.getInt(key, 0) + 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "$title: ${p - 10} dp"
                    if (fromUser) prefs.edit().putInt(key, p - 10).apply()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {
                    if (IslandService.running) {
                        startForegroundService(
                            Intent(this@MainActivity, IslandService::class.java)
                                .setAction(IslandService.ACTION_RELOAD)
                        )
                    }
                }
            })
        }
        row.addView(label)
        row.addView(bar)
        return row
    }
}
