package com.aetherdex.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale

class MonthlyPnlChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#262D3D")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5563")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val greenBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C853")
        style = Paint.Style.FILL
    }

    private val redBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }

    private val zeroBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#374151")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9CA3AF")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        style = Paint.Style.FILL
    }

    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C853")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val monthLabels = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val monthlyPnlUsd = DoubleArray(12)
    private var totalFeesUsd: Double = 0.0
    private var selectedYearStr: String = "ALL"
    private var baseCurrency: String = "USD"
    private var fxRate: Double = 1.0
    private var fxSymbol: String = "$"

    private var selectedMonthIndex: Int = -1

    fun setData(closedPositions: List<ClosedPosition>, selectedYear: String = "ALL", currency: String = "USD") {
        this.selectedYearStr = selectedYear
        this.baseCurrency = currency
        this.fxRate = when (currency) {
            "EUR" -> 0.92
            "GBP" -> 0.79
            else -> 1.0
        }
        this.fxSymbol = when (currency) {
            "EUR" -> "€"
            "GBP" -> "£"
            else -> "$"
        }

        monthlyPnlUsd.fill(0.0)
        totalFeesUsd = 0.0
        selectedMonthIndex = -1

        val cal = Calendar.getInstance()
        val targetYear = selectedYear.toIntOrNull()

        for (pos in closedPositions) {
            cal.timeInMillis = pos.timestamp
            val year = cal.get(Calendar.YEAR)

            if (targetYear != null && year != targetYear) {
                continue
            }

            val monthIndex = cal.get(Calendar.MONTH) // 0..11
            if (monthIndex in 0..11) {
                val takerFee = pos.positionSizeUsdc * 0.0005 * 2.0
                val netPnl = pos.realizedPnl - takerFee
                monthlyPnlUsd[monthIndex] += netPnl
                totalFeesUsd += takerFee
            }
        }

        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
            val paddingLeft = 30f
            val paddingRight = 30f
            val chartWidth = width - paddingLeft - paddingRight
            val slotWidth = chartWidth / 12f
            val x = event.x

            if (x in paddingLeft..(width - paddingRight)) {
                val index = ((x - paddingLeft) / slotWidth).toInt().coerceIn(0, 11)
                if (event.action == MotionEvent.ACTION_UP) {
                    selectedMonthIndex = if (selectedMonthIndex == index) -1 else index
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val paddingLeft = 30f
        val paddingRight = 30f
        val paddingTop = 45f
        val paddingBottom = 55f

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom
        val baselineY = paddingTop + (chartHeight * 0.55f)

        // Draw background grid lines
        canvas.drawLine(paddingLeft, paddingTop, width - paddingRight, paddingTop, gridPaint)
        canvas.drawLine(paddingLeft, paddingTop + (chartHeight * 0.25f), width - paddingRight, paddingTop + (chartHeight * 0.25f), gridPaint)
        canvas.drawLine(paddingLeft, paddingTop + (chartHeight * 0.75f), width - paddingRight, paddingTop + (chartHeight * 0.75f), gridPaint)

        // Draw faint horizontal zero baseline
        canvas.drawLine(paddingLeft, baselineY, width - paddingRight, baselineY, baselinePaint)

        // Max absolute PnL for scaling
        var maxAbsPnl = monthlyPnlUsd.maxOfOrNull { Math.abs(it) } ?: 0.0
        if (maxAbsPnl < 10.0) maxAbsPnl = 100.0 // Minimum scale headroom

        val maxBarHeightAbove = chartHeight * 0.45f
        val maxBarHeightBelow = chartHeight * 0.35f

        val numBars = 12
        val slotWidth = chartWidth / numBars
        val barWidth = slotWidth * 0.72f
        val cornerRadius = 8f

        var tooltipRectToDraw: RectF? = null
        var tooltipTextToDraw: String? = null
        var tooltipColor: Int = Color.parseColor("#00C853")

        for (i in 0 until numBars) {
            val pnlUsd = monthlyPnlUsd[i]
            val slotLeft = paddingLeft + (i * slotWidth)
            val barLeft = slotLeft + (slotWidth - barWidth) / 2f
            val barRight = barLeft + barWidth
            val isSelected = (i == selectedMonthIndex)

            val monthLabel = monthLabels[i]

            val barRect = if (pnlUsd == 0.0) {
                // Flat zero bar line
                RectF(barLeft, baselineY - 2.5f, barRight, baselineY + 2.5f).also {
                    canvas.drawRoundRect(it, 2f, 2f, zeroBarPaint)
                }
            } else if (pnlUsd > 0) {
                // Positive green bar going up
                val barH = ((pnlUsd / maxAbsPnl) * maxBarHeightAbove).toFloat().coerceAtLeast(8f)
                val topY = baselineY - barH
                RectF(barLeft, topY, barRight, baselineY).also {
                    canvas.drawRoundRect(it, cornerRadius, cornerRadius, greenBarPaint)
                }
            } else {
                // Negative red bar going down
                val barH = ((Math.abs(pnlUsd) / maxAbsPnl) * maxBarHeightBelow).toFloat().coerceAtLeast(8f)
                val bottomY = baselineY + barH
                RectF(barLeft, baselineY, barRight, bottomY).also {
                    canvas.drawRoundRect(it, cornerRadius, cornerRadius, redBarPaint)
                }
            }

            // Draw outline highlight on tapped bar
            if (isSelected) {
                canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, highlightBorderPaint)

                // Prepare tooltip calculation
                val convertedPnl = pnlUsd * fxRate
                val formattedVal = String.format(
                    Locale.US,
                    "%s: %s%s%.2f",
                    monthLabel,
                    if (convertedPnl >= 0) "+" else "-",
                    fxSymbol,
                    Math.abs(convertedPnl)
                )
                tooltipTextToDraw = formattedVal
                tooltipColor = if (pnlUsd >= 0) Color.parseColor("#00C853") else Color.parseColor("#FF5252")

                val ttWidth = (tooltipTextPaint.measureText(formattedVal) + 24f).coerceAtLeast(120f)
                val ttHeight = 36f
                val centerX = barLeft + (barWidth / 2f)

                val ttLeft = (centerX - (ttWidth / 2f)).coerceIn(paddingLeft, width - paddingRight - ttWidth)
                val ttRight = ttLeft + ttWidth
                val ttTop = if (barRect.top - ttHeight - 10f > 5f) {
                    barRect.top - ttHeight - 10f
                } else {
                    barRect.bottom + 10f
                }
                tooltipRectToDraw = RectF(ttLeft, ttTop, ttRight, ttTop + ttHeight)
            }

            // Draw month label below
            val labelColor = if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#9CA3AF")
            textPaint.color = labelColor
            canvas.drawText(monthLabel, barLeft + (barWidth / 2f), height - 12f, textPaint)
        }

        // Draw Tooltip Bubble overlay on top of chart
        if (tooltipRectToDraw != null && tooltipTextToDraw != null) {
            tooltipBorderPaint.color = tooltipColor
            canvas.drawRoundRect(tooltipRectToDraw, 8f, 8f, tooltipBgPaint)
            canvas.drawRoundRect(tooltipRectToDraw, 8f, 8f, tooltipBorderPaint)
            canvas.drawText(
                tooltipTextToDraw,
                tooltipRectToDraw.centerX(),
                tooltipRectToDraw.centerY() + 7f,
                tooltipTextPaint
            )
        }
    }
}
