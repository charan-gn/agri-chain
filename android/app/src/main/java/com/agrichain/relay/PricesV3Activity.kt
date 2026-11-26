package com.agrichain.relay

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityPricesV3Binding

/** Read-only view of the signed PRICE_TABLE cache. */
class PricesV3Activity : AppCompatActivity() {

    private lateinit var binding: ActivityPricesV3Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPricesV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.regionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            V3Dictionaries.regions.map { it.second },
        )
        binding.regionSpinner.setSelection(
            (RelayRuntime.settings(this).region - 1).coerceIn(0, V3Dictionaries.regions.size - 1),
        )
        binding.refreshButton.setOnClickListener { render() }
        render()
    }

    private fun render() {
        val region = V3Dictionaries.regions[binding.regionSpinner.selectedItemPosition].first
        val cache = RelayRuntime.snapshot().prices.firstOrNull { it.region == region }
        if (cache == null) {
            binding.statusText.text = "No signed price table cached for this region."
            binding.priceList.text =
                "Connect a gateway or wait for a PRICE_BEACON, then open this screen again."
            return
        }
        val ageSeconds = cache.ageMs / 1000
        binding.statusText.text =
            "version ${cache.version} · asOf ${cache.asOf} · age ${ageSeconds}s · ${cache.source}"
        binding.priceList.text = cache.rows.joinToString("\n") { row ->
            "${V3Dictionaries.cropName(row.crop).padEnd(12)} " +
                "${V3Dictionaries.money(row.pricePaise.toLong()).padStart(10)} /kg  " +
                "(${row.trades} trades)"
        }
    }
}
