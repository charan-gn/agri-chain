package com.agrichain.buyer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.databinding.ActivityBuyerHistoryBinding
import com.agrichain.net.LedgerClient
import com.agrichain.net.LedgerSettings
import com.agrichain.ui.UiHelpers
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
    private val timeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        UiHelpers.wireToggle(binding.techHeader, binding.techBody, binding.techChevron)

        batchId = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
        binding.batchId.text = "Batch code: $batchId"
        binding.ledgerUrl.text = "Server: ${LedgerSettings.baseUrl(this)}"
        binding.refreshButton.setOnClickListener { load() }

        if (batchId.isBlank()) {
            showError("No batch code entered")
            return
        }
        load()
    }

    private fun load() {
        val context = applicationContext
        binding.refreshButton.isEnabled = false
        binding.safetyIcon.setImageResource(R.drawable.ill_shield_offline)
        binding.status.text = "Checking crop history…"
        binding.status.setTextColor(getColor(R.color.status_neutral))
        binding.safetySub.text = "Talking to the server"

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
                    offlineNote = "Showing saved offline copy"
                    runCatching { JSONObject(cachedJson) }.getOrNull()
                }
            }

            if (json == null) {
                val message = online.exceptionOrNull()?.let { "Could not load: ${it.message}" }
                    ?: "Could not load: no saved copy"
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
                            binding.safetySub.text =
                                "$offlineNote (${online.exceptionOrNull()?.message.orEmpty()})"
                        }
                    }
                }
                .onFailure {
                    runOnUiThread { showError("Bad server response: ${it.message}") }
                }
        }.start()
    }

    private fun render(history: BatchHistory, tradePricePaise: Int?) {
        binding.refreshButton.isEnabled = true
        binding.ledgerUrl.text = "Server: ${LedgerSettings.baseUrl(this)}"

<<<<<<< HEAD
        when {
            history.tamperCount > 0 || history.mockCount > 0 -> {
                val parts = buildList {
                    if (history.tamperCount > 0) add("${history.tamperCount} tamper")
                    if (history.mockCount > 0) add("${history.mockCount} mock GPS")
                    if (history.heartbeatGaps > 0) add("${history.heartbeatGaps} GPS gap(s)")
                }
                binding.status.text = "Warning: ${parts.joinToString(", ")}"
                binding.status.setTextColor(getColor(R.color.status_alert))
            }
            history.verified && history.heartbeatGaps > 0 -> {
                binding.status.text = "Verified with ${history.heartbeatGaps} GPS gap(s) (>5 min)"
                binding.status.setTextColor(getColor(R.color.status_warn))
            }
            history.verified -> {
                binding.status.text = "Verified chain"
                binding.status.setTextColor(getColor(R.color.status_ok))
            }
            else -> {
                binding.status.text = "Chain not verified"
                binding.status.setTextColor(getColor(R.color.status_alert))
            }
=======
        if (history.verified && history.tamperCount == 0) {
            binding.safetyIcon.setImageResource(R.drawable.ill_shield_ok)
            binding.status.text = "Safe & Verified ✓"
            binding.status.setTextColor(getColor(R.color.status_ok))
            binding.safetySub.text = "No tampering found. All records match."
        } else if (history.tamperCount > 0) {
            binding.safetyIcon.setImageResource(R.drawable.ill_shield_alert)
            binding.status.text = "Tamper Warning (${history.tamperCount})"
            binding.status.setTextColor(getColor(R.color.status_alert))
            binding.safetySub.text = "The box flagged possible tampering. Check below."
        } else {
            binding.safetyIcon.setImageResource(R.drawable.ill_shield_alert)
            binding.status.text = "Could Not Verify"
            binding.status.setTextColor(getColor(R.color.status_alert))
            binding.safetySub.text = "Record signature chain has a mismatch."
>>>>>>> task-T21-laasya
        }

        binding.cropImage.setImageResource(UiHelpers.cropIcon(history.crop))
        binding.details.text =
<<<<<<< HEAD
            "${history.crop} · grade ${history.grade} · ${history.initialKg} kg initial · " +
                "${history.eventCount} events · ${history.heartbeatGaps} gaps · ${history.mockCount} mock"
        binding.owner.text = "Owner: ${BatchHistory.shortActor(history.ownerId)}"
        binding.balances.text = "Balances\n" + history.balances.joinToString("\n") { (actor, kg) ->
            "${BatchHistory.shortActor(actor)}: $kg kg"
        }
=======
            "${UiHelpers.cropTitle(history.crop)} · Grade ${history.grade} · ${history.initialKg} kg"
        binding.owner.text = "Current owner: ${UiHelpers.friendlyActor(history.ownerId)}"
>>>>>>> task-T21-laasya
        binding.tradePrice.text = if (tradePricePaise == null) {
            "Typical price: no recent sales yet"
        } else {
            "Typical price: ${BatchHistory.rupees(tradePricePaise)}/kg"
        }

        renderBalances(history)

        binding.routeView.setPoints(history.route)
        binding.routeEmpty.visibility = if (history.route.size < 2) View.VISIBLE else View.GONE
        binding.routeEmpty.text = when (history.route.size) {
            0 -> "No GPS trip pins recorded yet"
            1 -> "Only 1 GPS pin so far — needs 2 pins to draw a path"
            else -> ""
        }

        renderEvents(history.events)
    }

    private fun renderBalances(history: BatchHistory) {
        binding.balancesList.removeAllViews()
        val maxKg = history.balances.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        if (history.balances.isEmpty()) {
            binding.balancesList.addView(
                TextView(this).apply {
                    text = "No holders recorded yet"
                    setTextColor(getColor(R.color.text_secondary))
                }
            )
            return
        }
        history.balances.forEach { (actor, kg) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(10))
            }
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            labelRow.addView(
                TextView(this).apply {
                    text = UiHelpers.friendlyActor(actor)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            labelRow.addView(
                TextView(this).apply {
                    text = "$kg kg"
                    setTextColor(getColor(R.color.brand_green))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            row.addView(labelRow)
            row.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = maxKg
                    progress = kg.coerceAtLeast(0)
                    progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_green))
                }
            )
            binding.balancesList.addView(row)
        }
    }

    private fun renderEvents(events: List<HistoryEvent>) {
        binding.eventList.removeAllViews()
        val pad = dp(14)
        events.reversed().forEach { event ->
            val isTamper = event.type.equals("TAMPER", ignoreCase = true)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                if (isTamper) {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.alert_red_light))
                }
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            }

            card.addView(
                ImageView(this).apply {
                    setImageResource(UiHelpers.eventIcon(event.type))
                    val size = dp(34)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
                    if (event.type !in setOf("REGISTER", "BARTER")) {
                        setColorFilter(UiHelpers.eventColor(this@BuyerHistoryActivity, event.type))
                    }
                }
            )

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(
                TextView(this).apply {
                    text = UiHelpers.eventTitle(event.type)
                    setTextColor(
                        if (isTamper) getColor(R.color.status_alert) else getColor(R.color.text_primary)
                    )
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            textCol.addView(
                TextView(this).apply {
                    text = "${event.detail}\n${UiHelpers.friendlyActor(event.actorId)} · ${timeFormat.format(Date(event.ts))}"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 13f
                }
            )
            card.addView(textCol)
            binding.eventList.addView(card)
        }
    }

    private fun showError(message: String) {
        binding.refreshButton.isEnabled = true
        binding.safetyIcon.setImageResource(R.drawable.ill_shield_alert)
        binding.status.text = "Could Not Load"
        binding.status.setTextColor(getColor(R.color.status_alert))
        binding.safetySub.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
