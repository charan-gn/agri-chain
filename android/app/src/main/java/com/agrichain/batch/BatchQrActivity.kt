package com.agrichain.batch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Chains
import com.agrichain.data.SyncStore
import com.agrichain.databinding.ActivityBatchQrBinding
import com.agrichain.net.LedgerClient
import com.agrichain.qr.BatchQrCodec
import com.agrichain.qr.QrBitmap
import com.agrichain.ui.UiHelpers
import org.json.JSONObject

class BatchQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchQrBinding
    private lateinit var batchId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        batchId = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
        val event = BatchStore(this).registerEvent(batchId)
        if (batchId.isEmpty() || event == null) {
            Toast.makeText(this, "Harvest not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val payload = BatchQrCodec.payload(batchId)
        val crop = event.data["crop"]?.toString()
        binding.cropImage.setImageResource(UiHelpers.cropIcon(crop))
        binding.qrImage.setImageBitmap(QrBitmap.encode(payload))
        binding.details.text =
            "${UiHelpers.cropTitle(crop)} · ${event.data["weightKg"]} kg · Grade ${event.data["grade"]}"
        binding.batchId.text = "Batch code: $batchId"
        binding.actor.text = "Signed by: ${event.actorId}"
        binding.payload.text = "QR text: $payload"

        binding.copyCodeButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("AgriChain batch code", batchId))
            Toast.makeText(this, "Batch code copied", Toast.LENGTH_SHORT).show()
        }
        binding.doneButton.setOnClickListener { finish() }
        binding.authorizeButton.setOnClickListener { writeBind(binding.boxIdInput.text.toString()) }
        binding.unbindButton.setOnClickListener { writeBind(null) }
        showLatestBind()
        UiHelpers.wireToggle(binding.detailsHeader, binding.detailsBody, binding.detailsChevron)
    }

    private fun showLatestBind() {
        val latest = BatchStore(this).all()
            .filter { it.batchId == batchId && it.type == "BIND" }
            .maxByOrNull { it.ts }
        binding.bindStatus.text = if (latest == null) {
            "No box authorized yet."
        } else {
            val boxId = latest.data["boxId"]?.toString()
            if (boxId.isNullOrBlank()) "Box unbound." else "Authorized: $boxId"
        }
    }

    private fun writeBind(rawBoxId: String?) {
        val boxId = rawBoxId?.trim()?.takeIf { it.isNotEmpty() }
        if (boxId != null && !BOX_ID.matches(boxId)) {
            binding.boxIdInput.error = "Use the box ID copied from the box phone (box:<uuid>)"
            return
        }
        binding.authorizeButton.isEnabled = false
        binding.unbindButton.isEnabled = false
        binding.bindStatus.text = "Signing BIND on the farmer main chain…"

        Thread {
            val farmer = ActorIdentity.loadOrCreate(this, "farmer")
            val localHead = Chains.head(
                BatchStore(this).all(),
                farmer.actorId,
                Chains.MAIN,
            )
            val serverHead = runCatching { serverMainHead(farmer.actorId) }.getOrNull()
            // Prefer the local chain when it has unsigned/pending events; only a fresh
            // install with no local chain needs the server head.
            val prevHash = localHead.ifEmpty { serverHead.orEmpty() }
            val result = runCatching {
                val bind = BindEvent.create(
                    batchId = batchId,
                    boxId = boxId,
                    signer = farmer,
                    prevHash = prevHash,
                )
                BatchStore(this).save(bind)
                SyncStore(this).enqueue(bind)
                bind
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.authorizeButton.isEnabled = true
                binding.unbindButton.isEnabled = true
                result.onSuccess { bind ->
                    binding.bindStatus.text = if (boxId == null) {
                        "Unbind signed. Sync to make it effective."
                    } else {
                        "Authorized $boxId — sync to make it effective."
                    }
                    Toast.makeText(
                        this,
                        if (boxId == null) "Unbind saved" else "Box authorized",
                        Toast.LENGTH_SHORT,
                    ).show()
                    showLatestBind()
                    binding.bindStatus.append("\nBIND ${bind.hash.take(12)}…")
                }.onFailure { error ->
                    binding.bindStatus.text = "Could not sign BIND: ${error.message}"
                }
            }
        }.start()
    }

    /** Uses the server head when online; offline events still sync after REGISTER. */
    private fun serverMainHead(actorId: String): String? {
        val json = LedgerClient(this).getBatch(batchId)
        val chains = json.optJSONArray("chains") ?: return null
        for (index in 0 until chains.length()) {
            val chain = chains.getJSONObject(index)
            if (chain.optString("actorId") == actorId && chain.optString("stream") == "main") {
                return chain.optString("head").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    companion object {
        private const val EXTRA_BATCH_ID = "batchId"
        private val BOX_ID = Regex("^box:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

        fun intent(context: Context, batchId: String): Intent =
            Intent(context, BatchQrActivity::class.java).putExtra(EXTRA_BATCH_ID, batchId)
    }
}
