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

    suspend fun ensureAgentApprovedAndSynced(context: Context, accountId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val (agentPk, agentAddr) = getOrCreateAgentKey(context)
            val masterAddress = EmbeddedWalletManager.getActiveEvmAddress(context)
            val masterPkHex = EmbeddedWalletManager.getActivePrivateKeyHex(context)

            if (masterPkHex.isNullOrEmpty() || !masterAddress.startsWith("0x")) {
                Log.e(TAG, "Cannot approve agent: Master wallet private key unavailable.")
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

            // Submit approveAgent action to Hyperliquid L1 API
            val approved = HyperliquidOrderManager.approveAgentOnL1(
                masterPrivateKeyHex = masterPkHex,
                agentAddress = agentAddr,
                agentName = "AetherDex Cloud Bot Engine"
            )

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IS_APPROVED, approved).apply()

            Log.i(TAG, "Agent Key $agentAddr approval status on L1: $approved")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error in ensureAgentApprovedAndSynced", e)
            return@withContext false
        }
    }
}
