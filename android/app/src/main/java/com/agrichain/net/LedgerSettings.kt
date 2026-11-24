package com.agrichain.net

import android.content.Context

object LedgerSettings {

    private const val PREFS = "agrichain_settings"
    private const val KEY = "ledgerBaseUrl"

    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"

    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT_BASE_URL)
            ?.trimEnd('/')
            ?: DEFAULT_BASE_URL

    fun setBaseUrl(context: Context, value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "URL must start with http:// or https://"
        }
        require(normalized.length > "https://".length) { "Enter the ledger host" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, normalized)
            .apply()
        return normalized
    }
}
