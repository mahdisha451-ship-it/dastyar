package com.dastyar.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class Act(
    val type: String,
    val id: Int?,
    val text: String?,
    val url: String?,
    val name: String?,
    val dir: String?,
    val ms: Long,
    val message: String?,
    val sensitive: Boolean,
    val enter: Boolean
)

object Api {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun plan(ctx: Context, goal: String, screen: String, pkg: String, history: List<String>): Act =
        withContext(Dispatchers.IO) {
            val base = Prefs.url(ctx).trimEnd('/')
            require(base.startsWith("http")) { "آدرس سرور را در تنظیمات وارد کنید" }
            val body = JSONObject()
                .put("command", goal)
                .put("package", pkg)
                .put("screen", screen)
                .put("history", JSONArray(history.takeLast(14)))
            val req = Request.Builder()
                .url("$base/plan")
                .header("x-app-token", Prefs.token(ctx))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) error("پاسخ سرور: ${r.code}")
                val a = JSONObject(s).getJSONObject("action")
                Act(
                    type = a.optString("type"),
                    id = if (a.has("id")) a.optInt("id", -1).takeIf { it >= 0 } else null,
                    text = a.optString("text").ifEmpty { null },
                    url = a.optString("url").ifEmpty { null },
                    name = a.optString("name").ifEmpty { null },
                    dir = a.optString("dir").ifEmpty { null },
                    ms = a.optLong("ms", 700),
                    message = a.optString("message").ifEmpty { null },
                    sensitive = a.optBoolean("sensitive", false),
                    enter = a.optBoolean("enter", false)
                )
            }
        }
}
