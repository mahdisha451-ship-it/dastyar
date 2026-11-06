package com.dastyar.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf("home") }
    when (screen) {
        "voice" -> VoiceScreen { screen = "home" }
        "text" -> TextScreen { screen = "home" }
        "settings" -> SettingsScreen { screen = "home" }
        else -> HomeScreen { screen = it }
    }
}

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    var a11y by remember { mutableStateOf(false) }
    var mic by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            a11y = Hub.service != null || (enabled.contains(ctx.packageName) && enabled.contains("AssistantService"))
            mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            delay(800)
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("دستیار", style = MaterialTheme.typography.headlineLarge)
        Text("با صدا یا متن فارسی، گوشی را کنترل کنید.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("پیش‌نیازها", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (a11y) "✅ دسترسی Accessibility فعال است" else "❌ دسترسی Accessibility خاموش است")
                    if (!a11y) OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("فعال‌سازی") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mic) "✅ میکروفون مجاز است" else "❌ اجازه میکروفون داده نشده")
                    if (!mic) OutlinedButton(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("اجازه") }
                }
                if (!a11y) Text(
                    "در تنظیمات: Accessibility ← برنامه‌های نصب‌شده ← دستیار ← روشن. اگر اندروید ۱۳ به بالا دارید و گزینه خاکستری است: تنظیمات ← برنامه‌ها ← دستیار ← سه‌نقطه ← «Allow restricted settings».",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(onClick = { go("voice") }, Modifier.fillMaxWidth().height(96.dp)) {
            Text("🎤  حالت صوتی", style = MaterialTheme.typography.titleLarge)
        }
        Button(onClick = { go("text") }, Modifier.fillMaxWidth().height(96.dp)) {
            Text("⌨️  حالت متنی", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedButton(onClick = { go("settings") }, Modifier.fillMaxWidth()) { Text("تنظیمات سرور") }
    }
}

@Composable
fun VoiceScreen(back: () -> Unit) {
    val status by Hub.status.collectAsState()
    val listening by Hub.listening.collectAsState()
    val busy by Hub.busy.collectAsState()
    val connected = Hub.service != null

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Text("حالت صوتی", style = MaterialTheme.typography.headlineMedium)
        if (!connected) Text("ابتدا دسترسی Accessibility را فعال کنید (صفحه اصلی).")
        Button(
            onClick = { if (listening) Hub.service?.stopVoice() else Hub.service?.startVoice() },
            Modifier.size(190.dp),
            enabled = connected
        ) { Text(if (listening) "در حال گوش دادن…\n(لمس برای توقف)" else "شروع گوش دادن") }
        Text(status, style = MaterialTheme.typography.titleMedium)
        Text("مثال: «فلاشر را روشن کن»، «اینستاگرام را باز کن»، «در گوگل قیمت دلار را سرچ کن»", style = MaterialTheme.typography.bodySmall)
        if (busy) Button(onClick = { Hub.service?.engine?.stop() }) { Text("توقف فوری") }
        OutlinedButton(onClick = back) { Text("بازگشت") }
    }
}

@Composable
fun TextScreen(back: () -> Unit) {
    val status by Hub.status.collectAsState()
    val busy by Hub.busy.collectAsState()
    var text by remember { mutableStateOf("") }
    var warn by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("حالت متنی", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("دستور خود را بنویسید") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (text.isNotBlank()) {
                    warn = if (Hub.submit(text)) "" else "ابتدا دسترسی Accessibility را فعال کنید."
                    if (warn.isEmpty()) text = ""
                }
            }) { Text("اجرا") }
            if (busy) OutlinedButton(onClick = { Hub.service?.engine?.stop() }) { Text("توقف فوری") }
        }
        if (warn.isNotEmpty()) Text(warn, color = MaterialTheme.colorScheme.error)
        Text(status, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = back) { Text("بازگشت") }
    }
}

@Composable
fun SettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Prefs.url(ctx)) }
    var token by remember { mutableStateOf(Prefs.token(ctx)) }
    var confirm by remember { mutableStateOf(Prefs.confirm(ctx)) }
    var saved by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("تنظیمات", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("آدرس سرور (https://...onrender.com)") }, singleLine = true)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("APP_TOKEN") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("تأیید قبل از پرداخت و کارهای برگشت‌ناپذیر")
            Switch(checked = confirm, onCheckedChange = { confirm = it })
        }
        Button(onClick = { Prefs.save(ctx, url, token, confirm); saved = true }) { Text("ذخیره") }
        if (saved) Text("ذخیره شد ✅")
        OutlinedButton(onClick = back) { Text("بازگشت") }
    }
}
