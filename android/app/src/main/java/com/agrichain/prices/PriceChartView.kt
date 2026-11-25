package com.agrichain.prices

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class PriceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var bars: List<PriceBar> = emptyList()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2933")
        textSize = sp(13f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B5E20")
        textSize = sp(13f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = sp(10f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val track = RectF()

    fun setTrades(trades: List<Trade>) {
        bars = PriceBars.build(trades)
        contentDescription = if (bars.isEmpty()) {
            "No priced transfers cached"
        } else {
            bars.joinToString(", ") { "${it.label} ${PriceFormat.perKg(it.pricePerKgPaise)}" }
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val rows = if (bars.isEmpty()) dp(64f) else bars.size * rowHeight
        val height = resolveSize(
            paddingTop + paddingBottom + rows.toInt(),
            heightMeasureSpec,
        )
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + dp(4f)
        val right = width - paddingRight - dp(4f)

        if (bars.isEmpty()) {
            val centerY = height / 2f - (emptyPaint.descent() + emptyPaint.ascent()) / 2f
            canvas.drawText("No priced transfers yet.", width / 2f, centerY, emptyPaint)
            return
        }

        var top = paddingTop.toFloat()
        bars.forEach { bar ->
            val baseline = top + sp(14f)
            canvas.drawText(bar.label, left, baseline, labelPaint)
            canvas.drawText(
                PriceFormat.perKg(bar.pricePerKgPaise),
                right,
                baseline,
                valuePaint,
            )

            val barTop = top + dp(22f)
            track.set(left, barTop, right, barTop + dp(8f))
            canvas.drawRoundRect(track, dp(4f), dp(4f), trackPaint)
            if (bar.fraction > 0f) {
                track.right = left + (right - left) * bar.fraction
                canvas.drawRoundRect(track, dp(4f), dp(4f), fillPaint)
            }
            canvas.drawText(bar.source, left, barTop + dp(8f) + sp(10f), sourcePaint)
            top += rowHeight
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val rowHeight: Float
        get() = dp(54f)
}
