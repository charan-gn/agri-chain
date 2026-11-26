package com.agrichain.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi NSD/TCP link, ported from the Offline Mesh Chat app's NsdChatManager.
 * Peers advertise an `_agrichain._tcp.` service on the local network and open a
 * short-lived TCP connection per envelope. The payload is the same signed
 * [MeshEnvelope] the BLE, Nearby, SMS and LoRa links carry; TTL, dedupe and ACK
 * rules stay in [RelayEngine].
 */
object WifiMeshProtocol {
    const val SERVICE_TYPE = "_agrichain._tcp."
    private const val NAME_PREFIX = "AgriChain-"
    private const val SEPARATOR = '\t'

    /** NSD names must be ASCII and short; the suffix is the relay peer id. */
    fun serviceName(identity: String): String =
        NAME_PREFIX + sanitizePeerId(identity).take(32).ifEmpty { "phone" }

    fun peerId(serviceName: String): String? =
        serviceName.takeIf { it.startsWith(NAME_PREFIX) }
            ?.removePrefix(NAME_PREFIX)
            ?.let { sanitizePeerId(it).takeIf { id -> id.isNotEmpty() } }

    fun sanitizePeerId(raw: String): String =
        raw.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)

    /** One line, tab separated; JSON escapes literal tabs inside chat text. */
    fun frame(peerId: String, envelope: MeshEnvelope): String =
        sanitizePeerId(peerId) + SEPARATOR + envelope.encode()

    fun parse(line: String): Pair<String, MeshEnvelope>? {
        val split = line.indexOf(SEPARATOR)
        if (split <= 0) return null
        val peerId = sanitizePeerId(line.substring(0, split))
        if (peerId.isEmpty()) return null
        val envelope = MeshEnvelope.parse(line.substring(split + 1)) ?: return null
        return peerId to envelope
    }
}

class WifiMeshTransport(
    context: Context?,
    private val identity: String,
) : Transport {

    override val id: String = "wifi"
    override val maxPayload: Int = 32_000

    private val app = context?.applicationContext
    private val nsd: NsdManager? =
        runCatching { app?.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
    private val localPeerId = WifiMeshProtocol.sanitizePeerId(identity)

    /** peer id -> host and port, learned from NSD resolution or an inbound socket. */
    private val peers = ConcurrentHashMap<String, Pair<String, Int>>()

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var onEnvelope: ((MeshEnvelope, Transport, String?) -> Unit)? = null
    @Volatile private var onLog: ((String) -> Unit)? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun available(): Boolean = running && server != null && nsd != null

    fun peerCount(): Int = peers.keys.count { it != localPeerId }

    override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) {
        startWithPeer({ envelope, transport, _ -> onReceive(envelope, transport) }, onLog)
    }

    override fun startWithPeer(
        onReceive: (MeshEnvelope, Transport, String?) -> Unit,
        onLog: (String) -> Unit,
    ) {
        if (running) return
        if (app == null || nsd == null) {
            onLog("wi-fi: NSD unavailable")
            return
        }
        running = true
        this.onEnvelope = onReceive
        this.onLog = onLog
        openServer()
        registerService()
        startDiscovery()
        log("started as $localPeerId")
    }

    override fun stop() {
        if (!running) return
        running = false
        stopDiscovery()
        unregisterService()
        runCatching { server?.close() }.onFailure { log("socket close: ${it.message}") }
        server = null
        multicastLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
            multicastLock = null
        }
        peers.clear()
        onEnvelope = null
        onLog = null
        log("stopped")
    }

    override fun peers(envelope: MeshEnvelope): Set<String> = knownPeers()

    override fun knownPeers(): Set<String> =
        peers.keys.filter { it != localPeerId }.toSet()

    override fun send(envelope: MeshEnvelope): Int {
        if (!available()) return 0
        val targets = peers.filterKeys { it != localPeerId }
        if (targets.isEmpty()) return 0
        val line = WifiMeshProtocol.frame(localPeerId, envelope)
        var delivered = 0
        for ((peerId, address) in targets) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address.first, address.second), 2_000)
                    val writer = PrintWriter(
                        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8),
                        true,
                    )
                    writer.println(line)
                    writer.flush()
                }
                delivered++
            } catch (error: Exception) {
                log("send to $peerId failed: ${error.message}")
            }
        }
        return delivered
    }

    private fun openServer() {
        try {
            val socket = ServerSocket(0)
            server = socket
            log("server on port ${socket.localPort}")
            Thread({ acceptLoop(socket) }, "agrichain-wifi-server").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            log("server failed: ${error.message}")
            running = false
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                handleIncoming(socket.accept())
            } catch (_: SocketException) {
                break
            } catch (error: Exception) {
                if (running) log("accept failed: ${error.message}")
            }
        }
    }

    private fun handleIncoming(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5_000
            val line = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                .readLine() ?: return
            val (peerId, envelope) = WifiMeshProtocol.parse(line) ?: return
            if (peerId == localPeerId) return
            rememberPeer(peerId, client.inetAddress.hostAddress.orEmpty(), client.port)
            onEnvelope?.invoke(envelope, this, peerId)
        }
    }

    private fun rememberPeer(peerId: String, host: String, port: Int) {
        if (peerId.isEmpty() || peerId == localPeerId || host.isEmpty()) return
        peers[peerId] = host to port
    }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = WifiMeshProtocol.serviceName(identity)
            serviceType = WifiMeshProtocol.SERVICE_TYPE
            port = server?.localPort ?: -1
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) =
                log("advertising ${service.serviceName}")

            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) =
                log("advertise failed: $errorCode")

            override fun onServiceUnregistered(service: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) =
                log("unregister failed: $errorCode")
        }
        registrationListener = listener
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { log("advertise failed: ${it.message}") }
    }

    private fun unregisterService() {
        registrationListener?.let { listener ->
            runCatching { nsd?.unregisterService(listener) }
            registrationListener = null
        }
    }

    private fun startDiscovery() {
        val manager = nsd ?: return
        val wifi = runCatching {
            app?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()
        val lock = runCatching { wifi?.createMulticastLock("agrichain-relay") }.getOrNull()
        if (lock != null && !lock.isHeld) {
            runCatching { lock.acquire() }
        }
        multicastLock = lock

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log("scanning for peers")

            override fun onServiceFound(service: NsdServiceInfo) {
                val peerId = WifiMeshProtocol.peerId(service.serviceName) ?: return
                if (peerId == localPeerId) return
                runCatching { manager.resolveService(service, resolveListener) }
                    .onFailure { log("resolve failed: ${it.message}") }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                WifiMeshProtocol.peerId(service.serviceName)?.let { peers.remove(it) }
                log("peer lost")
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                log("scan start failed: $errorCode")

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) =
                log("scan stop failed: $errorCode")
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(WifiMeshProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { log("scan failed: ${it.message}") }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) =
            log("resolve failed: $errorCode")

        override fun onServiceResolved(service: NsdServiceInfo) {
            val peerId = WifiMeshProtocol.peerId(service.serviceName) ?: return
            val host = service.host?.hostAddress ?: return
            rememberPeer(peerId, host, service.port)
            log("peer found: $peerId")
        }
    }

    private fun stopDiscovery() {
        val manager = nsd ?: return
        val listener = discoveryListener ?: return
        try {
            manager.stopServiceDiscovery(listener)
        } catch (error: Exception) {
            log("scan stop failed: ${error.message}")
        }
        discoveryListener = null
    }

    private fun log(line: String) {
        Log.i(TAG, "wifi: $line")
        onLog?.invoke(line)
    }

    companion object {
        private const val TAG = "AgriChain"
    }
}
