package com.agrichain.net

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the ledger without asking for an IP. Probes the saved URL, the USB
 * loopback (`adb reverse`), the emulator host, then broadcasts on the LAN where
 * `ledger/discovery.py` answers. The first healthy URL is saved for every screen.
 */
object LedgerDiscovery {

    const val DISCOVERY_PORT = 8100
    private const val PROBE_PATH = "/health"
    private const val TOKEN = "AGRICHAIN_DISCOVER"
    private const val TAG = "AgriChain"

    private val running = AtomicBoolean(false)
    private val waiters = mutableListOf<(String?) -> Unit>()
    private val lock = Any()

    fun candidates(saved: String): List<String> =
        listOf(saved, LedgerSettings.USB_BASE_URL, LedgerSettings.DEFAULT_BASE_URL)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()

    fun probe(baseUrl: String, timeoutMs: Int = 900): Boolean {
        val connection = try {
            URL(baseUrl.trimEnd('/') + PROBE_PATH).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            return false
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.responseCode == 200
        } catch (error: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    /** Blocking; call from a worker thread. Returns and persists the first healthy URL. */
    fun discover(context: Context, onLog: (String) -> Unit = {}): String? {
        val saved = LedgerSettings.baseUrl(context)
        if (probe(saved)) {
            onLog("ledger: saved $saved")
            return saved
        }
        onLog("ledger: $saved unreachable, searching")

        val direct = candidates(saved).drop(1).firstOrNull { candidate ->
            probe(candidate).also { healthy -> if (healthy) onLog("ledger: found $candidate") }
        }
        val found = direct
            ?: discoverLan(onLog).firstOrNull { probe(it, timeoutMs = 1_500) }

        return if (found != null) {
            LedgerSettings.setBaseUrl(context, found)
            onLog("ledger: using $found")
            found
        } else {
            onLog("ledger: not found (start ./tools/ledger.sh or ./tools/dev.sh)")
            null
        }
    }

    fun discoverAsync(
        context: Context,
        onLog: (String) -> Unit = {},
        onResult: (String?) -> Unit,
    ) {
        synchronized(lock) {
            if (running.get()) {
                waiters += onResult
                return
            }
            running.set(true)
        }
        Thread {
            var result: String? = null
            try {
                result = discover(context, onLog)
            } catch (error: Exception) {
                onLog("ledger: search failed (${error.message})")
            }
            val queued = synchronized(lock) {
                running.set(false)
                waiters.toList().also { waiters.clear() }
            }
            onResult(result)
            queued.forEach { it(result) }
        }.start()
    }

    private fun discoverLan(onLog: (String) -> Unit): List<String> {
        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 400
            }
        } catch (error: Exception) {
            onLog("ledger: udp unavailable (${error.message})")
            return emptyList()
        }

        val found = LinkedHashSet<String>()
        return try {
            val token = TOKEN.toByteArray(Charsets.UTF_8)
            socket.send(
                DatagramPacket(
                    token,
                    token.size,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT,
                )
            )
            val deadline = System.currentTimeMillis() + 1_500
            val buffer = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    parseReply(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        ?.let { found += it }
                } catch (_: SocketTimeoutException) {
                    // Keep listening until the deadline.
                }
            }
            Log.i(TAG, "LAN replies: $found")
            onLog(if (found.isEmpty()) "ledger: no LAN reply on udp/$DISCOVERY_PORT" else "ledger: LAN reply $found")
            found.toList()
        } catch (error: Exception) {
            onLog("ledger: udp failed (${error.message})")
            emptyList()
        } finally {
            socket.close()
        }
    }

    fun parseReply(text: String): String? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("service") != "agrichain") return null
        val base = json.optString("baseUrl").trim().trimEnd('/')
        return base.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
