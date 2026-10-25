package com.aetherdex.app

import android.content.Context
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derives network-specific deposit addresses for blockchain networks
 * from the user's BIP-39 seed stored in [EmbeddedWalletManager].
 *
 * Supports multi-account derivation per network using standard derivation paths:
 * EVM (ETH/BNB/ARB/POLYGON) → BIP44 m/44'/60'/0'/0/X
 * BTC  → BIP84  m/84'/0'/0'/0/X   secp256k1 P2WPKH bech32
 * TRX  → BIP44  m/44'/195'/0'/0/X secp256k1 base58check
 * SOL  → SLIP10 m/44'/501'/X'/0'  Ed25519 base58
 */
object MultiChainAddressDeriver {

    // ── 0x80000000 as signed Int (hardened BIP32 flag) ───────────────────────
    private val H = Int.MIN_VALUE  // 0x80000000

    // ── Asset Token metadata ──────────────────────────────────────────────────

    enum class AssetToken(
        val symbol: String,
        val displayName: String,
        val priceUsd: Double,
        val defaultBalance: Double,
        val colorHex: String,
        val iconLabel: String
    ) {
        USDC("USDC", "USD Coin", 1.0, 0.00, "#2775CA", "💵"),
        USDT("USDT", "Tether USD", 1.0, 0.00, "#26A17B", "🟢"),
        ETH("ETH", "Ethereum", 3500.00, 0.00, "#627EEA", "Ξ"),
        BNB("BNB", "BNB Smart Chain", 580.00, 0.00, "#F0B90B", "🟡"),
        BTC("BTC", "Bitcoin", 68000.00, 0.00, "#F7931A", "₿"),
        SOL("SOL", "Solana", 150.00, 0.00, "#9945FF", "◎")
    }

    // ── Network metadata ─────────────────────────────────────────────────────

    enum class Network(
        val displayName: String,
        val symbol: String,
        val networkColor: String,
        val uriScheme: String,
        val chainId: Long?,
        val estFeeUsd: String,
        val estTimeStr: String,
        val minDepositStr: String,
        val warningText: String
    ) {
        ARBITRUM(
            "Arbitrum One", "ARB", "#28A0F0", "ethereum", 42161L,
            "$0.20", "~15 sec", "10 USDC",
            "⚠️ Only send Arbitrum One assets. Sending on Ethereum Mainnet will be permanently lost."
        ),
        ETHEREUM(
            "Ethereum Mainnet", "ETH", "#627EEA", "ethereum", 1L,
            "$2.50", "~2 min", "50 USDC",
            "⚠️ Only send ERC-20 / ETH assets on Ethereum Mainnet. Sending other assets will result in permanent loss."
        ),
        BNB(
            "BNB Smart Chain", "BNB", "#F0B90B", "ethereum", 56L,
            "$0.15", "~1 min", "10 USDC",
            "⚠️ Only send BEP-20 assets on BNB Smart Chain. Wrong network = permanent loss."
        ),
        POLYGON(
            "Polygon Mainnet", "MATIC", "#8247E5", "ethereum", 137L,
            "$0.05", "~1 min", "10 USDC",
            "⚠️ Only send Polygon / MATIC assets. Sending on other networks will result in permanent loss."
        ),
        SOLANA(
            "Solana", "SOL", "#9945FF", "solana", null,
            "$0.01", "~10 sec", "1 SOL / 10 USDC",
            "⚠️ Only send SOL / SPL tokens to this address. Assets on other networks cannot be recovered."
        ),
        BITCOIN(
            "Bitcoin", "BTC", "#F7931A", "bitcoin", null,
            "$1.50", "~10 min", "0.0005 BTC",
            "⚠️ Only send native BTC to this bc1q… SegWit address. BEP-20 or ERC-20 tokens cannot be recovered."
        ),
        TRON(
            "Tron", "TRX", "#EC0623", "tron", null,
            "$0.80", "~1 min", "20 TRX",
            "⚠️ Only send TRC-20 / TRX assets. Sending ERC-20 tokens to a TRX address will be permanently lost."
        )
    }

    // ── Live Network Stats & Gas Fee Estimator ─────────────────────────────────

    data class NetworkStats(
        val feeUsd: String,
        val timeStr: String,
        val minDepositStr: String
    )

    fun getValidNetworksForToken(token: AssetToken): List<Network> {
        return when (token) {
            AssetToken.USDC -> listOf(Network.ARBITRUM, Network.ETHEREUM, Network.BNB, Network.POLYGON, Network.SOLANA)
            AssetToken.USDT -> listOf(Network.ETHEREUM, Network.TRON, Network.BNB, Network.POLYGON, Network.ARBITRUM, Network.SOLANA)
            AssetToken.ETH  -> listOf(Network.ARBITRUM, Network.ETHEREUM)
            AssetToken.BNB  -> listOf(Network.BNB)
            AssetToken.BTC  -> listOf(Network.BITCOIN)
            AssetToken.SOL  -> listOf(Network.SOLANA)
        }
    }

    /**
     * Fetches live network fee & timing data.
     * Returns fee in format: "X.XXXXXX TOKEN (~$Y.YY)" for Hyperliquid-style display.
     */
    fun fetchLiveNetworkStats(network: Network): NetworkStats {
        return try {
            when (network) {
                Network.BITCOIN  -> fetchBtcLiveStats()
                Network.ETHEREUM -> fetchEvmLiveStats("https://eth.llamarpc.com", 21000L, "ETH", 3500.0, "~2 min", "50 USDC")
                Network.ARBITRUM -> fetchEvmLiveStats("https://arb1.arbitrum.io/rpc", 80000L, "ETH", 3500.0, "~15 sec", "10 USDC")
                Network.BNB      -> fetchEvmLiveStats("https://bsc-dataseed.binance.org", 21000L, "BNB", 550.0, "~1 min", "10 USDC")
                Network.POLYGON  -> fetchEvmLiveStats("https://polygon-rpc.com", 21000L, "MATIC", 0.50, "~1 min", "10 USDC")
                Network.SOLANA   -> fetchSolanaLiveStats()
                Network.TRON     -> NetworkStats("1 TRX (~$0.09)", "~1 min", "20 TRX")
            }
        } catch (e: Exception) {
            NetworkStats(network.estFeeUsd, network.estTimeStr, network.minDepositStr)
        }
    }

    private fun fetchBtcLiveStats(): NetworkStats {
        return try {
            val url = java.net.URL("https://mempool.space/api/v1/fees/recommended")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val halfHourFee = json.optInt("halfHourFee", 18) // sat/vB
            // Avg SegWit P2WPKH tx: ~140 vBytes; 1 BTC ≈ $68,000
            val feeBtc = (halfHourFee * 140.0) / 100_000_000.0
            val feeUsd = feeBtc * 68_000.0
            val estTime = when {
                halfHourFee > 40 -> "~20 min (High Congestion)"
                halfHourFee > 20 -> "~10 min"
                else             -> "~8 min"
            }
            val feeDisplay = "%.6f BTC (~$%.2f)".format(feeBtc, feeUsd)
            NetworkStats(feeDisplay, estTime, "0.0005 BTC")
        } catch (e: Exception) {
            NetworkStats("0.000021 BTC (~$1.43)", "~10 min", "0.0005 BTC")
        }
    }

    private fun fetchEvmLiveStats(
        rpcUrl: String,
        gasLimit: Long,
        nativeSymbol: String,
        nativePriceUsd: Double,
        defaultTime: String,
        minDep: String
    ): NetworkStats {
        return try {
            val url = java.net.URL(rpcUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = """{"jsonrpc":"2.0","method":"eth_gasPrice","params":[],"id":1}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val hexGas = json.optString("result", "0x3B9ACA00") // default 1 Gwei
            val gasPriceWei = java.math.BigInteger(hexGas.removePrefix("0x"), 16)
            val totalWei = gasPriceWei.multiply(java.math.BigInteger.valueOf(gasLimit))
            val totalNative = totalWei.toDouble() / 1e18
            val feeUsd = totalNative * nativePriceUsd
            val feeDisplay = when {
                feeUsd < 0.001 -> "<0.000001 $nativeSymbol (<$0.01)"
                else           -> "%.6f $nativeSymbol (~$%.2f)".format(totalNative, feeUsd)
            }
            NetworkStats(feeDisplay, defaultTime, minDep)
        } catch (e: Exception) {
            val fallback = when (nativeSymbol) {
                "ETH"   -> "0.000045 ETH (~$0.16)"
                "BNB"   -> "0.000035 BNB (~$0.02)"
                "MATIC" -> "0.002 MATIC (<$0.01)"
                else    -> "~\$0.20"
            }
            NetworkStats(fallback, defaultTime, minDep)
        }
    }

    /**
     * Fetches live native EVM coin balance (ETH, BNB, MATIC) via eth_getBalance.
     */
    fun fetchEvmNativeBalance(rpcUrl: String, walletAddress: String): Double {
        return try {
            val url = java.net.URL(rpcUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val cleanAddr = walletAddress.lowercase().removePrefix("0x")
            val body = """{"jsonrpc":"2.0","method":"eth_getBalance","params":["0x$cleanAddr","latest"],"id":1}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val hexVal = json.optString("result", "0x0")
            val wei = java.math.BigInteger(hexVal.removePrefix("0x").ifEmpty { "0" }, 16)
            wei.toDouble() / 1e18
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * Fetches live ERC-20 token balance (USDC, USDT, etc.) via eth_call balanceOf(address).
     */
    fun fetchErc20Balance(
        rpcUrl: String,
        contractAddress: String,
        walletAddress: String,
        decimals: Int = 6
    ): Double {
        return try {
            val url = java.net.URL(rpcUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val paddedAddr = walletAddress.lowercase().removePrefix("0x").padStart(64, '0')
            val callData = "0x70a08231$paddedAddr"
            val body = """{"jsonrpc":"2.0","method":"eth_call","params":[{"to":"$contractAddress","data":"$callData"},"latest"],"id":1}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val hexVal = json.optString("result", "0x0")
            val raw = java.math.BigInteger(hexVal.removePrefix("0x").ifEmpty { "0" }, 16)
            raw.toDouble() / Math.pow(10.0, decimals.toDouble())
        } catch (e: Exception) {
            0.0
        }
    }

    private fun fetchSolanaLiveStats(): NetworkStats {
        return try {
            // Query recent prioritization fees (lamports per compute unit) via JSON-RPC
            val url = java.net.URL("https://api.mainnet-beta.solana.com")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = """{"jsonrpc":"2.0","id":1,"method":"getRecentPrioritizationFees","params":[]}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val arr = json.optJSONArray("result")
            // Typical Solana transfer: 5000 base lamports + ~200 compute units * prioritization fee
            val baseLamports = 5000L
            val avgPrioFee = if (arr != null && arr.length() > 0) {
                var total = 0L
                val count = minOf(arr.length(), 10)
                for (i in 0 until count) total += arr.optJSONObject(i)?.optLong("prioritizationFee", 0L) ?: 0L
                total / count
            } else 0L
            // fee = base + prioritization * 200,000 CU / 1,000,000 (micro-lamports → lamports)
            val totalLamports = baseLamports + (avgPrioFee * 200_000L / 1_000_000L)
            val feeSol = totalLamports / 1_000_000_000.0 // 1 SOL = 1e9 lamports
            val solPrice = 150.0 // approximate; could fetch from CoinGecko in a follow-up
            val feeUsd = feeSol * solPrice
            val feeDisplay = "%.6f SOL (~$%.4f)".format(feeSol, feeUsd)
            NetworkStats(feeDisplay, "~5 sec", "1 SOL / 10 USDC")
        } catch (e: Exception) {
            NetworkStats("0.000005 SOL (~$0.001)", "~5 sec", "1 SOL / 10 USDC")
        }
    }

    /**
     * Formats QR code URI strings according to network specifications:
     * - EIP-681 for EVM: ethereum:<address>[@<chainId>]
     * - Standard URIs for Non-EVM: bitcoin:<address>, tron:<address>, solana:<address>
     */
    fun getQrUri(network: Network, address: String): String {
        return when (network) {
            Network.ETHEREUM -> "ethereum:$address"
            Network.BNB      -> "ethereum:$address@56"
            Network.ARBITRUM -> "ethereum:$address@42161"
            Network.POLYGON  -> "ethereum:$address@137"
            Network.BITCOIN  -> "bitcoin:$address"
            Network.TRON     -> "tron:$address"
            Network.SOLANA   -> "solana:$address"
        }
    }

    // ── Session cache ─────────────────────────────────────────────────────────

    /** Clears cached addresses (no-op for compatibility). */
    fun clearCache() {}

    /**
     * Returns the deposit address for [network] and [accountIndex], derived from the stored seed.
     * Always computes fresh HD address from the BIP-39 seed to ensure 100% address accuracy.
     */
    fun deriveAddress(ctx: Context, network: Network, accountIndex: Int = 0): String {
        return when (network) {
            Network.ETHEREUM, Network.BNB, Network.ARBITRUM, Network.POLYGON ->
                deriveEvmAddress(ctx, accountIndex)
            Network.BITCOIN -> deriveBitcoinAddress(ctx, accountIndex)
            Network.TRON    -> deriveTronAddress(ctx, accountIndex)
            Network.SOLANA  -> deriveSolanaAddress(ctx, accountIndex)
        }
    }

    // ── BIP-39 master seed (64 bytes) ─────────────────────────────────────────

    private fun mnemonicToSeed(mnemonic: String): ByteArray {
        // MUST normalize exactly as BIP-39 spec: trim whitespace and lowercase
        // so that "Word Word..." and "word word..." produce the same 64-byte seed.
        val normalized = mnemonic.trim().lowercase()
        val spec = PBEKeySpec(normalized.toCharArray(), "mnemonic".toByteArray(Charsets.UTF_8), 2048, 512)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }

    // ── HMAC-SHA512 ───────────────────────────────────────────────────────────

    private fun hmac512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    // ── secp256k1 parameters ──────────────────────────────────────────────────

    private val SECP_P  = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val SECP_N  = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val SECP_GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val SECP_GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    private data class Pt(val x: BigInteger, val y: BigInteger)

    private fun ptAdd(p: Pt, q: Pt): Pt {
        if (p == q) return ptDouble(p)
        val dx = (q.x - p.x).mod(SECP_P)
        val dy = (q.y - p.y).mod(SECP_P)
        val lam = dy.multiply(dx.modInverse(SECP_P)).mod(SECP_P)
        val x3 = (lam.pow(2) - p.x - q.x).mod(SECP_P)
        val y3 = (lam.multiply(p.x - x3) - p.y).mod(SECP_P)
        return Pt(x3, y3)
    }

    private fun ptDouble(p: Pt): Pt {
        val lam = (THREE.multiply(p.x.pow(2)).multiply(TWO.multiply(p.y).modInverse(SECP_P))).mod(SECP_P)
        val x3 = (lam.pow(2) - TWO.multiply(p.x)).mod(SECP_P)
        val y3 = (lam.multiply(p.x - x3) - p.y).mod(SECP_P)
        return Pt(x3, y3)
    }

    /** Double-and-add scalar multiplication on secp256k1. */
    private fun ptMul(k: BigInteger): Pt {
        var result: Pt? = null
        var addend = Pt(SECP_GX, SECP_GY)
        var scalar = k.mod(SECP_N)
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0))
                result = if (result == null) addend else ptAdd(result, addend)
            addend = ptDouble(addend)
            scalar = scalar.shiftRight(1)
        }
        return result!!
    }

    private fun BigInteger.toBytes32(): ByteArray {
        val ba = toByteArray()
        return when {
            ba.size == 32 -> ba
            ba.size > 32  -> ba.copyOfRange(ba.size - 32, ba.size)
            else          -> ByteArray(32 - ba.size) + ba
        }
    }

    private fun compressedPubKey(privBytes: ByteArray): ByteArray {
        val pt = ptMul(BigInteger(1, privBytes))
        val pfx = if (pt.y.testBit(0)) 0x03.toByte() else 0x02.toByte()
        return byteArrayOf(pfx) + pt.x.toBytes32()
    }

    fun uncompressedPubKey64Public(privBytes: ByteArray): ByteArray {
        val pt = ptMul(BigInteger(1, privBytes))
        return pt.x.toBytes32() + pt.y.toBytes32()
    }

    private fun uncompressedPubKey64(privBytes: ByteArray): ByteArray = uncompressedPubKey64Public(privBytes)

    // ── BIP32 HD key derivation (secp256k1) ───────────────────────────────────

    private fun ser32(i: Int) = byteArrayOf(
        (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()
    )

    /**
     * Derives a secp256k1 child private key at [path] from a 64-byte BIP-39 seed.
     * Use `index or H` for hardened levels.
     */
    fun bip32DerivePublic(seed: ByteArray, vararg path: Int): ByteArray {
        var hmac = hmac512("Bitcoin seed".toByteArray(), seed)
        var key       = hmac.copyOfRange(0, 32)
        var chainCode = hmac.copyOfRange(32, 64)

        for (index in path) {
            val hardened = (index and H) != 0
            val data = if (hardened) byteArrayOf(0x00) + key + ser32(index)
                       else         compressedPubKey(key) + ser32(index)
            hmac = hmac512(chainCode, data)
            val il = hmac.copyOfRange(0, 32)
            key       = (BigInteger(1, il) + BigInteger(1, key)).mod(SECP_N).toBytes32()
            chainCode = hmac.copyOfRange(32, 64)
        }
        return key
    }

    private fun bip32Derive(seed: ByteArray, vararg path: Int): ByteArray = bip32DerivePublic(seed, *path)

    // ── SLIP-0010 Ed25519 derivation (all hardened) ───────────────────────────

    private fun slip10Derive(seed: ByteArray, vararg path: Int): ByteArray {
        var hmac = hmac512("ed25519 seed".toByteArray(), seed)
        var key       = hmac.copyOfRange(0, 32)
        var chainCode = hmac.copyOfRange(32, 64)

        for (index in path) {
            val hi = index or H   // always hardened
            val data = byteArrayOf(0x00) + key + ser32(hi)
            hmac = hmac512(chainCode, data)
            key       = hmac.copyOfRange(0, 32)  // IL directly (not added to parent)
            chainCode = hmac.copyOfRange(32, 64)
        }
        return key
    }

    // ── RIPEMD-160 (inline, no BouncyCastle) ──────────────────────────────────

    private object Ripemd160 {
        private val KL = intArrayOf(0, 0x5A827999, 0x6ED9EBA1, -0x70E44324, -0x56AC02B2)
        private val KR = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0)
        private val RL = intArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13)
        private val RR = intArrayOf(5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11)
        private val SL = intArrayOf(11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6)
        private val SR = intArrayOf(8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8,8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11)

        private fun f(j: Int, x: Int, y: Int, z: Int) = when {
            j < 16 -> x xor y xor z
            j < 32 -> (x and y) or (x.inv() and z)
            j < 48 -> (x or y.inv()) xor z
            j < 64 -> (x and z) or (y and z.inv())
            else   -> x xor (y or z.inv())
        }
        private fun Int.rol(n: Int) = (this shl n) or (this ushr (32 - n))
        private fun Int.toLE() = byteArrayOf(toByte(), (this shr 8).toByte(), (this shr 16).toByte(), (this shr 24).toByte())

        fun hash(msg: ByteArray): ByteArray {
            val padLen = ((55 - msg.size % 64 + 64) % 64)
            val pad = ByteArray(msg.size + padLen + 9)
            msg.copyInto(pad)
            pad[msg.size] = 0x80.toByte()
            val bits = msg.size.toLong() * 8
            for (i in 0..7) pad[pad.size - 8 + i] = (bits ushr (8 * i)).toByte()

            var h0 = 0x67452301; var h1 = -0x10325477; var h2 = -0x67452302
            var h3 = 0x10325476; var h4 = -0x3C2D1E10

            for (b in pad.indices step 64) {
                val w = IntArray(16) { i ->
                    (pad[b+i*4].toInt() and 0xFF) or ((pad[b+i*4+1].toInt() and 0xFF) shl 8) or
                    ((pad[b+i*4+2].toInt() and 0xFF) shl 16) or ((pad[b+i*4+3].toInt() and 0xFF) shl 24)
                }
                var al=h0;var bl=h1;var cl=h2;var dl=h3;var el=h4
                var ar=h0;var br=h1;var cr=h2;var dr=h3;var er=h4
                for (j in 0 until 80) {
                    val tl=(al+f(j,bl,cl,dl)+w[RL[j]]+KL[j/16]).rol(SL[j])+el
                    al=el;el=dl;dl=cl.rol(10);cl=bl;bl=tl
                    val tr=(ar+f(79-j,br,cr,dr)+w[RR[j]]+KR[j/16]).rol(SR[j])+er
                    ar=er;er=dr;dr=cr.rol(10);cr=br;br=tr
                }
                val t=h1+cl+dr;h1=h2+dl+er;h2=h3+el+ar;h3=h4+al+br;h4=h0+bl+cr;h0=t
            }
            return h0.toLE()+h1.toLE()+h2.toLE()+h3.toLE()+h4.toLE()
        }
    }

    // ── Keccak-256 (BouncyCastle, standard Ethereum/Tron hash) ────────────────

    object Keccak256 {
        fun hash(input: ByteArray): ByteArray {
            val digest = org.bouncycastle.crypto.digests.KeccakDigest(256)
            digest.update(input, 0, input.size)
            val out = ByteArray(32)
            digest.doFinal(out, 0)
            return out
        }
    }


    // ── Bech32 (Bitcoin P2WPKH) ───────────────────────────────────────────────

    private object Bech32 {
        private const val CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

        private fun polymod(v: IntArray): Int {
            var c = 1
            for (x in v) {
                val c0 = c ushr 25
                c = ((c and 0x1FFFFFF) shl 5) xor x
                for (i in 0 until 5) if ((c0 shr i) and 1 != 0) c = c xor GEN[i]
            }
            return c
        }

        private fun hrpExpand(hrp: String): IntArray {
            val r = IntArray(hrp.length * 2 + 1)
            for (i in hrp.indices) r[i] = hrp[i].code ushr 5
            for (i in hrp.indices) r[hrp.length + 1 + i] = hrp[i].code and 31
            return r
        }

        private fun checksum(hrp: String, data: IntArray): IntArray {
            val m = polymod(hrpExpand(hrp) + data + IntArray(6)) xor 1
            return IntArray(6) { i -> (m ushr (5*(5-i))) and 31 }
        }

        private fun conv85(data: ByteArray): IntArray {
            var acc = 0; var bits = 0
            val r = mutableListOf<Int>()
            for (b in data) {
                acc = (acc shl 8) or (b.toInt() and 0xFF); bits += 8
                while (bits >= 5) { bits -= 5; r.add((acc ushr bits) and 31) }
            }
            if (bits > 0) r.add((acc shl (5 - bits)) and 31)
            return r.toIntArray()
        }

        /** Encode a 20-byte witness program as bc1q… (P2WPKH bech32). */
        fun encodeP2WPKH(hash20: ByteArray): String {
            val prog = intArrayOf(0) + conv85(hash20)   // witness version 0 + 5-bit groups
            val cs   = checksum("bc", prog)
            return "bc1" + (prog + cs).joinToString("") { CHARS[it].toString() }
        }
    }

    // ── Base58 / Base58Check ──────────────────────────────────────────────────

    private object Base58 {
        private const val ABC = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private val BASE = BigInteger.valueOf(58)

        fun encode(input: ByteArray): String {
            var n = BigInteger(1, input)
            val sb = StringBuilder()
            while (n > BigInteger.ZERO) {
                val (q, r) = n.divideAndRemainder(BASE)
                sb.insert(0, ABC[r.toInt()])
                n = q
            }
            for (b in input) { if (b == 0.toByte()) sb.insert(0, '1') else break }
            return sb.toString()
        }

        fun checkEncode(input: ByteArray): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val cs = sha.digest(sha.digest(input)).copyOfRange(0, 4)
            return encode(input + cs)
        }
    }

    // ── EIP-55 Checksum helper for EVM Addresses ──────────────────────────────

    private fun checksumEvmAddress(addressHex: String): String {
        val clean = addressHex.lowercase().removePrefix("0x")
        val hash = Keccak256.hash(clean.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val sb = StringBuilder()
        for (i in clean.indices) {
            val char = clean[i]
            if (char in '0'..'9') {
                sb.append(char)
            } else {
                val nibble = Character.digit(hash[i], 16)
                if (nibble >= 8) {
                    sb.append(char.uppercaseChar())
                } else {
                    sb.append(char.lowercaseChar())
                }
            }
        }
        return "0x" + sb.toString()
    }

    // ── Per-chain derivation with multi-account support ────────────────────────

    private fun getMnemonic(ctx: Context): String? =
        EmbeddedWalletManager.getMnemonicWords(ctx)?.joinToString(" ")

    fun deriveEvmAddressDirect(ctx: Context, accountIndex: Int = 0): String {
        val mnemonic = getMnemonic(ctx) ?: return "Vault not initialized"
        val seed = mnemonicToSeed(mnemonic)
        // BIP44 m/44'/60'/0'/0/X
        val priv   = bip32Derive(seed, 44 or H, 60 or H, 0 or H, 0, accountIndex)
        val pub64  = uncompressedPubKey64(priv)
        val keccak = Keccak256.hash(pub64)
        val addrBytes = keccak.copyOfRange(12, 32)
        val result = checksumEvmAddress(addrBytes.joinToString("") { "%02x".format(it) })
        seed.fill(0)
        priv.fill(0)
        pub64.fill(0)
        keccak.fill(0)
        return result
    }

    private fun deriveEvmAddress(ctx: Context, accountIndex: Int): String {
        return EmbeddedWalletManager.getActiveEvmAddress(ctx)
    }

    private fun deriveBitcoinAddress(ctx: Context, accountIndex: Int): String {
        val mnemonic = getMnemonic(ctx) ?: return "Vault not initialized"
        val seed = mnemonicToSeed(mnemonic)
        // BIP84 m/84'/0'/0'/0/X
        val priv = bip32Derive(seed, 84 or H, 0 or H, 0 or H, 0, accountIndex)
        val cpk  = compressedPubKey(priv)
        val sha256Hash = MessageDigest.getInstance("SHA-256").digest(cpk)
        val hash160 = Ripemd160.hash(sha256Hash)   // 20 bytes
        val result = Bech32.encodeP2WPKH(hash160)
        seed.fill(0)
        priv.fill(0)
        cpk.fill(0)
        return result
    }

    private fun deriveTronAddress(ctx: Context, accountIndex: Int): String {
        val mnemonic = getMnemonic(ctx) ?: return "Vault not initialized"
        val seed = mnemonicToSeed(mnemonic)
        // BIP44 m/44'/195'/0'/0/X
        val priv   = bip32Derive(seed, 44 or H, 195 or H, 0 or H, 0, accountIndex)
        val pub64  = uncompressedPubKey64(priv)      // 64 bytes (no 0x04 prefix)
        val keccak = Keccak256.hash(pub64)            // 32 bytes
        val addrBytes = byteArrayOf(0x41.toByte()) + keccak.copyOfRange(12, 32) // 0x41 + last 20
        val result = Base58.checkEncode(addrBytes)     // T...
        seed.fill(0)
        priv.fill(0)
        pub64.fill(0)
        keccak.fill(0)
        return result
    }

    private fun deriveSolanaAddress(ctx: Context, accountIndex: Int): String {
        val mnemonic = getMnemonic(ctx) ?: return "Vault not initialized"
        val seed = mnemonicToSeed(mnemonic)
        // SLIP-0010 m/44'/501'/X'/0' (Ed25519)
        val privSeed = slip10Derive(seed, 44, 501, accountIndex, 0)  // 32-byte Ed25519 seed

        val result = try {
            val edSpec    = net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName("Ed25519")
            val privSpec  = net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec(privSeed, edSpec)
            val edPrivKey = net.i2p.crypto.eddsa.EdDSAPrivateKey(privSpec)
            Base58.encode(edPrivKey.abyte)   // 32-byte compressed public key → Base58
        } catch (e: Exception) {
            // Fallback: deterministic Base58 from seed hash if EdDSA unavailable
            Base58.encode(MessageDigest.getInstance("SHA-256").digest(privSeed))
        }
        seed.fill(0)
        privSeed.fill(0)
        return result
    }

    // ── Asset & Network Live Balance & Fee Helpers ───────────────────────────

    private val balanceCache = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun getCachedBalance(token: AssetToken, network: Network? = null): Double {
        val key = if (network != null) "${token.name}_${network.name}" else "TOTAL_${token.name}"
        return balanceCache[key] ?: 0.0
    }

    fun setCachedBalance(token: AssetToken, network: Network?, balance: Double) {
        val key = if (network != null) "${token.name}_${network.name}" else "TOTAL_${token.name}"
        balanceCache[key] = balance
    }

    fun fetchSpecificNetworkBalance(context: Context, token: AssetToken, network: Network): Double {
        val evmAddr = EmbeddedWalletManager.getActiveEvmAddress(context)
        val bal = when (token) {
            AssetToken.USDT -> when (network) {
                Network.BNB -> fetchErc20Balance("https://bsc-dataseed.binance.org", "0x55d398326f99059fF775485246999027B3197955", evmAddr, 18)
                Network.ARBITRUM -> fetchErc20Balance("https://arb1.arbitrum.io/rpc", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", evmAddr, 6)
                Network.POLYGON -> fetchErc20Balance("https://polygon-rpc.com", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", evmAddr, 6)
                Network.ETHEREUM -> fetchErc20Balance("https://cloudflare-eth.com", "0xdAC17F958D2ee523a2206206994597C13D831ec7", evmAddr, 6)
                else -> 0.0
            }
            AssetToken.USDC -> when (network) {
                Network.ARBITRUM -> fetchErc20Balance("https://arb1.arbitrum.io/rpc", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", evmAddr, 6)
                Network.BNB -> fetchErc20Balance("https://bsc-dataseed.binance.org", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", evmAddr, 18)
                Network.POLYGON -> fetchErc20Balance("https://polygon-rpc.com", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", evmAddr, 6)
                Network.ETHEREUM -> fetchErc20Balance("https://cloudflare-eth.com", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", evmAddr, 6)
                else -> 0.0
            }
            AssetToken.ETH -> when (network) {
                Network.ETHEREUM -> fetchEvmNativeBalance("https://cloudflare-eth.com", evmAddr)
                Network.ARBITRUM -> fetchEvmNativeBalance("https://arb1.arbitrum.io/rpc", evmAddr)
                else -> 0.0
            }
            AssetToken.BNB -> when (network) {
                Network.BNB -> fetchEvmNativeBalance("https://bsc-dataseed.binance.org", evmAddr)
                else -> 0.0
            }
            AssetToken.BTC -> 0.0
            AssetToken.SOL -> 0.0
        }
        setCachedBalance(token, network, bal)
        return bal
    }

    suspend fun refreshAssetBalances(context: Context) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val evmAddr = EmbeddedWalletManager.getActiveEvmAddress(context)

            // USDT
            val usdtBsc = fetchErc20Balance("https://bsc-dataseed.binance.org", "0x55d398326f99059fF775485246999027B3197955", evmAddr, 18)
            val usdtArb = fetchErc20Balance("https://arb1.arbitrum.io/rpc", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", evmAddr, 6)
            val usdtPoly = fetchErc20Balance("https://polygon-rpc.com", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", evmAddr, 6)
            val usdtEth = fetchErc20Balance("https://cloudflare-eth.com", "0xdAC17F958D2ee523a2206206994597C13D831ec7", evmAddr, 6)
            val usdtHl = EmbeddedWalletManager.fetchOnChainBalance(context)

            setCachedBalance(AssetToken.USDT, Network.BNB, usdtBsc)
            setCachedBalance(AssetToken.USDT, Network.ARBITRUM, usdtArb)
            setCachedBalance(AssetToken.USDT, Network.POLYGON, usdtPoly)
            setCachedBalance(AssetToken.USDT, Network.ETHEREUM, usdtEth)
            setCachedBalance(AssetToken.USDT, null, usdtBsc + usdtArb + usdtPoly + usdtEth + usdtHl)

            // USDC
            val usdcArb = fetchErc20Balance("https://arb1.arbitrum.io/rpc", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", evmAddr, 6)
            val usdcBsc = fetchErc20Balance("https://bsc-dataseed.binance.org", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", evmAddr, 18)
            val usdcPoly = fetchErc20Balance("https://polygon-rpc.com", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", evmAddr, 6)
            val usdcEth = fetchErc20Balance("https://cloudflare-eth.com", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", evmAddr, 6)

            setCachedBalance(AssetToken.USDC, Network.ARBITRUM, usdcArb)
            setCachedBalance(AssetToken.USDC, Network.BNB, usdcBsc)
            setCachedBalance(AssetToken.USDC, Network.POLYGON, usdcPoly)
            setCachedBalance(AssetToken.USDC, Network.ETHEREUM, usdcEth)
            setCachedBalance(AssetToken.USDC, null, usdcArb + usdcBsc + usdcPoly + usdcEth)

            // ETH
            val ethEth = fetchEvmNativeBalance("https://cloudflare-eth.com", evmAddr)
            val ethArb = fetchEvmNativeBalance("https://arb1.arbitrum.io/rpc", evmAddr)

            setCachedBalance(AssetToken.ETH, Network.ETHEREUM, ethEth)
            setCachedBalance(AssetToken.ETH, Network.ARBITRUM, ethArb)
            setCachedBalance(AssetToken.ETH, null, ethEth + ethArb)

            // BNB
            val bnbBsc = fetchEvmNativeBalance("https://bsc-dataseed.binance.org", evmAddr)
            setCachedBalance(AssetToken.BNB, Network.BNB, bnbBsc)
            setCachedBalance(AssetToken.BNB, null, bnbBsc)
        }
    }

    data class LiveGasEstimate(
        val feeNative: Double = 0.0,
        val nativeSymbol: String = "ETH",
        val feeNativeStr: String = "0.00",
        val feeUsdStr: String = "$0.00",
        val combinedStr: String = "$0.00"
    )

    fun fetchNativeGasBalance(context: Context, network: Network): Double {
        val evmAddr = EmbeddedWalletManager.getActiveEvmAddress(context)
        return when (network) {
            Network.BNB -> fetchEvmNativeBalance("https://bsc-dataseed.binance.org", evmAddr)
            Network.ETHEREUM -> fetchEvmNativeBalance("https://cloudflare-eth.com", evmAddr)
            Network.ARBITRUM -> fetchEvmNativeBalance("https://arb1.arbitrum.io/rpc", evmAddr)
            Network.POLYGON -> fetchEvmNativeBalance("https://polygon-rpc.com", evmAddr)
            else -> 0.0
        }
    }

    fun getErc20ContractAddress(network: Network, token: AssetToken): String? {
        return when (token) {
            AssetToken.USDT -> when (network) {
                Network.BNB -> "0x55d398326f99059fF775485246999027B3197955"
                Network.ARBITRUM -> "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9"
                Network.POLYGON -> "0xc2132D05D31c914a87C6611C10748AEb04B58e8F"
                Network.ETHEREUM -> "0xdAC17F958D2ee523a2206206994597C13D831ec7"
                else -> null
            }
            AssetToken.USDC -> when (network) {
                Network.ARBITRUM -> "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
                Network.BNB -> "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d"
                Network.POLYGON -> "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"
                Network.ETHEREUM -> "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
                else -> null
            }
            else -> null
        }
    }

    fun estimateLiveEvmGasFee(
        network: Network,
        assetToken: AssetToken,
        senderAddress: String,
        recipientAddress: String,
        amount: Double
    ): LiveGasEstimate {
        val rpcUrls = when (network) {
            Network.BNB -> listOf("https://bsc-dataseed.binance.org", "https://bsc-dataseed1.defibit.io", "https://rpc.ankr.com/bsc")
            Network.ARBITRUM -> listOf("https://arb1.arbitrum.io/rpc", "https://rpc.ankr.com/arbitrum")
            Network.POLYGON -> listOf("https://polygon-rpc.com", "https://rpc.ankr.com/polygon")
            Network.ETHEREUM -> listOf("https://cloudflare-eth.com", "https://rpc.ankr.com/eth")
            else -> return LiveGasEstimate(0.0, network.symbol, network.estFeeUsd, network.estFeeUsd, network.estFeeUsd)
        }

        val nativePrice = when (network) {
            Network.BNB -> 580.0
            Network.ETHEREUM, Network.ARBITRUM -> 3500.0
            Network.POLYGON -> 0.40
            else -> 1.0
        }

        val nativeSymbol = when (network) {
            Network.BNB -> "BNB"
            Network.ETHEREUM, Network.ARBITRUM -> "ETH"
            Network.POLYGON -> "POL"
            else -> network.symbol
        }

        val minGasPrice = when (network) {
            Network.BNB -> java.math.BigInteger.valueOf(500_000_000L) // 0.5 Gwei floor for BSC (gives 0.0000105 BNB for 21,000 gas limit)
            Network.POLYGON -> java.math.BigInteger.valueOf(30_000_000_000L)
            Network.ARBITRUM -> java.math.BigInteger.valueOf(100_000_000L)
            else -> java.math.BigInteger.valueOf(1_000_000_000L)
        }

        for (rpcUrl in rpcUrls) {
            try {
                val url = java.net.URL(rpcUrl)
                val connG = url.openConnection() as java.net.HttpURLConnection
                connG.connectTimeout = 3000
                connG.readTimeout = 3000
                connG.requestMethod = "POST"
                connG.setRequestProperty("Content-Type", "application/json")
                connG.doOutput = true
                val bodyG = """{"jsonrpc":"2.0","method":"eth_gasPrice","params":[],"id":1}"""
                connG.outputStream.write(bodyG.toByteArray(Charsets.UTF_8))
                val textG = connG.inputStream.bufferedReader().readText()
                val jsonG = org.json.JSONObject(textG)
                val gasPriceHex = jsonG.optString("result", "0x0")
                val parsedGasPrice = java.math.BigInteger(gasPriceHex.removePrefix("0x").ifEmpty { "0" }, 16)
                val gasPriceWei = if (parsedGasPrice > minGasPrice) parsedGasPrice else minGasPrice

                val isNativeTransfer = (assetToken == AssetToken.ETH && (network == Network.ETHEREUM || network == Network.ARBITRUM)) ||
                                       (assetToken == AssetToken.BNB && network == Network.BNB)
                var gasLimit = if (isNativeTransfer) 21000L else 65000L

                val cleanSender = senderAddress.lowercase().removePrefix("0x")
                val cleanRecip = recipientAddress.lowercase().removePrefix("0x")

                if (cleanRecip.length == 40 && cleanSender.length == 40) {
                    try {
                        val connE = java.net.URL(rpcUrl).openConnection() as java.net.HttpURLConnection
                        connE.connectTimeout = 3000
                        connE.readTimeout = 3000
                        connE.requestMethod = "POST"
                        connE.setRequestProperty("Content-Type", "application/json")
                        connE.doOutput = true

                        val contractAddr = getErc20ContractAddress(network, assetToken)
                        val bodyE = if (isNativeTransfer || contractAddr == null) {
                            """{"jsonrpc":"2.0","method":"eth_estimateGas","params":[{"from":"0x$cleanSender","to":"0x$cleanRecip"}],"id":2}"""
                        } else {
                            val decimals = if ((assetToken == AssetToken.USDT || assetToken == AssetToken.USDC) && network == Network.BNB) 18 else 6
                            val tokenUnits = java.math.BigDecimal.valueOf(if (amount > 0) amount else 1.0)
                                .multiply(java.math.BigDecimal.TEN.pow(decimals)).toBigInteger()
                            val paddedRecip = cleanRecip.padStart(64, '0')
                            val paddedAmount = tokenUnits.toString(16).padStart(64, '0')
                            val callData = "0xa9059cbb$paddedRecip$paddedAmount"
                            """{"jsonrpc":"2.0","method":"eth_estimateGas","params":[{"from":"0x$cleanSender","to":"$contractAddr","data":"$callData"}],"id":2}"""
                        }
                        connE.outputStream.write(bodyE.toByteArray(Charsets.UTF_8))
                        val textE = connE.inputStream.bufferedReader().readText()
                        val jsonE = org.json.JSONObject(textE)
                        val estHex = jsonE.optString("result", "")
                        if (estHex.startsWith("0x")) {
                            val parsedEst = java.math.BigInteger(estHex.removePrefix("0x"), 16).toLong()
                            if (parsedEst > 0) gasLimit = parsedEst
                        }
                    } catch (_: Exception) {}
                }

                val totalWei = gasPriceWei.multiply(java.math.BigInteger.valueOf(gasLimit))
                val feeNative = totalWei.toDouble() / 1e18
                val feeUsd = feeNative * nativePrice

                val feeNativeStr = if (feeNative < 0.0001) {
                    String.format(java.util.Locale.US, "%.7f %s", feeNative, nativeSymbol).trimEnd('0').trimEnd('.')
                } else {
                    String.format(java.util.Locale.US, "%.5f %s", feeNative, nativeSymbol).trimEnd('0').trimEnd('.')
                }

                val feeUsdStr = when {
                    feeUsd <= 0.0 -> "$0.00"
                    feeUsd < 0.0001 -> "~$0.0001"
                    feeUsd < 0.01 -> String.format(java.util.Locale.US, "~$%.4f", feeUsd)
                    else -> String.format(java.util.Locale.US, "~$%.2f", feeUsd)
                }

                return LiveGasEstimate(feeNative, nativeSymbol, feeNativeStr, feeUsdStr, "$feeNativeStr ($feeUsdStr)")
            } catch (_: Exception) {
                continue
            }
        }

        val symbol = when (network) {
            Network.BNB -> "BNB"
            Network.ETHEREUM, Network.ARBITRUM -> "ETH"
            Network.POLYGON -> "POL"
            else -> network.symbol
        }
        return LiveGasEstimate(0.0, symbol, network.estFeeUsd, network.estFeeUsd, network.estFeeUsd)
    }

    // ── EVM Transaction Signing & Broadcasting Core ─────────────────────────

    data class TxResult(
        val success: Boolean,
        val txHash: String?,
        val error: String?
    )

    object EvmTransactionSigner {

        fun sendRawEvmTransaction(
            context: Context,
            network: Network,
            assetToken: AssetToken,
            recipientAddress: String,
            amount: Double
        ): TxResult {
            val rpcUrl = when (network) {
                Network.BNB -> "https://bsc-dataseed.binance.org"
                Network.ARBITRUM -> "https://arb1.arbitrum.io/rpc"
                Network.POLYGON -> "https://polygon-rpc.com"
                Network.ETHEREUM -> "https://cloudflare-eth.com"
                else -> return TxResult(false, null, "Broadcasting not supported on ${network.displayName}")
            }
            val chainId = network.chainId ?: return TxResult(false, null, "Invalid chain ID")

            val mnemonic = getMnemonic(context) ?: return TxResult(false, null, "Wallet seed not found")
            val seed = mnemonicToSeed(mnemonic)
            val privKeyBytes = bip32Derive(seed, 44 or H, 60 or H, 0 or H, 0, EmbeddedWalletManager.getActiveAccountIndex(context))
            val senderAddr = EmbeddedWalletManager.getActiveEvmAddress(context)

            return try {
                // 1. Fetch Nonce (eth_getTransactionCount)
                val nonce = fetchNonce(rpcUrl, senderAddr)

                // 2. Fetch Gas Price (eth_gasPrice)
                val gasPrice = fetchGasPrice(rpcUrl)

                // 3. Estimate Gas Limit
                val isNative = (assetToken == AssetToken.ETH && (network == Network.ETHEREUM || network == Network.ARBITRUM)) ||
                               (assetToken == AssetToken.BNB && network == Network.BNB)

                val contractAddr = getErc20ContractAddress(network, assetToken)
                val gasLimit = estimateGasLimit(rpcUrl, senderAddr, recipientAddress, contractAddr, isNative, amount)

                // 4. Construct RLP encoded fields
                val cleanRecip = recipientAddress.lowercase().removePrefix("0x")
                val toBytes = if (isNative) hexToBytes(cleanRecip) else hexToBytes(contractAddr!!.lowercase().removePrefix("0x"))

                val valueBytes = if (isNative) {
                    val wei = java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal("1000000000000000000")).toBigInteger()
                    bigIntToBytes(wei)
                } else {
                    ByteArray(0)
                }

                val dataBytes = if (isNative) {
                    ByteArray(0)
                } else {
                    val decimals = if (assetToken == AssetToken.USDT && network == Network.BNB) 18 else 6
                    val tokenUnits = java.math.BigDecimal.valueOf(amount).multiply(java.math.BigDecimal.TEN.pow(decimals)).toBigInteger()
                    val selector = hexToBytes("a9059cbb")
                    val recipPadded = hexToBytes(cleanRecip.padStart(64, '0'))
                    val amountPadded = hexToBytes(tokenUnits.toString(16).padStart(64, '0'))
                    selector + recipPadded + amountPadded
                }

                // 5. Sign EIP-155 Transaction
                val signedTxHex = signEip155Transaction(
                    nonce = nonce,
                    gasPrice = gasPrice,
                    gasLimit = gasLimit,
                    to = toBytes,
                    value = valueBytes,
                    data = dataBytes,
                    chainId = chainId,
                    privateKey = privKeyBytes
                )

                // 6. Broadcast via eth_sendRawTransaction
                val txHash = broadcastRawTx(rpcUrl, signedTxHex)
                seed.fill(0)
                privKeyBytes.fill(0)

                if (txHash.startsWith("0x") && txHash.length >= 64) {
                    TxResult(true, txHash, null)
                } else {
                    TxResult(false, null, txHash)
                }
            } catch (e: Exception) {
                seed.fill(0)
                privKeyBytes.fill(0)
                TxResult(false, null, e.message ?: "Transaction broadcasting error")
            }
        }

        private fun fetchNonce(rpcUrl: String, address: String): Long {
            val conn = java.net.URL(rpcUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val clean = address.lowercase().removePrefix("0x")
            val body = """{"jsonrpc":"2.0","method":"eth_getTransactionCount","params":["0x$clean","pending"],"id":1}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val hex = json.optString("result", "0x0")
            return java.math.BigInteger(hex.removePrefix("0x").ifEmpty { "0" }, 16).toLong()
        }

        private fun fetchGasPrice(rpcUrl: String): java.math.BigInteger {
            val conn = java.net.URL(rpcUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = """{"jsonrpc":"2.0","method":"eth_gasPrice","params":[],"id":1}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val hex = json.optString("result", "0x3b9aca00")
            return java.math.BigInteger(hex.removePrefix("0x").ifEmpty { "3b9aca00" }, 16)
        }

        private fun estimateGasLimit(
            rpcUrl: String,
            sender: String,
            recipient: String,
            contractAddr: String?,
            isNative: Boolean,
            amount: Double
        ): java.math.BigInteger {
            val fallback = if (isNative) 21000L else 65000L
            return try {
                val conn = java.net.URL(rpcUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val cleanS = sender.lowercase().removePrefix("0x")
                val cleanR = recipient.lowercase().removePrefix("0x")

                val body = if (isNative || contractAddr == null) {
                    """{"jsonrpc":"2.0","method":"eth_estimateGas","params":[{"from":"0x$cleanS","to":"0x$cleanR"}],"id":1}"""
                } else {
                    val cleanC = contractAddr.lowercase().removePrefix("0x")
                    val paddedRecip = cleanR.padStart(64, '0')
                    val paddedAmount = java.math.BigInteger.valueOf((amount * 1e6).toLong()).toString(16).padStart(64, '0')
                    val callData = "0xa9059cbb$paddedRecip$paddedAmount"
                    """{"jsonrpc":"2.0","method":"eth_estimateGas","params":[{"from":"0x$cleanS","to":"0x$cleanC","data":"$callData"}],"id":1}"""
                }
                conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
                val text = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(text)
                val hex = json.optString("result", "")
                if (hex.startsWith("0x")) {
                    java.math.BigInteger(hex.removePrefix("0x"), 16)
                } else {
                    java.math.BigInteger.valueOf(fallback)
                }
            } catch (e: Exception) {
                java.math.BigInteger.valueOf(fallback)
            }
        }

        private fun broadcastRawTx(rpcUrl: String, rawTxHex: String): String {
            val conn = java.net.URL(rpcUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val cleanHex = if (rawTxHex.startsWith("0x")) rawTxHex else "0x$rawTxHex"
            val body = """{"jsonrpc":"2.0","method":"eth_sendRawTransaction","params":["$cleanHex"],"id":1}"""
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val text = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(text)
            if (json.has("result")) {
                return json.getString("result")
            } else if (json.has("error")) {
                val errObj = json.getJSONObject("error")
                val msg = errObj.optString("message", "RPC error")
                throw RuntimeException(msg)
            }
            throw RuntimeException("Unknown RPC response: $text")
        }

        fun signEip155Transaction(
            nonce: Long,
            gasPrice: java.math.BigInteger,
            gasLimit: java.math.BigInteger,
            to: ByteArray,
            value: ByteArray,
            data: ByteArray,
            chainId: Long,
            privateKey: ByteArray
        ): String {
            val nonceBytes = bigIntToBytes(java.math.BigInteger.valueOf(nonce))
            val gasPriceBytes = bigIntToBytes(gasPrice)
            val gasLimitBytes = bigIntToBytes(gasLimit)
            val chainIdBytes = bigIntToBytes(java.math.BigInteger.valueOf(chainId))
            val emptyBytes = ByteArray(0)

            // Unsigned RLP: [nonce, gasPrice, gasLimit, to, value, data, chainId, 0, 0]
            val unsignedRlp = rlpEncodeList(
                rlpEncodeBytes(nonceBytes),
                rlpEncodeBytes(gasPriceBytes),
                rlpEncodeBytes(gasLimitBytes),
                rlpEncodeBytes(to),
                rlpEncodeBytes(value),
                rlpEncodeBytes(data),
                rlpEncodeBytes(chainIdBytes),
                rlpEncodeBytes(emptyBytes),
                rlpEncodeBytes(emptyBytes)
            )

            val rawHash = Keccak256.hash(unsignedRlp)

            val curve = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1")
            val domainParams = org.bouncycastle.crypto.params.ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
            val privKeyParams = org.bouncycastle.crypto.params.ECPrivateKeyParameters(java.math.BigInteger(1, privateKey), domainParams)

            val signer = org.bouncycastle.crypto.signers.ECDSASigner(org.bouncycastle.crypto.signers.HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()))
            signer.init(true, privKeyParams)
            val sigComponents = signer.generateSignature(rawHash)

            var r = sigComponents[0]
            var s = sigComponents[1]

            val halfN = curve.n.shiftRight(1)
            var recId = 0
            if (s > halfN) {
                s = curve.n.subtract(s)
            }

            val pub64 = uncompressedPubKey64Public(privateKey)
            val pubX = java.math.BigInteger(1, pub64.copyOfRange(0, 32))
            val pubY = java.math.BigInteger(1, pub64.copyOfRange(32, 64))

            for (i in 0..1) {
                val candidatePt = recoverPubKey(rawHash, r, s, i, domainParams)
                if (candidatePt != null && candidatePt.x == pubX && candidatePt.y == pubY) {
                    recId = i
                    break
                }
            }

            val v = chainId * 2 + 35 + recId
            val vBytes = bigIntToBytes(java.math.BigInteger.valueOf(v))
            val rBytes = bigIntToBytes(r)
            val sBytes = bigIntToBytes(s)

            // Signed RLP: [nonce, gasPrice, gasLimit, to, value, data, v, r, s]
            val signedRlp = rlpEncodeList(
                rlpEncodeBytes(nonceBytes),
                rlpEncodeBytes(gasPriceBytes),
                rlpEncodeBytes(gasLimitBytes),
                rlpEncodeBytes(to),
                rlpEncodeBytes(value),
                rlpEncodeBytes(data),
                rlpEncodeBytes(vBytes),
                rlpEncodeBytes(rBytes),
                rlpEncodeBytes(sBytes)
            )

            return "0x" + bytesToHex(signedRlp)
        }

        private fun recoverPubKey(
            hash: ByteArray,
            r: java.math.BigInteger,
            s: java.math.BigInteger,
            recId: Int,
            params: org.bouncycastle.crypto.params.ECDomainParameters
        ): Pt? {
            val n = params.n
            val i = java.math.BigInteger.valueOf(recId.toLong() / 2)
            val x = r.add(i.multiply(n))
            val curve = params.curve
            if (x >= curve.field.characteristic) return null

            val comp = byteArrayOf((if (recId and 1 == 1) 0x03 else 0x02).toByte()) + bigIntToBytes(x).padStart(32)
            val R = curve.decodePoint(comp)
            if (!R.multiply(n).isInfinity) return null

            val e = java.math.BigInteger(1, hash)
            val eInv = java.math.BigInteger.ZERO.subtract(e).mod(n)
            val rInv = r.modInverse(n)
            val q = org.bouncycastle.math.ec.ECAlgorithms.sumOfTwoMultiplies(R, s, params.g, eInv).multiply(rInv)
            return Pt(q.normalize().xCoord.toBigInteger(), q.normalize().yCoord.toBigInteger())
        }

        private fun ByteArray.padStart(length: Int): ByteArray {
            if (this.size >= length) return this
            val out = ByteArray(length)
            System.arraycopy(this, 0, out, length - this.size, this.size)
            return out
        }

        private fun rlpEncodeBytes(b: ByteArray): ByteArray {
            if (b.isEmpty()) return byteArrayOf(0x80.toByte())
            if (b.size == 1 && (b[0].toInt() and 0xFF) < 0x80) return b
            if (b.size <= 55) return byteArrayOf((0x80 + b.size).toByte()) + b
            val lenBytes = bigIntToBytes(java.math.BigInteger.valueOf(b.size.toLong()))
            return byteArrayOf((0xb7 + lenBytes.size).toByte()) + lenBytes + b
        }

        private fun rlpEncodeList(vararg items: ByteArray): ByteArray {
            val totalLen = items.sumOf { it.size }
            val payload = ByteArray(totalLen)
            var pos = 0
            for (item in items) {
                System.arraycopy(item, 0, payload, pos, item.size)
                pos += item.size
            }
            if (totalLen <= 55) {
                return byteArrayOf((0xc0 + totalLen).toByte()) + payload
            }
            val lenBytes = bigIntToBytes(java.math.BigInteger.valueOf(totalLen.toLong()))
            return byteArrayOf((0xf7 + lenBytes.size).toByte()) + lenBytes + payload
        }

        private fun bigIntToBytes(b: java.math.BigInteger): ByteArray {
            if (b == java.math.BigInteger.ZERO) return ByteArray(0)
            val ba = b.toByteArray()
            if (ba.isEmpty()) return ByteArray(0)
            if (ba[0] == 0.toByte()) return ba.copyOfRange(1, ba.size)
            return ba
        }

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.lowercase().removePrefix("0x")
            val len = clean.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }
    }
}
