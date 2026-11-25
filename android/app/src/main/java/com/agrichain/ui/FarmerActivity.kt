package com.agrichain.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.barter.BarterActivity
import com.agrichain.batch.BatchQrActivity
import com.agrichain.batch.BatchStore
import com.agrichain.batch.RegisterBatchActivity
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Event
import com.agrichain.databinding.ActivityFarmerBinding

class FarmerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFarmerBinding
    private lateinit var batchAdapter: HarvestAdapter
    private var batches: List<Event> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFarmerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.actorId.text = ActorIdentity.loadOrCreate(this, "farmer").actorId

        batchAdapter = HarvestAdapter()
        binding.batchList.adapter = batchAdapter
        binding.batchList.emptyView = binding.batchEmpty
        binding.batchList.setOnItemClickListener { _, _, position, _ ->
            startActivity(BatchQrActivity.intent(this, batches[position].batchId))
        }
        binding.registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterBatchActivity::class.java))
        }
        binding.barterButton.setOnClickListener {
            startActivity(Intent(this, BarterActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        batches = BatchStore(this).all()
            .filter { it.type == "REGISTER" }
            .sortedByDescending { it.ts }
        batchAdapter.notifyDataSetChanged()
    }

    private inner class HarvestAdapter : BaseAdapter() {
        override fun getCount(): Int = batches.size
        override fun getItem(position: Int): Event = batches[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_farmer_batch, parent, false)
            val event = batches[position]
            val crop = event.data["crop"]?.toString()
            val weight = event.data["weightKg"]?.toString() ?: "0"
            val grade = event.data["grade"]?.toString() ?: "A"

            view.findViewById<ImageView>(R.id.cropImage)
                .setImageResource(UiHelpers.cropIcon(crop))
            view.findViewById<TextView>(R.id.cropTitle).text =
                "${UiHelpers.cropTitle(crop)} · $weight kg"
            view.findViewById<TextView>(R.id.cropSubtitle).text =
                "Grade $grade · Tap to show QR"
            return view
        }
    }
}
