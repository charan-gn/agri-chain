package com.agrichain.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityBatchQrV3Binding
import com.agrichain.qr.BatchQrCodec
import com.agrichain.qr.QrBitmap

/** QR for a v3 batch id (the BATCH_REGISTER msgId). */
class BatchQrV3Activity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchQrV3Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchQrV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        val batchId = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
        if (batchId.length != 24) {
            Toast.makeText(this, "Batch not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val payload = runCatching { BatchQrCodec.payload(batchId) }.getOrNull()
        if (payload == null) {
            Toast.makeText(this, "Batch id is not valid v3 hex", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val batch = BatchStoreV3(this).find(batchId)
        binding.qrImage.setImageBitmap(QrBitmap.encode(payload))
        binding.batchId.text = "Batch id: $batchId"
        binding.details.text = if (batch == null) {
            "Signed BATCH_REGISTER message"
        } else {
            "${V3Dictionaries.cropName(batch.crop)} ... ${batch.weightKg} kg ... " +
                "grade ${V3Dictionaries.gradeName(batch.grade)} ... " +
                V3Dictionaries.regionName(batch.region)
        }
        binding.payload.text = "QR text: $payload"
        binding.copyButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("AgriChain batch id", batchId))
            Toast.makeText(this, "Batch id copied", Toast.LENGTH_SHORT).show()
        }
        binding.doneButton.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_BATCH_ID = "batchId"
    }
}
