package com.agrichain.relay

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrichain.databinding.ActivityMarketV3Binding
import kotlinx.coroutines.launch

/** Offline-first LISTING screen. The signed message is queued immediately. */
class MarketActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMarketV3Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarketV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.cropSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            V3Dictionaries.crops.map { it.second },
        )
        binding.regionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            V3Dictionaries.regions.map { it.second },
        )
        binding.gradeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            V3Dictionaries.grades.map { it.second },
        )
        binding.qtyInput.setText("500")
        binding.askInput.setText("2500")
        binding.validHoursInput.setText("24")
        binding.createButton.setOnClickListener { createListing() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun createListing() {
        val qty = binding.qtyInput.text.toString().toLongOrNull()
        val ask = binding.askInput.text.toString().toLongOrNull()
        val hours = binding.validHoursInput.text.toString().toLongOrNull() ?: 24
        if (qty == null || qty <= 0) {
            binding.statusText.text = "Enter a quantity above zero"
            return
        }
        if (ask == null || ask <= 0) {
            binding.statusText.text = "Enter an ask in paise per kg"
            return
        }
        val batchHex = binding.batchInput.text.toString().trim()
        val batchId = when {
            batchHex.isEmpty() -> ByteArray(12)
            batchHex.length == 24 -> runCatching { V3Codec.hex(batchHex) }.getOrElse {
                binding.statusText.text = "Batch id must be 24 hex characters"
                return
            }
            else -> {
                binding.statusText.text = "Batch id must be 24 hex characters"
                return
            }
        }
        val crop = V3Dictionaries.crops[binding.cropSpinner.selectedItemPosition].first
        val region = V3Dictionaries.regions[binding.regionSpinner.selectedItemPosition].first
        val grade = V3Dictionaries.grades[binding.gradeSpinner.selectedItemPosition].first
        val validUntil = System.currentTimeMillis() / 1000 + hours.coerceIn(1, 720) * 3600
        val body = V3Body.Listing(
            crop = crop,
            qtyKg = qty,
            askPaise = ask,
            region = region,
            validUntil = validUntil,
            grade = grade,
            batchId = batchId,
        )
        binding.statusText.text = "Signing listing..."
        lifecycleScope.launch {
            val originated = runCatching { RelayRuntime.originate(V3Type.LISTING, body) }
                .getOrElse { error ->
                    binding.statusText.text = "Listing failed: ${error.message}"
                    return@launch
                }
            if (originated == null) {
                binding.statusText.text = "Relay is not running. Open Relay and start it."
                return@launch
            }
            runCatching { RelayRuntime.core?.cacheListing(originated.message, 0) }
            binding.statusText.text =
                "Queued ${originated.msgId.take(12)} · sent to ${originated.links} link(s)"
            render()
        }
    }

    private fun render() {
        val listings = RelayRuntime.snapshot().listings
        if (listings.isEmpty()) {
            binding.listingList.text = "No cached listings yet. Create one or wait for a gateway."
            return
        }
        binding.listingList.text = listings.joinToString("\n\n") { listing ->
            buildString {
                append(V3Dictionaries.cropName(listing.crop))
                append(" · ").append(listing.qtyKg).append(" kg")
                append(" · ask ").append(V3Dictionaries.money(listing.askPaise)).append("/kg")
                append('\n')
                append("id ").append(listing.listingId.take(12))
                append(" · ").append(V3Dictionaries.regionName(listing.region))
                append(" · grade ").append(V3Dictionaries.gradeName(listing.grade))
                append('\n')
                append("remaining ").append(listing.remainingKg).append(" kg")
                append(" · seller ").append(listing.sellerId.take(8))
                if (listing.validUntil > 0) {
                    append(" · until ").append(listing.validUntil)
                }
            }
        }
    }
}
