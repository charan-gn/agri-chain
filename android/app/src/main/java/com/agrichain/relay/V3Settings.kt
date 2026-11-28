package com.agrichain.relay

import android.content.Context
import com.agrichain.net.LedgerSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3-only settings. The ledger host and admin token stay in [LedgerSettings] so
 * the old screens and the v3 relay always agree on where the server is.
 */
class V3Settings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var ledgerPubkey: String?
        get() = prefs.getString(KEY_LEDGER_PUBKEY, null)?.takeIf { it.length == 66 }
        set(value) = prefs.edit().putString(KEY_LEDGER_PUBKEY, value).apply()

    var role: String
        get() = prefs.getString(KEY_ROLE, "farmer") ?: "farmer"
        set(value) = prefs.edit().putString(KEY_ROLE, value).apply()

    var name: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAME, value.take(80)).apply()

    var nick: String
        get() = prefs.getString(KEY_NICK, "")?.takeIf { it.isNotEmpty() } ?: name
        set(value) = prefs.edit().putString(KEY_NICK, value.take(20)).apply()

    var region: Int
        get() = prefs.getInt(KEY_REGION, 1)
        set(value) = prefs.edit().putInt(KEY_REGION, value).apply()

    var phone: String
        get() = prefs.getString(KEY_PHONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PHONE, value.trim().take(24)).apply()

    var gatewayEnabled: Boolean
        get() = prefs.getBoolean(KEY_GATEWAY, true)
        set(value) = prefs.edit().putBoolean(KEY_GATEWAY, value).apply()

    var meshTtl: Int
        get() = prefs.getInt(KEY_TTL, 7).coerceIn(1, 9)
        set(value) = prefs.edit().putInt(KEY_TTL, value.coerceIn(1, 9)).apply()

    var smsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_ENABLED, value).apply()

    var smsGateway: String
        get() = prefs.getString(KEY_SMS_GATEWAY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SMS_GATEWAY, value.trim().take(24)).apply()

    /** A phone with a SIM in gateway mode accepts AM1 envelopes and commands. */
    var smsGatewayMode: Boolean
        get() = prefs.getBoolean(KEY_SMS_GATEWAY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SMS_GATEWAY_MODE, value).apply()

    /**
     * BLE is always on alongside Wi-Fi. The stored value is kept only for
     * compatibility with older builds and saved settings screens.
     */
    var bleEnabled: Boolean
        get() = true
        set(value) = prefs.edit().putBoolean(KEY_BLE_ENABLED, value).apply()

    var loraEnabled: Boolean
        get() = prefs.getBoolean(KEY_LORA_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LORA_ENABLED, value).apply()

    var registered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_REGISTERED, value).apply()

    var caps: Int
        get() = prefs.getInt(KEY_CAPS, CAP_WIFI or CAP_BLE or CAP_GATEWAY) or CAP_WIFI or CAP_BLE
        set(value) = prefs.edit().putInt(KEY_CAPS, value).apply()

    fun baseUrl(context: Context): String = LedgerSettings.baseUrl(context)

    fun adminToken(context: Context): String = LedgerSettings.adminToken(context)

    /** Refresh the pinned key from `GET /v3/ledger` (trust on first use). */
    fun fetchAndPinLedgerKey(context: Context): String {
        val info = V3Registration.ledgerInfo(baseUrl(context))
        val pubkey = info.optString("pubkey", "")
        require(pubkey.length == 66) { "ledger did not return a compressed pubkey" }
        ledgerPubkey = pubkey
        return pubkey
    }

    companion object {
        const val CAP_BLE = 0x01
        const val CAP_WIFI = 0x02
        const val CAP_LORA = 0x04
        const val CAP_SMS = 0x08
        const val CAP_GATEWAY = 0x10

        private const val PREFS = "agrichain_v3"
        private const val KEY_LEDGER_PUBKEY = "ledgerPubkey"
        private const val KEY_ROLE = "role"
        private const val KEY_NAME = "name"
        private const val KEY_NICK = "nick"
        private const val KEY_REGION = "region"
        private const val KEY_PHONE = "phone"
        private const val KEY_GATEWAY = "gatewayEnabled"
        private const val KEY_TTL = "meshTtl"
        private const val KEY_SMS_ENABLED = "smsEnabled"
        private const val KEY_SMS_GATEWAY = "smsGateway"
        private const val KEY_SMS_GATEWAY_MODE = "smsGatewayMode"
        private const val KEY_BLE_ENABLED = "bleEnabled"
        private const val KEY_LORA_ENABLED = "loraEnabled"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_CAPS = "caps"
    }
}

/** Local order book. The ledger is authoritative; this is the offline UI cache. */
data class LocalOrder(
    val orderId: String,
    val listingId: String,
    val sellerId: String,
    val buyerId: String,
    val crop: Int,
    val qtyKg: Long,
    val bidPaise: Long,
    val region: Int,
    val createdAt: Long,
    val status: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("orderId", orderId)
        .put("listingId", listingId)
        .put("sellerId", sellerId)
        .put("buyerId", buyerId)
        .put("crop", crop)
        .put("qtyKg", qtyKg)
        .put("bidPaise", bidPaise)
        .put("region", region)
        .put("createdAt", createdAt)
        .put("status", status)

    companion object {
        fun fromJson(json: JSONObject): LocalOrder = LocalOrder(
            orderId = json.getString("orderId"),
            listingId = json.optString("listingId", ""),
            sellerId = json.optString("sellerId", ""),
            buyerId = json.optString("buyerId", ""),
            crop = json.optInt("crop", 0),
            qtyKg = json.optLong("qtyKg", 0),
            bidPaise = json.optLong("bidPaise", 0),
            region = json.optInt("region", 0),
            createdAt = json.optLong("createdAt", 0),
            status = json.optString("status", "queued"),
        )
    }
}

class OrderStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<LocalOrder> {
        val raw = prefs.getString(KEY_ORDERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { LocalOrder.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(order: LocalOrder) {
        val orders = all().toMutableList()
        val index = orders.indexOfFirst { it.orderId == order.orderId }
        if (index >= 0) orders[index] = order else orders.add(0, order)
        persist(orders.take(200))
    }

    fun updateStatus(orderId: String, status: String) {
        val orders = all().toMutableList()
        val index = orders.indexOfFirst { it.orderId == orderId }
        if (index >= 0) {
            orders[index] = orders[index].copy(status = status)
            persist(orders)
        }
    }

    fun find(orderId: String): LocalOrder? = all().firstOrNull { it.orderId == orderId }

    private fun persist(orders: List<LocalOrder>) {
        val array = JSONArray()
        orders.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ORDERS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "agrichain_v3_orders"
        private const val KEY_ORDERS = "orders"
    }
}
