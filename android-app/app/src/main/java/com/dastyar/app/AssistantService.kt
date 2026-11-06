package com.dastyar.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class Snap(
    val text: String,
    val nodes: List<AccessibilityNodeInfo>,
    val labels: List<String>,
    val pkg: String,
    val captcha: Boolean
)

class AssistantService : AccessibilityService() {
    lateinit var engine: Engine
    lateinit var overlay: Overlay
    lateinit var voice: Voice
    lateinit var apps: AppIndex
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        apps = AppIndex(packageManager)
        overlay = Overlay(this, { engine.resume() }, { engine.stop() })
        engine = Engine(this)
        voice = Voice(
            this,
            onText = { engine.submit(it) },
            onPartial = { engine.onPartial(it) },
            onState = { Hub.listening.value = it }
        )
        Hub.service = this
        Hub.status.value = "آماده"
        Thread { apps.refresh() }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Hub.service = null
        main.post { voice.stop() }
        overlay.destroy()
        return super.onUnbind(intent)
    }

    fun startVoice() { main.post { voice.start() } }
    fun stopVoice() { main.post { voice.stop() } }

    // ---------- Screen reading ----------

    fun snapshot(): Snap {
        val root = rootInActiveWindow ?: return Snap("(صفحه خالی)", emptyList(), emptyList(), "", false)
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val labels = ArrayList<String>()
        val sb = StringBuilder()
        var cap = false

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= 220 || depth > 40) return
            if (!n.isVisibleToUser) return
            val label = (n.text ?: n.contentDescription ?: n.hintText)?.toString()
                ?.replace("\n", " ")?.take(80) ?: ""
            val vid = n.viewIdResourceName?.substringAfterLast('/') ?: ""
            val low = Normalizer.norm("$label $vid ${n.className ?: ""}")
            if (CAPTCHA_KEYS.any { low.contains(it) }) cap = true
            val interesting = label.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (interesting) {
                val kind = when {
                    n.isEditable -> "edit"
                    n.isScrollable -> "scroll"
                    n.isCheckable -> "chk"
                    n.isClickable -> "btn"
                    else -> "txt"
                }
                sb.append(nodes.size).append('|').append(kind).append('|').append(label).append('\n')
                nodes.add(n)
                labels.add(label)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return Snap(sb.toString(), nodes, labels, root.packageName?.toString() ?: "", cap)
    }

    // ---------- UI actions ----------

    fun click(s: Snap, id: Int): Boolean {
        val n = s.nodes.getOrNull(id) ?: return false
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 6) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
            depth++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun type(s: Snap, id: Int, text: String, enter: Boolean): Boolean {
        val n = s.nodes.getOrNull(id) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        if (ok && enter && Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    fun scroll(s: Snap, dir: String): Boolean {
        val forward = dir != "up"
        val node = s.nodes.firstOrNull { it.isScrollable }
        val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node != null && node.performAction(act)) return true
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (forward) 0.70f else 0.30f)
        val y2 = dm.heightPixels * (if (forward) 0.30f else 0.70f)
        return swipe(x, y1, x, y2)
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 50)).build()
        return dispatchGesture(g, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build()
        return dispatchGesture(g, null, null)
    }

    fun global(which: String): Boolean = when (which) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "screenshot" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "lock" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    // ---------- System actions ----------

    fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    fun openUrl(u: String) {
        val url = if (u.startsWith("http")) u else "https://$u"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun torch(on: Boolean): Boolean {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun volume(up: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun panel(which: String) {
        val action = if (which == "wifi") Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_BLUETOOTH_SETTINGS
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Vibration + beep so the user notices the app is waiting for them. */
    fun alert() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP)
        } catch (e: Exception) { }
    }

    companion object {
        private val CAPTCHA_KEYS = listOf(
            "captcha", "recaptcha", "hcaptcha", "کپچا", "کد امنیتی", "تصویر امنیتی",
            "من ربات نیستم", "not a robot"
        )
    }
}
