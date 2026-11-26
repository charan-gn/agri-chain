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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.UUID

/**
 * BLE GATT transport for v3 envelopes.
 *
 * Every phone runs a GATT server and scans for the same service. The lower
 * 32-bit actor hash opens the connection, so each pair has one link. Frames
 * are [BleV3Codec] fragments; the relay owns TTL and dedupe.
 */
class BleTransport(
    private val context: Context,
    private val actorId: ByteArray,
    private val enabled: () -> Boolean = { true },
) : Transport {

    override val id: String = "ble"
    override val meshLinks: Boolean = true
    override val maxFrame: Int = BleV3Codec.MAX_FRAME

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _links = MutableStateFlow<Set<LinkInfo>>(emptySet())
    override val links: StateFlow<Set<LinkInfo>> = _links.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64)
    override val incoming: Flow<Incoming> = _incoming.asSharedFlow()

    private val reassembler = BleV3Reassembler()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val localHash: Int = ByteBuffer.wrap(
        java.security.MessageDigest.getInstance("SHA-256").digest(actorId),
    ).int

    private data class Link(
        val address: String,
        val device: BluetoothDevice,
        val gatt: BluetoothGatt?,
        val central: Boolean,
        var peerId: String? = null,
        var maxFrame: Int = BleV3Codec.MIN_FRAME,
        var ready: Boolean = false,
        var notifyEnabled: Boolean = false,
        var writing: Boolean = false,
        var mtuRequested: Boolean = false,
        var gattCharacteristic: BluetoothGattCharacteristic? = null,
        val queue: ArrayDeque<ByteArray> = ArrayDeque(),
    )

    private val linksByAddress = LinkedHashMap<String, Link>()
    private val connecting = HashSet<Int>()
    private val pendingHash = HashMap<String, Int>()

    @Volatile
    private var running = false

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var characteristic: BluetoothGattCharacteristic? = null

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    override suspend fun start() {
        if (running) return
        if (!enabled()) {
            _state.value = TransportState.DEGRADED
            return
        }
        if (!hasPermissions()) {
            _state.value = TransportState.ERROR
            Log.w(TAG, "ble: missing Bluetooth permissions")
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = TransportState.ERROR
            Log.w(TAG, "ble: adapter unavailable")
            return
        }
        openServer(manager)
        startAdvertising(adapter)
        startScanning(adapter)
        running = true
        _state.value = if (gattServer != null) TransportState.RUNNING else TransportState.DEGRADED
        Log.i(TAG, "ble: started")
    }

    override suspend fun stop() {
        running = false
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        synchronized(linksByAddress) {
            linksByAddress.values.forEach { link ->
                runCatching { link.gatt?.disconnect() }
                runCatching { link.gatt?.close() }
                synchronized(link.queue) { link.queue.clear() }
            }
            linksByAddress.clear()
        }
        synchronized(connecting) { connecting.clear() }
        synchronized(pendingHash) { pendingHash.clear() }
        runCatching { gattServer?.close() }
        gattServer = null
        characteristic = null
        scanner = null
        advertiser = null
        _state.value = TransportState.STOPPED
        _links.value = emptySet()
        Log.i(TAG, "ble: stopped")
    }

    override suspend fun send(linkId: String?, envelope: ByteArray): SendResult {
        if (!running) return SendResult.NoLink("ble not running")
        val targets = synchronized(linksByAddress) {
            linksByAddress.values.filter { link ->
                link.ready && (linkId == null || linkId == link.peerId || linkId == link.address)
            }
        }
        if (targets.isEmpty()) return SendResult.NoLink("no BLE peer")
        var queued = 0
        for (link in targets) {
            val frames = runCatching { BleV3Codec.chunks(envelope, link.maxFrame) }.getOrElse {
                return SendResult.Failed(it.message ?: "ble chunking failed", retryable = false)
            }
            synchronized(link.queue) {
                while (link.queue.size + frames.size > MAX_QUEUE) {
                    link.queue.removeFirst()
                }
                link.queue.addAll(frames)
            }
            queued += 1
            pump(link)
        }
        Log.i(TAG, "ble: queued for $queued link(s)")
        return SendResult.Sent(queued)
    }

    /** Test hook: feed a frame as if a peer wrote it. */
    fun simulateReceive(bytes: ByteArray, peerId: String = "ble-test"): ByteArray? {
        val frame = BleV3Codec.parse(bytes) ?: return null
        val envelope = reassembler.accept(peerId, frame, System.currentTimeMillis()) ?: return null
        _incoming.tryEmit(Incoming(envelope, peerId, id))
        return envelope
    }

    // ---- GATT server -----------------------------------------------------

    private fun openServer(manager: BluetoothManager) {
        val server = runCatching { manager.openGattServer(context, serverCallback) }.getOrNull()
        if (server == null) {
            Log.w(TAG, "ble: GATT server unavailable")
            return
        }
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
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
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(frameCharacteristic)
        gattServer = server
        characteristic = frameCharacteristic
        if (!server.addService(service)) {
            Log.w(TAG, "ble: addService failed")
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
                    synchronized(linksByAddress) {
                        linksByAddress.getOrPut(device.address) {
                            Link(device.address, device, null, central = false)
                        }
                    }
                    Log.i(TAG, "ble: central connected ...${device.address.takeLast(8)}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(linksByAddress) {
                        linksByAddress.remove(device.address)?.let { link ->
                            synchronized(link.queue) { link.queue.clear() }
                        }
                    }
                    publishLinks()
                    Log.i(TAG, "ble: central gone ...${device.address.takeLast(8)}")
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
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
            if (descriptor.uuid != CCCD_UUID) return
            val enabled = value != null &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
            if (!enabled) return
            val link = synchronized(linksByAddress) { linksByAddress[device.address] } ?: return
            link.notifyEnabled = true
            markReady(link)
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
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
            if (preparedWrite || value == null) return
            val link = synchronized(linksByAddress) { linksByAddress[device.address] } ?: return
            handleFrame(link, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val link = synchronized(linksByAddress) { linksByAddress[device.address] } ?: return
            synchronized(link.queue) { link.writing = false }
            pump(link)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val link = synchronized(linksByAddress) { linksByAddress[device.address] } ?: return
            link.maxFrame = (mtu - 3).coerceIn(BleV3Codec.MIN_FRAME, BleV3Codec.MAX_FRAME)
            Log.i(TAG, "ble: central mtu=$mtu frame=${link.maxFrame}")
        }
    }

    // ---- BLE central -----------------------------------------------------

    private fun startScanning(adapter: BluetoothAdapter) {
        val leScanner = runCatching { adapter.bluetoothLeScanner }.getOrNull() ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()
        runCatching { leScanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { Log.w(TAG, "ble: scan failed: ${it.message}") }
        scanner = leScanner
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running) return
            val remoteHash = remoteHash(result) ?: return
            if (remoteHash == localHash) return
            val peer = peerLabel(remoteHash)
            if (synchronized(linksByAddress) { linksByAddress.values.any { it.peerId == peer } }) return
            // The lower unsigned hash opens the link; the other side accepts.
            if (java.lang.Integer.compareUnsigned(localHash, remoteHash) >= 0) return
            synchronized(connecting) {
                if (remoteHash in connecting) return
                connecting += remoteHash
            }
            synchronized(pendingHash) { pendingHash[result.device.address] = remoteHash }
            val gatt = runCatching {
                result.device.connectGatt(context, false, centralCallback, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
            if (gatt == null) {
                synchronized(connecting) { connecting.remove(remoteHash) }
                synchronized(pendingHash) { pendingHash.remove(result.device.address) }
                Log.w(TAG, "ble: connect failed for $peer")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "ble: scan failed code=$errorCode")
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
                synchronized(linksByAddress) { linksByAddress[address] = link }
                synchronized(connecting) { hash?.let { connecting.remove(it) } }
                if (!gatt.discoverServices()) {
                    Log.w(TAG, "ble: discoverServices failed")
                    gatt.disconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(linksByAddress) {
                    linksByAddress.remove(address)?.let { link ->
                        synchronized(link.queue) { link.queue.clear() }
                    }
                }
                synchronized(pendingHash) {
                    pendingHash.remove(address)?.let { hash ->
                        synchronized(connecting) { connecting.remove(hash) }
                    }
                }
                runCatching { gatt.close() }
                publishLinks()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = synchronized(linksByAddress) { linksByAddress[gatt.device.address] } ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect()
                return
            }
            val frameCharacteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(FRAME_UUID)
            if (frameCharacteristic == null) {
                gatt.disconnect()
                return
            }
            link.gattCharacteristic = frameCharacteristic
            val subscribed = runCatching {
                gatt.setCharacteristicNotification(frameCharacteristic, true)
            }.getOrDefault(false)
            val descriptor = frameCharacteristic.getDescriptor(CCCD_UUID)
            if (!subscribed || descriptor == null) {
                markReady(link)
                return
            }
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            if (!gatt.writeDescriptor(descriptor)) markReady(link)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            val link = synchronized(linksByAddress) { linksByAddress[gatt.device.address] } ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "ble: subscribe failed status=$status")
            }
            markReady(link)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val link = synchronized(linksByAddress) { linksByAddress[gatt.device.address] } ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                link.maxFrame = (mtu - 3).coerceIn(BleV3Codec.MIN_FRAME, BleV3Codec.MAX_FRAME)
            }
            markReady(link)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val link = synchronized(linksByAddress) { linksByAddress[gatt.device.address] } ?: return
            synchronized(link.queue) { link.writing = false }
            pump(link)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            val link = synchronized(linksByAddress) { linksByAddress[gatt.device.address] } ?: return
            handleFrame(link, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val link = synchronized(linksByAddress) { linksByAddress[gatt.device.address] } ?: return
            handleFrame(link, value)
        }
    }

    // ---- link lifecycle --------------------------------------------------

    private fun markReady(link: Link) {
        link.ready = true
        publishLinks()
        if (link.central && link.gatt != null && !link.mtuRequested) {
            link.mtuRequested = true
            val requested = runCatching { link.gatt.requestMtu(REQUESTED_MTU) }.getOrDefault(false)
            if (!requested) mainHandler.postDelayed({ pump(link) }, 500)
        }
        pump(link)
    }

    private fun publishLinks() {
        val snapshot = synchronized(linksByAddress) {
            linksByAddress.values.filter { it.ready }.map { link ->
                LinkInfo(
                    linkId = link.peerId ?: link.address,
                    transportId = id,
                    peerId = link.peerId,
                    peerName = link.peerId ?: link.address.takeLast(8),
                    slow = false,
                    rssi = null,
                )
            }.toSet()
        }
        _links.value = snapshot
    }

    private fun pump(link: Link) {
        if (!running || !link.ready) return
        val frame: ByteArray
        synchronized(link.queue) {
            if (link.writing) return
            if (link.queue.isEmpty()) return
            frame = link.queue.removeFirst()
            link.writing = true
        }
        if (!sendFrame(link, frame)) {
            synchronized(link.queue) { link.writing = false }
        }
    }

    private fun sendFrame(link: Link, frame: ByteArray): Boolean = runCatching {
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
        Log.w(TAG, "ble: send error: ${it.message}")
        false
    }

    private fun handleFrame(link: Link, bytes: ByteArray) {
        val frame = BleV3Codec.parse(bytes) ?: return
        val envelope = reassembler.accept(
            link.peerId ?: link.address,
            frame,
            System.currentTimeMillis(),
        ) ?: return
        _incoming.tryEmit(Incoming(envelope, link.peerId ?: link.address, id))
    }

    private fun remoteHash(result: ScanResult): Int? {
        val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return null
        if (data.size < 4) return null
        return ByteBuffer.wrap(data).int
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val leAdvertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull() ?: return
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
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(
                ParcelUuid(SERVICE_UUID),
                ByteBuffer.allocate(4).putInt(localHash).array(),
            )
            .build()
        runCatching {
            leAdvertiser.startAdvertising(settings, data, response, advertiseCallback)
        }.onFailure { Log.w(TAG, "ble: advertise failed: ${it.message}") }
        advertiser = leAdvertiser
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "ble: advertising as ${peerLabel(localHash)}")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "ble: advertise failed code=$errorCode")
        }
    }

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN) &&
            granted(Manifest.permission.BLUETOOTH_ADVERTISE) &&
            granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun peerLabel(hash: Int): String = "ble-%08x".format(hash)

    companion object {
        private const val TAG = "AgriMesh"
        private const val REQUESTED_MTU = 517
        private const val MAX_QUEUE = 512
        private val SERVICE_UUID: UUID =
            UUID.fromString("6b1f3a40-7c2d-4e58-9b1a-0d4f5e6a7b80")
        private val FRAME_UUID: UUID =
            UUID.fromString("6b1f3a41-7c2d-4e58-9b1a-0d4f5e6a7b80")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
