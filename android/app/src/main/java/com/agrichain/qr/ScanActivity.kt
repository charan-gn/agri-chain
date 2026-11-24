package com.agrichain.qr

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.buyer.BuyerHistoryActivity
import com.agrichain.databinding.ActivityScanBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            binding.result.text = "Scan cancelled"
            return@registerForActivityResult
        }
        val batchId = BatchQrCodec.parse(contents)
        if (batchId == null) {
            binding.result.text = "Not an AgriChain batch QR"
        } else {
            binding.result.text = "Valid batch QR\n$batchId"
            startActivity(BuyerHistoryActivity.intent(this, batchId))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.scanButton.setOnClickListener { launchScanner() }
    }

    private fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan an AgriChain batch QR")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }
}
