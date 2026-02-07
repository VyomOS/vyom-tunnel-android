package io.github.vyomtunnel.sdk.models

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

object ConnectionProfiler {

    private const val TAG = "VyomProfiler"
    private const val DEFAULT_HOST = "1.1.1.1"
    private const val DEFAULT_PORT = 80
    private const val PROBE_COUNT = 10
    private const val CONNECT_TIMEOUT = 2000
    private const val INTER_PROBE_DELAY = 100L

    /**
     * Runs a sequence of network probes to determine connection quality.
     * Operates on a background thread and returns results via [callback].
     */
    fun runDiagnostics(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        count: Int = PROBE_COUNT,
        callback: (VyomProfile) -> Unit
    ) {
        kotlin.concurrent.thread(start = true, name = "VyomProfilerThread") {
            val latencies = mutableListOf<Long>()
            var lostPackets = 0

            for (i in 1..count) {
                try {
                    val start = System.currentTimeMillis()
                    // Use 'use' to automatically close the socket regardless of success/fail
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
                        val rtt = System.currentTimeMillis() - start
                        latencies.add(rtt)
                    }
                } catch (e: Exception) {
                    lostPackets++
                    Log.w(TAG, "Probe $i failed: ${e.message}")
                }

                if (i < count) Thread.sleep(INTER_PROBE_DELAY)
            }

            if (latencies.isEmpty()) {
                Log.e(TAG, "All probes failed. Connection is likely dead.")
                callback(VyomProfile(0, 0, 100.0, 0))
                return@thread
            }

            // Calculations
            val avgLatency = latencies.average().toLong()
            val packetLoss = (lostPackets.toDouble() / count.toDouble()) * 100.0

            // Jitter calculation (Standard RFC deviation method)
            var totalJitter: Long = 0
            for (i in 0 until latencies.size - 1) {
                totalJitter += abs(latencies[i] - latencies[i + 1])
            }
            val jitter = if (latencies.size > 1) totalJitter / (latencies.size - 1) else 0

            val qualityScore = calculateScore(avgLatency, jitter, packetLoss)

            val profile = VyomProfile(avgLatency, jitter, packetLoss, qualityScore)
            Log.i(TAG, "Diagnostics complete: $profile")
            callback(profile)
        }
    }

    /**
     * Internal algorithm to convert raw metrics into a user-friendly 0-100 score.
     */
    private fun calculateScore(latency: Long, jitter: Long, loss: Double): Int {
        val lossPenalty = loss * 6.0          // High penalty for any packet loss
        val jitterPenalty = jitter / 1.5      // Moderate penalty for instability
        val latencyPenalty = latency / 25.0   // Lower penalty for distance (latency)

        val rawScore = 100.0 - (lossPenalty + jitterPenalty + latencyPenalty)
        return rawScore.toInt().coerceIn(0, 100)
    }
}