package com.agrichain.mesh

import android.content.Context

object SmsSettings {

    private const val PREFS = "agrichain_settings"
    private const val KEY = "smsGatewayNumber"

    fun gatewayNumber(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Saves a normalized number, or clears it when [value] is blank. */
    fun setGatewayNumber(context: Context, value: String): String? {
        val normalized = value.trim().replace(" ", "").replace("-", "")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (normalized.isEmpty()) {
            prefs.edit().remove(KEY).apply()
            return null
        }
        require(Regex("^\\+?[0-9]{6,15}$").matches(normalized)) {
            "Use digits with optional leading +, 6-15 digits"
        }
        prefs.edit().putString(KEY, normalized).apply()
        return normalized
    }
}
