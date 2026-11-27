package com.agrichain.relay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Farmer-side cache of v3 batches. The ledger owns the real batch row. */
data class LocalBatch(
    val batchId: String,
    val crop: Int,
    val weightKg: Long,
    val harvestTs: Long,
    val region: Int,
    val grade: Int,
    val createdAt: Long,
    val boxId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("batchId", batchId)
        .put("crop", crop)
        .put("weightKg", weightKg)
        .put("harvestTs", harvestTs)
        .put("region", region)
        .put("grade", grade)
        .put("createdAt", createdAt)
        .put("boxId", boxId ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): LocalBatch = LocalBatch(
            batchId = json.getString("batchId"),
            crop = json.optInt("crop", 0),
            weightKg = json.optLong("weightKg", 0),
            harvestTs = json.optLong("harvestTs", 0),
            region = json.optInt("region", 0),
            grade = json.optInt("grade", 0),
            createdAt = json.optLong("createdAt", 0),
            boxId = json.optString("boxId", "").takeIf { it.isNotEmpty() },
        )
    }
}

class BatchStoreV3(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<LocalBatch> {
        val raw = prefs.getString(KEY_BATCHES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { LocalBatch.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun find(batchId: String): LocalBatch? = all().firstOrNull { it.batchId == batchId }

    fun save(batch: LocalBatch) {
        val batches = all().toMutableList()
        val index = batches.indexOfFirst { it.batchId == batch.batchId }
        if (index >= 0) batches[index] = batch else batches.add(0, batch)
        persist(batches.take(100))
    }

    fun bind(batchId: String, boxId: String?) {
        val batches = all().toMutableList()
        val index = batches.indexOfFirst { it.batchId == batchId }
        if (index >= 0) {
            batches[index] = batches[index].copy(boxId = boxId)
            persist(batches)
        }
    }

    private fun persist(batches: List<LocalBatch>) {
        val array = JSONArray()
        batches.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_BATCHES, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "agrichain_v3_batches"
        private const val KEY_BATCHES = "batches"
    }
}
