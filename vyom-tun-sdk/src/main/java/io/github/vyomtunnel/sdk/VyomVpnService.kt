package io.github.vyomtunnel.sdk

import android.app.Application.getProcessName
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import hev.htproxy.TProxyService
import io.github.vyomtunnel.core.NativeEngine
import java.io.File
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

class VyomVpnService : TProxyService() {

    companion object {
        private const val TAG = "VyomVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "io.github.vyomtunnel.vpn"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_MTU = 1280
        private const val LOCAL_ADDRESS = "172.19.0.1"
        private const val BRIDGE_PORT = 20808
        private const val BRIDGE_ADDRESS = "127.0.0.1"

        // Intent Keys
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val NOTIF_TITLE = "NOTIF_TITLE"
        const val NOTIF_CONTENT = "NOTIF_CONTENT"
        const val NOTIF_ICON = "NOTIF_ICON"
        const val NOTIF_CHANNEL = "NOTIF_CHANNEL"
    }

    private var statsTimer: Timer? = null
    private var lastUp: Long = 0
    private var lastDown: Long = 0
    private var tunInterface: ParcelFileDescriptor? = null
    private var healthCheckTimer: Timer? = null
    private var lastTotalRx: Long = 0
    private var noDataCount = 0

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (!VyomVpnManager.isAutoReconnectEnabled(this@VyomVpnService)) {
                Log.i(TAG, "Auto-reconnect disabled by user")
                return
            }

            if (!VyomVpnManager.wasVpnRunning(this@VyomVpnService)) {
                Log.i(TAG, "VPN not marked alive, skipping reconnect")
                return
            }

            if (VyomVpnManager.currentState != VyomState.CONNECTED) return

            val config = VyomVpnManager.getLastConfig(this@VyomVpnService) ?: return

            Log.i(TAG, "Network changed → restarting Xray")

            NativeEngine.stopXray()
            NativeEngine.startXray(config, filesDir.absolutePath)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(TAG, "Network connection lost")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setupForegroundService(intent)

        when (intent?.action) {
            "START_VPN" -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: ""
                startVpn(config)
            }
            "STOP_VPN" -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(xrayConfig: String) {
        val assetPath = filesDir.absolutePath
        val sessionName = VyomVpnManager.getCustomName(this) ?: "Vyom VPN"
        Log.i("VyomVPN", "=== START VPN ===")

        thread(name = "VyomStartup") {
            try {
                NativeEngine.stopXray()
                TProxyStopService()
                Thread.sleep(300)

                val result = NativeEngine.startXray(xrayConfig, assetPath)
                Log.i("VyomVPN", "Xray started: $result")
                Thread.sleep(1000)

                val builder = Builder()
                    .setSession(sessionName)
                    .setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .allowFamily(android.system.OsConstants.AF_INET)
                    .addDisallowedApplication(packageName)

                val excludedApps = VyomVpnManager.getExcludedApps(this)
                for (pkg in excludedApps) {
                    try {
                        if (pkg != packageName) {
                            builder.addDisallowedApplication(pkg)
                            Log.i("VyomVPN", "SplitTunnel EXCLUDE: $pkg")
                        }
                    } catch (e: Exception) {
                        Log.w("VyomVPN", "Failed to exclude $pkg", e)
                    }
                }

                if (VyomVpnManager.isKillSwitchEnabled(this)) {
                    builder.setBlocking(true)
                }

                tunInterface = builder.establish()
                val fd = tunInterface?.fd ?: throw IllegalStateException("TUN failed")
                Log.i("VyomVPN", "TUN OK FD=$fd")

                val tunFile = File(filesDir, "tun.yaml")
                tunFile.writeText(
                    """
                socks5:
                  address: 127.0.0.1
                  port: 20808
                tcp:
                  enabled: true
                udp:
                  enabled: true
                dns:
                  enabled: true
                """.trimIndent()
                )

                TProxyStartService(tunFile.absolutePath, fd)

                startStatsTicker()
                notifyStatus(VyomState.CONNECTED)
                Log.i("VyomVPN", "=== VPN CONNECTED ===")

            } catch (e: Exception) {
                Log.e("VyomVPN", "VPN START FAILED", e)
                notifyStatus(VyomState.ERROR)
            }
        }
    }

    private fun stopVpn() {
        notifyStatus(VyomState.STOPPING)
        healthCheckTimer?.cancel()

        thread(start = true, name = "VyomShutdownThread") {
            try {
                stopStatsTicker()
                tunInterface?.close()
                tunInterface = null

                this@VyomVpnService.TProxyStopService()
                NativeEngine.stopXray()

                notifyStatus(VyomState.DISCONNECTED)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Vpn stop crash", e)
                notifyStatus(VyomState.DISCONNECTED)
                stopSelf()
            }
        }
    }

    private fun startStatsTicker() {
        statsTimer?.cancel()
        statsTimer = Timer()
        statsTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val stats = this@VyomVpnService.TProxyGetStats()
                if (stats.size >= 4) {
                    val currentUp = stats[1]
                    val currentDown = stats[3]

                    val speedUp = if (lastUp > 0) currentUp - lastUp else 0
                    val speedDown = if (lastDown > 0) currentDown - lastDown else 0

                    lastUp = currentUp
                    lastDown = currentDown

                    notifyTraffic(speedUp, speedDown)
                }
            }
        }, 0L, 1000L)
    }

    private fun stopStatsTicker() {
        statsTimer?.cancel()
        statsTimer = null
        lastUp = 0
        lastDown = 0
    }

    private fun setupForegroundService(intent: Intent?) {
        val customName = VyomVpnManager.getCustomName(this)
        val customIcon = VyomVpnManager.getCustomIcon(this)
        val appLabel = applicationInfo.loadLabel(packageManager).toString()
        val title = customName ?: appLabel
        val content = intent?.getStringExtra(NOTIF_CONTENT) ?: "VPN is active"
        val iconRes = if (customIcon != 0) customIcon else applicationInfo.icon
        val channelName = intent?.getStringExtra(NOTIF_CHANNEL) ?: "VPN Service"

        createNotificationChannel(channelName)

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(iconRes)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel(channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.webkit.WebView.setDataDirectorySuffix("xray_process")
            } catch (e: Exception) {
                Log.e(TAG, e.message.toString())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onDestroy() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            healthCheckTimer?.cancel()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun notifyStatus(state: VyomState) {
        val intent = Intent(VyomVpnManager.ACTION_VPN_STATE)
        intent.putExtra("STATE", state.name)
        sendBroadcast(intent)
    }

    private fun notifyTraffic(up: Long, down: Long) {
        val intent = Intent(VyomVpnManager.ACTION_VPN_TRAFFIC)
        intent.putExtra("UP", up)
        intent.putExtra("DOWN", down)
        sendBroadcast(intent)
    }

    private fun startHealthGuard() {
        healthCheckTimer?.cancel()
        healthCheckTimer = Timer()
        healthCheckTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val stats = this@VyomVpnService.TProxyGetStats()
                if (stats.size >= 4) {
                    val currentTotalRx = stats[3] // Total Received Bytes

                    if (currentTotalRx > 0 && currentTotalRx == lastTotalRx) {
                        noDataCount++
                    } else {
                        noDataCount = 0
                    }

                    lastTotalRx = currentTotalRx

                    // If no new data for 10 seconds while connected
                    if (noDataCount >= 20) {
                        Log.w(TAG, "No internet traffic detected for 10s!")
                        val intent = Intent(VyomVpnManager.ACTION_NO_INTERNET)
                        sendBroadcast(intent)
                        noDataCount = 0 // Reset to avoid spamming
                    }
                }
            }
        }, 10000L, 1000L) // Check every second after 10s delay
    }
}