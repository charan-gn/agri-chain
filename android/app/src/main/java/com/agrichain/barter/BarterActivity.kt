package com.agrichain.barter

import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Event
import com.agrichain.databinding.ActivityBarterBinding
import com.agrichain.qr.BatchQrCodec
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.UUID

class BarterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBarterBinding
    private lateinit var signer: ActorIdentity
    private lateinit var client: BarterClient
    private var myBatches: List<BatchInfo> = emptyList()
    private var offers: List<BarterOffer> = emptyList()

    private val scanPartnerLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val batchId = BatchQrCodec.parse(contents)
        if (batchId == null) {
            Toast.makeText(this, "Not an AgriChain crop QR", Toast.LENGTH_SHORT).show()
        } else {
            binding.wantBatchInput.setText(batchId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        signer = ActorIdentity.loadOrCreate(this, "farmer")
        client = BarterClient(this)
        binding.actorId.text = signer.actorId
        binding.scanWantBatchButton.setOnClickListener {
            scanPartnerLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the other farmer's harvest QR")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
        binding.createButton.setOnClickListener { createOffer() }
        binding.refreshButton.setOnClickListener { load() }
        load()
    }

    private fun load() {
        runAsync("Loading your harvests & trades…") {
            val batches = client.myBatches(signer.actorId)
            val loadedOffers = client.offers(signer.actorId)
            ui {
                myBatches = batches
                offers = loadedOffers
                renderBatches()
                renderOffers()
                binding.status.text =
                    "${batches.size} harvest(s) ready · ${loadedOffers.count { it.status == "open" }} open trade(s)"
                binding.status.setTextColor(getColor(R.color.status_ok))
            }
        }
    }

    private fun renderBatches() {
        val labels = myBatches.map { it.label }
        binding.giveBatchSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.createButton.isEnabled = myBatches.isNotEmpty()
    }

    private fun createOffer() {
        val batch = myBatches.getOrNull(binding.giveBatchSpinner.selectedItemPosition)
        if (batch == null) {
            showError("Upload a harvest to the server first before trading")
            return
        }
        val giveKg = binding.giveKgInput.text.toString().trim().toIntOrNull()
        val wantKg = binding.wantKgInput.text.toString().trim().toIntOrNull()
        val wantBatchId = binding.wantBatchInput.text.toString().trim()
        val withActor = binding.withActorInput.text.toString().trim()

        if (giveKg == null || giveKg <= 0 || wantKg == null || wantKg <= 0) {
            showError("Enter positive kg numbers for both sides")
            return
        }
        if (giveKg > batch.kg) {
            showError("You only have ${batch.kg} kg left in this harvest")
            return
        }
        if (wantBatchId.isEmpty() || withActor.isEmpty()) {
            showError("Enter or scan the other farmer's harvest code & ID")
            return
        }

        runAsync("Sending trade offer…") {
            val head = client.head(batch.batchId)
            val event = Event.create(
                batch.batchId,
                "BARTER",
                mapOf(
                    "offerId" to UUID.randomUUID().toString(),
                    "action" to "offer",
                    "withActorId" to withActor,
                    "giveKg" to giveKg,
                    "wantBatchId" to wantBatchId,
                    "wantKg" to wantKg,
                ),
                signer,
                prevHash = head,
            )
            client.post(event)
            ui {
                binding.giveKgInput.text?.clear()
                binding.wantKgInput.text?.clear()
                binding.wantBatchInput.text?.clear()
                binding.withActorInput.text?.clear()
                binding.status.text = "Trade offer sent!"
                binding.status.setTextColor(getColor(R.color.status_ok))
                load()
            }
        }
    }

    private fun accept(offer: BarterOffer) {
        if (myBatches.none { it.batchId == offer.wantBatchId }) {
            showError("This trade asks for a harvest you do not hold")
            return
        }
        runAsync("Accepting trade…") {
            val head = client.head(offer.wantBatchId)
            val event = Event.create(
                offer.wantBatchId,
                "BARTER",
                mapOf(
                    "offerId" to offer.offerId,
                    "action" to "accept",
                    "withActorId" to offer.actorId,
                    "giveKg" to offer.wantKg,
                    "wantBatchId" to offer.batchId,
                    "wantKg" to offer.giveKg,
                ),
                signer,
                prevHash = head,
            )
            client.post(event)
            ui {
                binding.status.text = "Trade accepted — crops swapped!"
                binding.status.setTextColor(getColor(R.color.status_ok))
                load()
            }
        }
    }

    private fun cancel(offer: BarterOffer) {
        runAsync("Cancelling trade…") {
            val head = client.head(offer.batchId)
            val event = Event.create(
                offer.batchId,
                "BARTER",
                mapOf(
                    "offerId" to offer.offerId,
                    "action" to "cancel",
                    "withActorId" to offer.withActorId,
                    "giveKg" to offer.giveKg,
                    "wantBatchId" to offer.wantBatchId,
                    "wantKg" to offer.wantKg,
                ),
                signer,
                prevHash = head,
            )
            client.post(event)
            ui {
                binding.status.text = "Trade cancelled"
                binding.status.setTextColor(getColor(R.color.status_neutral))
                load()
            }
        }
    }

    private fun renderOffers() {
        binding.offerList.removeAllViews()
        val pad14 = dp(14)
        val gap8 = dp(8)
        if (offers.isEmpty()) {
            binding.offerList.addView(
                TextView(this).apply {
                    text = "No trade offers yet."
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(pad14, pad14, pad14, pad14)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 15f
                }
            )
            return
        }

        offers.forEach { offer ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(pad14, pad14, pad14, pad14)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badgeText = when (offer.status) {
                "open" -> "WAITING"
                "accepted" -> "DONE ✓"
                else -> "CANCELLED"
            }
            val badgeColor = when (offer.status) {
                "open" -> R.color.status_warn
                "accepted" -> R.color.status_ok
                else -> R.color.status_neutral
            }
            topRow.addView(
                TextView(this).apply {
                    text = badgeText
                    setTextColor(getColor(badgeColor))
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            card.addView(topRow)

            card.addView(
                TextView(this).apply {
                    text = describe(offer)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    setPadding(0, gap8 / 2, 0, gap8 / 2)
                }
            )

            if (offer.status == "open" && offer.actorId == signer.actorId) {
                card.addView(
                    MaterialButton(this).apply {
                        text = "Cancel Offer"
                        setBackgroundColor(getColor(R.color.alert_red))
                        setTextColor(getColor(android.R.color.white))
                        setOnClickListener { cancel(offer) }
                    }
                )
            }
            if (offer.status == "open" && offer.withActorId == signer.actorId) {
                card.addView(
                    MaterialButton(this).apply {
                        text = "Accept & Swap"
                        setBackgroundColor(getColor(R.color.brand_green))
                        setTextColor(getColor(android.R.color.white))
                        setOnClickListener { accept(offer) }
                    }
                )
            }
            binding.offerList.addView(card)
        }
    }

    private fun describe(offer: BarterOffer): String {
        val mine = offer.actorId == signer.actorId
        return if (mine) {
            "You give ${offer.giveKg} kg (${offer.batchId.take(8)}) ⇄ " +
                "You get ${offer.wantKg} kg (${offer.wantBatchId.take(8)}) from ${shortActor(offer.withActorId)}"
        } else {
            "${shortActor(offer.actorId)} gives ${offer.giveKg} kg (${offer.batchId.take(8)}) ⇄ " +
                "Wants ${offer.wantKg} kg of your ${offer.wantBatchId.take(8)}"
        }
    }

    private fun shortActor(actorId: String): String {
        val role = actorId.substringBefore(':', actorId)
        val rawId = actorId.substringAfter(':', "")
        return if (rawId.length >= 8) "$role:${rawId.take(8)}" else actorId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun runAsync(message: String, block: () -> Unit) {
        binding.status.text = message
        binding.status.setTextColor(getColor(R.color.status_neutral))
        binding.createButton.isEnabled = false
        binding.refreshButton.isEnabled = false
        Thread {
            try {
                block()
            } catch (error: Exception) {
                ui { showError(error.message ?: "Request failed") }
            } finally {
                ui {
                    binding.createButton.isEnabled = myBatches.isNotEmpty()
                    binding.refreshButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun showError(message: String) {
        binding.status.text = message
        binding.status.setTextColor(getColor(R.color.status_alert))
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }
}
