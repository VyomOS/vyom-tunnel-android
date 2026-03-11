package io.github.vyomtunnel.core

import android.net.VpnService

internal object NativeEngine {
    var vpnService: VpnService? = null

    init {
        try {
            System.loadLibrary("xray")
            System.loadLibrary("hev-socks5-tunnel")
            System.loadLibrary("vyom-v2ray")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun startXray(config: String, assetPath: String): Int

    external fun stopXray()

    external fun validateConfig(config: String, assetPath: String): String?

    @JvmStatic
    fun protectSocket(fd: Int): Boolean {
        return vpnService?.protect(fd) ?: false
    }
}