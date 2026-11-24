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
 * Internet transport. Uploads whatever is in the outbox to the ledger; the
 * ledger treats a repeated eventId as a success, so retries are safe.
 */
class HttpLedgerGateway(context: Context) : LedgerGateway {

    private val app = context.applicationContext

    override fun available(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun upload(events: List<Event>): UploadResult {
        val api = LedgerApi(LedgerSettings.baseUrl(app))
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var error: String? = null

        for ((batchId, group) in events.groupBy { it.batchId }) {
            var server = try {
                api.fetchBatch(batchId)
            } catch (failure: IOException) {
                error = failure.message
                rejected += group.map { it.eventId }
                continue
            }
            var head = server?.head.orEmpty()
            var ids = server?.eventIds ?: emptySet()

            group.filter { it.eventId in ids }.forEach { accepted += it.eventId }
            for (event in SyncPlanner.order(group, ids, head)) {
                if (event.eventId in ids) {
                    accepted += event.eventId
                    continue
                }
                if (!canAppend(event, head)) {
                    val refreshed = try {
                        api.fetchBatch(batchId)
                    } catch (failure: IOException) {
                        null
                    }
                    if (refreshed != null) {
                        head = refreshed.head
                        ids = refreshed.eventIds
                    }
                    if (event.eventId in ids) {
                        accepted += event.eventId
                        continue
                    }
                    if (!canAppend(event, head)) {
                        rejected += event.eventId
                        error = "prev_mismatch"
                        continue
                    }
                }

                try {
                    head = api.postEvent(event)
                    ids = ids + event.eventId
                    accepted += event.eventId
                } catch (failure: LedgerException) {
                    rejected += event.eventId
                    error = "${failure.code}: ${failure.detail}"
                    if (failure.status == 409 && failure.head != null) head = failure.head
                    if (failure.status == 422 || failure.status == 400) {
                        // A bad signature/hash/actor will not fix itself.
                        continue
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

    private fun canAppend(event: Event, head: String): Boolean {
        if (head.isEmpty()) {
            return event.prevHash.isEmpty() &&
                (event.type == "REGISTER" || (event.batchId == "market" && event.type == "PRICE"))
        }
        return event.type != "REGISTER" && event.prevHash == head
    }
}
