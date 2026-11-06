package com.dastyar.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dastyar", Context.MODE_PRIVATE)

    fun url(c: Context): String = sp(c).getString("url", "") ?: ""
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun confirm(c: Context): Boolean = sp(c).getBoolean("confirm", true)

    fun save(c: Context, url: String, token: String, confirm: Boolean) {
        sp(c).edit()
            .putString("url", url.trim())
            .putString("token", token.trim())
            .putBoolean("confirm", confirm)
            .apply()
    }

    const val MAX_STEPS = 40
}
