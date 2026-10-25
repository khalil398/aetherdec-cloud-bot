package com.aetherdex.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared state manager for synchronizing active Hyperliquid Spot asset selection
 * seamlessly across the Trade tab (Spot Trading) and the Bot Strategy Chart.
 */
object SharedSpotSelectionState {

    @Volatile
    var currentDisplaySymbol: String = "PURR/USDC"
        private set

    private val listeners = CopyOnWriteArrayList<(symbol: String, source: Any?) -> Unit>()

    fun setSymbol(symbol: String, source: Any? = null) {
        val trimmed = symbol.trim()
        if (trimmed.isBlank()) return
        val base = HyperliquidOrderManager.cleanSymbolString(trimmed)
        val normalized = if (base.isNotBlank()) "$base/USDC" else "PURR/USDC"
        currentDisplaySymbol = normalized
        for (listener in listeners) {
            try {
                listener(normalized, source)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addListener(listener: (symbol: String, source: Any?) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (symbol: String, source: Any?) -> Unit) {
        listeners.remove(listener)
    }
}
