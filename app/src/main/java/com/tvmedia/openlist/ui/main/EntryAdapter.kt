package com.tvmedia.openlist.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvmedia.openlist.R
import com.tvmedia.openlist.data.model.Entry
import com.tvmedia.openlist.databinding.ItemEntryBinding
import java.util.Locale

/** Renders directory entries and keeps D-Pad focus on meaningful rows only. */
class EntryAdapter(
    private val onEntryClick: (Entry) -> Unit,
) : ListAdapter<Entry, EntryAdapter.EntryViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val binding = ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EntryViewHolder(binding) { position ->
            if (position != RecyclerView.NO_POSITION) onEntryClick(getItem(position))
        }
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EntryViewHolder(
        private val binding: ItemEntryBinding,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener { onClick(bindingAdapterPosition) }
        }

        fun bind(entry: Entry) {
            val context = binding.root.context
            binding.nameText.text = entry.name
            binding.sizeText.text = when {
                entry.isDir -> context.getString(R.string.entry_folder)
                entry.size > 0L -> formatSize(entry.size)
                else -> context.getString(R.string.entry_size_unknown)
            }

            val (iconRes, colorRes) = when {
                entry.isDir -> R.drawable.ic_folder to R.color.icon_folder
                entry.isPlayable -> R.drawable.ic_video to R.color.icon_video
                else -> R.drawable.ic_file to R.color.icon_file
            }
            binding.icon.setImageResource(iconRes)
            binding.icon.setColorFilter(ContextCompat.getColor(context, colorRes))

            // Non-video files stay visible but must not intercept the focus path.
            val focusable = entry.isFocusable
            binding.root.isFocusable = focusable
            binding.root.isClickable = focusable
            binding.root.alpha = if (focusable) 1f else 0.45f
        }
    }

    private companion object {

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Entry>() {
            override fun areItemsTheSame(oldItem: Entry, newItem: Entry): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: Entry, newItem: Entry): Boolean =
                oldItem == newItem
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
