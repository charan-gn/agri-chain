package com.agrichain.relay

import android.Manifest
import android.annotation.SuppressLint
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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * v3 BLE GATT mesh. Every phone runs a GATT server, an advertiser, a scanner
 * and a GATT client at once, so two phones link in whichever direction finds
 * the other first.
 *
 * Wire rules: one characteristic, write-without-response plus notify, CCCD
 * subscription before traffic, MTU raised to 247, one GATT operation in flight
 * per link, six links maximum, scan duty cycle 5 s on / 10 s off once two
 * links exist, and reconnect backoff on status 133/8/19. The service and
 * characteristic UUIDs are random per install.
 *
 * The relay owns TTL, dedupe and store-and-forward. This class only moves whole
 * envelopes: at MTU 247 an envelope (<=199 bytes) is one write; before the MTU
 * is raised [BleFrameCodec] chunks it and [BleReassembler] rebuilds it.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    context: Context?,
    private val identity: V3Identity,
) : Transport {

    override val id: String = "ble"
    override val meshLinks: Boolean = true
    override val maxFrame: Int = V3.MAX_MESSAGE + 1

    private val app = context?.applicationContext
    private val me: String = identity.actorHex
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val serviceUuid: UUID
    private val frameUuid: UUID

    init {
        val (service, frame) = loadUuids()
        serviceUuid = service
        frameUuid = frame
    }

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _links = MutableStateFlow<Set<LinkInfo>>(emptySet())
    override val links: StateFlow<Set<LinkInfo>> = _links.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 128)
    override val incoming: Flow<Incoming> = _incoming.asSharedFlow()

    private val gate = BleScanGate()
    private val backoff = BleBackoff()
    private val reassembler = BleReassembler()

    private val linksByAddress = ConcurrentHashMap<String, Link>()
    private val nextAttemptAt = ConcurrentHashMap<String, Long>()
    private val attempts = ConcurrentHashMap<String, Int>()

    @Volatile private var running = false
    @Volatile private var adapter: BluetoothAdapter? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var serverCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var scanner: BluetoothLeScanner? = null
    @Volatile private var scanning = false
    private var scanJob: Job? = null

    private class Link(
        val address: String,
        val device: BluetoothDevice,
        val gatt: BluetoothGatt?,
        val central: Boolean,
    ) {
        @Volatile var ready = false
        @Volatile var notifyEnabled = false
        @Volatile var peerId: String? = null
        @Volatile var gattCharacteristic: BluetoothGattCharacteristic? = null
        @Volatile var frameSize = BleFrameCodec.MIN_FRAME
        @Volatile var writing = false
        @Volatile var writeToken = 0
        val queue = ArrayDeque<ByteArray>()
    }

    // ------------------------------------------------------------- lifecycle

    override suspend fun start() {
        if (running) return
        if (app == null) {
            _state.value = TransportState.ERROR
            log("no context")
            return
        }
        if (!hasPermissions()) {
            _state.value = TransportState.ERROR
            log("missing Bluetooth permission")
            return
        }
        val manager = app.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = TransportState.ERROR
            log("adapter unavailable or turned off")
            return
        }
        _state.value = TransportState.STARTING
        this.adapter = adapter
        running = true
        val server = openServer(manager)
        if (server == null) {
            _state.value = TransportState.ERROR
            running = false
            return
        }
        startAdvertising(adapter)
        scanJob = scope.launch { scanLoop(adapter) }
        _state.value = TransportState.RUNNING
        log("started as $me")
    }

    override suspend fun stop() {
        if (!running) return
        running = false
        scanJob?.cancel()
        scanJob = null
        stopScanning()
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        runCatching { gattServer?.close() }
        gattServer = null
        serverCharacteristic = null
        for (link in linksByAddress.values) {
            runCatching { link.gatt?.close() }
            synchronized(link.queue) { link.queue.clear() }
        }
        linksByAddress.clear()
        nextAttemptAt.clear()
        attempts.clear()
        reassembler.clear()
        _links.value = emptySet()
        adapter = null
        _state.value = TransportState.STOPPED
        log("stopped")
    }

    override suspend fun send(linkId: String?, envelope: ByteArray): SendResult {
        if (!running) return SendResult.NoLink("ble stopped")
        require(envelope.isNotEmpty()) { "envelope is empty" }
        val targets = if (linkId != null) {
            listOfNotNull(linksByAddress[linkId])
        } else {
            linksByAddress.values.toList()
        }.filter { it.ready }
        if (targets.isEmpty()) return SendResult.NoLink("no ble link")
        var sent = 0
        for (link in targets) {
            val frames = runCatching {
                BleFrameCodec.chunks(envelope, link.frameSize)
            }.getOrNull()
            if (frames == null) {
                log("envelope does not fit ${link.frameSize}-byte frames")
                continue
            }
            synchronized(link.queue) {
                while (link.queue.size + frames.size > MAX_QUEUE) {
                    link.queue.pollFirst() ?: break
                }
                link.queue.addAll(frames)
            }
            sent += 1
            pump(link)
        }
        return if (sent == 0) {
            SendResult.Failed("ble write failed")
        } else {
            SendResult.Sent(sent)
        }
    }

    // ---------------------------------------------------------------- server

    private fun openServer(manager: BluetoothManager): BluetoothGattServer? {
        val ctx = app ?: return null
        val server = runCatching { manager.openGattServer(ctx, serverCallback) }
            .getOrElse {
                log("openGattServer failed: ${it.message}")
                return null
            }
        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val characteristic = BluetoothGattCharacteristic(
            frameUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(characteristic)
        if (!server.addService(service)) {
            log("addService failed")
            runCatching { server.close() }
            return null
        }
        gattServer = server
        serverCharacteristic = characteristic
        return server
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int,
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (linksByAddress.size >= MAX_LINKS) {
                        log("refusing ${label(device.address)}: $MAX_LINKS links")
                        runCatching { gattServer?.cancelConnection(device) }
                        return
                    }
                    val link = Link(device.address, device, null, central = false)
                    linksByAddress[device.address] = link
                    log("central connected ${label(device.address)}")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    drop(linksByAddress.remove(device.address), "central gone")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val link = linksByAddress[device.address] ?: return
            link.frameSize = BleFrameCodec.frameSize(mtu)
            log("peripheral mtu=$mtu frame=${link.frameSize}")
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
                respond(device, requestId, offset)
            }
            if (descriptor.uuid != CCCD_UUID || value == null) return
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            val link = linksByAddress[device.address] ?: return
            link.notifyEnabled = enabled
            if (enabled) markReady(link)
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
                respond(device, requestId, offset)
            }
            if (preparedWrite || value == null) return
            val link = linksByAddress[device.address] ?: return
            handleFrame(link, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val link = linksByAddress[device.address] ?: return
            synchronized(link.queue) { link.writing = false }
            pump(link)
        }

        private fun respond(device: BluetoothDevice, requestId: Int, offset: Int) {
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
    }

    // ---------------------------------------------------------------- client

    private fun scanLoop(adapter: BluetoothAdapter) {
        scope.launch {
            while (isActive && running) {
                val now = System.currentTimeMillis()
                val linkCount = linksByAddress.values.count { it.ready }
                if (scanning && gate.shouldStop(now, linkCount)) {
                    stopScanning()
                } else if (!scanning && gate.allowStart(now, linkCount)) {
                    startScanning(adapter)
                }
                delay(SCAN_TICK_MS)
            }
        }
    }

    private fun startScanning(adapter: BluetoothAdapter) {
        val leScanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        if (leScanner == null) {
            log("scanner unavailable")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()
        val started = runCatching {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            true
        }.getOrElse {
            log("scan failed: ${it.message}")
            false
        }
        if (started) {
            scanner = leScanner
            scanning = true
            gate.onStart(System.currentTimeMillis())
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        scanning = false
        gate.onStop(System.currentTimeMillis())
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val address = result.device.address
            if (linksByAddress.containsKey(address)) return
            if (linksByAddress.size >= MAX_LINKS) return
            val now = System.currentTimeMillis()
            val notBefore = nextAttemptAt[address]
            if (notBefore != null && now < notBefore) return
            if (linksByAddress.values.any { it.address == address }) return
            val gatt = runCatching {
                result.device.connectGatt(app, false, centralCallback, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
            if (gatt == null) {
                log("connect failed for ${label(address)}")
                scheduleRetry(address, GATT_ERROR)
                return
            }
            linksByAddress[address] = Link(address, result.device, gatt, central = true)
            log("connecting to ${label(address)}")
        }

        override fun onScanFailed(errorCode: Int) {
            log("scan failed code=$errorCode")
            scanning = false
        }
    }

    private val centralCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                attempts.remove(address)
                nextAttemptAt.remove(address)
                log("connected ${label(address)}")
                // MTU first: the default 23-byte ATT MTU cannot carry an envelope.
                if (!gatt.requestMtu(BleFrameCodec.REQUESTED_MTU)) {
                    if (!gatt.discoverServices()) {
                        log("discoverServices failed")
                        gatt.disconnect()
                    }
                }
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val link = linksByAddress.remove(address)
                drop(link, "disconnected status=$status")
                runCatching { gatt.close() }
                if (backoff.isRetryable(status)) scheduleRetry(address, status)
                return
            }
            if (backoff.isRetryable(status)) {
                linksByAddress.remove(address)?.let { drop(it, "connect status=$status") }
                runCatching { gatt.close() }
                scheduleRetry(address, status)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val link = linksByAddress[gatt.device.address] ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                link.frameSize = BleFrameCodec.frameSize(mtu)
                log("mtu=$mtu frame=${link.frameSize}")
            }
            if (!gatt.discoverServices()) {
                log("discoverServices failed")
                gatt.disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = linksByAddress[gatt.device.address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("service discovery failed status=$status")
                gatt.disconnect()
                return
            }
            val service = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(frameUuid)
            if (characteristic == null) {
                log("frame characteristic missing")
                gatt.disconnect()
                return
            }
            link.gattCharacteristic = characteristic
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                log("notification enable failed")
                markReady(link)
                return
            }
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                markReady(link)
                return
            }
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                log("CCCD write failed")
                markReady(link)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            val link = linksByAddress[gatt.device.address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("subscribe failed status=$status")
            } else {
                link.notifyEnabled = true
            }
            markReady(link)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val link = linksByAddress[gatt.device.address] ?: return
            synchronized(link.queue) { link.writing = false }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("write failed status=$status")
            }
            pump(link)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val link = linksByAddress[gatt.device.address] ?: return
            handleFrame(link, value)
        }

        @Deprecated("Android 13 passes the value to the overload above")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            val link = linksByAddress[gatt.device.address] ?: return
            handleFrame(link, value)
        }
    }

    // ----------------------------------------------------------------- links

    private fun markReady(link: Link) {
        if (link.ready) return
        link.ready = true
        publish()
        log("link ready ${link.peerId ?: label(link.address)}")
    }

    /**
     * Learn the peer actor from any envelope: `ttl(1) || version(1) || type(1)
     * || flags(1) || ts(4) || fromId(8)`, so fromId starts at byte 8.
     */
    private fun learnPeer(link: Link, envelope: ByteArray) {
        if (envelope.size < 16) return
        val actor = V3Codec.hex(envelope.copyOfRange(8, 16))
        if (actor == me) {
            log("closing self link ${label(link.address)}")
            runCatching { link.gatt?.disconnect() }
            return
        }
        if (link.peerId == actor) return
        link.peerId = actor
        val duplicate = linksByAddress.values.firstOrNull {
            it !== link && it.peerId == actor
        }
        if (duplicate != null) {
            val keep = BleLinkElection.preferCandidate(me, actor, link.central)
            val loser = if (keep) duplicate else link
            log("duplicate link to $actor; dropping ${label(loser.address)}")
            linksByAddress.remove(loser.address)
            runCatching { loser.gatt?.disconnect() }
            runCatching { loser.gatt?.close() }
            runCatching { gattServer?.cancelConnection(loser.device) }
            if (!keep) return
        }
        publish()
    }

    private fun drop(link: Link?, reason: String) {
        if (link == null) return
        synchronized(link.queue) { link.queue.clear() }
        reassembler.expire(System.currentTimeMillis())
        log("$reason ${label(link.address)}")
        publish()
    }

    private fun publish() {
        _links.value = linksByAddress.values
            .filter { it.ready }
            .map { link ->
                LinkInfo(
                    linkId = link.address,
                    transportId = id,
                    peerId = link.peerId,
                )
            }
            .toSet()
    }

    private fun scheduleRetry(address: String, status: Int) {
        if (!backoff.isRetryable(status)) return
        val attempt = (attempts[address] ?: 0) + 1
        attempts[address] = attempt
        val wait = backoff.nextDelay(attempt)
        nextAttemptAt[address] = System.currentTimeMillis() + wait
        log("retry ${label(address)} in ${wait}ms (status=$status)")
    }

    // ----------------------------------------------------------------- frames

    private fun handleFrame(link: Link, bytes: ByteArray) {
        val frame = BleFrameCodec.parse(bytes) ?: return
        val envelope = reassembler.accept(link.address, frame, System.currentTimeMillis())
            ?: return
        learnPeer(link, envelope)
        _incoming.tryEmit(
            Incoming(envelope = envelope, linkId = link.address, transportId = id),
        )
    }

    private fun pump(link: Link) {
        if (!link.ready || !running) return
        val frame: ByteArray
        synchronized(link.queue) {
            if (link.writing) return
            frame = link.queue.pollFirst() ?: return
            link.writing = true
            link.writeToken += 1
            val token = link.writeToken
            // No-response writes do not always report completion; release the
            // slot on a timer so one lost callback cannot stall the link.
            mainHandler.postDelayed({
                val released = synchronized(link.queue) {
                    if (link.writeToken == token && link.writing) {
                        link.writing = false
                        true
                    } else {
                        false
                    }
                }
                if (released) pump(link)
            }, WRITE_TIMEOUT_MS)
        }
        if (!sendFrame(link, frame)) {
            synchronized(link.queue) { link.writing = false }
            log("frame send failed ${label(link.address)}")
        }
    }

    private fun sendFrame(link: Link, frame: ByteArray): Boolean = runCatching {
        if (link.central) {
            val gatt = link.gatt ?: return false
            val characteristic = link.gattCharacteristic ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } else {
            val server = gattServer ?: return false
            val characteristic = serverCharacteristic ?: return false
            if (!link.notifyEnabled) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(link.device, characteristic, false, frame) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(link.device, characteristic, false)
            }
        }
    }.getOrElse {
        log("send error: ${it.message}")
        false
    }

    // ------------------------------------------------------------- advertise

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val leAdvertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
        if (leAdvertiser == null) {
            log("advertiser unavailable")
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
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        runCatching {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
        }.onFailure { log("advertise failed: ${it.message}") }
        advertiser = leAdvertiser
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            log("advertise failed code=$errorCode")
        }
    }

    // ------------------------------------------------------------------ misc

    private fun loadUuids(): Pair<UUID, UUID> {
        val prefs = runCatching {
            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        if (prefs != null) {
            val service = prefs.getString(KEY_SERVICE, null)
            val frame = prefs.getString(KEY_FRAME, null)
            if (service != null && frame != null) {
                val parsedService = runCatching { UUID.fromString(service) }.getOrNull()
                val parsedFrame = runCatching { UUID.fromString(frame) }.getOrNull()
                if (parsedService != null && parsedFrame != null && parsedService != parsedFrame) {
                    return parsedService to parsedFrame
                }
            }
        }
        val (service, frame) = BleUuids.random(SecureRandom())
        prefs?.edit()?.putString(KEY_SERVICE, service.toString())
            ?.putString(KEY_FRAME, frame.toString())
            ?.apply()
        return service to frame
    }

    private fun hasPermissions(): Boolean {
        val ctx = app ?: return false
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

    private fun label(address: String): String = "ble:...${address.takeLast(5)}"

    private fun log(line: String) {
        Log.i(TAG, "ble: $line")
    }

    fun close() {
        runCatching { scope.cancel() }
    }

    companion object {
        private const val TAG = "AgriMesh"
        private const val PREFS = "agrichain-ble"
        private const val KEY_SERVICE = "service-uuid"
        private const val KEY_FRAME = "frame-uuid"
        private const val MAX_LINKS = 6
        private const val MAX_QUEUE = 512
        private const val WRITE_TIMEOUT_MS = 250L
        private const val SCAN_TICK_MS = 500L
        private const val GATT_ERROR = 133
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
