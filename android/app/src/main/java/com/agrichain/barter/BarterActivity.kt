package com.agrichain.barter

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Event
import com.agrichain.databinding.ActivityBarterBinding
import java.util.UUID

class BarterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBarterBinding
    private lateinit var signer: ActorIdentity
    private lateinit var client: BarterClient
    private var myBatches: List<BatchInfo> = emptyList()
    private var offers: List<BarterOffer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signer = ActorIdentity.loadOrCreate(this, "farmer")
        client = BarterClient(this)
        binding.actorId.text = signer.actorId
        binding.createButton.setOnClickListener { createOffer() }
        binding.refreshButton.setOnClickListener { load() }
        load()
    }

    private fun load() {
        runAsync("Loading…") {
            val batches = client.myBatches(signer.actorId)
            val loadedOffers = client.offers(signer.actorId)
            ui {
                myBatches = batches
                offers = loadedOffers
                renderBatches()
                renderOffers()
                binding.status.text =
                    "${batches.size} batch(es) · ${loadedOffers.count { it.status == "open" }} open offer(s)"
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
            showError("No batch with tokens on the ledger")
            return
        }
        val giveKg = binding.giveKgInput.text.toString().trim().toIntOrNull()
        val wantKg = binding.wantKgInput.text.toString().trim().toIntOrNull()
        val wantBatchId = binding.wantBatchInput.text.toString().trim()
        val withActor = binding.withActorInput.text.toString().trim()

        if (giveKg == null || giveKg <= 0 || wantKg == null || wantKg <= 0) {
            showError("Give and want must be positive kg")
            return
        }
        if (giveKg > batch.kg) {
            showError("Give exceeds ${batch.kg} kg in this batch")
            return
        }
        if (wantBatchId.isEmpty() || withActor.isEmpty()) {
            showError("Counterparty batch id and actor id are required")
            return
        }

        runAsync("Creating offer…") {
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
                binding.status.text = "Offer created"
                binding.status.setTextColor(getColor(R.color.status_ok))
                load()
            }
        }
    }

    private fun accept(offer: BarterOffer) {
        if (myBatches.none { it.batchId == offer.wantBatchId }) {
            showError("This offer wants tokens from a batch this farmer does not hold")
            return
        }
        runAsync("Accepting…") {
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
                binding.status.text = "Offer accepted — balances swapped"
                binding.status.setTextColor(getColor(R.color.status_ok))
                load()
            }
        }
    }

    private fun cancel(offer: BarterOffer) {
        runAsync("Cancelling…") {
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
                binding.status.text = "Offer cancelled"
                binding.status.setTextColor(getColor(R.color.status_neutral))
                load()
            }
        }
    }

    private fun renderOffers() {
        binding.offerList.removeAllViews()
        if (offers.isEmpty()) {
            binding.offerList.addView(
                TextView(this).apply {
                    text = "No offers yet."
                    setPadding(0, 8, 0, 8)
                }
            )
            return
        }
        offers.forEach { offer ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(
                TextView(this).apply {
                    text = describe(offer)
                    textSize = 13f
                }
            )
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (offer.status == "open" && offer.actorId == signer.actorId) {
                actions.addView(
                    Button(this).apply {
                        text = "Cancel"
                        setOnClickListener { cancel(offer) }
                    }
                )
            }
            if (offer.status == "open" && offer.withActorId == signer.actorId) {
                actions.addView(
                    Button(this).apply {
                        text = "Accept"
                        setOnClickListener { accept(offer) }
                    }
                )
            }
            if (actions.childCount > 0) row.addView(actions)
            binding.offerList.addView(row)
        }
    }

    private fun describe(offer: BarterOffer): String {
        val mine = offer.actorId == signer.actorId
        return if (mine) {
            "${offer.status.uppercase()} · give ${offer.giveKg} kg from ${offer.batchId.take(8)} " +
                "→ want ${offer.wantKg} kg of ${offer.wantBatchId.take(8)} " +
                "from ${shortActor(offer.withActorId)}"
        } else {
            "${offer.status.uppercase()} · ${shortActor(offer.actorId)} gives ${offer.giveKg} kg " +
                "(${offer.batchId.take(8)}) for ${offer.wantKg} kg of your ${offer.wantBatchId.take(8)}"
        }
    }

    private fun shortActor(actorId: String): String {
        val role = actorId.substringBefore(':', actorId)
        val rawId = actorId.substringAfter(':', "")
        return if (rawId.length >= 8) "$role:${rawId.take(8)}" else actorId
    }

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
