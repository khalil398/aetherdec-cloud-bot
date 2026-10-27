package com.aetherdex.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Manages Hyperliquid EIP-712 Agent Key generation, L1 authorization,
 * and secure synchronization to Firebase Firestore for 24/7 Cloud Bot execution.
 */
object AgentKeyManager {

    private const val TAG = "AgentKeyManager"
    private const val PREFS_NAME = "aetherdex_agent_key_prefs"
    private const val KEY_AGENT_PK = "agent_private_key"
    private const val KEY_AGENT_ADDRESS = "agent_address"
    private const val KEY_IS_APPROVED = "agent_is_approved"

    @Synchronized
    fun getOrCreateAgentKey(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingPk = prefs.getString(KEY_AGENT_PK, null)
        val existingAddr = prefs.getString(KEY_AGENT_ADDRESS, null)

        if (!existingPk.isNullOrEmpty() && !existingAddr.isNullOrEmpty()) {
            return Pair(existingPk, existingAddr)
        }

        Log.i(TAG, "Generating new ephemeral Hyperliquid Agent Key pair...")
        val random = SecureRandom()
        val privateKeyBytes = ByteArray(32)
        random.nextBytes(privateKeyBytes)
        val privateKeyBigInt = BigInteger(1, privateKeyBytes)
        val ecKeyPair = ECKeyPair.create(privateKeyBigInt)

        val agentPkHex = Numeric.toHexStringWithPrefixZeroPadded(ecKeyPair.privateKey, 64)
        val agentAddrHex = Keys.toChecksumAddress(Keys.getAddress(ecKeyPair))

        prefs.edit()
            .putString(KEY_AGENT_PK, agentPkHex)
            .putString(KEY_AGENT_ADDRESS, agentAddrHex)
            .putBoolean(KEY_IS_APPROVED, false)
            .apply()

        Log.i(TAG, "Created Agent Key: Address = $agentAddrHex")
        return Pair(agentPkHex, agentAddrHex)
    }

    suspend fun ensureAgentApprovedAndSynced(
        context: Context,
        accountId: String,
        overrideMasterAddress: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val (agentPk, agentAddr) = getOrCreateAgentKey(context)
            val masterAddress = overrideMasterAddress
                ?: (if (context is MainActivity) context.getActiveMasterWalletAddress() else EmbeddedWalletManager.getActiveEvmAddress(context))
            val masterPkHex = EmbeddedWalletManager.getActivePrivateKeyHex(context)

            if (!masterAddress.startsWith("0x")) {
                Log.e(TAG, "Cannot sync agent: Master wallet address invalid ($masterAddress).")
                return@withContext false
            }

            // Sync restricted Agent secret to Firestore users/{uid}/bot/bot_secret
            FirebaseSyncManager.syncBotSecretToFirestore(
                context = context,
                agentPrivateKey = agentPk,
                agentAddress = agentAddr,
                masterAddress = masterAddress,
                accountId = accountId
            )

            // Check if already approved on L1 first!
            val alreadyApproved = HyperliquidOrderManager.checkAgentApprovalOnL1(masterAddress, agentAddr)
            if (alreadyApproved) {
                Log.i(TAG, "Agent Key $agentAddr is already approved on Hyperliquid L1 for $masterAddress")
                setAgentApproved(context, true)
                return@withContext true
            }

            if (masterPkHex.isNullOrEmpty()) {
                Log.w(TAG, "Master wallet private key unavailable for automatic L1 signing (external wallet connected).")
                return@withContext false
            }

            // Submit approveAgent action to Hyperliquid L1 API
            val (approved, response) = HyperliquidOrderManager.approveAgentOnL1(
                masterPrivateKeyHex = masterPkHex,
                agentAddress = agentAddr,
                agentName = "AetherDex Bot"
            )

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IS_APPROVED, approved).apply()

            Log.i(TAG, "Agent Key $agentAddr approval status on L1: $approved, response: $response")
            return@withContext approved
        } catch (e: Exception) {
            Log.e(TAG, "Error in ensureAgentApprovedAndSynced", e)
            return@withContext false
        }
    }

    suspend fun authorizeAndSyncAgent(
        context: Context,
        accountId: String,
        masterAddress: String,
        masterPrivateKeyHex: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val (agentPk, agentAddr) = getOrCreateAgentKey(context)
            FirebaseSyncManager.syncBotSecretToFirestore(
                context = context,
                agentPrivateKey = agentPk,
                agentAddress = agentAddr,
                masterAddress = masterAddress,
                accountId = accountId
            )

            val (approved, response) = HyperliquidOrderManager.approveAgentOnL1(
                masterPrivateKeyHex = masterPrivateKeyHex,
                agentAddress = agentAddr,
                agentName = "AetherDex Bot"
            )
            setAgentApproved(context, approved)
            Pair(approved, response)
        } catch (e: Exception) {
            Log.e(TAG, "authorizeAndSyncAgent failed with exception", e)
            Pair(false, e.message ?: "Failed to authorize agent key on L1")
        }
    }

    fun getAgentAddress(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AGENT_ADDRESS, null) ?: getOrCreateAgentKey(context).second
    }

    fun isAgentApprovedLocally(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_APPROVED, false)
    }

    fun setAgentApproved(context: Context, approved: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_IS_APPROVED, approved).apply()
    }

    suspend fun rotateAgentKey(
        context: Context,
        accountId: String,
        overrideMasterAddress: String? = null
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val (newPk, newAddr) = getOrCreateAgentKey(context)
        val masterAddress = overrideMasterAddress
            ?: (if (context is MainActivity) context.getActiveMasterWalletAddress() else EmbeddedWalletManager.getActiveEvmAddress(context))
        if (masterAddress.startsWith("0x")) {
            FirebaseSyncManager.syncBotSecretToFirestore(
                context = context,
                agentPrivateKey = newPk,
                agentAddress = newAddr,
                masterAddress = masterAddress,
                accountId = accountId
            )
        }
        Pair(newPk, newAddr)
    }
}
