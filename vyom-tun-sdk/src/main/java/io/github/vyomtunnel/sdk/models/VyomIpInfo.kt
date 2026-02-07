package io.github.vyomtunnel.sdk.models

import org.json.JSONObject

data class VyomIpInfo(
    val ip: String = "Unknown",
    val country: String = "Unknown",
    val countryCode: String = "",
    val region: String = "",
    val city: String = "",
    val isp: String = "Unknown",
    val timezone: String = ""
) {
    companion object {
        fun fromJson(json: String): VyomIpInfo {
            val obj = JSONObject(json)
            return VyomIpInfo(
                ip = obj.optString("query", "Unknown"),
                country = obj.optString("country", "Unknown"),
                countryCode = obj.optString("countryCode", ""),
                region = obj.optString("regionName", ""),
                city = obj.optString("city", ""),
                isp = obj.optString("isp", "Unknown"),
                timezone = obj.optString("timezone", "")
            )
        }
    }
}