package com.example.tareamov.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar / Spider chart custom View.
 * Call [setAxes] with a list of [Axis] objects (value 0..100) to display the chart.
 * The polygon animates from the center outward on each data update.
 */
class RadarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Axis(val label: String, val value: Float) // value 0..100

    // ── Paints ──────────────────────────────────────────────────────────────

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#40B8B3FF")
    }

    private val innerGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#20FFFFFF")
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#15FFFFFF")
    }

    private val dataFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dataStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#B8B3FF")
        strokeJoin = Paint.Join.ROUND
    }

    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B8B3FF")
    }

    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#30B8B3FF")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 26f
        color = Color.parseColor("#99FFFFFF")
    }

    // ── State ────────────────────────────────────────────────────────────────

    private val axes = mutableListOf<Axis>()
    private var animProgress = 0f
    private val dataPath = Path()
    private val ringPath = Path()

    // ── Public API ───────────────────────────────────────────────────────────

    fun setAxes(data: List<Axis>) {
        axes.clear()
        axes.addAll(data)
        startAnimation()
    }

    // ── Animation ────────────────────────────────────────────────────────────

    private fun startAnimation() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animProgress = animatedValue as Float; invalidate() }
            start()
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val n = axes.size
        if (n < 3) return

        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.60f
        val rings = 4
        val startAngle = (-Math.PI / 2).toFloat() // 12 o'clock

        fun angle(i: Int) = startAngle + i * 2f * Math.PI.toFloat() / n

        fun ptAt(i: Int, fraction: Float) = PointF(
            cx + r * fraction * cos(angle(i)),
            cy + r * fraction * sin(angle(i))
        )

        // ── Grid rings ──────────────────────────────────────────────────────
        for (ring in 1..rings) {
            val f = ring.toFloat() / rings
            ringPath.reset()
            val first = ptAt(0, f)
            ringPath.moveTo(first.x, first.y)
            for (i in 1 until n) {
                val p = ptAt(i, f)
                ringPath.lineTo(p.x, p.y)
            }
            ringPath.close()
            canvas.drawPath(ringPath, if (ring == rings) outerRingPaint else innerGridPaint)
        }

        // ── Axis spokes ─────────────────────────────────────────────────────
        for (i in 0 until n) {
            val tip = ptAt(i, 1f)
            canvas.drawLine(cx, cy, tip.x, tip.y, axisPaint)
        }

        // ── Data polygon ────────────────────────────────────────────────────
        dataPath.reset()
        for (i in 0 until n) {
            val f = (axes[i].value / 100f).coerceIn(0f, 1f) * animProgress
            val pt = ptAt(i, f)
            if (i == 0) dataPath.moveTo(pt.x, pt.y) else dataPath.lineTo(pt.x, pt.y)
        }
        dataPath.close()

        // Radial gradient fill
        dataFillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.parseColor("#66B8B3FF"), Color.parseColor("#11B8B3FF")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(dataPath, dataFillPaint)
        canvas.drawPath(dataPath, dataStrokePaint)

        // ── Dot markers ─────────────────────────────────────────────────────
        for (i in 0 until n) {
            val f = (axes[i].value / 100f).coerceIn(0f, 1f) * animProgress
            val pt = ptAt(i, f)
            canvas.drawCircle(pt.x, pt.y, 11f, dotHaloPaint)
            canvas.drawCircle(pt.x, pt.y, 5.5f, dotFillPaint)
        }

        // ── Axis labels ─────────────────────────────────────────────────────
        val labelGap = 28f
        for (i in 0 until n) {
            val a = angle(i)
            val lx = cx + (r + labelGap) * cos(a)
            val ly = cy + (r + labelGap) * sin(a) + labelPaint.textSize / 3f
            canvas.drawText(axes[i].label, lx, ly, labelPaint)
        }
    }
}
