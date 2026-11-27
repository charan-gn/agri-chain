package com.agrichain.relay

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrichain.databinding.ActivityBatchV3Binding
import kotlinx.coroutines.launch

/**
 * v3 replacement for the old batch screens: BATCH_REGISTER and BIND are signed
 * client messages, so they work offline and reach the ledger through any gateway.
 */
class BatchV3Activity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchV3Binding
    private var batches: List<LocalBatch> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.cropSpinner.adapter = spinner(V3Dictionaries.crops.map { it.second })
        binding.regionSpinner.adapter = spinner(V3Dictionaries.regions.map { it.second })
        binding.gradeSpinner.adapter = spinner(V3Dictionaries.grades.map { it.second })
        binding.weightInput.setText("500")
        binding.harvestHoursInput.setText("1")
        binding.registerButton.setOnClickListener { registerBatch() }
        binding.bindButton.setOnClickListener { bindBox() }
        binding.qrButton.setOnClickListener { showQr() }
        binding.marketButton.setOnClickListener {
            val batch = selectedBatch()
            val intent = Intent(this, MarketActivity::class.java)
            if (batch != null) intent.putExtra(MarketActivity.EXTRA_BATCH_ID, batch.batchId)
            startActivity(intent)
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun spinner(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

    private fun registerBatch() {
        val weight = binding.weightInput.text.toString().toLongOrNull()
        val hours = binding.harvestHoursInput.text.toString().toLongOrNull() ?: 1
        if (weight == null || weight <= 0) {
            binding.statusText.text = "Enter a weight above zero"
            return
        }
        if (weight > 100000) {
            binding.statusText.text = "A batch is capped at 100,000 kg"
            return
        }
        val crop = V3Dictionaries.crops[binding.cropSpinner.selectedItemPosition].first
        val region = V3Dictionaries.regions[binding.regionSpinner.selectedItemPosition].first
        val grade = V3Dictionaries.grades[binding.gradeSpinner.selectedItemPosition].first
        val now = System.currentTimeMillis() / 1000
        val harvest = (now - hours.coerceIn(0, 24 * 30) * 3600).coerceAtMost(now + 3600)
        val body = V3Body.BatchRegister(
            crop = crop,
            weightKg = weight,
            harvestTs = harvest,
            region = region,
            grade = grade,
        )
        binding.statusText.text = "Signing BATCH_REGISTER..."
        lifecycleScope.launch {
            val originated = runCatching {
                RelayRuntime.originate(V3Type.BATCH_REGISTER, body)
            }.getOrElse { error ->
                binding.statusText.text = "Batch failed: ${error.message}"
                return@launch
            }
            if (originated == null) {
                binding.statusText.text = "Relay is not running. Open Relay and start it."
                return@launch
            }
            BatchStoreV3(this@BatchV3Activity).save(
                LocalBatch(
                    batchId = originated.msgId,
                    crop = crop,
                    weightKg = weight,
                    harvestTs = harvest,
                    region = region,
                    grade = grade,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            binding.batchIdInput.setText(originated.msgId)
            binding.statusText.text =
                "Queued batch ${originated.msgId.take(12)} ... sent to ${originated.links} link(s)"
            render()
        }
    }

    private fun bindBox() {
        val batchId = binding.batchIdInput.text.toString().trim()
        val boxId = binding.boxIdInput.text.toString().trim()
        if (batchId.length != 24 || runCatching { V3Codec.hex(batchId) }.isFailure) {
            binding.statusText.text = "Batch id must be 24 hex characters"
            return
        }
        if (boxId.length != 16 || runCatching { V3Codec.hex(boxId) }.isFailure) {
            binding.statusText.text = "Box id must be 16 hex characters (8 bytes)"
            return
        }
        val body = V3Body.Bind(
            batchId = V3Codec.hex(batchId),
            boxId = V3Codec.hex(boxId),
        )
        binding.statusText.text = "Signing BIND..."
        lifecycleScope.launch {
            val originated = runCatching {
                RelayRuntime.originate(V3Type.BIND, body)
            }.getOrElse { error ->
                binding.statusText.text = "Bind failed: ${error.message}"
                return@launch
            }
            if (originated == null) {
                binding.statusText.text = "Relay is not running. Open Relay and start it."
                return@launch
            }
            BatchStoreV3(this@BatchV3Activity).bind(batchId, boxId)
            binding.statusText.text =
                "Queued BIND ${originated.msgId.take(12)} ... sent to ${originated.links} link(s)"
            render()
        }
    }

    private fun showQr() {
        val batch = selectedBatch()
        if (batch == null) {
            binding.statusText.text = "Register a batch first"
            return
        }
        startActivity(
            Intent(this, BatchQrV3Activity::class.java)
                .putExtra(BatchQrV3Activity.EXTRA_BATCH_ID, batch.batchId),
        )
    }

    private fun selectedBatch(): LocalBatch? {
        val typed = binding.batchIdInput.text.toString().trim()
        return batches.firstOrNull { it.batchId == typed } ?: batches.firstOrNull()
    }

    private fun render() {
        batches = BatchStoreV3(this).all()
        if (batches.isEmpty()) {
            binding.batchList.text = "No batches yet."
            return
        }
        binding.batchList.text = batches.joinToString("\n\n") { batch ->
            buildString {
                append(V3Dictionaries.cropName(batch.crop))
                append(" ... ").append(batch.weightKg).append(" kg")
                append(" ... grade ").append(V3Dictionaries.gradeName(batch.grade))
                append('\n')
                append(batch.batchId)
                append('\n')
                append(V3Dictionaries.regionName(batch.region))
                append(" ... box ").append(batch.boxId?.take(16) ?: "not bound")
            }
        }
        if (binding.batchIdInput.text.isNullOrBlank()) {
            binding.batchIdInput.setText(batches.first().batchId)
        }
        batches.firstOrNull()?.boxId?.let { box ->
            if (binding.boxIdInput.text.isNullOrBlank()) binding.boxIdInput.setText(box)
        }
    }
}
