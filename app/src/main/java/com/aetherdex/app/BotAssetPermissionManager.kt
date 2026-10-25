package com.aetherdex.app

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Manages per-asset trading permissions for the AI Algorithmic Bot Engine.
 * 
 * Rules:
 * - Default State: Off (Disabled) by default for all assets.
 * - When an asset's toggle is OFF, the AI Bot Engine is strictly forbidden from executing auto-buy trades for that asset.
 * - When an asset's toggle is ON, the bot is authorized to evaluate signals and execute trades ONLY for that specific enabled asset.
 * - Settings are persisted in SharedPreferences and synchronized to Firestore for 24/7 Cloud Bot enforcement.
 */
object BotAssetPermissionManager {
    private const val TAG = "BotAssetPermission"
    private const val PREFS_NAME = "aetherdex_bot_asset_permissions"
    private const val KEY_ENABLED_ASSETS = "enabled_assets_set"

    fun isAssetEnabled(context: Context, baseToken: String): Boolean {
        val clean = HyperliquidOrderManager.cleanSymbolString(baseToken).uppercase(Locale.US)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_ENABLED_ASSETS, emptySet()) ?: emptySet()
        return set.contains(clean)
    }

    fun setAssetEnabled(context: Context, baseToken: String, enabled: Boolean) {
        val clean = HyperliquidOrderManager.cleanSymbolString(baseToken).uppercase(Locale.US)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(KEY_ENABLED_ASSETS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) {
            currentSet.add(clean)
        } else {
            currentSet.remove(clean)
        }
        prefs.edit().putStringSet(KEY_ENABLED_ASSETS, currentSet).apply()
        Log.i(TAG, "Asset '$clean' bot permission updated: enabled=$enabled. Total active assets: ${currentSet.size}")

        // Synchronize to Firestore for Cloud Bot execution engine
        try {
            FirebaseSyncManager.syncEnabledAssetsToFirestore(context, currentSet.toList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync enabled assets to Firestore", e)
        }
    }

    fun toggleAsset(context: Context, baseToken: String): Boolean {
        val newState = !isAssetEnabled(context, baseToken)
        setAssetEnabled(context, baseToken, newState)
        return newState
    }

    fun getEnabledAssets(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_ENABLED_ASSETS, emptySet()) ?: emptySet()
    }
}
