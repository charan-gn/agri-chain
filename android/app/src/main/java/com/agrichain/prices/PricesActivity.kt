package com.agrichain.prices

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.databinding.ActivityPricesBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.ui.UiHelpers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PricesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPricesBinding
    private lateinit var cache: PriceCache
    private lateinit var repository: PriceRepository

    private val timeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.US)
    private lateinit var cropViews: List<Pair<String, View>>
    private var selectedCrop: String = "wheat"

    private var requestSeq = 0
    private var discovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPricesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        UiHelpers.wireToggle(binding.serverHeader, binding.serverBody, binding.serverChevron)

        cache = PriceCache(this)
        repository = PriceRepository(cache)
        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))

        cropViews = listOf(
            "wheat" to binding.cropWheat,
            "rice" to binding.cropRice,
            "maize" to binding.cropMaize,
            "chilli" to binding.cropChilli,
            "cotton" to binding.cropCotton,
        )
        cropViews.forEach { (crop, view) ->
            view.setOnClickListener {
                if (selectedCrop != crop) {
                    highlightCrop(crop)
                    load()
                }
            }
        }

        val initial = cache.crop().trim().lowercase(Locale.US)
            .takeIf { c -> cropViews.any { it.first == c } } ?: "wheat"
        highlightCrop(initial)

        binding.refreshButton.setOnClickListener { load() }
        binding.findLedgerButton.setOnClickListener { discoverLedger() }
        load()
    }

    override fun onDestroy() {
        requestSeq += 1
        super.onDestroy()
    }

    private fun highlightCrop(crop: String) {
        selectedCrop = crop
        cropViews.forEach { (key, view) ->
            view.setBackgroundResource(
                if (key == crop) R.drawable.bg_card_selected else R.drawable.bg_card
            )
        }
        binding.heroCropImage.setImageResource(UiHelpers.cropIcon(crop))
        binding.heroCropName.text = UiHelpers.cropTitle(crop)
    }

    private fun load() {
        val crop = selectedCrop
        val raw = binding.ledgerUrlInput.text.toString().trim()
        if (raw.isEmpty()) {
            renderEmpty("Enter the server address or tap Auto-detect.")
            return
        }
        val url = try {
            LedgerSettings.setBaseUrl(this, PriceUrls.normalizeBaseUrl(raw))
        } catch (error: IllegalArgumentException) {
            renderEmpty(error.message ?: "Enter the server address")
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
                            "Offline and no saved price for ${UiHelpers.cropTitle(crop)}.\n" +
                                (error.message ?: "Network error")
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
        binding.statusText.text = "Looking for server on USB and Wi-Fi…"
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
                        "No server found. Run ./tools/ledger.sh (Wi-Fi) or ./tools/phone.sh (USB)."
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
            renderEmpty("No saved price for ${UiHelpers.cropTitle(crop)} yet. Tap Update Prices when online.")
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

        val source = if (live) "Live" else "Saved offline"
        val stamp = if (live && snapshot.updatedAt > 0) snapshot.updatedAt else savedAt
        val detail = if (error.isNullOrBlank()) "" else " · ${error.take(60)}"
        binding.statusText.text =
            "$source · ${timeFormat.format(Date(stamp))} · ${snapshot.sampleSize} sale(s)$detail"

        val median = snapshot.pricePerKgPaise
        binding.heroPrice.text = if (median == null) "No sales yet" else PriceFormat.perKg(median)

        binding.gradeAPrice.text =
            "A: ${snapshot.byGrade["A"]?.let { PriceFormat.perKg(it) } ?: "—"}"
        binding.gradeBPrice.text =
            "B: ${snapshot.byGrade["B"]?.let { PriceFormat.perKg(it) } ?: "—"}"
        binding.gradeCPrice.text =
            "C: ${snapshot.byGrade["C"]?.let { PriceFormat.perKg(it) } ?: "—"}"

        if (median == null) {
            binding.detailText.text =
                "No priced sales for ${UiHelpers.cropTitle(selectedCrop)} yet."
            return
        }

        binding.detailText.text = buildString {
            trades.takeLast(20).reversed().forEach { trade ->
                append(timeFormat.format(Date(trade.ts)))
                append("  ")
                append("${trade.tokens} kg".padEnd(8))
                append(" Grade ")
                append(trade.grade)
                append("  ")
                append(PriceFormat.perKg(trade.pricePerKgPaise))
                append("\n    ")
                append(UiHelpers.friendlyActor(trade.fromActorId))
                append(" → ")
                append(UiHelpers.friendlyActor(trade.toActorId))
                append("\n")
            }
        }
    }

    private fun renderEmpty(message: String) {
        binding.priceChart.setTrades(emptyList())
        binding.heroPrice.text = "—"
        binding.gradeAPrice.text = "A: —"
        binding.gradeBPrice.text = "B: —"
        binding.gradeCPrice.text = "C: —"
        binding.statusText.text = message
        binding.detailText.text = ""
    }

    private fun setLoading(loading: Boolean) {
        binding.refreshButton.isEnabled = !loading
        binding.findLedgerButton.isEnabled = !loading && !discovering
        binding.refreshButton.text = if (loading) "Checking…" else "Update Prices"
    }
}
