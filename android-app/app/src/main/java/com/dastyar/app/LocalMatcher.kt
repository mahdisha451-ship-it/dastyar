package com.dastyar.app

object Normalizer {
    /** Normalizes Persian/Arabic text: unified letters, latin digits, no diacritics, single spaces. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            when {
                ch == 'ي' || ch == 'ى' -> sb.append('ی')
                ch == 'ك' -> sb.append('ک')
                ch == 'أ' || ch == 'إ' -> sb.append('ا')
                ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                ch == '\u200c' || ch == '\u200f' || ch == '\u200e' -> sb.append(' ')
                ch in '\u064B'..'\u065F' || ch == '\u0640' -> {}
                ch.isLetterOrDigit() || ch == ' ' || ch == '.' || ch == '/' || ch == ':' || ch == '-' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}

sealed class Cmd {
    data class Torch(val on: Boolean) : Cmd()
    data class OpenApp(val name: String) : Cmd()
    data class OpenUrl(val url: String) : Cmd()
    data class Search(val q: String) : Cmd()
    data class Global(val which: String) : Cmd()
    data class Volume(val up: Boolean) : Cmd()
    data class Panel(val which: String) : Cmd()
    object Resume : Cmd()
    object Stop : Cmd()
}

/** Fast offline path: recognizes common commands without calling the LLM. */
object LocalMatcher {
    private val FILL = setOf("رو", "را", "لطفا", "خب", "یه", "یک", "من", "برام", "بیا", "جان")

    private val RESUME = setOf("ادامه", "ادامه بده", "تایید", "تایید کن", "تایید میکنم", "بله", "اوکی", "ok", "اره", "آره")
    private val STOP = setOf("توقف", "توقف کن", "بس", "بس کن", "بسه", "وایسا", "وایستا", "لغو", "کافیه", "stop", "نه")

    private val GLOBALS = mapOf(
        "برگرد" to "back", "برگشت" to "back", "عقب" to "back", "بک" to "back", "بازگشت" to "back",
        "صفحه اصلی" to "home", "هوم" to "home", "خانه" to "home", "برو خانه" to "home",
        "اسکرین شات" to "screenshot", "اسکرین شات بگیر" to "screenshot", "عکس از صفحه بگیر" to "screenshot",
        "قفل کن" to "lock", "قفل صفحه" to "lock",
        "اعلان ها" to "notifications", "نوتیفیکیشن" to "notifications",
        "برنامه های اخیر" to "recents"
    )

    private val URL = Regex("""^(?:(?:برو|بره)\s+)?(?:(?:به|تو|توی)\s+)?(?:سایت\s+)?((?:https?://)?[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:/\S*)?)(?:\s+(?:باز کن|بزن|برو))?$""")

    private val S1 = Regex("""^(?:تو|توی|در|داخل)\s+گوگل\s+(.+?)\s+(?:سرچ|جستجو)\s*کن$""")
    private val S2 = Regex("""^(?:تو|توی|در|داخل)\s+گوگل\s+(?:سرچ|جستجو)\s*کن\s+(.+)$""")
    private val S3 = Regex("""^(.+?)\s+(?:تو|توی|در|داخل)\s+گوگل\s+(?:سرچ|جستجو)\s*کن$""")

    private val O1 = Regex("""^(?:برنامه\s+)?(.+?)\s+(?:باز|اجرا)\s*کن$""")
    private val O2 = Regex("""^(?:باز|اجرا)\s*کن\s+(?:برنامه\s+)?(.+)$""")
    private val O3 = Regex("""^(?:برو|بره)\s+(?:تو|توی|به)\s+(?:برنامه\s+)?(.+)$""")

    private fun String.hasAny(vararg k: String) = k.any { this.contains(it) }

    fun match(raw: String): Cmd? {
        val n = Normalizer.norm(raw).trimEnd('.')
        if (n.isEmpty()) return null
        val t = n.split(' ').filter { it !in FILL }.joinToString(" ")
        if (t.isEmpty()) return null

        if (t in RESUME || t.startsWith("ادامه")) return Cmd.Resume
        if (t in STOP) return Cmd.Stop
        GLOBALS[t]?.let { return Cmd.Global(it) }

        if (t.length <= 30) {
            if (t.hasAny("فلاشر", "چراغ قوه", "چراغقوه", "فلش")) {
                if (t.contains("خاموش")) return Cmd.Torch(false)
                if (t.contains("روشن") || t.contains("بزن")) return Cmd.Torch(true)
            }
            if (t.contains("صدا")) {
                if (t.hasAny("زیاد", "بالا", "بلند")) return Cmd.Volume(true)
                if (t.hasAny("کم", "پایین", "آروم")) return Cmd.Volume(false)
            }
            if (t.hasAny("وای فای", "وایفای", "wifi", "wi-fi")) return Cmd.Panel("wifi")
            if (t.contains("بلوتوث")) return Cmd.Panel("bluetooth")
        }

        URL.find(t)?.let { return Cmd.OpenUrl(it.groupValues[1]) }

        S1.find(t)?.let { return Cmd.Search(it.groupValues[1]) }
        S2.find(t)?.let { return Cmd.Search(it.groupValues[1]) }
        S3.find(t)?.let { return Cmd.Search(it.groupValues[1]) }

        for (r in listOf(O1, O2, O3)) {
            r.find(t)?.let {
                val name = it.groupValues[1].trim()
                if (name.isNotEmpty() && name.split(' ').size <= 4) return Cmd.OpenApp(name)
            }
        }
        return null
    }
}
