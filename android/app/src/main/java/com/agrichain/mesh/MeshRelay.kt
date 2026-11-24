package com.agrichain.mesh

import android.content.Context
import com.agrichain.core.Event
import com.agrichain.data.SyncStore

interface EventSink {
    /** True when the event is new to the queue; false when it was already seen. */
    fun enqueue(event: Event): Boolean
}

class SyncEventSink(context: Context) : EventSink {
    private val store = SyncStore(context)

    override fun enqueue(event: Event): Boolean = store.enqueue(event)
}

data class MeshResult(val accepted: Int, val duplicates: Int)

class MeshRelay(private val sink: EventSink) {

    fun accept(envelope: MeshEnvelope): MeshResult {
        var accepted = 0
        var duplicates = 0
        for (event in envelope.events) {
            if (sink.enqueue(event)) accepted++ else duplicates++
        }
        return MeshResult(accepted, duplicates)
    }
}

interface MeshTransport {
    fun start(
        identity: String,
        onEnvelope: (MeshEnvelope) -> Unit,
        onLog: (String) -> Unit,
    )

    fun stop()

    /** Returns the number of connected peers the envelope was sent to. */
    fun broadcast(envelope: MeshEnvelope): Int
}
