package com.aetherdex.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

class BotStrategyChartFragment : Fragment() {

    companion object {
        var activeStrategiesState: Set<String> = emptySet()
    }

    private val defaultSpotPairs = listOf(
        "BTC/USDC", "ETH/USDC", "SOL/USDC", "HYPE/USDC",
        "PURR/USDC", "HFUN/USDC", "LICK/USDC", "MANLET/USDC", "JEFF/USDC", "SIX/USDC",
        "WAGMI/USDC", "CAPPY/USDC", "POINTS/USDC", "SCHIZO/USDC", "SELL/USDC", "BIGBEN/USDC",
        "CATNIP/USDC", "HBOOST/USDC", "SUCKY/USDC", "GUP/USDC", "FARMED/USDC", "TRUMP/USDC",
        "GMEOW/USDC", "PEPE/USDC", "XULIAN/USDC", "RUG/USDC", "ILIENS/USDC"
    )

    private val FALLBACK_SPOT_INDICES = mapOf(
        "PURR" to 0, "HFUN" to 1, "LICK" to 2, "MANLET" to 3, "JEFF" to 4, "SIX" to 5,
        "WAGMI" to 6, "CAPPY" to 7, "POINTS" to 8, "TRUMP" to 9, "GMEOW" to 10,
        "PEPE" to 11, "XULIAN" to 12, "RUG" to 13, "ILIENS" to 14, "SCHIZO" to 23,
        "SELL" to 24, "BIGBEN" to 25, "CATNIP" to 26, "HBOOST" to 27, "SUCKY" to 28,
        "GUP" to 29, "FARMED" to 30
    )

    private var currentPair = "PURR/USDC"
    private var isFullscreenMode = false

    private val availableTimeframes = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1D", "1w", "1M")
    private var currentTimeframe = "30m"
    private val timeframeButtons = mutableMapOf<String, TextView>()
    private lateinit var scrollTimeframes: HorizontalScrollView

    private lateinit var rootFrameLayout: FrameLayout
    private lateinit var chartContentLayout: LinearLayout
    private lateinit var headerCard: LinearLayout
    private lateinit var tvSelectedAsset: TextView
    private lateinit var tvHeaderPrice: TextView
    private lateinit var btnStrategyInfo: ImageView
    private lateinit var btnToggleFullscreen: ImageView
    private lateinit var btnResetChart: ImageView
    private lateinit var btnRefresh: ImageView
    private var btnFloatingExitFullscreen: ImageView? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var botChartView: BotStrategyChartView

    private var strategyInfoPopup: PopupWindow? = null

    var onFullscreenToggleListener: ((Boolean) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var fetchJob: Job? = null
    private var pollingJob: Job? = null

    private val sharedSelectionListener: (String, Any?) -> Unit = { newSymbol, source ->
        if (source !== this && isAdded) {
            activity?.runOnUiThread {
                selectSymbol(newSymbol, notifySharedState = false)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val bgMain = Color.parseColor("#0B0E14")
        val bgCard = Color.parseColor("#151921")
        val bgChip = Color.parseColor("#1F2430")
        val textPrim = Color.parseColor("#FFFFFF")
        val textSec = Color.parseColor("#8A96A8")
        val green = Color.parseColor("#00E676")

        rootFrameLayout = FrameLayout(context).apply {
            setBackgroundColor(bgMain)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        chartContentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Sync initial pair from SharedSpotSelectionState
        currentPair = SharedSpotSelectionState.currentDisplaySymbol

        // ── Streamlined Compact Top Header Bar (Single Row, Max Vertical Canvas) ──
        headerCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(bgCard)
                cornerRadius = density * 12f
            }
            val hp = dp(10)
            val vp = dp(8)
            setPadding(hp, vp, hp, vp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(10), dp(10), dp(10), dp(6))
            }
        }

        // ── 1. Asset Dropdown Selector (Relocated to left header space) ──
        val layoutAssetSelector = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(bgChip)
                cornerRadius = density * 8f
                setStroke(dp(1), Color.parseColor("#2A3241"))
            }
            val pX = dp(10)
            val pY = dp(6)
            setPadding(pX, pY, pX, pY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                showAssetSearchDialog()
            }
        }

        tvSelectedAsset = TextView(context).apply {
            text = "$currentPair  ▾"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        layoutAssetSelector.addView(tvSelectedAsset)
        headerCard.addView(layoutAssetSelector)

        // ── 2. Streamlined Compact Live Price (Centered in header bar) ──
        tvHeaderPrice = TextView(context).apply {
            text = "Loading..."
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrim)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }
        headerCard.addView(tvHeaderPrice)

        // ── 3. Right Action Controls: Info (Popup), Fullscreen, Reset, Refresh ──
        val rightActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Strategy Detail & Legend Info Button (ℹ️)
        btnStrategyInfo = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            setColorFilter(green)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBg(bgChip, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                toggleStrategyInfoPopup(btnStrategyInfo)
            }
        }
        rightActions.addView(btnStrategyInfo)

        // Full Screen Button (⛶) next to strategy info icon
        btnToggleFullscreen = ImageView(context).apply {
            setImageResource(R.drawable.ic_fullscreen)
            setColorFilter(textSec)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBg(bgChip, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                toggleFullscreenMode()
            }
        }
        rightActions.addView(btnToggleFullscreen)

        btnResetChart = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setColorFilter(textSec)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBg(bgChip, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { botChartView.resetView() }
        }
        rightActions.addView(btnResetChart)

        btnRefresh = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            setColorFilter(textSec)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBg(bgChip, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                fetchCandleHistory(currentPair, currentTimeframe)
                val hlCoin = getHyperliquidCoin(currentPair)
                connectHyperliquidWebSocket(currentPair, hlCoin)
                startPollingLoop(currentPair, hlCoin)
            }
        }
        rightActions.addView(btnRefresh)

        headerCard.addView(rightActions)
        chartContentLayout.addView(headerCard)

        // ── Multi-Timeframe Selector Bar (1m, 5m, 15m, 30m, 1h, 4h, 1D, 1w, 1M) ──
        scrollTimeframes = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(10), 0, dp(10), dp(6))
            }
        }

        val layoutTimeframes = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        timeframeButtons.clear()
        availableTimeframes.forEach { tf ->
            val btnTf = TextView(context).apply {
                text = tf
                textSize = 12f
                setTypeface(null, if (tf == currentTimeframe) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER
                val padH = dp(11)
                val padV = dp(5)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    selectTimeframe(tf)
                }
            }
            timeframeButtons[tf] = btnTf
            layoutTimeframes.addView(btnTf)
        }
        scrollTimeframes.addView(layoutTimeframes)
        chartContentLayout.addView(scrollTimeframes)
        updateTimeframeButtonsUi()

        // Loading ProgressBar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply {
                setMargins(dp(10), 0, dp(10), dp(4))
            }
        }
        chartContentLayout.addView(progressBar)

        // ── Main Bot Strategy Chart Canvas ─────────────────────────────────
        botChartView = BotStrategyChartView(context).apply {
            timeframeLabel = currentTimeframe
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        syncStrategiesFromPrefs()
        chartContentLayout.addView(botChartView)
        rootFrameLayout.addView(chartContentLayout)

        // ── Floating Clean Exit Full Screen Button ─────────────────────────
        btnFloatingExitFullscreen = ImageView(context).apply {
            setImageResource(R.drawable.ic_fullscreen_exit)
            setColorFilter(Color.WHITE)
            val p = dp(9)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6151921"))
                setStroke(dp(1), Color.parseColor("#374151"))
            }
            elevation = density * 12f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(14)
                marginEnd = dp(14)
            }
            setOnClickListener {
                exitFullscreenMode()
            }
        }
        rootFrameLayout.addView(btnFloatingExitFullscreen)

        // Register shared selection state listener
        SharedSpotSelectionState.addListener(sharedSelectionListener)

        // Pre-fetch spot metadata if not yet loaded
        ensureSpotMetadataLoaded()

        // Fetch initial data & start live ticker loops
        val hlCoin = getHyperliquidCoin(currentPair)
        fetchCandleHistory(currentPair, currentTimeframe)
        connectHyperliquidWebSocket(currentPair, hlCoin)
        startPollingLoop(currentPair, hlCoin)

        return rootFrameLayout
    }

    fun isChartFullscreenMode(): Boolean = isFullscreenMode

    fun toggleFullscreenMode() {
        setFullscreenState(!isFullscreenMode)
    }

    fun exitFullscreenMode() {
        if (isFullscreenMode) {
            setFullscreenState(false)
        }
    }

    private fun setFullscreenState(fullscreen: Boolean) {
        isFullscreenMode = fullscreen
        headerCard.visibility = if (fullscreen) View.GONE else View.VISIBLE
        scrollTimeframes.visibility = if (fullscreen) View.GONE else View.VISIBLE
        progressBar.visibility = if (fullscreen) View.GONE else (if (fetchJob?.isActive == true) View.VISIBLE else View.GONE)
        btnFloatingExitFullscreen?.visibility = if (fullscreen) View.VISIBLE else View.GONE
        onFullscreenToggleListener?.invoke(fullscreen)
    }

    fun selectTimeframe(timeframe: String) {
        if (currentTimeframe == timeframe && botChartView.candles.isNotEmpty()) return
        currentTimeframe = timeframe
        botChartView.timeframeLabel = timeframe
        updateTimeframeButtonsUi()
        fetchCandleHistory(currentPair, currentTimeframe)
        val hlCoin = getHyperliquidCoin(currentPair)
        connectHyperliquidWebSocket(currentPair, hlCoin)
    }

    private fun updateTimeframeButtonsUi() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val activeBg = Color.parseColor("#00E676")
        val inactiveBg = Color.parseColor("#151921")
        val activeText = Color.parseColor("#0B0E14")
        val inactiveText = Color.parseColor("#8A96A8")
        val strokeColor = Color.parseColor("#2A3241")

        timeframeButtons.forEach { (tf, btn) ->
            val isSelected = (tf == currentTimeframe)
            btn.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            btn.setTextColor(if (isSelected) activeText else inactiveText)
            btn.background = GradientDrawable().apply {
                setColor(if (isSelected) activeBg else inactiveBg)
                cornerRadius = density * 6f
                if (!isSelected) {
                    setStroke(dp(1), strokeColor)
                }
            }
        }
    }

    private fun getHyperliquidInterval(timeframe: String): String {
        return when (timeframe) {
            "1m" -> "1m"
            "5m" -> "5m"
            "15m" -> "15m"
            "30m" -> "30m"
            "1h" -> "1h"
            "4h" -> "4h"
            "1D" -> "1d"
            "1w" -> "1w"
            "1M" -> "1M"
            else -> "30m"
        }
    }

    private fun getBinanceInterval(timeframe: String): String {
        return when (timeframe) {
            "1m" -> "1m"
            "5m" -> "5m"
            "15m" -> "15m"
            "30m" -> "30m"
            "1h" -> "1h"
            "4h" -> "4h"
            "1D" -> "1d"
            "1w" -> "1w"
            "1M" -> "1M"
            else -> "30m"
        }
    }

    private fun getCandleDurationMs(timeframe: String): Long {
        return when (timeframe) {
            "1m" -> 60_000L
            "5m" -> 5 * 60_000L
            "15m" -> 15 * 60_000L
            "30m" -> 30 * 60_000L
            "1h" -> 60 * 60_000L
            "4h" -> 4 * 60 * 60_000L
            "1D" -> 24 * 60 * 60_000L
            "1w" -> 7 * 24 * 60 * 60_000L
            "1M" -> 30L * 24 * 60 * 60_000L
            else -> 30 * 60_000L
        }
    }

    private fun ensureSpotMetadataLoaded() {
        if (!HyperliquidOrderManager.isSpotMetadataLoaded) {
            lifecycleScope.launch(Dispatchers.IO) {
                HyperliquidOrderManager.fetchSpotMarketMetadata()
            }
        }
    }

    private fun showAssetSearchDialog() {
        val ctx = context ?: return
        SpotAssetSearchBottomSheet.show(ctx, currentPair) { selectedSymbol ->
            selectSymbol(selectedSymbol, notifySharedState = true)
        }
    }

    private fun toggleStrategyInfoPopup(anchor: View) {
        if (strategyInfoPopup?.isShowing == true) {
            strategyInfoPopup?.dismiss()
            strategyInfoPopup = null
            return
        }

        val context = anchor.context
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val popupCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151921"))
                cornerRadius = density * 12f
                setStroke(dp(1), Color.parseColor("#2A3241"))
            }
            elevation = density * 16f
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(dp(295), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Title Row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
        }

        val isDualRibbon = activeStrategiesState.any {
            it.contains("Dual-Ribbon", ignoreCase = true) ||
            it.contains("Combined Ribbon", ignoreCase = true) ||
            it.contains("Krypt", ignoreCase = true) ||
            it.contains("Dual Ribbon", ignoreCase = true)
        }

        val tvTitle = TextView(context).apply {
            text = if (isDualRibbon) "🤖 5M DUAL-RIBBON STRATEGY" else "🤖 BOT 30M STRATEGY"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(tvTitle)
        popupCard.addView(titleRow)

        val tvDesc = TextView(context).apply {
            text = if (isDualRibbon) {
                "Combined Ribbon: 134 EMA High/Low Green Band (#00E676) + Ribbon 2 (Krypt Fast 8 EMAs 20-55 on close)."
            } else {
                "Dual EMA channel breakout engine on 30m candles with strict breakout crossover verification."
            }
            textSize = 11f
            setTextColor(Color.parseColor("#8A96A8"))
            setPadding(0, 0, 0, dp(8))
        }
        popupCard.addView(tvDesc)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#2A3241"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                bottomMargin = dp(6)
            }
        }
        popupCard.addView(divider)

        // Color Legend Items
        fun addLegendRow(dotColor: Int, label: String, description: String) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(8)
                }
            }
            row.addView(dot)

            val tvLbl = TextView(context).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(tvLbl)

            val tvDet = TextView(context).apply {
                text = description
                textSize = 11f
                setTextColor(Color.parseColor("#8A96A8"))
            }
            row.addView(tvDet)

            popupCard.addView(row)
        }

        if (isDualRibbon) {
            addLegendRow(Color.parseColor("#00E676"), "Ribbon 1", "134 EMA High/Low Green Band")
            addLegendRow(Color.parseColor("#F5EB5D"), "MA-1 (20)", "Primary Yellow Reference Line")
            addLegendRow(Color.parseColor("#F5B771"), "MA 25-35", "Krypt Fast EMAs (25, 30, 35)")
            addLegendRow(Color.parseColor("#F56D58"), "MA 40-55", "Krypt Slow EMAs (40, 45, 50, 55)")
            addLegendRow(Color.parseColor("#00E676"), "▲ BUY", "Breakout above 134 Green Band")
            addLegendRow(Color.parseColor("#FF1744"), "▼ SELL", "Breakout below 134 Green Band")
        } else {
            addLegendRow(Color.WHITE, "EMA 8", "White High/Low Channel")
            addLegendRow(Color.parseColor("#FFD600"), "EMA 34", "Yellow High/Low Channel")
            addLegendRow(Color.parseColor("#FF3D00"), "EMA 200/233", "Baseline Trend Filter")
            addLegendRow(Color.parseColor("#00E676"), "▲ BUY", "Breakout above Yellow")
            addLegendRow(Color.parseColor("#FF1744"), "▼ SELL", "Breakout below Yellow")
            addLegendRow(Color.parseColor("#00E5FF"), "Cyan Line", "Live Price / Level")
        }

        // ── BOT TARGET RADAR (Toggled Assets) Section ──
        val radarDivider = View(context).apply {
            setBackgroundColor(Color.parseColor("#2A3241"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        popupCard.addView(radarDivider)

        val tvRadarTitle = TextView(context).apply {
            text = "BOT TARGET RADAR (Toggled Assets)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 0, 0, dp(6))
        }
        popupCard.addView(tvRadarTitle)

        val radarContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        popupCard.addView(radarContainer)

        var popupRef: PopupWindow? = null

        val renderRadarRows = { items: List<BotRadarManager.RadarItem> ->
            radarContainer.removeAllViews()
            if (items.isEmpty()) {
                val tvEmpty = TextView(context).apply {
                    text = "No assets enabled for Bot Trading.\nTurn toggle ON in Spot Asset list."
                    textSize = 11f
                    setTextColor(Color.parseColor("#8A96A8"))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(4))
                }
                radarContainer.addView(tvEmpty)
            } else {
                items.forEach { item ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#1C222E"))
                            cornerRadius = density * 6f
                            setStroke(dp(1), Color.parseColor("#2A3241"))
                        }
                        val px = dp(8)
                        val py = dp(5)
                        setPadding(px, py, px, py)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(4)
                        }
                        setOnClickListener {
                            selectSymbol(item.symbol, notifySharedState = true)
                            popupRef?.dismiss()
                        }
                    }

                    val tvName = TextView(context).apply {
                        text = item.symbol
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(tvName)

                    // Color Coding:
                    // If progress/proximity is >= 50%, Bright Neon Green (#00E676)
                    // If progress/proximity is < 50%, Vibrant Red (#FF5252)
                    val colorHex = if (item.proximity >= 50) "#00E676" else "#FF5252"
                    val tvPercent = TextView(context).apply {
                        text = if (item.proximity >= 100) "▲ 100%" else "${item.proximity}%"
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor(colorHex))
                    }
                    row.addView(tvPercent)
                    radarContainer.addView(row)
                }
            }
        }

        // 1. Calculate current pair proximity from live chart canvas
        val currentSnap = botChartView.getLatestEmaSnapshot()
        if (currentSnap != null) {
            val curProx = BotStrategyChartView.calculateBreakoutProximity(
                currentSnap.ema8High,
                currentSnap.ema8Low,
                currentSnap.ema34High,
                currentSnap.ema34Low,
                currentSnap.prevEma8High,
                currentSnap.prevEma8Low
            )
            BotRadarManager.updateScore(currentPair, curProx)
        }

        // 2. Render initial cached/known states
        val enabledTokens = BotAssetPermissionManager.getEnabledAssets(context)
        val initialItems = enabledTokens.map { token ->
            val clean = HyperliquidOrderManager.cleanSymbolString(token).uppercase(Locale.US)
            val disp = "$clean/USDC"
            BotRadarManager.getScore(clean) ?: BotRadarManager.RadarItem(
                symbol = disp,
                cleanToken = clean,
                proximity = 0,
                isBreakout = false
            )
        }.sortedWith(
            compareByDescending<BotRadarManager.RadarItem> { it.proximity }
                .thenBy { it.symbol }
        )
        renderRadarRows(initialItems)

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(dp(295), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(popupCard)
        }

        val popup = PopupWindow(
            scrollView,
            dp(295),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                strategyInfoPopup = null
            }
        }
        popupRef = popup
        strategyInfoPopup = popup
        popup.showAsDropDown(anchor, -dp(245), dp(6))

        // 3. Asynchronously fetch latest 30m candles and compute exact proximity for all toggled assets
        viewLifecycleOwner.lifecycleScope.launch {
            val freshScores = BotRadarManager.refreshAllEnabledAssets(context)
            if (strategyInfoPopup?.isShowing == true && isAdded) {
                renderRadarRows(freshScores)
            }
        }
    }

    fun selectSymbol(symbol: String, notifySharedState: Boolean = false) {
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol)
        val normalized = if (clean.isNotBlank()) "$clean/USDC" else "PURR/USDC"
        if (normalized.equals(currentPair, ignoreCase = true) && botChartView.candles.isNotEmpty()) {
            return
        }

        currentPair = normalized
        if (notifySharedState) {
            SharedSpotSelectionState.setSymbol(normalized, source = this)
        }

        tvSelectedAsset.text = "$normalized  ▾"
        tvHeaderPrice.text = "Loading..."
        val hlCoin = getHyperliquidCoin(normalized)

        disconnectWebSocket()
        fetchCandleHistory(normalized, currentTimeframe)
        connectHyperliquidWebSocket(normalized, hlCoin)
        startPollingLoop(normalized, hlCoin)
    }

    private fun getHyperliquidCoin(symbol: String): String {
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol).uppercase(Locale.US)
        if (clean in listOf("BTC", "ETH", "SOL", "HYPE")) {
            return clean
        }
        if (clean == "PURR") return "PURR"

        val spotInfo = HyperliquidOrderManager.getSpotAssetInfo(clean)
            ?: HyperliquidOrderManager.getSpotAssetInfo(symbol)
        if (spotInfo != null) {
            return if (spotInfo.baseToken.equals("PURR", true)) "PURR" else "@${spotInfo.universeIndex}"
        }

        val fallbackIdx = FALLBACK_SPOT_INDICES[clean]
        if (fallbackIdx != null) {
            return if (fallbackIdx == 0) "PURR" else "@$fallbackIdx"
        }

        return clean
    }

    private fun extractPriceFromAllMids(mids: JSONObject, symbol: String, hlCoin: String): Double? {
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol).uppercase(Locale.US)
        val spotInfo = HyperliquidOrderManager.getSpotAssetInfo(clean)
            ?: HyperliquidOrderManager.getSpotAssetInfo(symbol)
        val universeIdxKey = if (spotInfo != null) "@${spotInfo.universeIndex}" else null

        val keysToCheck = listOfNotNull(
            hlCoin,
            universeIdxKey,
            if (clean == "PURR") "PURR" else null,
            clean,
            symbol
        )

        for (k in keysToCheck) {
            val px = mids.optString(k, "").toDoubleOrNull()
            if (px != null && px > 0.0) return px
        }
        return null
    }

    private fun fetchCandleHistory(symbol: String, timeframe: String = currentTimeframe) {
        fetchJob?.cancel()
        progressBar.visibility = View.VISIBLE
        val hlCoin = getHyperliquidCoin(symbol)
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol).uppercase(Locale.US)
        val hlInterval = getHyperliquidInterval(timeframe)
        val candleDuration = getCandleDurationMs(timeframe)

        // Request maximum historical candle depth allowed by exchange (up to 5,000 candles on Hyperliquid)
        val maxCandles = when (timeframe) {
            "1m", "5m", "15m", "30m", "1h", "4h" -> 5000L
            "1D" -> 3000L
            "1w" -> 1000L
            "1M" -> 300L
            else -> 5000L
        }

        fetchJob = lifecycleScope.launch(Dispatchers.IO) {
            var candlesLoaded = false
            try {
                val now = System.currentTimeMillis()
                val startTime = now - (maxCandles * candleDuration)
                val url = URL("${HyperliquidOrderManager.HYPERLIQUID_API_URL}/info")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val reqObj = JSONObject().apply {
                    put("type", "candleSnapshot")
                    put("req", JSONObject().apply {
                        put("coin", hlCoin)
                        put("interval", hlInterval)
                        put("startTime", startTime)
                        put("endTime", now)
                    })
                }

                conn.outputStream.use { os ->
                    os.write(reqObj.toString().toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == 200) {
                    val respText = conn.inputStream.bufferedReader().readText()
                    val arr = JSONArray(respText)
                    if (arr.length() > 0) {
                        val list = mutableListOf<BotStrategyChartView.Candle>()
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            val t = c.optLong("t")
                            val o = c.optString("o", "0").toDoubleOrNull() ?: 0.0
                            val h = c.optString("h", "0").toDoubleOrNull() ?: 0.0
                            val l = c.optString("l", "0").toDoubleOrNull() ?: 0.0
                            val closePx = c.optString("c", "0").toDoubleOrNull() ?: 0.0
                            val v = c.optString("v", "0").toDoubleOrNull() ?: 0.0
                            list.add(BotStrategyChartView.Candle(o, h, l, closePx, v, t))
                        }
                        list.sortBy { it.timestamp }
                        withContext(Dispatchers.Main) {
                            if (!isActive) return@withContext
                            progressBar.visibility = View.GONE
                            botChartView.candles = list
                            if (list.isNotEmpty()) {
                                tvHeaderPrice.text = "$" + BotStrategyChartView.formatPriceValue(list.last().close)
                            }
                        }
                        candlesLoaded = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (!candlesLoaded) {
                // Secondary fallback to Binance for major cryptos
                try {
                    val binanceSymbol = "${clean}USDT"
                    val bInterval = getBinanceInterval(timeframe)
                    val bUrl = URL("https://api.binance.com/api/v3/klines?symbol=$binanceSymbol&interval=$bInterval&limit=1000")
                    val bConn = bUrl.openConnection() as HttpURLConnection
                    bConn.requestMethod = "GET"
                    bConn.connectTimeout = 6000
                    bConn.readTimeout = 6000
                    if (bConn.responseCode == 200) {
                        val jsonText = bConn.inputStream.bufferedReader().readText()
                        val jsonArray = JSONArray(jsonText)
                        val candleList = mutableListOf<BotStrategyChartView.Candle>()
                        for (i in 0 until jsonArray.length()) {
                            val k = jsonArray.getJSONArray(i)
                            val timestamp = k.getLong(0)
                            val open = k.getString(1).toDoubleOrNull() ?: 0.0
                            val high = k.getString(2).toDoubleOrNull() ?: 0.0
                            val low = k.getString(3).toDoubleOrNull() ?: 0.0
                            val close = k.getString(4).toDoubleOrNull() ?: 0.0
                            val vol = k.getString(5).toDoubleOrNull() ?: 0.0
                            candleList.add(BotStrategyChartView.Candle(open, high, low, close, vol, timestamp))
                        }
                        withContext(Dispatchers.Main) {
                            if (!isActive) return@withContext
                            progressBar.visibility = View.GONE
                            botChartView.candles = candleList
                            if (candleList.isNotEmpty()) {
                                tvHeaderPrice.text = "$" + BotStrategyChartView.formatPriceValue(candleList.last().close)
                            }
                        }
                        candlesLoaded = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                if (isActive) progressBar.visibility = View.GONE
            }
        }
    }

    private fun connectHyperliquidWebSocket(symbol: String, hlCoin: String) {
        disconnectWebSocket()
        val request = Request.Builder().url("wss://api.hyperliquid.xyz/ws").build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    // Subscribe to real-time candles for this coin and selected timeframe
                    val candleSub = JSONObject().apply {
                        put("method", "subscribe")
                        put("subscription", JSONObject().apply {
                            put("type", "candle")
                            put("coin", hlCoin)
                            put("interval", getHyperliquidInterval(currentTimeframe))
                        })
                    }
                    webSocket.send(candleSub.toString())

                    // Subscribe to allMids for live price updates across all assets
                    val midsSub = JSONObject().apply {
                        put("method", "subscribe")
                        put("subscription", JSONObject().apply {
                            put("type", "allMids")
                        })
                    }
                    webSocket.send(midsSub.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val channel = json.optString("channel", "")

                    if (channel == "candle") {
                        val d = json.optJSONObject("data") ?: return
                        val coinInMsg = d.optString("s", "")
                        if (coinInMsg.equals(hlCoin, ignoreCase = true) || coinInMsg.equals(symbol, ignoreCase = true)) {
                            val t = d.optLong("t")
                            val o = d.optString("o", "0").toDoubleOrNull() ?: 0.0
                            val h = d.optString("h", "0").toDoubleOrNull() ?: 0.0
                            val l = d.optString("l", "0").toDoubleOrNull() ?: 0.0
                            val c = d.optString("c", "0").toDoubleOrNull() ?: 0.0
                            val v = d.optString("v", "0").toDoubleOrNull() ?: 0.0
                            if (c > 0.0) {
                                val liveCandle = BotStrategyChartView.Candle(o, h, l, c, v, t)
                                activity?.runOnUiThread {
                                    onLiveCandleUpdated(liveCandle)
                                }
                            }
                        }
                    } else if (channel == "allMids") {
                        val d = json.optJSONObject("data") ?: return
                        val mids = d.optJSONObject("mids") ?: return
                        val px = extractPriceFromAllMids(mids, symbol, hlCoin)
                        if (px != null && px > 0.0) {
                            activity?.runOnUiThread {
                                onLivePriceTick(px)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Handled gracefully by 1.5s Polling Loop
            }
        })
    }

    private fun startPollingLoop(symbol: String, hlCoin: String) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val url = URL("${HyperliquidOrderManager.HYPERLIQUID_API_URL}/info")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000

                    val reqBody = JSONObject().apply {
                        put("type", "allMids")
                    }
                    conn.outputStream.use { os ->
                        os.write(reqBody.toString().toByteArray(Charsets.UTF_8))
                    }

                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().readText()
                        val mids = JSONObject(text)
                        val px = extractPriceFromAllMids(mids, symbol, hlCoin)
                        if (px != null && px > 0.0) {
                            withContext(Dispatchers.Main) {
                                if (isActive) {
                                    onLivePriceTick(px)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore and retry next tick
                }
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    private fun onLivePriceTick(price: Double) {
        if (price <= 0.0) return
        tvHeaderPrice.text = "$" + BotStrategyChartView.formatPriceValue(price)
        val currentList = botChartView.candles.toMutableList()
        if (currentList.isNotEmpty()) {
            val lastIdx = currentList.size - 1
            val last = currentList[lastIdx]
            val updated = BotStrategyChartView.Candle(
                open = last.open,
                high = maxOf(last.high, price),
                low = minOf(last.low, price),
                close = price,
                volume = last.volume,
                timestamp = last.timestamp
            )
            currentList[lastIdx] = updated
            botChartView.candles = currentList
        }
    }

    private fun onLiveCandleUpdated(candle: BotStrategyChartView.Candle) {
        tvHeaderPrice.text = "$" + BotStrategyChartView.formatPriceValue(candle.close)
        val currentList = botChartView.candles.toMutableList()
        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.timestamp == candle.timestamp) {
                currentList[currentList.size - 1] = candle
            } else if (candle.timestamp > last.timestamp) {
                currentList.add(candle)
                if (currentList.size > 5500) currentList.removeAt(0)
            }
        } else {
            currentList.add(candle)
        }
        botChartView.candles = currentList
    }

    private fun toggleFullscreenLandscape() {
        toggleFullscreenMode()
    }

    private fun disconnectWebSocket() {
        try {
            webSocket?.close(1000, "Symbol changed")
            webSocket = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroyView() {
        strategyInfoPopup?.dismiss()
        strategyInfoPopup = null
        if (isFullscreenMode) {
            onFullscreenToggleListener?.invoke(false)
        }
        SharedSpotSelectionState.removeListener(sharedSelectionListener)
        fetchJob?.cancel()
        pollingJob?.cancel()
        disconnectWebSocket()
        super.onDestroyView()
    }

    fun updateActiveStrategies(strategies: Set<String>) {
        activeStrategiesState = strategies
        if (::botChartView.isInitialized) {
            botChartView.setActiveStrategies(strategies)
        }
        if (strategies.any { it.contains("Dual-Ribbon", ignoreCase = true) || it.contains("5m", ignoreCase = true) }) {
            if (currentTimeframe != "5m") {
                selectTimeframe("5m")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncStrategiesFromPrefs()
    }

    private fun syncStrategiesFromPrefs() {
        if (!::botChartView.isInitialized) return
        try {
            val botPrefs = requireContext().getSharedPreferences("aetherdex_bot_prefs", Context.MODE_PRIVATE)
            val spotPrefs = requireContext().getSharedPreferences("aetherdex_prefs", Context.MODE_PRIVATE)
            val isSpotMode = spotPrefs.getBoolean("is_spot_mode", true)

            // Resolve persisted strategy set (prefer StringSet, fall back to comma-string)
            val resolvedSet: Set<String> = run {
                val savedSet = if (isSpotMode) {
                    botPrefs.getStringSet("spot_bot_strategies", null)
                } else {
                    botPrefs.getStringSet("futures_bot_strategies", null)
                }
                if (savedSet != null && savedSet.isNotEmpty()) return@run savedSet

                val singleStrat = if (isSpotMode) {
                    botPrefs.getString("spot_bot_strategy", null)
                } else {
                    botPrefs.getString("futures_bot_strategy", null)
                }
                if (!singleStrat.isNullOrBlank()) {
                    val parsed = singleStrat.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    if (parsed.isNotEmpty()) return@run parsed
                }

                // Absolute fallback — if nothing was ever saved, use the companion state
                // (which MainActivity also populates from prefs at setupBotEngine time).
                // Avoid the hard-coded "30m EMA High/Low" default so the chart never
                // overrides a user-saved selection.
                if (activeStrategiesState.isNotEmpty()) activeStrategiesState
                else setOf("30m EMA High/Low")
            }

            // Apply — updateActiveStrategies also auto-switches timeframe to 5m for Dual-Ribbon
            activeStrategiesState = resolvedSet
            updateActiveStrategies(resolvedSet)
        } catch (e: Exception) {
            e.printStackTrace()
            updateActiveStrategies(activeStrategiesState)
        }
    }

    private fun roundedBg(bgColor: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = resources.displayMetrics.density * radiusDp
        }
    }
}
