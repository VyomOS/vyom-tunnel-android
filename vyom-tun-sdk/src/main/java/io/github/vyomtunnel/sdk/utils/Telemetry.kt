package io.github.vyomtunnel.sdk.utils

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import io.github.vyomtunnel.sdk.BuildConfig

internal object Telemetry {

    fun sendUsagePing(context: Context) {
        if (BuildConfig.GA_ID.isEmpty()) return

        thread {
            try {
                val payload = JSONObject().apply {
                    put("client_id", context.packageName)
                    put("events", JSONArray().apply {
                        put(JSONObject().apply {
                            put("name", "sdk_initialize")
                            put("params", JSONObject().apply {
                                put("host_package", context.packageName)
                                put("sdk_version", BuildConfig.VERSION_NAME)
                                put("android_res", "${Build.VERSION.SDK_INT}")
                            })
                        })
                    })
                }

                val url =
                    URL("https://www.google-analytics.com/mp/collect?measurement_id=${BuildConfig.GA_ID}&api_secret=${BuildConfig.GA_SECRET}")
                with(url.openConnection() as HttpURLConnection) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(payload.toString().toByteArray()) }
                    disconnect()
                }
            } catch (ignored: Exception) {}
        }
    }
}