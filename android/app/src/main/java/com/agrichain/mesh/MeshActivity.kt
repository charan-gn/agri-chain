package com.agrichain.mesh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.R
import com.agrichain.core.Permissions
import com.agrichain.data.ChatMessage
import com.agrichain.databinding.ActivityMeshBinding
import com.agrichain.ui.UiHelpers
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple relay chat: one name field, one shared conversation. Wi-Fi and BLE
 * start automatically; the server acts as a halo hub so messages also reach
 * phones that are not on the same local radio.
 */
class MeshActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeshBinding
    private lateinit var runtime: MeshRuntime
    private var running = false
    private var contactsKey = ""
    private var serverChat: List<ChatMessage> = emptyList()
    private var serverOnline = false
    private var fetchingServer = false

    private val handler = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val linkTick = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) updateRelayLink()
            handler.postDelayed(this, 2_000)
        }
    }

    private val serverTick = object : Runnable {
        override fun run() {
            fetchServer()
            handler.postDelayed(this, 4_000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            log("Denied: ${denied.joinToString()}")
            Log.w(TAG, "relay: permissions denied ${denied.joinToString()}")
            return@registerForActivityResult
        }
        startRelay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeshBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.status.setOnClickListener { ensurePermissions() }
        binding.chatLinkStatus.setOnClickListener { ensurePermissions() }
        UiHelpers.wireToggle(binding.advancedHeader, binding.advancedBody, binding.advancedChevron)

        runtime = MeshRuntime.get(this)
        runtime.logSink = { line -> ui { log(line) } }

        binding.senderId.text = "Phone ID: ${runtime.selfId}"
        binding.gatewayNumberInput.setText(SmsSettings.gatewayNumber(this).orEmpty())
        binding.startButton.setOnClickListener { ensurePermissions() }
        binding.stopButton.setOnClickListener { stopRelay() }
        binding.modeAutoButton.setOnClickListener { setMode(RelayLinkMode.AUTO) }
        binding.modeWifiButton.setOnClickListener { setMode(RelayLinkMode.WIFI) }
        binding.modeBleButton.setOnClickListener { setMode(RelayLinkMode.BLE) }
        binding.saveGatewayButton.setOnClickListener { saveGateway() }
        binding.broadcastButton.setOnClickListener { broadcastPending() }
        binding.syncButton.setOnClickListener { syncNow() }
        binding.copyButton.setOnClickListener { copyPending() }
        binding.relayButton.setOnClickListener { relayPasted() }
        binding.chatNameInput.setText(runtime.userName)
        binding.saveChatNameButton.setOnClickListener { saveName() }
        binding.chatNameInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                saveName()
                true
            } else {
                false
            }
        }
        binding.chatSendButton.setOnClickListener { sendChat() }
        binding.chatInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else {
                false
            }
        }
        runtime.chatListener = { message -> ui { appendChat(message) } }
        refreshContacts(force = true)

        running = runtime.relayStatus().running
        if (!running && Permissions.missingMesh(this).isEmpty()) {
            startRelay()
        } else if (running) {
            runtime.announceUser(force = true)
        }
        updateRelayLink()
        refreshPending()
        binding.root.post { fetchServer() }
    }

    override fun onResume() {
        super.onResume()
        running = runtime.relayStatus().running
        updateRelayLink()
        refreshPending()
        refreshContacts()
        renderChat()
        handler.removeCallbacks(linkTick)
        handler.removeCallbacks(serverTick)
        handler.post(linkTick)
        handler.post(serverTick)
    }

    override fun onPause() {
        handler.removeCallbacks(linkTick)
        handler.removeCallbacks(serverTick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(linkTick)
        handler.removeCallbacks(serverTick)
        runtime.logSink = null
        runtime.chatListener = null
        super.onDestroy()
    }

    private fun ensurePermissions() {
        val missing = Permissions.missingMesh(this).map { it.permission }
        if (missing.isEmpty()) {
            startRelay()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startRelay() {
        if (running) return
        runtime.startRelay()
        running = true
        runtime.announceUser(force = true)
        binding.chatNameInput.setText(runtime.userName)
        Log.i(TAG, "relay: started ${runtime.relayStatus().mode.name.lowercase()} discovery")
        updateRelayLink()
        Thread {
            val result = runtime.pump()
            ui {
                log("startup sync: ${result.uploaded} uploaded, ${result.pending} pending")
                refreshPending()
            }
        }.start()
    }

    private fun stopRelay() {
        if (!running) return
        runtime.stopRelay()
        running = false
        Log.i(TAG, "relay: stopped")
        updateRelayLink()
    }

    private fun setMode(mode: RelayLinkMode) {
        runtime.setRelayMode(mode)
        updateRelayLink()
    }

    private fun saveName() {
        val name = binding.chatNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            log("Enter a name first")
            return
        }
        runtime.setUserName(name)
        binding.chatNameInput.setText(runtime.userName)
        refreshContacts(force = true)
        updateRelayLink()
        log("Your name is now ${runtime.userName}")
    }

    private fun saveGateway() {
        try {
            val saved = SmsSettings.setGatewayNumber(
                this,
                binding.gatewayNumberInput.text.toString(),
            )
            binding.gatewayNumberInput.setText(saved.orEmpty())
            log(if (saved == null) "Helper number cleared" else "Helper: $saved")
            refreshPending()
        } catch (error: IllegalArgumentException) {
            log(error.message ?: "Invalid phone number")
        }
    }

    private fun broadcastPending() {
        val sent = runtime.broadcastPending()
        log("Sent waiting items to $sent link(s)")
        refreshPending()
    }

    private fun syncNow() {
        binding.syncButton.isEnabled = false
        Thread {
            val result = runtime.pump()
            ui {
                log(
                    "Sync: ${result.uploaded} uploaded, ${result.pending} pending" +
                        (result.error?.let { " · $it" } ?: "")
                )
                binding.syncButton.isEnabled = true
                refreshPending()
            }
        }.start()
    }

    private fun copyPending() {
        val pending = runtime.pending()
        if (pending.isEmpty()) {
            log("No waiting items to copy")
            return
        }
        val text = MeshEnvelope.events(runtime.selfId, pending).encode()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AgriChain envelope", text))
        log("Copied ${pending.size} waiting item(s)")
    }

    private fun relayPasted() {
        val raw = binding.envelopeInput.text.toString().trim()
        val (status, result) = runtime.acceptManualInput(raw)
        when {
            status == "invalid" -> log("Not a valid envelope, AC3| SMS or MC1| LoRa frame")
            result == null -> {
                log("Buffered chunk ($status) — paste the remaining parts")
                binding.envelopeInput.text?.clear()
            }
            else -> {
                log("Received ($status): ${result.freshEvents} new, ${result.uploaded} uploaded")
                binding.envelopeInput.text?.clear()
                refreshPending()
            }
        }
    }

    private fun sendChat() {
        val text = binding.chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val message = runtime.sendChat(text)
        if (message == null) {
            log("Chat needs the mesh signing key")
        } else {
            binding.chatInput.text?.clear()
            appendChat(message)
            updateRelayLink()
            handler.postDelayed({ fetchServer() }, 900)
        }
    }

    /** Refreshes the visible roster from phones seen on either radio and names. */
    private fun refreshContacts(force: Boolean = false) {
        val peers = runtime.discoveredPeers()
        for (peer in peers) {
            if (runtime.contacts.nameOf(peer) == null) {
                runtime.contacts.put(peer, "Phone ${peer.takeLast(6)}")
            }
        }
        val contacts = runtime.contacts.all()
        val key = contacts.joinToString("|") { "${it.userId}:${it.name}" }
        if (!force && key == contactsKey) return
        contactsKey = key
        runtime.contacts.markSeen(peers)

        binding.contactsText.text = if (contacts.isEmpty()) {
            "Everyone: no one seen yet — save your name to announce it"
        } else {
            "Everyone: " + contacts.sortedBy { it.name }.joinToString(", ") { contact ->
                if (contact.userId == runtime.selfId) "${contact.name} (you)" else contact.name
            }
        }
    }

    /** Merges local mesh chat with the server halo feed; envelopeId wins once. */
    private fun renderChat() {
        val merged = LinkedHashMap<String, ChatMessage>()
        runtime.chatMessages().forEach { merged[it.envelopeId] = it }
        serverChat.forEach { merged.putIfAbsent(it.envelopeId, it) }
        val ordered = merged.values.sortedBy { it.ts }
        binding.chatList.removeAllViews()
        if (ordered.isEmpty()) {
            binding.chatList.addView(emptyState())
        } else {
            ordered.forEach { addBubble(it) }
        }
        scrollToBottom()
    }

    private fun appendChat(message: ChatMessage) {
        binding.chatList.addView(bubbleRow(message))
        scrollToBottom()
    }

    private fun addBubble(message: ChatMessage) {
        binding.chatList.addView(bubbleRow(message))
    }

    private fun bubbleRow(message: ChatMessage): View {
        val outgoing = message.outgoing
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (outgoing) Gravity.END else Gravity.START
        }
        val label = if (outgoing) {
            "You"
        } else {
            runtime.contacts.nameOf(message.senderId)?.takeIf { it.isNotBlank() }
                ?: "Phone ${message.senderId.takeLast(6).ifBlank { "unknown" }}"
        }
        val halo = if (message.status == "server" && !outgoing) " · halo" else ""
        val meta = TextView(this).apply {
            text = "$label$halo · ${clock.format(Date(message.ts))}"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MeshActivity, R.color.text_secondary))
            setPadding(dp(4), 0, dp(4), dp(3))
        }
        val bubble = TextView(this).apply {
            text = message.body
            textSize = 16f
            setTextColor(
                if (outgoing) Color.WHITE
                else ContextCompat.getColor(this@MeshActivity, R.color.text_primary)
            )
            setBackgroundResource(if (outgoing) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setTextIsSelectable(true)
        }
        column.addView(meta)
        column.addView(bubble)
        if (outgoing) {
            column.addView(
                TextView(this).apply {
                    text = if (message.status == "waiting") "waiting for the halo or a nearby phone" else "sent"
                    textSize = 11f
                    setTextColor(
                        ContextCompat.getColor(
                            this@MeshActivity,
                            if (message.status == "waiting") R.color.status_warn else R.color.status_ok,
                        )
                    )
                    setPadding(dp(4), dp(3), dp(4), 0)
                },
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (outgoing) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            addView(column)
        }
    }

    private fun emptyState(): View = TextView(this).apply {
        text = "No messages yet.\nSay hello to the mesh."
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(this@MeshActivity, R.color.text_secondary))
        setPadding(0, dp(48), 0, dp(24))
    }

    private fun scrollToBottom() {
        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun fetchServer() {
        if (fetchingServer) return
        fetchingServer = true
        Thread {
            val messages = runtime.serverChat()
            val online = runtime.ledgerReachable()
            ui {
                fetchingServer = false
                serverChat = messages
                serverOnline = online
                renderChat()
                updateRelayLink()
            }
        }.start()
    }

    private fun updateRelayLink() {
        val status = runtime.relayStatus()
        val active = when (status.active) {
            RelayLinkId.WIFI -> "Wi-Fi"
            RelayLinkId.BLE -> "Bluetooth"
            null -> "none"
        }
        binding.linkStatus.text =
            "Wi-Fi: ${status.wifiPeers} · Bluetooth: ${status.blePeers} · Active: $active"
        binding.linkHint.text = when (status.mode) {
            RelayLinkMode.AUTO ->
                "Auto uses whichever radio finds a phone first and keeps watching both."
            RelayLinkMode.WIFI -> "Wi-Fi only. Phones must share the same local network."
            RelayLinkMode.BLE -> "Bluetooth only. No local network needed."
        }
        val peers = status.wifiPeers + status.blePeers
        val radio = when {
            !status.running -> "Radio off · tap to start"
            active == "none" -> "Scanning Wi-Fi + Bluetooth…"
            else -> "$peers nearby · $active"
        }
        binding.chatLinkStatus.text =
            "$radio · halo ${if (serverOnline) "online" else "offline"}"
        if (status.running && status.active != null) {
            runtime.announceUser()
            refreshContacts()
        }
        binding.status.text = if (status.running) "Relay ON · $active" else "Relay off · tap to start"
        binding.status.setTextColor(
            getColor(if (status.running) R.color.status_ok else R.color.status_neutral)
        )
        if (status.running && status.active != null && runtime.flushChatOutbox() > 0) {
            renderChat()
        }
        binding.startButton.isEnabled = !status.running
        binding.stopButton.isEnabled = status.running
        highlight(binding.modeAutoButton, status.mode == RelayLinkMode.AUTO)
        highlight(binding.modeWifiButton, status.mode == RelayLinkMode.WIFI)
        highlight(binding.modeBleButton, status.mode == RelayLinkMode.BLE)
    }

    private fun highlight(button: MaterialButton, selected: Boolean) {
        val fill = if (selected) R.color.brand_green_light else R.color.surface_card
        val outline = if (selected) R.color.brand_green else R.color.border_warm
        val text = if (selected) R.color.brand_green else R.color.text_secondary
        button.backgroundTintList = ColorStateList.valueOf(getColor(fill))
        button.strokeColor = ColorStateList.valueOf(getColor(outline))
        button.setTextColor(getColor(text))
    }

    private fun refreshPending() {
        val smsReady = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED && SmsSettings.gatewayNumber(this) != null
        val loraState = when {
            runtime.meshtastic.available() -> "LoRa ON"
            runtime.meshtastic.isInstalled() -> "LoRa ready"
            else -> "LoRa off"
        }
        val net = if (runtime.ledgerReachable()) "Server ON" else "Server off"
        binding.transports.text =
            "$loraState  ·  SMS ${if (smsReady) "ready" else "off"}  ·  $net  ·  " +
                "${runtime.pendingCount()} waiting"
    }

    private fun log(line: String) {
        binding.log.append(if (binding.log.text.isEmpty()) line else "\n$line")
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    companion object {
        private const val TAG = "AgriChain"
    }
}
