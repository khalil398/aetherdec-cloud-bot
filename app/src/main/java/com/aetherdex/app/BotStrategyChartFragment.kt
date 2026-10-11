package com.aetherdex.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
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

    // Comprehensive initial list of active Hyperliquid Spot assets
    private val defaultSpotPairs = listOf(
        "PURR/USDC", "HFUN/USDC", "LICK/USDC", "MANLET/USDC", "JEFF/USDC", "SIX/USDC",
        "WAGMI/USDC", "CAPPY/USDC", "POINTS/USDC", "SCHIZO/USDC", "SELL/USDC", "BIGBEN/USDC",
        "CATNIP/USDC", "HBOOST/USDC", "SUCKY/USDC", "GUP/USDC", "FARMED/USDC", "TRUMP/USDC",
        "GMEOW/USDC", "PEPE/USDC", "XULIAN/USDC", "RUG/USDC", "ILIENS/USDC", "HYPE/USDC",
        "BTC/USDC", "ETH/USDC", "SOL/USDC"
    )

    private val FALLBACK_SPOT_INDICES = mapOf(
        "PURR" to 0, "HFUN" to 1, "LICK" to 2, "MANLET" to 3, "JEFF" to 4, "SIX" to 5,
        "WAGMI" to 6, "CAPPY" to 7, "POINTS" to 8, "TRUMP" to 9, "GMEOW" to 10,
        "PEPE" to 11, "XULIAN" to 12, "RUG" to 13, "ILIENS" to 14, "SCHIZO" to 23,
        "SELL" to 24, "BIGBEN" to 25, "CATNIP" to 26, "HBOOST" to 27, "SUCKY" to 28,
        "GUP" to 29, "FARMED" to 30
    )

    private val spotPairsList = mutableListOf<String>()
    private var currentPair = "PURR/USDC"
    private var isFullscreenMode = false
    private var isProgrammaticSpinnerSelection = false

    private lateinit var rootLayout: LinearLayout
    private lateinit var tvHeaderPrice: TextView
    private lateinit var tvSignalStatus: TextView
    private lateinit var spinnerPairs: Spinner
    private lateinit var spinnerAdapter: ArrayAdapter<String>
    private lateinit var btnToggleFullscreen: ImageView
    private lateinit var btnResetChart: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var botChartView: BotStrategyChartView

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

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Initialize symbol list with comprehensive fallback
        spotPairsList.clear()
        spotPairsList.addAll(defaultSpotPairs)

        // Sync initial pair from SharedSpotSelectionState
        currentPair = SharedSpotSelectionState.currentDisplaySymbol
        if (!spotPairsList.any { it.equals(currentPair, ignoreCase = true) }) {
            spotPairsList.add(0, currentPair)
        }

        // ── Top Header Control & Indicator Legend Bar ───────────────────────
        val headerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bgCard)
                cornerRadius = density * 14f
            }
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(12), dp(12), dp(8))
            }
        }

        // ── Row 1: Pair Dropdown, Live Price, Signal Status & Actions ──────
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val pairCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvTitleBadge = TextView(context).apply {
            text = "🤖 BOT 30M STRATEGY"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(green)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = roundedBg(Color.parseColor("#1000E676"), 6f)
        }
        titleRow.addView(tvTitleBadge)

        tvHeaderPrice = TextView(context).apply {
            text = "Loading..."
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrim)
        }

        pairCol.addView(titleRow)
        pairCol.addView(tvHeaderPrice)
        topRow.addView(pairCol)

        // Action Buttons: Reset, Refresh, Landscape Fullscreen
        btnResetChart = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setColorFilter(textSec)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBg(bgChip, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { botChartView.resetView() }
        }

        btnRefresh = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_popup_sync)
            setColorFilter(textSec)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBg(bgChip, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                fetch30mCandleHistory(currentPair)
                val hlCoin = getHyperliquidCoin(currentPair)
                connectHyperliquidWebSocket(currentPair, hlCoin)
                startPollingLoop(currentPair, hlCoin)
            }
        }

        btnToggleFullscreen = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            setColorFilter(textSec)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBg(bgChip, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener {
                toggleFullscreenLandscape()
            }
        }

        topRow.addView(btnResetChart)
        topRow.addView(btnRefresh)
        topRow.addView(btnToggleFullscreen)
        headerCard.addView(topRow)

        // ── Row 2: Pair Selector & Indicator Legend ─────────────────────────
        val legendRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        spinnerAdapter = object : ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            spotPairsList
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(null, Typeface.BOLD)
                tv.textSize = 13f
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setBackgroundColor(Color.parseColor("#151921"))
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(null, Typeface.BOLD)
                tv.textSize = 14f
                val pad = dp(10)
                tv.setPadding(pad, pad, pad, pad)
                return tv
            }
        }

        spinnerPairs = Spinner(context).apply {
            adapter = spinnerAdapter
            background = roundedBg(bgChip, 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val initialIndex = spotPairsList.indexOfFirst { it.equals(currentPair, ignoreCase = true) }
            if (initialIndex >= 0) {
                setSelection(initialIndex)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isProgrammaticSpinnerSelection) return
                    val selected = spotPairsList.getOrNull(position) ?: return
                    if (!selected.equals(currentPair, ignoreCase = true)) {
                        selectSymbol(selected, notifySharedState = true)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        legendRow.addView(spinnerPairs)

        tvSignalStatus = TextView(context).apply {
            text = "EMA8 (White) | EMA34 (Yellow) | EMA 200/233 (Red)"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textSec)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        legendRow.addView(tvSignalStatus)

        headerCard.addView(legendRow)
        rootLayout.addView(headerCard)

        // Loading ProgressBar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply {
                setMargins(dp(12), 0, dp(12), dp(4))
            }
        }
        rootLayout.addView(progressBar)

        // ── Main Bot Strategy Chart Canvas ─────────────────────────────────
        botChartView = BotStrategyChartView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(botChartView)

        // Register shared selection state listener
        SharedSpotSelectionState.addListener(sharedSelectionListener)

        // Populate full dynamic spot list from Hyperliquid L1 metadata
        populateDynamicSpotAssetList()

        // Fetch initial data & start live ticker loops
        val hlCoin = getHyperliquidCoin(currentPair)
        fetch30mCandleHistory(currentPair)
        connectHyperliquidWebSocket(currentPair, hlCoin)
        startPollingLoop(currentPair, hlCoin)

        return rootLayout
    }

    private fun populateDynamicSpotAssetList() {
        val existingSpots = HyperliquidOrderManager.getAllSpotAssetInfos()
        if (existingSpots.isNotEmpty()) {
            mergeSpotAssetInfos(existingSpots)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val freshSpots = HyperliquidOrderManager.fetchSpotMarketMetadata()
            if (freshSpots.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        mergeSpotAssetInfos(freshSpots)
                    }
                }
            }
        }
    }

    private fun mergeSpotAssetInfos(spots: List<HyperliquidOrderManager.SpotAssetInfo>) {
        val newSymbols = spots.map { it.displaySymbol }
        var changed = false
        for (sym in newSymbols) {
            if (!spotPairsList.any { it.equals(sym, ignoreCase = true) }) {
                spotPairsList.add(sym)
                changed = true
            }
        }
        if (changed) {
            spinnerAdapter.notifyDataSetChanged()
            val pos = spotPairsList.indexOfFirst { it.equals(currentPair, ignoreCase = true) }
            if (pos >= 0 && spinnerPairs.selectedItemPosition != pos) {
                isProgrammaticSpinnerSelection = true
                spinnerPairs.setSelection(pos)
                isProgrammaticSpinnerSelection = false
            }
        }
    }

    fun selectSymbol(symbol: String, notifySharedState: Boolean = false) {
        val normalized = if (!symbol.contains("/") && !symbol.endsWith("USDC", true)) "$symbol/USDC" else symbol
        if (normalized.equals(currentPair, ignoreCase = true) && botChartView.candles.isNotEmpty()) {
            return
        }

        currentPair = normalized
        if (notifySharedState) {
            SharedSpotSelectionState.setSymbol(normalized, source = this)
        }

        if (!spotPairsList.any { it.equals(normalized, ignoreCase = true) }) {
            spotPairsList.add(0, normalized)
            spinnerAdapter.notifyDataSetChanged()
        }

        val pos = spotPairsList.indexOfFirst { it.equals(normalized, ignoreCase = true) }
        if (pos >= 0 && spinnerPairs.selectedItemPosition != pos) {
            isProgrammaticSpinnerSelection = true
            spinnerPairs.setSelection(pos)
            isProgrammaticSpinnerSelection = false
        }

        tvHeaderPrice.text = "Loading..."
        val hlCoin = getHyperliquidCoin(normalized)

        disconnectWebSocket()
        fetch30mCandleHistory(normalized)
        connectHyperliquidWebSocket(normalized, hlCoin)
        startPollingLoop(normalized, hlCoin)
    }

    private fun getHyperliquidCoin(symbol: String): String {
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol).uppercase(Locale.US)
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

        if (clean in listOf("BTC", "ETH", "SOL", "HYPE")) {
            return clean
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

    private fun fetch30mCandleHistory(symbol: String) {
        fetchJob?.cancel()
        progressBar.visibility = View.VISIBLE
        val hlCoin = getHyperliquidCoin(symbol)
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol).uppercase(Locale.US)

        fetchJob = lifecycleScope.launch(Dispatchers.IO) {
            var candlesLoaded = false
            try {
                val now = System.currentTimeMillis()
                val startTime = now - (300L * 30L * 60L * 1000L)
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
                        put("interval", "30m")
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
                    val bUrl = URL("https://api.binance.com/api/v3/klines?symbol=$binanceSymbol&interval=30m&limit=300")
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
                    // Subscribe to real-time candles for this coin
                    val candleSub = JSONObject().apply {
                        put("method", "subscribe")
                        put("subscription", JSONObject().apply {
                            put("type", "candle")
                            put("coin", hlCoin)
                            put("interval", "30m")
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
                if (currentList.size > 500) currentList.removeAt(0)
            }
        } else {
            currentList.add(candle)
        }
        botChartView.candles = currentList
    }

    private fun toggleFullscreenLandscape() {
        isFullscreenMode = !isFullscreenMode
        btnToggleFullscreen.setColorFilter(if (isFullscreenMode) Color.parseColor("#00E676") else Color.parseColor("#8A96A8"))

        val activity = activity ?: return
        if (isFullscreenMode) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onFullscreenToggleListener?.invoke(isFullscreenMode)
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
        SharedSpotSelectionState.removeListener(sharedSelectionListener)
        fetchJob?.cancel()
        pollingJob?.cancel()
        disconnectWebSocket()
        super.onDestroyView()
    }

    private fun roundedBg(bgColor: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = resources.displayMetrics.density * radiusDp
        }
    }
}
