package com.sametgkte.gktedpi.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sametgkte.gktedpi.databinding.ItemStrategyResultBinding
import com.sametgkte.gktedpi.profiles.StrategyPreset
import java.util.Locale

data class StrategyTestResult(
    val preset: StrategyPreset,
    val successCount: Int,
    val totalTargets: Int,
    val avgLatencyMs: Long,
)

class StrategyTestAdapter(
    private val onApplyClicked: (StrategyPreset) -> Unit,
) : RecyclerView.Adapter<StrategyTestAdapter.Holder>() {

    private var results: List<StrategyTestResult> = emptyList()

    fun submitList(items: List<StrategyTestResult>) {
        results = items
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemStrategyResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemStrategyResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = results[position]
        val isTr = Locale.getDefault().language == "tr"
        holder.binding.presetTitle.text = item.preset.localizedName(isTr)
        holder.binding.presetCmdText.text = item.preset.commandArgs
        holder.binding.presetScore.text = if (item.successCount > 0) {
            if (isTr) {
                "✓ ${item.successCount}/${item.totalTargets} Başarılı • Ort. ${item.avgLatencyMs} ms"
            } else {
                "✓ ${item.successCount}/${item.totalTargets} Passed • Avg ${item.avgLatencyMs} ms"
            }
        } else {
            if (isTr) "✗ Yanıt alınamadı (Engellendi / Zaman aşımı)" else "✗ Failed / Timed out"
        }

        holder.binding.applyResultButton.setOnClickListener {
            onApplyClicked(item.preset)
        }
    }

    override fun getItemCount(): Int = results.size
}
