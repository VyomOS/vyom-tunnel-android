package io.github.vyomtunnel.core

internal object NativeEngine {
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
}