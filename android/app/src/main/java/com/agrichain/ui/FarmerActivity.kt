package com.agrichain.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.batch.BatchQrActivity
import com.agrichain.batch.BatchStore
import com.agrichain.batch.RegisterBatchActivity
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Event
import com.agrichain.databinding.ActivityFarmerBinding

class FarmerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFarmerBinding
    private lateinit var adapter: ArrayAdapter<String>
    private var batches: List<Event> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFarmerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.actorId.text = ActorIdentity.loadOrCreate(this, "farmer").actorId
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.batchList.adapter = adapter
        binding.batchList.emptyView = binding.batchEmpty
        binding.batchList.setOnItemClickListener { _, _, position, _ ->
            startActivity(BatchQrActivity.intent(this, batches[position].batchId))
        }
        binding.registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterBatchActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        batches = BatchStore(this).all()
            .filter { it.type == "REGISTER" }
            .sortedByDescending { it.ts }
        adapter.clear()
        adapter.addAll(
            batches.map {
                "${it.data["crop"]} | ${it.data["weightKg"]} kg | grade ${it.data["grade"]}"
            }
        )
    }
}
