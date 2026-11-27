package com.agrichain.relay

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrichain.databinding.ActivityBarterV3Binding
import kotlinx.coroutines.launch

/** v3 barter: offers are signed client messages, so they work while offline. */
class BarterV3Activity : AppCompatActivity() {

    private lateinit var binding: ActivityBarterV3Binding
    private lateinit var store: BarterStoreV3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarterV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        store = BarterStoreV3(this)
        binding.backButton.setOnClickListener { finish() }
        binding.giveCropSpinner.adapter = spinner(V3Dictionaries.crops.map { it.second })
        binding.wantCropSpinner.adapter = spinner(V3Dictionaries.crops.map { it.second })
        binding.regionSpinner.adapter = spinner(V3Dictionaries.regions.map { it.second })
        binding.giveKgInput.setText("100")
        binding.wantKgInput.setText("100")
        binding.validHoursInput.setText("48")
        binding.actorText.text = "My actor id: ${actorId()}"
        binding.offerButton.setOnClickListener { createOffer() }
        binding.acceptButton.setOnClickListener { acceptOffer() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun actorId(): String =
        RelayRuntime.identity?.actorHex
            ?: runCatching { V3Identity.loadOrCreate(this).actorHex }
                .getOrDefault("not available")

    private fun spinner(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

    private fun createOffer() {
        val giveKg = binding.giveKgInput.text.toString().toLongOrNull()
        val wantKg = binding.wantKgInput.text.toString().toLongOrNull()
        val hours = binding.validHoursInput.text.toString().toLongOrNull() ?: 48
        if (giveKg == null || giveKg <= 0 || wantKg == null || wantKg <= 0) {
            binding.statusText.text = "Enter positive kg amounts for both sides"
            return
        }
        val giveCrop = V3Dictionaries.crops[binding.giveCropSpinner.selectedItemPosition].first
        val wantCrop = V3Dictionaries.crops[binding.wantCropSpinner.selectedItemPosition].first
        val region = V3Dictionaries.regions[binding.regionSpinner.selectedItemPosition].first
        val validUntil = System.currentTimeMillis() / 1000 + hours.coerceIn(1, 720) * 3600
        val body = V3Body.BarterOffer(
            giveCrop = giveCrop,
            giveKg = giveKg,
            wantCrop = wantCrop,
            wantKg = wantKg,
            region = region,
            validUntil = validUntil,
        )
        binding.statusText.text = "Signing BARTER_OFFER..."
        lifecycleScope.launch {
            val originated = runCatching {
                RelayRuntime.originate(V3Type.BARTER_OFFER, body)
            }.getOrElse { error ->
                binding.statusText.text = "Offer failed: ${error.message}"
                return@launch
            }
            if (originated == null) {
                binding.statusText.text = "Relay is not running. Open Relay and start it."
                return@launch
            }
            store.save(
                LocalBarterOffer(
                    offerId = originated.msgId,
                    giveCrop = giveCrop,
                    giveKg = giveKg,
                    wantCrop = wantCrop,
                    wantKg = wantKg,
                    region = region,
                    validUntil = validUntil,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            binding.offerIdText.text = "Offer id: ${originated.msgId}\nShare this id and your actor id with the other farmer."
            binding.statusText.text =
                "Queued ${originated.msgId.take(12)} ... sent to ${originated.links} link(s)"
            render()
        }
    }

    private fun acceptOffer() {
        val offerHex = binding.acceptOfferIdInput.text.toString().trim()
        val ownerHex = binding.acceptOwnerInput.text.toString().trim()
        if (offerHex.length != 24 || runCatching { V3Codec.hex(offerHex) }.isFailure) {
            binding.statusText.text = "Offer id must be 24 hex characters"
            return
        }
        if (ownerHex.length != 16 || runCatching { V3Codec.hex(ownerHex) }.isFailure) {
            binding.statusText.text = "Owner actor id must be 16 hex characters"
            return
        }
        val body = V3Body.BarterAccept(offerId = V3Codec.hex(offerHex))
        binding.statusText.text = "Signing BARTER_ACCEPT..."
        lifecycleScope.launch {
            val originated = runCatching {
                RelayRuntime.originate(V3Type.BARTER_ACCEPT, body, V3Codec.hex(ownerHex))
            }.getOrElse { error ->
                binding.statusText.text = "Accept failed: ${error.message}"
                return@launch
            }
            if (originated == null) {
                binding.statusText.text = "Relay is not running. Open Relay and start it."
                return@launch
            }
            store.markAccepted(offerHex)
            binding.statusText.text =
                "Queued accept ${originated.msgId.take(12)} ... sent to ${originated.links} link(s)"
            render()
        }
    }

    private fun render() {
        val offers = store.all()
        if (offers.isEmpty()) {
            binding.offerList.text = "No offers on this phone yet."
            return
        }
        binding.offerList.text = offers.joinToString("\n\n") { offer ->
            buildString {
                append(V3Dictionaries.cropName(offer.giveCrop))
                append(" ").append(offer.giveKg).append(" kg")
                append(" for ")
                append(V3Dictionaries.cropName(offer.wantCrop))
                append(" ").append(offer.wantKg).append(" kg")
                append(" (").append(offer.status).append(")\n")
                append(offer.offerId)
                append("\n")
                append(V3Dictionaries.regionName(offer.region))
                append(" ... until ").append(offer.validUntil)
            }
        }
    }
}
