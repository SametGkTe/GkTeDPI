package com.sametgkte.gktedpi.adapters

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sametgkte.gktedpi.databinding.ItemAppSelectBinding

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
)

class AppListAdapter(
    private val selectedPackages: MutableSet<String>,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private var visibleItems: List<AppEntry> = emptyList()

    fun submitList(items: List<AppEntry>) {
        visibleItems = items
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemAppSelectBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppSelectBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = visibleItems[position]
        holder.binding.appLabel.text = item.label
        holder.binding.appPackage.text = item.packageName
        holder.binding.appIcon.setImageDrawable(item.icon)
        holder.binding.appCheckBox.isChecked = item.packageName in selectedPackages

        holder.binding.root.setOnClickListener {
            if (item.packageName in selectedPackages) {
                selectedPackages.remove(item.packageName)
            } else {
                selectedPackages.add(item.packageName)
            }
            notifyItemChanged(holder.bindingAdapterPosition)
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = visibleItems.size
}
