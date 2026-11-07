package com.selva.chargefix

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Theme-aware chart colours (one blue series, quiet grid, text in text colours). */
class ChartPalette(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val series = Color.parseColor(if (dark) "#3987e5" else "#2a78d6")
    val charging = Color.parseColor(if (dark) "#33199e70" else "#331baf7a")
    val grid = Color.parseColor(if (dark) "#383835" else "#e3e2de")
    val muted = Color.parseColor(if (dark) "#c3c2b7" else "#52514e")
    val text = Color.parseColor(if (dark) "#f0efec" else "#0b0b0b")
    val surface = Color.parseColor(if (dark) "#1a1a19" else "#fcfcfb")
}

/** Battery % over time; charging stretches are shaded green. Touch and drag to read values. */
class LevelChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val pal = ChartPalette(ctx)
    private val d = resources.displayMetrics.density
    private var data: List<LogEntry> = emptyList()
    private var t0 = 0L
    private var t1 = 1L
    private var touchX = -1f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.series; style = Paint.Style.STROKE; strokeWidth = 2 * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val bandPaint = Paint().apply { color = pal.charging }
    private val gridPaint = Paint().apply { color = pal.grid; strokeWidth = d }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.muted; textSize = 11 * d }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textSize = 13 * d; isFakeBoldText = true }
    private val tipBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }
    private val tipEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.grid; style = Paint.Style.STROKE; strokeWidth = d }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.series }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.surface }

    fun setData(entries: List<LogEntry>, from: Long, to: Long) {
        data = entries; t0 = from; t1 = to; touchX = -1f
        contentDescription = if (entries.isEmpty()) "Battery history: no data yet"
        else "Battery history from ${entries.first().level}% to ${entries.last().level}%"
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (200 * d).toInt())

    private val l get() = 36 * d
    private val r get() = width - 8 * d
    private val top get() = 12 * d
    private val bot get() = height - 24 * d
    private fun x(t: Long) = l + (r - l) * ((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1))
    private fun y(v: Int) = bot - (bot - top) * v / 100f

    override fun onDraw(c: Canvas) {
        listOf(0, 50, 100).forEach { v ->
            c.drawLine(l, y(v), r, y(v), gridPaint)
            c.drawText("$v%", 0f, y(v) + 4 * d, axisText)
        }
        val long = t1 - t0 > 2 * 86_400_000L
        val f = SimpleDateFormat(if (long) "EEE" else "HH:mm", Locale.getDefault())
        listOf(0f, 0.5f, 1f).forEach { p ->
            val t = t0 + ((t1 - t0) * p).toLong()
            val s = f.format(Date(t))
            val w = axisText.measureText(s)
            c.drawText(s, (x(t) - w * p).coerceIn(l, r - w), height - 6 * d, axisText)
        }
        if (data.size < 2) {
            val s = "Collecting data… check back in a few hours"
            c.drawText(s, l + 8 * d, (top + bot) / 2, axisText)
            return
        }
        // Charging bands
        for (k in 1 until data.size) {
            val a = data[k - 1]; val b = data[k]
            if (a.plugged && b.plugged && b.t - a.t < 45 * 60_000) c.drawRect(x(a.t), top, x(b.t), bot, bandPaint)
        }
        // Line (breaks across gaps when the phone was off)
        val path = Path()
        var started = false
        for (k in data.indices) {
            val e = data[k]
            val gap = k > 0 && e.t - data[k - 1].t > 45 * 60_000
            if (!started || gap) { path.moveTo(x(e.t), y(e.level)); started = true } else path.lineTo(x(e.t), y(e.level))
        }
        c.drawPath(path, linePaint)
        // Touch read-out
        if (touchX >= l) {
            val e = data.minByOrNull { kotlin.math.abs(x(it.t) - touchX) } ?: return
            val px = x(e.t); val py = y(e.level)
            c.drawLine(px, top, px, bot, gridPaint)
            c.drawCircle(px, py, 6 * d, ringPaint)
            c.drawCircle(px, py, 4 * d, dotPaint)
            val label = "${e.level}% · ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(e.t))}" +
                if (e.plugged) " · charging" else ""
            val w = tipText.measureText(label) + 16 * d
            val bx = (px - w / 2).coerceIn(l, r - w)
            c.drawRoundRect(bx, top, bx + w, top + 26 * d, 6 * d, 6 * d, tipBox)
            c.drawRoundRect(bx, top, bx + w, top + 26 * d, 6 * d, 6 * d, tipEdge)
            c.drawText(label, bx + 8 * d, top + 18 * d, tipText)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true); touchX = ev.x; invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touchX = -1f; invalidate() }
        }
        return true
    }
}
