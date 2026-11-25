package com.agrichain.batch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityBatchQrBinding
import com.agrichain.qr.BatchQrCodec
import com.agrichain.qr.QrBitmap
import com.agrichain.ui.UiHelpers

class BatchQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchQrBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        val batchId = intent.getStringExtra(EXTRA_BATCH_ID)
        val event = batchId?.let { BatchStore(this).registerEvent(it) }
        if (batchId == null || event == null) {
            Toast.makeText(this, "Harvest not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val payload = BatchQrCodec.payload(batchId)
        val crop = event.data["crop"]?.toString()
        binding.cropImage.setImageResource(UiHelpers.cropIcon(crop))
        binding.qrImage.setImageBitmap(QrBitmap.encode(payload))
        binding.details.text =
            "${UiHelpers.cropTitle(crop)} · ${event.data["weightKg"]} kg · Grade ${event.data["grade"]}"
        binding.batchId.text = "Batch code: $batchId"
        binding.actor.text = "Signed by: ${event.actorId}"
        binding.payload.text = "QR text: $payload"

        binding.copyCodeButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("AgriChain batch code", batchId))
            Toast.makeText(this, "Batch code copied", Toast.LENGTH_SHORT).show()
        }
        binding.doneButton.setOnClickListener { finish() }
        UiHelpers.wireToggle(binding.detailsHeader, binding.detailsBody, binding.detailsChevron)
    }

    companion object {
        private const val EXTRA_BATCH_ID = "batchId"

        fun intent(context: Context, batchId: String): Intent =
            Intent(context, BatchQrActivity::class.java).putExtra(EXTRA_BATCH_ID, batchId)
    }
}
