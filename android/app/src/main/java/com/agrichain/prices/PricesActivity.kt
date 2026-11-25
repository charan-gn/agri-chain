package com.agrichain.prices

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityPricesBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PricesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPricesBinding
    private lateinit var cache: PriceCache
    private lateinit var repository: PriceRepository

    private val crops = arrayOf("wheat", "rice", "maize", "chilli", "cotton")
    private val timeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.US)

    private var requestSeq = 0
    private var ready = false
    private var discovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPricesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = PriceCache(this)
        repository = PriceRepository(cache)

        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))
        binding.cropSpinner.adapter = adapter(crops)

        val savedCrop = crops.indexOfFirst { it.equals(cache.crop(), ignoreCase = true) }
        binding.cropSpinner.setSelection(savedCrop.coerceAtLeast(0), false)

        binding.cropSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (ready) load()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.refreshButton.setOnClickListener { load() }
        binding.findLedgerButton.setOnClickListener { discoverLedger() }

        ready = true
        load()
    }

    override fun onDestroy() {
        requestSeq += 1
        super.onDestroy()
    }

    private fun load() {
        val crop = crops[binding.cropSpinner.selectedItemPosition]
        val raw = binding.ledgerUrlInput.text.toString().trim()
        if (raw.isEmpty()) {
            renderEmpty("Enter the ledger host, or tap Auto-detect ledger.")
            return
        }
        val url = try {
            LedgerSettings.setBaseUrl(this, PriceUrls.normalizeBaseUrl(raw))
        } catch (error: IllegalArgumentException) {
            renderEmpty(error.message ?: "Enter the ledger host")
            return
        }
        binding.ledgerUrlInput.setText(url)
        cache.saveSelection(crop)

        showCached(crop)

        val seq = ++requestSeq
        setLoading(true)
        Thread {
            var result = runCatching { repository.fetch(url, crop) }
            if (result.isFailure && !LedgerDiscovery.probe(url)) {
                val found = LedgerDiscovery.discover(this)
                if (found != null && found != url) {
                    result = runCatching { repository.fetch(found, crop) }
                    if (result.isSuccess) {
                        runOnUiThread {
                            if (seq == requestSeq && !isFinishing && !isDestroyed) {
                                binding.ledgerUrlInput.setText(found)
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                if (seq != requestSeq || isFinishing || isDestroyed) return@runOnUiThread
                setLoading(false)
                result.onSuccess { snapshot ->
                    val savedAt = System.currentTimeMillis()
                    cache.save(crop, snapshot, savedAt)
                    render(snapshot, live = true, savedAt = savedAt)
                }.onFailure { error ->
                    val cached = repository.cached(crop)
                    if (cached == null) {
                        renderEmpty(
                            "Offline and no cached token price for ${cropLabel()}.\n" +
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

    private fun discoverLedger() {
        if (discovering) return
        discovering = true
        binding.findLedgerButton.isEnabled = false
        binding.statusText.text = "Searching for a ledger on USB and Wi-Fi…"
        LedgerDiscovery.discoverAsync(
            this,
            onLog = { line ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && discovering) {
                        binding.statusText.text = line
                    }
                }
            },
        ) { found ->
            runOnUiThread {
                discovering = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.findLedgerButton.isEnabled = true
                if (found == null) {
                    binding.statusText.text =
                        "No ledger found. Run ./tools/ledger.sh (Wi-Fi) or " +
                            "./tools/phone.sh (USB), then retry."
                } else {
                    binding.ledgerUrlInput.setText(found)
                    load()
                }
            }
        }
    }

    private fun showCached(crop: String) {
        val cached = repository.cached(crop)
        if (cached == null) {
            renderEmpty("No cached token price for ${cropLabel()} yet. Tap Refresh when online.")
        } else {
            render(cached.snapshot, live = false, savedAt = cached.savedAt)
        }
    }

    private fun render(
        snapshot: TradePriceSnapshot,
        live: Boolean,
        savedAt: Long,
        error: String? = null,
    ) {
        val trades = snapshot.trades
        binding.priceChart.setTrades(trades)

        val source = if (live) "Live" else "Cached"
        val stamp = if (live && snapshot.updatedAt > 0) snapshot.updatedAt else savedAt
        val detail = if (error.isNullOrBlank()) "" else " · ${error.take(80)}"
        binding.statusText.text = buildString {
            append(source)
            append(" · ")
            append(timeFormat.format(Date(stamp)))
            append(" · ")
            append(if (trades.isEmpty()) "no trades" else "${trades.size} trade(s)")
            append(" · ")
            append(snapshot.method)
            append(detail)
        }

        val median = snapshot.pricePerKgPaise
        if (median == null) {
            binding.detailText.text =
                "No priced transfers for ${cropLabel()} yet.\n" +
                    "Only priced TRANSFERs count; self-trades are ignored."
            return
        }

        binding.detailText.text = buildString {
            append(cropLabel())
            append(" · ")
            append(PriceFormat.perKg(median))
            append(" median\n")
            append("sample ")
            append(snapshot.sampleSize)
            append(" of the last 20 priced transfers\n")
            append("by grade  ")
            append(
                listOf("A", "B", "C").joinToString("   ") { grade ->
                    val price = snapshot.byGrade[grade]
                    "$grade ${if (price == null) "—" else PriceFormat.perKg(price)}"
                }
            )
            append("\n\n")
            trades.takeLast(20).reversed().forEach { trade ->
                append(timeFormat.format(Date(trade.ts)))
                append("  ")
                append("${trade.tokens} kg".padEnd(8))
                append(" grade ")
                append(trade.grade)
                append("  ")
                append(PriceFormat.perKg(trade.pricePerKgPaise))
                append("\n    ")
                append(shortActor(trade.fromActorId))
                append(" → ")
                append(shortActor(trade.toActorId))
                append("\n")
            }
        }
    }

    private fun renderEmpty(message: String) {
        binding.priceChart.setTrades(emptyList())
        binding.statusText.text = message
        binding.detailText.text = ""
    }

    private fun setLoading(loading: Boolean) {
        binding.refreshButton.isEnabled = !loading
        binding.findLedgerButton.isEnabled = !loading && !discovering
        binding.refreshButton.text = if (loading) "Fetching…" else "Refresh price"
    }

    private fun shortActor(actorId: String): String {
        val role = actorId.substringBefore(':', actorId)
        val rawId = actorId.substringAfter(':', "")
        return if (rawId.length >= 8) "$role:${rawId.take(8)}" else actorId
    }

    private fun cropLabel(): String =
        crops[binding.cropSpinner.selectedItemPosition]
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    private fun adapter(values: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, values).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
