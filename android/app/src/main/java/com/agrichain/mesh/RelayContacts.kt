package com.agrichain.mesh

import android.content.Context
import org.json.JSONObject

/** A phone the relay has seen, with the display name it announced. */
data class RelayContact(
    val userId: String,
    val name: String,
    val lastSeen: Long,
)

/**
 * Small persistent directory of relay users. Names are self-reported UI labels,
 * not ledger actors and not an identity claim.
 */
class RelayContacts(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "agrichain_relay_users",
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun put(userId: String, name: String, now: Long = System.currentTimeMillis()) {
        val id = userId.trim()
        val label = name.trim().take(40)
        if (id.isEmpty() || label.isEmpty()) return
        val all = load().toMutableMap()
        val previous = all[id]
        all[id] = RelayContact(
            userId = id,
            name = label,
            lastSeen = maxOf(now, previous?.lastSeen ?: 0L),
        )
        save(all)
    }

    @Synchronized
    fun all(): List<RelayContact> =
        load().values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    @Synchronized
    fun nameOf(userId: String): String? = load()[userId]?.name

    @Synchronized
    fun markSeen(userIds: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (userIds.isEmpty()) return
        val all = load().toMutableMap()
        var changed = false
        for (id in userIds) {
            val contact = all[id] ?: continue
            all[id] = contact.copy(lastSeen = maxOf(contact.lastSeen, now))
            changed = true
        }
        if (changed) save(all)
    }

    private fun load(): Map<String, RelayContact> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val result = linkedMapOf<String, RelayContact>()
        runCatching {
            val root = JSONObject(raw)
            root.keys().forEach { id ->
                val row = root.getJSONObject(id)
                result[id] = RelayContact(
                    userId = id,
                    name = row.optString("name"),
                    lastSeen = row.optLong("seen"),
                )
            }
        }
        return result
    }

    private fun save(contacts: Map<String, RelayContact>) {
        val root = JSONObject()
        contacts.forEach { (id, contact) ->
            root.put(id, JSONObject().put("name", contact.name).put("seen", contact.lastSeen))
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private companion object {
        const val KEY = "contacts"
    }
}
