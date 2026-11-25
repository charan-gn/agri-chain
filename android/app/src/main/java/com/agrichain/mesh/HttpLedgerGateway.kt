package com.agrichain.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.agrichain.core.Event
import com.agrichain.data.LedgerApi
import com.agrichain.data.LedgerException
import com.agrichain.data.SyncPlanner
import com.agrichain.net.LedgerSettings
import java.io.IOException

/**
 * Internet transport. Uploads queued events to the v2 ledger per (actorId, stream) chain.
 */
class HttpLedgerGateway(context: Context) : LedgerGateway {

    private val app = context.applicationContext

    override fun available(): Boolean {
        val base = LedgerSettings.baseUrl(app)
        if (base.startsWith("http://127.0.0.1") || base.startsWith("http://localhost")) {
            return true
        }
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    override fun upload(events: List<Event>): UploadResult {
        val api = LedgerApi(LedgerSettings.baseUrl(app))
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var error: String? = null

        val batchGroups = events.groupBy { it.batchId }.entries.sortedBy { (_, group) ->
            when {
                group.any { it.type == "REGISTER" } -> 0
                group.any { it.type == "BIND" } -> 1
                else -> 2
            }
        }

        for ((batchId, group) in batchGroups) {
            val server = try {
                api.fetchBatch(batchId)
            } catch (failure: IOException) {
                error = failure.message
                rejected += group.map { it.eventId }
                continue
            }
            val chainHeads = server?.chainHeads?.toMutableMap() ?: mutableMapOf()
            var ids = server?.eventIds ?: emptySet()

            group.filter { it.eventId in ids }.forEach { accepted += it.eventId }
            for (event in SyncPlanner.orderV2(group, ids, chainHeads)) {
                if (event.eventId in ids) {
                    accepted += event.eventId
                    continue
                }
                val chainKey = event.actorId to SyncPlanner.streamFor(event.type)
                var head = chainHeads[chainKey].orEmpty()
                if (event.prevHash != head) {
                    val refreshed = runCatching { api.fetchBatch(batchId) }.getOrNull()
                    if (refreshed != null) {
                        ids = refreshed.eventIds
                        chainHeads.clear()
                        chainHeads.putAll(refreshed.chainHeads)
                        head = chainHeads[chainKey].orEmpty()
                    }
                    if (event.eventId in ids) {
                        accepted += event.eventId
                        continue
                    }
                    if (event.prevHash != head) {
                        rejected += event.eventId
                        error = "prev_mismatch"
                        continue
                    }
                }

                try {
                    val newHead = api.postEvent(event)
                    chainHeads[chainKey] = newHead
                    ids = ids + event.eventId
                    accepted += event.eventId
                } catch (failure: LedgerException) {
                    rejected += event.eventId
                    error = "${failure.code}: ${failure.detail}"
                    if (failure.status == 409 && failure.head != null) {
                        chainHeads[chainKey] = failure.head
                    }
                } catch (failure: IOException) {
                    rejected += event.eventId
                    error = failure.message
                    break
                }
            }
        }
        return UploadResult(accepted, rejected, error)
    }
}
