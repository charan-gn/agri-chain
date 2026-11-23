package com.agrichain.batch

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.core.ActorIdentity
import com.agrichain.databinding.ActivityRegisterBatchBinding

class RegisterBatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBatchBinding
    private val crops = listOf("wheat", "rice", "maize", "cotton", "chilli")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cropSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, crops)
        binding.gradeGroup.check(binding.gradeA.id)
        binding.registerButton.setOnClickListener { register() }
    }

    private fun register() {
        val weightKg = binding.weightInput.text?.toString()?.trim()?.toIntOrNull()
        if (weightKg == null || weightKg !in 1..100_000) {
            binding.weightLayout.error = "Enter weight in kg (1-100000)"
            return
        }
        binding.weightLayout.error = null

        val crop = binding.cropSpinner.selectedItem?.toString() ?: return
        val grade = when (binding.gradeGroup.checkedRadioButtonId) {
            binding.gradeB.id -> "B"
            binding.gradeC.id -> "C"
            else -> "A"
        }

        try {
            val signer = ActorIdentity.loadOrCreate(this, "farmer")
            val event = BatchRegistrar(BatchStore(this), signer).register(crop, grade, weightKg)
            startActivity(BatchQrActivity.intent(this, event.batchId))
            finish()
        } catch (error: Exception) {
            Toast.makeText(this, "Registration failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }
}
