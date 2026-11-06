package com.dastyar.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared state between the UI and the accessibility service. */
object Hub {
    @Volatile var service: AssistantService? = null
    val status = MutableStateFlow("آماده")
    val listening = MutableStateFlow(false)
    val busy = MutableStateFlow(false)

    /** Sends a text command to the engine. Returns false if the service is not enabled yet. */
    fun submit(text: String): Boolean {
        val s = service ?: return false
        s.engine.submit(text)
        return true
    }
}
