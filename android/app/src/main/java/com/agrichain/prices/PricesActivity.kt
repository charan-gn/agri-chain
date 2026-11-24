package com.agrichain.prices

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityPricesBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PricesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPricesBinding
    private lateinit var cache: PriceCache
    private lateinit var repository: PriceRepository

    private val crops = arrayOf("wheat", "rice", "maize", "chilli", "cotton")
    private val markets = arrayOf(
        "All markets",
        "warangal",
        "khammam",
        "nizamabad",
        "karimnagar",
        "adilabad",
    )
    private val timeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.US)

    private var requestSeq = 0
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPricesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = PriceCache(this)
        repository = PriceRepository(cache)

        binding.ledgerUrlInput.setText(cache.ledgerUrl())
        binding.cropSpinner.adapter = adapter(crops)
        binding.marketSpinner.adapter = adapter(markets)

        val savedCrop = crops.indexOfFirst { it.equals(cache.crop(), ignoreCase = true) }
        binding.cropSpinner.setSelection(savedCrop.coerceAtLeast(0), false)
        val savedMarket = cache.market()
        val marketIndex = if (savedMarket == null) {
            0
        } else {
            markets.indexOfFirst { it.equals(savedMarket, ignoreCase = true) }
        }
        binding.marketSpinner.setSelection(marketIndex.coerceAtLeast(0), false)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (ready) load(refresh = false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.cropSpinner.onItemSelectedListener = listener
        binding.marketSpinner.onItemSelectedListener = listener
        binding.refreshButton.setOnClickListener { load(refresh = true) }

        ready = true
        load(refresh = false)
    }

    override fun onDestroy() {
        requestSeq += 1
        super.onDestroy()
    }

    private fun load(refresh: Boolean) {
        val crop = crops[binding.cropSpinner.selectedItemPosition]
        val market = selectedMarket()
        val url = PriceUrls.normalizeBaseUrl(binding.ledgerUrlInput.text.toString())
        binding.ledgerUrlInput.setText(url)
        cache.saveLedgerUrl(url)
        cache.saveSelection(crop, market)

        showCached(crop, market)

        val seq = ++requestSeq
        setLoading(true, refresh)
        Thread {
            val result = runCatching { repository.fetch(url, crop, market, refresh) }
            runOnUiThread {
                if (seq != requestSeq || isFinishing || isDestroyed) return@runOnUiThread
                setLoading(false, refresh)
                result.onSuccess { snapshot ->
                    val savedAt = System.currentTimeMillis()
                    cache.save(crop, market, snapshot, savedAt)
                    render(snapshot, live = true, savedAt = savedAt)
                }.onFailure { error ->
                    val cached = repository.cached(crop, market)
                    if (cached == null) {
                        renderEmpty(
                            "Offline and no cached rates for ${cropLabel()}.\n" +
                                (error.message ?: "Unknown network error")
                        )
                    } else {
                        render(
                            cached.snapshot,
                            live = false,
                            savedAt = cached.savedAt,
                            error = error.message,
                        )
                    }
                }
            }
        }.start()
    }

    private fun showCached(crop: String, market: String?) {
        val cached = repository.cached(crop, market)
        if (cached == null) {
            renderEmpty("No cached rates for ${cropLabel()} yet. Tap Refresh when online.")
        } else {
            render(cached.snapshot, live = false, savedAt = cached.savedAt)
        }
    }

    private fun render(
        snapshot: PriceSnapshot,
        live: Boolean,
        savedAt: Long,
        error: String? = null,
    ) {
        val points = snapshot.prices
        binding.priceChart.setPrices(points)

        val source = if (live) "Live" else "Cached"
        val stamp = if (live && snapshot.updatedAt > 0) snapshot.updatedAt else savedAt
        val detail = if (error.isNullOrBlank()) "" else " · ${error.take(80)}"
        binding.statusText.text = buildString {
            append(source)
            append(" · ")
            append(timeFormat.format(Date(stamp)))
            append(" · ")
            append(if (points.isEmpty()) "no rates" else "${points.size} market(s)")
            append(detail)
        }

        if (points.isEmpty()) {
            binding.detailText.text = "No rates for ${cropLabel()} yet."
            return
        }

        val minimum = points.minOf { it.pricePerKgPaise }
        val maximum = points.maxOf { it.pricePerKgPaise }
        binding.detailText.text = buildString {
            append(cropLabel())
            append(" · ")
            append(PriceFormat.rupees(minimum))
            append(" – ")
            append(PriceFormat.perKg(maximum))
            points.sortedBy { it.market }.forEach { point ->
                append("\n")
                append(point.market.padEnd(12))
                append(" ")
                append(PriceFormat.perKg(point.pricePerKgPaise))
                append("  ")
                append(point.source)
            }
        }
    }

    private fun renderEmpty(message: String) {
        binding.priceChart.setPrices(emptyList())
        binding.statusText.text = message
        binding.detailText.text = ""
    }

    private fun setLoading(loading: Boolean, refresh: Boolean) {
        binding.refreshButton.isEnabled = !loading
        binding.refreshButton.text = when {
            !loading -> "Refresh prices"
            refresh -> "Refreshing…"
            else -> "Fetching…"
        }
    }

    private fun selectedMarket(): String? {
        val position = binding.marketSpinner.selectedItemPosition
        return if (position <= 0) null else markets[position]
    }

    private fun cropLabel(): String =
        crops[binding.cropSpinner.selectedItemPosition]
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    private fun adapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, values).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
