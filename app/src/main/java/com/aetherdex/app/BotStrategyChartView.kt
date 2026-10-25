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

    data class EmaSnapshot(
        val ema8High: Double,
        val ema8Low: Double,
        val ema34High: Double,
        val ema34Low: Double,
        val prevEma8High: Double = 0.0,
        val prevEma8Low: Double = 0.0
    )

    data class FractalLevel(
        val isHigh: Boolean,        // true = Fractal High (Resistance), false = Fractal Low (Support)
        val price: Double,
        val startIndex: Int,        // Candle index where the fractal pivot was formed
        var endIndex: Int = -1,     // Candle index where price breaks through, or -1 if unbroken
        var isBroken: Boolean = false,
        var breakIndex: Int = -1,   // Candle index of the break
        var isChoch: Boolean = false,
        var swingType: String = ""  // "HH", "LH", "HL", "LL"
    )

    enum class BotState { NEUTRAL, IN_BUY, IN_SELL }
    private enum class ChannelState { UNKNOWN, BELOW_OR_INSIDE, FULLY_ABOVE }

    enum class MarketState { UPTREND, DOWNTREND, RANGE }
    var currentMarketState: MarketState = MarketState.RANGE
        private set

    companion object {
        fun formatPriceValue(value: Double): String {
            val absV = abs(value)
            return when {
                absV >= 1000.0 -> String.format(Locale.US, "%,.2f", value)
                absV >= 1.0 -> String.format(Locale.US, "%.4f", value)
                absV >= 0.01 -> String.format(Locale.US, "%.5f", value)
                absV >= 0.0001 -> String.format(Locale.US, "%.6f", value)
                absV > 0.0 -> String.format(Locale.US, "%.8f", value)
                else -> "0.00"
            }
        }

        fun calculateBreakoutProximity(
            ema8High: Double,
            ema8Low: Double,
            ema34High: Double,
            ema34Low: Double,
            prevEma8High: Double = 0.0,
            prevEma8Low: Double = 0.0
        ): Int {
            // Full breakout above Yellow channel
            if (ema8Low > ema34High && ema8High > ema34High) {
                return 100
            }

            val channelHeight = maxOf(ema34High - ema34Low, 1e-8)
            val isSlantingDown = (prevEma8High > 0.0 && ema8High < prevEma8High && ema8Low < prevEma8Low)

            // Downtrend / completely below Yellow Channel
            if (ema8High <= ema34Low) {
                if (isSlantingDown) {
                    // Completely oversold/downtrending without upward momentum
                    return 0
                }
                val distBelow = ema34Low - ema8High
                if (distBelow >= channelHeight) {
                    return 0
                }
                // Curving upwards towards Yellow channel lower boundary
                val upwardProgress = ((channelHeight - distBelow) / channelHeight) * 25.0
                return upwardProgress.toInt().coerceIn(0, 24)
            }

            // Upward Convergence into / through Yellow Channel towards top boundary
            val pHigh = ((ema8High - ema34Low) / channelHeight).coerceIn(0.0, 1.0)
            val pLow = ((ema8Low - ema34Low) / channelHeight).coerceIn(0.0, 1.0)

            val proximity = 25.0 + (pHigh * 25.0) + (pLow * 49.0)
            return proximity.toInt().coerceIn(0, 99)
        }

        fun calculateEMA(data: DoubleArray, period: Int): DoubleArray {
            val n = data.size
            val result = DoubleArray(n)
            if (n == 0) return result

            val alpha = 2.0 / (period + 1.0)
            var ema = data[0]
            result[0] = ema

            for (i in 1 until n) {
                ema = (alpha * data[i]) + ((1.0 - alpha) * ema)
                result[i] = ema
            }
            return result
        }

        fun computeProximityFromCandles(candles: List<Candle>): Int {
            if (candles.size < 35) return 0
            val highs = DoubleArray(candles.size) { candles[it].high }
            val lows = DoubleArray(candles.size) { candles[it].low }
            val ema8H = calculateEMA(highs, 8)
            val ema8L = calculateEMA(lows, 8)
            val ema34H = calculateEMA(highs, 34)
            val ema34L = calculateEMA(lows, 34)
            val lastIdx = candles.size - 1
            val prevIdx = if (lastIdx > 0) lastIdx - 1 else 0
            return calculateBreakoutProximity(
                ema8High = ema8H[lastIdx],
                ema8Low = ema8L[lastIdx],
                ema34High = ema34H[lastIdx],
                ema34Low = ema34L[lastIdx],
                prevEma8High = ema8H[prevIdx],
                prevEma8Low = ema8L[prevIdx]
            )
        }
    }

    var timeframeLabel: String = "30m"

    // Candle data
    var candles: List<Candle> = emptyList()
        set(value) {
            val isNewDataset = field.isEmpty() || (value.isNotEmpty() && field.isNotEmpty() && (abs(value.first().close - field.first().close) > field.first().close * 0.1 || abs(value.size - field.size) > 20))
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

    // 5-Minute Dual-Ribbon Strategy (Combined Ribbon) Series
    private var ema134HighSeries: DoubleArray = DoubleArray(0)
    private var ema134LowSeries: DoubleArray = DoubleArray(0)
    private var kryptEmaSeries: List<DoubleArray> = emptyList() // 8 EMAs: 20, 25, 30, 35, 40, 45, 50, 55
    private var dualRibbonSignals: IntArray = IntArray(0)
    private var dualRibbonSlSeries: DoubleArray = DoubleArray(0)
    private var dualRibbonTpSeries: DoubleArray = DoubleArray(0)
    private var dualRibbonEntrySeries: DoubleArray = DoubleArray(0)

    // Signals: 1 = BUY, -1 = SELL, 0 = NONE
    private var signalSeries: IntArray = IntArray(0)

    // Multi-Strategy Mode Selection
    var activeStrategies: Set<String> = setOf("30m EMA High/Low")
        set(value) {
            field = value
            postInvalidate()
        }

    fun setActiveStrategies(strategies: Collection<String>) {
        activeStrategies = strategies.toSet()
        postInvalidate()
    }

    // Fractal CHoCh (ICT) Structure & Signals
    private var fractalLevels: List<FractalLevel> = emptyList()
    private var chochSignals: IntArray = IntArray(0)
    private var marketSwings: List<FractalLevel> = emptyList()

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
    private val colorCyan = Color.parseColor("#00E5FF")

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

    private val paintSignalLine = Paint().apply {
        color = colorCyan
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintPriceBadge = Paint().apply {
        color = colorCyan
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

    // Fractal CHoCh (ICT) Structure Paints (Green for Bullish Fractal Highs, Red for Bearish Fractal Lows)
    private val paintFractalHigh = Paint().apply {
        color = Color.parseColor("#00E676") // Green for Bullish Fractal High
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintFractalHighDashed = Paint().apply {
        color = Color.parseColor("#00E676") // Green for Bullish Fractal High / CHoCh
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        isAntiAlias = true
    }

    private val paintFractalLow = Paint().apply {
        color = Color.parseColor("#FF5252") // Red for Bearish Fractal Low
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintFractalLowDashed = Paint().apply {
        color = Color.parseColor("#FF5252") // Red for Bearish Fractal Low / CHoCh
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        isAntiAlias = true
    }

    private val paintFractalDot = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintChochBadgeBull = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintChochBadgeBear = Paint().apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintSwingText = Paint().apply {
        textSize = 10f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    private val paintSwingBadgeBg = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintSwingBadgeBorder = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val paintStructureLabel = Paint().apply {
        color = Color.WHITE
        textSize = 18f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
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

    // 5-Minute Dual-Ribbon Paints & Reusable Paths
    private val colorRibbon1 = Color.parseColor("#00E676")
    private val colorRibbon1Fill = Color.parseColor("#1800E676")

    private val paintRibbon1Line = Paint().apply {
        color = colorRibbon1
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintRibbon1Fill = Paint().apply {
        color = colorRibbon1Fill
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Exact Krypt Fast EMAs Colors from Pine Script:
    // MA-1 (#F5EB5D, width 3.2f), MA-2 (#F5B771), MA-3 (#F5B056), MA-4 (#F57B4E),
    // MA-5 (#F56D58), MA-6 (#F57D51), MA-7 (#F55151), MA-8 (#AA2707)
    private val kryptColors = intArrayOf(
        Color.parseColor("#F5EB5D"),
        Color.parseColor("#F5B771"),
        Color.parseColor("#F5B056"),
        Color.parseColor("#F57B4E"),
        Color.parseColor("#F56D58"),
        Color.parseColor("#F57D51"),
        Color.parseColor("#F55151"),
        Color.parseColor("#AA2707")
    )

    private val kryptPaints = kryptColors.mapIndexed { idx, col ->
        Paint().apply {
            color = col
            strokeWidth = if (idx == 0) 3.2f else 2.2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }

    private val pathRibbon1Channel = Path()
    private val pathRibbon1Upper = Path()
    private val pathRibbon1Lower = Path()
    private val pathKryptEmas = Array(8) { Path() }

    // 5-Minute Dual-Ribbon SL/TP Risk Management Paints
    private val paintRibbonSl = Paint().apply {
        color = Color.parseColor("#FF5252") // Red for Stop Loss
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        isAntiAlias = true
    }

    private val paintRibbonTp = Paint().apply {
        color = Color.parseColor("#00E676") // Green for Take Profit (1:2 RRR)
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        isAntiAlias = true
    }

    private val paintRiskPillBg = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintRiskPillBorder = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val paintRiskPillText = Paint().apply {
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

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

        val opens = DoubleArray(n) { candles[it].open }
        val highs = DoubleArray(n) { candles[it].high }
        val lows = DoubleArray(n) { candles[it].low }
        val closes = DoubleArray(n) { candles[it].close }

        ema8HighSeries = calculateEMA(highs, 8)
        ema8LowSeries = calculateEMA(lows, 8)
        ema34HighSeries = calculateEMA(highs, 34)
        ema34LowSeries = calculateEMA(lows, 34)
        ema200Series = calculateEMA(closes, 200)
        ema233Series = calculateEMA(closes, 233)

        // Calculate 5-Minute Dual-Ribbon (Combined Ribbon)
        ema134HighSeries = calculateEMA(highs, 134)
        ema134LowSeries = calculateEMA(lows, 134)
        val kryptLengths = intArrayOf(20, 25, 30, 35, 40, 45, 50, 55)
        kryptEmaSeries = kryptLengths.map { calculateEMA(closes, it) }

        dualRibbonSignals = IntArray(n)
        dualRibbonSlSeries = DoubleArray(n)
        dualRibbonTpSeries = DoubleArray(n)
        dualRibbonEntrySeries = DoubleArray(n)

        var lastLockedDirection = 0 // 0 = None, 1 = Locked in BUY, -1 = Locked in SELL (Strict alternating signals)
        var hasPulledBackRibbon = false

        if (n > 0 && kryptEmaSeries.size == 8) {
            val ma1 = kryptEmaSeries[0] // EMA 20 (yellow reference line)
            val ma8 = kryptEmaSeries[7] // EMA 55 (slowest ribbon EMA)

            for (i in 0 until n) {
                val e134H = ema134HighSeries[i]
                val e134L = ema134LowSeries[i]
                val e20 = ma1[i]
                val e55 = ma8[i]
                val o = opens[i]
                val h = highs[i]
                val l = lows[i]
                val c = closes[i]

                val ribbonTop = max(e20, e55)
                val ribbonBottom = min(e20, e55)
                val bodyTop = max(o, c)
                val bodyBottom = min(o, c)

                // Step 1: EMA 20 and price cross and trade above/below both 134 EMA green bands
                val isAboveGreenBand = (e20 > e134H && c > e134H)
                val isBelowGreenBand = (e20 < e134L && c < e134L)

                // BUY Signal Check: STRICTLY BLOCKED if already locked in BUY (lastLockedDirection == 1)
                if (lastLockedDirection != 1) {
                    if (isAboveGreenBand) {
                        if (bodyTop >= ribbonBottom) {
                            if (l <= e20) {
                                hasPulledBackRibbon = true
                            }

                            val isGreen = c > o
                            val closesAboveYellow = c > e20

                            if (hasPulledBackRibbon && isGreen && closesAboveYellow) {
                                dualRibbonSignals[i] = 1
                                lastLockedDirection = 1 // STRICT SINGLE-SIGNAL LOCK: Blocks all subsequent BUY signals until opposite SELL!
                                hasPulledBackRibbon = false

                                // Stop Loss sits snugly right against the 134 EMA Low green band boundary
                                var sl = e134L
                                if (sl >= c) {
                                    sl = c * 0.999
                                }
                                val risk = max(c - sl, c * 0.001)
                                val tp = c + (risk * 2.0) // Exact 1:2 RRR

                                dualRibbonEntrySeries[i] = c
                                dualRibbonSlSeries[i] = sl
                                dualRibbonTpSeries[i] = tp
                            }
                        } else {
                            hasPulledBackRibbon = false
                        }
                    }
                }

                // SELL Signal Check: STRICTLY BLOCKED if already locked in SELL (lastLockedDirection == -1)
                if (lastLockedDirection != -1) {
                    if (isBelowGreenBand) {
                        if (bodyBottom <= ribbonTop) {
                            if (h >= e20) {
                                hasPulledBackRibbon = true
                            }

                            val isRed = c < o
                            val closesBelowYellow = c < e20

                            if (hasPulledBackRibbon && isRed && closesBelowYellow) {
                                dualRibbonSignals[i] = -1
                                lastLockedDirection = -1 // STRICT SINGLE-SIGNAL LOCK: Blocks all subsequent SELL signals until opposite BUY!
                                hasPulledBackRibbon = false

                                // Stop Loss sits snugly right against the 134 EMA High green band boundary
                                var sl = e134H
                                if (sl <= c) {
                                    sl = c * 1.001
                                }
                                val risk = max(sl - c, c * 0.001)
                                val tp = c - (risk * 2.0) // Exact 1:2 RRR

                                dualRibbonEntrySeries[i] = c
                                dualRibbonSlSeries[i] = sl
                                dualRibbonTpSeries[i] = tp
                            }
                        } else {
                            hasPulledBackRibbon = false
                        }
                    }
                }
            }
        }

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
                // A new ▲ BUY signal can ONLY trigger if unlocked by a previous ▼ SELL (or initial)
                if (currentPositionState != BotState.IN_BUY) {
                    signalSeries[i] = 1 // Render a single ▲ BUY badge ONCE
                    currentPositionState = BotState.IN_BUY
                }
            } else if (isWhiteBelowYellow) {
                // A new ▼ SELL signal can ONLY trigger if unlocked by a previous ▲ BUY (or initial)
                if (currentPositionState != BotState.IN_SELL) {
                    signalSeries[i] = -1 // Render a single ▼ SELL badge ONCE
                    currentPositionState = BotState.IN_SELL
                }
            }
            // State is strictly preserved when lines enter, dip into, or stay in the Yellow Channel
            // (no reset to NEUTRAL, guaranteeing no duplicate signals until opposite breakout)
        }

        val snap = getLatestEmaSnapshot()
        if (snap != null) {
            activeProximity = calculateBreakoutProximity(
                snap.ema8High,
                snap.ema8Low,
                snap.ema34High,
                snap.ema34Low,
                snap.prevEma8High,
                snap.prevEma8Low
            )
        }

        // Calculate Ethan's ICT Fractal Highs, Lows and CHoCh structure
        calculateFractalsAndChoch()
    }

    private fun calculateFractalsAndChoch() {
        val n = candles.size
        if (n < 4) {
            fractalLevels = emptyList()
            marketSwings = emptyList()
            chochSignals = IntArray(n)
            currentMarketState = MarketState.RANGE
            return
        }

        val allCandidates = mutableListOf<FractalLevel>()

        // 1. Identify valid ICT Swing Highs and Swing Lows (Pivots)
        // A swing high is a local peak with lower highs on both sides.
        // A swing low is a local trough with higher lows on both sides.
        for (i in 1 until (n - 1)) {
            val sc = candles[i]
            val prev = candles[i - 1]
            val next = candles[i + 1]

            // 3-bar basic pivot condition
            val is3BarHigh = sc.high > prev.high && sc.high >= next.high
            val is3BarLow = sc.low < prev.low && sc.low <= next.low

            // 5-bar confirmation when available for superior structural reliability
            val is5BarHigh = if (i >= 2 && i < n - 2) {
                sc.high >= candles[i - 2].high && is3BarHigh && sc.high >= candles[i + 2].high
            } else is3BarHigh

            val is5BarLow = if (i >= 2 && i < n - 2) {
                sc.low <= candles[i - 2].low && is3BarLow && sc.low <= candles[i + 2].low
            } else is3BarLow

            if (is5BarHigh) {
                var breakIdx = -1
                for (k in (i + 1) until n) {
                    // Valid body close breaks the level
                    if (candles[k].close > sc.high) {
                        breakIdx = k
                        break
                    }
                }
                allCandidates.add(
                    FractalLevel(
                        isHigh = true,
                        price = sc.high,
                        startIndex = i,
                        endIndex = if (breakIdx != -1) breakIdx else (n - 1),
                        isBroken = (breakIdx != -1),
                        breakIndex = breakIdx,
                        isChoch = false
                    )
                )
            }

            if (is5BarLow) {
                var breakIdx = -1
                for (k in (i + 1) until n) {
                    // Valid body close breaks the level
                    if (candles[k].close < sc.low) {
                        breakIdx = k
                        break
                    }
                }
                allCandidates.add(
                    FractalLevel(
                        isHigh = false,
                        price = sc.low,
                        startIndex = i,
                        endIndex = if (breakIdx != -1) breakIdx else (n - 1),
                        isBroken = (breakIdx != -1),
                        breakIndex = breakIdx,
                        isChoch = false
                    )
                )
            }
        }

        // 2. Classify confirmed Fractal Highs and Fractal Lows (HH, HL, LH, LL)
        val highCandidates = allCandidates.filter { it.isHigh }.sortedBy { it.startIndex }
        var prevHighPrice: Double? = null
        for (h in highCandidates) {
            h.swingType = if (prevHighPrice == null) {
                "HH"
            } else if (h.price > prevHighPrice) {
                "HH" // HH (Higher High): When a new Fractal High exceeds the previous Fractal High
            } else {
                "LH" // LH (Lower High): When a new Fractal High is lower than the previous Fractal High
            }
            prevHighPrice = h.price
        }

        val lowCandidates = allCandidates.filter { !it.isHigh }.sortedBy { it.startIndex }
        var prevLowPrice: Double? = null
        for (l in lowCandidates) {
            l.swingType = if (prevLowPrice == null) {
                "LL"
            } else if (l.price > prevLowPrice) {
                "HL" // HL (Higher Low): When a new Fractal Low is higher than the previous Fractal Low (Uptrend)
            } else {
                "LL" // LL (Lower Low): When a new Fractal Low drops below the previous Fractal Low (Downtrend)
            }
            prevLowPrice = l.price
        }

        // 3. Trend & Range Contextual Rules based on HH/HL and LH/LL sequence
        val lastHigh = highCandidates.lastOrNull()
        val lastLow = lowCandidates.lastOrNull()
        currentMarketState = when {
            lastHigh != null && lastLow != null && lastHigh.swingType == "HH" && lastLow.swingType == "HL" ->
                MarketState.UPTREND // Uptrend: Continuous HH and HL formation
            lastHigh != null && lastLow != null && lastHigh.swingType == "LH" && lastLow.swingType == "LL" ->
                MarketState.DOWNTREND // Downtrend: Continuous LH and LL formation
            else ->
                MarketState.RANGE // Range / Sideways: Swings overlap without creating new highs or lows
        }

        marketSwings = allCandidates.sortedBy { it.startIndex }

        // 4. Filter and curate active structural boundary lines & clean terminated rays
        val unbrokenHighs = allCandidates.filter { it.isHigh && !it.isBroken }
        val unbrokenLows = allCandidates.filter { !it.isHigh && !it.isBroken }

        // Deduplicate unbroken highs within 0.15% price proximity (keep higher price)
        val filteredUnbrokenHighs = mutableListOf<FractalLevel>()
        for (h in unbrokenHighs.sortedByDescending { it.price }) {
            if (filteredUnbrokenHighs.none { abs(it.price - h.price) / h.price < 0.0015 }) {
                filteredUnbrokenHighs.add(h)
            }
        }

        // Deduplicate unbroken lows within 0.15% price proximity (keep lower price)
        val filteredUnbrokenLows = mutableListOf<FractalLevel>()
        for (l in unbrokenLows.sortedBy { it.price }) {
            if (filteredUnbrokenLows.none { abs(it.price - l.price) / l.price < 0.0015 }) {
                filteredUnbrokenLows.add(l)
            }
        }

        // Broken levels: keep recent significant levels terminating at breakout bar
        val filteredBrokenLevels = allCandidates
            .filter { it.isBroken && (it.breakIndex - it.startIndex >= 2) }
            .sortedByDescending { it.breakIndex }
            .take(15) // Limit historical broken lines to recent 15 to keep chart clean and uncluttered

        val finalLevels = mutableListOf<FractalLevel>()
        finalLevels.addAll(filteredBrokenLevels)
        finalLevels.addAll(filteredUnbrokenHighs)
        finalLevels.addAll(filteredUnbrokenLows)

        finalLevels.sortBy { it.startIndex }
        fractalLevels = finalLevels
        chochSignals = IntArray(n) // Completely suppress CHoCh signal badges
    }

    var activeProximity: Int = 0
        private set

    fun getLatestEmaSnapshot(): EmaSnapshot? {
        val n = candles.size
        if (n == 0 || ema8HighSeries.size < n || ema8LowSeries.size < n || ema34HighSeries.size < n || ema34LowSeries.size < n) {
            return null
        }
        val idx = n - 1
        val prevIdx = if (idx > 0) idx - 1 else 0
        return EmaSnapshot(
            ema8High = ema8HighSeries[idx],
            ema8Low = ema8LowSeries[idx],
            ema34High = ema34HighSeries[idx],
            ema34Low = ema34LowSeries[idx],
            prevEma8High = if (idx > 0) ema8HighSeries[prevIdx] else 0.0,
            prevEma8Low = if (idx > 0) ema8LowSeries[prevIdx] else 0.0
        )
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

        val showEmaStrategy = activeStrategies.any {
            it.equals("30m EMA High/Low", ignoreCase = true) ||
            it.contains("EMA High/Low", ignoreCase = true) ||
            it.contains("EMA Channel", ignoreCase = true)
        }
        val showDualRibbon = activeStrategies.any {
            it.contains("Dual-Ribbon", ignoreCase = true) ||
            it.contains("Combined Ribbon", ignoreCase = true) ||
            it.contains("Krypt", ignoreCase = true) ||
            it.contains("Dual Ribbon", ignoreCase = true)
        }
        val showFractalChoch = activeStrategies.any {
            it.contains("Fractal", ignoreCase = true) ||
            it.contains("CHoCh", ignoreCase = true) ||
            it.contains("MSS", ignoreCase = true)
        }

        // Compute Min & Max price in visible range
        var minPrice = Double.MAX_VALUE
        var maxPrice = Double.MIN_VALUE

        for (i in calcStartIndex..calcEndIndex) {
            val c = candles[i]
            if (c.low < minPrice) minPrice = c.low
            if (c.high > maxPrice) maxPrice = c.high

            if (showEmaStrategy) {
                if (i < ema8LowSeries.size && ema8LowSeries[i] > 0 && ema8LowSeries[i] < minPrice) minPrice = ema8LowSeries[i]
                if (i < ema8HighSeries.size && ema8HighSeries[i] > maxPrice) maxPrice = ema8HighSeries[i]
                if (i < ema34LowSeries.size && ema34LowSeries[i] > 0 && ema34LowSeries[i] < minPrice) minPrice = ema34LowSeries[i]
                if (i < ema34HighSeries.size && ema34HighSeries[i] > maxPrice) maxPrice = ema34HighSeries[i]
                if (i < ema200Series.size && ema200Series[i] > 0 && ema200Series[i] < minPrice) minPrice = ema200Series[i]
                if (i < ema200Series.size && ema200Series[i] > maxPrice) maxPrice = ema200Series[i]
                if (i < ema233Series.size && ema233Series[i] > 0 && ema233Series[i] < minPrice) minPrice = ema233Series[i]
                if (i < ema233Series.size && ema233Series[i] > maxPrice) maxPrice = ema233Series[i]
            }

            if (showDualRibbon) {
                if (i < ema134LowSeries.size && ema134LowSeries[i] > 0 && ema134LowSeries[i] < minPrice) minPrice = ema134LowSeries[i]
                if (i < ema134HighSeries.size && ema134HighSeries[i] > maxPrice) maxPrice = ema134HighSeries[i]
                if (kryptEmaSeries.isNotEmpty()) {
                    val ma1 = kryptEmaSeries[0]
                    val ma8 = kryptEmaSeries[7]
                    if (i < ma1.size && ma1[i] > 0) {
                        if (ma1[i] < minPrice) minPrice = ma1[i]
                        if (ma1[i] > maxPrice) maxPrice = ma1[i]
                    }
                    if (i < ma8.size && ma8[i] > 0) {
                        if (ma8[i] < minPrice) minPrice = ma8[i]
                        if (ma8[i] > maxPrice) maxPrice = ma8[i]
                    }
                }
            }
        }

        if (showFractalChoch && candles.isNotEmpty()) {
            val visibleLevels = fractalLevels.filter { lvl ->
                lvl.startIndex <= calcEndIndex && (lvl.endIndex >= calcStartIndex || !lvl.isBroken)
            }
            val candleSpan = maxOf(maxPrice - minPrice, 1.0)
            for (lvl in visibleLevels) {
                if (lvl.price in (minPrice - candleSpan * 0.35)..(maxPrice + candleSpan * 0.35)) {
                    if (lvl.price < minPrice) minPrice = lvl.price
                    if (lvl.price > maxPrice) maxPrice = lvl.price
                }
            }
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
            val pText = formatPriceValue(pValue)
            canvas.drawText(pText, paddingLeftPx + chartWidth + 12f, py + 8f, paintTextSec)
        }

        // ── Visual Strategy Mode Indicator Overlay (Top-Left of Chart) ──
        var legendOffsetX = paddingLeftPx + 14f
        val legendY = paddingTopPx + 22f

        if (showEmaStrategy) {
            val pillText = "30M EMA RIBBON"
            val textWidth = paintStructureLabel.measureText(pillText)
            val pillWidth = textWidth + 26f
            val pillHeight = 22f
            val pillRect = RectF(legendOffsetX, legendY - 14f, legendOffsetX + pillWidth, legendY - 14f + pillHeight)
            val paintPillBg = Paint().apply {
                color = Color.parseColor("#E6151921")
                style = Paint.Style.FILL
            }
            val paintPillBorder = Paint().apply {
                color = colorYellowChannel
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            canvas.drawRoundRect(pillRect, 6f, 6f, paintPillBg)
            canvas.drawRoundRect(pillRect, 6f, 6f, paintPillBorder)

            val paintDot = Paint().apply { color = colorYellowChannel; style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawCircle(legendOffsetX + 10f, legendY - 3f, 3.5f, paintDot)

            val paintLbl = Paint(paintStructureLabel).apply {
                textSize = 11f
                color = Color.WHITE
            }
            canvas.drawText(pillText, legendOffsetX + 18f, legendY + 1f, paintLbl)
            legendOffsetX += pillWidth + 8f
        }

        if (showDualRibbon) {
            val pillText = "5M DUAL-RIBBON (134 GREEN + KRYPT 20-55)"
            val textWidth = paintStructureLabel.measureText(pillText)
            val pillWidth = textWidth + 26f
            val pillHeight = 22f
            val pillRect = RectF(legendOffsetX, legendY - 14f, legendOffsetX + pillWidth, legendY - 14f + pillHeight)
            val paintPillBg = Paint().apply {
                color = Color.parseColor("#E6151921")
                style = Paint.Style.FILL
            }
            val paintPillBorder = Paint().apply {
                color = Color.parseColor("#00E676")
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            canvas.drawRoundRect(pillRect, 6f, 6f, paintPillBg)
            canvas.drawRoundRect(pillRect, 6f, 6f, paintPillBorder)

            val paintDot = Paint().apply { color = Color.parseColor("#00E676"); style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawCircle(legendOffsetX + 10f, legendY - 3f, 3.5f, paintDot)

            val paintLbl = Paint(paintStructureLabel).apply {
                textSize = 11f
                color = Color.WHITE
            }
            canvas.drawText(pillText, legendOffsetX + 18f, legendY + 1f, paintLbl)
            legendOffsetX += pillWidth + 8f
        }

        if (showFractalChoch) {
            val (marketStateText, stateColor) = when (currentMarketState) {
                MarketState.UPTREND -> Pair("ICT: UPTREND (HH/HL)", Color.parseColor("#00E676"))
                MarketState.DOWNTREND -> Pair("ICT: DOWNTREND (LH/LL)", Color.parseColor("#FF5252"))
                MarketState.RANGE -> Pair("ICT: RANGE / SIDEWAYS", Color.parseColor("#FFB300"))
            }
            val textWidth = paintStructureLabel.measureText(marketStateText)
            val pillWidth = textWidth + 26f
            val pillHeight = 22f
            val pillRect = RectF(legendOffsetX, legendY - 14f, legendOffsetX + pillWidth, legendY - 14f + pillHeight)
            val paintPillBg = Paint().apply {
                color = Color.parseColor("#E6151921")
                style = Paint.Style.FILL
            }
            val paintPillBorder = Paint().apply {
                color = stateColor
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            canvas.drawRoundRect(pillRect, 6f, 6f, paintPillBg)
            canvas.drawRoundRect(pillRect, 6f, 6f, paintPillBorder)

            val paintDot = Paint().apply { color = stateColor; style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawCircle(legendOffsetX + 10f, legendY - 3f, 3.5f, paintDot)

            val paintLbl = Paint(paintStructureLabel).apply {
                textSize = 11f
                color = Color.WHITE
            }
            canvas.drawText(marketStateText, legendOffsetX + 18f, legendY + 1f, paintLbl)
            legendOffsetX += pillWidth + 8f
        }

        // Reset & Build Paths for EMA Channels & Trend Lines (if EMA strategy is active)
        if (showEmaStrategy) {
            pathWhiteUpper.reset()
            pathWhiteLower.reset()
            pathWhiteChannel.reset()

            pathYellowUpper.reset()
            pathYellowLower.reset()
            pathYellowChannel.reset()

            pathEma200.reset()
            pathEma233.reset()

            var firstPointWhite = true
            var firstPointYellow = true
            var firstPoint200 = true
            var firstPoint233 = true

            for (i in renderStartIndex..renderEndIndex) {
                val cx = getX(i)

                if (i < ema8HighSeries.size && ema8HighSeries[i] > 0) {
                    val y8H = getY(ema8HighSeries[i])
                    val y8L = getY(ema8LowSeries[i])
                    if (firstPointWhite) {
                        pathWhiteUpper.moveTo(cx, y8H)
                        pathWhiteLower.moveTo(cx, y8L)
                        firstPointWhite = false
                    } else {
                        pathWhiteUpper.lineTo(cx, y8H)
                        pathWhiteLower.lineTo(cx, y8L)
                    }
                }

                if (i < ema34HighSeries.size && ema34HighSeries[i] > 0) {
                    val y34H = getY(ema34HighSeries[i])
                    val y34L = getY(ema34LowSeries[i])
                    if (firstPointYellow) {
                        pathYellowUpper.moveTo(cx, y34H)
                        pathYellowLower.moveTo(cx, y34L)
                        firstPointYellow = false
                    } else {
                        pathYellowUpper.lineTo(cx, y34H)
                        pathYellowLower.lineTo(cx, y34L)
                    }
                }

                if (i < ema200Series.size && ema200Series[i] > 0) {
                    val y200 = getY(ema200Series[i])
                    if (firstPoint200) {
                        pathEma200.moveTo(cx, y200)
                        firstPoint200 = false
                    } else {
                        pathEma200.lineTo(cx, y200)
                    }
                }

                if (i < ema233Series.size && ema233Series[i] > 0) {
                    val y233 = getY(ema233Series[i])
                    if (firstPoint233) {
                        pathEma233.moveTo(cx, y233)
                        firstPoint233 = false
                    } else {
                        pathEma233.lineTo(cx, y233)
                    }
                }
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
        }

        // Reset & Build Paths for 5-Minute Dual-Ribbon Strategy (134 Green Band + 8 Krypt EMAs)
        if (showDualRibbon) {
            pathRibbon1Upper.reset()
            pathRibbon1Lower.reset()
            pathRibbon1Channel.reset()
            for (p in pathKryptEmas) p.reset()

            var firstPointR1 = true
            val firstPointKrypt = BooleanArray(8) { true }

            for (i in renderStartIndex..renderEndIndex) {
                val cx = getX(i)

                if (i < ema134HighSeries.size && ema134HighSeries[i] > 0) {
                    val y134H = getY(ema134HighSeries[i])
                    val y134L = getY(ema134LowSeries[i])
                    if (firstPointR1) {
                        pathRibbon1Upper.moveTo(cx, y134H)
                        pathRibbon1Lower.moveTo(cx, y134L)
                        firstPointR1 = false
                    } else {
                        pathRibbon1Upper.lineTo(cx, y134H)
                        pathRibbon1Lower.lineTo(cx, y134L)
                    }
                }

                for (k in 0 until minOf(8, kryptEmaSeries.size)) {
                    val series = kryptEmaSeries[k]
                    if (i < series.size && series[i] > 0) {
                        val yKrypt = getY(series[i])
                        if (firstPointKrypt[k]) {
                            pathKryptEmas[k].moveTo(cx, yKrypt)
                            firstPointKrypt[k] = false
                        } else {
                            pathKryptEmas[k].lineTo(cx, yKrypt)
                        }
                    }
                }
            }

            // Construct Ribbon 1 Fill
            for (i in renderStartIndex..renderEndIndex) {
                if (i < ema134HighSeries.size && ema134HighSeries[i] > 0) {
                    val cx = getX(i)
                    val y134H = getY(ema134HighSeries[i])
                    if (i == renderStartIndex) pathRibbon1Channel.moveTo(cx, y134H) else pathRibbon1Channel.lineTo(cx, y134H)
                }
            }
            for (i in renderEndIndex downTo renderStartIndex) {
                if (i < ema134LowSeries.size && ema134LowSeries[i] > 0) {
                    val cx = getX(i)
                    val y134L = getY(ema134LowSeries[i])
                    pathRibbon1Channel.lineTo(cx, y134L)
                }
            }
            pathRibbon1Channel.close()

            // Draw Ribbon 1 Fill & Thick Green Lines
            canvas.drawPath(pathRibbon1Channel, paintRibbon1Fill)
            canvas.drawPath(pathRibbon1Upper, paintRibbon1Line)
            canvas.drawPath(pathRibbon1Lower, paintRibbon1Line)

            // Draw Ribbon 2 Krypt EMAs
            for (k in 0 until minOf(8, kryptEmaSeries.size)) {
                canvas.drawPath(pathKryptEmas[k], kryptPaints[k])
            }
        }

        // Draw Fractal CHoCh (ICT) Visual Structure Lines (Horizontal structure lines across candles)
        if (showFractalChoch) {
            val visibleLevels = fractalLevels.filter { lvl ->
                lvl.startIndex <= renderEndIndex && (lvl.endIndex >= renderStartIndex || !lvl.isBroken)
            }

            for (lvl in visibleLevels) {
                val y = getY(lvl.price)
                val startX = getX(lvl.startIndex)
                val endX = if (lvl.isBroken && lvl.breakIndex >= 0) {
                    getX(lvl.breakIndex)
                } else {
                    paddingLeftPx + chartWidth
                }

                if (endX <= startX) continue

                val themeColor = if (lvl.isHigh) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
                val paintLine = if (lvl.isHigh) paintFractalHighDashed else paintFractalLowDashed

                // 1. Horizontal structure line extending to termination candle or right edge
                canvas.drawLine(startX, y, endX, y, paintLine)

                // 2. Anchor Dot at origin
                paintFractalDot.color = themeColor
                canvas.drawCircle(startX, y, 4.5f, paintFractalDot)

                // 3. Termination / Breakpoint rendering
                if (lvl.isBroken && lvl.breakIndex >= 0) {
                    // Clean vertical termination notch strictly at the breakout bar
                    canvas.drawLine(endX, y - 6f, endX, y + 6f, paintLine)
                } else {
                    // Unbroken active structure level extending continuously across into the future (Persistent Ray)
                    val labelText = if (lvl.isHigh) "Fractal High: ${formatPriceValue(lvl.price)}" else "Fractal Low: ${formatPriceValue(lvl.price)}"
                    val pillWidth = 145f
                    val pillHeight = 20f
                    val pillX = (paddingLeftPx + chartWidth - pillWidth - 6f).coerceAtLeast(startX + 10f)
                    val pillY = if (lvl.isHigh) y - 22f else y + 4f
                    val pillRect = RectF(pillX, pillY, pillX + pillWidth, pillY + pillHeight)
                    val paintLabelBg = Paint().apply {
                        color = Color.parseColor("#CC151921")
                        style = Paint.Style.FILL
                    }
                    val paintLabelBorder = Paint().apply {
                        color = themeColor
                        style = Paint.Style.STROKE
                        strokeWidth = 1.5f
                    }
                    canvas.drawRoundRect(pillRect, 4f, 4f, paintLabelBg)
                    canvas.drawRoundRect(pillRect, 4f, 4f, paintLabelBorder)

                    val paintTxt = Paint(paintStructureLabel).apply {
                        textSize = 12f
                        color = themeColor
                    }
                    canvas.drawText(labelText, pillX + 6f, pillY + 15f, paintTxt)
                }
            }

            // Draw Market Structure Swing Badges (HH, HL, LH, LL) at corresponding swing points
            val visibleSwings = marketSwings.filter { it.startIndex in renderStartIndex..renderEndIndex }
            for (sw in visibleSwings) {
                val swingText = sw.swingType
                if (swingText.isEmpty()) continue

                val cx = getX(sw.startIndex)
                val isBullish = (swingText == "HH" || swingText == "HL")
                val themeColor = if (isBullish) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
                val badgeBgColor = if (isBullish) Color.parseColor("#E60D2517") else Color.parseColor("#E6290D0D")

                val badgeWidth = 28f
                val badgeHeight = 16f
                val halfBadgeW = badgeWidth / 2f

                val badgeTop = if (sw.isHigh) {
                    val yCandleHigh = getY(candles[sw.startIndex].high)
                    yCandleHigh - badgeHeight - 6f
                } else {
                    val yCandleLow = getY(candles[sw.startIndex].low)
                    yCandleLow + 6f
                }

                val badgeRect = RectF(cx - halfBadgeW, badgeTop, cx + halfBadgeW, badgeTop + badgeHeight)

                paintSwingBadgeBg.color = badgeBgColor
                canvas.drawRoundRect(badgeRect, 4f, 4f, paintSwingBadgeBg)

                paintSwingBadgeBorder.color = themeColor
                canvas.drawRoundRect(badgeRect, 4f, 4f, paintSwingBadgeBorder)

                paintSwingText.color = themeColor
                val textW = paintSwingText.measureText(swingText)
                val textX = cx - (textW / 2f)
                val textY = badgeTop + badgeHeight - 3.5f
                canvas.drawText(swingText, textX, textY, paintSwingText)
            }
        }

        // Draw Candlesticks & Signal Badges
        val bodyWidth = if (candleWidth <= 2.2f) 1f else max(1f, candleWidth * 0.72f)
        val halfBody = bodyWidth / 2f
        val wickWidth = if (candleWidth <= 3f) 1f else if (candleWidth <= 6f) 1.5f else 2.5f
        paintWickBull.strokeWidth = wickWidth
        paintWickBear.strokeWidth = wickWidth

        val lastDualRibbonSigIdx = if (showDualRibbon) dualRibbonSignals.indexOfLast { it != 0 } else -1

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

            // Draw Strategy Signal Badges (BUY / SELL)
            val sig = if (showEmaStrategy && i < signalSeries.size && signalSeries[i] != 0) {
                signalSeries[i]
            } else if (showDualRibbon && i < dualRibbonSignals.size) {
                dualRibbonSignals[i]
            } else 0

            if (sig == 1) { // BUY Signal
                val badgeY = yLow + 24f
                canvas.drawLine(cx, yLow, cx, badgeY, paintSignalLine)
                val rect = RectF(cx - 38f, badgeY, cx + 38f, badgeY + 28f)
                canvas.drawRoundRect(rect, 8f, 8f, paintBadgeBuy)
                val paintSignalText = Paint(paintText).apply { textSize = 18f; color = Color.BLACK }
                canvas.drawText("▲ BUY", cx - 28f, badgeY + 20f, paintSignalText)
            } else if (sig == -1) { // SELL Signal
                val badgeY = yHigh - 32f
                canvas.drawLine(cx, yHigh, cx, badgeY + 28f, paintSignalLine)
                val rect = RectF(cx - 40f, badgeY, cx + 40f, badgeY + 28f)
                canvas.drawRoundRect(rect, 8f, 8f, paintBadgeSell)
                val paintSignalText = Paint(paintText).apply { textSize = 18f; color = Color.WHITE }
                canvas.drawText("▼ SELL", cx - 30f, badgeY + 20f, paintSignalText)
            }
        }

        // Render Clean Active SL / TP Risk Management for Dual-Ribbon (Only for latest active signal, avoiding historical clutter)
        if (showDualRibbon && lastDualRibbonSigIdx >= 0 && lastDualRibbonSigIdx < dualRibbonSlSeries.size) {
            val lastSig = dualRibbonSignals[lastDualRibbonSigIdx]
            val sl = dualRibbonSlSeries[lastDualRibbonSigIdx]
            val tp = dualRibbonTpSeries[lastDualRibbonSigIdx]

            if (sl > 0.0 && tp > 0.0) {
                val ySl = getY(sl)
                val yTp = getY(tp)
                val startX = max(paddingLeftPx, getX(lastDualRibbonSigIdx))
                val endX = paddingLeftPx + chartWidth

                if (endX > startX) {
                    if (lastSig == 1) { // Active BUY Position
                        // TP Target line & pill badge (Green)
                        canvas.drawLine(startX, yTp, endX, yTp, paintRibbonTp)
                        val tpLabel = "TP " + formatPriceValue(tp) + " (1:2)"
                        val tpW = paintRiskPillText.measureText(tpLabel) + 16f
                        val tpRect = RectF(endX - tpW, yTp - 14f, endX - 2f, yTp + 5f)
                        paintRiskPillBg.color = Color.parseColor("#E60D2517")
                        paintRiskPillBorder.color = Color.parseColor("#00E676")
                        canvas.drawRoundRect(tpRect, 4f, 4f, paintRiskPillBg)
                        canvas.drawRoundRect(tpRect, 4f, 4f, paintRiskPillBorder)
                        paintRiskPillText.color = Color.parseColor("#00E676")
                        canvas.drawText(tpLabel, endX - tpW + 8f, yTp - 1f, paintRiskPillText)

                        // SL Stop Loss line & pill badge (Red)
                        canvas.drawLine(startX, ySl, endX, ySl, paintRibbonSl)
                        val slLabel = "SL " + formatPriceValue(sl)
                        val slW = paintRiskPillText.measureText(slLabel) + 16f
                        val slRect = RectF(endX - slW, ySl - 5f, endX - 2f, ySl + 14f)
                        paintRiskPillBg.color = Color.parseColor("#E6290D0D")
                        paintRiskPillBorder.color = Color.parseColor("#FF5252")
                        canvas.drawRoundRect(slRect, 4f, 4f, paintRiskPillBg)
                        canvas.drawRoundRect(slRect, 4f, 4f, paintRiskPillBorder)
                        paintRiskPillText.color = Color.parseColor("#FF5252")
                        canvas.drawText(slLabel, endX - slW + 8f, ySl + 8f, paintRiskPillText)
                    } else if (lastSig == -1) { // Active SELL Position
                        // SL Stop Loss line & pill badge (Red, above price for Short)
                        canvas.drawLine(startX, ySl, endX, ySl, paintRibbonSl)
                        val slLabel = "SL " + formatPriceValue(sl)
                        val slW = paintRiskPillText.measureText(slLabel) + 16f
                        val slRect = RectF(endX - slW, ySl - 14f, endX - 2f, ySl + 5f)
                        paintRiskPillBg.color = Color.parseColor("#E6290D0D")
                        paintRiskPillBorder.color = Color.parseColor("#FF5252")
                        canvas.drawRoundRect(slRect, 4f, 4f, paintRiskPillBg)
                        canvas.drawRoundRect(slRect, 4f, 4f, paintRiskPillBorder)
                        paintRiskPillText.color = Color.parseColor("#FF5252")
                        canvas.drawText(slLabel, endX - slW + 8f, ySl - 1f, paintRiskPillText)

                        // TP Target line & pill badge (Green, below price for Short)
                        canvas.drawLine(startX, yTp, endX, yTp, paintRibbonTp)
                        val tpLabel = "TP " + formatPriceValue(tp) + " (1:2)"
                        val tpW = paintRiskPillText.measureText(tpLabel) + 16f
                        val tpRect = RectF(endX - tpW, yTp - 5f, endX - 2f, yTp + 14f)
                        paintRiskPillBg.color = Color.parseColor("#E60D2517")
                        paintRiskPillBorder.color = Color.parseColor("#00E676")
                        canvas.drawRoundRect(tpRect, 4f, 4f, paintRiskPillBg)
                        canvas.drawRoundRect(tpRect, 4f, 4f, paintRiskPillBorder)
                        paintRiskPillText.color = Color.parseColor("#00E676")
                        canvas.drawText(tpLabel, endX - tpW + 8f, yTp + 8f, paintRiskPillText)
                    }
                }
            }
        }

        // Live Price Line & Badge on Latest Candle
        if (candles.isNotEmpty()) {
            val lastCandle = candles.last()
            val yLast = getY(lastCandle.close)

            val lastIndex = totalCandles - 1
            val lastCandleX = getX(lastIndex)
            val startX = lastCandleX.coerceIn(paddingLeftPx, paddingLeftPx + chartWidth)

            // Right-aligned Cyan/Blue line extending strictly from latest candle to price axis
            canvas.drawLine(startX, yLast, paddingLeftPx + chartWidth, yLast, paintSignalLine)

            val priceStr = formatPriceValue(lastCandle.close)
            val rectTag = RectF(paddingLeftPx + chartWidth + 4f, yLast - 18f, width.toFloat() - 6f, yLast + 18f)
            canvas.drawRoundRect(rectTag, 6f, 6f, paintPriceBadge)

            val paintTagText = Paint(paintText).apply {
                textSize = 20f
                color = Color.BLACK
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
                val pattern = when (timeframeLabel) {
                    "1D", "1d", "1w", "1M" -> "yyyy-MM-dd"
                    else -> "MM-dd HH:mm"
                }
                val sdf = SimpleDateFormat("$pattern ($timeframeLabel)", Locale.US)
                val timeStr = if (hc.timestamp > 0) sdf.format(Date(hc.timestamp)) else "$timeframeLabel Candle"

                val hoverPrice = currentMaxPrice - ((clampedY - paddingTopPx) / chartHeight) * priceRange
                val priceHoverStr = formatPriceValue(hoverPrice)

                // Render Hover Callout Box
                val boxWidth = 380f
                val boxHeight = 110f
                val boxX = (clampedX + 20f).coerceAtMost(width.toFloat() - boxWidth - 10f)
                val boxY = (clampedY - boxHeight - 20f).coerceAtLeast(paddingTopPx + 10f)

                val boxRect = RectF(boxX, boxY, boxX + boxWidth, boxY + boxHeight)
                val paintBox = Paint().apply { color = colorCardBg; style = Paint.Style.FILL }
                val paintBoxBorder = Paint().apply { color = Color.parseColor("#374151"); style = Paint.Style.STROKE; strokeWidth = 2f }

                canvas.drawRoundRect(boxRect, 10f, 10f, paintBox)
                canvas.drawRoundRect(boxRect, 10f, 10f, paintBoxBorder)

                canvas.drawText("$timeStr | $$priceHoverStr", boxX + 14f, boxY + 30f, paintText)
                val ohlcText = "O:${formatPriceValue(hc.open)} H:${formatPriceValue(hc.high)} L:${formatPriceValue(hc.low)} C:${formatPriceValue(hc.close)}"
                canvas.drawText(ohlcText, boxX + 14f, boxY + 62f, paintTextSec)

                if (showEmaStrategy) {
                    val e8H = if (hoverIndex < ema8HighSeries.size) ema8HighSeries[hoverIndex] else 0.0
                    val e34H = if (hoverIndex < ema34HighSeries.size) ema34HighSeries[hoverIndex] else 0.0
                    val emaText = "EMA8:${formatPriceValue(e8H)} | EMA34:${formatPriceValue(e34H)}"
                    val paintEmaLegend = Paint(paintTextSec).apply { color = colorYellowChannel; textSize = 18f }
                    canvas.drawText(emaText, boxX + 14f, boxY + 90f, paintEmaLegend)
                } else if (showDualRibbon) {
                    val r1H = if (hoverIndex < ema134HighSeries.size) ema134HighSeries[hoverIndex] else 0.0
                    val ma1 = if (kryptEmaSeries.isNotEmpty() && hoverIndex < kryptEmaSeries[0].size) kryptEmaSeries[0][hoverIndex] else 0.0
                    val ribbonText = "134H:${formatPriceValue(r1H)} | KRYPT-20:${formatPriceValue(ma1)}"
                    val paintRibbonLegend = Paint(paintTextSec).apply { color = Color.parseColor("#00E676"); textSize = 18f }
                    canvas.drawText(ribbonText, boxX + 14f, boxY + 90f, paintRibbonLegend)
                } else if (showFractalChoch) {
                    val activeFractals = fractalLevels.filter { !it.isBroken }
                    val lastH = activeFractals.lastOrNull { it.isHigh }?.price
                    val lastL = activeFractals.lastOrNull { !it.isHigh }?.price
                    val stateLabel = currentMarketState.name
                    val chochText = "FH:${if (lastH != null) formatPriceValue(lastH) else "--"} | FL:${if (lastL != null) formatPriceValue(lastL) else "--"} | $stateLabel"
                    val paintChochLegend = Paint(paintTextSec).apply {
                        color = when (currentMarketState) {
                            MarketState.UPTREND -> Color.parseColor("#00E676")
                            MarketState.DOWNTREND -> Color.parseColor("#FF5252")
                            MarketState.RANGE -> Color.parseColor("#FFB300")
                        }
                        textSize = 18f
                    }
                    canvas.drawText(chochText, boxX + 14f, boxY + 90f, paintChochLegend)
                }
            }
        }
    }
}
