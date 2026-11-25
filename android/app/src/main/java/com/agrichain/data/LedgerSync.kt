package com.agrichain.data

import android.content.Context
import com.agrichain.batch.BatchStore
import com.agrichain.core.ActorIdentity
import java.io.IOException

data class SyncReport(val uploaded: Int, val failed: Int, val pending: Int)

class LedgerSync(private val context: Context, private val api: LedgerApi) {

    private val store = SyncStore(context)

    fun registerActors(log: (String) -> Unit) {
        val adminToken = com.agrichain.net.LedgerSettings.adminToken(context)
        for (role in listOf("farmer", "buyer", "box")) {
            val identity = ActorIdentity.loadOrCreate(context, role)
            api.registerActor(
                identity.actorId,
                nameFor(role),
                role,
                identity.publicKeySpkiBase64,
                adminToken = adminToken,
            )
            log("$role ${identity.actorId.take(13)}… ok")
        }
    }

    fun importLocal(): Int {
        BatchStore(context).all().forEach { store.enqueue(it) }
        return store.pendingCount()
    }

    fun sync(log: (String) -> Unit): SyncReport {
        val queued = importLocal()
        log("$queued event(s) pending")
        var uploaded = 0
        var failed = 0

        val pending = store.pending()
        val batches = pending.groupBy { it.batchId }.entries.sortedBy { (_, events) ->
            when {
                events.any { it.type == "REGISTER" } -> 0
                events.any { it.type == "BIND" } -> 1
                else -> 2
            }
        }

        for ((batchId, events) in batches) {
            var server = api.fetchBatch(batchId)
            var ids = server?.eventIds ?: emptySet()
            var chainHeads = server?.chainHeads?.toMutableMap() ?: mutableMapOf()
            var attempts = 0
            var done = false

            while (!done && attempts < 4) {
                val plan = SyncPlanner.planV2(events, ids, chainHeads)
                if (plan.orphans.isNotEmpty()) {
                    log(
                        "${batchId.take(8)}: ${plan.orphans.size} event(s) do not extend " +
                            "their chain — re-sign them (new eventId/ts/prevHash)"
                    )
                }
                if (plan.ordered.isEmpty()) {
                    done = true
                    break
                }
                attempts++
                var retry = false
                for (event in plan.ordered) {
                    val chainKey = event.actorId to SyncPlanner.streamFor(event.type)
                    try {
                        val newHead = api.postEvent(event)
                        chainHeads[chainKey] = newHead
                        ids = ids + event.eventId
                        store.markSynced(listOf(event.eventId))
                        uploaded++
                    } catch (error: LedgerException) {
                        if (error.status == 409 && error.code in RETRYABLE_409) {
                            // unbound_box waits for the BIND relay; batch_unknown waits
                            // for REGISTER. Both are retryable, never dropped.
                            retry = true
                            break
                        } else {
                            failed++
                            log("${event.type} ${event.eventId.take(8)} rejected: ${error.code}")
                            if (error.status in setOf(400, 403, 422)) continue
                            done = true
                            break
                        }
                    } catch (error: IOException) {
                        failed++
                        log("network: ${error.message}")
                        done = true
                        break
                    }
                }
                if (done) break
                if (retry) {
                    Thread.sleep(800)
                    val refreshed = api.fetchBatch(batchId)
                    ids = refreshed?.eventIds ?: ids
                    chainHeads = refreshed?.chainHeads?.toMutableMap() ?: chainHeads
                    log("${batchId.take(8)}: retryable 409, refreshing heads")
                } else {
                    done = true
                }
            }
        }

        return SyncReport(uploaded, failed, store.pendingCount())
    }

    fun pendingCount(): Int = store.pendingCount()

    private fun nameFor(role: String): String = when (role) {
        "farmer" -> "Farmer"
        "buyer" -> "Buyer"
        else -> "Transport box"
    }

    private companion object {
        val RETRYABLE_409 = setOf("prev_mismatch", "unbound_box", "batch_unknown")
    }
}
