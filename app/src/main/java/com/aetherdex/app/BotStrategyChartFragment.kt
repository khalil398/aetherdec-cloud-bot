package com.aetherdex.app

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

class BotStrategyChartFragment : Fragment() {

    private val tradingPairs = listOf("BTCUSDC", "ETHUSDC", "SOLUSDC", "BNBUSDC", "XRPUSDC", "DOGEUSDC")
    private val pairDisplayNames = mapOf(
        "BTCUSDC" to "BTC / USDC",
        "ETHUSDC" to "ETH / USDC",
        "SOLUSDC" to "SOL / USDC",
        "BNBUSDC" to "BNB / USDC",
        "XRPUSDC" to "XRP / USDC",
        "DOGEUSDC" to "DOGE / USDC"
    )

    private var currentPair = "BTCUSDC"
    private var isFullscreenMode = false

    private lateinit var rootLayout: LinearLayout
    private lateinit var tvHeaderPair: TextView
    private lateinit var tvHeaderPrice: TextView
    private lateinit var tvSignalStatus: TextView
    private lateinit var spinnerPairs: Spinner
    private lateinit var btnToggleFullscreen: ImageView
    private lateinit var btnResetChart: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var botChartView: BotStrategyChartView

    var onFullscreenToggleListener: ((Boolean) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder().build()

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
            text = "🤖 BOT 5M STRATEGY"
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
                fetch5mCandleHistory()
                connectBinanceKlineWebSocket(currentPair)
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

        val spinnerAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            tradingPairs.map { pairDisplayNames[it] ?: it }
        )

        spinnerPairs = Spinner(context).apply {
            adapter = spinnerAdapter
            background = roundedBg(bgChip, 8f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = tradingPairs[position]
                    if (selected != currentPair) {
                        currentPair = selected
                        fetch5mCandleHistory()
                        connectBinanceKlineWebSocket(currentPair)
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

        fetch5mCandleHistory()
        connectBinanceKlineWebSocket(currentPair)

        return rootLayout
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

    override fun onDestroyView() {
        disconnectWebSocket()
        super.onDestroyView()
    }

    private fun fetch5mCandleHistory() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val urlStr = "https://api.binance.com/api/v3/klines?symbol=$currentPair&interval=5m&limit=300"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val stream = conn.inputStream
                    val jsonText = stream.bufferedReader().use { it.readText() }
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
                        progressBar.visibility = View.GONE
                        botChartView.candles = candleList
                        if (candleList.isNotEmpty()) {
                            val lastPrice = candleList.last().close
                            tvHeaderPrice.text = String.format(Locale.US, "$%.2f", lastPrice)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
            }
        }
    }

    private fun connectBinanceKlineWebSocket(symbol: String) {
        disconnectWebSocket()
        val streamSymbol = symbol.lowercase(Locale.US)
        val url = "wss://stream.binance.com:9443/ws/${streamSymbol}@kline_5m"

        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val k = json.optJSONObject("k") ?: return

                    val openTime = k.getLong("t")
                    val open = k.getString("o").toDoubleOrNull() ?: 0.0
                    val high = k.getString("h").toDoubleOrNull() ?: 0.0
                    val low = k.getString("l").toDoubleOrNull() ?: 0.0
                    val close = k.getString("c").toDoubleOrNull() ?: 0.0
                    val vol = k.getString("v").toDoubleOrNull() ?: 0.0

                    val liveCandle = BotStrategyChartView.Candle(open, high, low, close, vol, openTime)

                    activity?.runOnUiThread {
                        val currentList = botChartView.candles.toMutableList()
                        if (currentList.isNotEmpty()) {
                            val last = currentList.last()
                            if (last.timestamp == liveCandle.timestamp) {
                                currentList[currentList.size - 1] = liveCandle
                            } else if (liveCandle.timestamp > last.timestamp) {
                                currentList.add(liveCandle)
                                if (currentList.size > 500) currentList.removeAt(0)
                            }
                        } else {
                            currentList.add(liveCandle)
                        }
                        botChartView.candles = currentList
                        tvHeaderPrice.text = String.format(Locale.US, "$%.2f", liveCandle.close)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
            }
        })
    }

    private fun disconnectWebSocket() {
        try {
            webSocket?.close(1000, "Fragment UI disconnect")
            webSocket = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun roundedBg(bgColor: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = resources.displayMetrics.density * radiusDp
        }
    }
}
