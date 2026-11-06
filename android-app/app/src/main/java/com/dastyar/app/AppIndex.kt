package com.dastyar.app

import android.content.Intent
import android.content.pm.PackageManager

/** Cached index of launchable apps for matching Persian/English names. */
class AppIndex(private val pm: PackageManager) {
    private class A(val label: String, val pkg: String)

    @Volatile private var apps: List<A> = emptyList()

    fun refresh() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(i, 0).map {
            A(Normalizer.norm(it.loadLabel(pm).toString()), it.activityInfo.packageName)
        }
    }

    fun find(name: String): String? {
        if (apps.isEmpty()) refresh()
        val q = Normalizer.norm(name).removePrefix("برنامه ").trim()
        val keys = (listOf(q) + (ALIASES[q] ?: emptyList())).filter { it.isNotBlank() }
        for (k in keys) apps.firstOrNull { it.label == k }?.let { return it.pkg }
        for (k in keys) {
            val ascii = k.all { it.code < 128 }
            apps.firstOrNull { it.label.contains(k) || (ascii && it.pkg.contains(k)) }?.let { return it.pkg }
        }
        return null
    }

    companion object {
        private val ALIASES: Map<String, List<String>> = mapOf(
            "اینستاگرام" to listOf("instagram"), "اینستا" to listOf("instagram"),
            "تلگرام" to listOf("telegram"), "واتساپ" to listOf("whatsapp"), "واتس اپ" to listOf("whatsapp"),
            "کروم" to listOf("chrome"), "مرورگر" to listOf("chrome", "browser"),
            "یوتیوب" to listOf("youtube"), "دوربین" to listOf("camera"),
            "تنظیمات" to listOf("settings"), "گالری" to listOf("gallery", "photos"),
            "تماس" to listOf("dialer", "phone"), "شماره گیر" to listOf("dialer"),
            "پیامک" to listOf("messaging", "messages", "mms"), "ساعت" to listOf("clock"),
            "ماشین حساب" to listOf("calculator"), "نقشه" to listOf("maps"), "گوگل" to listOf("google"),
            "اسنپ" to listOf("snapp"), "دیوار" to listOf("divar"), "ایتا" to listOf("eitaa"),
            "روبیکا" to listOf("rubika"), "بله" to listOf("bale"), "آپارات" to listOf("aparat"),
            "بازار" to listOf("bazaar", "cafebazaar"), "مخاطبین" to listOf("contacts")
        )
    }
}
