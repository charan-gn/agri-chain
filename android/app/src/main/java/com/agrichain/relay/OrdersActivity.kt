package com.agrichain.relay

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrichain.databinding.ActivityOrdersV3Binding
import kotlinx.coroutines.launch

/** ORDER / ORDER_ACCEPT / ORDER_CANCEL / DELIVERED with an offline order book. */
class OrdersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOrdersV3Binding
    private var listings: List<ListingCache> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOrdersV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.qtyInput.setText("100")
        binding.bidInput.setText("2500")
        binding.validHoursInput.setText("24")
        binding.createButton.setOnClickListener { createOrder() }
        binding.acceptButton.setOnClickListener {
            sendOrderAction(V3Type.ORDER_ACCEPT, "accepted locally")
        }
        binding.cancelButton.setOnClickListener {
            sendOrderAction(V3Type.ORDER_CANCEL, "cancelled locally")
        }
        binding.deliveredButton.setOnClickListener {
            sendOrderAction(V3Type.DELIVERED, "delivered locally")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshListings()
        renderOrders()
    }

    private fun refreshListings() {
        val selected = binding.listingSpinner.selectedItemPosition
        listings = RelayRuntime.snapshot().listings
        val labels = listings.map {
            "${V3Dictionaries.cropName(it.crop)} ${it.remainingKg}kg " +
                "@${V3Dictionaries.money(it.askPaise)} · ${it.listingId.take(8)}"
        }.ifEmpty { listOf("No cached listings") }
        binding.listingSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        if (selected in labels.indices) binding.listingSpinner.setSelection(selected)
    }

    private fun createOrder() {
        val listing = listings.getOrNull(binding.listingSpinner.selectedItemPosition)
        if (listing == null) {
            binding.statusText.text = "No listing selected. Open Market first."
            return
        }
        val qty = binding.qtyInput.text.toString().toLongOrNull()
        val bid = binding.bidInput.text.toString().toLongOrNull()
        val hours = binding.validHoursInput.text.toString().toLongOrNull() ?: 24
        if (qty == null || qty <= 0) {
            binding.statusText.text = "Enter a quantity above zero"
            return
        }
        if (bid == null || bid <= 0) {
            binding.statusText.text = "Enter a bid in paise per kg"
            return
        }
        val validUntil = System.currentTimeMillis() / 1000 + hours.coerceIn(1, 720) * 3600
        val body = V3Body.Order(
            listingId = V3Codec.hex(listing.listingId),
            qtyKg = qty,
            bidPaise = bid,
            validUntil = validUntil,
        )
        binding.statusText.text = "Signing order..."
        lifecycleScope.launch {
            val originated = runCatching {
                RelayRuntime.originate(V3Type.ORDER, body, V3Codec.hex(listing.sellerId))
            }.getOrElse { error ->
                binding.statusText.text = "Order failed: ${error.message}"
                return@launch
            }
            if (originated == null) {
                binding.statusText.text = "Relay is not running. Open Relay and start it."
                return@launch
            }
            val me = RelayRuntime.core?.me.orEmpty()
            RelayRuntime.orderStore(this@OrdersActivity).save(
                LocalOrder(
                    orderId = originated.msgId,
                    listingId = listing.listingId,
                    sellerId = listing.sellerId,
                    buyerId = me,
                    crop = listing.crop,
                    qtyKg = qty,
                    bidPaise = bid,
                    region = listing.region,
                    createdAt = System.currentTimeMillis(),
                    status = "queued",
                ),
            )
            binding.orderIdInput.setText(originated.msgId)
            binding.statusText.text =
                "Queued ${originated.msgId.take(12)} · sent to ${originated.links} link(s)"
            renderOrders()
        }
    }

    private fun sendOrderAction(type: V3Type, label: String) {
        val orderHex = binding.orderIdInput.text.toString().trim()
        if (orderHex.length != 24) {
            binding.statusText.text = "Enter a 24-character order id"
            return
        }
        val orderId = runCatching { V3Codec.hex(orderHex) }.getOrElse {
            binding.statusText.text = "Order id must be hex"
            return
        }
        val store = RelayRuntime.orderStore(this)
        val order = store.find(orderHex)
        val me = RelayRuntime.core?.me.orEmpty()
        val toId = when (type) {
            V3Type.ORDER_ACCEPT -> order?.buyerId?.takeIf { it.isNotEmpty() }
            V3Type.ORDER_CANCEL -> if (order?.buyerId == me) order.sellerId else order?.buyerId
            V3Type.DELIVERED -> order?.sellerId
            else -> null
        }
        val body = when (type) {
            V3Type.ORDER_ACCEPT -> V3Body.OrderAccept(orderId)
            V3Type.ORDER_CANCEL -> V3Body.OrderCancel(orderId)
            V3Type.DELIVERED -> V3Body.Delivered(orderId)
            else -> return
        }
        binding.statusText.text = "Signing ${type.name}..."
        lifecycleScope.launch {
            val originated = runCatching {
                RelayRuntime.originate(
                    type,
                    body,
                    toId?.takeIf { it.length == 16 }?.let { V3Codec.hex(it) },
                )
            }.getOrElse { error ->
                binding.statusText.text = "${type.name} failed: ${error.message}"
                return@launch
            }
            if (originated == null) {
                binding.statusText.text = "Relay is not running. Open Relay and start it."
                return@launch
            }
            store.updateStatus(orderHex, label)
            binding.statusText.text =
                "${type.name} queued ${originated.msgId.take(12)} · ${originated.links} link(s)"
            renderOrders()
        }
    }

    private fun renderOrders() {
        val orders = RelayRuntime.orderStore(this).all()
        if (orders.isEmpty()) {
            binding.orderList.text = "No orders yet."
            return
        }
        binding.orderList.text = orders.joinToString("\n\n") { order ->
            buildString {
                append(order.orderId.take(12)).append(" · ").append(order.status).append('\n')
                append(V3Dictionaries.cropName(order.crop))
                append(" · ").append(order.qtyKg).append(" kg")
                append(" @").append(V3Dictionaries.money(order.bidPaise)).append("/kg")
                append('\n')
                append("listing ").append(order.listingId.take(8))
                append(" · seller ").append(order.sellerId.take(8))
                append(" · buyer ").append(order.buyerId.take(8))
            }
        }
    }
}
