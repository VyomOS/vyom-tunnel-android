package io.github.vyomtunnel.sdk.utils

import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object LinkParser {

    private const val TAG = "VyomLinkParser"
    private const val DEFAULT_PORT = 20808
    private const val DEFAULT_DNS = "8.8.8.8"

    fun parse(link: String): String {
        return try {
            when {
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("vmess://") -> parseVmess(link)
                else -> throw IllegalArgumentException("Unsupported protocol: Only VLESS and VMess are supported")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse link: $link", e)
            throw e
        }
    }

    private fun parseVless(link: String): String {
        val uri = Uri.parse(link)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }

        return buildConfigJson(
            protocol = "vless",
            host = uri.host.orEmpty(),
            port = if (uri.port != -1) uri.port else 443,
            uuid = uri.userInfo.orEmpty(),
            security = params["security"] ?: "none",
            sni = params["sni"].orEmpty(),
            network = params["type"] ?: "tcp",
            flow = params["flow"].orEmpty(),
            path = params["path"].orEmpty(),
            publicKey = params["pbk"].orEmpty(),
            shortId = params["sid"].orEmpty(),
            fingerprint = params["fp"] ?: "chrome"
        )
    }

    private fun parseVmess(link: String): String {
        val rawData = link.removePrefix("vmess://")
        val jsonStr = try {
            decodeBase64(rawData)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid VMess Base64 encoding")
        }

        // Handle common malformed JSON from certain providers (e.g., missing values for keys)
        val sanitizedJson = jsonStr.replace("\"tls\",", "\"tls\":\"tls\",")
        val v = JSONObject(sanitizedJson)

        return buildConfigJson(
            protocol = "vmess",
            host = v.getString("add"),
            port = v.optInt("port", 443),
            uuid = v.getString("id"),
            security = if (v.optString("tls").isNotEmpty() && v.optString("tls") != "none") "tls" else "none",
            sni = v.optString("sni").orEmpty(),
            network = v.optString("net", "tcp"),
            path = v.optString("path").orEmpty(),
            headerType = v.optString("type").orEmpty(),
            flow = ""
        )
    }

    internal fun buildConfigJson(
        protocol: String, host: String, port: Int, uuid: String,
        security: String, sni: String, network: String, flow: String,
        path: String = "", headerType: String = "", publicKey: String = "",
        shortId: String = "", fingerprint: String = "chrome"
    ): String {
        val config = JSONObject()

        // 1. Log Configuration
        config.put("log", JSONObject().put("loglevel", "debug"))

        // 2. DNS Configuration
        config.put("dns", JSONObject().put("servers", JSONArray().put(DEFAULT_DNS).put("1.1.1.1")))

        // 3. Inbound Configuration (Socks for Bridge)
        val inbound = JSONObject().apply {
            put("port", DEFAULT_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
            })
        }
        config.put("inbounds", JSONArray().put(inbound))

        // 4. Outbound Configuration (The Proxy)
        val outbound = JSONObject().apply {
            put("protocol", protocol)
            put("tag", "proxy")
            put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().apply {
                put("address", host)
                put("port", port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", uuid)
                    if (flow.isNotEmpty()) put("flow", flow)
                    put("encryption", if (protocol == "vless") "none" else "auto")
                }))
            })))

            val streamSettings = JSONObject().apply {
                put("network", network)
                put("security", security)

                when (security) {
                    "tls" -> put("tlsSettings", JSONObject().put("serverName", if (sni.isNotEmpty()) sni else host))
                    "reality" -> put("realitySettings", JSONObject().apply {
                        put("serverName", if (sni.isNotEmpty()) sni else host)
                        put("publicKey", publicKey)
                        put("shortId", shortId)
                        put("fingerprint", fingerprint)
                    })
                }

                if (network == "ws") {
                    put("wsSettings", JSONObject().put("path", path))
                } else if (network == "tcp" && headerType == "http") {
                    put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http")))
                }
            }
            put("streamSettings", streamSettings)
        }

        // 5. Direct Outbound (Freedom)
        val freedom = JSONObject().put("protocol", "freedom").put("tag", "direct")
        config.put("outbounds", JSONArray().put(outbound).put(freedom))

        // 6. Routing Configuration
        config.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().put(JSONObject().apply {
                put("type", "field")
                put("port", 53)
                put("outboundTag", "direct")
            }))
        })

        return config.toString()
    }

    /**
     * Fixes potential padding issues in Base64 strings before decoding.
     */
    private fun decodeBase64(input: String): String {
        var str = input
        while (str.length % 4 != 0) str += "="
        val bytes = try {
            Base64.decode(str, Base64.DEFAULT)
        } catch (e: Exception) {
            Base64.decode(str, Base64.URL_SAFE)
        }
        return String(bytes, StandardCharsets.UTF_8)
    }
}