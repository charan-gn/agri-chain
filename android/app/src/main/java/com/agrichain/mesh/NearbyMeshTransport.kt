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

class NearbyMeshTransport(context: Context) : MeshTransport {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val endpoints = mutableSetOf<String>()
    private var identity = "agrichain"
    private var onEnvelope: ((MeshEnvelope) -> Unit)? = null
    private var onLog: ((String) -> Unit)? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val envelope = MeshEnvelope.parse(String(bytes, Charsets.UTF_8)) ?: return
            onEnvelope?.invoke(envelope)
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

    override fun start(
        identity: String,
        onEnvelope: (MeshEnvelope) -> Unit,
        onLog: (String) -> Unit,
    ) {
        this.identity = identity
        this.onEnvelope = onEnvelope
        this.onLog = onLog

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
        onLog("mesh started")
    }

    override fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpoints.clear()
        onLog?.invoke("mesh stopped")
    }

    override fun broadcast(envelope: MeshEnvelope): Int {
        if (endpoints.isEmpty()) return 0
        val payload = Payload.fromBytes(envelope.encode().toByteArray(Charsets.UTF_8))
        client.sendPayload(endpoints.toList(), payload)
            .addOnFailureListener { onLog?.invoke("send failed: ${it.message}") }
        return endpoints.size
    }

    companion object {
        private const val SERVICE_ID = "com.agrichain.mesh"
    }
}
