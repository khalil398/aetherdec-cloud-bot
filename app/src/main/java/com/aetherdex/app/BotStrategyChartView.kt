package com.aetherdex.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class BotStrategyChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Candle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double = 0.0,
        val timestamp: Long = 0L
    )

    enum class BotState { NEUTRAL, IN_BUY, IN_SELL }
    private enum class ChannelState { UNKNOWN, BELOW_OR_INSIDE, FULLY_ABOVE }

    // Candle data
    var candles: List<Candle> = emptyList()
        set(value) {
            val isNewDataset = field.isEmpty() || (value.isNotEmpty() && field.isNotEmpty() && abs(value.first().close - field.first().close) > field.first().close * 0.1)
            field = value
            recalculateIndicators()
            updatePriceRange()
            if (isNewDataset) {
                resetView()
            } else if (value.isNotEmpty()) {
                val maxScroll = max(0f, (value.size - 6).toFloat())
                scrollOffsetFloat = scrollOffsetFloat.coerceIn(-6f, maxScroll)
            }
            invalidate()
        }

    private fun updatePriceRange() {
        if (candles.isEmpty()) return
        val minP = candles.minOf { it.low }
        val maxP = candles.maxOf { it.high }
        if (maxP > minP) {
            lastPriceRange = (maxP - minP) * 1.44
        }
    }

    // Calculated indicators series
    private var ema8HighSeries: DoubleArray = DoubleArray(0)
    private var ema8LowSeries: DoubleArray = DoubleArray(0)
    private var ema34HighSeries: DoubleArray = DoubleArray(0)
    private var ema34LowSeries: DoubleArray = DoubleArray(0)
    private var ema200Series: DoubleArray = DoubleArray(0)
    private var ema233Series: DoubleArray = DoubleArray(0)

    // Signals: 1 = BUY, -1 = SELL, 0 = NONE
    private var signalSeries: IntArray = IntArray(0)

    // View State & Touch Control Parameters
    private var zoomFactor: Float = 1.0f // 0.10f to 8.0f (Controls candleWidth and visible candle count)
    private var scrollOffsetFloat: Float = 0f
    private var verticalPriceOffset: Double = 0.0 // Free look vertical price pan
    private var yScaleMultiplier: Double = 1.0 // Price scale vertical stretch/compression
    private var lastPriceRange: Double = 1.0

    private val baseVisibleCount = 45f // Base visible candle count at 1.0x zoom

    // Touch interaction tracking
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingPriceAxis = false
    private var isDraggingTimeAxis = false
    private var isDraggingChart = false

    // Crosshair state
    private var isCrosshairActive = false
    private var touchX = 0f
    private var touchY = 0f
    private var selectedCandleIndex = -1

    // Layout Margins
    private val paddingRightPx = 140f
    private val paddingTopPx = 70f
    private val paddingBottomPx = 60f
    private val paddingLeftPx = 10f

    // Colors
    private val colorBg = Color.parseColor("#0B0E14")
    private val colorCardBg = Color.parseColor("#151921")
    private val colorGrid = Color.parseColor("#1A202C")
    private val colorBullish = Color.parseColor("#00E676")
    private val colorBearish = Color.parseColor("#FF5252")
    private val colorWhiteChannel = Color.parseColor("#FFFFFF")
    private val colorWhiteChannelFill = Color.parseColor("#20FFFFFF")
    private val colorYellowChannel = Color.parseColor("#FFD700")
    private val colorYellowChannelFill = Color.parseColor("#25FFD700")
    private val colorEma200 = Color.parseColor("#FF3B30")
    private val colorEma233 = Color.parseColor("#FF9500")
    private val colorTextSec = Color.parseColor("#8A96A8")

    // Pre-allocated Paints
    private val paintGrid = Paint().apply {
        color = colorGrid
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val paintCandleBull = Paint().apply {
        color = colorBullish
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintCandleBear = Paint().apply {
        color = colorBearish
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintWickBull = Paint().apply {
        color = colorBullish
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintWickBear = Paint().apply {
        color = colorBearish
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintWhiteLine = Paint().apply {
        color = colorWhiteChannel
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintWhiteFill = Paint().apply {
        color = colorWhiteChannelFill
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintYellowLine = Paint().apply {
        color = colorYellowChannel
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintYellowFill = Paint().apply {
        color = colorYellowChannelFill
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintEma200Line = Paint().apply {
        color = colorEma200
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintEma233Line = Paint().apply {
        color = colorEma233
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val paintTextSec = Paint().apply {
        color = colorTextSec
        textSize = 22f
        isAntiAlias = true
    }

    private val paintBadgeBuy = Paint().apply {
        color = colorBullish
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintBadgeSell = Paint().apply {
        color = colorBearish
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintCrosshair = Paint().apply {
        color = Color.parseColor("#90A4AE")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        isAntiAlias = true
    }

    // Pre-allocated reusable paths
    private val pathWhiteChannel = Path()
    private val pathWhiteUpper = Path()
    private val pathWhiteLower = Path()
    private val pathYellowChannel = Path()
    private val pathYellowUpper = Path()
    private val pathYellowLower = Path()
    private val pathEma200 = Path()
    private val pathEma233 = Path()

    // Smooth Kinetic Fling Scroller
    private val scroller = OverScroller(context)
    private var lastFlingX = 0

    // Touch gesture detectors
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = zoomFactor
                zoomFactor = (zoomFactor * detector.scaleFactor).coerceIn(0.10f, 8.0f)

                val chartWidth = width.toFloat() - paddingLeftPx - paddingRightPx
                if (chartWidth > 0f && candles.isNotEmpty()) {
                    val focusX = (detector.focusX - paddingLeftPx).coerceIn(0f, chartWidth)
                    val focusRatio = focusX / chartWidth
                    val oldVisible = baseVisibleCount / oldZoom
                    val newVisible = baseVisibleCount / zoomFactor
                    val candleDelta = (newVisible - oldVisible) * (1f - focusRatio)
                    val maxScroll = max(0f, (candles.size - 6).toFloat())
                    scrollOffsetFloat = (scrollOffsetFloat + candleDelta).coerceIn(-6f, maxScroll)
                }

                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isCrosshairActive) {
                    isCrosshairActive = false
                    invalidate()
                    return true
                } else {
                    touchX = e.x
                    touchY = e.y
                    isCrosshairActive = true
                    invalidate()
                    return true
                }
            }

            override fun onLongPress(e: MotionEvent) {
                touchX = e.x
                touchY = e.y
                isCrosshairActive = true
                invalidate()
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (candles.isEmpty() || scaleGestureDetector.isInProgress || isCrosshairActive) return false
                scroller.forceFinished(true)
                lastFlingX = 0
                scroller.fling(
                    0, 0,
                    velocityX.toInt(), 0,
                    -20000, 20000,
                    0, 0
                )
                postInvalidateOnAnimation()
                return true
            }
        })
    }

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            val currX = scroller.currX
            val dx = currX - lastFlingX
            lastFlingX = currX

            val chartWidth = width.toFloat() - paddingLeftPx - paddingRightPx
            val visibleCount = (baseVisibleCount / zoomFactor).coerceIn(5f, max(45f, candles.size.toFloat()))
            val candleWidth = max(0.5f, chartWidth / visibleCount)
            if (candleWidth > 0f) {
                val deltaOffset = dx / candleWidth
                val minScroll = -6f
                val maxScroll = max(0f, (candles.size - 6).toFloat())
                scrollOffsetFloat = (scrollOffsetFloat + deltaOffset).coerceIn(minScroll, maxScroll)
            }
            postInvalidateOnAnimation()
        }
    }

    fun resetView() {
        scroller.forceFinished(true)
        zoomFactor = 1.0f
        scrollOffsetFloat = 0f
        verticalPriceOffset = 0.0
        yScaleMultiplier = 1.0
        isCrosshairActive = false
        selectedCandleIndex = -1
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (candles.isEmpty()) return super.onTouchEvent(event)

        gestureDetector.onTouchEvent(event)

        if (isCrosshairActive) {
            touchX = event.x
            touchY = event.y
            invalidate()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        scaleGestureDetector.onTouchEvent(event)

        if (scaleGestureDetector.isInProgress) {
            lastTouchX = event.x
            lastTouchY = event.y
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        val rightAxisLeft = width.toFloat() - paddingRightPx
        val bottomAxisTop = height.toFloat() - paddingBottomPx

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchX = event.x
                lastTouchY = event.y
                isDraggingPriceAxis = event.x >= rightAxisLeft
                isDraggingTimeAxis = event.y >= bottomAxisTop
                isDraggingChart = !isDraggingPriceAxis && !isDraggingTimeAxis
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y

                val chartWidth = rightAxisLeft - paddingLeftPx
                val chartHeight = bottomAxisTop - paddingTopPx

                if (isDraggingPriceAxis && chartHeight > 0f) {
                    // Right Y-Axis Scale Drag: Stretches or compresses candle vertical height
                    val scaleDelta = 1.0 + (dy / 300.0)
                    yScaleMultiplier = (yScaleMultiplier * scaleDelta).coerceIn(0.2, 5.0)
                    invalidate()
                } else if (isDraggingTimeAxis && chartWidth > 0f) {
                    // Bottom X-Axis Time Scale Drag: Adjusts zoom factor
                    val zoomDelta = dx / 200f
                    zoomFactor = (zoomFactor + zoomDelta).coerceIn(0.10f, 8.0f)
                    invalidate()
                } else if (isDraggingChart && chartWidth > 0f && chartHeight > 0f) {
                    // 1. Horizontal Drag (Scroll through candles)
                    val visibleCount = (baseVisibleCount / zoomFactor).coerceIn(5f, max(45f, candles.size.toFloat()))
                    val candleWidth = max(0.5f, chartWidth / visibleCount)
                    if (candleWidth > 0f) {
                        val deltaOffset = dx / candleWidth
                        val minScroll = -6f
                        val maxScroll = max(0f, (candles.size - 6).toFloat())
                        scrollOffsetFloat = (scrollOffsetFloat + deltaOffset).coerceIn(minScroll, maxScroll)
                    }

                    // 2. Vertical Drag (Free look - Pan price up / down smoothly 1:1 with finger)
                    val effectiveRange = if (lastPriceRange > 0.0) lastPriceRange * yScaleMultiplier else 100.0
                    val priceShift = (dy / chartHeight) * effectiveRange
                    verticalPriceOffset += priceShift
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingPriceAxis = false
                isDraggingTimeAxis = false
                isDraggingChart = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return true
    }

    private fun recalculateIndicators() {
        val n = candles.size
        if (n == 0) return

        val highs = DoubleArray(n) { candles[it].high }
        val lows = DoubleArray(n) { candles[it].low }
        val closes = DoubleArray(n) { candles[it].close }

        ema8HighSeries = calculateEMA(highs, 8)
        ema8LowSeries = calculateEMA(lows, 8)
        ema34HighSeries = calculateEMA(highs, 34)
        ema34LowSeries = calculateEMA(lows, 34)
        ema200Series = calculateEMA(closes, 200)
        ema233Series = calculateEMA(closes, 233)

        signalSeries = IntArray(n)
        var currentPositionState = BotState.NEUTRAL
        if (n > 0) {
            val e8H0 = ema8HighSeries[0]
            val e8L0 = ema8LowSeries[0]
            val e34H0 = ema34HighSeries[0]
            val e34L0 = ema34LowSeries[0]
            if (e8L0 > e34H0 && e8H0 > e34H0) {
                currentPositionState = BotState.IN_BUY
            } else if (e8H0 < e34L0 && e8L0 < e34L0) {
                currentPositionState = BotState.IN_SELL
            }
        }

        for (i in 1 until n) {
            val e8H = ema8HighSeries[i]
            val e8L = ema8LowSeries[i]
            val e34H = ema34HighSeries[i]
            val e34L = ema34LowSeries[i]

            // BUY Signal (▲ BUY): ENTIRE White Channel completely exits/crosses ABOVE ENTIRE Yellow Channel
            // Condition: EMA_8_Low > EMA_34_High AND EMA_8_High > EMA_34_High
            val isWhiteAboveYellow = (e8L > e34H && e8H > e34H)

            // SELL Signal (▼ SELL): ENTIRE White Channel completely exits/crosses BELOW ENTIRE Yellow Channel
            // Condition: EMA_8_High < EMA_34_Low AND EMA_8_Low < EMA_34_Low
            val isWhiteBelowYellow = (e8H < e34L && e8L < e34L)

            if (isWhiteAboveYellow) {
                if (currentPositionState != BotState.IN_BUY) {
                    signalSeries[i] = 1 // Render a single ▲ BUY badge ONCE on exact candle where this transition occurs
                    currentPositionState = BotState.IN_BUY
                }
            } else if (isWhiteBelowYellow) {
                if (currentPositionState != BotState.IN_SELL) {
                    signalSeries[i] = -1 // Render a single ▼ SELL badge ONCE on exact candle where this transition occurs
                    currentPositionState = BotState.IN_SELL
                }
            } else {
                currentPositionState = BotState.NEUTRAL
            }
        }
    }

    private fun calculateEMA(data: DoubleArray, period: Int): DoubleArray {
        val n = data.size
        val result = DoubleArray(n)
        if (n == 0) return result

        val k = 2.0 / (period + 1.0)
        var sum = 0.0
        val initialCount = min(n, period)
        for (i in 0 until initialCount) {
            sum += data[i]
        }
        var ema = sum / initialCount
        for (i in 0 until initialCount) {
            result[i] = ema
        }

        for (i in initialCount until n) {
            ema = (data[i] * k) + (ema * (1.0 - k))
            result[i] = ema
        }
        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(colorBg)

        val totalCandles = candles.size
        if (totalCandles == 0) {
            val emptyMsg = "Fetching 30m Bot Strategy Candles..."
            canvas.drawText(emptyMsg, width / 4f, height / 2f, paintTextSec)
            return
        }

        val chartWidth = width.toFloat() - paddingLeftPx - paddingRightPx
        val chartHeight = height.toFloat() - paddingTopPx - paddingBottomPx

        if (chartWidth <= 0 || chartHeight <= 0) return

        // Compute visible range with time scale zoom factor
        val visibleCandleCount = (baseVisibleCount / zoomFactor).coerceIn(5f, max(45f, totalCandles.toFloat()))
        val floatEndIndex = (totalCandles - 1).toFloat() - scrollOffsetFloat
        val floatStartIndex = floatEndIndex - visibleCandleCount + 1f

        val calcStartIndex = max(0, floatStartIndex.toInt())
        val calcEndIndex = min(totalCandles - 1, floatEndIndex.toInt())

        if (calcEndIndex < calcStartIndex) return

        // Render indices extended by 1 candle to left/right for smooth margin transitions
        val renderStartIndex = max(0, (floatStartIndex - 1f).toInt())
        val renderEndIndex = min(totalCandles - 1, (floatEndIndex + 1f).toInt())

        // Compute Min & Max price in visible range
        var minPrice = Double.MAX_VALUE
        var maxPrice = Double.MIN_VALUE

        for (i in calcStartIndex..calcEndIndex) {
            val c = candles[i]
            if (c.low < minPrice) minPrice = c.low
            if (c.high > maxPrice) maxPrice = c.high

            if (i < ema8LowSeries.size && ema8LowSeries[i] > 0 && ema8LowSeries[i] < minPrice) minPrice = ema8LowSeries[i]
            if (i < ema8HighSeries.size && ema8HighSeries[i] > maxPrice) maxPrice = ema8HighSeries[i]
            if (i < ema34LowSeries.size && ema34LowSeries[i] > 0 && ema34LowSeries[i] < minPrice) minPrice = ema34LowSeries[i]
            if (i < ema34HighSeries.size && ema34HighSeries[i] > maxPrice) maxPrice = ema34HighSeries[i]
            if (i < ema200Series.size && ema200Series[i] > 0 && ema200Series[i] < minPrice) minPrice = ema200Series[i]
            if (i < ema200Series.size && ema200Series[i] > maxPrice) maxPrice = ema200Series[i]
        }

        if (minPrice >= maxPrice) {
            minPrice *= 0.99
            maxPrice *= 1.01
        }

        // Add 22% vertical padding so candles have comfortable breathing room and are smaller
        val rawSpan = maxPrice - minPrice
        val paddingSpan = rawSpan * 0.22
        val fittedMinPrice = minPrice - paddingSpan
        val fittedMaxPrice = maxPrice + paddingSpan

        val effectiveSpan = (fittedMaxPrice - fittedMinPrice) * yScaleMultiplier
        val midPrice = (fittedMaxPrice + fittedMinPrice) / 2.0 + verticalPriceOffset
        val currentMinPrice = midPrice - (effectiveSpan / 2.0)
        val currentMaxPrice = midPrice + (effectiveSpan / 2.0)
        val priceRange = if (currentMaxPrice > currentMinPrice) currentMaxPrice - currentMinPrice else 1.0
        lastPriceRange = priceRange

        val candleWidth = max(0.5f, chartWidth / visibleCandleCount)
        val getX = { index: Int -> paddingLeftPx + (index - floatStartIndex) * candleWidth + candleWidth / 2f }
        val getY = { price: Double ->
            val ratio = (price - currentMinPrice) / priceRange
            (paddingTopPx + chartHeight - ratio * chartHeight).toFloat()
        }

        // Draw Grid Lines & Price Axis Labels
        val gridLines = 5
        for (g in 0..gridLines) {
            val py = paddingTopPx + (chartHeight / gridLines) * g
            canvas.drawLine(paddingLeftPx, py, paddingLeftPx + chartWidth, py, paintGrid)

            val pValue = currentMaxPrice - (g.toDouble() / gridLines) * priceRange
            val pText = String.format(Locale.US, "%.2f", pValue)
            canvas.drawText(pText, paddingLeftPx + chartWidth + 12f, py + 8f, paintTextSec)
        }

        // Reset & Build Paths for EMA Channels & Trend Lines
        pathWhiteUpper.reset()
        pathWhiteLower.reset()
        pathWhiteChannel.reset()

        pathYellowUpper.reset()
        pathYellowLower.reset()
        pathYellowChannel.reset()

        pathEma200.reset()
        pathEma233.reset()

        var firstPoint = true
        for (i in renderStartIndex..renderEndIndex) {
            val cx = getX(i)

            if (i < ema8HighSeries.size && ema8HighSeries[i] > 0) {
                val y8H = getY(ema8HighSeries[i])
                val y8L = getY(ema8LowSeries[i])
                if (firstPoint) {
                    pathWhiteUpper.moveTo(cx, y8H)
                    pathWhiteLower.moveTo(cx, y8L)
                } else {
                    pathWhiteUpper.lineTo(cx, y8H)
                    pathWhiteLower.lineTo(cx, y8L)
                }
            }

            if (i < ema34HighSeries.size && ema34HighSeries[i] > 0) {
                val y34H = getY(ema34HighSeries[i])
                val y34L = getY(ema34LowSeries[i])
                if (firstPoint) {
                    pathYellowUpper.moveTo(cx, y34H)
                    pathYellowLower.moveTo(cx, y34L)
                } else {
                    pathYellowUpper.lineTo(cx, y34H)
                    pathYellowLower.lineTo(cx, y34L)
                }
            }

            if (i < ema200Series.size && ema200Series[i] > 0) {
                val y200 = getY(ema200Series[i])
                if (firstPoint) pathEma200.moveTo(cx, y200) else pathEma200.lineTo(cx, y200)
            }

            if (i < ema233Series.size && ema233Series[i] > 0) {
                val y233 = getY(ema233Series[i])
                if (firstPoint) pathEma233.moveTo(cx, y233) else pathEma233.lineTo(cx, y233)
            }

            firstPoint = false
        }

        // Construct White Channel Fill (EMA 8 High/Low)
        for (i in renderStartIndex..renderEndIndex) {
            if (i < ema8HighSeries.size && ema8HighSeries[i] > 0) {
                val cx = getX(i)
                val y8H = getY(ema8HighSeries[i])
                if (i == renderStartIndex) pathWhiteChannel.moveTo(cx, y8H) else pathWhiteChannel.lineTo(cx, y8H)
            }
        }
        for (i in renderEndIndex downTo renderStartIndex) {
            if (i < ema8LowSeries.size && ema8LowSeries[i] > 0) {
                val cx = getX(i)
                val y8L = getY(ema8LowSeries[i])
                pathWhiteChannel.lineTo(cx, y8L)
            }
        }
        pathWhiteChannel.close()

        // Construct Yellow Channel Fill (EMA 34 High/Low)
        for (i in renderStartIndex..renderEndIndex) {
            if (i < ema34HighSeries.size && ema34HighSeries[i] > 0) {
                val cx = getX(i)
                val y34H = getY(ema34HighSeries[i])
                if (i == renderStartIndex) pathYellowChannel.moveTo(cx, y34H) else pathYellowChannel.lineTo(cx, y34H)
            }
        }
        for (i in renderEndIndex downTo renderStartIndex) {
            if (i < ema34LowSeries.size && ema34LowSeries[i] > 0) {
                val cx = getX(i)
                val y34L = getY(ema34LowSeries[i])
                pathYellowChannel.lineTo(cx, y34L)
            }
        }
        pathYellowChannel.close()

        // Draw EMA Channel Fills & Stroke Lines
        canvas.drawPath(pathYellowChannel, paintYellowFill)
        canvas.drawPath(pathYellowUpper, paintYellowLine)
        canvas.drawPath(pathYellowLower, paintYellowLine)

        canvas.drawPath(pathWhiteChannel, paintWhiteFill)
        canvas.drawPath(pathWhiteUpper, paintWhiteLine)
        canvas.drawPath(pathWhiteLower, paintWhiteLine)

        // Draw Long-Term Trend Lines (EMA 200 & EMA 233)
        canvas.drawPath(pathEma200, paintEma200Line)
        canvas.drawPath(pathEma233, paintEma233Line)

        // Draw Candlesticks & Signal Badges
        val bodyWidth = if (candleWidth <= 2.2f) 1f else max(1f, candleWidth * 0.72f)
        val halfBody = bodyWidth / 2f
        val wickWidth = if (candleWidth <= 3f) 1f else if (candleWidth <= 6f) 1.5f else 2.5f
        paintWickBull.strokeWidth = wickWidth
        paintWickBear.strokeWidth = wickWidth

        for (i in renderStartIndex..renderEndIndex) {
            val c = candles[i]
            val cx = getX(i)
            val yOpen = getY(c.open)
            val yClose = getY(c.close)
            val yHigh = getY(c.high)
            val yLow = getY(c.low)

            val isBullish = c.close >= c.open
            val paintBody = if (isBullish) paintCandleBull else paintCandleBear
            val paintWick = if (isBullish) paintWickBull else paintWickBear

            // Draw Wick
            canvas.drawLine(cx, yHigh, cx, yLow, paintWick)

            // Draw Body
            val topBody = min(yOpen, yClose)
            val bottomBody = max(yOpen, yClose)
            val bodyHeight = max(2f, bottomBody - topBody)
            canvas.drawRect(cx - halfBody, topBody, cx + halfBody, topBody + bodyHeight, paintBody)

            // Draw Signal Markers (BUY / SELL - Single badge per breakout transition state)
            val sig = if (i < signalSeries.size) signalSeries[i] else 0
            if (sig == 1) { // BUY Signal
                val badgeY = yLow + 24f
                canvas.drawLine(cx, yLow, cx, badgeY, paintWickBull)
                val rect = RectF(cx - 38f, badgeY, cx + 38f, badgeY + 28f)
                canvas.drawRoundRect(rect, 8f, 8f, paintBadgeBuy)
                val paintSignalText = Paint(paintText).apply { textSize = 18f; color = Color.BLACK }
                canvas.drawText("▲ BUY", cx - 28f, badgeY + 20f, paintSignalText)
            } else if (sig == -1) { // SELL Signal
                val badgeY = yHigh - 32f
                canvas.drawLine(cx, yHigh, cx, badgeY + 28f, paintWickBear)
                val rect = RectF(cx - 40f, badgeY, cx + 40f, badgeY + 28f)
                canvas.drawRoundRect(rect, 8f, 8f, paintBadgeSell)
                val paintSignalText = Paint(paintText).apply { textSize = 18f; color = Color.WHITE }
                canvas.drawText("▼ SELL", cx - 30f, badgeY + 20f, paintSignalText)
            }
        }

        // Live Price Line & Badge on Latest Candle
        if (candles.isNotEmpty()) {
            val lastCandle = candles.last()
            val yLast = getY(lastCandle.close)
            val lastIsBull = lastCandle.close >= lastCandle.open
            val paintLine = if (lastIsBull) paintWickBull else paintWickBear
            val paintBg = if (lastIsBull) paintBadgeBuy else paintBadgeSell

            val lastIndex = totalCandles - 1
            val lastCandleX = getX(lastIndex)
            val startX = lastCandleX.coerceIn(paddingLeftPx, paddingLeftPx + chartWidth)

            canvas.drawLine(startX, yLast, paddingLeftPx + chartWidth, yLast, paintLine)

            val priceStr = String.format(Locale.US, "%.2f", lastCandle.close)
            val rectTag = RectF(paddingLeftPx + chartWidth + 4f, yLast - 18f, width.toFloat() - 6f, yLast + 18f)
            canvas.drawRoundRect(rectTag, 6f, 6f, paintBg)

            val paintTagText = Paint(paintText).apply {
                textSize = 20f
                color = if (lastIsBull) Color.BLACK else Color.WHITE
            }
            canvas.drawText(priceStr, paddingLeftPx + chartWidth + 12f, yLast + 6f, paintTagText)
        }

        // Crosshair Handler
        if (isCrosshairActive) {
            val clampedX = touchX.coerceIn(paddingLeftPx, paddingLeftPx + chartWidth)
            val clampedY = touchY.coerceIn(paddingTopPx, paddingTopPx + chartHeight)

            // Draw Crosshair Lines
            canvas.drawLine(paddingLeftPx, clampedY, paddingLeftPx + chartWidth, clampedY, paintCrosshair)
            canvas.drawLine(clampedX, paddingTopPx, clampedX, paddingTopPx + chartHeight, paintCrosshair)

            // Identify selected candle index
            val relativeX = clampedX - paddingLeftPx
            val hoverIndex = (floatStartIndex + (relativeX / candleWidth)).roundToInt().coerceIn(0, totalCandles - 1)
            selectedCandleIndex = hoverIndex

            if (hoverIndex in candles.indices) {
                val hc = candles[hoverIndex]
                val sdf = SimpleDateFormat("MM-dd HH:mm (30m)", Locale.US)
                val timeStr = if (hc.timestamp > 0) sdf.format(Date(hc.timestamp)) else "30m Candle"

                val hoverPrice = currentMaxPrice - ((clampedY - paddingTopPx) / chartHeight) * priceRange
                val priceHoverStr = String.format(Locale.US, "%.2f", hoverPrice)

                // Render Hover Callout Box
                val boxWidth = 340f
                val boxHeight = 110f
                val boxX = (clampedX + 20f).coerceAtMost(width.toFloat() - boxWidth - 10f)
                val boxY = (clampedY - boxHeight - 20f).coerceAtLeast(paddingTopPx + 10f)

                val boxRect = RectF(boxX, boxY, boxX + boxWidth, boxY + boxHeight)
                val paintBox = Paint().apply { color = colorCardBg; style = Paint.Style.FILL }
                val paintBoxBorder = Paint().apply { color = Color.parseColor("#374151"); style = Paint.Style.STROKE; strokeWidth = 2f }

                canvas.drawRoundRect(boxRect, 10f, 10f, paintBox)
                canvas.drawRoundRect(boxRect, 10f, 10f, paintBoxBorder)

                canvas.drawText("$timeStr | $$priceHoverStr", boxX + 14f, boxY + 30f, paintText)
                val ohlcText = String.format(Locale.US, "O:%.2f H:%.2f L:%.2f C:%.2f", hc.open, hc.high, hc.low, hc.close)
                canvas.drawText(ohlcText, boxX + 14f, boxY + 62f, paintTextSec)

                val e8H = if (hoverIndex < ema8HighSeries.size) ema8HighSeries[hoverIndex] else 0.0
                val e34H = if (hoverIndex < ema34HighSeries.size) ema34HighSeries[hoverIndex] else 0.0
                val emaText = String.format(Locale.US, "EMA8:%.2f | EMA34:%.2f", e8H, e34H)
                val paintEmaLegend = Paint(paintTextSec).apply { color = colorYellowChannel; textSize = 18f }
                canvas.drawText(emaText, boxX + 14f, boxY + 90f, paintEmaLegend)
            }
        }
    }
}
