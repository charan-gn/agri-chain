package com.agrichain.buyer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class RouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val points = mutableListOf<Pair<Double, Double>>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = 1f
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B5E20")
        style = Paint.Style.FILL
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.FILL
    }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        style = Paint.Style.FILL
    }

    init {
        setBackgroundColor(Color.parseColor("#FAFAFA"))
    }

    fun setPoints(newPoints: List<Pair<Double, Double>>) {
        points.clear()
        points.addAll(newPoints)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (index in 1..3) {
            val x = width * index / 4f
            val y = height * index / 4f
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
        if (points.isEmpty()) return

        val padding = 28f
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        val spanLat = (maxLat - minLat).coerceAtLeast(0.000001)
        val spanLon = (maxLon - minLon).coerceAtLeast(0.000001)
        val drawWidth = width - padding * 2
        val drawHeight = height - padding * 2

        fun x(lon: Double): Float = (padding + (lon - minLon) / spanLon * drawWidth).toFloat()
        fun y(lat: Double): Float = (height - padding - (lat - minLat) / spanLat * drawHeight).toFloat()

        if (points.size == 1) {
            canvas.drawCircle(x(points[0].second), y(points[0].first), 10f, startPaint)
            return
        }

        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(x(point.second), y(point.first))
            else path.lineTo(x(point.second), y(point.first))
        }
        canvas.drawPath(path, routePaint)
        points.forEach { point ->
            canvas.drawCircle(x(point.second), y(point.first), 5f, pointPaint)
        }
        canvas.drawCircle(x(points.first().second), y(points.first().first), 10f, startPaint)
        canvas.drawCircle(x(points.last().second), y(points.last().first), 10f, endPaint)
    }
}
