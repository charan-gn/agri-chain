package com.agrichain.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure Wi-Fi mesh framing. Kept outside the Android transport so the JVM tests
 * can prove the beacon, length-prefix and link-election rules.
 */
object WifiProtocol {
    const val SERVICE_TYPE = "_agrimesh._tcp."
    const val BEACON_PORT = 47474
    private const val NAME_PREFIX = "AgriMesh-"
    private val MAGIC = byteArrayOf(0x41, 0x4D, 0x42, 0x31) // "AMB1"

    data class Beacon(val actorId: String, val port: Int, val host: String? = null)

    fun serviceName(actorHex: String): String = NAME_PREFIX + actorHex.take(16)

    fun actorFromServiceName(name: String): String? {
        if (!name.startsWith(NAME_PREFIX)) return null
        val actor = name.removePrefix(NAME_PREFIX).take(16)
        return actor.takeIf { it.length == 16 && it.all { ch -> ch.isDigit() || ch in 'a'..'f' } }
    }

    fun beacon(actorId: ByteArray, port: Int): ByteArray {
        require(actorId.size == 8) { "actorId must be 8 bytes" }
        require(port in 1..65535) { "port out of range" }
        return MAGIC + actorId + byteArrayOf((port ushr 8).toByte(), port.toByte())
    }

    fun parseBeacon(data: ByteArray, host: String? = null): Beacon? {
        if (data.size != 14) return null
        if (!data.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val actor = V3Codec.hex(data.copyOfRange(4, 12))
        val port = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
        if (port == 0) return null
        return Beacon(actor, port, host)
    }

    /** Four-byte big-endian length prefix; length 0 is a keep-alive ping. */
    fun frame(envelope: ByteArray): ByteArray {
        require(envelope.size <= 65_535) { "envelope too large" }
        return byteArrayOf(
            (envelope.size ushr 24).toByte(),
            (envelope.size ushr 16).toByte(),
            (envelope.size ushr 8).toByte(),
            envelope.size.toByte(),
        ) + envelope
    }

    fun writeFrame(out: DataOutputStream, envelope: ByteArray) {
        out.writeInt(envelope.size)
        if (envelope.isNotEmpty()) out.write(envelope)
        out.flush()
    }

    /** Returns an empty array for a ping, or null on a clean EOF. */
    fun readFrame(input: DataInputStream): ByteArray? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(length in 0..65_535) { "bad frame length $length" }
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        input.readFully(buffer)
        return buffer
    }

    /** fromId lives at envelope[8..15] because ttl is byte 0. */
    fun actorIdFromEnvelope(envelope: ByteArray): String? {
        if (envelope.size < 16) return null
        return V3Codec.hex(envelope.copyOfRange(8, 16))
    }

    /** The lower actorId opens the TCP link; the higher one waits. */
    fun shouldInitiate(me: String, peer: String): Boolean = me < peer
}

class WifiTransport(
    context: Context?,
    private val identity: V3Identity,
) : Transport {

    override val id: String = "wifi"
    override val meshLinks: Boolean = true
    override val maxFrame: Int = 65_535

    private val app = context?.applicationContext
    private val nsd: NsdManager? =
        runCatching { app?.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
    private val me: String = identity.actorHex

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _links = MutableStateFlow<Set<LinkInfo>>(emptySet())
    override val links: StateFlow<Set<LinkInfo>> = _links.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 128)
    override val incoming: Flow<Incoming> = _incoming.asSharedFlow()

    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<String, Conn>()

    private var server: ServerSocket? = null
    private var beaconSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private class Conn(
        val linkId: String,
        val peerId: String,
        val socket: Socket,
        val out: DataOutputStream,
        val initiatedByUs: Boolean,
    ) {
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        val writeLock = Any()
    }

    override suspend fun start() {
        if (!running.compareAndSet(false, true)) return
        if (app == null || nsd == null) {
            _state.value = TransportState.ERROR
            log("NSD unavailable")
            running.set(false)
            return
        }
        _state.value = TransportState.STARTING
        runCatching { openServer() }
            .onFailure {
                log("server failed: ${it.message}")
                running.set(false)
                _state.value = TransportState.ERROR
                return
            }
        registerService()
        startDiscovery()
        acquireMulticastLock()
        startBeaconReceiver()
        startBeaconSender()
        startMaintenance()
        _state.value = TransportState.RUNNING
        log("started as $me on port ${server?.localPort}")
    }

    override suspend fun stop() {
        if (!running.compareAndSet(true, false)) return
        _state.value = TransportState.STOPPED
        stopDiscovery()
        unregisterService()
        runCatching { server?.close() }
        server = null
        runCatching { beaconSocket?.close() }
        beaconSocket = null
        connections.values.forEach { close(it) }
        connections.clear()
        _links.value = emptySet()
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
        log("stopped")
    }

    override suspend fun send(linkId: String?, envelope: ByteArray): SendResult {
        val targets = if (linkId != null) {
            connections[linkId]?.let { listOf(it) } ?: emptyList()
        } else {
            connections.values.toList()
        }
        if (targets.isEmpty()) return SendResult.NoLink("no wifi link")
        var sent = 0
        for (conn in targets) {
            try {
                synchronized(conn.writeLock) {
                    WifiProtocol.writeFrame(conn.out, envelope)
                }
                sent += 1
            } catch (error: Exception) {
                log("send to ${conn.peerId} failed: ${error.message}")
                drop(conn)
            }
        }
        return if (sent == 0) SendResult.Failed("wifi write failed") else SendResult.Sent(sent)
    }

    private fun openServer() {
        val socket = ServerSocket(0)
        server = socket
        executor.execute {
            while (running.get()) {
                try {
                    accept(socket.accept())
                } catch (_: SocketException) {
                    break
                } catch (error: Exception) {
                    if (running.get()) log("accept failed: ${error.message}")
                }
            }
        }
    }

    private fun accept(socket: Socket) {
        socket.tcpNoDelay = true
        executor.execute { readLoop(socket, inbound = true) }
    }

    private fun readLoop(socket: Socket, inbound: Boolean, existing: Conn? = null) {
        var conn: Conn? = existing
        try {
            val input = DataInputStream(socket.getInputStream())
            val out = DataOutputStream(socket.getOutputStream())
            while (running.get()) {
                val envelope = WifiProtocol.readFrame(input) ?: break
                val current = conn
                if (current == null) {
                    if (envelope.isEmpty()) continue
                    val peer = WifiProtocol.actorIdFromEnvelope(envelope) ?: break
                    if (peer == me) break
                    conn = register(peer, socket, out, initiatedByUs = !inbound)
                    if (conn == null) break
                } else {
                    current.lastSeen = System.currentTimeMillis()
                }
                if (envelope.isNotEmpty()) {
                    val active = conn ?: break
                    _incoming.tryEmit(
                        Incoming(
                            envelope = envelope,
                            linkId = active.linkId,
                            transportId = id,
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            if (running.get()) log("link closed: ${error.message}")
        } finally {
            conn?.let { drop(it) }
            runCatching { socket.close() }
        }
    }

    private fun connect(peer: String, host: String, port: Int) {
        if (!running.get()) return
        if (connections.containsKey("wifi:$peer")) return
        executor.execute {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 3_000)
                val out = DataOutputStream(socket.getOutputStream())
                val conn = register(peer, socket, out, initiatedByUs = true) ?: return@execute
                executor.execute { readLoop(socket, inbound = false, existing = conn) }
                log("connected to $peer at $host:$port (${conn.linkId})")
            } catch (error: Exception) {
                log("connect to $peer failed: ${error.message}")
            }
        }
    }

    /**
     * Keep exactly one link per actor. If both sides opened a socket, the link
     * initiated by the higher actorId is closed (the spec's tie-break).
     */
    private fun register(
        peer: String,
        socket: Socket,
        out: DataOutputStream,
        initiatedByUs: Boolean,
    ): Conn? {
        val linkId = "wifi:$peer"
        val candidate = Conn(linkId, peer, socket, out, initiatedByUs)
        val existing = connections[linkId]
        if (existing != null) {
            val keepNew = initiatorRank(candidate) < initiatorRank(existing)
            if (keepNew) {
                connections[linkId] = candidate
                close(existing)
            } else {
                close(candidate)
                return null
            }
        } else {
            connections[linkId] = candidate
        }
        publishLinks()
        return connections[linkId]
    }

    private fun initiatorRank(conn: Conn): String =
        if (conn.initiatedByUs) me else conn.peerId

    private fun drop(conn: Conn) {
        if (connections.remove(conn.linkId, conn)) {
            close(conn)
            publishLinks()
        }
    }

    private fun close(conn: Conn) {
        runCatching { conn.socket.close() }
    }

    private fun publishLinks() {
        _links.value = connections.values.map { conn ->
            LinkInfo(linkId = conn.linkId, transportId = id, peerId = conn.peerId)
        }.toSet()
    }

    private fun registerService() {
        val port = server?.localPort ?: return
        val info = NsdServiceInfo().apply {
            serviceName = WifiProtocol.serviceName(me)
            serviceType = WifiProtocol.SERVICE_TYPE
            this.port = port
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
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = log("scanning for peers")

            override fun onServiceFound(service: NsdServiceInfo) {
                val peer = WifiProtocol.actorFromServiceName(service.serviceName) ?: return
                if (peer == me) return
                runCatching { manager.resolveService(service, resolveListener) }
                    .onFailure { log("resolve failed: ${it.message}") }
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                log("scan start failed: $errorCode")

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) =
                log("scan stop failed: $errorCode")
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(
                WifiProtocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        }.onFailure { log("scan failed: ${it.message}") }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) =
            log("resolve failed: $errorCode")

        override fun onServiceResolved(service: NsdServiceInfo) {
            val peer = WifiProtocol.actorFromServiceName(service.serviceName) ?: return
            val host = service.host?.hostAddress ?: return
            log("found $peer at $host:${service.port}")
            if (WifiProtocol.shouldInitiate(me, peer)) {
                connect(peer, host, service.port)
            }
        }
    }

    private fun stopDiscovery() {
        val manager = nsd ?: return
        val listener = discoveryListener ?: return
        runCatching { manager.stopServiceDiscovery(listener) }
        discoveryListener = null
    }

    private fun acquireMulticastLock() {
        val wifi = runCatching {
            app?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return
        val lock = runCatching { wifi.createMulticastLock("agrimesh-wifi") }.getOrNull() ?: return
        runCatching {
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        }
    }

    private fun startBeaconReceiver() {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(WifiProtocol.BEACON_PORT))
        }
        beaconSocket = socket
        executor.execute {
            val buffer = ByteArray(64)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val beacon = WifiProtocol.parseBeacon(
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                        packet.address?.hostAddress,
                    ) ?: continue
                    if (beacon.actorId == me) continue
                    val host = beacon.host ?: continue
                    if (WifiProtocol.shouldInitiate(me, beacon.actorId)) {
                        connect(beacon.actorId, host, beacon.port)
                    }
                } catch (_: SocketException) {
                    break
                } catch (error: Exception) {
                    if (running.get()) log("beacon receive failed: ${error.message}")
                }
            }
        }
    }

    private fun startBeaconSender() {
        executor.execute {
            while (running.get()) {
                val port = server?.localPort
                if (port != null) {
                    val payload = WifiProtocol.beacon(identity.actorId, port)
                    for (address in broadcastAddresses()) {
                        runCatching {
                            DatagramSocket().use { socket ->
                                socket.broadcast = true
                                socket.send(
                                    DatagramPacket(
                                        payload,
                                        payload.size,
                                        address,
                                        WifiProtocol.BEACON_PORT,
                                    ),
                                )
                            }
                        }
                    }
                }
                sleep(10_000)
            }
        }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = mutableSetOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                val name = nic.name ?: ""
                if (name.startsWith("rmnet") || name.startsWith("ccmni")) continue
                for (address: InterfaceAddress in nic.interfaceAddresses) {
                    address.broadcast?.let { result.add(it) }
                }
            }
        }
        if (result.isEmpty()) {
            runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        }
        return result
    }

    private fun startMaintenance() {
        executor.execute {
            while (running.get()) {
                val now = System.currentTimeMillis()
                for (conn in connections.values) {
                    if (now - conn.lastSeen > 60_000) {
                        log("link ${conn.peerId} timed out")
                        drop(conn)
                        continue
                    }
                    runCatching {
                        synchronized(conn.writeLock) { WifiProtocol.writeFrame(conn.out, ByteArray(0)) }
                    }
                }
                sleep(20_000)
            }
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun log(line: String) {
        Log.i(TAG, "wifi: $line")
    }

    companion object {
        private const val TAG = "AgriMesh"
    }
}
