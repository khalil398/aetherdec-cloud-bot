package com.aetherdex.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages live strategy breakout proximity radar calculations for assets with Bot Trading Toggle ON.
 * Calculates distance toward valid ▲ BUY breakout (0% to 100%) and caches real-time radar scores.
 */
object BotRadarManager {
    private const val TAG = "BotRadarManager"

    data class RadarItem(
        val symbol: String,
        val cleanToken: String,
        val proximity: Int, // 0 to 100
        val isBreakout: Boolean,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    private val cachedScores = ConcurrentHashMap<String, RadarItem>()

    fun getScore(symbol: String): RadarItem? {
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol).uppercase(Locale.US)
        return cachedScores[clean]
    }

    fun getAllScores(): List<RadarItem> {
        return cachedScores.values.toList()
    }

    fun updateScore(symbol: String, proximity: Int, isBreakout: Boolean = proximity >= 100) {
        val clean = HyperliquidOrderManager.cleanSymbolString(symbol).uppercase(Locale.US)
        val formattedSymbol = if (!symbol.contains("/") && !symbol.endsWith("USDC", true)) "$clean/USDC" else symbol
        val clamped = proximity.coerceIn(0, 100)
        cachedScores[clean] = RadarItem(
            symbol = formattedSymbol,
            cleanToken = clean,
            proximity = clamped,
            isBreakout = isBreakout || clamped >= 100,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Resolves coin query identifier for Hyperliquid candle snapshots:
     * - BTC, ETH, SOL, HYPE
     * - PURR
     * - Other spot: @{universeIndex} (e.g., @142 for UBTC, @206 for UENA)
     */
    private fun getHyperliquidQueryCoin(token: String): String {
        val clean = HyperliquidOrderManager.cleanSymbolString(token).uppercase(Locale.US)
        if (clean in listOf("BTC", "ETH", "SOL", "HYPE")) return clean
        if (clean == "PURR") return "PURR"

        val spotInfo = HyperliquidOrderManager.getSpotAssetInfo(clean)
            ?: HyperliquidOrderManager.getSpotAssetInfo(token)
        if (spotInfo != null) {
            return if (spotInfo.baseToken.equals("PURR", true)) "PURR" else "@${spotInfo.universeIndex}"
        }
        return clean
    }

    /**
     * Fetches recent 30m candles from Hyperliquid info endpoint and computes breakout proximity score.
     */
    suspend fun fetchProximityForAsset(token: String): RadarItem = withContext(Dispatchers.IO) {
        val clean = HyperliquidOrderManager.cleanSymbolString(token).uppercase(Locale.US)
        val displaySymbol = if (!token.contains("/") && !token.endsWith("USDC", true)) "$clean/USDC" else token
        val queryCoin = getHyperliquidQueryCoin(clean)

        try {
            val now = System.currentTimeMillis()
            val startTime = now - (90L * 30L * 60L * 1000L) // 90 30m candles (~45h history)
            val url = URL("${HyperliquidOrderManager.HYPERLIQUID_API_URL}/info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            val reqObj = JSONObject().apply {
                put("type", "candleSnapshot")
                put("req", JSONObject().apply {
                    put("coin", queryCoin)
                    put("interval", "30m")
                    put("startTime", startTime)
                    put("endTime", now)
                })
            }

            conn.outputStream.use { os ->
                os.write(reqObj.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(resp)
                if (arr.length() >= 35) {
                    val candles = mutableListOf<BotStrategyChartView.Candle>()
                    for (i in 0 until arr.length()) {
                        val c = arr.getJSONObject(i)
                        candles.add(
                            BotStrategyChartView.Candle(
                                open = c.optString("o", "0").toDoubleOrNull() ?: 0.0,
                                high = c.optString("h", "0").toDoubleOrNull() ?: 0.0,
                                low = c.optString("l", "0").toDoubleOrNull() ?: 0.0,
                                close = c.optString("c", "0").toDoubleOrNull() ?: 0.0,
                                volume = c.optString("v", "0").toDoubleOrNull() ?: 0.0,
                                timestamp = c.optLong("t", 0L)
                            )
                        )
                    }
                    candles.sortBy { it.timestamp }
                    val proximity = BotStrategyChartView.computeProximityFromCandles(candles)
                    val item = RadarItem(
                        symbol = displaySymbol,
                        cleanToken = clean,
                        proximity = proximity,
                        isBreakout = proximity >= 100
                    )
                    cachedScores[clean] = item
                    return@withContext item
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch 30m candle history for radar token $clean: ${e.message}")
        }

        // Return existing score if present, otherwise default to 0%
        val existing = cachedScores[clean]
        if (existing != null) return@withContext existing

        val fallback = RadarItem(
            symbol = displaySymbol,
            cleanToken = clean,
            proximity = 0,
            isBreakout = false
        )
        cachedScores[clean] = fallback
        return@withContext fallback
    }

    /**
     * Refreshes proximity scores for all enabled assets in parallel.
     */
    suspend fun refreshAllEnabledAssets(context: Context): List<RadarItem> = coroutineScope {
        val enabledTokens = BotAssetPermissionManager.getEnabledAssets(context)
        if (enabledTokens.isEmpty()) return@coroutineScope emptyList()

        val tasks = enabledTokens.map { token ->
            async(Dispatchers.IO) {
                fetchProximityForAsset(token)
            }
        }
        val results = tasks.awaitAll()
        results.sortedWith(
            compareByDescending<RadarItem> { it.proximity }
                .thenBy { it.symbol }
        )
    }
}
