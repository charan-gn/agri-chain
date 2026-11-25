package com.agrichain.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.UUID

/**
 * Raw BLE GATT mesh, inspired by BitChat's link layer. Every phone advertises a
 * service UUID and scans for the same UUID. The lower 32-bit peer hash opens the
 * GATT connection, so two phones never race to connect to each other. One
 * characteristic is written by the central and notified by the peripheral.
 *
 * The relay engine owns TTL, event dedupe and store-and-forward. This transport
 * only fragments a [MeshEnvelope] into [BleMeshCodec] frames and reassembles
 * them, so it plugs into the same mesh as Nearby, SMS and Meshtastic.
 *
 * This is a clean-room implementation, not BitChat wire compatibility: BitChat
 * packets are a different binary format and use Noise sessions.
 */
class BleMeshTransport(
    private val context: Context?,
    private val identity: String = "agri",
    override val id: String = "ble",
    private val stubMode: Boolean = context == null,
) : Transport {

    override val maxPayload: Int = BleMeshCodec.MAX_FRAME

    private val localHash: Int = BleMeshCodec.peerHash(identity)
    private val reassembler = BleReassembler()
    private val seenEnvelopes = LinkedHashSet<String>()
    private val links = mutableMapOf<String, Link>()
    private val connecting = mutableSetOf<Int>()
    private val pendingHash = mutableMapOf<String, Int>()

    @Volatile private var running = false
    @Volatile private var adapter: BluetoothAdapter? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var scanner: BluetoothLeScanner? = null

    @Volatile private var onReceive: ((MeshEnvelope, Transport, String?) -> Unit)? = null
    @Volatile private var onLog: ((String) -> Unit)? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile var stubAvailable: Boolean = false
    val sentFrames = mutableListOf<ByteArray>()

    private class Link(
        val address: String,
        val device: BluetoothDevice,
        val gatt: BluetoothGatt?,
        val central: Boolean,
        @Volatile var peerId: String?,
    ) {
        @Volatile var ready = false
        @Volatile var notifyEnabled = false
        @Volatile var helloQueued = false
        @Volatile var gattCharacteristic: BluetoothGattCharacteristic? = null
        @Volatile var maxFrame = BleMeshCodec.MIN_FRAME
        @Volatile var mtuRequested = false
        @Volatile var started = false
        val queue = ArrayDeque<ByteArray>()
        var writing = false
    }

    override fun available(): Boolean {
        if (stubMode) return stubAvailable
        return running && hasPermissions() && adapter?.isEnabled == true
    }

    override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) {
        startWithPeer({ envelope, transport, _ -> onReceive(envelope, transport) }, onLog)
    }

    override fun startWithPeer(
        onReceive: (MeshEnvelope, Transport, String?) -> Unit,
        onLog: (String) -> Unit,
    ) {
        // A running transport is left alone, unless Bluetooth was turned off or
        // its permission was revoked; in that case rebuild advertising/scanning.
        if (running && (stubMode || available())) return
        if (running) stop()
        this.onReceive = onReceive
        this.onLog = onLog
        running = true
        if (stubMode) {
            logLine("stub ready")
            return
        }
        if (!hasPermissions()) {
            logLine("missing Bluetooth permission")
            running = false
            return
        }
        val manager = context?.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            logLine("adapter unavailable or turned off")
            running = false
            return
        }
        this.adapter = adapter
        openServer(manager)
        startAdvertising(adapter)
        startScanning(adapter)
        logLine("started (${peerLabel(localHash)})")
    }

    override fun stop() {
        running = false
        if (!stubMode) {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            runCatching { scanner?.stopScan(scanCallback) }
            runCatching { gattServer?.close() }
            synchronized(links) {
                links.values.forEach { link ->
                    runCatching { link.gatt?.close() }
                    synchronized(link.queue) { link.queue.clear() }
                }
                links.clear()
            }
            synchronized(connecting) { connecting.clear() }
            synchronized(pendingHash) { pendingHash.clear() }
            reassembler.clear()
        }
        advertiser = null
        scanner = null
        gattServer = null
        characteristic = null
        adapter = null
        onReceive = null
        onLog = null
    }

    override fun peers(envelope: MeshEnvelope): Set<String> {
        if (stubMode) return emptySet()
        return synchronized(links) {
            links.values.mapNotNull { link -> link.peerId.takeIf { link.ready } }.toSet()
        }
    }

    fun connectedPeerCount(): Int {
        if (stubMode) return if (stubAvailable) 1 else 0
        return synchronized(links) { links.values.count { it.ready && it.peerId != null } }
    }

    override fun send(envelope: MeshEnvelope): Int {
        if (!available()) return 0
        if (stubMode) {
            val frames = runCatching { BleMeshCodec.chunks(envelope) }.getOrNull() ?: return 0
            synchronized(sentFrames) { sentFrames.addAll(frames) }
            return 1
        }
        val targets = synchronized(links) {
            links.values.filter { it.ready }.toList()
        }
        if (targets.isEmpty()) return 0

        val frameSize = targets.minOf { it.maxFrame }
            .coerceIn(BleMeshCodec.MIN_FRAME, BleMeshCodec.MAX_FRAME)
        val parts = splitForFrames(envelope, frameSize)
        var linksSent = 0
        for (link in targets) {
            var queued = false
            for (part in parts) {
                val frames = runCatching { BleMeshCodec.chunks(part, link.maxFrame) }
                    .getOrElse {
                        logLine("envelope ${part.envelopeId.take(8)} does not fit: ${it.message}")
                        emptyList()
                    }
                if (frames.isEmpty()) continue
                synchronized(link.queue) {
                    while (link.queue.size + frames.size > MAX_QUEUE) {
                        link.queue.pollFirst() ?: break
                    }
                    link.queue.addAll(frames)
                }
                queued = true
            }
            if (queued) {
                linksSent++
                pump(link)
            }
        }
        return linksSent
    }

    /** Splits a large event batch until each part fits the current MTU. */
    private fun splitForFrames(envelope: MeshEnvelope, maxFrame: Int): List<MeshEnvelope> {
        if (envelope.kind != MeshKind.EVENTS || envelope.events.size <= 1) {
            return listOf(envelope)
        }
        return runCatching {
            BleMeshCodec.chunks(envelope, maxFrame)
            listOf(envelope)
        }.getOrElse {
            val middle = envelope.events.size / 2
            splitForFrames(
                envelope.copy(
                    envelopeId = MeshEnvelope.shortId(),
                    events = envelope.events.take(middle),
                ),
                maxFrame,
            ) + splitForFrames(
                envelope.copy(
                    envelopeId = MeshEnvelope.shortId(),
                    events = envelope.events.drop(middle),
                ),
                maxFrame,
            )
        }
    }

    /** Test hook: feed a raw frame as if a peer sent it. */
    fun simulateReceive(bytes: ByteArray, peerId: String? = null): MeshEnvelope? {
        val frame = BleMeshCodec.parse(bytes) ?: return null
        if (frame.type == BleMeshCodec.TYPE_HELLO) return null
        val body = reassembler.accept("stub", frame, System.currentTimeMillis()) ?: return null
        val envelope = runCatching {
            MeshEnvelope.parse(String(body, Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (!rememberEnvelope(envelope.envelopeId)) return null
        onReceive?.invoke(envelope, this, peerId)
        return envelope
    }

    // ---- BLE server ------------------------------------------------------

    private fun openServer(manager: BluetoothManager) {
        val ctx = context ?: return
        val server = runCatching { manager.openGattServer(ctx, serverCallback) }.getOrNull()
        if (server == null) {
            logLine("GATT server unavailable")
            return
        }
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val frameCharacteristic = BluetoothGattCharacteristic(
            FRAME_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE or
                BluetoothGattCharacteristic.PERMISSION_READ,
        )
        frameCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(frameCharacteristic)
        gattServer = server
        characteristic = frameCharacteristic
        if (!server.addService(service)) {
            logLine("addService failed")
            runCatching { server.close() }
            gattServer = null
            characteristic = null
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(links) {
                        links.getOrPut(device.address) {
                            Link(
                                address = device.address,
                                device = device,
                                gatt = null,
                                central = false,
                                peerId = null,
                            )
                        }
                    }
                    logLine("central connected ${device.address.takeLast(8)}")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(links) {
                        links.remove(device.address)?.let { link ->
                            synchronized(link.queue) { link.queue.clear() }
                        }
                    }
                    logLine("central gone ${device.address.takeLast(8)}")
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null,
                    )
                }
            }
            if (descriptor.uuid != CCCD_UUID) return
            val enabled = value != null &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
            if (!enabled) return
            val link = synchronized(links) { links[device.address] } ?: return
            link.notifyEnabled = true
            markReady(link)
            logLine("subscribed ${device.address.takeLast(8)}")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null,
                    )
                }
            }
            if (preparedWrite || value == null) return
            val link = synchronized(links) { links[device.address] } ?: return
            handleFrame(link, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val link = synchronized(links) { links[device.address] } ?: return
            synchronized(link.queue) { link.writing = false }
            pump(link)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val link = synchronized(links) { links[device.address] } ?: return
            link.maxFrame = (mtu - 3).coerceIn(BleMeshCodec.MIN_FRAME, BleMeshCodec.MAX_FRAME)
            logLine("central mtu=$mtu frame=${link.maxFrame}")
        }
    }

    // ---- BLE central -----------------------------------------------------

    private fun startScanning(adapter: BluetoothAdapter) {
        val leScanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        if (leScanner == null) {
            logLine("scanner unavailable")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()
        runCatching { leScanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { logLine("scan failed: ${it.message}") }
        scanner = leScanner
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val remoteHash = remoteHash(result) ?: return
            if (remoteHash == localHash) return
            val peer = peerLabel(remoteHash)
            if (synchronized(links) { links.values.any { it.peerId == peer } }) return
            // Only the lower unsigned hash opens the link; the other side accepts.
            if (java.lang.Integer.compareUnsigned(localHash, remoteHash) >= 0) return
            synchronized(connecting) {
                if (remoteHash in connecting) return
                connecting += remoteHash
            }
            synchronized(pendingHash) { pendingHash[result.device.address] = remoteHash }
            val gatt = runCatching {
                result.device.connectGatt(
                    context,
                    false,
                    centralCallback,
                    BluetoothDevice.TRANSPORT_LE,
                )
            }.getOrNull()
            if (gatt == null) {
                synchronized(connecting) { connecting.remove(remoteHash) }
                synchronized(pendingHash) { pendingHash.remove(result.device.address) }
                logLine("connect failed for $peer")
            } else {
                logLine("connecting to $peer")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logLine("scan failed code=$errorCode")
        }
    }

    private val centralCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val hash = synchronized(pendingHash) { pendingHash[address] }
                val link = Link(
                    address = address,
                    device = gatt.device,
                    gatt = gatt,
                    central = true,
                    peerId = hash?.let(::peerLabel),
                )
                synchronized(links) { links[address] = link }
                synchronized(connecting) { hash?.let { connecting.remove(it) } }
                logLine("connected to ${link.peerId ?: address}")
                if (!gatt.discoverServices()) {
                    logLine("discoverServices failed")
                    gatt.disconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(links) {
                    links.remove(address)?.let { link ->
                        synchronized(link.queue) { link.queue.clear() }
                    }
                }
                synchronized(pendingHash) {
                    pendingHash.remove(address)?.let { hash ->
                        synchronized(connecting) { connecting.remove(hash) }
                    }
                }
                runCatching { gatt.close() }
                logLine("disconnected ${address.takeLast(8)}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = synchronized(links) { links[gatt.device.address] } ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logLine("service discovery failed status=$status")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val frameCharacteristic = service?.getCharacteristic(FRAME_UUID)
            if (frameCharacteristic == null) {
                logLine("frame characteristic missing")
                gatt.disconnect()
                return
            }
            link.gattCharacteristic = frameCharacteristic
            val subscribed = runCatching {
                gatt.setCharacteristicNotification(frameCharacteristic, true)
            }.getOrDefault(false)
            if (!subscribed) {
                markReady(link)
                return
            }
            val descriptor = frameCharacteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                markReady(link)
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                logLine("subscribe write failed")
                markReady(link)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            val link = synchronized(links) { links[gatt.device.address] } ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logLine("subscribe failed status=$status")
            }
            markReady(link)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val link = synchronized(links) { links[gatt.device.address] } ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                link.maxFrame = (mtu - 3).coerceIn(BleMeshCodec.MIN_FRAME, BleMeshCodec.MAX_FRAME)
                logLine("mtu=$mtu frame=${link.maxFrame}")
            }
            startLink(link)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val link = synchronized(links) { links[gatt.device.address] } ?: return
            synchronized(link.queue) { link.writing = false }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logLine("write failed status=$status")
            }
            pump(link)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val link = synchronized(links) { links[gatt.device.address] } ?: return
            val value = characteristic.value ?: return
            handleFrame(link, value)
        }
    }

    private fun markReady(link: Link) {
        link.ready = true
        // Subscribe first, then negotiate MTU. GATT allows one outstanding
        // operation; issuing a write while requestMtu is in flight fails.
        if (link.central && link.gatt != null && !link.mtuRequested) {
            link.mtuRequested = true
            val requested = runCatching { link.gatt.requestMtu(REQUESTED_MTU) }
                .getOrDefault(false)
            if (!requested) {
                startLink(link)
            } else {
                // Some stacks never call onMtuChanged; start at the default MTU.
                mainHandler.postDelayed({ startLink(link) }, 1_500)
            }
            return
        }
        startLink(link)
    }

    private fun startLink(link: Link) {
        if (!running || link.started) return
        link.started = true
        sendHello(link)
        pump(link)
    }

    private fun sendHello(link: Link) {
        if (link.helloQueued) return
        link.helloQueued = true
        synchronized(link.queue) {
            link.queue.addFirst(BleMeshCodec.hello(localHash))
        }
        pump(link)
    }

    private fun pump(link: Link) {
        if (!link.ready || !link.started) return
        val frame: ByteArray
        synchronized(link.queue) {
            if (link.writing) return
            frame = link.queue.pollFirst() ?: return
            link.writing = true
        }
        val sent = sendFrame(link, frame)
        if (!sent) {
            synchronized(link.queue) { link.writing = false }
            logLine("frame send failed to ${link.peerId ?: link.address.takeLast(8)}")
        }
    }

    private fun sendFrame(link: Link, frame: ByteArray): Boolean {
        return runCatching {
            if (link.central) {
                val gatt = link.gatt ?: return false
                val gattCharacteristic = link.gattCharacteristic ?: return false
                @Suppress("DEPRECATION")
                gattCharacteristic.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(gattCharacteristic)
            } else {
                val server = gattServer ?: return false
                val serverCharacteristic = characteristic ?: return false
                if (!link.notifyEnabled) return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(
                        link.device,
                        serverCharacteristic,
                        false,
                        frame,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    serverCharacteristic.value = frame
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(link.device, serverCharacteristic, false)
                }
            }
        }.getOrElse {
            logLine("send error: ${it.message}")
            false
        }
    }

    private fun handleFrame(link: Link, bytes: ByteArray) {
        val frame = BleMeshCodec.parse(bytes) ?: return
        val helloHash = BleMeshCodec.helloHash(frame)
        if (helloHash != null) {
            if (helloHash == localHash) {
                logLine("rejecting self link")
                runCatching { link.gatt?.disconnect() }
                return
            }
            link.peerId = peerLabel(helloHash)
            logLine("hello ${link.peerId}")
            return
        }
        val body = reassembler.accept(link.address, frame, System.currentTimeMillis()) ?: return
        val envelope = runCatching {
            MeshEnvelope.parse(String(body, Charsets.UTF_8))
        }.getOrNull()
        if (envelope == null) {
            logLine("dropped unreadable envelope from ${link.peerId ?: link.address.takeLast(8)}")
            return
        }
        if (!rememberEnvelope(envelope.envelopeId)) return
        onReceive?.invoke(envelope, this, link.peerId)
    }

    private fun rememberEnvelope(envelopeId: String): Boolean {
        synchronized(seenEnvelopes) {
            if (envelopeId in seenEnvelopes) return false
            if (seenEnvelopes.size >= SEEN_LIMIT) {
                val oldest = seenEnvelopes.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            seenEnvelopes += envelopeId
        }
        return true
    }

    private fun remoteHash(result: ScanResult): Int? {
        val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return null
        if (data.size < 4) return null
        return ByteBuffer.wrap(data).int
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val leAdvertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
        if (leAdvertiser == null) {
            logLine("advertiser unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(
                ParcelUuid(SERVICE_UUID),
                ByteBuffer.allocate(4).putInt(localHash).array(),
            )
            .build()
        runCatching {
            leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        }.onFailure { logLine("advertise failed: ${it.message}") }
        advertiser = leAdvertiser
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logLine("advertising as ${peerLabel(localHash)}")
        }

        override fun onStartFailure(errorCode: Int) {
            logLine("advertise failed code=$errorCode")
        }
    }

    private fun hasPermissions(): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(ctx, Manifest.permission.BLUETOOTH_SCAN) &&
                granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) &&
                granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun peerLabel(hash: Int): String = "ble-%08x".format(hash)

    private fun logLine(line: String) {
        onLog?.invoke(line)
    }

    companion object {
        private val SERVICE_UUID: UUID =
            UUID.fromString("6b1f3a40-7c2d-4e58-9b1a-0d4f5e6a7b80")
        private val FRAME_UUID: UUID =
            UUID.fromString("6b1f3a41-7c2d-4e58-9b1a-0d4f5e6a7b80")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUESTED_MTU = 517
        private const val SEEN_LIMIT = 256
        private const val MAX_QUEUE = 512
    }
}
