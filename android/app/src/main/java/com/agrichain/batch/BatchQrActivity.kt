package com.agrichain.batch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityBatchQrBinding
import com.agrichain.qr.BatchQrCodec
import com.agrichain.qr.QrBitmap

class BatchQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchQrBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val batchId = intent.getStringExtra(EXTRA_BATCH_ID)
        val event = batchId?.let { BatchStore(this).registerEvent(it) }
        if (batchId == null || event == null) {
            Toast.makeText(this, "Batch not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val payload = BatchQrCodec.payload(batchId)
        binding.qrImage.setImageBitmap(QrBitmap.encode(payload))
        binding.details.text =
            "${event.data["crop"]} | grade ${event.data["grade"]} | ${event.data["weightKg"]} kg"
        binding.batchId.text = batchId
        binding.actor.text = "Signed by ${event.actorId}"
        binding.payload.text = payload
        binding.doneButton.setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_BATCH_ID = "batchId"

        fun intent(context: Context, batchId: String): Intent =
            Intent(context, BatchQrActivity::class.java).putExtra(EXTRA_BATCH_ID, batchId)
    }
}
