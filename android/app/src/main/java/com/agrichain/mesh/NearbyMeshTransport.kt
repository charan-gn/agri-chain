package com.agrichain.mesh

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Nearby Connections (Bluetooth / WiFi Direct) link. The relay forwards the same
 * envelope it uses for SMS, so peers do not care which radio delivered it.
 */
class NearbyMeshTransport(
    context: Context,
    private val identity: String,
) : Transport {

    override val id: String = "nearby"
    override val maxPayload: Int = 32_000

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val endpoints = mutableSetOf<String>()
    private var running = false
    private var onEnvelope: ((MeshEnvelope, Transport) -> Unit)? = null
    private var onLog: ((String) -> Unit)? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val envelope = MeshEnvelope.parse(String(bytes, Charsets.UTF_8)) ?: return
            onEnvelope?.invoke(envelope, this@NearbyMeshTransport)
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate,
        ) = Unit
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
            onLog?.invoke("connecting to ${info.endpointName}")
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                endpoints += endpointId
                onLog?.invoke("connected (${endpoints.size} peer(s))")
            } else {
                onLog?.invoke("connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpoints -= endpointId
            onLog?.invoke("disconnected (${endpoints.size} peer(s))")
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            client.requestConnection(identity, endpointId, connectionCallback)
                .addOnFailureListener { onLog?.invoke("request failed: ${it.message}") }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    override fun available(): Boolean = running

    override fun start(
        onReceive: (MeshEnvelope, Transport) -> Unit,
        onLog: (String) -> Unit,
    ) {
        if (running) return
        onEnvelope = onReceive
        this.onLog = onLog
        running = true

        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(
            identity,
            SERVICE_ID,
            connectionCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        )
            .addOnSuccessListener { onLog("advertising as $identity") }
            .addOnFailureListener { onLog("advertise failed: ${it.message}") }
        client.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { onLog("discovery failed: ${it.message}") }
        onLog("nearby started")
    }

    override fun stop() {
        if (!running) return
        running = false
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        synchronized(endpoints) { endpoints.clear() }
        onLog?.invoke("nearby stopped")
    }

    override fun send(envelope: MeshEnvelope): Int {
        if (!running) return 0
        val peers = synchronized(endpoints) { endpoints.toList() }
        if (peers.isEmpty()) return 0
        return try {
            val payload = Payload.fromBytes(envelope.encode().toByteArray(Charsets.UTF_8))
            client.sendPayload(peers, payload)
                .addOnFailureListener { onLog?.invoke("send failed: ${it.message}") }
            peers.size
        } catch (error: Exception) {
            onLog?.invoke("send failed: ${error.message}")
            0
        }
    }

    companion object {
        private const val SERVICE_ID = "com.agrichain.mesh"
    }
}
