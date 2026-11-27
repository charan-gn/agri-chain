package com.agrichain.relay

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide v3 relay. RelayService owns the lifetime; activities only read
 * the snapshot and originate messages. All network and radio work happens on
 * [scope], never on the UI thread.
 */
object RelayRuntime {

    private const val TAG = "AgriMesh"
    private const val PREFS = "agrichain_v3_runtime"
    private const val KEY_STATE = "state"

    private val _status = MutableStateFlow("stopped")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastNotice = MutableStateFlow<String?>(null)
    val lastNotice: StateFlow<String?> = _lastNotice.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    @Volatile
    var core: RelayCore? = null
        private set

    @Volatile
    var identity: V3Identity? = null
        private set

    @Volatile
    var wifi: WifiTransport? = null
        private set

    @Volatile
    var ble: BleTransport? = null
        private set

    @Volatile
    var sms: SmsTransport? = null
        private set

    @Volatile
    var smsGateway: SmsGateway? = null
        private set

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private var gatewayClient: V3LedgerClient? = null

    @Synchronized
    fun start(context: Context): String? {
        if (core != null) return null
        appContext = context.applicationContext
        val settings = V3Settings(appContext)
        _status.value = "starting"

        val baseUrl = settings.baseUrl(appContext)
        val pin = settings.ledgerPubkey ?: runCatching {
            settings.fetchAndPinLedgerKey(appContext)
        }.getOrElse { error ->
            val message = "ledger key unavailable: ${error.message}"
            _status.value = message
            Log.w(TAG, "relay: $message")
            return message
        }

        val signer = runCatching { V3Identity.loadOrCreate(appContext) }.getOrElse { error ->
            val message = "identity failed: ${error.message}"
            _status.value = message
            Log.e(TAG, "relay: $message")
            return message
        }
        identity = signer

        val caps = settings.caps or
            (if (settings.gatewayEnabled) V3Settings.CAP_GATEWAY else 0)
        val config = RelayConfig(
            nick = settings.nick.ifBlank { "phone" },
            caps = caps,
            meshTtl = settings.meshTtl,
            smsEnabled = settings.smsEnabled,
            smsGatewayNumber = settings.smsGateway.takeIf { it.isNotBlank() },
        )
        val relay = runCatching {
            RelayCore(
                signer = signer,
                config = config,
                ledgerPubkey = V3Codec.hex(pin),
                scope = scope,
            )
        }.getOrElse { error ->
            val message = "relay init failed: ${error.message}"
            _status.value = message
            Log.e(TAG, "relay: $message")
            return message
        }

        runCatching {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATE, null)
                ?.let { relay.importJson(it) }
        }.onFailure { Log.w(TAG, "relay: state import failed: ${it.message}") }

        val wifiTransport = WifiTransport(appContext, signer)
        relay.attach(wifiTransport)
        wifi = wifiTransport

        // BLE is a flooded mesh link like Wi-Fi; the transport itself refuses to
        // start when the runtime Bluetooth permissions have not been granted.
        val bleTransport = BleTransport(appContext, signer)
        relay.attach(bleTransport)
        ble = bleTransport

        val client = HttpV3LedgerClient(baseUrl)
        gatewayClient = client
        if (settings.smsEnabled) {
            attachSms(relay, client, settings)
        }
        core = relay

        jobs += scope.launch {
            runCatching { relay.start() }
                .onFailure { Log.e(TAG, "relay: transport start failed: ${it.message}") }
        }
        jobs += scope.launch { tickLoop(relay) }
        jobs += scope.launch { gatewayLoop(relay, client) }
        jobs += scope.launch { eventLoop(relay) }

        _status.value = "running"
        Log.i(TAG, "relay: running as ${signer.actorHex} via $baseUrl")
        return null
    }

    /**
     * SMS is the gateway/receipt leg, not a flooded mesh link. The transport
     * chunks whole envelopes; the gateway maps msgId -> originating number so a
     * ledger RECEIPT can travel back over GSM.
     */
    private fun attachSms(relay: RelayCore, client: V3LedgerClient, settings: V3Settings) {
        val transport = SmsTransport(
            context = appContext,
            gatewayNumber = settings.smsGateway.takeIf { it.isNotBlank() },
        )
        relay.attach(transport)
        sms = transport
        relay.smsFallback = { number, envelope ->
            transport.send(number, envelope) is SendResult.Sent
        }
        val gateway = SmsGateway(
            priceCaches = { relay.priceCaches() },
            listingCaches = { relay.listingCaches() },
            priceFeed = { client.prices(0) },
            listingFeed = { client.listings(0) },
            sendEnvelope = { number, envelope -> transport.enqueueEnvelope(number, envelope) },
            sendText = { number, text -> transport.enqueueText(number, text) },
        )
        relay.ledgerMessageHook = { raw -> gateway.onLedgerMessage(raw) }
        smsGateway = gateway
        for ((number, envelope) in SmsInboxV3.drain()) {
            gateway.remember(envelope, number)
            scope.launch {
                runCatching { relay.onEnvelope(envelope, arrivalTransportId = "sms") }
                    .onFailure { Log.w(TAG, "sms: buffered envelope failed: ${it.message}") }
            }
        }
    }

    /** Called by [SmsReceiverV3] for a reassembled `AM1` envelope. */
    fun onSmsEnvelope(number: String, envelope: ByteArray) {
        val relay = core
        if (relay == null) {
            SmsInboxV3.add(number, envelope)
            Log.i(TAG, "sms: relay stopped, buffered ${envelope.size} bytes")
            return
        }
        scope.launch {
            smsGateway?.remember(envelope, number)
            val outcome = runCatching {
                relay.onEnvelope(envelope, arrivalTransportId = "sms")
            }.getOrElse { error ->
                Log.w(TAG, "sms: envelope failed: ${error.message}")
                return@launch
            }
            Log.i(
                TAG,
                "sms: ${outcome.type} from ...${number.takeLast(4)} " +
                    "fresh=${outcome.fresh}${outcome.droppedReason?.let { " ($it)" } ?: ""}",
            )
        }
    }

    /** Called by [SmsReceiverV3] for a plain-text PRICE/LISTINGS command. */
    fun onSmsCommand(number: String, text: String) {
        val gateway = smsGateway
        if (gateway == null) {
            Log.i(TAG, "sms: command ignored (SMS disabled)")
            return
        }
        scope.launch {
            val reply = runCatching { gateway.answer(number, text) }.getOrElse { error ->
                Log.w(TAG, "sms: command failed: ${error.message}")
                return@launch
            }
            if (reply != null) {
                Log.i(TAG, "sms: reply to ...${number.takeLast(4)} (${reply.length} chars)")
            }
        }
    }

    @Synchronized
    fun stop() {
        val relay = core ?: return
        persist(relay)
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.launch { runCatching { relay.stop() } }
        core = null
        wifi = null
        ble?.close()
        ble = null
        sms = null
        smsGateway = null
        gatewayClient = null
        _status.value = "stopped"
        Log.i(TAG, "relay: stopped")
    }

    fun snapshot(): RelaySnapshot {
        val relay = core
        if (relay != null) return relay.snapshot()
        return RelaySnapshot(
            me = identity?.actorHex ?: "",
            peers = emptyList(),
            pendingCount = 0,
            pendingOwnCount = 0,
            prices = emptyList(),
            listings = emptyList(),
            lastReceipt = null,
            gatewayReachable = false,
            gatewayLastSeen = 0,
            transports = emptyList(),
        )
    }

    suspend fun originate(
        type: V3Type,
        body: V3Body,
        toId: ByteArray? = null,
    ): Originated? = core?.originate(type, body, toId, V3Settings(appContext).meshTtl)

    fun settings(context: Context): V3Settings = V3Settings(context)

    fun orderStore(context: Context): OrderStore = OrderStore(context)

    fun persist() {
        core?.let { persist(it) }
    }

    fun startService(context: Context) {
        val intent = Intent(context, RelayService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, RelayService::class.java))
    }

    private fun persist(relay: RelayCore) {
        runCatching {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE, relay.exportJson())
                .apply()
        }.onFailure { Log.w(TAG, "relay: state save failed: ${it.message}") }
    }

    private suspend fun tickLoop(relay: RelayCore) {
        var ticks = 0
        while (true) {
            runCatching { relay.tick() }
                .onFailure { Log.w(TAG, "relay: tick failed: ${it.message}") }
            ticks += 1
            if (ticks % 6 == 0) persist(relay)
            delay(5_000)
        }
    }

    private suspend fun gatewayLoop(relay: RelayCore, client: V3LedgerClient) {
        runCatching { GatewayLoop(relay, client).run() }
            .onFailure { Log.w(TAG, "relay: gateway loop stopped: ${it.message}") }
    }

    private suspend fun eventLoop(relay: RelayCore) {
        val orders = OrderStore(appContext)
        relay.uiEvents.collect { event ->
            when (event) {
                is RelayEvent.Receipt -> {
                    if (event.receipt.rejected) {
                        orders.updateStatus(event.receipt.msgId, "rejected")
                    }
                }
                is RelayEvent.Notice -> {
                    val status = when (event.kind) {
                        2 -> "accepted"
                        3 -> "cancelled"
                        4 -> "delivered"
                        else -> null
                    }
                    if (status != null) {
                        orders.updateStatus(event.refId, status)
                    }
                    _lastNotice.value = noticeText(event)
                }
                is RelayEvent.DirectedMessage -> {
                    if (event.type == V3Type.ORDER) {
                        rememberIncomingOrder(event, relay, orders)
                    }
                }
                is RelayEvent.Peer -> Unit
            }
        }
    }

    private fun rememberIncomingOrder(
        event: RelayEvent.DirectedMessage,
        relay: RelayCore,
        orders: OrderStore,
    ) {
        val body = runCatching { V3Bodies.parse(V3Type.ORDER, V3Codec.decode(event.raw).body) }
            .getOrNull() as? V3Body.Order ?: return
        val listingId = V3Codec.hex(body.listingId)
        val listing = relay.listingCaches().firstOrNull { it.listingId == listingId }
        orders.save(
            LocalOrder(
                orderId = event.msgId,
                listingId = listingId,
                sellerId = relay.me,
                buyerId = event.fromId,
                crop = listing?.crop ?: 0,
                qtyKg = body.qtyKg,
                bidPaise = body.bidPaise,
                region = listing?.region ?: 0,
                createdAt = System.currentTimeMillis(),
                status = "received",
            ),
        )
    }

    private fun noticeText(notice: RelayEvent.Notice): String = when (notice.kind) {
        1 -> "Order received by seller"
        2 -> "Order accepted: ${notice.qtyKg} kg at ${notice.pricePaise} paise/kg"
        3 -> "Order cancelled"
        4 -> "Delivery recorded"
        5 -> "Tamper alert on batch ${notice.refId.take(8)}"
        else -> "Ledger notice ${notice.kind}"
    }
}
