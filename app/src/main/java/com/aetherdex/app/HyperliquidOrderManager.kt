package com.aetherdex.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jcajce.provider.digest.Keccak
import org.json.JSONArray
import org.json.JSONObject
import org.msgpack.core.MessagePack
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Protocol Bridge for Hyperliquid L1 Perpetual DEX Order Execution.
 * Implements Hyperliquid's exact EIP-712 phantom agent signing scheme.
 */
object HyperliquidOrderManager {

    private const val TAG = "HyperliquidOrderMgr"
    const val HYPERLIQUID_API_URL = "https://api.hyperliquid.xyz"

    data class AssetInfo(
        val index: Int,
        val name: String,
        val szDecimals: Int,
        val maxLeverage: Int,
        val isOnlyIsolated: Boolean = false,
        var markPrice: Double = 0.0,
        var prevDayPrice: Double = 0.0,
        var dayVolumeUsd: Double = 0.0
    ) {
        val change24hPercent: Double
            get() = if (prevDayPrice > 0.0) ((markPrice - prevDayPrice) / prevDayPrice) * 100.0 else 0.0

        val displaySymbol: String
            get() = "$name/USDC"

        override fun toString(): String = displaySymbol
    }

    data class SpotAssetInfo(
        val universeIndex: Int,
        val l1AssetIndex: Int = 10000 + universeIndex,
        val name: String,
        val baseToken: String,
        val quoteToken: String = "USDC",
        val szDecimals: Int = 2,
        val weiDecimals: Int = 8,
        var markPrice: Double = 0.0,
        var prevDayPrice: Double = 0.0,
        var dayVolumeUsd: Double = 0.0
    ) {
        val change24hPercent: Double
            get() = if (prevDayPrice > 0.0) ((markPrice - prevDayPrice) / prevDayPrice) * 100.0 else 0.0

        val displaySymbol: String
            get() = if (name.contains("/")) name else "$name/USDC"

        override fun toString(): String = displaySymbol
    }

    data class SpotBalanceInfo(
        val coin: String,
        val total: Double,
        val hold: Double = 0.0
    ) {
        val available: Double
            get() = (total - hold).coerceAtLeast(0.0)
    }

    private val dynamicAssetMap = java.util.concurrent.ConcurrentHashMap<String, AssetInfo>()
    private val dynamicAssetList = java.util.concurrent.CopyOnWriteArrayList<AssetInfo>()
    @Volatile var isMetadataLoaded: Boolean = false

    private val dynamicSpotAssetMap = java.util.concurrent.ConcurrentHashMap<String, SpotAssetInfo>()
    private val dynamicSpotAssetList = java.util.concurrent.CopyOnWriteArrayList<SpotAssetInfo>()
    @Volatile var isSpotMetadataLoaded: Boolean = false

    suspend fun fetchSpotMarketMetadata(): List<SpotAssetInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$HYPERLIQUID_API_URL/info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val requestBody = JSONObject().apply {
                put("type", "spotMetaAndAssetCtxs")
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val rootArray = JSONArray(responseText)
                if (rootArray.length() >= 2) {
                    val metaObj = rootArray.getJSONObject(0)
                    val ctxsArray = rootArray.getJSONArray(1)

                    val universeArray = metaObj.optJSONArray("universe") ?: JSONArray()
                    val tokensArray = metaObj.optJSONArray("tokens") ?: JSONArray()

                    val tokenMap = mutableMapOf<Int, JSONObject>()
                    for (i in 0 until tokensArray.length()) {
                        val tok = tokensArray.getJSONObject(i)
                        tokenMap[tok.optInt("index", i)] = tok
                    }

                    val newList = mutableListOf<SpotAssetInfo>()

                    for (i in 0 until universeArray.length()) {
                        val u = universeArray.getJSONObject(i)
                        val idx = u.optInt("index", i)
                        val rawName = u.optString("name", "")

                        val tokenIndices = u.optJSONArray("tokens")
                        val t0Idx = tokenIndices?.optInt(0, -1) ?: -1
                        val t1Idx = tokenIndices?.optInt(1, -1) ?: -1

                        val t0Obj = tokenMap[t0Idx]
                        val t1Obj = tokenMap[t1Idx]

                        val t0Name = t0Obj?.optString("name", "") ?: ""
                        val t1Name = t1Obj?.optString("name", "USDC") ?: "USDC"
                        val szDec = t0Obj?.optInt("szDecimals", 2) ?: 2
                        val weiDec = t0Obj?.optInt("weiDecimals", 8) ?: 8

                        val displayName = if (rawName.startsWith("@") || rawName.isBlank()) {
                            if (t0Name.isNotBlank()) "$t0Name/$t1Name" else "SPOT-$idx"
                        } else rawName

                        val spotInfo = SpotAssetInfo(
                            universeIndex = idx,
                            l1AssetIndex = 10000 + idx,
                            name = displayName,
                            baseToken = if (t0Name.isNotBlank()) t0Name else displayName.substringBefore("/"),
                            quoteToken = t1Name,
                            szDecimals = szDec,
                            weiDecimals = weiDec
                        )

                        if (idx < ctxsArray.length()) {
                            val ctx = ctxsArray.optJSONObject(idx)
                            if (ctx != null) {
                                spotInfo.markPrice = ctx.optString("markPx", "0").toDoubleOrNull() ?: 0.0
                                spotInfo.prevDayPrice = ctx.optString("prevDayPx", "0").toDoubleOrNull() ?: 0.0
                                spotInfo.dayVolumeUsd = ctx.optString("dayNtlVlm", "0").toDoubleOrNull() ?: 0.0
                            }
                        }

                        if (!displayName.startsWith("@") && !displayName.startsWith("SPOT-")) {
                            newList.add(spotInfo)
                            dynamicSpotAssetMap[cleanSymbolString(displayName)] = spotInfo
                            dynamicSpotAssetMap[cleanSymbolString(spotInfo.baseToken)] = spotInfo
                        }
                    }

                    if (newList.isNotEmpty()) {
                        dynamicSpotAssetList.clear()
                        dynamicSpotAssetList.addAll(newList)
                        isSpotMetadataLoaded = true
                        Log.i(TAG, "Successfully loaded ${newList.size} Spot pairs from Hyperliquid L1")
                        return@withContext newList
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Hyperliquid Spot market metadata", e)
        }
        return@withContext dynamicSpotAssetList.toList()
    }

    suspend fun fetchSpotClearinghouseState(userAddress: String): Map<String, SpotBalanceInfo> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, SpotBalanceInfo>()
        if (userAddress.isBlank()) return@withContext result
        try {
            val url = URL("$HYPERLIQUID_API_URL/info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val requestBody = JSONObject().apply {
                put("type", "spotClearinghouseState")
                put("user", userAddress)
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val rootObj = JSONObject(responseText)
                val balancesArray = rootObj.optJSONArray("balances") ?: JSONArray()
                for (i in 0 until balancesArray.length()) {
                    val b = balancesArray.getJSONObject(i)
                    val coin = b.optString("coin", "")
                    val total = b.optString("total", "0").toDoubleOrNull() ?: 0.0
                    val hold = b.optString("hold", "0").toDoubleOrNull() ?: 0.0
                    if (coin.isNotBlank()) {
                        result[coin] = SpotBalanceInfo(coin, total, hold)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch spotClearinghouseState for $userAddress", e)
        }
        return@withContext result
    }

    fun getAllSpotAssetInfos(): List<SpotAssetInfo> {
        if (dynamicSpotAssetList.isNotEmpty()) {
            return dynamicSpotAssetList.toList()
        }
        val purr = SpotAssetInfo(0, 10000, "PURR/USDC", "PURR", "USDC", 0, 5)
        val hfun = SpotAssetInfo(1, 10001, "HFUN/USDC", "HFUN", "USDC", 2, 8)
        val hype = SpotAssetInfo(150, 10150, "HYPE/USDC", "HYPE", "USDC", 2, 8)
        val btc = SpotAssetInfo(200, 10200, "BTC/USDC", "BTC", "USDC", 5, 8)
        val eth = SpotAssetInfo(201, 10201, "ETH/USDC", "ETH", "USDC", 4, 8)
        val sol = SpotAssetInfo(202, 10202, "SOL/USDC", "SOL", "USDC", 2, 8)
        return listOf(purr, hfun, hype, btc, eth, sol)
    }

    fun getSpotAssetInfo(symbol: String): SpotAssetInfo? {
        val clean = cleanSymbolString(symbol)
        return dynamicSpotAssetMap[clean]
    }

    fun getSpotAssetIndex(symbol: String): Int {
        val info = getSpotAssetInfo(symbol)
        if (info != null) return info.l1AssetIndex
        val clean = cleanSymbolString(symbol)
        if (clean == "PURR") return 10000
        if (clean == "HFUN") return 10001
        if (clean == "HYPE") return 10150
        return 10000
    }

    // Verified fallback asset indices on Hyperliquid L1 (matches exact universe ordering)
    private val FALLBACK_ASSET_MAP = mapOf(
        "BTC" to 0,
        "ETH" to 1,
        "ATOM" to 2,
        "MATIC" to 3,
        "DYDX" to 4,
        "SOL" to 5,
        "AVAX" to 6,
        "BNB" to 7,
        "APE" to 8,
        "OP" to 9,
        "LTC" to 10,
        "ARB" to 11,
        "DOGE" to 12,
        "INJ" to 13,
        "SUI" to 14,
        "kPEPE" to 15,
        "CRV" to 16,
        "LDO" to 17,
        "LINK" to 18,
        "STX" to 19,
        "RNDR" to 20,
        "CFX" to 21,
        "FTM" to 22,
        "GMX" to 23,
        "SNX" to 24,
        "XRP" to 25,
        "BCH" to 26,
        "APT" to 27,
        "AAVE" to 28,
        "COMP" to 29,
        "PAXG" to 187,
        "HYPE" to 159,
        "PURR" to 152
    )

    private val FALLBACK_SZ_DECIMALS = mapOf(
        0 to 5,  // BTC
        1 to 4,  // ETH
        2 to 2,  // ATOM
        5 to 2,  // SOL
        6 to 2,  // AVAX
        7 to 3,  // BNB
        12 to 0, // DOGE
        14 to 1, // SUI
        18 to 1, // LINK
        25 to 0, // XRP
        26 to 3, // BCH
        28 to 2, // AAVE
        187 to 3,// PAXG
        159 to 2 // HYPE
    )

    init {
        // Initialize static fallback map immediately
        FALLBACK_ASSET_MAP.forEach { (coin, idx) ->
            val sz = FALLBACK_SZ_DECIMALS[idx] ?: 2
            val info = AssetInfo(idx, coin, sz, 20)
            dynamicAssetMap[coin] = info
        }
    }

    /**
     * Fetches full perpetual market metadata dynamically from Hyperliquid's info API ({"type": "metaAndAssetCtxs"}).
     * Dynamically maps every symbol (BTC, ETH, SOL, XRP, GOLD, NVDA, TSLA, etc.) to its exact L1 asset index,
     * size decimal precision (szDecimals), max leverage, mark price, and 24h change stats.
     */
    suspend fun fetchMarketMetadata(): List<AssetInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$HYPERLIQUID_API_URL/info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val body = JSONObject().apply {
                put("type", "metaAndAssetCtxs")
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val array = JSONArray(responseText)
                if (array.length() > 0) {
                    val metaObj = array.getJSONObject(0)
                    val universeArray = metaObj.getJSONArray("universe")
                    val ctxsArray = if (array.length() > 1) array.getJSONArray(1) else JSONArray()

                    val newList = mutableListOf<AssetInfo>()
                    val newMap = mutableMapOf<String, AssetInfo>()

                    for (i in 0 until universeArray.length()) {
                        val coinObj = universeArray.getJSONObject(i)
                        val name = coinObj.getString("name")
                        val szDecimals = coinObj.getInt("szDecimals")
                        val maxLeverage = coinObj.optInt("maxLeverage", 50)
                        val onlyIsolated = coinObj.optBoolean("onlyIsolated", false)

                        var markPx = 0.0
                        var prevDayPx = 0.0
                        var dayVol = 0.0

                        if (i < ctxsArray.length()) {
                            val ctxObj = ctxsArray.getJSONObject(i)
                            markPx = ctxObj.optString("markPx", "0").toDoubleOrNull() ?: 0.0
                            prevDayPx = ctxObj.optString("prevDayPx", "0").toDoubleOrNull() ?: 0.0
                            dayVol = ctxObj.optString("dayNtlVlm", "0").toDoubleOrNull() ?: 0.0
                        }

                        val info = AssetInfo(
                            index = i,
                            name = name,
                            szDecimals = szDecimals,
                            maxLeverage = maxLeverage,
                            isOnlyIsolated = onlyIsolated,
                            markPrice = markPx,
                            prevDayPrice = prevDayPx,
                            dayVolumeUsd = dayVol
                        )
                        newList.add(info)
                        newMap[name.uppercase()] = info
                    }

                    dynamicAssetMap.putAll(newMap)
                    dynamicAssetList.clear()
                    dynamicAssetList.addAll(newList)
                    isMetadataLoaded = true
                    Log.i(TAG, "Successfully loaded ${newList.size} perpetual markets dynamically from Hyperliquid L1")
                    return@withContext newList
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Hyperliquid market metadata dynamically", e)
        }
        return@withContext dynamicAssetList.toList()
    }

    fun getAllAssetInfos(): List<AssetInfo> {
        if (dynamicAssetList.isNotEmpty()) {
            return dynamicAssetList.toList()
        }
        return dynamicAssetMap.values.toList()
    }

    fun cleanSymbolString(symbol: String): String {
        return symbol.trim()
            .replace("/", "")
            .replace("USDC", "")
            .replace("USDT", "")
            .replace("BUSD", "")
            .replace("PERP", "")
            .uppercase()
    }

    fun getAssetIndex(symbol: String): Int {
        val clean = cleanSymbolString(symbol)
        dynamicAssetMap[clean]?.let { return it.index }
        return FALLBACK_ASSET_MAP[clean] ?: 0
    }

    fun getAssetSzDecimals(asset: Int): Int {
        if (asset >= 0 && asset < dynamicAssetList.size) {
            return dynamicAssetList[asset].szDecimals
        }
        return FALLBACK_SZ_DECIMALS[asset] ?: 2
    }

    fun getMaxLeverage(asset: Int): Int {
        if (asset >= 0 && asset < dynamicAssetList.size) {
            return dynamicAssetList[asset].maxLeverage
        }
        return 20
    }

    fun getAssetInfo(symbol: String): AssetInfo? {
        val clean = cleanSymbolString(symbol)
        return dynamicAssetMap[clean]
    }

    fun getAvailableSymbols(): List<String> {
        if (dynamicAssetList.isNotEmpty()) {
            return dynamicAssetList.map { "${it.name}/USDC" }
        }
        return FALLBACK_ASSET_MAP.keys.map { "$it/USDC" }
    }

    data class HyperliquidOrderPayload(
        val asset: Int,
        val isBuy: Boolean,
        val limitPx: Double,
        val sz: Double,
        val reduceOnly: Boolean,
        val orderType: String, // "MARKET" or "LIMIT"
        val timestamp: Long = System.currentTimeMillis(),
        val cloid: String = String.format(Locale.US, "0x%032x", System.currentTimeMillis()),
        val tpPrice: Double = 0.0,  // Take Profit price (0 if not set)
        val slPrice: Double = 0.0,  // Stop Loss price (0 if not set)
        val tif: String = if (orderType == "MARKET") "Ioc" else "Gtc"
    )

    /**
     * Rounds a price according to Hyperliquid L1 rules:
     * 1. Max 5 significant figures
     * 2. Max (6 - szDecimals) decimal places for perps
     */
    fun roundPriceToHyperliquidRules(price: Double, szDecimals: Int = 4): Double {
        if (price <= 0.0) return 0.0
        val maxDecimals = (6 - szDecimals).coerceAtLeast(0)

        // Round to 5 significant figures
        val d = Math.ceil(Math.log10(Math.abs(price)))
        val power = 5 - d.toInt()
        val magnitude = Math.pow(10.0, power.toDouble())
        val shifted = Math.round(price * magnitude)
        val sigFigRounded = shifted / magnitude

        // Enforce max decimals
        val decimalFactor = Math.pow(10.0, maxDecimals.toDouble())
        return Math.round(sigFigRounded * decimalFactor) / decimalFactor
    }

    /**
     * Rounds size to the asset's szDecimals precision.
     * Ensures size is at least 1 step size (10^-szDecimals) if sz > 0.
     */
    fun roundSizeToHyperliquidRules(sz: Double, szDecimals: Int): Double {
        if (sz <= 0.0) return 0.0
        val factor = Math.pow(10.0, szDecimals.toDouble())
        val rounded = Math.round(sz * factor) / factor
        val minStep = 1.0 / factor
        return if (rounded < minStep) minStep else rounded
    }

    /**
     * Formats a Double to a decimal string without scientific notation or trailing zeros.
     */
    fun formatDecimal(value: Double): String {
        if (value == 0.0) return "0"
        val formatted = String.format(Locale.US, "%.8f", value)
        return formatted.trimEnd('0').trimEnd('.')
    }

    /**
     * Keccak-256 hash using BouncyCastle (Android-compatible).
     */
    fun keccak256(data: ByteArray): ByteArray {
        val digest = Keccak.Digest256()
        digest.update(data)
        return digest.digest()
    }

    /**
     * Encodes the Hyperliquid action using msgpack.
     * Follows Hyperliquid's exact field order for order and updateLeverage actions.
     */
    fun encodeActionForMsgpack(action: JSONObject): ByteArray {
        val type = action.optString("type", "")
        if (type == "updateLeverage") {
            val packer = MessagePack.newDefaultBufferPacker()
            try {
                packer.packMapHeader(4)
                packer.packString("type")
                packer.packString("updateLeverage")
                packer.packString("asset")
                packer.packInt(action.getInt("asset"))
                packer.packString("isCross")
                packer.packBoolean(action.getBoolean("isCross"))
                packer.packString("leverage")
                packer.packInt(action.getInt("leverage"))
                packer.close()
                return packer.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "Msgpack encoding for updateLeverage failed", e)
                throw e
            }
        }

        if (type == "spotUser") {
            val packer = MessagePack.newDefaultBufferPacker()
            try {
                if (action.has("classTransfer")) {
                    packer.packMapHeader(2)
                    packer.packString("type")
                    packer.packString("spotUser")
                    packer.packString("classTransfer")
                    val ct = action.getJSONObject("classTransfer")
                    packer.packMapHeader(2)
                    packer.packString("usdc")
                    val usdcVal = ct.optLong("usdc", 0L)
                    packer.packLong(usdcVal)
                    packer.packString("toPerp")
                    packer.packBoolean(ct.getBoolean("toPerp"))
                }
                packer.close()
                return packer.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "Msgpack encoding for spotUser failed", e)
                throw e
            }
        }

        if (type == "cancel") {
            val packer = MessagePack.newDefaultBufferPacker()
            try {
                packer.packMapHeader(2)
                packer.packString("type")
                packer.packString("cancel")
                packer.packString("cancels")
                val cancelsArray = action.getJSONArray("cancels")
                packer.packArrayHeader(cancelsArray.length())
                for (i in 0 until cancelsArray.length()) {
                    val item = cancelsArray.getJSONObject(i)
                    packer.packMapHeader(2)
                    packer.packString("a")
                    packer.packInt(item.getInt("a"))
                    packer.packString("o")
                    packer.packLong(item.getLong("o"))
                }
                packer.close()
                return packer.toByteArray()
            } catch (e: Exception) {
                Log.e(TAG, "Msgpack encoding for cancel failed", e)
                throw e
            }
        }

        val packer = MessagePack.newDefaultBufferPacker()
        
        try {
            val hasGrouping = action.has("grouping") && !action.isNull("grouping")
            val mapSize = if (hasGrouping) 3 else 2
            
            // Action is a map with type, orders, and optional grouping
            packer.packMapHeader(mapSize)
            
            // "type" field
            packer.packString("type")
            packer.packString(action.getString("type"))
            
            // "orders" field (array)
            packer.packString("orders")
            val orders = action.getJSONArray("orders")
            packer.packArrayHeader(orders.length())
            
            for (i in 0 until orders.length()) {
                val order = orders.getJSONObject(i)
                val hasCloid = order.has("c") && !order.isNull("c") && order.getString("c").isNotEmpty()
                
                // Hyperliquid expects 7 fields if cloid is present, or 6 fields if not
                packer.packMapHeader(if (hasCloid) 7 else 6) // a, b, p, s, r, t, [c]
                
                // a (asset)
                packer.packString("a")
                packer.packInt(order.getInt("a"))
                
                // b (isBuy)
                packer.packString("b")
                packer.packBoolean(order.getBoolean("b"))
                
                // p (price)
                packer.packString("p")
                packer.packString(order.getString("p"))
                
                // s (size)
                packer.packString("s")
                packer.packString(order.getString("s"))
                
                // r (reduceOnly)
                packer.packString("r")
                packer.packBoolean(order.getBoolean("r"))
                
                // t (order type)
                packer.packString("t")
                val t = order.getJSONObject("t")
                packer.packMapHeader(1)
                packer.packString("limit")
                val limit = t.getJSONObject("limit")
                packer.packMapHeader(1)
                packer.packString("tif")
                packer.packString(limit.getString("tif"))
                
                // c (cloid) - client order ID
                if (hasCloid) {
                    packer.packString("c")
                    packer.packString(order.getString("c"))
                }
            }
            
            if (hasGrouping) {
                packer.packString("grouping")
                packer.packString(action.getString("grouping"))
            }
            
            packer.close()
            val result = packer.toByteArray()
            Log.i(TAG, "Msgpack encoded action size: ${result.size} bytes")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Msgpack encoding failed", e)
            throw e
        }
    }

    /**
     * Creates the L1 action hash for Hyperliquid phantom agent signing.
     * 1. Msgpack-encode the action
     * 2. Append nonce as uint64 big-endian (8 bytes)
     * 3. Append vault marker (0x00 for no vault)
     * 4. Keccak-256 hash the result
     */
    fun createL1ActionHash(action: JSONObject, nonce: Long, vaultAddress: String? = null): ByteArray {
        val actionBytes = encodeActionForMsgpack(action)
        
        val nonceBytes = ByteArray(8)
        for (i in 0..7) {
            nonceBytes[i] = ((nonce shr (56 - i * 8)) and 0xFF).toByte()
        }
        
        val vaultMarker = if (!vaultAddress.isNullOrEmpty()) {
            val vaultBytes = ByteArray(21)
            vaultBytes[0] = 0x01.toByte()
            val addressBytes = Numeric.hexStringToByteArray(vaultAddress)
            System.arraycopy(addressBytes, 0, vaultBytes, 1, 20)
            vaultBytes
        } else {
            byteArrayOf(0x00.toByte())
        }
        
        val dataToHash = actionBytes + nonceBytes + vaultMarker
        val hash = keccak256(dataToHash)
        
        Log.i(TAG, "L1 Action hash (connectionId): ${Numeric.toHexString(hash)}")
        return hash
    }

    private val DOMAIN_TYPEHASH = keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".toByteArray(Charsets.UTF_8))
    private val AGENT_TYPEHASH = keccak256("Agent(string source,bytes32 connectionId)".toByteArray(Charsets.UTF_8))

    private fun pad32(bytes: ByteArray): ByteArray {
        if (bytes.size == 32) return bytes
        if (bytes.size > 32) return bytes.copyOfRange(bytes.size - 32, bytes.size)
        val out = ByteArray(32)
        System.arraycopy(bytes, 0, out, 32 - bytes.size, bytes.size)
        return out
    }

    fun calculateDomainSeparator(): ByteArray {
        val nameHash = keccak256("Exchange".toByteArray(Charsets.UTF_8))
        val versionHash = keccak256("1".toByteArray(Charsets.UTF_8))
        val chainIdBytes = pad32(java.math.BigInteger.valueOf(1337L).toByteArray())
        val verifyingContractBytes = ByteArray(32)

        val buffer = ByteArray(32 * 5)
        System.arraycopy(DOMAIN_TYPEHASH, 0, buffer, 0, 32)
        System.arraycopy(nameHash, 0, buffer, 32, 32)
        System.arraycopy(versionHash, 0, buffer, 64, 32)
        System.arraycopy(chainIdBytes, 0, buffer, 96, 32)
        System.arraycopy(verifyingContractBytes, 0, buffer, 128, 32)

        return keccak256(buffer)
    }

    fun calculateAgentStructHash(connectionId: ByteArray): ByteArray {
        val sourceHash = keccak256("a".toByteArray(Charsets.UTF_8))
        val connectionIdPadded = pad32(connectionId)

        val buffer = ByteArray(32 * 3)
        System.arraycopy(AGENT_TYPEHASH, 0, buffer, 0, 32)
        System.arraycopy(sourceHash, 0, buffer, 32, 32)
        System.arraycopy(connectionIdPadded, 0, buffer, 64, 32)

        return keccak256(buffer)
    }

    fun calculateEip712Digest(connectionId: ByteArray): ByteArray {
        val domainSeparator = calculateDomainSeparator()
        val structHash = calculateAgentStructHash(connectionId)

        val buffer = ByteArray(2 + 32 + 32)
        buffer[0] = 0x19.toByte()
        buffer[1] = 0x01.toByte()
        System.arraycopy(domainSeparator, 0, buffer, 2, 32)
        System.arraycopy(structHash, 0, buffer, 34, 32)

        return keccak256(buffer)
    }

    /**
     * Computes the EIP-712 digest for user-signed usdClassTransfer action.
     * Domain: HyperliquidSignTransaction (chainId = 421614 / 0x66eee)
     * Struct: UsdClassTransfer(string hyperliquidChain,string amount,bool toPerp,uint64 nonce)
     */
    fun calculateUsdClassTransferEip712Digest(
        hyperliquidChain: String,
        amount: String,
        toPerp: Boolean,
        nonce: Long
    ): ByteArray {
        val nameHash = keccak256("HyperliquidSignTransaction".toByteArray(Charsets.UTF_8))
        val versionHash = keccak256("1".toByteArray(Charsets.UTF_8))
        val chainIdBytes = pad32(java.math.BigInteger.valueOf(421614L).toByteArray())
        val verifyingContractBytes = ByteArray(32)

        val domainBuffer = ByteArray(32 * 5)
        System.arraycopy(DOMAIN_TYPEHASH, 0, domainBuffer, 0, 32)
        System.arraycopy(nameHash, 0, domainBuffer, 32, 32)
        System.arraycopy(versionHash, 0, domainBuffer, 64, 32)
        System.arraycopy(chainIdBytes, 0, domainBuffer, 96, 32)
        System.arraycopy(verifyingContractBytes, 0, domainBuffer, 128, 32)
        val domainSeparator = keccak256(domainBuffer)

        val typeHash = keccak256(
            "HyperliquidTransaction:UsdClassTransfer(string hyperliquidChain,string amount,bool toPerp,uint64 nonce)".toByteArray(Charsets.UTF_8)
        )
        val chainHash = keccak256(hyperliquidChain.toByteArray(Charsets.UTF_8))
        val amountHash = keccak256(amount.toByteArray(Charsets.UTF_8))
        val toPerpBytes = pad32(byteArrayOf(if (toPerp) 1.toByte() else 0.toByte()))
        val nonceBytes = pad32(java.math.BigInteger.valueOf(nonce).toByteArray())

        val structBuffer = ByteArray(32 * 5)
        System.arraycopy(typeHash, 0, structBuffer, 0, 32)
        System.arraycopy(chainHash, 0, structBuffer, 32, 32)
        System.arraycopy(amountHash, 0, structBuffer, 64, 32)
        System.arraycopy(toPerpBytes, 0, structBuffer, 96, 32)
        System.arraycopy(nonceBytes, 0, structBuffer, 128, 32)
        val structHash = keccak256(structBuffer)

        val digestBuffer = ByteArray(2 + 32 + 32)
        digestBuffer[0] = 0x19.toByte()
        digestBuffer[1] = 0x01.toByte()
        System.arraycopy(domainSeparator, 0, digestBuffer, 2, 32)
        System.arraycopy(structHash, 0, digestBuffer, 34, 32)

        return keccak256(digestBuffer)
    }

    /**
     * Submits an approveAgent L1 action to Hyperliquid exchange API.
     * Approves an Agent Key (agentAddress) for trading authority.
     */
    suspend fun approveAgentOnL1(
        masterPrivateKeyHex: String,
        agentAddress: String,
        agentName: String = "AetherDex Cloud Bot Engine"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val nonce = System.currentTimeMillis()
            val action = JSONObject().apply {
                put("type", "approveAgent")
                put("hyperliquidChain", "Mainnet")
                put("signatureChainId", "0xa4b1")
                put("agentAddress", agentAddress)
                put("agentName", agentName)
                put("nonce", nonce)
            }

            val connectionId = createL1ActionHash(action, nonce)
            val digest = calculateEip712Digest(connectionId)

            val cleanPk = masterPrivateKeyHex.removePrefix("0x")
            val privateKeyBigInt = java.math.BigInteger(cleanPk, 16)
            val keyPair = ECKeyPair.create(privateKeyBigInt)
            val sigData = Sign.signMessage(digest, keyPair, false)

            val rHex = Numeric.toHexString(sigData.r)
            val sHex = Numeric.toHexString(sigData.s)
            val vInt = if (sigData.v.isNotEmpty()) (sigData.v[0].toInt() and 0xFF) else 27


            val signatureObj = JSONObject().apply {
                put("r", rHex)
                put("s", sHex)
                put("v", vInt)
            }

            val payload = JSONObject().apply {
                put("action", action)
                put("nonce", nonce)
                put("signature", signatureObj)
            }

            val url = URL("$HYPERLIQUID_API_URL/exchange")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }

            Log.i(TAG, "approveAgent L1 response ($responseCode): $responseText")
            return@withContext responseCode == 200 && !responseText.contains("\"status\":\"err\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to approveAgent on L1", e)
            return@withContext false
        }
    }

    /**
     * Builds the EIP-712 typed data JSON for Hyperliquid phantom agent signing.
     */

    fun buildEip712TypedDataJson(
        action: JSONObject,
        nonce: Long,
        vaultAddress: String? = null
    ): String {
        val connectionId = createL1ActionHash(action, nonce, vaultAddress)
        val connectionIdHex = Numeric.toHexString(connectionId)
        
        val domain = JSONObject().apply {
            put("name", "Exchange")
            put("version", "1")
            put("chainId", 1337)
            put("verifyingContract", "0x0000000000000000000000000000000000000000")
        }
        
        val types = JSONObject().apply {
            put("EIP712Domain", JSONArray().apply {
                put(JSONObject().apply { put("name", "name"); put("type", "string") })
                put(JSONObject().apply { put("name", "version"); put("type", "string") })
                put(JSONObject().apply { put("name", "chainId"); put("type", "uint256") })
                put(JSONObject().apply { put("name", "verifyingContract"); put("type", "address") })
            })
            put("Agent", JSONArray().apply {
                put(JSONObject().apply { put("name", "source"); put("type", "string") })
                put(JSONObject().apply { put("name", "connectionId"); put("type", "bytes32") })
            })
        }
        
        val message = JSONObject().apply {
            put("source", "a")
            put("connectionId", connectionIdHex)
        }
        
        val root = JSONObject().apply {
            put("domain", domain)
            put("types", types)
            put("primaryType", "Agent")
            put("message", message)
        }
        
        return root.toString()
    }

    /**
     * Builds standard EIP-712 JSON payload for WalletConnect external wallet fallback.
     */
    fun buildEip712TypedDataJson(
        orderPayload: HyperliquidOrderPayload
    ): String {
        val domain = JSONObject().apply {
            put("name", "Exchange")
            put("version", "1")
            put("chainId", 1337)
            put("verifyingContract", "0x0000000000000000000000000000000000000000")
        }

        val types = JSONObject().apply {
            put("EIP712Domain", JSONArray().apply {
                put(JSONObject().apply { put("name", "name"); put("type", "string") })
                put(JSONObject().apply { put("name", "version"); put("type", "string") })
                put(JSONObject().apply { put("name", "chainId"); put("type", "uint256") })
                put(JSONObject().apply { put("name", "verifyingContract"); put("type", "address") })
            })
            put("Agent", JSONArray().apply {
                put(JSONObject().apply { put("name", "source"); put("type", "string") })
                put(JSONObject().apply { put("name", "connectionId"); put("type", "bytes32") })
            })
        }

        val actionObj = buildOrderActionObject(orderPayload)
        val connectionId = createL1ActionHash(actionObj, orderPayload.timestamp, null)

        val message = JSONObject().apply {
            put("source", "a")
            put("connectionId", Numeric.toHexString(connectionId))
        }

        val root = JSONObject().apply {
            put("domain", domain)
            put("types", types)
            put("primaryType", "Agent")
            put("message", message)
        }

        return root.toString()
    }

    /**
     * Signs raw 32-byte EIP-712 digest using Web3j Secp256k1 WITHOUT adding personal_sign prefix.
     */
    fun signEip712Digest(privateKeyHex: String, eip712Digest: ByteArray): Triple<String, String, Int> {
        val cleanPk = privateKeyHex.trim().removePrefix("0x")
        val privateKey = Numeric.toBigInt(cleanPk)
        val publicKey = Sign.publicKeyFromPrivate(privateKey)
        val keyPair = ECKeyPair(privateKey, publicKey)

        // Sign 32-byte hash directly (false = do NOT add \x19Ethereum Signed Message prefix)
        val signatureData = Sign.signMessage(eip712Digest, keyPair, false)

        val r = Numeric.toHexStringWithPrefix(Numeric.toBigInt(signatureData.r))
        val s = Numeric.toHexStringWithPrefix(Numeric.toBigInt(signatureData.s))
        val v = Numeric.toBigInt(signatureData.v).toInt()

        try {
            val recoveredPubKey = Sign.signedMessageToKey(eip712Digest, signatureData)
            val recoveredAddress = "0x" + org.web3j.crypto.Keys.getAddress(recoveredPubKey)
            Log.i(TAG, "EIP-712 Signature Generated - Address Verification: $recoveredAddress")
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify signature recovery address", e)
        }

        return Triple(r, s, v)
    }

    /**
     * Legacy signature wrapper for backward compatibility.
     */
    fun signEip712Agent(privateKeyHex: String, eip712Json: String): Triple<String, String, Int> {
        val root = JSONObject(eip712Json)
        val message = root.getJSONObject("message")
        val connectionIdHex = message.getString("connectionId")
        val connectionIdBytes = Numeric.hexStringToByteArray(connectionIdHex)
        val digest = calculateEip712Digest(connectionIdBytes)
        return signEip712Digest(privateKeyHex, digest)
    }

    fun buildOrderActionObject(payload: HyperliquidOrderPayload): JSONObject {
        val ordersArray = JSONArray()
        ordersArray.put(JSONObject().apply {
            put("a", payload.asset)
            put("b", payload.isBuy)
            put("p", formatDecimal(payload.limitPx))
            put("s", formatDecimal(payload.sz))
            put("r", payload.reduceOnly)
            put("t", JSONObject().apply {
                put("limit", JSONObject().apply { put("tif", payload.tif) })
            })
            if (payload.cloid.isNotEmpty()) {
                put("c", payload.cloid)
            }
        })

        return JSONObject().apply {
            put("type", "order")
            put("orders", ordersArray)
            put("grouping", "na")
        }
    }

    fun buildCloseOrderActionObject(
        asset: Int,
        isLong: Boolean,
        sizeCrypto: Double,
        currentPrice: Double
    ): JSONObject {
        val szDecimals = getAssetSzDecimals(asset)
        // Slippage limit price for IOC market close (sell long at -5%, buy short at +5%)
        val rawClosePrice = if (isLong) currentPrice * 0.95 else currentPrice * 1.05
        val roundedClosePrice = roundPriceToHyperliquidRules(rawClosePrice, szDecimals)
        val roundedSize = roundSizeToHyperliquidRules(sizeCrypto, szDecimals)

        val ordersArray = JSONArray()
        ordersArray.put(JSONObject().apply {
            put("a", asset)
            put("b", !isLong)
            put("p", formatDecimal(roundedClosePrice))
            put("s", formatDecimal(roundedSize))
            put("r", true)
            put("t", JSONObject().apply {
                put("limit", JSONObject().apply { put("tif", "Ioc") })
            })
        })

        return JSONObject().apply {
            put("type", "order")
            put("orders", ordersArray)
            put("grouping", "na")
        }
    }

    /**
     * Broadcasts signed order to Hyperliquid Exchange API endpoint.
     */
    suspend fun submitSignedOrder(
        userAddress: String,
        signatureHex: String,
        payload: HyperliquidOrderPayload
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$HYPERLIQUID_API_URL/exchange")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val actionObj = buildOrderActionObject(payload)

            val cleanSig = signatureHex.trim().removePrefix("0x")
            val rHex = if (cleanSig.length >= 64) "0x" + cleanSig.substring(0, 64) else "0x0"
            val sHex = if (cleanSig.length >= 128) "0x" + cleanSig.substring(64, 128) else "0x0"
            val vVal = if (cleanSig.length >= 130) cleanSig.substring(128, 130).toIntOrNull(16) ?: 27 else 27

            val sigObj = JSONObject().apply {
                put("r", rHex)
                put("s", sHex)
                put("v", vVal)
            }

            val requestBody = JSONObject().apply {
                put("action", actionObj)
                put("nonce", payload.timestamp)
                put("signature", sigObj)
                put("vaultAddress", null)
            }

            val requestJsonString = requestBody.toString(2)
            Log.i(TAG, "=== HYPERLIQUID REQUEST BODY ===")
            Log.i(TAG, requestJsonString)
            Log.i(TAG, "=================================")

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val responseText = if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }

            Log.i(TAG, "=== HYPERLIQUID RESPONSE ===")
            Log.i(TAG, "HTTP Code: $code")
            Log.i(TAG, "Response: $responseText")
            Log.i(TAG, "==========================")

            val isSuccess = code == 200 && !responseText.contains("\"error\"") && !responseText.contains("\"err\"")
            isSuccess to responseText
        } catch (e: Exception) {
            Log.e(TAG, "=== ORDER SUBMISSION ERROR ===", e)
            false to "Order submission failed: ${e.message}"
        }
    }

    /**
     * Submits a reduce-only market order to close a position on Hyperliquid.
     */
    suspend fun closePositionOnHyperliquid(
        userAddress: String,
        signatureHex: String,
        asset: Int,
        isLong: Boolean,
        sizeCrypto: Double,
        currentPrice: Double,
        nonce: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$HYPERLIQUID_API_URL/exchange")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val actionObj = buildCloseOrderActionObject(asset, isLong, sizeCrypto, currentPrice)

            val cleanSig = signatureHex.trim().removePrefix("0x")
            val rHex = if (cleanSig.length >= 64) "0x" + cleanSig.substring(0, 64) else "0x0"
            val sHex = if (cleanSig.length >= 128) "0x" + cleanSig.substring(64, 128) else "0x0"
            val vVal = if (cleanSig.length >= 130) cleanSig.substring(128, 130).toIntOrNull(16) ?: 27 else 27

            val sigObj = JSONObject().apply {
                put("r", rHex)
                put("s", sHex)
                put("v", vVal)
            }

            val requestBody = JSONObject().apply {
                put("action", actionObj)
                put("nonce", nonce)
                put("signature", sigObj)
                put("vaultAddress", null)
            }

            Log.i(TAG, "=== HYPERLIQUID CLOSE ORDER ===")
            Log.i(TAG, requestBody.toString(2))
            Log.i(TAG, "==================================")

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val responseText = if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }

            Log.i(TAG, "=== CLOSE ORDER RESPONSE ===")
            Log.i(TAG, "HTTP Code: $code")
            Log.i(TAG, "Response: $responseText")
            Log.i(TAG, "============================")

            val isSuccess = code == 200 && !responseText.contains("\"error\"") && !responseText.contains("\"err\"")
            isSuccess to responseText
        } catch (e: Exception) {
            Log.e(TAG, "=== CLOSE ORDER ERROR ===", e)
            false to "Close order failed: ${e.message}"
        }
    }



    data class HyperliquidOrderResult(
        val success: Boolean,
        val responseMsg: String,
        val fillAvgPx: Double? = null,
        val filledSz: Double? = null,
        val oid: Long? = null
    )

    fun parseOrderResponse(responseText: String): HyperliquidOrderResult {
        try {
            val root = JSONObject(responseText)
            val status = root.optString("status", "")
            if (status == "ok") {
                val responseObj = root.optJSONObject("response")
                val dataObj = responseObj?.optJSONObject("data")
                val statusesArray = dataObj?.optJSONArray("statuses")
                if (statusesArray != null && statusesArray.length() > 0) {
                    val firstStatus = statusesArray.optJSONObject(0)
                    if (firstStatus != null) {
                        if (firstStatus.has("filled")) {
                            val filledObj = firstStatus.getJSONObject("filled")
                            val avgPx = filledObj.optString("avgPx", "0").toDoubleOrNull() ?: 0.0
                            val totalSz = filledObj.optString("totalSz", "0").toDoubleOrNull() ?: 0.0
                            val oid = filledObj.optLong("oid", 0L)
                            return HyperliquidOrderResult(
                                success = true,
                                responseMsg = responseText,
                                fillAvgPx = if (avgPx > 0.0) avgPx else null,
                                filledSz = if (totalSz > 0.0) totalSz else null,
                                oid = if (oid > 0L) oid else null
                            )
                        } else if (firstStatus.has("resting")) {
                            val restingObj = firstStatus.getJSONObject("resting")
                            val oid = restingObj.optLong("oid", 0L)
                            return HyperliquidOrderResult(
                                success = true,
                                responseMsg = responseText,
                                oid = if (oid > 0L) oid else null
                            )
                        } else if (firstStatus.has("error")) {
                            val errMsg = firstStatus.getString("error")
                            return HyperliquidOrderResult(
                                success = false,
                                responseMsg = errMsg
                            )
                        }
                    }
                }
                return HyperliquidOrderResult(success = true, responseMsg = responseText)
            } else if (root.has("response")) {
                val errStr = root.optString("response", responseText)
                return HyperliquidOrderResult(success = false, responseMsg = errStr)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing order response", e)
        }
        val isSuccess = !responseText.contains("\"error\"") && !responseText.contains("\"err\"")
        return HyperliquidOrderResult(success = isSuccess, responseMsg = responseText)
    }

    /**
     * Submits an updateLeverage action to Hyperliquid L1.
     */
    suspend fun updateLeverageOnHyperliquid(
        ctx: android.content.Context,
        asset: Int,
        leverage: Int,
        isCross: Boolean,
        nonce: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val actionObj = JSONObject().apply {
                put("type", "updateLeverage")
                put("asset", asset)
                put("isCross", isCross)
                put("leverage", leverage)
            }

            val signature = EmbeddedWalletManager.signEip712Order(ctx, actionObj, nonce)

            val url = URL("$HYPERLIQUID_API_URL/exchange")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val cleanSig = signature.trim().removePrefix("0x")
            val rHex = if (cleanSig.length >= 64) "0x" + cleanSig.substring(0, 64) else "0x0"
            val sHex = if (cleanSig.length >= 128) "0x" + cleanSig.substring(64, 128) else "0x0"
            val vVal = if (cleanSig.length >= 130) cleanSig.substring(128, 130).toIntOrNull(16) ?: 27 else 27

            val sigObj = JSONObject().apply {
                put("r", rHex)
                put("s", sHex)
                put("v", vVal)
            }

            val requestBody = JSONObject().apply {
                put("action", actionObj)
                put("nonce", nonce)
                put("signature", sigObj)
                put("vaultAddress", null)
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val responseText = if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }

            Log.i(TAG, "Update leverage response ($leverage x, isCross=$isCross): $responseText")
            val isSuccess = code == 200 && !responseText.contains("\"error\"") && !responseText.contains("\"err\"")
            isSuccess to responseText
        } catch (e: Exception) {
            Log.e(TAG, "Update leverage failed", e)
            false to "Update leverage failed: ${e.message}"
        }
    }

    /**
     * Broadcasts signed order to Hyperliquid Exchange API endpoint with detailed result parsing.
     */
    suspend fun submitSignedOrderDetails(
        userAddress: String,
        signatureHex: String,
        payload: HyperliquidOrderPayload
    ): HyperliquidOrderResult = withContext(Dispatchers.IO) {
        val (success, responseText) = submitSignedOrder(userAddress, signatureHex, payload)
        if (!success) {
            return@withContext HyperliquidOrderResult(false, responseText)
        }
        return@withContext parseOrderResponse(responseText)
    }

    /**
     * Submits a reduce-only market order to close a position on Hyperliquid with detailed result parsing.
     */
    suspend fun closePositionOnHyperliquidDetails(
        userAddress: String,
        signatureHex: String,
        asset: Int,
        isLong: Boolean,
        sizeCrypto: Double,
        currentPrice: Double,
        nonce: Long = System.currentTimeMillis()
    ): HyperliquidOrderResult = withContext(Dispatchers.IO) {
        val (success, responseText) = closePositionOnHyperliquid(userAddress, signatureHex, asset, isLong, sizeCrypto, currentPrice, nonce)
        if (!success) {
            return@withContext HyperliquidOrderResult(false, responseText)
        }
        return@withContext parseOrderResponse(responseText)
    }

    /**
     * Fetches clearinghouse state from Hyperliquid Info API.
     */
    suspend fun fetchClearinghouseState(userAddress: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$HYPERLIQUID_API_URL/info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val body = JSONObject().apply {
                put("type", "clearinghouseState")
                put("user", userAddress)
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                JSONObject(responseText)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch clearinghouse state", e)
            null
        }
    }

    /**
     * Executes internal transfer between Perpetual Clearinghouse and Spot Clearinghouse using Hyperliquid L1 usdClassTransfer user-signed action.
     * @param toSpot true = transfer from Perp to Spot (toPerp: false), false = transfer from Spot to Perp (toPerp: true)
     */
    suspend fun transferUsdcBetweenPerpAndSpot(
        ctx: android.content.Context,
        amountUsdc: Double,
        toSpot: Boolean,
        nonce: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val amountStr = String.format(Locale.US, "%.4f", amountUsdc).trimEnd('0').trimEnd('.')
            val toPerpVal = !toSpot

            val actionObj = JSONObject().apply {
                put("type", "usdClassTransfer")
                put("hyperliquidChain", "Mainnet")
                put("signatureChainId", "0x66eee")
                put("amount", amountStr)
                put("toPerp", toPerpVal)
                put("nonce", nonce)
            }

            val signature = EmbeddedWalletManager.signUsdClassTransfer(
                ctx = ctx,
                hyperliquidChain = "Mainnet",
                amount = amountStr,
                toPerp = toPerpVal,
                nonce = nonce
            )

            val url = URL("$HYPERLIQUID_API_URL/exchange")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val cleanSig = signature.trim().removePrefix("0x")
            val rHex = if (cleanSig.length >= 64) "0x" + cleanSig.substring(0, 64) else "0x0"
            val sHex = if (cleanSig.length >= 128) "0x" + cleanSig.substring(64, 128) else "0x0"
            val vVal = if (cleanSig.length >= 130) cleanSig.substring(128, 130).toIntOrNull(16) ?: 27 else 27

            val sigObj = JSONObject().apply {
                put("r", rHex)
                put("s", sHex)
                put("v", vVal)
            }

            val requestBody = JSONObject().apply {
                put("action", actionObj)
                put("nonce", nonce)
                put("signature", sigObj)
                put("vaultAddress", null)
            }

            Log.i(TAG, "=== HYPERLIQUID INTERNAL TRANSFER REQUEST ===")
            Log.i(TAG, requestBody.toString(2))

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val responseText = if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }

            Log.i(TAG, "Transfer response ($code): $responseText")
            val isSuccess = code == 200 && !responseText.contains("\"error\"") && !responseText.contains("\"err\"")
            isSuccess to responseText
        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed", e)
            false to "Transfer failed: ${e.message}"
        }
    }

    data class SpotOpenOrder(
        val coin: String,
        val limitPx: Double,
        val sz: Double,
        val side: String, // "BUY" or "SELL"
        val oid: Long,
        val timestamp: Long,
        val assetIndex: Int
    )

    /**
     * Fetches active open spot/perp limit orders from Hyperliquid Info API.
     */
    suspend fun fetchOpenSpotOrders(userAddress: String): List<SpotOpenOrder> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SpotOpenOrder>()
        if (userAddress.isBlank()) return@withContext result
        try {
            val url = URL("$HYPERLIQUID_API_URL/info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val requestBody = JSONObject().apply {
                put("type", "openOrders")
                put("user", userAddress)
            }

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val array = JSONArray(responseText)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val coin = obj.optString("coin", "")
                    val limitPx = obj.optString("limitPx", "0").toDoubleOrNull() ?: 0.0
                    val sz = obj.optString("sz", "0").toDoubleOrNull() ?: 0.0
                    val rawSide = obj.optString("side", "B")
                    val side = if (rawSide == "B" || rawSide.equals("buy", ignoreCase = true)) "BUY" else "SELL"
                    val oid = obj.optLong("oid", 0L)
                    val timestamp = obj.optLong("timestamp", 0L)

                    val spotInfo = getSpotAssetInfo(coin)
                    val assetIndex = spotInfo?.l1AssetIndex ?: getSpotAssetIndex(coin)

                    result.add(
                        SpotOpenOrder(
                            coin = coin,
                            limitPx = limitPx,
                            sz = sz,
                            side = side,
                            oid = oid,
                            timestamp = timestamp,
                            assetIndex = assetIndex
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch open orders for $userAddress", e)
        }
        return@withContext result
    }

    /**
     * Cancels an active spot/perp order on Hyperliquid L1 using cancel action.
     */
    suspend fun cancelSpotOrder(
        ctx: android.content.Context,
        assetIndex: Int,
        orderId: Long,
        nonce: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val cancelItem = JSONObject().apply {
                put("a", assetIndex)
                put("o", orderId)
            }
            val cancelsArray = JSONArray().apply { put(cancelItem) }
            val actionObj = JSONObject().apply {
                put("type", "cancel")
                put("cancels", cancelsArray)
            }

            val signature = EmbeddedWalletManager.signEip712Order(ctx, actionObj, nonce)

            val url = URL("$HYPERLIQUID_API_URL/exchange")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val cleanSig = signature.trim().removePrefix("0x")
            val rHex = if (cleanSig.length >= 64) "0x" + cleanSig.substring(0, 64) else "0x0"
            val sHex = if (cleanSig.length >= 128) "0x" + cleanSig.substring(64, 128) else "0x0"
            val vVal = if (cleanSig.length >= 130) cleanSig.substring(128, 130).toIntOrNull(16) ?: 27 else 27

            val sigObj = JSONObject().apply {
                put("r", rHex)
                put("s", sHex)
                put("v", vVal)
            }

            val requestBody = JSONObject().apply {
                put("action", actionObj)
                put("nonce", nonce)
                put("signature", sigObj)
                put("vaultAddress", null)
            }

            Log.i(TAG, "=== CANCEL ORDER REQUEST ===")
            Log.i(TAG, requestBody.toString(2))

            conn.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val responseText = if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }

            Log.i(TAG, "Cancel order response ($code): $responseText")
            val isSuccess = code == 200 && !responseText.contains("\"error\"") && !responseText.contains("\"err\"")
            isSuccess to responseText
        } catch (e: Exception) {
            Log.e(TAG, "Cancel order failed", e)
            false to "Cancel order failed: ${e.message}"
        }
    }
}
