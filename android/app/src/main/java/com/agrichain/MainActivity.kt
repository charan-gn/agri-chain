package com.agrichain

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityMainBinding
import com.agrichain.ui.BuyerActivity
import com.agrichain.ui.FarmerActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.farmerButton.setOnClickListener {
            startActivity(Intent(this, FarmerActivity::class.java))
        }
        binding.buyerButton.setOnClickListener {
            startActivity(Intent(this, BuyerActivity::class.java))
        }
    }
}
