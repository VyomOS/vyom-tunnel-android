package io.github.vyomtunnel.sdk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.github.vyomtunnel.core.NativeEngine
import io.github.vyomtunnel.sdk.models.ConnectionProfiler
import io.github.vyomtunnel.sdk.models.VyomIpInfo
import io.github.vyomtunnel.sdk.models.VyomProfile
import io.github.vyomtunnel.sdk.utils.AssetUtils
import io.github.vyomtunnel.sdk.utils.LinkParser
import io.github.vyomtunnel.sdk.utils.Telemetry
import io.github.vyomtunnel.sdk.BuildConfig

object VyomVpnManager {

    private const val TAG = "VyomVpnManager"

    // Broadcast Actions
    const val ACTION_VPN_STATE = "io.github.vyomtunnel.VPN_STATE"
    const val ACTION_VPN_TRAFFIC = "io.github.vyomtunnel.VPN_TRAFFIC"

    // Persistence Keys
    private const val PREFS_NAME = "vyom_vpn_prefs"
    private const val KEY_LAST_CONFIG = "last_config"
    private const val KEY_VPN_ALIVE = "vpn_should_be_running"
    private const val KEY_EXCLUDED_APPS = "excluded_apps_list"

    private var isInitialized = false
    private var internalReceiver: BroadcastReceiver? = null
    private var vpnListener: VyomListener? = null
    private var notificationConfig = VyomNotificationConfig()
    private val excludedApps = mutableSetOf<String>()
    private const val KEY_KILL_SWITCH = "kill_switch_enabled"
    const val ACTION_NO_INTERNET = "io.github.vyomtunnel.NO_INTERNET"

    var currentState: VyomState = VyomState.IDLE
        private set

    /**
     * Interface for the host application to receive updates.
     */
    interface VyomListener {
        fun onStateChanged(state: VyomState)
        fun onTrafficUpdate(up: Long, down: Long)
    }

    /**
     * Data class for UI branding of the VPN notification.
     */
    data class VyomNotificationConfig(
        val title: String? = null,
        val content: String? = null,
        val iconResId: Int? = null,
        val channelName: String = "VPN Service"
    )

    // --- CORE API ---

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            AssetUtils.copyAssets(context)
            System.loadLibrary("xray")
            System.loadLibrary("vyom-v2ray")
            loadSavedExclusions(context)
            Telemetry.sendUsagePing(context)
            initInternalFirebase(context)
            isInitialized = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native libraries failed to load", e)
        }
    }

    private fun initInternalFirebase(context: Context) {
        if (BuildConfig.FB_APP_ID.isEmpty()) return
        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FB_APP_ID)
                .setApiKey(BuildConfig.FB_API_KEY)
                .setProjectId(BuildConfig.FB_PROJECT_ID)
                .build()

            val appName = "VyomSDKCore"
            if (FirebaseApp.getApps(context).none { it.name == appName }) {
                FirebaseApp.initializeApp(context, options, appName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase setup failed")
        }
    }

    fun connect(context: Context, linkOrJson: String): String? {
        return try {
            val finalConfig = if (linkOrJson.trim().startsWith("{")) {
                linkOrJson
            } else {
                LinkParser.parse(linkOrJson)
            }

            val validationError = validateConfig(context, finalConfig)
            if (validationError != null) return validationError

            start(context, finalConfig)
            null // Success
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

    fun connectWithPermission(activity: Activity, input: String) {
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            saveConfig(activity, if (input.startsWith("{")) input else LinkParser.parse(input))
            activity.startActivity(Intent(activity, VyomPermissionActivity::class.java))
        } else {
            connect(activity, input)
        }
    }

    fun start(context: Context, configJson: String) {
        saveConfig(context, configJson)
        setVpnShouldRun(context, true)

        val intent = Intent(context, VyomVpnService::class.java).apply {
            action = "START_VPN"
            putExtra(VyomVpnService.EXTRA_CONFIG, configJson)
            putExtra(VyomVpnService.NOTIF_TITLE, notificationConfig.title)
            putExtra(VyomVpnService.NOTIF_CONTENT, notificationConfig.content)
            putExtra(VyomVpnService.NOTIF_ICON, notificationConfig.iconResId ?: 0)
            putExtra(VyomVpnService.NOTIF_CHANNEL, notificationConfig.channelName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        setVpnShouldRun(context, false)
        val intent = Intent(context, VyomVpnService::class.java).apply {
            action = "STOP_VPN"
        }
        context.startService(intent)
    }

    fun validateConfig(context: Context, config: String): String? {
        val assetPath = context.filesDir.absolutePath
        return NativeEngine.validateConfig(config, assetPath)
    }

    // --- LISTENERS & IPC (Inter-Process Communication) ---

    @RequiresApi(Build.VERSION_CODES.O)
    fun registerListener(context: Context, listener: VyomListener) {
        this.vpnListener = listener
        if (internalReceiver != null) return

        internalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_VPN_STATE -> {
                        val stateName = intent.getStringExtra("STATE") ?: return
                        currentState = VyomState.valueOf(stateName)
                        vpnListener?.onStateChanged(currentState)
                    }
                    ACTION_VPN_TRAFFIC -> {
                        val up = intent.getLongExtra("UP", 0L)
                        val down = intent.getLongExtra("DOWN", 0L)
                        vpnListener?.onTrafficUpdate(up, down)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_VPN_STATE)
            addAction(ACTION_VPN_TRAFFIC)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_EXPORTED else 0
        context.registerReceiver(internalReceiver, filter, flags)
    }

    fun unregisterListener(context: Context) {
        internalReceiver?.let {
            context.unregisterReceiver(it)
            internalReceiver = null
        }
        vpnListener = null
    }

    // --- APP SELECTION (SPLIT TUNNELING) ---

    fun toggleAppExclusion(context: Context, packageName: String) {
        if (excludedApps.contains(packageName)) excludedApps.remove(packageName)
        else excludedApps.add(packageName)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_EXCLUDED_APPS, excludedApps).apply()
    }

    fun getExcludedApps(context: Context): Set<String> {
        if (excludedApps.isEmpty()) loadSavedExclusions(context)
        return excludedApps
    }

    private fun loadSavedExclusions(context: Context) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
        excludedApps.clear()
        excludedApps.addAll(saved)
    }

    // --- DIAGNOSTICS & HELPERS ---

    fun checkInternet(callback: (Boolean) -> Unit) {
        kotlin.concurrent.thread {
            try {
                val conn = java.net.URL("http://connectivitycheck.gstatic.com/generate_204")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                callback(conn.responseCode == 204)
            } catch (e: Exception) {
                callback(false)
            }
        }
    }

    fun getPerformanceProfile(callback: (VyomProfile) -> Unit) {
        ConnectionProfiler.runDiagnostics(callback = callback)
    }

    fun setNotificationConfig(config: VyomNotificationConfig) {
        this.notificationConfig = config
    }

    // --- PERSISTENCE HELPERS ---

    private fun saveConfig(context: Context, config: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_CONFIG, config).apply()
    }

    private fun setVpnShouldRun(context: Context, shouldRun: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VPN_ALIVE, shouldRun).apply()
    }

    fun getLastConfig(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_CONFIG, null)

    fun wasVpnRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_VPN_ALIVE, false)

    fun setKillSwitch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_KILL_SWITCH, enabled).apply()
    }

    fun isKillSwitchEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KILL_SWITCH, false)
    }

    fun getCoreLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -t 50 VyomXrayCore:I *:S")
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Failed to fetch logs: ${e.message}"
        }
    }

    fun fetchIpInfo(callback: (VyomIpInfo?) -> Unit) {
        kotlin.concurrent.thread {
            try {
                val proxy = java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", 20808)
                )

                val url = java.net.URL("https://ipwho.is/")
                val conn = url.openConnection(proxy) as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val response = conn.inputStream.bufferedReader().use { it.readText() }

                val obj = org.json.JSONObject(response)
                val info = VyomIpInfo(
                    ip = obj.optString("ip", "Unknown"),
                    country = obj.optString("country", "Unknown"),
                    city = obj.optString("city", "Unknown"),
                    isp = obj.optString("connection", "{}").let {
                        org.json.JSONObject(it).optString("isp", "Unknown")
                    }
                )
                callback(info)
            } catch (e: Exception) {
                Log.e("VyomVPN", "Failed to fetch IP info: ${e.message}")
                callback(null)
            }
        }
    }

    fun isPermissionGranted(context: Context): Boolean {
        return android.net.VpnService.prepare(context) == null
    }
}