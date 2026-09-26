package com.keyguard.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import com.keyguard.app.databinding.ItemRowBinding

/**
 * Navigable rows, the shared building block of both apps' hub screens.
 *
 * A hub shows a summary per destination and puts the detail one tap away, which is what the
 * old tabbed layout could not do: every section held everything it owned, so every tab was a
 * long scroll. Rows are built here rather than inflated ad hoc so that every one in the product
 * has the same height, icon treatment and touch target.
 */
object Rows {

    /**
     * Inflates a row into [parent] and returns its binding, so the caller can update the
     * subtitle later (a status that changes on resume) without rebuilding the row.
     *
     * @param subtitle null hides the second line rather than leaving an empty gap.
     * @param onClick null draws the row without a chevron, as a plain read-only line.
     */
    fun add(
        parent: ViewGroup,
        @DrawableRes icon: Int,
        title: CharSequence,
        subtitle: CharSequence? = null,
        onClick: (() -> Unit)? = null,
    ): ItemRowBinding {
        val binding = ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, true)
        binding.rowIcon.setImageResource(icon)
        binding.rowTitle.text = title
        setSubtitle(binding, subtitle)
        if (onClick != null) {
            binding.root.setOnClickListener { onClick() }
        } else {
            binding.rowChevron.visibility = View.GONE
            binding.root.isClickable = false
            binding.root.background = null
        }
        return binding
    }

    fun setSubtitle(binding: ItemRowBinding, subtitle: CharSequence?) {
        binding.rowSubtitle.text = subtitle
        binding.rowSubtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
