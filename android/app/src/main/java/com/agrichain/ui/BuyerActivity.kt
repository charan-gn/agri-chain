package com.agrichain.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.buyer.BuyerHistoryActivity
import com.agrichain.databinding.ActivityBuyerBinding
import com.agrichain.net.LedgerSettings
import com.agrichain.qr.ScanActivity

class BuyerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityBuyerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))

        binding.scanButton.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        binding.openHistoryButton.setOnClickListener {
            val batchId = binding.batchIdInput.text.toString().trim()
            if (batchId.isEmpty()) {
                binding.urlStatus.text = "Enter a batch id first"
            } else {
                startActivity(BuyerHistoryActivity.intent(this, batchId))
            }
        }
        binding.saveUrlButton.setOnClickListener {
            runCatching { LedgerSettings.setBaseUrl(this, binding.ledgerUrlInput.text.toString()) }
                .onSuccess {
                    binding.ledgerUrlInput.setText(it)
                    binding.urlStatus.text = "Saved ledger server"
                }
                .onFailure { binding.urlStatus.text = it.message }
        }
    }
}
