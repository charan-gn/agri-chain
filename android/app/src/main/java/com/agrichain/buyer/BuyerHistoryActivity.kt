package com.agrichain.buyer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.databinding.ActivityBuyerHistoryBinding
import com.agrichain.net.LedgerClient
import com.agrichain.net.LedgerSettings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BuyerHistoryActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BATCH_ID = "batchId"
        private const val CACHE_PREFS = "agrichain_history_cache"

        fun intent(context: Context, batchId: String): Intent =
            Intent(context, BuyerHistoryActivity::class.java)
                .putExtra(EXTRA_BATCH_ID, batchId)
    }

    private lateinit var binding: ActivityBuyerHistoryBinding
    private lateinit var batchId: String
    private val timeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        batchId = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
        binding.batchId.text = batchId
        binding.ledgerUrl.text = LedgerSettings.baseUrl(this)
        binding.refreshButton.setOnClickListener { load() }

        if (batchId.isBlank()) {
            showError("No batch id")
            return
        }
        load()
    }

    private fun load() {
        val context = applicationContext
        binding.refreshButton.isEnabled = false
        binding.status.text = "Loading from ledger…"
        binding.status.setTextColor(getColor(R.color.status_neutral))

        Thread {
            val online = runCatching { LedgerClient(context).getBatch(batchId) }
            online.onSuccess { cache(batchId, it.toString()) }

            var offlineNote: String? = null
            val json: JSONObject? = if (online.isSuccess) {
                online.getOrNull()
            } else {
                val cachedJson = cached(batchId)
                if (cachedJson == null) {
                    null
                } else {
                    offlineNote = "Offline copy — ${online.exceptionOrNull()?.message}"
                    runCatching { JSONObject(cachedJson) }.getOrNull()
                }
            }

            if (json == null) {
                val message = online.exceptionOrNull()?.let { "Could not load: ${it.message}" }
                    ?: "Could not load: no cached copy"
                runOnUiThread { showError(message) }
                return@Thread
            }

            runCatching { BatchHistory.parse(json) }
                .onSuccess { history ->
                    val price = runCatching {
                        LedgerClient(context)
                            .getTradePrice(history.crop)
                            .optIntOrNull("pricePerKgPaise")
                    }.getOrNull()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        render(history, price)
                        if (offlineNote != null) {
                            binding.status.text = offlineNote
                            binding.status.setTextColor(getColor(R.color.status_warn))
                        }
                    }
                }
                .onFailure {
                    runOnUiThread { showError("Bad ledger response: ${it.message}") }
                }
        }.start()
    }

    private fun render(history: BatchHistory, tradePricePaise: Int?) {
        binding.refreshButton.isEnabled = true
        binding.ledgerUrl.text = LedgerSettings.baseUrl(this)

        if (history.verified && history.tamperCount == 0) {
            binding.status.text = "Verified chain"
            binding.status.setTextColor(getColor(R.color.status_ok))
        } else if (history.tamperCount > 0) {
            binding.status.text = "Tamper detected: ${history.tamperCount} event(s)"
            binding.status.setTextColor(getColor(R.color.status_alert))
        } else {
            binding.status.text = "Chain not verified"
            binding.status.setTextColor(getColor(R.color.status_alert))
        }

        binding.details.text =
            "${history.crop} · grade ${history.grade} · ${history.initialKg} kg initial"
        binding.owner.text = "Owner: ${BatchHistory.shortActor(history.ownerId)}"
        binding.balances.text = "Balances\n" + history.balances.joinToString("\n") { (actor, kg) ->
            "${BatchHistory.shortActor(actor)}: $kg kg"
        }
        binding.tradePrice.text = if (tradePricePaise == null) {
            "Trade price: no trades yet"
        } else {
            "Trade price: ${BatchHistory.rupees(tradePricePaise)}/kg"
        }

        binding.routeView.setPoints(history.route)
        binding.routeEmpty.visibility = if (history.route.size < 2) View.VISIBLE else View.GONE
        binding.routeEmpty.text = when (history.route.size) {
            0 -> "No GPS points yet"
            1 -> "Only 1 GPS point — route needs two"
            else -> ""
        }

        binding.eventList.removeAllViews()
        history.events.reversed().forEach { event ->
            val row = TextView(this).apply {
                text = "${timeFormat.format(Date(event.ts))}  ${event.type}  " +
                    "${BatchHistory.shortActor(event.actorId)}\n${event.detail}"
                setPadding(0, 8, 0, 8)
                textSize = 13f
            }
            binding.eventList.addView(row)
        }
    }

    private fun showError(message: String) {
        binding.refreshButton.isEnabled = true
        binding.status.text = message
        binding.status.setTextColor(getColor(R.color.status_alert))
    }

    private fun cache(batchId: String, json: String) {
        getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(batchId, json)
            .apply()
    }

    private fun cached(batchId: String): String? =
        getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).getString(batchId, null)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)
}
