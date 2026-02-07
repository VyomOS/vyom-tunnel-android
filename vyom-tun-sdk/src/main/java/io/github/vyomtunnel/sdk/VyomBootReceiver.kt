package io.github.vyomtunnel.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class VyomBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VyomBootReceiver"

        // Some devices use different actions for boot completion
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (BOOT_ACTIONS.contains(action)) {
            Log.i(TAG, "Device boot detected ($action). Checking persistence...")

            try {
                if (VyomVpnManager.wasVpnRunning(context)) {
                    val lastConfig = VyomVpnManager.getLastConfig(context)

                    if (lastConfig != null) {
                        Log.i(TAG, "Persistence active. Automatically restarting VPN...")
                        VyomVpnManager.start(context, lastConfig)
                    } else {
                        Log.w(TAG, "VPN was marked as running, but config was missing.")
                    }
                } else {
                    Log.d(TAG, "VPN was not running before shutdown. No action taken.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-restart VPN on boot", e)
            }
        }
    }
}