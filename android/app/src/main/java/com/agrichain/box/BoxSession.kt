package com.agrichain.box

import android.content.Context

class BoxSession(context: Context) {

    private val prefs = context.getSharedPreferences("agrichain_box", Context.MODE_PRIVATE)

    fun batchId(): String? = prefs.getString("batchId", null)

    fun ledgerHead(batchId: String): String = prefs.getString("head-$batchId", "").orEmpty()

    fun ledgerUrl(): String = prefs.getString("ledgerUrl", DEFAULT_LEDGER_URL) ?: DEFAULT_LEDGER_URL

    fun saveLedgerUrl(ledgerUrl: String) {
        prefs.edit().putString("ledgerUrl", ledgerUrl).apply()
    }

    fun bind(batchId: String, ledgerHead: String, ledgerUrl: String) {
        prefs.edit()
            .putString("batchId", batchId)
            .putString("head-$batchId", ledgerHead)
            .putString("ledgerUrl", ledgerUrl)
            .apply()
    }

    companion object {
        const val DEFAULT_LEDGER_URL = "http://10.0.2.2:8000"
    }
}
