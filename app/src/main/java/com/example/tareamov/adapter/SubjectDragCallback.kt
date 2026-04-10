package com.example.tareamov.adapter

import android.graphics.Canvas
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Handles drag-to-reorder for SubjectAdapter.
 * Single Responsibility: solo gestiona la lógica de arrastre e ItemTouchHelper.
 * Auto-scroll proporcional al acercar el ítem arrastrado al borde de la pantalla.
 */
class SubjectDragCallback(
    private val adapter: SubjectAdapter,
    private val scrollContainer: NestedScrollView?,
    private val onDragReleased: () -> Unit
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

    companion object {
        private const val SCROLL_ZONE_DP = 90f
        private const val MAX_SCROLL_SPEED = 22
        private const val MIN_SCROLL_SPEED = 5
        private const val FRAME_MS = 16L
    }

    private var scrolling = false
    private var scrollSpeed = 0

    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (scrolling) {
                scrollContainer?.scrollBy(0, scrollSpeed)
                scrollContainer?.postDelayed(this, FRAME_MS)
            }
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onMoveItem(viewHolder.adapterPosition, target.adapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)

        if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || !isCurrentlyActive) {
            stopAutoScroll()
            return
        }

        // Posición absoluta en pantalla (incluye translationY seteada por ItemTouchHelper)
        val location = IntArray(2)
        viewHolder.itemView.getLocationOnScreen(location)
        val itemTop = location[1].toFloat()
        val itemBottom = itemTop + viewHolder.itemView.height

        val dm = recyclerView.resources.displayMetrics
        val screenH = dm.heightPixels.toFloat()
        val zonePx = SCROLL_ZONE_DP * dm.density

        when {
            itemTop < zonePx -> {
                val ratio = (1f - itemTop / zonePx).coerceIn(0f, 1f)
                val speed = (MIN_SCROLL_SPEED + (MAX_SCROLL_SPEED - MIN_SCROLL_SPEED) * ratio).toInt()
                startAutoScroll(-speed)
            }
            itemBottom > screenH - zonePx -> {
                val ratio = (1f - (screenH - itemBottom) / zonePx).coerceIn(0f, 1f)
                val speed = (MIN_SCROLL_SPEED + (MAX_SCROLL_SPEED - MIN_SCROLL_SPEED) * ratio).toInt()
                startAutoScroll(speed)
            }
            else -> stopAutoScroll()
        }
    }

    private fun startAutoScroll(speed: Int) {
        scrollSpeed = speed
        if (!scrolling) {
            scrolling = true
            scrollContainer?.post(scrollRunnable)
        }
    }

    fun stopAutoScroll() {
        scrolling = false
        scrollContainer?.removeCallbacks(scrollRunnable)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        stopAutoScroll()
        onDragReleased()
    }

    /**
     * Cuando está en true el long press sobre cualquier card inicia el arrastre
     * directamente, sin necesidad de tocar el handle.
     */
    var dragModeActive: Boolean = false

    override fun isLongPressDragEnabled() = dragModeActive
}
