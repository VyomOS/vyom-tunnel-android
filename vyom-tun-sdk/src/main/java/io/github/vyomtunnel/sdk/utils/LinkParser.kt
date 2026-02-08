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
        protocol: String,
        host: String,
        port: Int,
        uuid: String,
        security: String,
        sni: String,
        network: String,
        flow: String,
        path: String = "",
        headerType: String = "",
        publicKey: String = "",
        shortId: String = "",
        fingerprint: String = "chrome"
    ): String {

        val config = JSONObject()

        config.put("log", JSONObject().put("loglevel", "warning"))

        config.put("fakedns", JSONArray().put(
            JSONObject().apply {
                put("ipPool", "198.18.0.0/15")
                put("poolSize", 65535)
            }
        ))

        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")
                put("https://8.8.8.8/dns-query")
                put("localhost")
            })
            put("queryStrategy", "UseIPv4")
        })

        config.put("inbounds", JSONArray().put(
            JSONObject().apply {
                put("listen", "127.0.0.1")
                put("port", 20808)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                        put("fakedns")
                    })
                })
            }
        ))

        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", protocol)
            put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().apply {
                    put("address", host)
                    put("port", port)
                    put("users", JSONArray().put(
                        JSONObject().apply {
                            put("id", uuid)
                            put("encryption", if (protocol == "vless") "none" else "auto")
                            if (flow.isNotEmpty()) put("flow", flow)
                        }
                    ))
                }
            )))

            put("streamSettings", JSONObject().apply {
                put("network", network)
                put("security", security)

                when (security) {
                    "tls" -> put("tlsSettings", JSONObject().put(
                        "serverName", if (sni.isNotEmpty()) sni else host
                    ))
                    "reality" -> put("realitySettings", JSONObject().apply {
                        put("serverName", if (sni.isNotEmpty()) sni else host)
                        put("publicKey", publicKey)
                        put("shortId", shortId)
                        put("fingerprint", fingerprint)
                    })
                }

                if (network == "ws") {
                    put("wsSettings", JSONObject().put("path", path))
                }
                if (network == "tcp" && headerType == "http") {
                    put("tcpSettings", JSONObject().put("header",
                        JSONObject().put("type", "http")
                    ))
                }
            })
        }

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().put("domainStrategy", "UseIP"))
        }

        config.put("outbounds", JSONArray().apply {
            put(proxyOutbound)
            put(directOutbound)
        })

        config.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                    put("type", "field")
                    put("network", "udp")
                    put("port", 53)
                    put("outboundTag", "proxy")
                })

                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "proxy")
                })
            })
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