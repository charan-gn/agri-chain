package com.agrichain.net

import android.content.Context

object LedgerSettings {

    private const val PREFS = "agrichain_settings"
    private const val KEY = "ledgerBaseUrl"
    private const val ADMIN_KEY = "adminToken"

    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
    const val USB_BASE_URL = "http://127.0.0.1:8000"
    const val DEFAULT_ADMIN_TOKEN = "dev-admin-token"

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

    /** Admin token for `POST /actors`; local demos use `dev-admin-token`. */
    fun adminToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ADMIN_KEY, DEFAULT_ADMIN_TOKEN)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ADMIN_TOKEN

    fun setAdminToken(context: Context, value: String): String {
        val normalized = value.trim().ifEmpty { DEFAULT_ADMIN_TOKEN }
        require(normalized.none { it.isWhitespace() }) { "Admin token must not contain spaces" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ADMIN_KEY, normalized)
            .apply()
        return normalized
    }
}
