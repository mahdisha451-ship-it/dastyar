package com.dastyar.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Floating status panel with Stop and Continue buttons (works on top of any app). */
class Overlay(
    private val svc: AccessibilityService,
    private val onGo: () -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val root = LinearLayout(svc)
    private val tv = TextView(svc)
    private val go = Button(svc)
    private val stop = Button(svc)
    private val hideRunnable = Runnable { root.visibility = View.GONE }

    init {
        h.post { build() }
    }

    private fun build() {
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(32, 20, 32, 20)
        root.background = GradientDrawable().apply {
            setColor(0xE6202124.toInt())
            cornerRadius = 36f
        }
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        tv.maxWidth = svc.resources.displayMetrics.widthPixels - 160
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        go.setOnClickListener { onGo() }
        stop.text = "توقف"
        stop.setOnClickListener { onStop() }
        row.addView(go)
        row.addView(stop)
        root.addView(tv)
        root.addView(row)
        root.visibility = View.GONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        wm.addView(root, lp)
    }

    fun show(text: String) = h.post {
        h.removeCallbacks(hideRunnable)
        tv.text = text
        go.visibility = View.GONE
        root.visibility = View.VISIBLE
    }

    fun showWait(text: String, label: String) = h.post {
        h.removeCallbacks(hideRunnable)
        tv.text = text
        go.text = label
        go.visibility = View.VISIBLE
        root.visibility = View.VISIBLE
    }

    fun hideLater() = h.postDelayed(hideRunnable, 2500)

    fun destroy() = h.post {
        h.removeCallbacks(hideRunnable)
        try { wm.removeView(root) } catch (e: Exception) { }
    }
}
