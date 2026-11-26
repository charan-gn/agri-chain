package com.agrichain.mesh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.R
import com.agrichain.core.Permissions
import com.agrichain.data.ChatMessage
import com.agrichain.databinding.ActivityMeshBinding
import com.agrichain.ui.UiHelpers
import com.google.android.material.button.MaterialButton

/**
 * The Relay section. One start button turns on Wi-Fi NSD and BLE discovery;
 * the selector attaches whichever link finds a phone first and keeps watching
 * both radios. SMS, LoRa and the ledger gateway stay as backups.
 */
class MeshActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeshBinding
    private lateinit var runtime: MeshRuntime
    private var running = false
    private val recipientIds = mutableListOf<String>()
    private var contactsKey = ""

    private val handler = Handler(Looper.getMainLooper())
    private val linkTick = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) updateRelayLink()
            handler.postDelayed(this, 2_000)
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
        binding.saveChatNameButton.setOnClickListener {
            runtime.setUserName(binding.chatNameInput.text?.toString().orEmpty())
            refreshContacts(force = true)
            log("Your name is now ${runtime.userName}")
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
        updateRelayLink()
        refreshPending()
        if (intent?.getBooleanExtra("autoStart", false) == true && !running) {
            binding.root.post { ensurePermissions() }
        }
    }

    override fun onResume() {
        super.onResume()
        running = runtime.relayStatus().running
        updateRelayLink()
        refreshPending()
        refreshContacts()
        renderChat()
        handler.removeCallbacks(linkTick)
        handler.post(linkTick)
    }

    override fun onPause() {
        handler.removeCallbacks(linkTick)
        super.onPause()
    }

    override fun onDestroy() {
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
        val position = binding.chatRecipientSpinner.selectedItemPosition
        val recipient = recipientIds.getOrNull(position - 1)
        val message = runtime.sendChat(text, recipient)
        if (message == null) {
            log("Chat needs the mesh signing key")
        } else {
            binding.chatInput.text?.clear()
            appendChat(message)
            updateRelayLink()
        }
    }

    /** Builds the recipient list from phones seen on the radio and named peers. */
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

        recipientIds.clear()
        val labels = mutableListOf("Broadcast to all")
        contacts
            .filter { it.userId != runtime.selfId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { contact ->
                recipientIds += contact.userId
                labels += contact.name
            }

        val selected = binding.chatRecipientSpinner.selectedItemPosition.coerceAtLeast(0)
        binding.chatRecipientSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        if (selected < labels.size) binding.chatRecipientSpinner.setSelection(selected)

        binding.contactsText.text = if (contacts.isEmpty()) {
            "Contacts: none yet — share your name from the Relay section"
        } else {
            "Contacts: " + contacts.sortedBy { it.name }.joinToString(", ") { contact ->
                if (contact.userId == runtime.selfId) "${contact.name} (you)" else contact.name
            }
        }
    }

    private fun renderChat() {
        binding.chatMessages.text = ""
        runtime.chatMessages().forEach { appendChat(it) }
        binding.chatScroll.post { binding.chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun appendChat(message: ChatMessage) {
        val label = if (message.outgoing) {
            "You"
        } else {
            runtime.contacts.nameOf(message.senderId) ?: message.senderId.take(12)
        }
        val line = "$label  ${message.body}"
        binding.chatMessages.append(if (binding.chatMessages.text.isEmpty()) line else "\n$line")
        binding.chatScroll.post { binding.chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
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
        binding.chatLinkStatus.text = when {
            !status.running -> "Start the relay to chat."
            active == "none" -> "Searching for a phone — chat will queue."
            else -> "Chat over $active · messages sign with your mesh key."
        }
        if (status.running && status.active != null) {
            runtime.announceUser()
            refreshContacts()
        }
        binding.status.text = if (status.running) "Relay ON · $active" else "Relay is off"
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
