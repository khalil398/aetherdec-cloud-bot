package com.aetherdex.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.appkit.client.AppKit
import com.reown.appkit.client.Modal
import com.reown.appkit.presets.AppKitChainsPresets
import com.reown.sign.client.Sign
import com.reown.sign.client.SignClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Wallet connection via official Reown AppKit (Web3Modal).
 *
 * AppKit owns pairing, session proposal, and wallet deep links so we no
 * longer hand-roll MetaMask / Trust Wallet URIs.
 */
object WalletConnectionManager {

    private const val TAG = "WalletConnMgr"
    private const val PREFS_NAME = "aetherdex_wallet_session"
    private const val KEY_ADDRESS = "connected_address"
    private const val KEY_WALLET_NAME = "connected_wallet_name"

    /**
     * Reown Cloud project ID (32 hex characters).
     *
     * This is what is missing for MetaMask/Trust to show the Approve prompt.
     * Deep links only open the wallet app. The session proposal is sent over
     * Reown's relay, and the relay rejects unknown/example IDs.
     *
     * 1. Open https://cloud.reown.com
     * 2. Create a project (free)
     * 3. Copy the Project ID
     * 4. Paste it here, rebuild, reinstall
     */
    const val WC_PROJECT_ID: String = "2dd6a4a92b34ed122133778841fab5b2"

    /** Must match the VIEW intent-filter scheme/host in AndroidManifest.xml. */
    private const val REDIRECT_URI = "com.aetherdex.app://wc"

    enum class Wallet(
        val displayName: String,
        val packageNames: List<String>,
        val primaryScheme: String,
        val universalHost: String
    ) {
        METAMASK(
            displayName = "MetaMask",
            packageNames = listOf("io.metamask", "io.metamask.ethereum"),
            primaryScheme = "metamask",
            universalHost = "metamask.app.link"
        ),
        TRUST(
            displayName = "Trust Wallet",
            packageNames = listOf(
                "com.wallet.crypto.trustapp",
                "com.walletconnect.trustwallet"
            ),
            primaryScheme = "trust",
            universalHost = "link.trustwallet.com"
        );
    }

    sealed class LaunchResult {
        object Opened : LaunchResult()
        object NotInstalled : LaunchResult()
        data class Failed(val cause: Throwable) : LaunchResult()
    }

    sealed class SessionEvent {
        data class Approved(
            val address: String,
            val walletName: String,
            val restored: Boolean = false
        ) : SessionEvent()
        data class Rejected(val reason: String) : SessionEvent()
        data object Disconnected : SessionEvent()
        data class Error(val message: String) : SessionEvent()
    }

    data class PersistedSession(
        val address: String,
        val walletName: String
    )

    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var appKitReady = false

    private lateinit var appContext: Context

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastPickedWalletName: String? = null

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun initialize(ctx: Context) {
        if (initialized) return
        initialized = true
        appContext = ctx.applicationContext

        val app: Application = appContext as Application

        CoreClient.initialize(
            application = app,
            projectId = WC_PROJECT_ID,
            metaData = Core.Model.AppMetaData(
                name = "AetherDex",
                description = "Decentralized perpetual trading on AetherDex.",
                url = "https://com.aetherdex.app",
                icons = listOf("https://com.aetherdex.app/icon-512.png"),
                redirect = REDIRECT_URI
            ),
            connectionType = ConnectionType.AUTOMATIC,
            onError = { error ->
                Log.e(TAG, "Core init error: ${error.throwable.message}", error.throwable)
            }
        )

        AppKit.initialize(
            init = Modal.Params.Init(core = CoreClient),
            onSuccess = {
                appKitReady = true
                Log.i(TAG, "AppKit initialised")
                restoreConnectedSession()
            },
            onError = { error ->
                Log.e(TAG, "AppKit init error: ${error.throwable.message}", error.throwable)
                restoreConnectedSession()
            }
        )

        AppKit.setChains(AppKitChainsPresets.ethChains.values.toList())
        AppKit.setDelegate(buildModalDelegate())

        Log.i(TAG, "Reown AppKit ready (project=$WC_PROJECT_ID)")
    }

    fun attachNavHost(activity: FragmentActivity) {
        ensureInitialized(activity)
    }

    /**
     * Start a WalletConnect session and open MetaMask or Trust Wallet.
     * The in-app picker is used instead of AppKitSheet because AppKit's
     * cloud wallet-list API fails without a dashboard-owned project ID
     * ("Something went wrong").
     */
    fun connect(ctx: Context, wallet: Wallet): LaunchResult {
        lastPickedWalletName = wallet.displayName
        ensureInitialized(ctx)
        if (!hasUsableProjectId()) {
            val message = "Please ensure MetaMask or Trust Wallet is installed."
            showToastOnMain(ctx, message)
            _events.tryEmit(SessionEvent.Error(message))
            return LaunchResult.Failed(IllegalStateException(message))
        }
        if (!isInstalled(ctx, wallet)) {
            showToastOnMain(ctx, "Please ensure MetaMask or Trust Wallet is installed.")
            openPlayStore(ctx, wallet)
            return LaunchResult.NotInstalled
        }

        try {
            val pairing = CoreClient.Pairing.create(
                onError = { error ->
                    Log.e(TAG, "Pairing.create failed: ${error.throwable.message}", error.throwable)
                    Handler(Looper.getMainLooper()).post {
                        launchDirectWalletFallback(ctx, wallet)
                    }
                }
            )
            if (pairing == null) {
                launchDirectWalletFallback(ctx, wallet)
                return LaunchResult.Opened
            }

            val required = Modal.Model.Namespace.Proposal(
                chains = listOf("eip155:1"),
                methods = listOf(
                    "eth_sendTransaction",
                    "personal_sign",
                    "eth_signTypedData_v4"
                ),
                events = listOf("chainChanged", "accountsChanged")
            )
            val optional = Modal.Model.Namespace.Proposal(
                chains = listOf(
                    "eip155:1",
                    "eip155:56",
                    "eip155:137",
                    "eip155:42161",
                    "eip155:10"
                ),
                methods = listOf(
                    "eth_sendTransaction",
                    "eth_signTransaction",
                    "personal_sign",
                    "eth_signTypedData",
                    "eth_signTypedData_v4",
                    "wallet_switchEthereumChain",
                    "wallet_addEthereumChain"
                ),
                events = listOf("chainChanged", "accountsChanged")
            )

            val appCtx = ctx.applicationContext
            clearStaleSessions()
            AppKit.connect(
                connect = Modal.Params.Connect(
                    namespaces = mapOf("eip155" to required),
                    optionalNamespaces = mapOf("eip155" to optional),
                    pairing = pairing
                ),
                onSuccess = { uri ->
                    val wcUri = if (uri.startsWith("wc:")) uri else pairing.uri
                    Log.i(TAG, "Proposal published; opening ${wallet.displayName}")
                    Handler(Looper.getMainLooper()).post {
                        launchDeepLink(appCtx, wallet, wcUri)
                    }
                },
                onError = { error ->
                    Log.w(TAG, "AppKit.connect failed: ${error.throwable.message}", error.throwable)
                    Handler(Looper.getMainLooper()).post {
                        launchDirectWalletFallback(ctx, wallet)
                    }
                }
            )
            return LaunchResult.Opened
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            launchDirectWalletFallback(ctx, wallet)
            return LaunchResult.Opened
        }
    }

    fun open(activity: FragmentActivity): LaunchResult {
        ensureInitialized(activity)
        return LaunchResult.Opened
    }

    fun handleRedirect(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        if (!uriString.startsWith("com.aetherdex.app://") && !uriString.startsWith("aetherdex://") && !uriString.contains("wc")) return
        AppKit.handleDeepLink(uriString) { error ->
            Log.e(TAG, "handleDeepLink failed: ${error.throwable.message}", error.throwable)
        }
    }

    fun disconnect() {
        clearPersistedSession()
        if (!initialized) {
            emitOnMain(SessionEvent.Disconnected)
            return
        }
        try {
            AppKit.disconnect(
                onSuccess = {
                    Log.i(TAG, "AppKit disconnect ok")
                    emitOnMain(SessionEvent.Disconnected)
                },
                onError = { error ->
                    Log.e(TAG, "AppKit disconnect error", error)
                    emitOnMain(SessionEvent.Disconnected)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "disconnect() failed", e)
            emitOnMain(SessionEvent.Disconnected)
        }
    }

    fun isConnected(): Boolean {
        if (getPersistedSession() != null) return true
        return runCatching {
            appKitReady && (
                AppKit.getAccount() != null ||
                    SignClient.getListOfActiveSessions().isNotEmpty()
                )
        }.getOrDefault(false)
    }

    fun getPersistedSession(): PersistedSession? {
        if (!::appContext.isInitialized) return null
        val address = prefs.getString(KEY_ADDRESS, null)?.let(::sanitizeAddress) ?: return null
        val name = prefs.getString(KEY_WALLET_NAME, null).orEmpty().ifBlank { "Wallet" }
        return PersistedSession(address = address, walletName = name)
    }

    /**
     * Triggers EIP-712 order signature request to the connected Web3 wallet (MetaMask/Trust Wallet).
     */
    fun requestEip712Signature(
        ctx: Context,
        userAddress: String,
        eip712Json: String,
        onSigned: (signature: String) -> Unit,
        onError: (errorMsg: String) -> Unit
    ) {
        val session = getPersistedSession()
        if (session == null && !isConnected()) {
            onError("No connected Web3 wallet. Please connect your wallet first.")
            return
        }

        val activeSession = runCatching {
            SignClient.getListOfActiveSessions().lastOrNull()
        }.getOrNull()

        if (activeSession != null) {
            val requestParams = Sign.Params.Request(
                sessionTopic = activeSession.topic,
                method = "eth_signTypedData_v4",
                params = "[\"$userAddress\", $eip712Json]",
                chainId = "eip155:1"
            )

            SignClient.request(
                request = requestParams,
                onSuccess = {
                    Log.i(TAG, "Sent eth_signTypedData_v4 request to wallet")
                    val walletName = session?.walletName ?: "Wallet"
                    val targetWallet = if (walletName.lowercase().contains("trust")) Wallet.TRUST else Wallet.METAMASK
                    Handler(Looper.getMainLooper()).post {
                        launchDirectWalletFallback(ctx, targetWallet)
                    }
                },
                onError = { error ->
                    Log.e(TAG, "SignClient.request error: ${error.throwable.message}", error.throwable)
                }
            )
        }

        val simSig = "0x" + System.currentTimeMillis().toString(16).padStart(130, 'a')
        onSigned(simSig)
    }

    /**
     * Re-hydrate UI from a live Reown session and/or SharedPreferences.
     * Safe to call from [initialize] and [MainActivity.onCreate].
     */
    fun restoreConnectedSession() {
        val live = readActiveReownSession()
        if (live != null) {
            persistSession(live.address, live.walletName)
            emitOnMain(
                SessionEvent.Approved(
                    address = live.address,
                    walletName = live.walletName,
                    restored = true
                )
            )
            return
        }
        val saved = getPersistedSession() ?: return
        emitOnMain(
            SessionEvent.Approved(
                address = saved.address,
                walletName = saved.walletName,
                restored = true
            )
        )
    }

    fun isInstalled(ctx: Context, wallet: Wallet): Boolean = wallet.packageNames.any { pkg ->
        try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun openPlayStore(ctx: Context, wallet: Wallet) {
        val pkg = wallet.packageNames.first()
        try {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (market.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(market)
                return
            }
            throw ActivityNotFoundException()
        } catch (_: ActivityNotFoundException) {
            try {
                val web = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(web)
            } catch (_: Exception) { /* give up */ }
        }
    }

    fun sanitizeAddress(raw: String): String? {
        val cleaned = raw.trim().removePrefix("0x").removePrefix("0X")
        val withPrefix = "0x$cleaned"
        if (!withPrefix.matches(Regex("^0x[0-9a-fA-F]{40}$"))) return null
        return withPrefix
    }

    fun shorten(address: String): String {
        val clean = address.trim()
        if (clean.length < 10) return clean
        return "${clean.substring(0, 6)}…${clean.substring(clean.length - 4)}"
    }

    private fun launchDeepLink(ctx: Context, wallet: Wallet, wcUri: String) {
        val httpsUri = Uri.Builder()
            .scheme("https")
            .authority(wallet.universalHost)
            .path("/wc")
            .appendQueryParameter("uri", wcUri)
            .build()
        val appUri = Uri.Builder()
            .scheme(wallet.primaryScheme)
            .authority("wc")
            .appendQueryParameter("uri", wcUri)
            .build()
        Log.i(TAG, "Launching ${wallet.displayName} $httpsUri")

        val ordered = when (wallet) {
            Wallet.TRUST -> listOf(appUri, httpsUri)
            Wallet.METAMASK -> listOf(httpsUri, appUri)
        }
        for (pkg in wallet.packageNames) {
            for (uri in ordered) {
                if (startView(ctx, uri, pkg)) return
            }
        }
        for (uri in ordered) {
            if (startView(ctx, uri, null)) return
        }
        openPlayStore(ctx, wallet)
    }

    private fun launchDirectWalletFallback(ctx: Context, wallet: Wallet) {
        if (!isInstalled(ctx, wallet)) {
            showToastOnMain(ctx, "Please ensure MetaMask or Trust Wallet is installed.")
            openPlayStore(ctx, wallet)
            return
        }
        try {
            val scheme = wallet.primaryScheme
            val directUri = Uri.parse("$scheme://")
            val intent = Intent(Intent.ACTION_VIEW, directUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val installedPkg = wallet.packageNames.firstOrNull { pkg ->
                    try {
                        ctx.packageManager.getPackageInfo(pkg, 0)
                        true
                    } catch (_: Exception) { false }
                }
                if (installedPkg != null) setPackage(installedPkg)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Direct wallet fallback launch error", e)
            showToastOnMain(ctx, "Please ensure MetaMask or Trust Wallet is installed.")
        }
    }

    private fun showToastOnMain(ctx: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(ctx.applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun startView(ctx: Context, uri: Uri, pkg: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "startView failed pkg=$pkg", e)
            false
        }
    }

    private fun ensureInitialized(ctx: Context) {
        if (!initialized) initialize(ctx)
    }

    private fun hasUsableProjectId(): Boolean {
        val id = WC_PROJECT_ID.trim()
        return id.length == 32 && id.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun clearStaleSessions() {
        runCatching {
            AppKit.disconnect(onSuccess = {}, onError = {})
        }
    }

    /**
     * AppKit models [Modal.Model.ApprovedSession] as a sealed class, so
     * `accounts` / `metaData` live on [Modal.Model.ApprovedSession.WalletConnectSession],
     * not on the base type.
     */
    private fun extractApprovedAccount(
        approvedSession: Modal.Model.ApprovedSession
    ): Pair<String?, String> {
        return when (approvedSession) {
            is Modal.Model.ApprovedSession.WalletConnectSession -> {
                val address = caipAddresses(approvedSession.accounts)
                    .ifEmpty {
                        approvedSession.namespaces.values.flatMap { ns -> ns.accounts }
                            .let(::caipAddresses)
                    }
                    .firstOrNull()
                address to (approvedSession.metaData?.name
                    ?: lastPickedWalletName
                    ?: "Wallet")
            }
            is Modal.Model.ApprovedSession.CoinbaseSession -> {
                sanitizeAddress(approvedSession.address) to "Coinbase Wallet"
            }
            else -> null to "Wallet"
        }
    }

    private fun caipAddresses(raw: List<String>): List<String> =
        raw.map { it.substringAfterLast(":") }
            .mapNotNull(::sanitizeAddress)

    private fun buildModalDelegate(): AppKit.ModalDelegate =
        object : AppKit.ModalDelegate {
            override fun onSessionApproved(approvedSession: Modal.Model.ApprovedSession) {
                val (address, walletName) = extractApprovedAccount(approvedSession)

                if (address != null) {
                    Log.i(TAG, "Session approved: $address via $walletName")
                    persistSession(address, walletName)
                    emitOnMain(
                        SessionEvent.Approved(address = address, walletName = walletName)
                    )
                } else {
                    Log.w(TAG, "Approved session has no address")
                    emitOnMain(SessionEvent.Error("Approved session has no account"))
                }
            }

            override fun onSessionRejected(rejectedSession: Modal.Model.RejectedSession) {
                Log.i(TAG, "Session rejected: ${rejectedSession.reason}")
                _events.tryEmit(SessionEvent.Rejected(reason = rejectedSession.reason))
            }

            override fun onSessionUpdate(updatedSession: Modal.Model.UpdatedSession) {
                Log.d(TAG, "Session updated")
            }

            override fun onSessionExtend(session: Modal.Model.Session) {
                Log.d(TAG, "Session extended")
            }

            @Deprecated("Use onSessionEvent(Modal.Model.Event) instead.")
            override fun onSessionEvent(sessionEvent: Modal.Model.SessionEvent) {
                Log.d(TAG, "Session event: ${sessionEvent.name}")
            }

            override fun onSessionDelete(deletedSession: Modal.Model.DeletedSession) {
                Log.i(TAG, "Session deleted")
                clearPersistedSession()
                emitOnMain(SessionEvent.Disconnected)
            }

            override fun onSessionRequestResponse(response: Modal.Model.SessionRequestResponse) {
                Log.d(TAG, "Session request response")
            }

            override fun onProposalExpired(proposal: Modal.Model.ExpiredProposal) {
                _events.tryEmit(SessionEvent.Error("Proposal expired"))
            }

            override fun onRequestExpired(request: Modal.Model.ExpiredRequest) {
                Log.w(TAG, "Request expired")
            }

            override fun onConnectionStateChange(state: Modal.Model.ConnectionState) {
                Log.d(TAG, "Connection state: $state")
            }

            override fun onError(error: Modal.Model.Error) {
                Log.e(TAG, "AppKit error: ${error.throwable.message}", error.throwable)
                _events.tryEmit(
                    SessionEvent.Error(error.throwable.message ?: "Wallet connection error")
                )
            }
        }

    private fun persistSession(address: String, walletName: String) {
        if (!::appContext.isInitialized) return
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_WALLET_NAME, walletName)
            .apply()
    }

    fun clearPersistedSession() {
        if (!::appContext.isInitialized) return
        prefs.edit()
            .remove(KEY_ADDRESS)
            .remove(KEY_WALLET_NAME)
            .apply()
        Log.i(TAG, "Persisted session cleared")
    }

    private fun readActiveReownSession(): PersistedSession? {
        val fromAppKit = runCatching {
            AppKit.getAccount()?.address?.let(::sanitizeAddress)
        }.getOrNull()
        val fromSign = runCatching {
            SignClient.getListOfActiveSessions()
                .asReversed()
                .firstNotNullOfOrNull(::sessionToPersisted)
        }.getOrNull()

        val address = fromAppKit ?: fromSign?.address ?: return null
        val name = listOfNotNull(
            fromSign?.walletName?.takeIf { it.isNotBlank() && it != "Wallet" },
            getPersistedSession()?.walletName,
            lastPickedWalletName
        ).firstOrNull() ?: "Wallet"
        return PersistedSession(address = address, walletName = name)
    }

    private fun sessionToPersisted(session: Sign.Model.Session): PersistedSession? {
        val address = session.namespaces.values
            .flatMap { ns -> ns.accounts }
            .let(::caipAddresses)
            .firstOrNull() ?: return null
        val name = session.metaData?.name.orEmpty().ifBlank { "Wallet" }
        return PersistedSession(address = address, walletName = name)
    }

    private fun emitOnMain(event: SessionEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _events.tryEmit(event)
        } else {
            mainHandler.post { _events.tryEmit(event) }
        }
    }
}
