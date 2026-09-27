package com.keyguard.app.ui.child

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.keyguard.app.R

/**
 * A drawing of a keyboard, for the mock chat behind the warning preview.
 *
 * Deliberately *not* the real `KeyboardView`. The Warnings screen previews the overlay, which
 * floats over whatever keyboard the child already uses - Gboard, Samsung's, anything - so the
 * keys under it are somebody else's. Drawing Keyguard's own keyboard there would suggest the
 * child has to switch keyboards to be protected, which is exactly the misunderstanding the
 * overlay was built to remove. Blank keys in neutral greys read as "your keyboard" without
 * claiming to be any particular one.
 */
class MockKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_face)
    }
    private val modifierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_face_modifier)
    }
    private val rect = RectF()

    init {
        setBackgroundColor(context.getColor(R.color.keyboard_background))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val gap = 5 * density
        val radius = 5 * density
        val rows = ROWS.size
        val rowHeight = (height - gap * (rows + 1)) / rows
        val usableWidth = width - gap * 2

        ROWS.forEachIndexed { rowIndex, row ->
            val top = gap + rowIndex * (rowHeight + gap)
            // Each row is a list of key widths in tenths of the full width, centred, which is
            // enough to read as QWERTY at a glance without drawing any letters.
            val total = row.sum()
            val unit = (usableWidth - gap * (row.size - 1)) / TENTHS
            var left = gap + (usableWidth - (unit * total + gap * (row.size - 1))) / 2
            row.forEachIndexed { keyIndex, widthTenths ->
                val keyWidth = unit * widthTenths
                rect.set(left, top, left + keyWidth, top + rowHeight)
                val modifier = rowIndex == rows - 1 && keyIndex != 1 ||
                    rowIndex == 2 && (keyIndex == 0 || keyIndex == row.lastIndex)
                val paint = if (modifier) modifierPaint else keyPaint
                canvas.drawRoundRect(rect, radius, radius, paint)
                left += keyWidth + gap
            }
        }
    }

    private companion object {
        const val TENTHS = 10f

        val ROWS = listOf(
            List(10) { 1f },
            List(9) { 1f },
            listOf(1.5f) + List(7) { 1f } + listOf(1.5f),
            listOf(2f, 5f, 2f),
        )
    }
}
