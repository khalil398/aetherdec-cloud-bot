package com.aetherdex.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * Decoupled ViewModel for managing active trading symbol states across
 * Spot Trading, Perpetual Futures, and their respective chart views.
 *
 * Rules:
 * - Spot Trading and Bot Strategy Chart strictly share selectedSpotAsset.
 * - Perpetual Futures and standard TradingView Chart strictly share selectedPerpAsset.
 * - Symbol selection in one mode never cross-contaminates or overrides the other.
 */
class TradeViewModel : ViewModel() {

    // ── Perpetual Futures Active Symbol State ──
    private val _selectedPerpAsset = MutableLiveData<String>("BTC/USDC")
    val selectedPerpAsset: LiveData<String> = _selectedPerpAsset

    private val _rawPerpSymbol = MutableLiveData<String>("BTCUSDC")
    val rawPerpSymbol: LiveData<String> = _rawPerpSymbol

    var currentPerpAsset: String = "BTC/USDC"
        private set

    var currentPerpRaw: String = "BTCUSDC"
        private set

    // ── Spot Trading Active Symbol State ──
    private val _selectedSpotAsset = MutableLiveData<String>("PURR/USDC")
    val selectedSpotAsset: LiveData<String> = _selectedSpotAsset

    private val _rawSpotSymbol = MutableLiveData<String>("PURR")
    val rawSpotSymbol: LiveData<String> = _rawSpotSymbol

    var currentSpotAsset: String = "PURR/USDC"
        private set

    var currentSpotRaw: String = "PURR"
        private set

    fun setPerpAsset(displaySymbol: String, rawSymbol: String = HyperliquidOrderManager.cleanSymbolString(displaySymbol)) {
        val normalized = normalizeSymbol(displaySymbol)
        currentPerpAsset = normalized
        currentPerpRaw = rawSymbol
        _selectedPerpAsset.postValue(normalized)
        _rawPerpSymbol.postValue(rawSymbol)
    }

    fun setSpotAsset(displaySymbol: String, rawSymbol: String = HyperliquidOrderManager.cleanSymbolString(displaySymbol)) {
        val normalized = normalizeSymbol(displaySymbol)
        currentSpotAsset = normalized
        currentSpotRaw = rawSymbol
        _selectedSpotAsset.postValue(normalized)
        _rawSpotSymbol.postValue(rawSymbol)
        // Keep SharedSpotSelectionState synchronized for Bot Chart
        SharedSpotSelectionState.setSymbol(normalized)
    }

    companion object {
        fun normalizeSymbol(symbol: String): String {
            val clean = symbol.trim()
            if (clean.isBlank()) return "BTC/USDC"
            return if (!clean.contains("/") && !clean.endsWith("USDC", ignoreCase = true)) {
                "$clean/USDC"
            } else {
                clean
            }
        }
    }
}
