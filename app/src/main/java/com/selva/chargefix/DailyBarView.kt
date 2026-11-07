package com.selva.chargefix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** "% of battery used per day" for the last 7 days. Tap a bar to see its value. */
class DailyBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val pal = ChartPalette(ctx)
    private val d = resources.displayMetrics.density
    private var days: List<Pair<String, Int>> = emptyList()
    private var selected = -1

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.series }
    private val grid = Paint().apply { color = pal.grid; strokeWidth = d }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; textSize = 11 * d }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textSize = 12 * d; isFakeBoldText = true }

    fun setData(d: List<Pair<String, Int>>) {
        days = d; selected = d.lastIndex
        contentDescription = "Battery used per day: " + d.joinToString { "${it.first} ${it.second}%" }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (170 * d).toInt())

    private val top get() = 22 * d
    private val bot get() = height - 22 * d
    private fun maxV() = maxOf(100, days.maxOfOrNull { it.second } ?: 0)

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        val m = maxV()
        c.drawLine(0f, bot, width.toFloat(), bot, grid)
        val slot = width / days.size.toFloat()
        val bw = slot * 0.56f
        days.forEachIndexed { i, (label, v) ->
            val cx = slot * i + slot / 2
            val h = (bot - top) * v / m
            if (v > 0) {
                // 4dp rounded top, square at the baseline
                val rr = minOf(4 * d, h)
                val p = Path().apply {
                    addRoundRect(RectF(cx - bw / 2, bot - h, cx + bw / 2, bot),
                        floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f), Path.Direction.CW)
                }
                bar.alpha = if (selected == -1 || selected == i) 255 else 110
                c.drawPath(p, bar)
            }
            val lw = axis.measureText(label)
            c.drawText(label, cx - lw / 2, height - 6 * d, axis)
            if (i == selected) {
                val s = "$v%"
                val sw = value.measureText(s)
                c.drawText(s, cx - sw / 2, bot - h - 6 * d, value)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
            selected = (ev.x / (width / days.size.toFloat())).toInt().coerceIn(0, days.lastIndex)
            invalidate()
        }
        return true
    }
}
