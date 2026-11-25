package com.agrichain

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.box.BoxActivity
import com.agrichain.core.Permissions
import com.agrichain.data.ChatMessage
import com.agrichain.data.SyncActivity
import com.agrichain.data.SyncStore
import com.agrichain.databinding.ActivityMainBinding
import com.agrichain.mesh.MeshActivity
import com.agrichain.mesh.MeshRuntime
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.prices.PricesActivity
import com.agrichain.ui.BuyerActivity
import com.agrichain.ui.FarmerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat-first launcher. The mesh chat is the home screen; the original farmer,
 * buyer, box, price, sync and radio screens stay one tap away under Tools.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var runtime: MeshRuntime
    private var discovering = false
    private var requestingAll = false

    private val handler = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshMeshStatus()
            handler.postDelayed(this, 3_000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val wasAll = requestingAll
        requestingAll = false
        if (Permissions.missingMesh(this).isEmpty()) startMesh()
        refreshMeshStatus()
        if (wasAll) showPermissionSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runtime = MeshRuntime.get(this)

        // tools/phone.sh passes the active USB port so custom PORT values still work.
        intent?.getStringExtra("ledgerUrl")?.let { url ->
            runCatching { LedgerSettings.setBaseUrl(this, url) }
        }

        binding.toolsButton.setOnClickListener { showTools() }
        binding.meshBanner.setOnClickListener { ensureMeshPermissions() }
        binding.sendButton.setOnClickListener { sendChat() }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else {
                false
            }
        }

        renderChat()
        refreshMeshStatus()
        Permissions.logState(this)
        if (Permissions.missingMesh(this).isEmpty()) {
            startMesh()
        } else {
            ensureMeshPermissions()
        }
        if (intent?.getBooleanExtra("checkPermissions", false) == true) {
            binding.root.post { requestAllPermissions() }
        }
        autoDetect(announce = false)
        handleDevIntent()
    }

    override fun onResume() {
        super.onResume()
        runtime.chatListener = { message ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    addBubble(message)
                    scrollToBottom()
                }
            }
        }
        renderChat()
        refreshMeshStatus()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTick)
        runtime.chatListener = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDevIntent()
    }

    /** adb hooks: --es openBox <batchId> or --ez openMesh true, both with --ez autoStart. */
    private fun handleDevIntent() {
        val autoStart = intent?.getBooleanExtra("autoStart", false) == true
        intent?.getStringExtra("openBox")?.let { batchId ->
            Log.i(Permissions.TAG, "dev: opening box $batchId autoStart=$autoStart")
            startActivity(
                Intent(this, BoxActivity::class.java)
                    .putExtra("batchId", batchId)
                    .putExtra("autoStart", autoStart)
            )
        }
        if (intent?.getBooleanExtra("openMesh", false) == true) {
            Log.i(Permissions.TAG, "dev: opening mesh autoStart=$autoStart")
            startActivity(
                Intent(this, MeshActivity::class.java)
                    .putExtra("autoStart", autoStart)
            )
        }
    }

    // ---- chat -------------------------------------------------------------

    private fun sendChat() {
        val text = binding.chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val message = runtime.sendChat(text)
        if (message == null) {
            toast("Chat needs the mesh signing key")
            return
        }
        binding.chatInput.setText("")
        addBubble(message)
        scrollToBottom()
        refreshMeshStatus()
        if (message.status == "waiting") {
            toast("Saved. It sends when a phone is nearby.")
        }
    }

    private fun renderChat() {
        binding.messageList.removeAllViews()
        val messages = runtime.chatMessages()
        if (messages.isEmpty()) {
            binding.messageList.addView(emptyState())
            return
        }
        messages.forEach { addBubble(it) }
        scrollToBottom()
    }

    private fun addBubble(message: ChatMessage) {
        val outgoing = message.outgoing
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (outgoing) Gravity.END else Gravity.START
        }

        val meta = TextView(this).apply {
            text = "${if (outgoing) "You" else senderLabel(message.senderId)} · " +
                clock.format(Date(message.ts))
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setPadding(dp(4), 0, dp(4), dp(3))
        }

        val bubble = TextView(this).apply {
            text = message.body
            textSize = 16f
            setTextColor(
                if (outgoing) Color.WHITE
                else ContextCompat.getColor(this@MainActivity, R.color.text_primary)
            )
            setBackgroundResource(if (outgoing) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setTextIsSelectable(true)
        }

        column.addView(meta)
        column.addView(bubble)

        if (outgoing) {
            val delivered = TextView(this).apply {
                text = if (message.status == "waiting") {
                    "waiting for a nearby phone"
                } else {
                    "sent to mesh"
                }
                textSize = 11f
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (message.status == "waiting") R.color.status_warn else R.color.status_ok,
                    )
                )
                setPadding(dp(4), dp(3), dp(4), 0)
            }
            column.addView(delivered)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (outgoing) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        row.addView(
            column,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        binding.messageList.addView(row)
    }

    private fun emptyState(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(48), 0, dp(24))
        }
        box.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ill_mesh)
                contentDescription = "Nearby mesh"
                alpha = 0.9f
            },
            LinearLayout.LayoutParams(dp(88), dp(88)),
        )
        box.addView(
            TextView(this).apply {
                text = "No messages yet"
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
            },
        )
        box.addView(
            TextView(this).apply {
                text = "Chat goes phone-to-phone over Bluetooth.\nNo internet or phone number needed."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                gravity = Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
            },
        )
        return box
    }

    private fun senderLabel(senderId: String): String =
        "Phone · ${senderId.takeLast(6).ifBlank { "unknown" }}"

    private fun scrollToBottom() {
        binding.chatScroll.post {
            binding.chatScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ---- mesh -------------------------------------------------------------

    private fun ensureMeshPermissions() {
        val missing = Permissions.missingMesh(this)
        if (missing.isEmpty()) {
            startMesh()
        } else {
            permissionLauncher.launch(missing.map { it.permission }.toTypedArray())
        }
    }

    private fun startMesh() {
        if (Permissions.missingMesh(this).isNotEmpty()) {
            refreshMeshStatus()
            return
        }
        val running = runtime.startBleMesh()
        refreshMeshStatus()
        if (!running) {
            binding.meshBanner.text = "Turn on Bluetooth to chat phone-to-phone. Tap to retry."
        }
    }

    private fun refreshMeshStatus() {
        val running = runtime.bleRunning()
        val peers = runtime.blePeerCount()
        binding.meshStatus.text = when {
            !running -> "Nearby mesh is off"
            peers == 0 -> "BLE mesh on · searching for phones"
            peers == 1 -> "BLE mesh on · 1 phone nearby"
            else -> "BLE mesh on · $peers phones nearby"
        }
        if (running) {
            binding.meshBanner.visibility = View.GONE
        } else {
            binding.meshBanner.visibility = View.VISIBLE
            if (binding.meshBanner.text != "Turn on Bluetooth to chat phone-to-phone. Tap to retry.") {
                binding.meshBanner.text = if (Permissions.missingMesh(this).isNotEmpty()) {
                    "Allow Bluetooth to use the nearby chat mesh. Tap to grant."
                } else {
                    "Bluetooth mesh is off. Tap to turn it on."
                }
            }
        }

        if (running && peers > 0 && runtime.flushChatOutbox() > 0) {
            renderChat()
        }
    }

    // ---- tools and existing screens --------------------------------------

    private fun showTools() {
        val pending = runCatching { SyncStore(this).pendingCount() }.getOrDefault(0)
        val missing = Permissions.missing(this).size
        val items = arrayOf(
            "I grow crops — add & trade",
            "Check & buy — scan crop QR",
            "Crop box — GPS & lock alarm",
            "Crop prices",
            if (pending > 0) "Save to server — $pending waiting" else "Save to server — up to date",
            "Mesh & radio settings",
            if (missing == 0) "Phone permissions — all granted"
            else "Phone permissions — $missing missing",
            "Find server — ${LedgerSettings.baseUrl(this)}",
        )
        AlertDialog.Builder(this)
            .setTitle("AgriChain tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, FarmerActivity::class.java))
                    1 -> startActivity(Intent(this, BuyerActivity::class.java))
                    2 -> startActivity(Intent(this, BoxActivity::class.java))
                    3 -> startActivity(Intent(this, PricesActivity::class.java))
                    4 -> startActivity(Intent(this, SyncActivity::class.java))
                    5 -> startActivity(Intent(this, MeshActivity::class.java))
                    6 -> if (Permissions.missing(this).isEmpty()) {
                        showPermissionSummary()
                    } else {
                        requestAllPermissions()
                    }
                    7 -> autoDetect(announce = true)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun requestAllPermissions() {
        requestingAll = true
        permissionLauncher.launch(Permissions.required().map { it.permission }.toTypedArray())
    }

    private fun showPermissionSummary() {
        val missing = Permissions.missing(this)
        val message = if (missing.isEmpty()) {
            "All ${Permissions.required().size} permissions are allowed."
        } else {
            Permissions.summary(this) +
                "\n\nDenied (enable in App info > Permissions):\n\n" +
                Permissions.describe(missing) +
                "\n\nSMS toggles greyed out? Open App info, tap ⋮, then " +
                "\"Allow restricted settings\", and grant again."
        }
        AlertDialog.Builder(this)
            .setTitle("Permissions")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun autoDetect(announce: Boolean) {
        if (discovering) return
        discovering = true
        LedgerDiscovery.discoverAsync(
            this,
            onLog = { line -> Log.i(Permissions.TAG, "ledger discovery: $line") },
        ) { found ->
            runOnUiThread {
                discovering = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (found != null) {
                    if (announce) toast("Connected: $found")
                } else if (announce) {
                    toast("No server found. Start ./tools/ledger.sh or ./tools/dev.sh.")
                }
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
