package com.aetherdex.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale

/**
 * Manages Firebase Authentication and cloud synchronization with Firestore.
 * Automatically backs up and restores User Profile, Active Positions,
 * Closed Position Trade History, Tax Statistics, Web3 Wallet status,
 * and TradingView Chart layout state.
 */
object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"

    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    data class RestoredUserData(
        val userProfile: UserProfile?,
        val demoBalance: Double?,
        val activePositions: List<Position>?,
        val closedPositions: List<ClosedPosition>?,
        val connectedWalletAddress: String?,
        val tradingviewState: String? = null
    )

    fun isOnline(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun signUpWithEmail(
        email: String,
        password: String,
        onSuccess: (userId: String) -> Unit,
        onFailure: (exception: Exception) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val userId = authResult.user?.uid ?: ""
                Log.i(TAG, "Firebase SignUp successful for UID: $userId")
                onSuccess(userId)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase SignUp failed", e)
                onFailure(e)
            }
    }

    fun signInWithEmail(
        email: String,
        password: String,
        onSuccess: (userId: String) -> Unit,
        onFailure: (exception: Exception) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { authResult ->
                val userId = authResult.user?.uid ?: ""
                Log.i(TAG, "Firebase SignIn successful for UID: $userId")
                onSuccess(userId)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase SignIn failed", e)
                onFailure(e)
            }
    }

    fun syncWalletAddressToFirestore(
        context: Context,
        walletAddress: String?,
        accountId: String
    ) {
        if (!isOnline(context)) return
        val docId = auth.currentUser?.uid ?: accountId.ifEmpty { "AD-GUEST" }
        val payload = hashMapOf<String, Any?>(
            "connectedWalletAddress" to walletAddress,
            "lastSyncedAt" to System.currentTimeMillis()
        )

        firestore.collection("users")
            .document(docId)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                Log.i(TAG, "Synced wallet address '$walletAddress' to Firestore document: $docId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync wallet address to Firestore", e)
            }
    }

    fun syncTradingViewStateToFirestore(
        context: Context,
        tradingviewState: String,
        accountId: String
    ) {
        if (!isOnline(context)) return
        val docId = auth.currentUser?.uid ?: accountId.ifEmpty { "AD-GUEST" }
        val payload = hashMapOf<String, Any?>(
            "tradingview_state" to tradingviewState,
            "lastSyncedAt" to System.currentTimeMillis()
        )

        firestore.collection("users")
            .document(docId)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                Log.i(TAG, "Synced TradingView state to Firestore document: $docId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync TradingView state to Firestore", e)
            }
    }

    fun syncBotSecretToFirestore(
        context: Context,
        agentPrivateKey: String,
        agentAddress: String,
        masterAddress: String,
        accountId: String = ""
    ) {
        if (!isOnline(context)) return
        ensureFirebaseAuth {
            val docId = auth.currentUser?.uid ?: accountId.ifEmpty { "AD-GUEST" }
            val walletAddress = EmbeddedWalletManager.getActiveEvmAddress(context)
            val effectiveMaster = if (masterAddress.startsWith("0x")) masterAddress else walletAddress
            val payload = hashMapOf<String, Any>(
                "agentPrivateKey" to agentPrivateKey,
                "agentAddress" to agentAddress,
                "masterAddress" to effectiveMaster,
                "walletAddress" to effectiveMaster,
                "wallet_address" to effectiveMaster,
                "updated_at" to System.currentTimeMillis()
            )

            val targets = mutableSetOf<String>()
            targets.add(docId)
            if (effectiveMaster.startsWith("0x")) {
                targets.add(effectiveMaster)
                targets.add(effectiveMaster.lowercase(Locale.US))
            }
            if (walletAddress.startsWith("0x")) {
                targets.add(walletAddress)
                targets.add(walletAddress.lowercase(Locale.US))
            }

            for (target in targets) {
                firestore.collection("users")
                    .document(target)
                    .collection("bot")
                    .document("bot_secret")
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.i(TAG, "Synced bot_secret to Firestore (users/$target/bot/bot_secret)")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to sync bot_secret to Firestore for $target", e)
                    }

                firestore.collection("users")
                    .document(target)
                    .set(hashMapOf("bot_secret" to payload, "wallet_address" to effectiveMaster, "masterAddress" to effectiveMaster), SetOptions.merge())
            }
        }
    }

    fun syncBotConfigToFirestore(
        context: Context,
        active: Boolean,
        strategy: String,
        allocationPct: Int,
        targetCoin: String = "",
        accountId: String = "",
        marketType: String = "SPOT",
        leverage: Int = 10,
        marginMode: String = "ISOLATED",
        isIsolated: Boolean = true,
        positionDirection: String = "LONG",
        isSpotBotActive: Boolean = false,
        isFuturesBotActive: Boolean = false,
        spotStrategy: String = strategy,
        spotAllocationPct: Int = allocationPct,
        futuresStrategy: String = strategy,
        futuresAllocationPct: Int = allocationPct,
        strategies: List<String> = emptyList(),
        spotStrategies: List<String> = emptyList(),
        futuresStrategies: List<String> = emptyList(),
        explicitWalletAddress: String? = null
    ) {
        if (!isOnline(context)) {
            Log.d(TAG, "Device is offline. Skipping bot_config sync.")
            return
        }

        ensureFirebaseAuth {
            val docId = auth.currentUser?.uid ?: accountId.ifEmpty { "AD-GUEST" }
            val internalWallet = EmbeddedWalletManager.getActiveEvmAddress(context)
            val walletAddress = if (!explicitWalletAddress.isNullOrBlank() && explicitWalletAddress.startsWith("0x")) {
                explicitWalletAddress
            } else {
                internalWallet
            }
            val cleanTarget = HyperliquidOrderManager.cleanSymbolString(targetCoin).uppercase(Locale.US)
            val currentEnabled = BotAssetPermissionManager.getEnabledAssets(context).toMutableSet()
            if (cleanTarget.isNotBlank()) {
                currentEnabled.add(cleanTarget)
            }
            val enabledAssetsList = currentEnabled.toList()
            val isAnyActive = isSpotBotActive || isFuturesBotActive || active

            val finalSpotStrats = if (spotStrategies.isNotEmpty()) spotStrategies else (if (spotStrategy.contains(",")) spotStrategy.split(",").map { it.trim() } else listOf(spotStrategy))
            val finalFuturesStrats = if (futuresStrategies.isNotEmpty()) futuresStrategies else (if (futuresStrategy.contains(",")) futuresStrategy.split(",").map { it.trim() } else listOf(futuresStrategy))
            val finalAllStrats = if (strategies.isNotEmpty()) strategies else (if (strategy.contains(",")) strategy.split(",").map { it.trim() } else listOf(strategy))

            val spotConfigMap = hashMapOf<String, Any>(
                "active" to isSpotBotActive,
                "strategy" to spotStrategy,
                "strategies" to finalSpotStrats,
                "allocation_pct" to spotAllocationPct,
                "target_coin" to (if (cleanTarget.isNotBlank()) cleanTarget else "BTC"),
                "market_type" to "SPOT",
                "wallet_address" to walletAddress,
                "masterAddress" to walletAddress,
                "updated_at" to System.currentTimeMillis()
            )

            val futuresConfigMap = hashMapOf<String, Any>(
                "active" to isFuturesBotActive,
                "futures_active" to isFuturesBotActive,
                "is_futures_bot_active" to isFuturesBotActive,
                "strategy" to futuresStrategy,
                "strategies" to finalFuturesStrats,
                "allocation_pct" to futuresAllocationPct,
                "target_coin" to (if (cleanTarget.isNotBlank()) cleanTarget else "BTC"),
                "market_type" to "PERP",
                "leverage" to leverage,
                "margin_mode" to marginMode,
                "is_isolated" to isIsolated,
                "position_direction" to positionDirection,
                "wallet_address" to walletAddress,
                "masterAddress" to walletAddress,
                "updated_at" to System.currentTimeMillis()
            )

            val payload = hashMapOf<String, Any>(
                "active" to isAnyActive,
                "is_spot_bot_active" to isSpotBotActive,
                "is_futures_bot_active" to isFuturesBotActive,
                "spot_active" to isSpotBotActive,
                "futures_active" to isFuturesBotActive,
                "strategy" to strategy,
                "strategies" to finalAllStrats,
                "spot_strategies" to finalSpotStrats,
                "futures_strategies" to finalFuturesStrats,
                "allocation_pct" to allocationPct,
                "target_coin" to (if (cleanTarget.isNotBlank()) cleanTarget else "BTC"),
                "enabled_assets" to enabledAssetsList,
                "market_type" to marketType,
                "leverage" to leverage,
                "margin_mode" to marginMode,
                "is_isolated" to isIsolated,
                "position_direction" to positionDirection,
                "wallet_address" to walletAddress,
                "walletAddress" to walletAddress,
                "masterAddress" to walletAddress,
                "spot_config" to spotConfigMap,
                "futures_config" to futuresConfigMap,
                "updated_at" to System.currentTimeMillis()
            )

            val targets = mutableSetOf<String>()
            targets.add(docId)
            if (walletAddress.startsWith("0x")) {
                targets.add(walletAddress)
                targets.add(walletAddress.lowercase(Locale.US))
            }
            if (internalWallet.startsWith("0x")) {
                targets.add(internalWallet)
                targets.add(internalWallet.lowercase(Locale.US))
            }

            for (target in targets) {
                // Primary: Write to subcollection "bot", document "bot_config"
                firestore.collection("users")
                    .document(target)
                    .collection("bot")
                    .document("bot_config")
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.i(TAG, "Synced bot_config to Firestore (users/$target/bot/bot_config): spotActive=$isSpotBotActive, futuresActive=$isFuturesBotActive, anyActive=$isAnyActive, wallet=$walletAddress")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to sync bot_config to Firestore for $target", e)
                    }

                // Sync dedicated market-scoped subdocuments for maximum isolation
                firestore.collection("users")
                    .document(target)
                    .collection("bot")
                    .document("spot_config")
                    .set(spotConfigMap, SetOptions.merge())

                firestore.collection("users")
                    .document(target)
                    .collection("bot")
                    .document("futures_config")
                    .set(futuresConfigMap, SetOptions.merge())

                // Secondary: Write to subcollection "bot_config", document "config"
                firestore.collection("users")
                    .document(target)
                    .collection("bot_config")
                    .document("config")
                    .set(payload, SetOptions.merge())

                // Tertiary: Merge bot_config map field on main user document
                firestore.collection("users")
                    .document(target)
                    .set(hashMapOf(
                        "bot_config" to payload,
                        "wallet_address" to walletAddress,
                        "masterAddress" to walletAddress,
                        "connectedWalletAddress" to walletAddress,
                        "futures_active" to isFuturesBotActive,
                        "is_futures_bot_active" to isFuturesBotActive,
                        "active" to isAnyActive
                    ), SetOptions.merge())
            }
        }
    }

    fun syncEnabledAssetsToFirestore(
        context: Context,
        enabledAssets: List<String>,
        accountId: String = ""
    ) {
        if (!isOnline(context)) return
        ensureFirebaseAuth {
            val docId = auth.currentUser?.uid ?: accountId.ifEmpty { "AD-GUEST" }
            val walletAddress = EmbeddedWalletManager.getActiveEvmAddress(context)
            val payload = hashMapOf<String, Any>(
                "enabled_assets" to enabledAssets,
                "updated_at" to System.currentTimeMillis()
            )

            val targets = mutableSetOf<String>()
            targets.add(docId)
            if (walletAddress.startsWith("0x")) {
                targets.add(walletAddress)
                targets.add(walletAddress.lowercase(Locale.US))
            }

            for (target in targets) {
                firestore.collection("users")
                    .document(target)
                    .collection("bot")
                    .document("bot_config")
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.i(TAG, "Synced enabled_assets (${enabledAssets.size}) to Firestore (users/$target/bot/bot_config)")
                    }

                firestore.collection("users")
                    .document(target)
                    .collection("bot_config")
                    .document("config")
                    .set(payload, SetOptions.merge())

                firestore.collection("users")
                    .document(target)
                    .set(hashMapOf("bot_config" to payload, "enabled_assets" to enabledAssets), SetOptions.merge())
            }
        }
    }

    fun listenToBotStatus(
        accountId: String = "",
        walletAddress: String = "",
        onUpdateWithMarket: (pnlUsd: Double, pnlPct: Double, activeTrades: Int, marketType: String?) -> Unit
    ): com.google.firebase.firestore.ListenerRegistration? {
        val docId = auth.currentUser?.uid ?: accountId.ifEmpty { "AD-GUEST" }
        val targetIds = mutableSetOf<String>()
        targetIds.add(docId)
        if (walletAddress.startsWith("0x")) {
            targetIds.add(walletAddress)
            targetIds.add(walletAddress.lowercase(Locale.US))
        }
        Log.i(TAG, "Setting up real-time listener on bot_status for user targets: $targetIds")

        val handleSnapshot: (com.google.firebase.firestore.DocumentSnapshot?) -> Unit = { snapshot ->
            if (snapshot != null && snapshot.exists()) {
                val pnlUsd = snapshot.getDouble("pnl_usd")
                    ?: (snapshot.get("pnl_usd") as? Number)?.toDouble() ?: 0.0
                val pnlPct = snapshot.getDouble("pnl_pct")
                    ?: (snapshot.get("pnl_pct") as? Number)?.toDouble() ?: 0.0
                val activeTrades = snapshot.getLong("active_trades")?.toInt()
                    ?: (snapshot.get("active_trades") as? Number)?.toInt() ?: 0
                val marketType = snapshot.getString("market_type")
                Log.d(TAG, "Bot status update received from Firestore: pnlUsd=$pnlUsd, pnlPct=$pnlPct, activeTrades=$activeTrades, market=$marketType")
                onUpdateWithMarket(pnlUsd, pnlPct, activeTrades, marketType)
            }
        }

        var registration: com.google.firebase.firestore.ListenerRegistration? = null
        for (target in targetIds) {
            val reg = firestore.collection("users")
                .document(target)
                .collection("bot")
                .document("bot_status")
                .addSnapshotListener { snapshot, error ->
                    if (error == null) {
                        handleSnapshot(snapshot)
                    }
                }
            if (registration == null) registration = reg

            // Secondary listener on users/{target}/bot_status/status
            firestore.collection("users")
                .document(target)
                .collection("bot_status")
                .document("status")
                .addSnapshotListener { snapshot, error ->
                    if (error == null) {
                        handleSnapshot(snapshot)
                    }
                }

            // Tertiary listener on users/{target} root document field "bot_status"
            firestore.collection("users")
                .document(target)
                .addSnapshotListener { snapshot, error ->
                    if (error == null && snapshot != null && snapshot.exists() && snapshot.contains("bot_status")) {
                        @Suppress("UNCHECKED_CAST")
                        val statusMap = snapshot.get("bot_status") as? Map<String, Any>
                        if (statusMap != null) {
                            val pnlUsd = (statusMap["pnl_usd"] as? Number)?.toDouble() ?: 0.0
                            val pnlPct = (statusMap["pnl_pct"] as? Number)?.toDouble() ?: 0.0
                            val activeTrades = (statusMap["active_trades"] as? Number)?.toInt() ?: 0
                            val marketType = statusMap["market_type"] as? String
                            onUpdateWithMarket(pnlUsd, pnlPct, activeTrades, marketType)
                        }
                    }
                }
        }

        return registration
    }

    fun listenToBotStatus(
        accountId: String = "",
        onUpdate: (pnlUsd: Double, pnlPct: Double, activeTrades: Int) -> Unit
    ): com.google.firebase.firestore.ListenerRegistration? {
        return listenToBotStatus(accountId) { pnlUsd, pnlPct, activeTrades, _ ->
            onUpdate(pnlUsd, pnlPct, activeTrades)
        }
    }



    fun fetchUserDataFromFirestore(
        context: Context,
        userId: String,
        accountId: String,
        onSuccess: (RestoredUserData) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val targetDocId = userId.ifEmpty { accountId.ifEmpty { auth.currentUser?.uid ?: "" } }
        if (targetDocId.isEmpty()) {
            onFailure(IllegalStateException("No valid User ID or Account ID to fetch from Firestore."))
            return
        }

        firestore.collection("users").document(targetDocId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Log.w(TAG, "No Firestore document found for ID: $targetDocId")
                    onSuccess(RestoredUserData(null, null, null, null, null, null))
                    return@addOnSuccessListener
                }

                val fbUser = auth.currentUser
                var email = doc.getString("email") ?: ""
                if (email.isBlank() && fbUser != null && !fbUser.email.isNullOrBlank()) {
                    email = fbUser.email!!
                }

                val docAccId = doc.getString("accountId") ?: accountId
                var fullName = doc.getString("fullName") ?: ""
                if (fullName.isBlank()) {
                    if (fbUser != null && !fbUser.displayName.isNullOrBlank()) {
                        fullName = fbUser.displayName!!
                    } else if (email.contains("@")) {
                        fullName = email.substringBefore("@")
                    } else if (docAccId.isNotBlank()) {
                        fullName = "Trader_${docAccId.takeLast(6)}"
                    } else {
                        fullName = "Aether Trader"
                    }
                }

                val isLoggedIn = doc.getBoolean("isLoggedIn") ?: true
                val balance = doc.getDouble("demoBalance") ?: 0.0
                // Enforce active BIP-39 HD EVM address as single source of truth
                val liveEvmAddr = EmbeddedWalletManager.getActiveEvmAddress(context)
                val walletAddr = doc.getString("connectedWalletAddress").let { fetched ->
                    if (fetched != null && fetched.startsWith("0x") && fetched != liveEvmAddr) {
                        Log.i(TAG, "Sanitizing old EVM address '$fetched' with live HD address '$liveEvmAddr'")
                        syncWalletAddressToFirestore(context, liveEvmAddr, docAccId)
                    }
                    liveEvmAddr
                }
                val tvState = doc.getString("tradingview_state")

                val streetAddr = doc.getString("streetAddress") ?: ""
                val postalCity = doc.getString("postalCodeCity") ?: ""
                val countryStr = doc.getString("country") ?: "Germany"
                val baseCurr = doc.getString("baseCurrency") ?: "USD"

                val profile = UserProfile(
                    fullName = fullName,
                    email = email,
                    password = "",
                    accountId = docAccId,
                    isLoggedIn = isLoggedIn,
                    streetAddress = streetAddr,
                    postalCodeCity = postalCity,
                    country = countryStr,
                    baseCurrency = baseCurr
                )

                val activeList = mutableListOf<Position>()
                @Suppress("UNCHECKED_CAST")
                val activeArray = doc.get("activePositions") as? List<Map<String, Any>>
                activeArray?.forEach { map ->
                    activeList.add(
                        Position(
                            id = map["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                            symbol = map["symbol"] as? String ?: "BTC/USDC",
                            isLong = map["isLong"] as? Boolean ?: true,
                            entryPrice = (map["entryPrice"] as? Number)?.toDouble() ?: 0.0,
                            leverage = (map["leverage"] as? Number)?.toInt() ?: 10,
                            margin = (map["margin"] as? Number)?.toDouble() ?: 0.0,
                            tp = (map["tp"] as? Number)?.toDouble() ?: 0.0,
                            sl = (map["sl"] as? Number)?.toDouble() ?: 0.0,
                            timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                        )
                    )
                }

                val closedList = mutableListOf<ClosedPosition>()
                @Suppress("UNCHECKED_CAST")
                val closedArray = doc.get("closedPositions") as? List<Map<String, Any>>
                closedArray?.forEach { map ->
                    closedList.add(
                        ClosedPosition(
                            id = map["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                            symbol = map["symbol"] as? String ?: "BTC/USDC",
                            isLong = map["isLong"] as? Boolean ?: true,
                            leverage = (map["leverage"] as? Number)?.toInt() ?: 10,
                            margin = (map["margin"] as? Number)?.toDouble() ?: 0.0,
                            positionSizeUsdc = (map["positionSizeUsdc"] as? Number)?.toDouble() ?: 0.0,
                            entryPrice = (map["entryPrice"] as? Number)?.toDouble() ?: 0.0,
                            exitPrice = (map["exitPrice"] as? Number)?.toDouble() ?: 0.0,
                            realizedPnl = (map["realizedPnl"] as? Number)?.toDouble() ?: 0.0,
                            realizedPnlPercent = (map["realizedPnlPercent"] as? Number)?.toDouble() ?: 0.0,
                            closeReason = map["closeReason"] as? String ?: "Manual Close",
                            timestamp = (map["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                        )
                    )
                }

                onSuccess(RestoredUserData(profile, balance, activeList, closedList, walletAddr, tvState))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to fetch user data from Firestore", e)
                onFailure(e)
            }
    }

    fun syncUserDataToFirestore(
        context: Context,
        userProfile: UserProfile,
        demoBalance: Double,
        activePositions: List<Position>,
        closedPositions: List<ClosedPosition>,
        connectedWalletAddress: String? = null,
        tradingviewState: String? = null
    ) {
        if (!isOnline(context)) {
            Log.d(TAG, "Device is offline. Skipping Firestore background sync.")
            return
        }

        ensureFirebaseAuth {
            val primaryDocId = auth.currentUser?.uid ?: userProfile.accountId.ifEmpty { "AD-GUEST" }

            val totalClosedTrades = closedPositions.size
            val totalPnlUsd = closedPositions.sumOf { it.realizedPnl }
            val totalFeesUsd = closedPositions.sumOf { it.positionSizeUsdc * 0.0005 * 2.0 }
            val netCapitalGainsUsd = totalPnlUsd - totalFeesUsd

            val syncPayload = hashMapOf<String, Any?>(
                "fullName" to userProfile.fullName,
                "email" to userProfile.email,
                "accountId" to userProfile.accountId,
                "isLoggedIn" to userProfile.isLoggedIn,
                "streetAddress" to userProfile.streetAddress,
                "postalCodeCity" to userProfile.postalCodeCity,
                "country" to userProfile.country,
                "baseCurrency" to userProfile.baseCurrency,
                "demoBalance" to demoBalance,
                "connectedWalletAddress" to connectedWalletAddress,
                "tradingview_state" to tradingviewState,
                "lastSyncedAt" to System.currentTimeMillis(),
                "taxStats" to hashMapOf(
                    "totalClosedTrades" to totalClosedTrades,
                    "totalPnlUsd" to totalPnlUsd,
                    "totalFeesUsd" to totalFeesUsd,
                    "netCapitalGainsUsd" to netCapitalGainsUsd
                ),
                "activePositions" to activePositions.map { pos ->
                    hashMapOf(
                        "id" to pos.id,
                        "symbol" to pos.symbol,
                        "isLong" to pos.isLong,
                        "entryPrice" to pos.entryPrice,
                        "leverage" to pos.leverage,
                        "margin" to pos.margin,
                        "tp" to pos.tp,
                        "sl" to pos.sl,
                        "timestamp" to pos.timestamp
                    )
                },
                "closedPositions" to closedPositions.map { pos ->
                    hashMapOf(
                        "id" to pos.id,
                        "symbol" to pos.symbol,
                        "isLong" to pos.isLong,
                        "leverage" to pos.leverage,
                        "margin" to pos.margin,
                        "positionSizeUsdc" to pos.positionSizeUsdc,
                        "entryPrice" to pos.entryPrice,
                        "exitPrice" to pos.exitPrice,
                        "realizedPnl" to pos.realizedPnl,
                        "realizedPnlPercent" to pos.realizedPnlPercent,
                        "closeReason" to pos.closeReason,
                        "timestamp" to pos.timestamp
                    )
                }
            )

            firestore.collection("users")
                .document(primaryDocId)
                .set(syncPayload, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Firestore background sync successful for UID: $primaryDocId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Firestore background sync failed for UID: $primaryDocId", e)
                }

            if (userProfile.accountId.isNotBlank() && userProfile.accountId != primaryDocId) {
                firestore.collection("users")
                    .document(userProfile.accountId)
                    .set(syncPayload, SetOptions.merge())
            }
        }
    }

    fun updateUserProfileInFirebase(
        context: Context,
        newFullName: String,
        newStreetAddress: String = "",
        newPostalCodeCity: String = "",
        newCountry: String = "Germany",
        newBaseCurrency: String = "USD",
        accountId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val user = auth.currentUser
        val profileUpdates = com.google.firebase.auth.userProfileChangeRequest {
            displayName = newFullName
        }

        user?.updateProfile(profileUpdates)?.addOnCompleteListener { task ->
            val docId = user.uid ?: accountId.ifEmpty { "AD-GUEST" }
            val payload = hashMapOf<String, Any?>(
                "fullName" to newFullName,
                "streetAddress" to newStreetAddress,
                "postalCodeCity" to newPostalCodeCity,
                "country" to newCountry,
                "baseCurrency" to newBaseCurrency,
                "lastSyncedAt" to System.currentTimeMillis()
            )
            firestore.collection("users").document(docId).set(payload, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "Updated Firestore profile name and address for '$newFullName'")
                    onSuccess()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update Firestore profile info", e)
                    onFailure(e)
                }
        } ?: run {
            onFailure(IllegalStateException("No authenticated Firebase user found."))
        }
    }

    fun deleteUserAccountFromFirebase(
        context: Context,
        accountId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val user = auth.currentUser
        val docId = user?.uid ?: accountId

        // 1. Delete Firestore document(s)
        if (docId.isNotBlank()) {
            firestore.collection("users").document(docId).delete()
            if (accountId.isNotBlank() && accountId != docId) {
                firestore.collection("users").document(accountId).delete()
            }
        }

        // 2. Delete Firebase Auth user
        if (user != null) {
            user.delete()
                .addOnSuccessListener {
                    Log.i(TAG, "Firebase user account deleted successfully.")
                    onSuccess()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to delete Firebase user account", e)
                    onFailure(e)
                }
        } else {
            onSuccess()
        }
    }

    private fun ensureFirebaseAuth(onReady: () -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            onReady()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.i(TAG, "Firebase Anonymous Auth successful")
                    onReady()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Firebase Anonymous Auth failed", e)
                }
        }
    }
}
