package com.agrichain.box

import android.content.Context

class BoxSession(context: Context) {

    private val prefs = context.getSharedPreferences("agrichain_box", Context.MODE_PRIVATE)

    fun batchId(): String? = prefs.getString("batchId", null)

    fun ledgerHead(batchId: String): String = prefs.getString("head-$batchId", "").orEmpty()

    fun bind(batchId: String, ledgerHead: String) {
        prefs.edit()
            .putString("batchId", batchId)
            .putString("head-$batchId", ledgerHead)
            .apply()
    }
}
