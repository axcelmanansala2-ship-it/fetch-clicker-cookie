package com.smartsystem.autoclicker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smartsystem.autoclicker.databinding.ActivityTargetSetupBinding
import com.smartsystem.autoclicker.databinding.ItemTargetBinding
import com.smartsystem.autoclicker.models.DetectionTarget
import com.smartsystem.autoclicker.models.TargetRepository

/**
 * Lets the user manage their list of detection targets.
 * Each target is a text string that ML Kit OCR will look for on screen.
 */
class TargetSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTargetSetupBinding
    private lateinit var repo: TargetRepository
    private lateinit var adapter: TargetAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTargetSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repo = TargetRepository(this)
        adapter = TargetAdapter(repo.getAll()) { id ->
            repo.remove(id)
            adapter.targets = repo.getAll()
            adapter.notifyDataSetChanged()
            updateEmptyState()
            Toast.makeText(this, getString(R.string.msg_target_deleted), Toast.LENGTH_SHORT).show()
        }

        binding.recyclerTargets.layoutManager = LinearLayoutManager(this)
        binding.recyclerTargets.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.recyclerTargets.adapter = adapter

        binding.btnAddTarget.setOnClickListener { addTarget() }
        updateEmptyState()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun addTarget() {
        val label = binding.etLabel.text?.toString()?.trim() ?: ""
        val text = binding.etTextQuery.text?.toString()?.trim() ?: ""

        if (label.isEmpty() || text.isEmpty()) {
            Toast.makeText(this, "Label and text are required", Toast.LENGTH_SHORT).show()
            return
        }

        val target = DetectionTarget(
            label = label,
            textQuery = text,
            delayAfterMs = binding.etDelay.text?.toString()?.toLongOrNull() ?: 500L
        )
        repo.add(target)
        adapter.targets = repo.getAll()
        adapter.notifyDataSetChanged()
        updateEmptyState()

        binding.etLabel.text?.clear()
        binding.etTextQuery.text?.clear()
        binding.etDelay.text?.clear()

        Toast.makeText(this, getString(R.string.msg_target_added), Toast.LENGTH_SHORT).show()
    }

    private fun updateEmptyState() {
        binding.tvNoTargets.visibility =
            if (adapter.targets.isEmpty()) View.VISIBLE else View.GONE
    }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class TargetAdapter(
    var targets: List<DetectionTarget>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<TargetAdapter.VH>() {

    inner class VH(val binding: ItemTargetBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTargetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val target = targets[position]
        with(holder.binding) {
            tvLabel.text = target.label
            tvText.text = "\"${target.textQuery}\""
            tvDelay.text = "${target.delayAfterMs} ms delay"
            btnDelete.setOnClickListener { onDelete(target.id) }
        }
    }

    override fun getItemCount() = targets.size
}
