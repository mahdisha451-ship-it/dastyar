package com.dastyar.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Shared execution engine for voice and text modes: local fast path first, then the LLM agent loop. */
class Engine(private val svc: AssistantService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    @Volatile private var gate: CompletableDeferred<Boolean>? = null

    private val running get() = job?.isActive == true

    private val risky = listOf(
        "پرداخت", "خرید نهایی", "تایید نهایی", "ثبت نهایی", "ثبت سفارش", "حذف", "پاک کردن", "ارسال",
        "pay", "buy now", "delete", "send", "purchase", "checkout"
    )

    private fun say(msg: String) {
        Hub.status.value = msg
        svc.overlay.show(msg)
    }

    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val local = LocalMatcher.match(text)
        if (local is Cmd.Resume) { resume(); return }
        if (local is Cmd.Stop) { stop(); return }
        if (running) { say("یک کار در حال اجراست. بگویید «توقف»"); return }

        Hub.busy.value = true
        job = scope.launch {
            try {
                say("دریافت شد: $text")
                if (local != null) {
                    val r = runLocal(local)
                    if (r != null) { say(r); return@launch }
                }
                agent(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("خطا: ${e.message}")
            } finally {
                Hub.busy.value = false
                svc.overlay.hideLater()
            }
        }
    }

    /** Called with partial speech results so "continue" / "stop" react instantly. */
    fun onPartial(t: String) {
        val c = LocalMatcher.match(t)
        if (c is Cmd.Resume && gate != null) resume()
        else if (c is Cmd.Stop && (running || gate != null)) stop()
    }

    fun resume() { gate?.complete(true) }

    fun stop() {
        gate?.complete(false)
        job?.cancel()
        Hub.busy.value = false
        say("متوقف شد")
        svc.overlay.hideLater()
    }

    private suspend fun waitUser(msg: String, label: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        gate = d
        Hub.status.value = msg
        svc.overlay.showWait(msg, label)
        svc.alert()
        try {
            return d.await()
        } finally {
            gate = null
            svc.overlay.show("در حال ادامه...")
        }
    }

    private fun runLocal(c: Cmd): String? = when (c) {
        is Cmd.Torch -> if (svc.torch(c.on)) "فلاشر ${if (c.on) "روشن" else "خاموش"} شد" else "فلاشر پیدا نشد"
        is Cmd.OpenApp -> svc.apps.find(c.name)?.let { svc.launch(it); "باز شد: ${c.name}" }
        is Cmd.OpenUrl -> { svc.openUrl(c.url); "باز شد: ${c.url}" }
        is Cmd.Search -> {
            svc.openUrl("https://www.google.com/search?q=" + URLEncoder.encode(c.q, "UTF-8"))
            "جستجو در گوگل: ${c.q}"
        }
        is Cmd.Global -> { svc.global(c.which); "انجام شد" }
        is Cmd.Volume -> { svc.volume(c.up); "انجام شد" }
        is Cmd.Panel -> { svc.panel(c.which); "پنل باز شد (اندروید اجازه تغییر مستقیم نمی‌دهد)" }
        Cmd.Resume, Cmd.Stop -> null
    }

    private suspend fun agent(goal: String) {
        val history = ArrayList<String>()
        var skipCaptcha = 0
        for (step in 1..Prefs.MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            val snap = svc.snapshot()

            if (snap.captcha && skipCaptcha <= 0) {
                if (!waitUser("کپچا را خودتان وارد کنید، بعد بگویید «ادامه بده»", "ادامه")) return
                skipCaptcha = 3
                continue
            }
            if (skipCaptcha > 0) skipCaptcha--

            say("مرحله $step: در حال تصمیم‌گیری...")
            val act = Api.plan(svc, goal, snap.text, snap.pkg, history)
            job?.ensureActive()

            var ok = true
            when (act.type) {
                "done" -> { say(act.message ?: "انجام شد"); return }
                "ask_user" -> {
                    if (!waitUser(act.message ?: "نیاز به کمک شما است", "ادامه")) return
                    history.add("ask_user -> user continued")
                    skipCaptcha = 3
                    continue
                }
                "click" -> {
                    val label = act.id?.let { snap.labels.getOrNull(it) } ?: ""
                    if (isRisky(label, act.sensitive) && !waitUser("تأیید می‌کنید؟ «$label»", "تأیید")) return
                    ok = act.id != null && svc.click(snap, act.id)
                    say("کلیک: $label")
                }
                "type" -> {
                    ok = act.id != null && svc.type(snap, act.id, act.text ?: "", act.enter)
                    say("تایپ: ${act.text}")
                }
                "scroll" -> ok = svc.scroll(snap, act.dir ?: "down")
                "back" -> ok = svc.global("back")
                "home" -> ok = svc.global("home")
                "open_app" -> {
                    val pkg = act.name?.let { svc.apps.find(it) }
                    if (pkg != null) svc.launch(pkg) else ok = false
                    say("باز کردن: ${act.name}")
                }
                "open_url" -> {
                    act.url?.let { svc.openUrl(it) } ?: run { ok = false }
                    say("باز کردن: ${act.url}")
                }
                "wait" -> delay(act.ms.coerceIn(100, 5000))
                else -> ok = false
            }
            history.add("${act.type} ${act.id ?: act.text ?: act.url ?: act.name ?: act.dir ?: ""} -> ${if (ok) "ok" else "failed"}")
            delay(if (act.type == "open_app" || act.type == "open_url") 1200 else 350)
        }
        say("تعداد مراحل به حد مجاز رسید")
    }

    private fun isRisky(label: String, flagged: Boolean): Boolean {
        if (!Prefs.confirm(svc)) return false
        val n = Normalizer.norm(label)
        return flagged || risky.any { n.contains(it) }
    }
}
