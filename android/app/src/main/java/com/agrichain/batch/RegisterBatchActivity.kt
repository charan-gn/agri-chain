package com.agrichain.batch

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.core.ActorIdentity
import com.agrichain.databinding.ActivityRegisterBatchBinding

class RegisterBatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBatchBinding
    private var selectedCrop: String = "wheat"
    private var selectedGrade: String = "A"

    private lateinit var cropCards: List<Pair<String, View>>
    private lateinit var gradeCards: List<Pair<String, View>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        cropCards = listOf(
            "wheat" to binding.cropWheat,
            "rice" to binding.cropRice,
            "maize" to binding.cropMaize,
            "cotton" to binding.cropCotton,
            "chilli" to binding.cropChilli,
        )
        cropCards.forEach { (crop, view) ->
            view.setOnClickListener { selectCrop(crop) }
        }
        selectCrop("wheat")

        gradeCards = listOf(
            "A" to binding.gradeCardA,
            "B" to binding.gradeCardB,
            "C" to binding.gradeCardC,
        )
        gradeCards.forEach { (grade, view) ->
            view.setOnClickListener { selectGrade(grade) }
        }
        selectGrade("A")

        listOf(
            25 to binding.weight25,
            50 to binding.weight50,
            100 to binding.weight100,
            500 to binding.weight500,
        ).forEach { (kg, button) ->
            button.setOnClickListener {
                binding.weightInput.setText(kg.toString())
                binding.weightLayout.error = null
            }
        }

        binding.registerButton.setOnClickListener { register() }
    }

    private fun selectCrop(crop: String) {
        selectedCrop = crop
        cropCards.forEach { (key, view) ->
            view.setBackgroundResource(
                if (key == crop) R.drawable.bg_card_selected else R.drawable.bg_card
            )
        }
    }

    private fun selectGrade(grade: String) {
        selectedGrade = grade
        gradeCards.forEach { (key, view) ->
            view.setBackgroundResource(
                if (key == grade) R.drawable.bg_card_selected else R.drawable.bg_card
            )
        }
    }

    private fun register() {
        val weightKg = binding.weightInput.text?.toString()?.trim()?.toIntOrNull()
        if (weightKg == null || weightKg !in 1..100_000) {
            binding.weightLayout.error = "Enter weight in kg (1–100000)"
            return
        }
        binding.weightLayout.error = null

        try {
            val signer = ActorIdentity.loadOrCreate(this, "farmer")
            val event = BatchRegistrar(BatchStore(this), signer)
                .register(selectedCrop, selectedGrade, weightKg)
            startActivity(BatchQrActivity.intent(this, event.batchId))
            finish()
        } catch (error: Exception) {
            Toast.makeText(this, "Registration failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }
}
