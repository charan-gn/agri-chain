package com.agrichain.batch

import com.agrichain.core.Event
import com.agrichain.core.Signer
import java.util.UUID

class BatchRegistrar(
    private val store: BatchStore,
    private val signer: Signer,
) {

    fun register(crop: String, grade: String, weightKg: Int): Event {
        require(crop.isNotBlank()) { "Crop is required" }
        require(grade in setOf("A", "B", "C")) { "Grade must be A, B or C" }
        require(weightKg > 0) { "Weight must be positive" }

        val event = Event.create(
            batchId = UUID.randomUUID().toString(),
            type = "REGISTER",
            data = linkedMapOf(
                "crop" to crop,
                "grade" to grade,
                "weightKg" to weightKg,
            ),
            signer = signer,
        )
        store.save(event)
        return event
    }
}
