package com.agrichain.box

import android.content.Context
import com.agrichain.core.Chains

class BoxSession(context: Context) {

    private val prefs = context.getSharedPreferences("agrichain_box", Context.MODE_PRIVATE)

    fun batchId(): String? = prefs.getString("batchId", null)

    /** Server head for this box's own chain, so a reinstall continues the chain. */
    fun ledgerHead(batchId: String, stream: String): String =
        prefs.getString("head-$batchId-$stream", "").orEmpty()

    fun ledgerMainHead(batchId: String): String = ledgerHead(batchId, Chains.MAIN)

    fun ledgerTraceHead(batchId: String): String = ledgerHead(batchId, Chains.TRACE)

    fun bind(batchId: String, mainHead: String, traceHead: String) {
        prefs.edit()
            .putString("batchId", batchId)
            .putString("head-$batchId-${Chains.MAIN}", mainHead)
            .putString("head-$batchId-${Chains.TRACE}", traceHead)
            .apply()
    }
}
