package com.dastyar.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/** Continuous Persian speech recognition. Must be used from the main thread. */
class Voice(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    private var rec: SpeechRecognizer? = null
    private var on = false
    private val h = Handler(Looper.getMainLooper())

    fun start() {
        if (on) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Hub.status.value = "اجازه میکروفون لازم است"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Hub.status.value = "سرویس تشخیص گفتار (Google) روی گوشی پیدا نشد"
            return
        }
        on = true
        onState(true)
        create()
        begin()
    }

    fun stop() {
        on = false
        onState(false)
        h.removeCallbacksAndMessages(null)
        rec?.destroy()
        rec = null
    }

    private fun create() {
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
    }

    private fun begin() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        try { rec?.startListening(i) } catch (e: Exception) { again(600, true) }
    }

    private fun again(delay: Long = 150, recreate: Boolean = false) {
        if (!on) return
        h.postDelayed({
            if (!on) return@postDelayed
            if (recreate) create()
            begin()
        }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) onText(t)
            again()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Hub.status.value = "اجازه میکروفون لازم است"
                    stop()
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> again(500, true)
                else -> again(800, true)
            }
        }
    }
}
