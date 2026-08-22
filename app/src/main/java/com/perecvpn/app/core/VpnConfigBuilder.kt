package com.perecvpn.app.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object VpnConfigBuilder {
    fun build(vless: String): String {
        val u = Uri.parse(vless)
        require(u.scheme.equals("vless", true)) { "Нужен VLESS" }
        val uuid = u.userInfo ?: error("VLESS UUID отсутствует")
        val host = u.host ?: error("VLESS host отсутствует")
        val port = u.port.takeIf { it > 0 } ?: 443
        val q = u.queryParameterNames.associateWith { u.getQueryParameter(it).orEmpty() }
        val security = q["security"].orEmpty().lowercase()
        val tls = JSONObject().put("enabled", security == "tls" || security == "reality" || q["tls"] == "1")
        val sni = q["sni"].orEmpty().ifBlank { q["host"].orEmpty() }
        if (sni.isNotBlank()) tls.put("server_name", sni)
        val fp = q["fp"].orEmpty()
        if (fp.isNotBlank()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        if (security == "reality") {
            val reality = JSONObject().put("enabled", true)
            q["pbk"]?.takeIf { it.isNotBlank() }?.let { reality.put("public_key", it) }
            q["sid"]?.takeIf { it.isNotBlank() }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }

        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("packet_encoding", q["packetEncoding"].orEmpty().ifBlank { "xudp" })
            .put("tls", tls)

        q["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }

        val type = q["type"].orEmpty().ifBlank { q["network"].orEmpty() }.lowercase()
        when (type) {
            "ws", "websocket" -> {
                val transport = JSONObject().put("type", "ws")
                q["path"]?.let { transport.put("path", decode(it)) }
                q["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("headers", JSONObject().put("Host", it)) }
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                q["serviceName"]?.takeIf { it.isNotBlank() }?.let { transport.put("service_name", decode(it)) }
                q["mode"]?.takeIf { it.isNotBlank() }?.let { transport.put("idle_session_check_interval", it) }
                outbound.put("transport", transport)
            }
            "h2", "http" -> {
                val transport = JSONObject().put("type", "http")
                q["path"]?.let { transport.put("path", decode(it)) }
                q["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("host", JSONArray().put(it)) }
                outbound.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                q["path"]?.let { transport.put("path", decode(it)) }
                q["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("host", it) }
                outbound.put("transport", transport)
            }
        }

        val dnsServers = JSONArray().put(JSONObject().put("type", "udp").put("server", "1.1.1.1"))
        val dns = JSONObject().put("servers", dnsServers)
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "perec-tun")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "mixed")
        val route = JSONObject().put("auto_detect_interface", true).put("final", "proxy")
        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", route)
            .toString()
    }

    private fun decode(v: String): String = try { URLDecoder.decode(v, StandardCharsets.UTF_8.name()) } catch (_: Exception) { v }
}
