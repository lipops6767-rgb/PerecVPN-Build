package com.perecvpn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.IntentFilter
import android.net.VpnService
import com.perecvpn.app.core.PerecVpnService
import com.perecvpn.app.core.VpnConfigBuilder
import java.io.File
import android.util.Base64
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class VpnActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var connect: Button
    private val nodes = mutableListOf<String>()
    private var selected = -1
    private var pendingStart = false
    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (intent.action == PerecVpnService.ACTION_STATUS) {
                val s = intent.getStringExtra(PerecVpnService.EXTRA_STATUS).orEmpty()
                status.text = s
                connect.text = if (s == "Подключено") "ВЫКЛЮЧИТЬ VPN" else "ВКЛЮЧИТЬ VPN"
            }
        }
    }
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= 33) { registerReceiver(statusReceiver, IntentFilter(PerecVpnService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED) } else { registerReceiver(statusReceiver, IntentFilter(PerecVpnService.ACTION_STATUS)) }
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK && pendingStart) startVpnService() else status.text = "Разрешение VPN не выдано"
            pendingStart = false
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, PerecVpnService::class.java).setAction(PerecVpnService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Подключение…"
        connect.text = "ПОДКЛЮЧЕНИЕ…"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn)
        input = findViewById(R.id.subscriptionInput)
        status = findViewById(R.id.statusText)
        list = findViewById(R.id.serverList)
        connect = findViewById(R.id.connectButton)

        findViewById<Button>(R.id.pasteButton).setOnClickListener { paste() }
        findViewById<Button>(R.id.updateButton).setOnClickListener { loadSubscription() }
        list.setOnItemClickListener { _, _, position, _ ->
            selected = position
            status.text = "Выбран: ${nodes[position].substringAfterLast('#').ifBlank { "VLESS-сервер" }}"
        }
        connect.setOnClickListener {
            if (connect.text.toString().contains("ВЫКЛЮЧИТЬ")) {
                startService(Intent(this, PerecVpnService::class.java).setAction(PerecVpnService.ACTION_STOP))
                return@setOnClickListener
            }
            if (selected < 0) {
                Toast.makeText(this, "Сначала выбери сервер", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val config = VpnConfigBuilder.build(nodes[selected])
                File(filesDir, "active-config.json").writeText(config)
            } catch (e: Exception) {
                status.text = "Ошибка конфигурации: ${e.message}"
                return@setOnClickListener
            }
            pendingStart = true
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                startActivityForResult(prepare, 1001)
            } else {
                startVpnService()
            }
        }
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
        } else {
            input.setText(text)
            if (text.startsWith("http://") || text.startsWith("https://")) loadSubscription()
        }
    }

    private fun loadSubscription() {
        val url = input.text.toString().trim()
        if (!url.startsWith("https://perecsub.com/")) {
            status.text = "Нужна ссылка подписки perecsub.com"
            return
        }
        status.text = "Загрузка подписки…"
        Thread {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "PerecVPN/1.1").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val parsed = parseSubscription(body)
                    runOnUiThread {
                        nodes.clear(); nodes.addAll(parsed); selected = -1
                        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, nodes.map { displayName(it) })
                        status.text = if (nodes.isEmpty()) "VLESS-конфигурации не найдены" else "Найдено серверов: ${nodes.size}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка загрузки: ${e.message ?: "неизвестная ошибка"}" }
            }
        }.start()
    }

    private fun parseSubscription(raw: String): List<String> {
        val candidates = linkedSetOf<String>()
        fun collect(text: String) {
            text.lineSequence().map { it.trim() }.filter { it.startsWith("vless://") }.forEach { candidates.add(it) }
            Regex("vless://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE).findAll(text).forEach { candidates.add(it.value) }
        }
        collect(raw)
        val compact = raw.replace("\\n", "").trim()
        val decoded = try {
            Base64.decode(compact, Base64.DEFAULT).toString(StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
        if (decoded.isNotBlank()) collect(decoded)
        return candidates.toList()
    }

    private fun displayName(uri: String): String {
        val name = uri.substringAfterLast('#', "VLESS-сервер").replace("%20", " ")
        return "🌶 $name"
    }
}
