package com.aetherdex.app

import android.annotation.SuppressLint
import android.util.Log
import java.util.Locale
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.slider.Slider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity(),
    WalletPickerBottomSheet.Listener,
    ConnectedWalletBottomSheet.Listener {

    companion object {
        const val TAG = "MainActivity"
    }

    // Chart handles
    private lateinit var webViewChart: WebView
    private lateinit var chartContainer: FrameLayout
    private lateinit var btnFullscreenChart: ImageButton
    private lateinit var fullscreenChartContainer: FrameLayout
    private var isFullscreenMode: Boolean = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // UI handles
    private lateinit var btnConnectWallet: MaterialButton
    private lateinit var sideGroup: MaterialButtonToggleGroup
    private lateinit var typeGroup: MaterialButtonToggleGroup
    private lateinit var btnLong: MaterialButton
    private lateinit var btnShort: MaterialButton
    private lateinit var btnMarket: MaterialButton
    private lateinit var btnLimit: MaterialButton
    private lateinit var sliderLeverage: Slider
    private lateinit var tvLeverageValue: TextView
    private lateinit var chip1x: Chip
    private lateinit var chip5x: Chip
    private lateinit var chip10x: Chip
    private lateinit var chip25x: Chip
    private lateinit var chip50x: Chip
    private lateinit var chip100x: Chip
    private lateinit var btnOpenPosition: MaterialButton
    private lateinit var marginModeGroup: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var btnMarginIsolated: com.google.android.material.button.MaterialButton
    private lateinit var btnMarginCross: com.google.android.material.button.MaterialButton
    private lateinit var tvMarginModeWarning: TextView
    private lateinit var btnExportCsv: MaterialButton
    private lateinit var btnExportTaxPdf: MaterialButton
    private lateinit var btnEditProfile: MaterialButton
    private lateinit var btnDeleteAccount: MaterialButton

    // Spot Trading & AI Bot Controller Handles
    private lateinit var marketTypeGroup: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var btnMarketTypePerp: com.google.android.material.button.MaterialButton
    private lateinit var btnMarketTypeSpot: com.google.android.material.button.MaterialButton
    private lateinit var cardSpotBotPanel: View
    private lateinit var switchBotActive: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tvBotStatusBadge: TextView
    private lateinit var tvBotAllocationLabel: TextView
    private lateinit var tvBotActiveTradesCount: TextView
    private lateinit var tvBotTotalPnl: TextView
    private lateinit var sliderBotAllocation: Slider
    private lateinit var chipGroupBotStrategy: com.google.android.material.chip.ChipGroup
    private lateinit var chipStrategyDca: Chip
    private lateinit var chipStrategyGrid: Chip
    private lateinit var chipStrategyTrend: Chip
    private lateinit var chipStrategyEmaChannel: Chip
    private lateinit var layoutSpotBalances: View
    private lateinit var tvSpotUsdcBalance: TextView
    private lateinit var tvSpotAssetBalance: TextView
    private lateinit var layoutPerpMarginContainer: View

    private var isSpotMode: Boolean = false
    private var isBotActive: Boolean = false
    private var botStrategy: String = "Smart DCA / Dip Buyer"
    private var botAllocationPct: Int = 25
    private var botTotalPnlUsd: Double = 0.0
    private var botActiveTradesCount: Int = 0
    private var spotUsdcBalance: Double = 0.0
    private var spotAssetBalances = mutableMapOf<String, Double>()
    private var spotBotJob: kotlinx.coroutines.Job? = null
    private var botStatusListener: com.google.firebase.firestore.ListenerRegistration? = null

    // Demo Trading State & Order Form UI handles
    private var demoBalance: Double = 0.00
    private var hyperliquidL1UsdcBalance: Double = 0.00
    private var marginModeIsolated: Boolean = true  // Default to Isolated for safety
    private val activePositions = mutableListOf<Position>()
    private val closedPositions = mutableListOf<ClosedPosition>()
    private var currentRawSymbol: String = "BTCUSDC"
    private var currentDisplaySymbol: String = "BTC/USDC"
    private val symbolPrices = mutableMapOf<String, Double>()
    private var currentBtcPrice: Double = 0.0
    private var symbolWebSocket: okhttp3.WebSocket? = null
    private var isMarketOrder: Boolean = true

    private lateinit var actvSymbol: android.widget.AutoCompleteTextView
    private lateinit var tvLivePriceHeader: TextView
    private lateinit var layoutEntryPrice: View
    private lateinit var etEntryPrice: EditText
    private lateinit var etAmount: EditText
    private lateinit var etTp: EditText
    private lateinit var etSl: EditText
    private lateinit var tvTpEstProfit: TextView
    private lateinit var tvSlEstLoss: TextView
    private lateinit var tvAvailableBalance: TextView
    private lateinit var tvPositionSizeHint: TextView
    private lateinit var btnSetPriceAlert: ImageButton
    private lateinit var tvEstLiquidationPrice: TextView
    private lateinit var tvRiskLevelBadge: TextView
    private lateinit var progressRiskMeter: ProgressBar
    private val activePriceAlerts = mutableListOf<PriceAlert>()
    private lateinit var tvPositionsBadge: TextView
    private lateinit var tvNoPositions: TextView
    private lateinit var positionsListContainer: LinearLayout

    // History & Section handles
    private lateinit var historySection: View
    private lateinit var tvHistorySummary: TextView
    private lateinit var btnClearHistory: MaterialButton
    private lateinit var tvNoHistory: TextView
    private lateinit var historyListContainer: LinearLayout

    // Tax & Section handles
    private lateinit var taxSection: View
    private lateinit var tvTaxFilteredStats: TextView
    private lateinit var chipGroupTaxFilter: com.google.android.material.chip.ChipGroup
    private lateinit var chipTaxAll: Chip
    private lateinit var chipTaxLastMonth: Chip
    private lateinit var chipTaxCustom: Chip
    private lateinit var actvTaxYear: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private var currentTaxFilterType: String = "ALL"
    private var dynamicCustomTaxChip: Chip? = null
    private lateinit var layoutTaxCustomDate: View
    private lateinit var btnTaxStartDate: MaterialButton
    private lateinit var btnTaxEndDate: MaterialButton
    private lateinit var btnTaxApplyCustomRange: MaterialButton
    private lateinit var currencyToggleGroup: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var btnCurrencyUsd: MaterialButton
    private lateinit var btnCurrencyEur: MaterialButton
    private lateinit var btnCurrencyGbp: MaterialButton

    private var customTaxStartMs: Long? = null
    private var customTaxEndMs: Long? = null

    // Settings & Section handles
    private lateinit var scrollContent: androidx.core.widget.NestedScrollView
    private lateinit var settingsSection: View
    private lateinit var cardExportBackup: LinearLayout
    private lateinit var cardImportRestore: LinearLayout
    private lateinit var btnExportBackup: MaterialButton
    private lateinit var btnImportRestore: MaterialButton

    // Profile handles & User state
    private var currentUserProfile = UserProfile()
    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvProfileAccountId: TextView
    private lateinit var btnProfileAuth: MaterialButton
    private lateinit var btnProfileWalletLink: MaterialButton
    private lateinit var tvTaxUserIdentity: TextView

    private lateinit var switchBiometricLock: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var monthlyPnlChart: MonthlyPnlChartView
    private lateinit var tvTotalFeesBadge: TextView
    private lateinit var layoutAppLockOverlay: View
    private lateinit var btnUnlockApp: MaterialButton

    private lateinit var layoutGuestBanner: View
    private lateinit var btnGuestUpgrade: MaterialButton
    private lateinit var layoutAuthProfileContainer: View

    // Embedded Vault Handles
    private lateinit var tvWalletVaultAddress: TextView
    private lateinit var tvWalletBackupStatusBadge: TextView
    private lateinit var btnWalletBackupSeed: MaterialButton
    private lateinit var tvWalletTotalBalance: TextView
    private var btnWalletDeposit: View? = null
    private var btnWalletWithdraw: View? = null
    private var btnWalletManageVault: View? = null
    private var switchDemoMode: com.google.android.material.switchmaterial.SwitchMaterial? = null
    private var btnResetDemoBalance: com.google.android.material.button.MaterialButton? = null
    private var tvDemoModeBadge: TextView? = null
    private var tvWalletDemoBadge: TextView? = null
    private var tvHistoryBadge: TextView? = null
    private var isDemoModeActive: Boolean = true
    private var realOnChainBalance: Double = 0.0

    // Hyperliquid L1 Deposit Handles
    private lateinit var tvHyperliquidBridgeAddress: TextView
    private lateinit var tvHyperliquidBalance: TextView
    private lateinit var tvHyperliquidBalanceUsdc: TextView
    private lateinit var btnHyperliquidDeposit: MaterialButton

    private var isAppUnlocked: Boolean = false

    // Bottom nav
    private lateinit var navTrade: LinearLayout
    private lateinit var navChart: LinearLayout
    private lateinit var navBotChart: LinearLayout
    private lateinit var navWallet: LinearLayout
    private lateinit var navPositions: LinearLayout
    private lateinit var navHistory: LinearLayout
    private lateinit var navTax: LinearLayout
    private lateinit var navSettings: LinearLayout

    private lateinit var chartSection: View
    private lateinit var botChartSection: View
    private lateinit var walletSection: View
    private lateinit var swipeRefreshWallet: SwipeRefreshLayout

    private val navTabs: List<LinearLayout> by lazy {
        listOf(navTrade, navChart, navBotChart, navWallet, navPositions, navHistory, navTax, navSettings)
    }
    private val navIcons: List<ImageView> by lazy {
        listOf(
            navTrade.getChildAt(0) as ImageView,
            navChart.getChildAt(0) as ImageView,
            navBotChart.getChildAt(0) as ImageView,
            navWallet.getChildAt(0) as ImageView,
            navPositions.getChildAt(0) as ImageView,
            navHistory.getChildAt(0) as ImageView,
            navTax.getChildAt(0) as ImageView,
            navSettings.getChildAt(0) as ImageView
        )
    }
    private val navLabels: List<TextView> by lazy {
        listOf(
            navTrade.getChildAt(1) as TextView,
            navChart.getChildAt(1) as TextView,
            navBotChart.getChildAt(1) as TextView,
            navWallet.getChildAt(1) as TextView,
            navPositions.getChildAt(1) as TextView,
            navHistory.getChildAt(1) as TextView,
            navTax.getChildAt(1) as TextView,
            navSettings.getChildAt(1) as TextView
        )
    }

    // Wallet state — driven by WalletConnectionManager.SessionEvent flow.
    private var connectedAddress: String? = null
    private var connectedWalletName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyEdgeToEdge()
        bindViews()
        EmbeddedWalletManager.initializeWallet(this)
        restoreUserProfile()
        restoreDemoTradingState()
        updateEmbeddedVaultUi()
        fetchOnChainBalance()
        fetchHyperliquidBalance()
        FirebaseSyncManager.syncUserDataToFirestore(
            context = this,
            userProfile = currentUserProfile,
            demoBalance = demoBalance,
            activePositions = activePositions,
            closedPositions = closedPositions
        )
        setupChartWebView()
        wireInteractions()
        restoreTaxFilterState()
        applySideColors()    // initial Long/Short styling
        applyTypeToggle()    // initial Market/Limit styling
        applyMarginModeColors() // initial Isolated/Cross styling
        syncChipFromSlider(sliderLeverage.value.toInt())
        updateDemoBalanceUi()
        updatePositionSizeHint()
        updatePositionsListUi()
        startPriceEngine()
        selectNav(0)         // default selected tab = Trade

        lifecycleScope.launch {
            HyperliquidOrderManager.fetchMarketMetadata()
            HyperliquidOrderManager.fetchSpotMarketMetadata()
            withContext(Dispatchers.Main) {
                if (isSpotMode) {
                    setupSpotSymbolDropdown()
                } else {
                    setupSymbolDropdown()
                }
            }
        }


        observeWalletEvents()
        WalletConnectionManager.attachNavHost(this)
        WalletConnectionManager.handleRedirect(intent?.dataString)
        restorePersistedWalletUi()
        
        // Only restore WalletConnect session if there's an active session
        // Otherwise clear the persisted session to avoid using old address
        if (WalletConnectionManager.isConnected()) {
            WalletConnectionManager.restoreConnectedSession()
        } else {
            WalletConnectionManager.clearPersistedSession()
        }
        
        checkAppLockState()
        checkOnboardingGateway()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WalletConnectionManager.handleRedirect(intent.dataString)
    }

    override fun onResume() {
        super.onResume()
        if (::webViewChart.isInitialized) {
            webViewChart.onResume()
        }
    }

    override fun onPause() {
        if (::webViewChart.isInitialized) {
            extractAndSaveTradingViewState()
            try {
                android.webkit.CookieManager.getInstance().flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            webViewChart.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            botStatusListener?.remove()
            botStatusListener = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            symbolWebSocket?.close(1000, "Activity destroyed")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (::webViewChart.isInitialized) {
            webViewChart.destroy()
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------------- //
    // Wallet flow — observe SessionEvent from WalletConnectionManager       //
    // -------------------------------------------------------------------- //

    private fun observeWalletEvents() {
        lifecycleScope.launch {
            WalletConnectionManager.events.collect { event ->
                when (event) {
                    is WalletConnectionManager.SessionEvent.Approved -> {
                        onWalletConnected(
                            address = event.address,
                            walletName = event.walletName,
                            showSnack = !event.restored
                        )
                    }
                    is WalletConnectionManager.SessionEvent.Rejected -> {
                        snack(R.string.wallet_cancelled)
                    }
                    is WalletConnectionManager.SessionEvent.Disconnected -> {
                        onWalletDisconnected()
                    }
                    is WalletConnectionManager.SessionEvent.Error -> {
                        snackString(event.message)
                    }
                }
            }
        }
    }

    private fun showWalletPicker() {
        val address = connectedAddress
        if (address != null) {
            showConnectedWalletSheet(address)
            return
        }
        val sheet = WalletPickerBottomSheet()
        sheet.setListener(this)
        sheet.show(supportFragmentManager, WalletPickerBottomSheet.TAG)
    }

    private fun showConnectedWalletSheet(address: String) {
        if (supportFragmentManager.findFragmentByTag(ConnectedWalletBottomSheet.TAG) != null) {
            return
        }
        val sheet = ConnectedWalletBottomSheet.newInstance(
            walletName = connectedWalletName ?: "Wallet",
            address = address
        )
        sheet.setListener(this)
        sheet.show(supportFragmentManager, ConnectedWalletBottomSheet.TAG)
    }

    override fun onDisconnectConfirmed() {
        WalletConnectionManager.disconnect()
    }

    override fun onWalletPicked(wallet: WalletConnectionManager.Wallet) {
        when (val result = WalletConnectionManager.connect(this, wallet)) {
            is WalletConnectionManager.LaunchResult.Opened -> {
                snack(R.string.wallet_awaiting_approval)
            }
            is WalletConnectionManager.LaunchResult.NotInstalled -> {
                snack(R.string.wallet_not_installed)
            }
            is WalletConnectionManager.LaunchResult.Failed -> {
                snackString(result.cause.message ?: getString(R.string.wallet_cancelled))
            }
        }
    }

    private fun onWalletConnected(
        address: String,
        walletName: String,
        showSnack: Boolean = true
    ) {
        val alreadyShown = connectedAddress.equals(address, ignoreCase = true)
        connectedAddress = address
        connectedWalletName = walletName
        btnConnectWallet.text = WalletConnectionManager.shorten(address)
        btnConnectWallet.contentDescription =
            getString(R.string.wallet_already_connected, address)
        if (showSnack && !alreadyShown) {
            snackString("$walletName • $address")
        }

        FirebaseSyncManager.syncWalletAddressToFirestore(
            context = this,
            walletAddress = address,
            accountId = getUserAccountId()
        )
    }

    private fun onWalletDisconnected() {
        connectedAddress = null
        connectedWalletName = null
        btnConnectWallet.text = getString(R.string.connect_wallet)
        btnConnectWallet.contentDescription = getString(R.string.connect_wallet)
        
        // Clear persisted WalletConnect session to prevent using old address
        WalletConnectionManager.clearPersistedSession()

        FirebaseSyncManager.syncWalletAddressToFirestore(
            context = this,
            walletAddress = null,
            accountId = getUserAccountId()
        )
    }

    private fun restorePersistedWalletUi() {
        val saved = WalletConnectionManager.getPersistedSession() ?: return
        onWalletConnected(
            address = saved.address,
            walletName = saved.walletName,
            showSnack = false
        )
    }

    // -------------------------------------------------------------------- //
    // UI plumbing                                                          //
    // -------------------------------------------------------------------- //

    private fun applyEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun bindViews() {
        btnConnectWallet = findViewById(R.id.btnConnectWallet)

        webViewChart = findViewById(R.id.webViewChart)
        chartContainer = findViewById(R.id.chartContainer)
        btnFullscreenChart = findViewById(R.id.btnFullscreenChart)
        fullscreenChartContainer = findViewById(R.id.fullscreenChartContainer)

        sideGroup = findViewById(R.id.sideGroup)
        btnLong = findViewById(R.id.btnLong)
        btnShort = findViewById(R.id.btnShort)

        typeGroup = findViewById(R.id.typeGroup)
        btnMarket = findViewById(R.id.btnMarket)
        btnLimit = findViewById(R.id.btnLimit)

        sliderLeverage = findViewById(R.id.sliderLeverage)
        tvLeverageValue = findViewById(R.id.tvLeverageValue)

        chip1x = findViewById(R.id.chip1x)
        chip5x = findViewById(R.id.chip5x)
        chip10x = findViewById(R.id.chip10x)
        chip25x = findViewById(R.id.chip25x)
        chip50x = findViewById(R.id.chip50x)
        chip100x = findViewById(R.id.chip100x)

        btnOpenPosition = findViewById(R.id.btnOpenPosition)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnExportTaxPdf = findViewById(R.id.btnExportTaxPdf)

        try {
            marginModeGroup = findViewById(R.id.marginModeGroup)
            btnMarginIsolated = findViewById(R.id.btnMarginIsolated)
            btnMarginCross = findViewById(R.id.btnMarginCross)
            tvMarginModeWarning = findViewById(R.id.tvMarginModeWarning)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding margin mode views", e)
            // Continue without margin mode UI if views don't exist
        }
        try {
            marketTypeGroup = findViewById(R.id.marketTypeGroup)
            btnMarketTypePerp = findViewById(R.id.btnMarketTypePerp)
            btnMarketTypeSpot = findViewById(R.id.btnMarketTypeSpot)
            cardSpotBotPanel = findViewById(R.id.cardSpotBotPanel)
            switchBotActive = findViewById(R.id.switchBotActive)
            tvBotStatusBadge = findViewById(R.id.tvBotStatusBadge)
            tvBotAllocationLabel = findViewById(R.id.tvBotAllocationLabel)
            tvBotActiveTradesCount = findViewById(R.id.tvBotActiveTradesCount)
            tvBotTotalPnl = findViewById(R.id.tvBotTotalPnl)
            sliderBotAllocation = findViewById(R.id.sliderBotAllocation)
            chipGroupBotStrategy = findViewById(R.id.chipGroupBotStrategy)
            chipStrategyDca = findViewById(R.id.chipStrategyDca)
            chipStrategyGrid = findViewById(R.id.chipStrategyGrid)
            chipStrategyTrend = findViewById(R.id.chipStrategyTrend)
            chipStrategyEmaChannel = findViewById(R.id.chipStrategyEmaChannel)
            layoutSpotBalances = findViewById(R.id.layoutSpotBalances)
            tvSpotUsdcBalance = findViewById(R.id.tvSpotUsdcBalance)
            tvSpotAssetBalance = findViewById(R.id.tvSpotAssetBalance)
            layoutPerpMarginContainer = findViewById(R.id.layoutPerpMarginContainer)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding Spot views", e)
        }

        actvSymbol = findViewById(R.id.actvSymbol)
        tvLivePriceHeader = findViewById(R.id.tvLivePriceHeader)
        layoutEntryPrice = findViewById(R.id.layoutEntryPrice)
        etEntryPrice = findViewById(R.id.etEntryPrice)
        etAmount = findViewById(R.id.etAmount)
        etTp = findViewById(R.id.etTp)
        etSl = findViewById(R.id.etSl)
        tvTpEstProfit = findViewById(R.id.tvTpEstProfit)
        tvSlEstLoss = findViewById(R.id.tvSlEstLoss)
        tvAvailableBalance = findViewById(R.id.tvAvailableBalance)
        tvPositionSizeHint = findViewById(R.id.tvPositionSizeHint)
        btnSetPriceAlert = findViewById(R.id.btnSetPriceAlert)
        tvEstLiquidationPrice = findViewById(R.id.tvEstLiquidationPrice)
        tvRiskLevelBadge = findViewById(R.id.tvRiskLevelBadge)
        progressRiskMeter = findViewById(R.id.progressRiskMeter)
        tvPositionsBadge = findViewById(R.id.tvPositionsBadge)
        tvNoPositions = findViewById(R.id.tvNoPositions)
        positionsListContainer = findViewById(R.id.positionsListContainer)

        btnSetPriceAlert.setOnClickListener { showPriceAlertsDialog() }
        restorePriceAlerts()

        scrollContent = findViewById(R.id.scrollContent)
        settingsSection = findViewById(R.id.settingsSection)
        historySection = findViewById(R.id.historySection)
        tvHistorySummary = findViewById(R.id.tvHistorySummary)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        tvNoHistory = findViewById(R.id.tvNoHistory)
        historyListContainer = findViewById(R.id.historyListContainer)

        taxSection = findViewById(R.id.taxSection)
        tvTaxFilteredStats = findViewById(R.id.tvTaxFilteredStats)
        chipGroupTaxFilter = findViewById(R.id.chipGroupTaxFilter)
        chipTaxAll = findViewById(R.id.chipTaxAll)
        chipTaxLastMonth = findViewById(R.id.chipTaxLastMonth)
        chipTaxCustom = findViewById(R.id.chipTaxCustom)
        actvTaxYear = findViewById(R.id.actvTaxYear)
        setupTaxYearDropdown()
        layoutTaxCustomDate = findViewById(R.id.layoutTaxCustomDate)
        btnTaxStartDate = findViewById(R.id.btnTaxStartDate)
        btnTaxEndDate = findViewById(R.id.btnTaxEndDate)
        btnTaxApplyCustomRange = findViewById(R.id.btnTaxApplyCustomRange)
        currencyToggleGroup = findViewById(R.id.currencyToggleGroup)
        btnCurrencyUsd = findViewById(R.id.btnCurrencyUsd)
        btnCurrencyEur = findViewById(R.id.btnCurrencyEur)
        btnCurrencyGbp = findViewById(R.id.btnCurrencyGbp)

        navTrade = findViewById(R.id.navTrade)
        navChart = findViewById(R.id.navChart)
        navBotChart = findViewById(R.id.navBotChart)
        navWallet = findViewById(R.id.navWallet)
        navPositions = findViewById(R.id.navPositions)
        navHistory = findViewById(R.id.navHistory)
        navTax = findViewById(R.id.navTax)
        navSettings = findViewById(R.id.navSettings)
        chartSection = findViewById(R.id.chartSection)
        botChartSection = findViewById(R.id.botChartSection)
        walletSection = findViewById(R.id.walletSection)
        swipeRefreshWallet = findViewById(R.id.swipeRefreshWallet)
        swipeRefreshWallet.setOnRefreshListener {
            fetchOnChainBalance()
        }

        cardExportBackup = findViewById(R.id.cardExportBackup)
        cardImportRestore = findViewById(R.id.cardImportRestore)
        btnExportBackup = findViewById(R.id.btnExportBackup)
        btnImportRestore = findViewById(R.id.btnImportRestore)

        tvProfileName = findViewById(R.id.tvProfileName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileAccountId = findViewById(R.id.tvProfileAccountId)
        btnProfileAuth = findViewById(R.id.btnProfileAuth)
        btnEditProfile = findViewById(R.id.btnEditProfile)
        btnDeleteAccount = findViewById(R.id.btnDeleteAccount)
        btnProfileWalletLink = findViewById(R.id.btnProfileWalletLink)
        tvTaxUserIdentity = findViewById(R.id.tvTaxUserIdentity)

        switchBiometricLock = findViewById(R.id.switchBiometricLock)
        monthlyPnlChart = findViewById(R.id.monthlyPnlChart)
        tvTotalFeesBadge = findViewById(R.id.tvTotalFeesBadge)
        layoutAppLockOverlay = findViewById(R.id.layoutAppLockOverlay)
        btnUnlockApp = findViewById(R.id.btnUnlockApp)

        layoutGuestBanner = findViewById(R.id.layoutGuestBanner)
        btnGuestUpgrade = findViewById(R.id.btnGuestUpgrade)
        layoutAuthProfileContainer = findViewById(R.id.layoutAuthProfileContainer)

        tvWalletVaultAddress = findViewById(R.id.tvWalletVaultAddress)
        tvWalletBackupStatusBadge = findViewById(R.id.tvWalletBackupStatusBadge)
        btnWalletBackupSeed = findViewById(R.id.btnWalletBackupSeed)
        tvWalletTotalBalance = findViewById(R.id.tvWalletTotalBalance)
        btnWalletDeposit = findViewById(R.id.btnWalletDeposit)
        btnWalletWithdraw = findViewById(R.id.btnWalletWithdraw)
        btnWalletManageVault = findViewById(R.id.btnWalletManageVault)

        switchDemoMode = findViewById(R.id.switchDemoMode)
        tvDemoModeBadge = findViewById(R.id.tvDemoModeBadge)
        tvWalletDemoBadge = findViewById(R.id.tvWalletDemoBadge)
        tvHistoryBadge = findViewById(R.id.tvHistoryBadge)

        // Hyperliquid L1 Deposit Views
        tvHyperliquidBridgeAddress = findViewById(R.id.tvHyperliquidBridgeAddress)
        tvHyperliquidBalance = findViewById(R.id.tvHyperliquidBalance)
        tvHyperliquidBalanceUsdc = findViewById(R.id.tvHyperliquidBalanceUsdc)
        btnHyperliquidDeposit = findViewById(R.id.btnHyperliquidDeposit)
    }

    private fun wireInteractions() {
        btnConnectWallet.setOnClickListener { showWalletPicker() }
        makeViewDraggableAndClickable(btnFullscreenChart, chartContainer) { toggleFullscreenChart() }

        btnWalletBackupSeed?.setOnClickListener { authenticateAndShowSeedPhraseBackup() }
        btnWalletDeposit?.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java).apply { putExtra("EXTRA_MODE", "DEPOSIT") })
        }
        btnWalletWithdraw?.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java).apply { putExtra("EXTRA_MODE", "WITHDRAW") })
        }
        btnWalletManageVault?.setOnClickListener { showVaultManagementDialog() }

        // Hyperliquid L1 Deposit click handler
        btnHyperliquidDeposit.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java).apply {
                putExtra("EXTRA_MODE", "DEPOSIT")
                putExtra("EXTRA_HYPERLIQUID", true)
                putExtra("EXTRA_ASSET", "USDC")
            })
        }

        findViewById<View>(R.id.itemAssetUsdt)?.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java).apply {
                putExtra("EXTRA_MODE", "DEPOSIT")
                putExtra("EXTRA_ASSET", "USDT")
            })
        }
        findViewById<View>(R.id.itemAssetUsdc)?.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java).apply {
                putExtra("EXTRA_MODE", "DEPOSIT")
                putExtra("EXTRA_ASSET", "USDC")
            })
        }
        findViewById<View>(R.id.itemAssetEth)?.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java).apply {
                putExtra("EXTRA_MODE", "DEPOSIT")
                putExtra("EXTRA_ASSET", "ETH")
            })
        }
        findViewById<View>(R.id.itemAssetWbtc)?.setOnClickListener {
            startActivity(Intent(this, ReceiveActivity::class.java).apply {
                putExtra("EXTRA_MODE", "DEPOSIT")
                putExtra("EXTRA_ASSET", "BTC")
            })
        }

        findViewById<View>(R.id.btnTransferSpot)?.setOnClickListener { showTransferDialog() }

        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        isDemoModeActive = prefs.getBoolean("is_demo_mode", true)
        switchDemoMode?.isChecked = isDemoModeActive
        btnResetDemoBalance = findViewById(R.id.btnResetDemoBalance)
        updateTradingEnvironmentUi()
        updateResetDemoButtonVisibility()

        switchDemoMode?.setOnCheckedChangeListener { _, isChecked ->
            isDemoModeActive = isChecked
            prefs.edit().putBoolean("is_demo_mode", isChecked).apply()
            updateTradingEnvironmentUi()
            updateResetDemoButtonVisibility()
            if (!isChecked) {
                fetchHyperliquidBalance()
            }
            val msg = if (isChecked) "Demo Mode Enabled ($10,000 Practice Balance)" else "Real On-Chain Mode Enabled (Hyperliquid L1 Active)"
            snackString(msg)
        }

        btnResetDemoBalance?.setOnClickListener {
            if (!isDemoModeActive) {
                snackString("Enable Demo Mode first to reset the demo balance.")
                return@setOnClickListener
            }
            demoBalance = 0.0
            saveDemoTradingState()
            updateDemoBalanceUi()
            updateEmbeddedVaultUi()
            snackString("💰 Demo Balance Reset to $0.00 USDC")
        }

        cardExportBackup.setOnClickListener { startExportBackup() }
        btnExportBackup.setOnClickListener { startExportBackup() }
        cardImportRestore.setOnClickListener { startImportRestore() }
        btnImportRestore.setOnClickListener { startImportRestore() }

        btnProfileAuth.setOnClickListener {
            if (currentUserProfile.isLoggedIn) {
                signOutUser()
            } else {
                showAuthDialog(false)
            }
        }
        btnEditProfile.setOnClickListener { showEditProfileDialog() }
        btnDeleteAccount.setOnClickListener { showDeleteAccountConfirmationDialog() }
        btnProfileWalletLink.setOnClickListener { showWalletPicker() }
        switchBiometricLock.isChecked = prefs.getBoolean("biometric_lock_enabled", false)
        switchBiometricLock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("biometric_lock_enabled", isChecked).apply()
            if (isChecked) {
                snackString("Biometric Security & Lock enabled")
            } else {
                snackString("Biometric Lock disabled")
            }
        }

        btnUnlockApp.setOnClickListener {
            showBiometricPrompt()
        }

        if (::btnGuestUpgrade.isInitialized) {
            btnGuestUpgrade.setOnClickListener { showAuthDialog(false) }
        }

        setupSymbolDropdown()

        sideGroup.check(R.id.btnLong)
        applySideColors()

        btnLong.setOnClickListener {
            sideGroup.check(R.id.btnLong)
            applySideColors()
            updatePositionSizeHint()
            updateTpSlEstUi()
        }

        btnShort.setOnClickListener {
            sideGroup.check(R.id.btnShort)
            applySideColors()
            updatePositionSizeHint()
            updateTpSlEstUi()
        }

        sideGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applySideColors()
            updatePositionSizeHint()
            updateTpSlEstUi()
        }

        typeGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applyTypeToggle()
            updatePositionSizeHint()
            updateTpSlEstUi()
        }

        etAmount.addTextChangedListener {
            updatePositionSizeHint()
            updateTpSlEstUi()
        }

        etEntryPrice.addTextChangedListener {
            updateTpSlEstUi()
        }

        etTp.addTextChangedListener { text ->
            // Prevent invalid decimal points
            val current = text.toString()
            if (current.count { it == '.' } > 1) {
                text?.delete(current.lastIndexOf('.'), current.lastIndexOf('.') + 1)
            }
            updateTpSlEstUi()
        }

        etSl.addTextChangedListener { text ->
            // Prevent invalid decimal points
            val current = text.toString()
            if (current.count { it == '.' } > 1) {
                text?.delete(current.lastIndexOf('.'), current.lastIndexOf('.') + 1)
            }
            updateTpSlEstUi()
        }

        sliderLeverage.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            tvLeverageValue.text = getString(R.string.leverage_value).replace("10x", "${v}x")
            syncChipFromSlider(v)
            updatePositionSizeHint()
            updateTpSlEstUi()
        }

        val chipListener = { chip: Chip, value: Int ->
            chip.setOnClickListener {
                if (chip.isEnabled) {
                    sliderLeverage.value = value.toFloat()
                    tvLeverageValue.text = "${value}x"
                    updatePositionSizeHint()
                    updateTpSlEstUi()
                    syncLeverageAndMarginModeToHyperliquid()
                }
            }
        }
        chipListener(chip1x, 1)
        chipListener(chip5x, 5)
        chipListener(chip10x, 10)
        chipListener(chip25x, 25)
        chipListener(chip50x, 50)
        chipListener(chip100x, 100)

        // Margin Mode Toggle Handler
        if (::marginModeGroup.isInitialized) {
            marginModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    val newIsolated = (checkedId == R.id.btnMarginIsolated)
                    if (newIsolated != marginModeIsolated) {
                        marginModeIsolated = newIsolated
                        applyMarginModeColors()

                        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
                        prefs.edit().putBoolean("margin_mode_isolated", marginModeIsolated).apply()

                        val mode = if (marginModeIsolated) "Isolated" else "Cross"
                        snackString("Margin Mode: $mode")

                        syncLeverageAndMarginModeToHyperliquid()
                    }
                }
            }
        }

        if (::marketTypeGroup.isInitialized) {
            marketTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    val isSpot = (checkedId == R.id.btnMarketTypeSpot)
                    toggleMarketType(isSpot)
                }
            }
        }
        setupBotEngine()

        btnOpenPosition.setOnClickListener {
            if (isSpotMode) executeSpotOrder() else executeOrder()
        }
        tvAvailableBalance.setOnClickListener { fetchHyperliquidBalance(showToast = true) }
        btnExportCsv.setOnClickListener { exportTaxCsv() }
        btnExportTaxPdf.setOnClickListener { exportTaxPdf() }

        btnClearHistory.setOnClickListener {
            val count = closedPositions.count { it.isDemo == isDemoModeActive }
            if (count == 0) return@setOnClickListener
            closedPositions.removeAll { it.isDemo == isDemoModeActive }
            saveDemoTradingState()
            updateHistoryListUi()
            val modeLabel = if (isDemoModeActive) "Demo" else "Real"
            snackString("$modeLabel trade history cleared")
        }

        chipTaxAll.setOnClickListener {
            if (::actvTaxYear.isInitialized) actvTaxYear.setText("Select Tax Year", false)
            currentTaxFilterType = "ALL"
            syncTaxFilterChip(chipTaxAll)
            layoutTaxCustomDate.visibility = View.GONE
            updateTaxFilterUi("ALL")
            saveTaxFilterState("ALL")
        }
        chipTaxLastMonth.setOnClickListener {
            if (::actvTaxYear.isInitialized) actvTaxYear.setText("Select Tax Year", false)
            currentTaxFilterType = "LAST_MONTH"
            syncTaxFilterChip(chipTaxLastMonth)
            layoutTaxCustomDate.visibility = View.GONE
            updateTaxFilterUi("LAST_MONTH")
            saveTaxFilterState("LAST_MONTH")
        }
        chipTaxCustom.setOnClickListener {
            if (::actvTaxYear.isInitialized) actvTaxYear.setText("Select Tax Year", false)
            currentTaxFilterType = "CUSTOM"
            syncTaxFilterChip(chipTaxCustom)
            layoutTaxCustomDate.visibility = View.VISIBLE
            updateTaxFilterUi("CUSTOM")
            saveTaxFilterState("CUSTOM")
        }

        currencyToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selectedCurrency = when (checkedId) {
                R.id.btnCurrencyEur -> "EUR"
                R.id.btnCurrencyGbp -> "GBP"
                else -> "USD"
            }
            syncCurrencyToggleStyle(selectedCurrency)
            if (currentUserProfile.baseCurrency != selectedCurrency) {
                currentUserProfile = currentUserProfile.copy(baseCurrency = selectedCurrency)
                saveUserProfile()
                updateUserProfileUi()
                updateDemoBalanceUi()
                updatePositionsListUi()
                updateHistoryListUi()
                val activeFilter = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE).getString("tax_filter_type", "ALL") ?: "ALL"
                updateTaxFilterUi(activeFilter)
                snackString("Base Currency updated to $selectedCurrency")
            }
        }

        btnTaxStartDate.setOnClickListener {
            showDatePickerDialog { _, _, _, timestampMs, formatted ->
                customTaxStartMs = timestampMs
                btnTaxStartDate.text = formatted
            }
        }

        btnTaxEndDate.setOnClickListener {
            showDatePickerDialog { _, _, _, timestampMs, formatted ->
                val endOfDayMs = timestampMs + (24 * 3600 * 1000 - 1)
                customTaxEndMs = endOfDayMs
                btnTaxEndDate.text = formatted
            }
        }

        btnTaxApplyCustomRange.setOnClickListener {
            val start = customTaxStartMs
            val end = customTaxEndMs
            if (start == null || end == null) {
                snackString("Please select both Start and End dates")
                return@setOnClickListener
            }
            if (start > end) {
                snackString("Start Date must be before or equal to End Date")
                return@setOnClickListener
            }

            // Remove existing dynamic custom chip if present
            dynamicCustomTaxChip?.let { oldChip ->
                chipGroupTaxFilter.removeView(oldChip)
                dynamicCustomTaxChip = null
            }

            val rangeText = "${btnTaxStartDate.text} to ${btnTaxEndDate.text}"
            val newChip = Chip(this).apply {
                text = rangeText
                isCheckable = true
                isChecked = true
                isCloseIconVisible = true
                setCloseIconResource(R.drawable.ic_close)
                closeIconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_red))
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bg_elevated))
                chipStrokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_green))
                chipStrokeWidth = (1 * resources.displayMetrics.density)
                setTextColor(ContextCompat.getColor(context, R.color.brand_green))

                setOnClickListener {
                    syncTaxFilterChip(this)
                    layoutTaxCustomDate.visibility = View.GONE
                    updateTaxFilterUi("CUSTOM")
                    saveTaxFilterState("CUSTOM")
                }

                setOnCloseIconClickListener {
                    chipGroupTaxFilter.removeView(this)
                    dynamicCustomTaxChip = null
                    saveTaxFilterState("ALL")
                    chipTaxAll.performClick()
                }
            }

            dynamicCustomTaxChip = newChip
            chipGroupTaxFilter.addView(newChip)
            syncTaxFilterChip(newChip)
            layoutTaxCustomDate.visibility = View.GONE
            updateTaxFilterUi("CUSTOM")
            saveTaxFilterState("CUSTOM")
            snackString("Filter applied: $rangeText")
        }

        navTabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectNav(index) }
        }
    }

    private fun applySideColors() {
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val red = ContextCompat.getColor(this, R.color.brand_red)
        val elevated = ContextCompat.getColor(this, R.color.bg_elevated)
        val black = ContextCompat.getColor(this, R.color.black)
        val white = ContextCompat.getColor(this, R.color.white)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)

        val isLong = (sideGroup.checkedButtonId == R.id.btnLong) || (btnLong.isChecked && !btnShort.isChecked)

        if (isLong) {
            btnLong.isChecked = true
            btnLong.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            btnLong.setTextColor(black)

            btnShort.isChecked = false
            btnShort.backgroundTintList = android.content.res.ColorStateList.valueOf(elevated)
            btnShort.setTextColor(textPrimary)

            btnOpenPosition.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            btnOpenPosition.setTextColor(black)
            btnOpenPosition.text = "Open Long"
        } else {
            btnShort.isChecked = true
            btnShort.backgroundTintList = android.content.res.ColorStateList.valueOf(red)
            btnShort.setTextColor(white)

            btnLong.isChecked = false
            btnLong.backgroundTintList = android.content.res.ColorStateList.valueOf(elevated)
            btnLong.setTextColor(textPrimary)

            btnOpenPosition.backgroundTintList = android.content.res.ColorStateList.valueOf(red)
            btnOpenPosition.setTextColor(white)
            btnOpenPosition.text = "Open Short"
        }
    }

    private fun applyMarginModeColors() {
        if (!::btnMarginIsolated.isInitialized || !::btnMarginCross.isInitialized) return
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val darkGray = ContextCompat.getColor(this, R.color.bg_elevated)
        val black = ContextCompat.getColor(this, R.color.black)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)

        if (marginModeIsolated) {
            btnMarginIsolated.isChecked = true
            btnMarginIsolated.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            btnMarginIsolated.setTextColor(black)

            btnMarginCross.isChecked = false
            btnMarginCross.backgroundTintList = android.content.res.ColorStateList.valueOf(darkGray)
            btnMarginCross.setTextColor(textPrimary)
        } else {
            btnMarginCross.isChecked = true
            btnMarginCross.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            btnMarginCross.setTextColor(black)

            btnMarginIsolated.isChecked = false
            btnMarginIsolated.backgroundTintList = android.content.res.ColorStateList.valueOf(darkGray)
            btnMarginIsolated.setTextColor(textPrimary)
        }

        val warning = if (marginModeIsolated) {
            "⚠️ Isolated Mode: Only position margin at risk. Safe for individual trades."
        } else {
            "⚠️ CROSS MODE WARNING: Entire account balance at risk! Only use if you understand the risks."
        }
        if (::tvMarginModeWarning.isInitialized) {
            tvMarginModeWarning.text = warning
            tvMarginModeWarning.setTextColor(
                if (marginModeIsolated) getColor(R.color.brand_green) else getColor(R.color.brand_red)
            )
        }
    }

    private fun syncLeverageAndMarginModeToHyperliquid() {
        if (isDemoModeActive) return
        if (!::sliderLeverage.isInitialized) return

        val currentLeverage = sliderLeverage.value.toInt()
        val isCross = !marginModeIsolated
        val assetIndex = HyperliquidOrderManager.getAssetIndex(currentDisplaySymbol)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (success, msg) = HyperliquidOrderManager.updateLeverageOnHyperliquid(
                    ctx = this@MainActivity,
                    asset = assetIndex,
                    leverage = currentLeverage,
                    isCross = isCross
                )
                withContext(Dispatchers.Main) {
                    if (success) {
                        snackString("Hyperliquid L1 Margin Updated: ${if (isCross) "Cross" else "Isolated"} ${currentLeverage}x")
                    } else {
                        Log.w("MainActivity", "L1 updateLeverage warning: $msg")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed sync leverage to Hyperliquid", e)
            }
        }
    }

    private fun syncChipFromSlider(value: Int) {
        val map = mapOf(
            1 to chip1x,
            5 to chip5x,
            10 to chip10x,
            25 to chip25x,
            50 to chip50x,
            100 to chip100x
        )
        val greenStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_green))
        val dividerStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider))
        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val primaryColor = ContextCompat.getColor(this, R.color.text_primary)

        map.forEach { (chipValue, chip) ->
            val isSelected = (chipValue == value)
            chip.isChecked = isSelected
            chip.chipStrokeColor = if (isSelected) greenStateList else dividerStateList
            chip.setTextColor(if (isSelected) greenColor else primaryColor)
        }
    }

    private fun showDatePickerDialog(onDateSelected: (year: Int, month: Int, day: Int, timestampMs: Long, formatted: String) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH)
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)

        val dpd = android.app.DatePickerDialog(this, { _, y, m, d ->
            val selCal = java.util.Calendar.getInstance()
            selCal.set(y, m, d, 0, 0, 0)
            selCal.set(java.util.Calendar.MILLISECOND, 0)
            val formatted = String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            onDateSelected(y, m, d, selCal.timeInMillis, formatted)
        }, year, month, day)
        dpd.show()
    }

    private fun setupTaxYearDropdown() {
        if (!::actvTaxYear.isInitialized) return
        val yearList = mutableListOf("Select Tax Year")
        for (y in 2020..2030) {
            yearList.add(y.toString())
        }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, yearList)
        actvTaxYear.setAdapter(adapter)

        actvTaxYear.setOnItemClickListener { _, _, position, _ ->
            val selected = yearList[position]
            if (selected != "Select Tax Year") {
                selectTaxYearFromDropdown(selected)
            } else {
                currentTaxFilterType = "ALL"
                syncTaxFilterChip(chipTaxAll)
                layoutTaxCustomDate.visibility = View.GONE
                updateTaxFilterUi("ALL")
                saveTaxFilterState("ALL")
            }
        }
    }

    private fun selectTaxYearFromDropdown(yearStr: String) {
        val yearInt = yearStr.toIntOrNull() ?: return
        currentTaxFilterType = yearStr

        val cal = java.util.Calendar.getInstance()
        cal.set(yearInt, java.util.Calendar.JANUARY, 1, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis

        cal.set(yearInt, java.util.Calendar.DECEMBER, 31, 23, 59, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endMs = cal.timeInMillis

        customTaxStartMs = startMs
        customTaxEndMs = endMs

        btnTaxStartDate.text = String.format(java.util.Locale.US, "%04d-01-01", yearInt)
        btnTaxEndDate.text = String.format(java.util.Locale.US, "%04d-12-31", yearInt)

        syncTaxFilterChip(null)
        layoutTaxCustomDate.visibility = View.GONE

        updateTaxFilterUi(yearStr)
        saveTaxFilterState(yearStr)
    }

    private fun syncTaxFilterChip(selectedChip: Chip?) {
        val allChips = mutableListOf(chipTaxAll, chipTaxLastMonth, chipTaxCustom)
        dynamicCustomTaxChip?.let { allChips.add(it) }

        val greenStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_green))
        val dividerStateList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.divider))
        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val primaryColor = ContextCompat.getColor(this, R.color.text_primary)

        allChips.forEach { chip ->
            val isSel = (selectedChip != null && chip == selectedChip)
            chip.isChecked = isSel
            chip.chipStrokeColor = if (isSel) greenStateList else dividerStateList
            chip.setTextColor(if (isSel) greenColor else primaryColor)
        }
    }

    private fun getFilteredClosedPositions(): List<ClosedPosition> {
        val activeFilter = currentTaxFilterType
        val targetYear = activeFilter.toIntOrNull()
        val now = System.currentTimeMillis()
        val modeHistory = closedPositions.filter { it.isDemo == isDemoModeActive }

        return when {
            targetYear != null -> {
                val cal = java.util.Calendar.getInstance()
                modeHistory.filter {
                    cal.timeInMillis = it.timestamp
                    cal.get(java.util.Calendar.YEAR) == targetYear
                }
            }
            activeFilter == "LAST_MONTH" -> {
                val thirtyDaysMs = 30L * 24 * 3600 * 1000
                modeHistory.filter { (now - it.timestamp) <= thirtyDaysMs }
            }
            activeFilter == "CUSTOM" -> {
                val start = customTaxStartMs ?: 0L
                val end = customTaxEndMs ?: Long.MAX_VALUE
                modeHistory.filter { it.timestamp in start..end }
            }
            else -> modeHistory
        }
    }

    private fun updateTaxFilterUi(filterType: String) {
        val filtered = getFilteredClosedPositions()
        val count = filtered.size
        val pnl = filtered.sumOf { it.realizedPnl }
        val formattedPnl = formatCurrency(pnl)
        tvTaxFilteredStats.text = String.format(java.util.Locale.US, "Taxable Closed Trades: %d  |  Taxable PnL: %s", count, formattedPnl)

        val totalFeesUsd = filtered.sumOf { it.positionSizeUsdc * 0.0005 * 2.0 }
        val formattedFees = formatCurrency(totalFeesUsd)
        tvTotalFeesBadge.text = "Total Fees: $formattedFees"

        if (::monthlyPnlChart.isInitialized) {
            monthlyPnlChart.setData(filtered, filterType, currentUserProfile.baseCurrency)
        }
    }

    private fun checkAppLockState() {
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        val isLockEnabled = prefs.getBoolean("biometric_lock_enabled", false)
        if (isLockEnabled && !isAppUnlocked) {
            layoutAppLockOverlay.visibility = View.VISIBLE
            showBiometricPrompt()
        } else {
            layoutAppLockOverlay.visibility = View.GONE
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAppUnlocked = true
                    layoutAppLockOverlay.visibility = View.GONE
                    snackString("Authentication Succeeded")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        snackString("Auth Error: $errString")
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    snackString("Biometric authentication failed")
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("AetherDex Security Lock")
            .setSubtitle("Authenticate to access your trading portal & financial data")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveTaxFilterState(filterType: String) {
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("tax_filter_type", filterType)
            if (customTaxStartMs != null) putLong("tax_custom_start_ms", customTaxStartMs!!) else remove("tax_custom_start_ms")
            if (customTaxEndMs != null) putLong("tax_custom_end_ms", customTaxEndMs!!) else remove("tax_custom_end_ms")
            if (::btnTaxStartDate.isInitialized && btnTaxStartDate.text.isNotEmpty()) putString("tax_custom_start_str", btnTaxStartDate.text.toString())
            if (::btnTaxEndDate.isInitialized && btnTaxEndDate.text.isNotEmpty()) putString("tax_custom_end_str", btnTaxEndDate.text.toString())
        }.apply()
    }

    private fun restoreTaxFilterState() {
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        val filterType = prefs.getString("tax_filter_type", "ALL") ?: "ALL"

        val savedStartMs = if (prefs.contains("tax_custom_start_ms")) prefs.getLong("tax_custom_start_ms", 0L) else null
        val savedEndMs = if (prefs.contains("tax_custom_end_ms")) prefs.getLong("tax_custom_end_ms", 0L) else null
        val savedStartStr = prefs.getString("tax_custom_start_str", null)
        val savedEndStr = prefs.getString("tax_custom_end_str", null)

        if (savedStartMs != null && savedEndMs != null && savedStartStr != null && savedEndStr != null) {
            customTaxStartMs = savedStartMs
            customTaxEndMs = savedEndMs
            btnTaxStartDate.text = savedStartStr
            btnTaxEndDate.text = savedEndStr

            if (dynamicCustomTaxChip == null && ::chipGroupTaxFilter.isInitialized) {
                val rangeText = "$savedStartStr to $savedEndStr"
                val newChip = Chip(this).apply {
                    text = rangeText
                    isCheckable = true
                    isChecked = (filterType == "CUSTOM")
                    isCloseIconVisible = true
                    setCloseIconResource(R.drawable.ic_close)
                    closeIconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_red))
                    chipBackgroundColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bg_elevated))
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand_green))
                    chipStrokeWidth = (1 * resources.displayMetrics.density)
                    setTextColor(ContextCompat.getColor(context, R.color.brand_green))

                    setOnClickListener {
                        syncTaxFilterChip(this)
                        layoutTaxCustomDate.visibility = View.GONE
                        updateTaxFilterUi("CUSTOM")
                        saveTaxFilterState("CUSTOM")
                    }

                    setOnCloseIconClickListener {
                        chipGroupTaxFilter.removeView(this)
                        dynamicCustomTaxChip = null
                        saveTaxFilterState("ALL")
                        chipTaxAll.performClick()
                    }
                }
                dynamicCustomTaxChip = newChip
                chipGroupTaxFilter.addView(newChip)
            }
        }

        currentTaxFilterType = filterType
        val isYear = (filterType.toIntOrNull() != null)

        if (isYear) {
            if (::actvTaxYear.isInitialized) {
                actvTaxYear.setText(filterType, false)
            }
            syncTaxFilterChip(null)
            layoutTaxCustomDate.visibility = View.GONE
            updateTaxFilterUi(filterType)
        } else {
            when (filterType) {
                "LAST_MONTH" -> {
                    syncTaxFilterChip(chipTaxLastMonth)
                    layoutTaxCustomDate.visibility = View.GONE
                    updateTaxFilterUi("LAST_MONTH")
                }
                "CUSTOM" -> {
                    dynamicCustomTaxChip?.let {
                        syncTaxFilterChip(it)
                        layoutTaxCustomDate.visibility = View.GONE
                        updateTaxFilterUi("CUSTOM")
                    } ?: run {
                        syncTaxFilterChip(chipTaxAll)
                        layoutTaxCustomDate.visibility = View.GONE
                        updateTaxFilterUi("ALL")
                    }
                }
                else -> {
                    syncTaxFilterChip(chipTaxAll)
                    layoutTaxCustomDate.visibility = View.GONE
                    updateTaxFilterUi("ALL")
                }
            }
        }
    }

    private fun saveFileToPublicDownloads(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                    }
                    true
                } else {
                    false
                }
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val targetFile = File(downloadsDir, fileName)
                targetFile.writeBytes(bytes)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun exportTaxCsv() {
        requireAuthForFeature("Tax CSV Export") {
            executeExportTaxCsv()
        }
    }

    private fun executeExportTaxCsv() {
        val list = getFilteredClosedPositions()
        if (list.isEmpty()) {
            snackString("No closed trades found for the selected date filter.")
            return
        }

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val sb = StringBuilder()
        sb.append("Trade ID,Date Opened,Date Closed,Symbol,Type,Leverage,Entry Price (USDC),Exit Price (USDC),Margin (USDC),Position Size (USDC),Taker Fee (USDC),Realized PnL (USDC),Realized PnL (%),Net PnL (USDC),Close Reason\n")

        for (pos in list) {
            val openTimeStr = dateFormat.format(java.util.Date(pos.timestamp - 300000L))
            val closeTimeStr = dateFormat.format(java.util.Date(pos.timestamp))
            val sideStr = if (pos.isLong) "LONG" else "SHORT"
            val takerFee = pos.positionSizeUsdc * 0.0005 * 2.0
            val netPnl = pos.realizedPnl - takerFee

            sb.append(String.format(
                java.util.Locale.US,
                "\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%d,%.4f,%.4f,%.2f,%.2f,%.4f,%.2f,%.2f,%.2f,\"%s\"\n",
                pos.id,
                openTimeStr,
                closeTimeStr,
                pos.symbol,
                sideStr,
                pos.leverage,
                pos.entryPrice,
                pos.exitPrice,
                pos.margin,
                pos.positionSizeUsdc,
                takerFee,
                pos.realizedPnl,
                pos.realizedPnlPercent,
                netPnl,
                pos.closeReason
            ))
        }

        try {
            val fileName = "AetherDex_TaxReport_${System.currentTimeMillis()}.csv"
            val bytes = sb.toString().toByteArray(Charsets.UTF_8)
            val success = saveFileToPublicDownloads(fileName, "text/csv", bytes)
            if (success) {
                snackString("File saved to Downloads: $fileName")
            } else {
                snackString("Failed to save CSV to Downloads")
            }
        } catch (e: Exception) {
            snackString("CSV Export error: ${e.localizedMessage}")
        }
    }

    private fun restoreUserProfile() {
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        val jsonString = prefs.getString("user_profile_json", null)
        if (jsonString != null) {
            try {
                currentUserProfile = parseUserProfileFromJson(org.json.JSONObject(jsonString))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (currentUserProfile.accountId.isEmpty()) {
            val legacyId = prefs.getString("user_account_id", null)
            val generatedId = legacyId ?: ("AD-" + (100000..999999).random())
            currentUserProfile = currentUserProfile.copy(accountId = generatedId)
            saveUserProfile()
        }

        updateUserProfileUi()
    }

    private fun saveUserProfile() {
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        prefs.edit()
            .putString("user_profile_json", currentUserProfile.toJson().toString())
            .putString("user_account_id", currentUserProfile.accountId)
            .apply()

        FirebaseSyncManager.syncUserDataToFirestore(
            context = this,
            userProfile = currentUserProfile,
            demoBalance = demoBalance,
            activePositions = activePositions,
            closedPositions = closedPositions,
            connectedWalletAddress = connectedAddress
        )
    }

    private fun updateUserProfileUi() {
        val accountId = getUserAccountId()
        val isLoggedIn = currentUserProfile.isLoggedIn
        val fbUser = FirebaseSyncManager.auth.currentUser

        var displayName = currentUserProfile.fullName.ifBlank {
            fbUser?.displayName ?: ""
        }
        if (displayName.isBlank() && (currentUserProfile.email.contains("@") || (fbUser != null && !fbUser.email.isNullOrBlank()))) {
            val emailToUse = currentUserProfile.email.ifBlank { fbUser?.email ?: "" }
            if (emailToUse.contains("@")) {
                displayName = emailToUse.substringBefore("@")
            }
        }
        if (displayName.isBlank() && isLoggedIn) {
            displayName = "Trader_${accountId.takeLast(6)}"
        }

        if (::layoutGuestBanner.isInitialized) {
            layoutGuestBanner.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
        }
        if (::layoutAuthProfileContainer.isInitialized) {
            layoutAuthProfileContainer.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        }

        if (::tvProfileName.isInitialized) {
            if (isLoggedIn) {
                tvProfileName.text = displayName
                tvProfileEmail.text = currentUserProfile.email.ifBlank { fbUser?.email ?: "Signed in via Secure Auth" }
                tvProfileAccountId.text = "Account ID: $accountId"
                btnProfileAuth.text = "Sign Out"
                btnProfileAuth.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.bg_panel)
                )
                btnProfileAuth.setTextColor(ContextCompat.getColor(this, R.color.brand_red))
                btnProfileAuth.strokeColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.brand_red)
                )
                btnProfileAuth.strokeWidth = (1 * resources.displayMetrics.density).toInt()

                if (::btnEditProfile.isInitialized) btnEditProfile.visibility = View.VISIBLE
                if (::btnDeleteAccount.isInitialized) btnDeleteAccount.visibility = View.VISIBLE
            } else {
                tvProfileName.text = "Guest Trader"
                tvProfileEmail.text = "Sign in for Secure Cloud Sync"
                tvProfileAccountId.text = "Account ID: $accountId"
                btnProfileAuth.text = "Sign In / Create Account"
                btnProfileAuth.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.brand_green)
                )
                btnProfileAuth.setTextColor(ContextCompat.getColor(this, R.color.black))
                btnProfileAuth.strokeWidth = 0

                if (::btnEditProfile.isInitialized) btnEditProfile.visibility = View.GONE
                if (::btnDeleteAccount.isInitialized) btnDeleteAccount.visibility = View.GONE
            }
        }

        if (::tvTaxUserIdentity.isInitialized) {
            val taxName = if (isLoggedIn) displayName else "Guest Trader"
            tvTaxUserIdentity.text = "Tax Identity: $taxName (Account ID: $accountId)"
        }

        if (::currencyToggleGroup.isInitialized) {
            val curr = currentUserProfile.baseCurrency
            when (curr) {
                "EUR" -> currencyToggleGroup.check(R.id.btnCurrencyEur)
                "GBP" -> currencyToggleGroup.check(R.id.btnCurrencyGbp)
                else -> currencyToggleGroup.check(R.id.btnCurrencyUsd)
            }
            syncCurrencyToggleStyle(curr)
        }
    }

    private fun syncCurrencyToggleStyle(selectedCurrency: String) {
        if (!::btnCurrencyUsd.isInitialized || !::btnCurrencyEur.isInitialized || !::btnCurrencyGbp.isInitialized) return
        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val elevatedColor = ContextCompat.getColor(this, R.color.bg_elevated)
        val blackColor = ContextCompat.getColor(this, R.color.black)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val dividerColor = ContextCompat.getColor(this, R.color.divider)

        val updateButton = { btn: MaterialButton, isSelected: Boolean ->
            btn.isChecked = isSelected
            if (isSelected) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(greenColor)
                btn.setTextColor(blackColor)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(greenColor)
                btn.setTypeface(btn.typeface, android.graphics.Typeface.BOLD)
            } else {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(elevatedColor)
                btn.setTextColor(textPrimary)
                btn.strokeColor = android.content.res.ColorStateList.valueOf(dividerColor)
                btn.setTypeface(btn.typeface, android.graphics.Typeface.NORMAL)
            }
        }

        updateButton(btnCurrencyUsd, selectedCurrency == "USD")
        updateButton(btnCurrencyEur, selectedCurrency == "EUR")
        updateButton(btnCurrencyGbp, selectedCurrency == "GBP")
    }

    private fun signOutUser() {
        extractAndSaveTradingViewState()
        try {
            FirebaseSyncManager.auth.signOut()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentUserProfile = currentUserProfile.copy(isLoggedIn = false)
        saveUserProfile()
        updateUserProfileUi()
        snackString("Signed out of profile.")
    }

    private fun showEditProfileDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(this).apply {
            text = "Edit Profile Name"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val subtitleTv = TextView(this).apply {
            text = "Update your official profile & address details for tax reports."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (4 * density).toInt(), 0, (14 * density).toInt())
        }

        val labelTv = TextView(this).apply {
            text = "Display Name / Full Name"
            textSize = 12f
            setTextColor(textSecondary)
        }

        val etName = EditText(this).apply {
            hint = "e.g. Satoshi Nakamoto"
            setTextColor(textPrimary)
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pIn = (12 * density).toInt()
            setPadding(pIn, pIn, pIn, pIn)
            textSize = 14f
            val currentName = if (currentUserProfile.fullName.isNotBlank()) currentUserProfile.fullName else tvProfileName.text.toString()
            if (currentName != "Guest Trader") {
                setText(currentName)
            }
        }

        val labelStreetTv = TextView(this).apply {
            text = "Street Address & House No."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        val etStreet = EditText(this).apply {
            hint = "e.g. Friedrichstraße 100"
            setTextColor(textPrimary)
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pIn = (12 * density).toInt()
            setPadding(pIn, pIn, pIn, pIn)
            textSize = 14f
            setText(currentUserProfile.streetAddress)
        }

        val labelPostalCityTv = TextView(this).apply {
            text = "Postal Code (PLZ) & City"
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        val etPostalCity = EditText(this).apply {
            hint = "e.g. 10117 Berlin"
            setTextColor(textPrimary)
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pIn = (12 * density).toInt()
            setPadding(pIn, pIn, pIn, pIn)
            textSize = 14f
            setText(currentUserProfile.postalCodeCity)
        }

        val labelCountryTv = TextView(this).apply {
            text = "Country"
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        val etCountry = EditText(this).apply {
            hint = "Germany"
            setTextColor(textPrimary)
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pIn = (12 * density).toInt()
            setPadding(pIn, pIn, pIn, pIn)
            textSize = 14f
            setText(if (currentUserProfile.country.isNotBlank()) currentUserProfile.country else "Germany")
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
            }
        }

        val btnCancel = com.google.android.material.button.MaterialButton(this).apply {
            text = "Cancel"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val btnSave = com.google.android.material.button.MaterialButton(this).apply {
            text = "Save Changes"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (8 * density).toInt()
            setOnClickListener {
                val newName = etName.text.toString().trim()
                val newStreet = etStreet.text.toString().trim()
                val newPostalCity = etPostalCity.text.toString().trim()
                val newCountryStr = etCountry.text.toString().trim().ifEmpty { "Germany" }

                if (newName.isEmpty()) {
                    snackString("Please enter a valid display name.")
                    return@setOnClickListener
                }
                isEnabled = false
                text = "Saving..."

                FirebaseSyncManager.updateUserProfileInFirebase(
                    context = this@MainActivity,
                    newFullName = newName,
                    newStreetAddress = newStreet,
                    newPostalCodeCity = newPostalCity,
                    newCountry = newCountryStr,
                    accountId = getUserAccountId(),
                    onSuccess = {
                        currentUserProfile = currentUserProfile.copy(
                            fullName = newName,
                            streetAddress = newStreet,
                            postalCodeCity = newPostalCity,
                            country = newCountryStr
                        )
                        saveUserProfile()
                        updateUserProfileUi()
                        dialog.dismiss()
                        snackString("Profile & tax address updated successfully!")
                    },
                    onFailure = { e ->
                        isEnabled = true
                        text = "Save Changes"
                        snackString("Failed to update profile: ${e.localizedMessage}")
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)

        container.addView(titleTv)
        container.addView(subtitleTv)
        container.addView(labelTv)
        container.addView(etName)
        container.addView(labelStreetTv)
        container.addView(etStreet)
        container.addView(labelPostalCityTv)
        container.addView(etPostalCity)
        container.addView(labelCountryTv)
        container.addView(etCountry)
        container.addView(btnRow)

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun showDeleteAccountConfirmationDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val red = ContextCompat.getColor(this, R.color.brand_red)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(this).apply {
            text = "Permanently Delete Account?"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(red)
        }

        val descTv = TextView(this).apply {
            text = "WARNING: This action will permanently wipe your account profile, trade history, open positions, tax statistics, and cloud backup data from Firebase. This action CANNOT be undone."
            textSize = 13f
            setTextColor(textPrimary)
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val btnCancel = com.google.android.material.button.MaterialButton(this).apply {
            text = "Cancel"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val btnConfirmDelete = com.google.android.material.button.MaterialButton(this).apply {
            text = "Delete Account"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(red)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            cornerRadius = (8 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                executeDeleteAccountWipe()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirmDelete)

        container.addView(titleTv)
        container.addView(descTv)
        container.addView(btnRow)

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun executeDeleteAccountWipe() {
        showProgressDialog("Deleting account & wiping data... Please wait.")
        val currentAccId = getUserAccountId()

        FirebaseSyncManager.deleteUserAccountFromFirebase(
            context = this,
            accountId = currentAccId,
            onSuccess = {
                finishAccountWipeAndReset()
            },
            onFailure = { e ->
                dismissProgressDialog()
                snackString("Failed to delete cloud profile: ${e.localizedMessage}")
                finishAccountWipeAndReset()
            }
        )
    }

    private fun finishAccountWipeAndReset() {
        try {
            // 1. Clear SharedPreferences
            getSharedPreferences("aetherdex_prefs", MODE_PRIVATE).edit().clear().apply()

            // 2. Clear WebView cache
            if (::webViewChart.isInitialized) {
                webViewChart.clearCache(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Reset in-memory states
        val newGuestId = "AD-" + (100000..999999).random()
        currentUserProfile = UserProfile(
            fullName = "",
            email = "",
            password = "",
            accountId = newGuestId,
            isLoggedIn = false
        )
        demoBalance = 0.0
        activePositions.clear()
        closedPositions.clear()
        connectedAddress = null
        connectedWalletName = null

        saveUserProfile()
        saveDemoTradingState()

        // 4. Update UI displays
        onWalletDisconnected()
        updateUserProfileUi()
        updateDemoBalanceUi()
        updatePositionsListUi()
        updateHistoryListUi()

        dismissProgressDialog()

        android.widget.Toast.makeText(
            this@MainActivity,
            "Account and associated data deleted successfully.",
            android.widget.Toast.LENGTH_LONG
        ).show()
        snackString("Account and associated data deleted successfully.")
    }

    private fun showAuthDialog(initialSignUp: Boolean = false) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val paddingPx = (20 * resources.displayMetrics.density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        var isSignUpMode = initialSignUp

        // Header with Title & Close Button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = if (isSignUpMode) "Create Account" else "Sign In to AetherDex"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnClose = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(R.drawable.bg_chip_unselected)
            val p = (6 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            val s = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { dialog.dismiss() }
        }

        headerRow.addView(tvTitle)
        headerRow.addView(btnClose)

        val tvSubtitle = TextView(this).apply {
            text = if (isSignUpMode) "Register for official tax reports & Web3 sync" else "Access your trading account & saved history"
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            textSize = 12f
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt())
        }

        // 2. Mode Toggle (Sign In vs Create Account)
        val modeToggleGroup = MaterialButtonToggleGroup(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * resources.displayMetrics.density).toInt()
            )
            isSingleSelection = true
            isSelectionRequired = true
        }

        val btnToggleSignIn = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Sign In"
            isAllCaps = false
            textSize = 13f
            cornerRadius = (10 * resources.displayMetrics.density).toInt()
        }

        val btnToggleSignUp = MaterialButton(this).apply {
            id = View.generateViewId()
            text = "Create Account"
            isAllCaps = false
            textSize = 13f
            cornerRadius = (10 * resources.displayMetrics.density).toInt()
        }

        modeToggleGroup.addView(btnToggleSignIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        modeToggleGroup.addView(btnToggleSignUp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // 3. Form Input Fields
        val layoutInputs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (14 * resources.displayMetrics.density).toInt()
            }
        }

        // Full Name Field (Sign Up only)
        val tvFullNameLabel = TextView(this).apply {
            text = "Full Name (for Official Tax Reports)"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
        }

        val etFullName = EditText(this).apply {
            hint = "e.g. Satoshi Nakamoto"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        // Street Address Field (Sign Up only)
        val tvStreetLabel = TextView(this).apply {
            text = "Street Address & House No. (Optional)"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val etStreetAddress = EditText(this).apply {
            hint = "e.g. Friedrichstraße 100"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        // Postal Code & City Field (Sign Up only)
        val tvPostalCityLabel = TextView(this).apply {
            text = "Postal Code (PLZ) & City"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val etPostalCodeCity = EditText(this).apply {
            hint = "e.g. 10117 Berlin"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        // Country Field (Sign Up only)
        val tvCountryLabel = TextView(this).apply {
            text = "Country"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val etCountry = EditText(this).apply {
            hint = "Germany"
            setText("Germany")
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        // Email Address Field
        val tvEmailLabel = TextView(this).apply {
            text = "Email Address"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val etEmail = EditText(this).apply {
            hint = "name@example.com"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 14f
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        // Password Field
        val tvPasswordLabel = TextView(this).apply {
            text = "Password"
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val etPassword = EditText(this).apply {
            hint = "••••••••"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        // 4. "Connect Wallet" Placeholder / Link Button
        val btnDialogConnectWallet = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (42 * resources.displayMetrics.density).toInt()
            ).apply {
                topMargin = (14 * resources.displayMetrics.density).toInt()
            }
            val green = ContextCompat.getColor(context, R.color.brand_green)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.bg_panel)
            )
            strokeColor = android.content.res.ColorStateList.valueOf(green)
            strokeWidth = (1 * resources.displayMetrics.density).toInt()
            setTextColor(green)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_wallet)
            iconTint = android.content.res.ColorStateList.valueOf(green)
            iconPadding = (6 * resources.displayMetrics.density).toInt()
            text = if (connectedAddress != null) "Connected: ${WalletConnectionManager.shorten(connectedAddress!!)}" else "Connect Web3 Wallet"
            isAllCaps = false
            textSize = 12f
            cornerRadius = (10 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                dialog.dismiss()
                showWalletPicker()
            }
        }

        // 5. Submit Button
        val btnSubmit = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * resources.displayMetrics.density).toInt()
            ).apply {
                topMargin = (16 * resources.displayMetrics.density).toInt()
            }
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.brand_green)
            )
            setTextColor(ContextCompat.getColor(context, R.color.black))
            text = if (isSignUpMode) "Create Account" else "Sign In"
            isAllCaps = false
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            cornerRadius = (12 * resources.displayMetrics.density).toInt()
        }

        fun updateModeUi() {
            if (isSignUpMode) {
                modeToggleGroup.check(btnToggleSignUp.id)
                tvTitle.text = "Create Account"
                tvSubtitle.text = "Register for official tax reports & Web3 sync"
                tvFullNameLabel.visibility = View.VISIBLE
                etFullName.visibility = View.VISIBLE
                tvStreetLabel.visibility = View.VISIBLE
                etStreetAddress.visibility = View.VISIBLE
                tvPostalCityLabel.visibility = View.VISIBLE
                etPostalCodeCity.visibility = View.VISIBLE
                tvCountryLabel.visibility = View.VISIBLE
                etCountry.visibility = View.VISIBLE
                btnSubmit.text = "Create Account"
                btnToggleSignUp.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_green))
                btnToggleSignUp.setTextColor(ContextCompat.getColor(this, R.color.black))
                btnToggleSignIn.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_elevated))
                btnToggleSignIn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            } else {
                modeToggleGroup.check(btnToggleSignIn.id)
                tvTitle.text = "Sign In to AetherDex"
                tvSubtitle.text = "Access your trading account & saved history"
                tvFullNameLabel.visibility = View.GONE
                etFullName.visibility = View.GONE
                tvStreetLabel.visibility = View.GONE
                etStreetAddress.visibility = View.GONE
                tvPostalCityLabel.visibility = View.GONE
                etPostalCodeCity.visibility = View.GONE
                tvCountryLabel.visibility = View.GONE
                etCountry.visibility = View.GONE
                btnSubmit.text = "Sign In"
                btnToggleSignIn.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_green))
                btnToggleSignIn.setTextColor(ContextCompat.getColor(this, R.color.black))
                btnToggleSignUp.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_elevated))
                btnToggleSignUp.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }

        btnToggleSignIn.setOnClickListener {
            isSignUpMode = false
            updateModeUi()
        }

        btnToggleSignUp.setOnClickListener {
            isSignUpMode = true
            updateModeUi()
        }

        if (currentUserProfile.email.isNotBlank()) {
            etEmail.setText(currentUserProfile.email)
        }
        if (currentUserProfile.fullName.isNotBlank()) {
            etFullName.setText(currentUserProfile.fullName)
        }
        if (currentUserProfile.streetAddress.isNotBlank()) {
            etStreetAddress.setText(currentUserProfile.streetAddress)
        }
        if (currentUserProfile.postalCodeCity.isNotBlank()) {
            etPostalCodeCity.setText(currentUserProfile.postalCodeCity)
        }
        if (currentUserProfile.country.isNotBlank()) {
            etCountry.setText(currentUserProfile.country)
        }

        btnSubmit.setOnClickListener {
            val emailStr = etEmail.text.toString().trim()
            val passwordStr = etPassword.text.toString().trim()
            val fullNameStr = etFullName.text.toString().trim()
            val streetStr = etStreetAddress.text.toString().trim()
            val postalCityStr = etPostalCodeCity.text.toString().trim()
            val countryStr = etCountry.text.toString().trim().ifEmpty { "Germany" }

            if (emailStr.isEmpty() || !emailStr.contains("@")) {
                snackString("Please enter a valid email address.")
                return@setOnClickListener
            }
            if (passwordStr.length < 6) {
                snackString("Password must be at least 6 characters.")
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.text = if (isSignUpMode) "Creating Account..." else "Signing In..."

            if (isSignUpMode) {
                if (fullNameStr.isEmpty()) {
                    snackString("Please enter your Full Name for Tax Compliance.")
                    btnSubmit.isEnabled = true
                    btnSubmit.text = "Create Account"
                    return@setOnClickListener
                }
                val generatedAccId = "AD-" + (100000..999999).random()

                FirebaseSyncManager.signUpWithEmail(
                    email = emailStr,
                    password = passwordStr,
                    onSuccess = { uid ->
                        btnSubmit.isEnabled = true
                        currentUserProfile = UserProfile(
                            fullName = fullNameStr,
                            email = emailStr,
                            password = passwordStr,
                            accountId = generatedAccId,
                            isLoggedIn = true,
                            streetAddress = streetStr,
                            postalCodeCity = postalCityStr,
                            country = countryStr
                        )
                        saveUserProfile()
                        updateUserProfileUi()
                        dialog.dismiss()
                        snackString("Account created successfully! Welcome, $fullNameStr (Account ID: $generatedAccId)")
                    },
                    onFailure = { exception ->
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "Create Account"
                        val err = exception.localizedMessage ?: "Registration failed."
                        snackString("Auth Error: $err")
                    }
                )
            } else {
                FirebaseSyncManager.signInWithEmail(
                    email = emailStr,
                    password = passwordStr,
                    onSuccess = { uid ->
                        btnSubmit.isEnabled = true
                        val fallbackAccId = currentUserProfile.accountId.ifEmpty { "AD-" + (100000..999999).random() }

                        FirebaseSyncManager.fetchUserDataFromFirestore(
                            context = this@MainActivity,
                            userId = uid,
                            accountId = fallbackAccId,
                            onSuccess = { restored ->
                                dialog.dismiss()
                                restored.userProfile?.let { prof ->
                                    currentUserProfile = prof.copy(isLoggedIn = true)
                                } ?: run {
                                    val fallbackName = if (fullNameStr.isNotBlank()) fullNameStr else emailStr.substringBefore("@")
                                    currentUserProfile = UserProfile(
                                        fullName = fallbackName,
                                        email = emailStr,
                                        password = passwordStr,
                                        accountId = fallbackAccId,
                                        isLoggedIn = true,
                                        streetAddress = streetStr,
                                        postalCodeCity = postalCityStr,
                                        country = countryStr
                                    )
                                }

                                restored.demoBalance?.let { demoBalance = it }

                                restored.activePositions?.let { posList ->
                                    activePositions.clear()
                                    activePositions.addAll(posList)
                                }

                                restored.closedPositions?.let { closedList ->
                                    closedPositions.clear()
                                    closedPositions.addAll(closedList)
                                }

                                restored.connectedWalletAddress?.let { wAddr ->
                                    connectedAddress = wAddr
                                    btnConnectWallet.text = WalletConnectionManager.shorten(wAddr)
                                }

                                restored.tradingviewState?.let { tvState ->
                                    if (tvState.isNotBlank()) {
                                        injectTradingViewState(tvState)
                                    }
                                }

                                saveUserProfile()
                                saveDemoTradingState()
                                updateUserProfileUi()
                                updateDemoBalanceUi()
                                updatePositionsListUi()
                                updateHistoryListUi()

                                val welcomeName = if (currentUserProfile.fullName.isNotBlank()) currentUserProfile.fullName else emailStr.substringBefore("@")
                                snackString("Welcome back, $welcomeName!")
                            },
                            onFailure = { fetchErr ->
                                dialog.dismiss()
                                currentUserProfile = currentUserProfile.copy(
                                    email = emailStr,
                                    isLoggedIn = true
                                )
                                saveUserProfile()
                                updateUserProfileUi()
                                snackString("Signed in, but cloud sync warning: ${fetchErr.localizedMessage}")
                            }
                        )
                    },
                    onFailure = { exception ->
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "Sign In"
                        val err = exception.localizedMessage ?: "Invalid email or password."
                        snackString("Auth Error: $err")
                    }
                )
            }
        }

        container.addView(headerRow)
        container.addView(tvSubtitle)
        container.addView(modeToggleGroup)

        layoutInputs.addView(tvFullNameLabel)
        layoutInputs.addView(etFullName)
        layoutInputs.addView(tvStreetLabel)
        layoutInputs.addView(etStreetAddress)
        layoutInputs.addView(tvPostalCityLabel)
        layoutInputs.addView(etPostalCodeCity)
        layoutInputs.addView(tvCountryLabel)
        layoutInputs.addView(etCountry)
        layoutInputs.addView(tvEmailLabel)
        layoutInputs.addView(etEmail)
        layoutInputs.addView(tvPasswordLabel)
        layoutInputs.addView(etPassword)

        container.addView(layoutInputs)
        container.addView(btnDialogConnectWallet)
        container.addView(btnSubmit)

        updateModeUi()

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun getUserAccountId(): String {
        if (currentUserProfile.accountId.isNotBlank()) {
            return currentUserProfile.accountId
        }
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        var id = prefs.getString("user_account_id", null)
        if (id == null) {
            id = "AD-" + (100000..999999).random()
            prefs.edit().putString("user_account_id", id).apply()
        }
        currentUserProfile = currentUserProfile.copy(accountId = id)
        return id
    }

    private fun formatCurrency(usdAmount: Double, targetCurrency: String = currentUserProfile.baseCurrency): String {
        val rate = when (targetCurrency) {
            "EUR" -> 0.92
            "GBP" -> 0.79
            else -> 1.0
        }
        val symbol = when (targetCurrency) {
            "EUR" -> "€"
            "GBP" -> "£"
            else -> "$"
        }
        val converted = usdAmount * rate
        val sign = if (converted < 0) "-" else ""
        val absVal = Math.abs(converted)
        return String.format(java.util.Locale.US, "%s%s%.2f %s", sign, symbol, absVal, targetCurrency)
    }

    private fun formatDualCurrency(usdAmount: Double): String {
        val baseStr = formatCurrency(usdAmount)
        val eurAmount = usdAmount * 0.92
        val usdSign = if (usdAmount < 0) "-" else ""
        val eurSign = if (eurAmount < 0) "-" else ""
        val absUsd = Math.abs(usdAmount)
        val absEur = Math.abs(eurAmount)
        return if (currentUserProfile.baseCurrency == "USD") {
            String.format(java.util.Locale.US, "%s$%.2f USD (%s€%.2f EUR)", usdSign, absUsd, eurSign, absEur)
        } else {
            baseStr
        }
    }

    private fun checkOnboardingGateway() {
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        val isCompleted = prefs.getBoolean("onboarding_completed", false)
        if (!isCompleted && !currentUserProfile.isLoggedIn) {
            showOnboardingGatewayDialog()
        }
    }

    private fun showOnboardingGatewayDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (24 * density).toInt()
            setPadding(p, p, p, p)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val logoIv = ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            val size = (52 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = (12 * density).toInt()
            }
        }

        val titleTv = TextView(this).apply {
            text = "Welcome to AetherDex"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
            gravity = android.view.Gravity.CENTER
        }

        val subtitleTv = TextView(this).apply {
            text = "Institutional Futures Trading & Real-time Tax Engine. Create an account to sync your profile across devices."
            textSize = 13f
            setTextColor(textSecondary)
            gravity = android.view.Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, (20 * density).toInt())
        }

        val btnCreateAccount = MaterialButton(this).apply {
            text = "Create Account"
            isAllCaps = false
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(greenColor)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (12 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                showAuthDialog(initialSignUp = true)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            ).apply { bottomMargin = (10 * density).toInt() }
        }

        val btnSignIn = MaterialButton(this).apply {
            text = "Sign In"
            isAllCaps = false
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textPrimary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (12 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                showAuthDialog(initialSignUp = false)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            ).apply { bottomMargin = (16 * density).toInt() }
        }

        val btnGuest = TextView(this).apply {
            text = "Continue as Guest"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(greenColor)
            gravity = android.view.Gravity.CENTER
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("onboarding_completed", true).apply()
                val guestId = getUserAccountId()
                dialog.dismiss()
                snackString("Operating in Guest Mode (Account ID: $guestId). You can sign up anytime in Settings.")
            }
        }

        container.addView(logoIv)
        container.addView(titleTv)
        container.addView(subtitleTv)
        container.addView(btnCreateAccount)
        container.addView(btnSignIn)
        container.addView(btnGuest)

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun requireAuthForFeature(featureTitle: String, onAuthenticatedAction: () -> Unit) {
        if (currentUserProfile.isLoggedIn) {
            onAuthenticatedAction()
        } else {
            showProtectedFeatureAuthDialog(featureTitle)
        }
    }

    private fun showProtectedFeatureAuthDialog(featureTitle: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(this).apply {
            text = "Account Required"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(greenColor)
        }

        val descTv = TextView(this).apply {
            text = "$featureTitle requires an AetherDex account. Sign in or Create an account to export official tax reports and cloud backups linked to a permanent Account ID."
            textSize = 13f
            setTextColor(textPrimary)
            setPadding(0, (8 * density).toInt(), 0, (18 * density).toInt())
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val btnCancel = MaterialButton(this).apply {
            text = "Cancel"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val btnSignInUp = MaterialButton(this).apply {
            text = "Sign In / Sign Up"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(greenColor)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            setTypeface(null, android.graphics.Typeface.BOLD)
            cornerRadius = (8 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                showAuthDialog(false)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSignInUp)

        container.addView(titleTv)
        container.addView(descTv)
        container.addView(btnRow)

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }



    private fun exportTaxPdf() {
        requireAuthForFeature("Official Tax PDF Export") {
            executeExportTaxPdf()
        }
    }

    private fun executeExportTaxPdf() {
        val list = getFilteredClosedPositions()
        if (list.isEmpty()) {
            snackString("No closed trades found for the selected date filter.")
            return
        }

        try {
            val userAccountId = getUserAccountId()
            val taxpayerName = if (currentUserProfile.isLoggedIn && currentUserProfile.fullName.isNotBlank()) currentUserProfile.fullName else "Guest Trader"
            val taxpayerEmail = if (currentUserProfile.isLoggedIn && currentUserProfile.email.isNotBlank()) currentUserProfile.email else "N/A"
            val fullAddressStr = buildString {
                if (currentUserProfile.streetAddress.isNotBlank()) append(currentUserProfile.streetAddress)
                if (currentUserProfile.postalCodeCity.isNotBlank()) {
                    if (isNotEmpty()) append(", ")
                    append(currentUserProfile.postalCodeCity)
                }
                if (currentUserProfile.country.isNotBlank()) {
                    if (isNotEmpty()) append(", ")
                    append(currentUserProfile.country)
                }
            }.ifEmpty { "Address Not Provided" }
            val reportTimestamp = System.currentTimeMillis()
            val reportId = "AETHER-$reportTimestamp"

            val pdfDocument = android.graphics.pdf.PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val paint = android.graphics.Paint()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            val nowStr = dateFormat.format(java.util.Date(reportTimestamp))

            var y = 40f

            // 1. Header Title & Metadata Bar
            paint.color = Color.parseColor("#0B0E14")
            paint.textSize = 18f
            paint.isFakeBoldText = true
            canvas.drawText("AETHERDEX FUTURES TAX REPORT", 40f, y, paint)

            y += 18f
            paint.textSize = 9.5f
            paint.isFakeBoldText = true
            paint.color = Color.parseColor("#00C853")
            canvas.drawText("Taxpayer Email: $taxpayerEmail  |  Account ID: $userAccountId  |  Report ID: $reportId", 40f, y, paint)

            y += 15f
            paint.textSize = 9.5f
            paint.isFakeBoldText = true
            paint.color = Color.parseColor("#111827")
            canvas.drawText("Taxpayer: $taxpayerName  |  Address: $fullAddressStr", 40f, y, paint)

            y += 15f
            paint.textSize = 9f
            paint.isFakeBoldText = false
            paint.color = Color.parseColor("#666666")
            canvas.drawText("Generated: $nowStr  |  FIFO & IRS Form 8949 / German Finanzamt Compliant", 40f, y, paint)

            val baseCurr = currentUserProfile.baseCurrency
            val fxRate = when (baseCurr) {
                "EUR" -> 0.92
                "GBP" -> 0.79
                else -> 1.0
            }

            // 2. Summary Box
            y += 20f
            val summaryHeight = 75f
            val summaryRect = android.graphics.RectF(40f, y, (pageWidth - 40).toFloat(), y + summaryHeight)
            paint.color = Color.parseColor("#F4F5F7")
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRoundRect(summaryRect, 8f, 8f, paint)

            val totalTrades = list.size
            val totalPnlUsd = list.sumOf { it.realizedPnl }
            val totalFeesUsd = list.sumOf { it.positionSizeUsdc * 0.0005 * 2.0 }
            val netCapitalGainsUsd = totalPnlUsd - totalFeesUsd

            paint.color = Color.parseColor("#111827")
            paint.textSize = 10f
            paint.isFakeBoldText = true
            canvas.drawText("TAX SUMMARY STATS (BASE CURRENCY: $baseCurr  ·  FX: 1 USD = $fxRate $baseCurr)", 55f, y + 20f, paint)

            paint.isFakeBoldText = false
            paint.textSize = 9f
            canvas.drawText("Closed Trades: $totalTrades", 55f, y + 40f, paint)

            val pnlFormatted = formatCurrency(totalPnlUsd)
            val feesFormatted = formatCurrency(totalFeesUsd)
            val netFormatted = formatCurrency(netCapitalGainsUsd)

            canvas.drawText("Gross Realized PnL: $pnlFormatted", 160f, y + 40f, paint)
            canvas.drawText("Total Fees Paid: $feesFormatted", 55f, y + 58f, paint)
            canvas.drawText("Net Capital Gains: $netFormatted", 310f, y + 58f, paint)

            // 3. Transactions Table Header
            y += summaryHeight + 25f
            paint.color = Color.parseColor("#161B22")
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(40f, y, (pageWidth - 40).toFloat(), y + 22f, paint)

            paint.color = Color.WHITE
            paint.textSize = 8.5f
            paint.isFakeBoldText = true
            canvas.drawText("Date", 45f, y + 15f, paint)
            canvas.drawText("Symbol", 125f, y + 15f, paint)
            canvas.drawText("Side", 195f, y + 15f, paint)
            canvas.drawText("Entry Price", 240f, y + 15f, paint)
            canvas.drawText("Exit Price", 315f, y + 15f, paint)
            canvas.drawText("Margin", 385f, y + 15f, paint)
            canvas.drawText("Net PnL ($baseCurr)", 445f, y + 15f, paint)

            // 4. Transactions Table Rows
            y += 24f
            paint.isFakeBoldText = false
            paint.textSize = 8f

            for (pos in list.take(28)) {
                val dateStr = dateFormat.format(java.util.Date(pos.timestamp))
                val sideStr = if (pos.isLong) "LONG" else "SHORT"
                val takerFee = pos.positionSizeUsdc * 0.0005 * 2.0
                val netPnlUsd = pos.realizedPnl - takerFee
                val netPnlFormatted = formatCurrency(netPnlUsd)

                paint.color = Color.parseColor("#333333")
                canvas.drawText(dateStr, 45f, y + 12f, paint)
                canvas.drawText(pos.symbol, 125f, y + 12f, paint)
                canvas.drawText(sideStr, 195f, y + 12f, paint)
                canvas.drawText(String.format(java.util.Locale.US, "$%.2f", pos.entryPrice), 240f, y + 12f, paint)
                canvas.drawText(String.format(java.util.Locale.US, "$%.2f", pos.exitPrice), 315f, y + 12f, paint)
                canvas.drawText(String.format(java.util.Locale.US, "$%.2f", pos.margin), 385f, y + 12f, paint)

                paint.color = if (netPnlUsd >= 0) Color.parseColor("#00C853") else Color.parseColor("#FF5252")
                paint.isFakeBoldText = true
                canvas.drawText(netPnlFormatted, 445f, y + 12f, paint)
                paint.isFakeBoldText = false

                y += 18f

                // Row divider line
                paint.color = Color.parseColor("#E5E7EB")
                canvas.drawLine(40f, y, (pageWidth - 40).toFloat(), y, paint)
            }

            // 5. Compliance Footer
            val footerY = pageHeight - 30f
            paint.color = Color.parseColor("#9CA3AF")
            paint.textSize = 8f
            paint.isFakeBoldText = false
            canvas.drawText(
                "Generated on $nowStr  |  Accounting Standard: FIFO  |  AetherDex Compliance Engine",
                40f,
                footerY,
                paint
            )

            pdfDocument.finishPage(page)

            val fileName = "AetherDex_TaxReport_${reportTimestamp}.pdf"
            val baos = java.io.ByteArrayOutputStream()
            pdfDocument.writeTo(baos)
            pdfDocument.close()

            val success = saveFileToPublicDownloads(fileName, "application/pdf", baos.toByteArray())
            if (success) {
                snackString("File saved to Downloads: $fileName")
            } else {
                snackString("Failed to save PDF to Downloads")
            }
        } catch (e: Exception) {
            snackString("PDF Generation error: ${e.localizedMessage}")
        }
    }

    private fun selectNav(index: Int) {
        val activeGreen = ContextCompat.getColor(this, R.color.brand_green)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)
        navIcons.forEachIndexed { i, icon ->
            icon.imageTintList =
                android.content.res.ColorStateList.valueOf(if (i == index) activeGreen else inactive)
        }
        navLabels.forEachIndexed { i, label ->
            label.setTextColor(if (i == index) activeGreen else inactive)
            label.setTypeface(
                label.typeface,
                if (i == index) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }

        if (::scrollContent.isInitialized && ::chartSection.isInitialized && ::botChartSection.isInitialized && ::walletSection.isInitialized && ::settingsSection.isInitialized && ::historySection.isInitialized && ::taxSection.isInitialized) {
            val isWalletTab = (index == 3)
            if (::swipeRefreshWallet.isInitialized) {
                swipeRefreshWallet.visibility = if (isWalletTab) View.VISIBLE else View.GONE
            }
            when (index) {
                0 -> { // Trade
                    scrollContent.visibility = View.VISIBLE
                    chartSection.visibility = View.GONE
                    botChartSection.visibility = View.GONE
                    walletSection.visibility = View.GONE
                    settingsSection.visibility = View.GONE
                    historySection.visibility = View.GONE
                    taxSection.visibility = View.GONE
                    scrollContent.smoothScrollTo(0, 0)
                }
                1 -> { // Chart (Dedicated Full-Screen Chart Screen)
                    scrollContent.visibility = View.GONE
                    chartSection.visibility = View.VISIBLE
                    botChartSection.visibility = View.GONE
                    walletSection.visibility = View.GONE
                    settingsSection.visibility = View.GONE
                    historySection.visibility = View.GONE
                    taxSection.visibility = View.GONE
                }
                2 -> { // Bot Strategy Chart
                    scrollContent.visibility = View.GONE
                    chartSection.visibility = View.GONE
                    botChartSection.visibility = View.VISIBLE
                    walletSection.visibility = View.GONE
                    settingsSection.visibility = View.GONE
                    historySection.visibility = View.GONE
                    taxSection.visibility = View.GONE
                    ensureBotChartFragmentLoaded()
                }
                3 -> { // Wallet
                    scrollContent.visibility = View.GONE
                    chartSection.visibility = View.GONE
                    botChartSection.visibility = View.GONE
                    walletSection.visibility = View.VISIBLE
                    settingsSection.visibility = View.GONE
                    historySection.visibility = View.GONE
                    taxSection.visibility = View.GONE
                    updateEmbeddedVaultUi()
                }
                4 -> { // Positions
                    scrollContent.visibility = View.VISIBLE
                    chartSection.visibility = View.GONE
                    botChartSection.visibility = View.GONE
                    walletSection.visibility = View.GONE
                    settingsSection.visibility = View.GONE
                    historySection.visibility = View.GONE
                    taxSection.visibility = View.GONE
                    scrollContent.post {
                        val positionsCard = findViewById<View>(R.id.positionsListContainer)?.parent as? View
                        if (positionsCard != null) {
                            scrollContent.smoothScrollTo(0, positionsCard.top)
                        }
                    }
                }
                5 -> { // History
                    scrollContent.visibility = View.GONE
                    chartSection.visibility = View.GONE
                    botChartSection.visibility = View.GONE
                    walletSection.visibility = View.GONE
                    settingsSection.visibility = View.GONE
                    historySection.visibility = View.VISIBLE
                    taxSection.visibility = View.GONE
                    updateHistoryListUi()
                }
                6 -> { // Tax
                    scrollContent.visibility = View.GONE
                    chartSection.visibility = View.GONE
                    botChartSection.visibility = View.GONE
                    walletSection.visibility = View.GONE
                    settingsSection.visibility = View.GONE
                    historySection.visibility = View.GONE
                    taxSection.visibility = View.VISIBLE
                    restoreTaxFilterState()
                }
                7 -> { // Settings
                    scrollContent.visibility = View.GONE
                    chartSection.visibility = View.GONE
                    botChartSection.visibility = View.GONE
                    walletSection.visibility = View.GONE
                    settingsSection.visibility = View.VISIBLE
                    historySection.visibility = View.GONE
                    taxSection.visibility = View.GONE
                }
            }
        }
    }

    private fun ensureBotChartFragmentLoaded() {
        val existing = supportFragmentManager.findFragmentById(R.id.botChartSection)
        if (existing == null) {
            val fragment = BotStrategyChartFragment()
            fragment.onFullscreenToggleListener = { isFullscreen ->
                val bottomNav = findViewById<View>(R.id.bottomNav)
                bottomNav?.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.botChartSection, fragment)
                .commitAllowingStateLoss()
        }
    }

    // -------------------------------------------------------------------- //
    // Export / Import Backup Logic                                         //
    // -------------------------------------------------------------------- //

    private var progressDialog: Dialog? = null

    private fun showProgressDialog(message: String) {
        dismissProgressDialog()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val paddingPx = (20 * density).toInt()
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        }

        val progressBar = android.widget.ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.brand_green)
            )
            layoutParams = LinearLayout.LayoutParams(
                (36 * density).toInt(),
                (36 * density).toInt()
            ).apply {
                marginEnd = (16 * density).toInt()
            }
        }

        val tvMsg = TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        container.addView(progressBar)
        container.addView(tvMsg)

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (32 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
        progressDialog = dialog
    }

    private fun dismissProgressDialog() {
        try {
            progressDialog?.dismiss()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        progressDialog = null
    }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                exportAppDataToZip(uri)
            }
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importAppDataFromZip(uri)
            }
        }

    private fun startExportBackup() {
        requireAuthForFeature("Cloud Backup Export") {
            val fileName = "AetherDex_Backup_${System.currentTimeMillis()}.zip"
            exportBackupLauncher.launch(fileName)
        }
    }

    private fun startImportRestore() {
        importBackupLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    }

    private fun reloadPreferencesFromXmlFile() {
        try {
            val prefsFile = File(applicationInfo.dataDir, "shared_prefs/aetherdex_prefs.xml")
            if (!prefsFile.exists()) return

            val xmlContent = prefsFile.readText()
            val map = parseSimpleXmlMap(xmlContent)
            val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
            val editor = prefs.edit()

            map.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                }
            }
            editor.putString("active_positions_json", "[]")
            editor.commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseSimpleXmlMap(xml: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(xml))

            var eventType = parser.eventType
            var currentTag = ""
            var currentKey: String? = null

            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        currentKey = parser.getAttributeValue(null, "name")
                    }
                    org.xmlpull.v1.XmlPullParser.TEXT -> {
                        val text = parser.text ?: ""
                        currentKey?.let { key ->
                            when (currentTag) {
                                "string" -> result[key] = text
                                "boolean" -> result[key] = text.toBoolean()
                                "float" -> result[key] = text.toFloatOrNull() ?: 0f
                                "long" -> result[key] = text.toLongOrNull() ?: 0L
                                "int" -> result[key] = text.toIntOrNull() ?: 0
                            }
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        currentTag = ""
                        currentKey = null
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun exportAppDataToZip(destinationUri: android.net.Uri) {
        showProgressDialog("Exporting backup... Please wait.")
        try {
            saveDemoTradingState()
            saveUserProfile()
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        val savedActivePositionsJson = prefs.getString("active_positions_json", "[]")

        // Temporarily set active_positions_json to "[]" in SharedPreferences before zipping
        prefs.edit().putString("active_positions_json", "[]").commit()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val dataDir = File(applicationInfo.dataDir)
                        val filesToBackup = listOf(
                            File(dataDir, "shared_prefs"),
                            File(dataDir, "app_webview"),
                            File(dataDir, "databases"),
                            filesDir
                        )
                        for (sourceFile in filesToBackup) {
                            if (sourceFile.exists()) {
                                zipFileOrDirectory(sourceFile, sourceFile.name, zipOut)
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    // Restore current session's active positions in SharedPreferences
                    prefs.edit().putString("active_positions_json", savedActivePositionsJson).apply()

                    dismissProgressDialog()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Backup exported successfully to Downloads",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    snackString("Backup exported successfully to Downloads")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Restore current session's active positions in SharedPreferences even on error
                    prefs.edit().putString("active_positions_json", savedActivePositionsJson).apply()

                    dismissProgressDialog()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Export failed: ${e.localizedMessage}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    snackString("Export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun zipFileOrDirectory(file: File, parentPath: String, zipOut: ZipOutputStream) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return
            for (child in children) {
                zipFileOrDirectory(child, "$parentPath/${child.name}", zipOut)
            }
        } else {
            FileInputStream(file).use { fis ->
                val zipEntry = ZipEntry(parentPath)
                zipOut.putNextEntry(zipEntry)
                fis.copyTo(zipOut)
                zipOut.closeEntry()
            }
        }
    }

    private fun importAppDataFromZip(sourceUri: android.net.Uri) {
        showProgressDialog("Restoring data... Please wait.")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zipIn ->
                        val dataDir = File(applicationInfo.dataDir)
                        var entry = zipIn.nextEntry
                        val buffer = ByteArray(8192)
                        while (entry != null) {
                            val outFile = File(dataDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    var len: Int
                                    while (zipIn.read(buffer).also { len = it } > 0) {
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    dismissProgressDialog()

                    // 1. Reload SharedPreferences from disk XML into active SharedPreferences instance
                    reloadPreferencesFromXmlFile()

                    // 2. Re-read all local persistence state into memory
                    restoreUserProfile()
                    restoreDemoTradingState()

                    // Force active positions state in memory to empty
                    activePositions.clear()

                    restoreTaxFilterState()
                    restorePersistedWalletUi()

                    // 3. Refresh UI views
                    updateUserProfileUi()
                    updateDemoBalanceUi()
                    updatePositionsListUi()
                    updateHistoryListUi()

                    // 4. Flush & reload TradingView WebView
                    try {
                        android.webkit.CookieManager.getInstance().flush()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (::webViewChart.isInitialized) {
                        val fullChartUrl = getTradingViewUrlForSymbol(currentRawSymbol)
                        webViewChart.loadUrl(fullChartUrl)
                    }

                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Data restored successfully! Restarting view...",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    snackString("Data restored successfully! Restarting view...")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissProgressDialog()
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Import failed: ${e.localizedMessage}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    snackString("Import failed: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun snack(resId: Int) {
        Snackbar.make(findViewById(R.id.main), resId, Snackbar.LENGTH_SHORT).show()
    }

    private fun snackString(msg: String) {
        Snackbar.make(findViewById(R.id.main), msg, Snackbar.LENGTH_LONG).show()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateUiForOrientation(newConfig.orientation)
    }

    private fun updateUiForOrientation(orientation: Int) {
        val isLandscape = (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        val bottomNav = findViewById<View>(R.id.bottomNav)
        val headerBar = findViewById<View>(R.id.headerBar)

        if (isLandscape) {
            bottomNav?.visibility = View.GONE
            headerBar?.visibility = View.GONE
            if (::btnFullscreenChart.isInitialized) {
                btnFullscreenChart.setImageResource(R.drawable.ic_fullscreen_exit)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.let { ic ->
                    ic.hide(
                        android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                    )
                    ic.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } else {
            bottomNav?.visibility = View.VISIBLE
            headerBar?.visibility = View.VISIBLE
            if (::btnFullscreenChart.isInitialized) {
                btnFullscreenChart.setImageResource(R.drawable.ic_fullscreen)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onBackPressed() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE || isFullscreenMode) {
            if (isFullscreenMode) {
                exitFullscreenChart()
            } else {
                toggleChartOrientation(forcePortrait = true)
            }
        } else {
            super.onBackPressed()
        }
    }

    private fun toggleFullscreenChart() {
        toggleChartOrientation()
    }

    private fun toggleChartOrientation(forcePortrait: Boolean = false) {
        val currentOrientation = resources.configuration.orientation
        if (forcePortrait || currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            updateUiForOrientation(android.content.res.Configuration.ORIENTATION_PORTRAIT)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            updateUiForOrientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        }
    }

    private fun enterFullscreenChart(externalView: View? = null) {
        if (isFullscreenMode) return
        isFullscreenMode = true

        // Lock screen orientation to landscape
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Hide system status bar and navigation bar on main window
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { ic ->
                ic.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                ic.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        val targetView = externalView ?: webViewChart
        (targetView.parent as? ViewGroup)?.removeView(targetView)

        fullscreenChartContainer.removeAllViews()
        fullscreenChartContainer.addView(
            targetView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val btnExit = ImageButton(this).apply {
            val size = (36 * resources.displayMetrics.density).toInt()
            val pad  = (6  * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
            setBackgroundResource(R.drawable.bg_chip_unselected)
            setImageResource(R.drawable.ic_fullscreen_exit)
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.cd_fullscreen_toggle)
        }

        fullscreenChartContainer.addView(btnExit)
        makeViewDraggableAndClickable(btnExit, fullscreenChartContainer) { exitFullscreenChart() }
        fullscreenChartContainer.visibility = View.VISIBLE
    }

    private fun exitFullscreenChart() {
        if (!isFullscreenMode) return
        isFullscreenMode = false

        // Restore system bars on main window
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Clean up WebChromeClient customView if active
        val cb = customViewCallback
        customViewCallback = null
        try {
            cb?.onCustomViewHidden()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val cv = customView
        customView = null
        if (cv != null) {
            (cv.parent as? ViewGroup)?.removeView(cv)
        }

        fullscreenChartContainer.visibility = View.GONE
        fullscreenChartContainer.removeAllViews()

        // Restore webViewChart to inline container
        (webViewChart.parent as? ViewGroup)?.removeView(webViewChart)
        if (::chartContainer.isInitialized) {
            chartContainer.addView(
                webViewChart,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // Re-apply saved draggable position to inline button
            makeViewDraggableAndClickable(btnFullscreenChart, chartContainer) { toggleFullscreenChart() }
        }
    }

    // -------------------------------------------------------------------- //
    // Demo Trading Logic & Positions State Management                       //
    // -------------------------------------------------------------------- //

    private fun extractSymbolFromUrlOrTitle(url: String?, title: String?): String? {
        if (!url.isNullOrEmpty()) {
            try {
                val uri = android.net.Uri.parse(url)
                val param = uri.getQueryParameter("symbol")
                if (!param.isNullOrEmpty()) {
                    val decoded = java.net.URLDecoder.decode(param, "UTF-8")
                    val sym = if (decoded.contains(":")) decoded.substringAfter(":") else decoded
                    if (sym.isNotBlank()) {
                        return cleanSymbolString(sym)
                    }
                }
            } catch (e: Exception) {
                // Ignore parse exceptions
            }
        }
        if (!title.isNullOrEmpty()) {
            val firstWord = title.trim().split(" ").firstOrNull()?.uppercase()
            if (firstWord != null && firstWord.length >= 4 && (firstWord.endsWith("USDC") || firstWord.endsWith("USDC") || firstWord.endsWith("BTC") || firstWord.endsWith("PERP"))) {
                return cleanSymbolString(firstWord)
            }
        }
        return null
    }

    private fun cleanSymbolString(sym: String): String {
        return sym.trim().uppercase().replace(".", "").replace("-", "")
    }

    private fun formatDisplaySymbol(rawSymbol: String): String {
        val upper = rawSymbol.uppercase()
        return when {
            upper.endsWith("USDC") -> "${upper.dropLast(4)}/USDC"
            upper.endsWith("USDT") -> "${upper.dropLast(4)}/USDT"
            upper.endsWith("BUSD") -> "${upper.dropLast(4)}/BUSD"
            upper.endsWith("BTC") && upper.length > 3 -> "${upper.dropLast(3)}/BTC"
            upper.endsWith("PERP") -> upper.replace("PERP", "/PERP")
            else -> upper
        }
    }

    private fun getTradingViewUrlForSymbol(rawSymbol: String): String {
        val clean = HyperliquidOrderManager.cleanSymbolString(rawSymbol)
        val tvSymbol = when (clean) {
            "BTC", "ETH", "SOL", "XRP", "ADA", "DOGE", "BNB", "AVAX", "LINK", "SUI", "APT", "DOT", "LTC", "NEAR", "BCH" -> "BINANCE:${clean}USDT"
            "PAXG" -> "BINANCE:PAXGUSDT"
            "GOLD", "XAU" -> "OANDA:XAUUSD"
            "SILVER", "XAG" -> "OANDA:XAGUSD"
            "NVDA", "TSLA", "AAPL", "GOOGL", "META", "MSFT", "AMZN" -> "NASDAQ:$clean"
            else -> "HYPERLIQUID:$clean"
        }
        return "https://www.tradingview.com/chart/?symbol=$tvSymbol"
    }

    private fun updateLeverageLimitsForSymbol(symbol: String) {
        if (!::sliderLeverage.isInitialized) return

        val cleanSymbol = HyperliquidOrderManager.cleanSymbolString(symbol)
        val assetIndex = HyperliquidOrderManager.getAssetIndex(cleanSymbol)
        val maxLev = HyperliquidOrderManager.getMaxLeverage(assetIndex)

        val maxLevFloat = maxLev.toFloat()

        if (sliderLeverage.valueTo != maxLevFloat) {
            if (sliderLeverage.value > maxLevFloat) {
                sliderLeverage.value = maxLevFloat
            }
            sliderLeverage.valueTo = maxLevFloat
        }

        if (sliderLeverage.value > maxLevFloat) {
            sliderLeverage.value = maxLevFloat
        }

        val currentVal = sliderLeverage.value.toInt()
        if (::tvLeverageValue.isInitialized) {
            tvLeverageValue.text = getString(R.string.leverage_value).replace("10x", "${currentVal}x")
        }

        val chipMap = mapOf(
            1 to chip1x,
            5 to chip5x,
            10 to chip10x,
            25 to chip25x,
            50 to chip50x,
            100 to chip100x
        )

        chipMap.forEach { (chipVal, chip) ->
            if (::chip1x.isInitialized && chip != null) {
                val isAllowed = chipVal <= maxLev
                chip.isEnabled = isAllowed
                chip.alpha = if (isAllowed) 1.0f else 0.35f
            }
        }

        syncChipFromSlider(currentVal)
    }

    private fun setupSymbolDropdown() {
        val assetList = HyperliquidOrderManager.getAllAssetInfos()
        val adapter = AssetDropdownAdapter(this, assetList)
        actvSymbol.setAdapter(adapter)
        actvSymbol.setText(currentDisplaySymbol, false)

        actvSymbol.setOnClickListener {
            actvSymbol.showDropDown()
        }

        updateLeverageLimitsForSymbol(currentRawSymbol)

        actvSymbol.setOnItemClickListener { parent, _, position, _ ->
            val selectedAsset = parent.getItemAtPosition(position) as? HyperliquidOrderManager.AssetInfo
            val selectedDisplay = selectedAsset?.displaySymbol ?: actvSymbol.text.toString()
            val newRaw = HyperliquidOrderManager.cleanSymbolString(selectedDisplay)
            if (newRaw != currentRawSymbol) {
                currentRawSymbol = newRaw
                currentDisplaySymbol = selectedDisplay
                saveDemoTradingState()

                if (::webViewChart.isInitialized) {
                    val fullChartUrl = getTradingViewUrlForSymbol(newRaw)
                    webViewChart.loadUrl(fullChartUrl)
                }

                switchLivePriceSymbol(currentRawSymbol)
                updateLeverageLimitsForSymbol(currentRawSymbol)
                updatePositionSizeHint()
                updateTpSlEstUi()
                snackString("Chart Symbol: $currentDisplaySymbol")
            }
        }
    }

    private fun toggleMarketType(isSpot: Boolean) {
        this.isSpotMode = isSpot
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val darkGray = ContextCompat.getColor(this, R.color.bg_elevated)
        val black = ContextCompat.getColor(this, R.color.black)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)

        if (isSpot) {
            if (::btnMarketTypeSpot.isInitialized && ::btnMarketTypePerp.isInitialized) {
                btnMarketTypeSpot.isChecked = true
                btnMarketTypeSpot.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
                btnMarketTypeSpot.setTextColor(black)

                btnMarketTypePerp.isChecked = false
                btnMarketTypePerp.backgroundTintList = android.content.res.ColorStateList.valueOf(darkGray)
                btnMarketTypePerp.setTextColor(textPrimary)
            }

            if (::cardSpotBotPanel.isInitialized) cardSpotBotPanel.visibility = View.VISIBLE
            if (::layoutSpotBalances.isInitialized) layoutSpotBalances.visibility = View.VISIBLE
            if (::layoutPerpMarginContainer.isInitialized) layoutPerpMarginContainer.visibility = View.GONE
            if (::tvEstLiquidationPrice.isInitialized) tvEstLiquidationPrice.visibility = View.GONE
            if (::progressRiskMeter.isInitialized) progressRiskMeter.visibility = View.GONE
            if (::tvRiskLevelBadge.isInitialized) tvRiskLevelBadge.visibility = View.GONE

            findViewById<View>(R.id.cardActivePositions)?.visibility = View.GONE
            findViewById<View>(R.id.cardSpotOpenOrders)?.visibility = View.VISIBLE

            btnLong.text = "Buy"
            btnShort.text = "Sell"
            updateSideColorsForSpot()

            setupSpotSymbolDropdown()
            fetchSpotBalances()
            fetchSpotOpenOrders()
            snackString("Switched to Spot Trading Mode")
        } else {
            if (::btnMarketTypeSpot.isInitialized && ::btnMarketTypePerp.isInitialized) {
                btnMarketTypePerp.isChecked = true
                btnMarketTypePerp.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
                btnMarketTypePerp.setTextColor(black)

                btnMarketTypeSpot.isChecked = false
                btnMarketTypeSpot.backgroundTintList = android.content.res.ColorStateList.valueOf(darkGray)
                btnMarketTypeSpot.setTextColor(textPrimary)
            }

            if (::cardSpotBotPanel.isInitialized) cardSpotBotPanel.visibility = View.GONE
            if (::layoutSpotBalances.isInitialized) layoutSpotBalances.visibility = View.GONE
            if (::layoutPerpMarginContainer.isInitialized) layoutPerpMarginContainer.visibility = View.VISIBLE
            if (::tvEstLiquidationPrice.isInitialized) tvEstLiquidationPrice.visibility = View.VISIBLE
            if (::progressRiskMeter.isInitialized) progressRiskMeter.visibility = View.VISIBLE
            if (::tvRiskLevelBadge.isInitialized) tvRiskLevelBadge.visibility = View.VISIBLE

            findViewById<View>(R.id.cardActivePositions)?.visibility = View.VISIBLE
            findViewById<View>(R.id.cardSpotOpenOrders)?.visibility = View.GONE

            btnLong.text = getString(R.string.side_long)
            btnShort.text = getString(R.string.side_short)
            applySideColors()

            setupSymbolDropdown()
            snackString("Switched to Perpetual Futures Mode")
        }
    }

    private fun updateSideColorsForSpot() {
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val red = ContextCompat.getColor(this, R.color.brand_red)
        val elevated = ContextCompat.getColor(this, R.color.bg_elevated)
        val black = ContextCompat.getColor(this, R.color.black)
        val white = ContextCompat.getColor(this, R.color.white)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)

        val isBuy = (sideGroup.checkedButtonId == R.id.btnLong) || (btnLong.isChecked && !btnShort.isChecked)
        val baseAsset = if (currentDisplaySymbol.contains("/")) currentDisplaySymbol.substringBefore("/") else currentDisplaySymbol

        if (isBuy) {
            btnLong.isChecked = true
            btnLong.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            btnLong.setTextColor(black)

            btnShort.isChecked = false
            btnShort.backgroundTintList = android.content.res.ColorStateList.valueOf(elevated)
            btnShort.setTextColor(textPrimary)

            btnOpenPosition.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            btnOpenPosition.setTextColor(black)
            btnOpenPosition.text = "Buy $baseAsset"
        } else {
            btnShort.isChecked = true
            btnShort.backgroundTintList = android.content.res.ColorStateList.valueOf(red)
            btnShort.setTextColor(white)

            btnLong.isChecked = false
            btnLong.backgroundTintList = android.content.res.ColorStateList.valueOf(elevated)
            btnLong.setTextColor(textPrimary)

            btnOpenPosition.backgroundTintList = android.content.res.ColorStateList.valueOf(red)
            btnOpenPosition.setTextColor(white)
            btnOpenPosition.text = "Sell $baseAsset"
        }
    }

    private fun setupSpotSymbolDropdown() {
        val spotAssets = HyperliquidOrderManager.getAllSpotAssetInfos()
        val adapter = SpotDropdownAdapter(this, spotAssets)
        actvSymbol.setAdapter(adapter)

        if (!spotAssets.any { it.displaySymbol == currentDisplaySymbol }) {
            val defaultSpot = spotAssets.firstOrNull()?.displaySymbol ?: "PURR/USDC"
            currentDisplaySymbol = defaultSpot
            currentRawSymbol = HyperliquidOrderManager.cleanSymbolString(defaultSpot)
        }

        actvSymbol.setText(currentDisplaySymbol, false)

        actvSymbol.setOnClickListener {
            actvSymbol.showDropDown()
        }

        actvSymbol.setOnItemClickListener { parent, _, position, _ ->
            val selectedSpot = parent.getItemAtPosition(position) as? HyperliquidOrderManager.SpotAssetInfo
            val selectedDisplay = selectedSpot?.displaySymbol ?: actvSymbol.text.toString()
            val newRaw = HyperliquidOrderManager.cleanSymbolString(selectedDisplay)

            currentRawSymbol = newRaw
            currentDisplaySymbol = selectedDisplay
            saveDemoTradingState()

            if (::webViewChart.isInitialized) {
                val fullChartUrl = getTradingViewUrlForSymbol(newRaw)
                webViewChart.loadUrl(fullChartUrl)
            }

            switchLivePriceSymbol(currentRawSymbol)
            updateSideColorsForSpot()
            updateSpotBalanceUi()
            snackString("Spot Pair Selected: $currentDisplaySymbol")
        }
    }

    private fun setupBotEngine() {
        if (!::switchBotActive.isInitialized) return

        val prefs = getSharedPreferences("aetherdex_bot_prefs", MODE_PRIVATE)
        isBotActive = prefs.getBoolean("bot_active", false)
        botStrategy = prefs.getString("bot_strategy", "Smart DCA / Dip Buyer") ?: "Smart DCA / Dip Buyer"
        botAllocationPct = prefs.getInt("bot_allocation_pct", 25)
        botTotalPnlUsd = prefs.getFloat("bot_pnl_usd", 0.0f).toDouble()
        botActiveTradesCount = prefs.getInt("bot_active_trades", 0)

        switchBotActive.isChecked = isBotActive
        if (::sliderBotAllocation.isInitialized) sliderBotAllocation.value = botAllocationPct.toFloat()
        if (::tvBotAllocationLabel.isInitialized) tvBotAllocationLabel.text = "Capital Allocation: ${botAllocationPct}%"

        if (::chipStrategyGrid.isInitialized && ::chipStrategyTrend.isInitialized && ::chipStrategyDca.isInitialized) {
            when (botStrategy) {
                "Grid Trading" -> chipStrategyGrid.isChecked = true
                "Trend Following" -> chipStrategyTrend.isChecked = true
                "5m EMA High/Low" -> if (::chipStrategyEmaChannel.isInitialized) chipStrategyEmaChannel.isChecked = true else chipStrategyTrend.isChecked = true
                else -> chipStrategyDca.isChecked = true
            }
        }

        updateBotUi()

        switchBotActive.setOnCheckedChangeListener { _, isChecked ->
            isBotActive = isChecked
            saveBotPrefs()
            updateBotUi()
            if (isBotActive) {
                snackString("AI Bot Activated (24/7 Background Strategy Running)")
                startBotLoop()
            } else {
                snackString("AI Bot Paused")
                stopBotLoop()
            }
        }

        if (::sliderBotAllocation.isInitialized) {
            sliderBotAllocation.addOnChangeListener { _, value, _ ->
                botAllocationPct = value.toInt()
                if (::tvBotAllocationLabel.isInitialized) {
                    tvBotAllocationLabel.text = "Capital Allocation: ${botAllocationPct}%"
                }
                saveBotPrefs()
                if (isBotActive) {
                    FirebaseSyncManager.syncBotConfigToFirestore(
                        this,
                        active = true,
                        strategy = botStrategy,
                        allocationPct = botAllocationPct,
                        accountId = currentUserProfile.accountId
                    )
                }
            }
        }

        if (::chipGroupBotStrategy.isInitialized) {
            chipGroupBotStrategy.setOnCheckedStateChangeListener { _, checkedIds ->
                val id = checkedIds.firstOrNull() ?: R.id.chipStrategyDca
                botStrategy = when (id) {
                    R.id.chipStrategyGrid -> "Grid Trading"
                    R.id.chipStrategyTrend -> "Trend Following"
                    R.id.chipStrategyEmaChannel -> "5m EMA High/Low"
                    else -> "Smart DCA / Dip Buyer"
                }
                saveBotPrefs()
                snackString("Bot Strategy updated: $botStrategy")
                if (isBotActive) {
                    FirebaseSyncManager.syncBotConfigToFirestore(
                        this,
                        active = true,
                        strategy = botStrategy,
                        allocationPct = botAllocationPct,
                        accountId = currentUserProfile.accountId
                    )
                }
            }
        }

        if (isBotActive) {
            startBotLoop()
        }
    }

    private fun saveBotPrefs() {
        val prefs = getSharedPreferences("aetherdex_bot_prefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("bot_active", isBotActive)
            .putString("bot_strategy", botStrategy)
            .putInt("bot_allocation_pct", botAllocationPct)
            .putFloat("bot_pnl_usd", botTotalPnlUsd.toFloat())
            .putInt("bot_active_trades", botActiveTradesCount)
            .apply()
    }

    private fun updateBotUi() {
        if (!::tvBotStatusBadge.isInitialized) return
        if (isBotActive) {
            tvBotStatusBadge.text = "● RUNNING (24/7 Engine Active)"
            tvBotStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
        } else {
            tvBotStatusBadge.text = "● IDLE (Disabled)"
            tvBotStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        if (::tvBotActiveTradesCount.isInitialized) tvBotActiveTradesCount.text = "$botActiveTradesCount Active Trades"

        if (::tvBotTotalPnl.isInitialized) {
            val sign = if (botTotalPnlUsd >= 0) "+" else ""
            val pct = if (spotUsdcBalance > 0) (botTotalPnlUsd / spotUsdcBalance) * 100.0 else 0.0
            tvBotTotalPnl.text = String.format(Locale.US, "%s$%.2f (%s%.2f%%)", sign, botTotalPnlUsd, sign, pct)
            val color = if (botTotalPnlUsd >= 0) ContextCompat.getColor(this, R.color.brand_green) else ContextCompat.getColor(this, R.color.brand_red)
            tvBotTotalPnl.setTextColor(color)
        }
    }

    private fun startBotLoop() {
        stopBotLoop()
        lifecycleScope.launch(Dispatchers.IO) {
            AgentKeyManager.ensureAgentApprovedAndSynced(
                this@MainActivity,
                currentUserProfile.accountId
            )
            FirebaseSyncManager.syncBotConfigToFirestore(
                context = this@MainActivity,
                active = true,
                strategy = botStrategy,
                allocationPct = botAllocationPct,
                accountId = currentUserProfile.accountId
            )
            withContext(Dispatchers.Main) {
                botStatusListener = FirebaseSyncManager.listenToBotStatus(
                    accountId = currentUserProfile.accountId
                ) { pnlUsd, _, activeTrades ->
                    runOnUiThread {
                        botTotalPnlUsd = pnlUsd
                        botActiveTradesCount = activeTrades
                        saveBotPrefs()
                        updateBotUi()
                    }
                }
            }
        }
    }


    private fun stopBotLoop() {
        spotBotJob?.cancel()
        spotBotJob = null

        botStatusListener?.remove()
        botStatusListener = null

        FirebaseSyncManager.syncBotConfigToFirestore(
            context = this,
            active = false,
            strategy = botStrategy,
            allocationPct = botAllocationPct,
            accountId = currentUserProfile.accountId
        )
    }

    private fun fetchSpotBalances() {
        val addr = EmbeddedWalletManager.getAddress(this) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val balances = HyperliquidOrderManager.fetchSpotClearinghouseState(addr)
            withContext(Dispatchers.Main) {
                spotAssetBalances.clear()
                balances.forEach { (coin, info) ->
                    spotAssetBalances[coin] = info.available
                }
                updateSpotBalanceUi()
            }
        }
    }

    private fun updateSpotBalanceUi() {
        if (!::tvSpotUsdcBalance.isInitialized || !::tvSpotAssetBalance.isInitialized) return
        val usdcBal = spotAssetBalances["USDC"] ?: if (isDemoModeActive) demoBalance else hyperliquidL1UsdcBalance
        val baseToken = if (currentDisplaySymbol.contains("/")) currentDisplaySymbol.substringBefore("/") else currentDisplaySymbol
        val assetBal = spotAssetBalances[baseToken] ?: 0.0

        spotUsdcBalance = usdcBal

        tvSpotUsdcBalance.text = String.format(Locale.US, "Available USDC: $%.2f", usdcBal)
        tvSpotAssetBalance.text = String.format(Locale.US, "Available %s: %.4f", baseToken, assetBal)
    }

    private fun getPerpUsdcBalance(): Double {
        return if (isDemoModeActive) demoBalance else hyperliquidL1UsdcBalance
    }

    private fun getSpotUsdcBalance(): Double {
        return spotUsdcBalance
    }

    private fun showTransferDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_internal_transfer)

        val window = dialog.window
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseTransferDialog)
        val tvFromLabel = dialog.findViewById<TextView>(R.id.tvTransferFromLabel)
        val tvFromBalance = dialog.findViewById<TextView>(R.id.tvTransferFromBalance)
        val tvToLabel = dialog.findViewById<TextView>(R.id.tvTransferToLabel)
        val tvToBalance = dialog.findViewById<TextView>(R.id.tvTransferToBalance)
        val btnSwap = dialog.findViewById<ImageButton>(R.id.btnSwapTransferDirection)
        val etAmount = dialog.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTransferAmount)
        val btnMax = dialog.findViewById<MaterialButton>(R.id.btnTransferMax)
        val btnConfirm = dialog.findViewById<MaterialButton>(R.id.btnConfirmTransfer)

        var toSpot = true // true = Perp -> Spot, false = Spot -> Perp

        fun updateBalancesAndLabels() {
            val perpBal = getPerpUsdcBalance()
            val spotBal = getSpotUsdcBalance()

            if (toSpot) {
                tvFromLabel?.text = "Perpetual Futures"
                tvFromBalance?.text = String.format(Locale.US, "Avail: $%.2f USDC", perpBal)
                tvToLabel?.text = "Spot Clearinghouse"
                tvToBalance?.text = String.format(Locale.US, "Avail: $%.2f USDC", spotBal)
            } else {
                tvFromLabel?.text = "Spot Clearinghouse"
                tvFromBalance?.text = String.format(Locale.US, "Avail: $%.2f USDC", spotBal)
                tvToLabel?.text = "Perpetual Futures"
                tvToBalance?.text = String.format(Locale.US, "Avail: $%.2f USDC", perpBal)
            }
        }

        updateBalancesAndLabels()

        btnClose?.setOnClickListener { dialog.dismiss() }

        btnSwap?.setOnClickListener {
            toSpot = !toSpot
            updateBalancesAndLabels()
        }

        btnMax?.setOnClickListener {
            val perpBal = getPerpUsdcBalance()
            val spotBal = getSpotUsdcBalance()
            val maxAvailable = if (toSpot) perpBal else spotBal
            // Floor to 4 decimal places to prevent float rounding UP (e.g. 10.009 -> 10.00 instead of 10.01)
            val truncated = Math.floor(maxAvailable * 10000.0) / 10000.0
            etAmount?.setText(HyperliquidOrderManager.formatDecimal(truncated))
        }

        btnConfirm?.setOnClickListener {
            val amountStr = etAmount?.text?.toString()?.trim() ?: ""
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            val perpBal = getPerpUsdcBalance()
            val spotBal = getSpotUsdcBalance()
            val maxAvailable = if (toSpot) perpBal else spotBal

            if (amount <= 0.0) {
                snackString("Please enter a valid transfer amount > 0")
                return@setOnClickListener
            }

            // Epsilon tolerance check (1e-4) to prevent false positive "Insufficient balance" errors due to micro-precision discrepancies
            if (amount > (maxAvailable + 0.0001)) {
                snackString(String.format(Locale.US, "Insufficient balance! Available: $%.2f USDC", maxAvailable))
                return@setOnClickListener
            }

            // Cap transfer amount at exact maxAvailable to prevent API rejection on minor rounding bounds
            val safeTransferAmount = amount.coerceAtMost(maxAvailable)

            btnConfirm.isEnabled = false
            btnConfirm.text = "Transferring..."

            if (isDemoModeActive) {
                // Simulated internal transfer in Demo Mode
                if (toSpot) {
                    demoBalance = (demoBalance - safeTransferAmount).coerceAtLeast(0.0)
                    spotUsdcBalance += safeTransferAmount
                    spotAssetBalances["USDC"] = spotUsdcBalance
                } else {
                    val currentSpotUsdc = spotAssetBalances["USDC"] ?: spotUsdcBalance
                    val actualTransfer = safeTransferAmount.coerceAtMost(currentSpotUsdc)
                    spotUsdcBalance = (currentSpotUsdc - actualTransfer).coerceAtLeast(0.0)
                    spotAssetBalances["USDC"] = spotUsdcBalance
                    demoBalance += actualTransfer
                }
                saveDemoTradingState()
                updateSpotBalanceUi()
                updateDemoBalanceUi()
                snackString(String.format(Locale.US, "Successfully transferred $%.2f USDC (Demo)", safeTransferAmount))
                dialog.dismiss()
            } else {
                // Live L1 transfer on Hyperliquid Exchange API
                lifecycleScope.launch(Dispatchers.IO) {
                    val (success, msg) = HyperliquidOrderManager.transferUsdcBetweenPerpAndSpot(
                        this@MainActivity,
                        amountUsdc = safeTransferAmount,
                        toSpot = toSpot
                    )

                    withContext(Dispatchers.Main) {
                        btnConfirm.isEnabled = true
                        btnConfirm.text = "Confirm Internal Transfer"

                        if (success) {
                            snackString("Transfer Successful!")
                            dialog.dismiss()
                            fetchHyperliquidBalance()
                            fetchSpotBalances()
                        } else {
                            snackString("Transfer Failed: $msg")
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun fetchSpotOpenOrders() {
        val addr = EmbeddedWalletManager.getAddress(this) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val orders = HyperliquidOrderManager.fetchOpenSpotOrders(addr)
            withContext(Dispatchers.Main) {
                updateSpotOpenOrdersUi(orders)
            }
        }
    }

    private fun updateSpotOpenOrdersUi(orders: List<HyperliquidOrderManager.SpotOpenOrder>) {
        val container = findViewById<LinearLayout>(R.id.spotOpenOrdersListContainer) ?: return
        val tvBadge = findViewById<TextView>(R.id.tvSpotOpenOrdersBadge)
        val tvNoOrders = findViewById<TextView>(R.id.tvNoSpotOpenOrders)

        container.removeAllViews()

        tvBadge?.text = "${orders.size}"
        tvNoOrders?.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE

        for (order in orders) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.item_spot_open_order, container, false)
            val tvPair = itemView.findViewById<TextView>(R.id.tvSpotOrderPair)
            val tvSide = itemView.findViewById<TextView>(R.id.tvSpotOrderSide)
            val tvPrice = itemView.findViewById<TextView>(R.id.tvSpotOrderPrice)
            val tvSize = itemView.findViewById<TextView>(R.id.tvSpotOrderSize)
            val btnCancel = itemView.findViewById<MaterialButton>(R.id.btnCancelSpotOrder)

            val displaySymbol = if (order.coin.contains("/")) order.coin else "${order.coin}/USDC"
            tvPair?.text = displaySymbol
            tvSide?.text = order.side

            val isBuy = order.side == "BUY"
            tvSide?.setTextColor(
                ContextCompat.getColor(this, if (isBuy) R.color.brand_green else R.color.brand_red)
            )

            tvPrice?.text = String.format(Locale.US, "$%.4f USDC", order.limitPx)
            tvSize?.text = String.format(Locale.US, "Size: %.4f %s", order.sz, order.coin.substringBefore("/"))

            btnCancel?.setOnClickListener {
                btnCancel?.isEnabled = false
                btnCancel?.text = "Cancelling..."
                lifecycleScope.launch(Dispatchers.IO) {
                    val (success, msg) = HyperliquidOrderManager.cancelSpotOrder(
                        this@MainActivity,
                        assetIndex = order.assetIndex,
                        orderId = order.oid
                    )
                    withContext(Dispatchers.Main) {
                        if (success) {
                            snackString("Spot Order Cancelled Successfully")
                            fetchSpotOpenOrders()
                            fetchSpotBalances()
                        } else {
                            btnCancel?.isEnabled = true
                            btnCancel?.text = "Cancel"
                            snackString("Cancel Failed: $msg")
                        }
                    }
                }
            }

            container.addView(itemView)
        }
    }

    private fun executeSpotOrder() {
        val amountStr = etAmount.text?.toString() ?: ""
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            snackString("Please enter a valid amount")
            return
        }

        val isBuy = (sideGroup.checkedButtonId == R.id.btnLong) || (btnLong.isChecked && !btnShort.isChecked)
        val spotInfo = HyperliquidOrderManager.getSpotAssetInfo(currentDisplaySymbol)
        val l1AssetIndex = spotInfo?.l1AssetIndex ?: HyperliquidOrderManager.getSpotAssetIndex(currentDisplaySymbol)
        val szDecimals = spotInfo?.szDecimals ?: 2

        val rawEntryPrice = if (isMarketOrder) {
            if (currentBtcPrice <= 0) {
                snackString("Fetching live spot price... Please try again.")
                return
            }
            if (isBuy) currentBtcPrice * 1.02 else currentBtcPrice * 0.98
        } else {
            val limitStr = etEntryPrice.text?.toString() ?: ""
            val limitPrice = limitStr.toDoubleOrNull()
            if (limitPrice == null || limitPrice <= 0) {
                snackString("Please enter a valid limit price")
                return
            }
            limitPrice
        }

        val entryPrice = HyperliquidOrderManager.roundPriceToHyperliquidRules(rawEntryPrice, szDecimals)

        val availableUsdc = spotUsdcBalance
        val baseAsset = if (currentDisplaySymbol.contains("/")) currentDisplaySymbol.substringBefore("/") else currentDisplaySymbol
        val availableAsset = spotAssetBalances[baseAsset] ?: 0.0

        if (isBuy) {
            if (amount > availableUsdc && availableUsdc > 0.0) {
                snackString(String.format(Locale.US, "Insufficient USDC balance! Available: $%.2f", availableUsdc))
                return
            }
        } else {
            if (amount > availableAsset && availableAsset > 0.0) {
                snackString(String.format(Locale.US, "Insufficient %s balance! Available: %.4f", baseAsset, availableAsset))
                return
            }
        }

        val internalAddr = EmbeddedWalletManager.getAddress(this)
        val isExternalWcConnected = connectedAddress != null || WalletConnectionManager.getPersistedSession() != null
        val activeWalletAddr = if (isExternalWcConnected) {
            connectedAddress ?: WalletConnectionManager.getPersistedSession()?.address ?: internalAddr
        } else {
            internalAddr
        }

        if (activeWalletAddr == null) {
            snackString("Vault initialization error.")
            return
        }

        val rawSizeCrypto = if (isBuy) amount / entryPrice else amount
        val sizeCrypto = HyperliquidOrderManager.roundSizeToHyperliquidRules(rawSizeCrypto, szDecimals)

        val spotOrderPayload = HyperliquidOrderManager.HyperliquidOrderPayload(
            asset = l1AssetIndex,
            isBuy = isBuy,
            limitPx = entryPrice,
            sz = sizeCrypto,
            reduceOnly = false,
            orderType = if (isMarketOrder) "MARKET" else "LIMIT"
        )

        if (isDemoModeActive) {
            val mockSig = "0xSPOT_DEMO_" + System.currentTimeMillis().toString(16)
            snackString("Demo Spot Order Executed (${if (isBuy) "Bought" else "Sold"} $baseAsset)")
            processSpotOrderWithSignature(mockSig, spotOrderPayload, activeWalletAddr, amount, entryPrice, isBuy, baseAsset)
            return
        }

        if (isExternalWcConnected) {
            val eip712Json = HyperliquidOrderManager.buildEip712TypedDataJson(spotOrderPayload)
            snackString("Requesting Web3 EIP-712 Spot Order Signature...")
            WalletConnectionManager.requestEip712Signature(
                ctx = this,
                userAddress = activeWalletAddr,
                eip712Json = eip712Json,
                onSigned = { signature ->
                    processSpotOrderWithSignature(signature, spotOrderPayload, activeWalletAddr, amount, entryPrice, isBuy, baseAsset)
                },
                onError = { err ->
                    snackString("Signature Error: $err")
                }
            )
        } else {
            snackString("Signing Real Spot Order on Hyperliquid L1...")
            val action = HyperliquidOrderManager.buildOrderActionObject(spotOrderPayload)
            val signature = EmbeddedWalletManager.signEip712Order(this, action, spotOrderPayload.timestamp)
            processSpotOrderWithSignature(signature, spotOrderPayload, activeWalletAddr, amount, entryPrice, isBuy, baseAsset)
        }
    }

    private fun processSpotOrderWithSignature(
        signature: String,
        payload: HyperliquidOrderManager.HyperliquidOrderPayload,
        userAddr: String,
        amount: Double,
        price: Double,
        isBuy: Boolean,
        baseAsset: String
    ) {
        lifecycleScope.launch {
            try {
                val result = HyperliquidOrderManager.submitSignedOrderDetails(
                    userAddress = userAddr,
                    signatureHex = signature,
                    payload = payload
                )
                if (result.success) {
                    snackString("Spot Order Submitted to Hyperliquid L1!")
                    fetchSpotBalances()
                } else {
                    snackString("Spot Order Result: ${result.responseMsg}")
                }
            } catch (e: Exception) {
                snackString("Spot Order Error: ${e.message}")
            }
        }
    }

    private fun checkAndUpdateSymbol(url: String?, title: String?) {
        val extracted = extractSymbolFromUrlOrTitle(url, title) ?: return
        if (extracted != currentRawSymbol && extracted.isNotBlank()) {
            currentRawSymbol = extracted
            currentDisplaySymbol = formatDisplaySymbol(extracted)
            saveDemoTradingState()

            if (::actvSymbol.isInitialized) {
                actvSymbol.setText(currentDisplaySymbol, false)
            }

            if (!isMarketOrder) {
                etEntryPrice.setText("")
            }

            switchLivePriceSymbol(currentRawSymbol)
            updateLeverageLimitsForSymbol(currentRawSymbol)
            updatePositionSizeHint()
            updateTpSlEstUi()
            snackString("Chart Symbol: $currentDisplaySymbol")
        }
    }

    private fun onLivePriceUpdated(rawSymbol: String, newPrice: Double) {
        if (newPrice <= 0) return
        val oldPrice = symbolPrices[rawSymbol]
        symbolPrices[rawSymbol] = newPrice

        HyperliquidOrderManager.getAssetInfo(rawSymbol)?.let { info ->
            info.markPrice = newPrice
        }

        checkPriceAlerts(rawSymbol, newPrice)

        if (rawSymbol == currentRawSymbol) {
            currentBtcPrice = newPrice
            if (::tvLivePriceHeader.isInitialized) {
                val formattedPrice = if (newPrice >= 10.0) {
                    String.format(java.util.Locale.US, "$%,.2f", newPrice)
                } else {
                    String.format(java.util.Locale.US, "$%,.4f", newPrice)
                }
                tvLivePriceHeader.text = formattedPrice
            }
            updatePositionSizeHint()
            updateTpSlEstUi()

            if (!isMarketOrder && etEntryPrice.text.isNullOrEmpty()) {
                etEntryPrice.setText(String.format(java.util.Locale.US, "%.2f", newPrice))
            }
        }

        evaluateAllPositionsTriggers()

        if (activePositions.isNotEmpty() || oldPrice != newPrice) {
            updatePositionsListUi()
        }
    }

    private fun checkPositionTriggers(pos: Position, markPrice: Double): String? {
        if (markPrice <= 0) return null

        if (pos.isLong) {
            // Long position triggers: TP (price rises above TP), SL or Liquidation (price falls below)
            if (pos.tp > 0 && markPrice >= pos.tp) {
                return "TP"
            }
            if (pos.sl > 0 && markPrice <= pos.sl) {
                return "SL"
            }
            if (markPrice <= pos.estLiquidationPrice) {
                return "LIQUIDATION"
            }
        } else {
            // Short position triggers: TP (price falls below TP), SL or Liquidation (price rises above)
            if (pos.tp > 0 && markPrice <= pos.tp) {
                return "TP"
            }
            if (pos.sl > 0 && markPrice >= pos.sl) {
                return "SL"
            }
            if (markPrice >= pos.estLiquidationPrice) {
                return "LIQUIDATION"
            }
        }
        return null
    }

    private fun evaluateAllPositionsTriggers() {
        if (activePositions.isEmpty()) return
        val triggeredPositions = mutableListOf<Triple<Position, String, Double>>()

        for (pos in activePositions.toList()) {
            val posRawSymbol = pos.symbol.replace("/", "").uppercase()
            val markPrice = symbolPrices[posRawSymbol] ?: 0.0
            if (markPrice > 0) {
                val trigger = checkPositionTriggers(pos, markPrice)
                if (trigger != null) {
                    triggeredPositions.add(Triple(pos, trigger, markPrice))
                }
            }
        }

        for ((pos, trigger, price) in triggeredPositions) {
            executeAutoTrigger(pos, trigger, price)
        }
    }

    private fun executeAutoTrigger(pos: Position, triggerReason: String, triggerPrice: Double) {
        val pnl = pos.calculatePnl(triggerPrice)
        val pnlPercent = pos.calculatePnlPercent(triggerPrice)
        val returnedAmount = (pos.margin + pnl).coerceAtLeast(0.0)

        if (pos.isDemo) {
            demoBalance += returnedAmount
        } else {
            realOnChainBalance += returnedAmount
        }
        activePositions.remove(pos)

        val reasonDisplay = when (triggerReason) {
            "TP" -> "Take Profit"
            "SL" -> "Stop Loss"
            "LIQUIDATION" -> "Liquidated"
            else -> "Manual Close"
        }
        val closedPos = ClosedPosition(
            symbol = pos.symbol,
            isLong = pos.isLong,
            leverage = pos.leverage,
            margin = pos.margin,
            positionSizeUsdc = pos.positionSizeUsdc,
            entryPrice = pos.entryPrice,
            exitPrice = triggerPrice,
            realizedPnl = pnl,
            realizedPnlPercent = pnlPercent,
            closeReason = reasonDisplay,
            timestamp = System.currentTimeMillis(),
            isDemo = pos.isDemo
        )
        closedPositions.add(0, closedPos)
        saveDemoTradingState()

        updateDemoBalanceUi()
        updateEmbeddedVaultUi()
        updatePositionsListUi()
        updateHistoryListUi()

        val pnlSign = if (pnl >= 0) "+" else ""
        val sideStr = if (pos.isLong) "Long" else "Short"
        val formattedPrice = if (triggerPrice >= 10.0) {
            String.format(java.util.Locale.US, "$%,.2f", triggerPrice)
        } else {
            String.format(java.util.Locale.US, "$%,.4f", triggerPrice)
        }

        val message = when (triggerReason) {
            "TP" -> String.format(
                java.util.Locale.US,
                "🎯 %s %s closed by Take Profit at %s! Profit: %s$%.2f (%s%.1f%%)",
                pos.symbol, sideStr, formattedPrice, pnlSign, pnl, pnlSign, pnlPercent
            )
            "SL" -> String.format(
                java.util.Locale.US,
                "🛑 %s %s closed by Stop Loss at %s! PnL: %s$%.2f (%s%.1f%%)",
                pos.symbol, sideStr, formattedPrice, pnlSign, pnl, pnlSign, pnlPercent
            )
            "LIQUIDATION" -> String.format(
                java.util.Locale.US,
                "⚠️ %s %s Liquidated at %s!",
                pos.symbol, sideStr, formattedPrice
            )
            else -> String.format(
                java.util.Locale.US,
                "%s %s Position closed at %s!",
                pos.symbol, sideStr, formattedPrice
            )
        }

        snackString(message)
    }

    private fun updateTpSlEstUi() {
        if (!::tvTpEstProfit.isInitialized || !::tvSlEstLoss.isInitialized) return

        val amountText = etAmount.text?.toString() ?: ""
        val margin = amountText.toDoubleOrNull() ?: 0.0
        val leverage = sliderLeverage.value.toInt()
        val isLong = btnLong.isChecked

        val entryPrice = if (isMarketOrder) {
            currentBtcPrice
        } else {
            etEntryPrice.text?.toString()?.toDoubleOrNull() ?: currentBtcPrice
        }

        // 1. Take Profit (TP) Projected Gain
        val tpStr = etTp.text?.toString()?.trim() ?: ""
        val tpPrice = tpStr.toDoubleOrNull()

        if (tpPrice != null && tpPrice > 0 && margin > 0 && entryPrice > 0 && !tpStr.isEmpty()) {
            val projPnl = if (isLong) {
                ((tpPrice - entryPrice) / entryPrice) * margin * leverage
            } else {
                ((entryPrice - tpPrice) / entryPrice) * margin * leverage
            }
            val projPercent = (projPnl / margin) * 100.0
            val pnlSign = if (projPnl >= 0) "+" else ""
            
            // Format with appropriate precision based on value size
            val pnlDisplay = if (Math.abs(projPnl) < 0.01) {
                String.format(java.util.Locale.US, "%.4f", projPnl)
            } else {
                String.format(java.util.Locale.US, "%.2f", projPnl)
            }
            
            tvTpEstProfit.text = String.format(
                java.util.Locale.US,
                "Est. Profit: %s$%s (%s%.1f%%)",
                pnlSign, pnlDisplay, pnlSign, projPercent
            )
            val greenColor = ContextCompat.getColor(this, R.color.brand_green)
            val redColor = ContextCompat.getColor(this, R.color.brand_red)
            tvTpEstProfit.setTextColor(if (projPnl >= 0) greenColor else redColor)
        } else {
            tvTpEstProfit.text = "Est. Profit: -- USDC"
            tvTpEstProfit.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
        }

        // 2. Stop Loss (SL) Projected Loss
        val slStr = etSl.text?.toString()?.trim() ?: ""
        val slPrice = slStr.toDoubleOrNull()

        // Only calculate if we have valid input and all required values
        if (slPrice != null && slPrice > 0 && margin > 0 && entryPrice > 0 && !slStr.isEmpty()) {
            val projPnl = if (isLong) {
                ((slPrice - entryPrice) / entryPrice) * margin * leverage
            } else {
                ((entryPrice - slPrice) / entryPrice) * margin * leverage
            }
            val projPercent = (projPnl / margin) * 100.0
            val pnlSign = if (projPnl >= 0) "+" else ""
            
            // Format with appropriate precision based on value size
            val pnlDisplay = if (Math.abs(projPnl) < 0.01) {
                String.format(java.util.Locale.US, "%.4f", projPnl)
            } else {
                String.format(java.util.Locale.US, "%.2f", projPnl)
            }
            
            tvSlEstLoss.text = String.format(
                java.util.Locale.US,
                "Est. Loss: %s$%s (%s%.1f%%)",
                pnlSign, pnlDisplay, pnlSign, projPercent
            )
            val redColor = ContextCompat.getColor(this, R.color.brand_red)
            val greenColor = ContextCompat.getColor(this, R.color.brand_green)
            tvSlEstLoss.setTextColor(if (projPnl <= 0) redColor else greenColor)
        } else {
            tvSlEstLoss.text = "Est. Loss: -- USDC"
            tvSlEstLoss.setTextColor(ContextCompat.getColor(this, R.color.brand_red))
        }
    }

    private fun startPriceEngine() {
        switchLivePriceSymbol(currentRawSymbol)

        lifecycleScope.launch {
            while (true) {
                val allMids = fetchHyperliquidAllMids()
                if (allMids.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        allMids.forEach { (coin, price) ->
                            if (price > 0) {
                                symbolPrices[coin] = price
                                symbolPrices["${coin}USDC"] = price
                                symbolPrices["${coin}USDT"] = price
                            }
                        }

                        val cleanCurrent = HyperliquidOrderManager.cleanSymbolString(currentRawSymbol)
                        val curPx = allMids[cleanCurrent] ?: allMids[currentRawSymbol]
                        if (curPx != null && curPx > 0) {
                            onLivePriceUpdated(currentRawSymbol, curPx)
                        }
                    }
                }

                val livePrice = fetchLivePriceRest(currentRawSymbol)
                if (livePrice != null && livePrice > 0) {
                    withContext(Dispatchers.Main) {
                        onLivePriceUpdated(currentRawSymbol, livePrice)
                    }
                }

                withContext(Dispatchers.Main) {
                    evaluateAllPositionsTriggers()
                    if (activePositions.isNotEmpty()) {
                        updatePositionsListUi()
                    }
                }

                kotlinx.coroutines.delay(1500)
            }
        }

        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                withContext(Dispatchers.Main) {
                    if (::webViewChart.isInitialized) {
                        webViewChart.evaluateJavascript("window.location.href") { result ->
                            val cleanUrl = result?.replace("\"", "") ?: ""
                            val title = webViewChart.title
                            checkAndUpdateSymbol(cleanUrl, title)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchHyperliquidAllMids(): Map<String, Double> = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL("${HyperliquidOrderManager.HYPERLIQUID_API_URL}/info")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            val body = org.json.JSONObject().apply {
                put("type", "allMids")
            }

            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(text)
                val map = mutableMapOf<String, Double>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val px = json.optString(key, "0").toDoubleOrNull()
                    if (px != null && px > 0) {
                        map[key.uppercase()] = px
                    }
                }
                map
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun switchLivePriceSymbol(rawSymbol: String) {
        try {
            symbolWebSocket?.close(1000, "Symbol changed")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        lifecycleScope.launch {
            val initialPrice = fetchLivePriceRest(rawSymbol)
            if (initialPrice != null && initialPrice > 0) {
                withContext(Dispatchers.Main) {
                    onLivePriceUpdated(rawSymbol, initialPrice)
                }
            }
        }

        startLiveSymbolWebSocket(rawSymbol)
    }

    private suspend fun fetchLivePriceRest(rawSymbol: String): Double? = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL("https://api.binance.com/api/v3/ticker/price?symbol=$rawSymbol")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val priceStr = text.substringAfter("\"price\":\"").substringBefore("\"")
                priceStr.toDoubleOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startLiveSymbolWebSocket(rawSymbol: String) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val symbolLower = rawSymbol.lowercase()
            val request = okhttp3.Request.Builder()
                .url("wss://stream.binance.com:9443/ws/${symbolLower}@miniTicker")
                .build()

            symbolWebSocket = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    try {
                        val priceStr = text.substringAfter("\"c\":\"").substringBefore("\"")
                        val price = priceStr.toDoubleOrNull()
                        if (price != null && price > 0) {
                            runOnUiThread {
                                onLivePriceUpdated(rawSymbol, price)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    // Handled by REST loop
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyTypeToggle() {
        isMarketOrder = btnMarket.isChecked
        val marketChecked = isMarketOrder
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val elevated = ContextCompat.getColor(this, R.color.bg_elevated)
        val black = ContextCompat.getColor(this, R.color.black)
        val white = ContextCompat.getColor(this, R.color.white)

        btnMarket.backgroundTintList = android.content.res.ColorStateList.valueOf(if (marketChecked) green else elevated)
        btnMarket.setTextColor(if (marketChecked) black else white)

        btnLimit.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!marketChecked) green else elevated)
        btnLimit.setTextColor(if (!marketChecked) black else white)

        layoutEntryPrice.visibility = if (marketChecked) View.GONE else View.VISIBLE
        if (!marketChecked && etEntryPrice.text.isNullOrEmpty()) {
            etEntryPrice.setText(String.format(java.util.Locale.US, "%.2f", currentBtcPrice))
        }
    }

    private fun updateDemoBalanceUi() {
        val bal = if (isDemoModeActive) demoBalance else hyperliquidL1UsdcBalance
        tvAvailableBalance.text = String.format(java.util.Locale.US, "Avail: $%.2f USDC", bal)
    }

    private fun fetchHyperliquidBalance(showToast: Boolean = false) {
        lifecycleScope.launch {
            try {
                if (showToast) {
                    snackString("Syncing live balance with Hyperliquid L1...")
                }
                val userAddress = EmbeddedWalletManager.getActiveEvmAddress(this@MainActivity)
                if (userAddress.isBlank() || userAddress == "0x0000...0000") {
                    android.util.Log.w("MainActivity", "Invalid wallet address for Hyperliquid balance fetch")
                    return@launch
                }

                val response = HyperliquidOrderManager.fetchClearinghouseState(userAddress)
                if (response != null) {
                    val marginSummary = response.optJSONObject("marginSummary")
                    val accountValue = marginSummary?.optString("accountValue", "0.0")?.toDoubleOrNull() ?: 0.0
                    val withdrawableStr = response.optString("withdrawable", null)
                    val withdrawable = withdrawableStr?.toDoubleOrNull()
                    
                    // Forcefully overwrite local state with exact Hyperliquid L1 clearinghouse response
                    val balanceToUse = withdrawable ?: accountValue

                    hyperliquidL1UsdcBalance = balanceToUse
                    updateDemoBalanceUi()

                    // Save to SharedPreferences
                    val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
                    prefs.edit().putFloat("hyperliquid_l1_balance", balanceToUse.toFloat()).apply()

                    if (showToast) {
                        snackString(String.format(java.util.Locale.US, "Hyperliquid L1 Balance Synced: $%.2f USDC", balanceToUse))
                    }
                    android.util.Log.i("MainActivity", "Hyperliquid balance updated: $balanceToUse USDC (withdrawable=$withdrawable, accountValue=$accountValue)")
                } else {
                    android.util.Log.w("MainActivity", "Hyperliquid API returned null response")
                }
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.e("MainActivity", "Hyperliquid API timeout", e)
                if (showToast) snackString("Network timeout. Could not fetch Hyperliquid balance.")
            } catch (e: java.net.UnknownHostException) {
                android.util.Log.e("MainActivity", "Network unreachable", e)
                if (showToast) snackString("No internet connection. Could not fetch Hyperliquid balance.")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to fetch Hyperliquid balance", e)
                if (showToast) snackString("Error fetching Hyperliquid balance. Please try again.")
            }
        }
    }

    private fun updatePositionSizeHint() {
        val amountText = etAmount.text?.toString() ?: ""
        val amount = amountText.toDoubleOrNull() ?: 0.0
        val leverage = sliderLeverage.value.toInt()
        val sizeUsdc = amount * leverage
        val price = currentBtcPrice
        val sizeCrypto = if (price > 0) sizeUsdc / price else 0.0
        val baseAsset = if (currentDisplaySymbol.contains("/")) currentDisplaySymbol.substringBefore("/") else currentDisplaySymbol

        tvPositionSizeHint.text = String.format(
            java.util.Locale.US,
            "Position Size: $%.2f USDC (%.4f %s)",
            sizeUsdc,
            sizeCrypto,
            baseAsset
        )

        if (::tvEstLiquidationPrice.isInitialized && ::tvRiskLevelBadge.isInitialized && ::progressRiskMeter.isInitialized) {
            val isLong = (sideGroup.checkedButtonId == R.id.btnLong) || (btnLong.isChecked && !btnShort.isChecked)
            val entryPrice = if (isMarketOrder) {
                price
            } else {
                etEntryPrice.text?.toString()?.toDoubleOrNull() ?: price
            }

            if (entryPrice > 0) {
                val estLiqPrice = if (isLong) {
                    (entryPrice * (1.0 - (1.0 / leverage))).coerceAtLeast(0.0)
                } else {
                    entryPrice * (1.0 + (1.0 / leverage))
                }
                val sideName = if (isLong) "Long" else "Short"
                tvEstLiquidationPrice.text = String.format(
                    java.util.Locale.US,
                    "Est. Liq Price: $%,.2f (%s %dx)",
                    estLiqPrice, sideName, leverage
                )
            } else {
                tvEstLiquidationPrice.text = "Est. Liq Price: --"
            }

            val totalBal = if (isDemoModeActive) demoBalance else realOnChainBalance
            val marginUsageRatio = if (totalBal > 0) (amount / totalBal) else 0.0

            val (riskTitle, badgeBg, textColorRes, progressVal) = when {
                leverage >= 50 || marginUsageRatio > 0.5 -> {
                    Tuple4("HIGH RISK 🔴", R.drawable.bg_button_red, R.color.white, 90)
                }
                leverage >= 20 || marginUsageRatio > 0.2 -> {
                    Tuple4("MEDIUM RISK 🟡", R.drawable.bg_chip_unselected, R.color.brand_green, 55)
                }
                else -> {
                    Tuple4("LOW RISK 🟢", R.drawable.bg_chip_selected_green, R.color.black, 20)
                }
            }

            tvRiskLevelBadge.text = riskTitle
            tvRiskLevelBadge.setBackgroundResource(badgeBg)
            tvRiskLevelBadge.setTextColor(ContextCompat.getColor(this, textColorRes))
            progressRiskMeter.progress = progressVal
        }
    }

    private fun showPriceAlertsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val red = ContextCompat.getColor(this, R.color.brand_red)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(this).apply {
            text = "🔔 Price Alerts (${currentDisplaySymbol})"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val subtitleTv = TextView(this).apply {
            text = String.format(java.util.Locale.US, "Current Market Price: $%.2f", currentBtcPrice)
            textSize = 12f
            setTextColor(green)
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
        }

        val targetPriceLabel = TextView(this).apply {
            text = "Target Price (USDC)"
            textSize = 12f
            setTextColor(textSecondary)
        }

        val etTargetPrice = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = String.format(java.util.Locale.US, "%.2f", currentBtcPrice)
            setTextColor(textPrimary)
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
        }

        var isAboveSelected = true
        val dirToggleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        }

        val btnAbove = MaterialButton(this).apply {
            text = "Rises Above ▲"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply { setMargins(0, 0, (4 * density).toInt(), 0) }
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (8 * density).toInt()
        }

        val btnBelow = MaterialButton(this).apply {
            text = "Drops Below ▼"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f).apply { setMargins((4 * density).toInt(), 0, 0, 0) }
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bg_elevated))
            setTextColor(textPrimary)
            cornerRadius = (8 * density).toInt()
        }

        btnAbove.setOnClickListener {
            isAboveSelected = true
            btnAbove.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            btnAbove.setTextColor(ContextCompat.getColor(this, R.color.black))
            btnBelow.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_elevated))
            btnBelow.setTextColor(textPrimary)
        }

        btnBelow.setOnClickListener {
            isAboveSelected = false
            btnBelow.backgroundTintList = android.content.res.ColorStateList.valueOf(red)
            btnBelow.setTextColor(ContextCompat.getColor(this, R.color.white))
            btnAbove.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_elevated))
            btnAbove.setTextColor(textPrimary)
        }

        dirToggleContainer.addView(btnAbove)
        dirToggleContainer.addView(btnBelow)

        val activeAlertsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshActiveAlertsList() {
            activeAlertsContainer.removeAllViews()
            val symbolAlerts = activePriceAlerts.filter { it.symbol == currentDisplaySymbol }
            if (symbolAlerts.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "No active alerts for ${currentDisplaySymbol}"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                }
                activeAlertsContainer.addView(emptyTv)
                return
            }

            val listTitle = TextView(this).apply {
                text = "Active Alerts:"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textSecondary)
                setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
            }
            activeAlertsContainer.addView(listTitle)

            symbolAlerts.forEach { alert ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_input)
                    setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, (6 * density).toInt())
                    }
                }

                val dirStr = if (alert.isAbove) "▲ >= " else "▼ <= "
                val alertTv = TextView(this).apply {
                    text = String.format(java.util.Locale.US, "%s %s$%.2f", alert.symbol, dirStr, alert.targetPrice)
                    textSize = 12f
                    setTextColor(textPrimary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnDelete = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setColorFilter(red)
                    setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                    setOnClickListener {
                        activePriceAlerts.remove(alert)
                        savePriceAlerts()
                        refreshActiveAlertsList()
                        snackString("Alert deleted")
                    }
                }

                row.addView(alertTv)
                row.addView(btnDelete)
                activeAlertsContainer.addView(row)
            }
        }

        val btnAddAlert = MaterialButton(this).apply {
            text = "Create Price Alert"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (10 * density).toInt()
            setOnClickListener {
                val target = etTargetPrice.text.toString().toDoubleOrNull()
                if (target == null || target <= 0) {
                    snackString("Please enter a valid target price")
                    return@setOnClickListener
                }
                val newAlert = PriceAlert(
                    symbol = currentDisplaySymbol,
                    targetPrice = target,
                    isAbove = isAboveSelected
                )
                activePriceAlerts.add(newAlert)
                savePriceAlerts()
                refreshActiveAlertsList()
                etTargetPrice.setText("")
                snackString(String.format(java.util.Locale.US, "Alert set for %s at $%.2f", currentDisplaySymbol, target))
            }
        }

        refreshActiveAlertsList()

        container.addView(titleTv)
        container.addView(subtitleTv)
        container.addView(targetPriceLabel)
        container.addView(etTargetPrice)
        container.addView(dirToggleContainer)
        container.addView(btnAddAlert)
        container.addView(activeAlertsContainer)

        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout((resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun checkPriceAlerts(rawSymbol: String, newPrice: Double) {
        if (activePriceAlerts.isEmpty()) return
        val triggered = mutableListOf<PriceAlert>()

        for (alert in activePriceAlerts.toList()) {
            val alertRaw = alert.symbol.replace("/", "").uppercase()
            if (alertRaw == rawSymbol.uppercase()) {
                if ((alert.isAbove && newPrice >= alert.targetPrice) || (!alert.isAbove && newPrice <= alert.targetPrice)) {
                    triggered.add(alert)
                }
            }
        }

        if (triggered.isNotEmpty()) {
            for (alert in triggered) {
                activePriceAlerts.remove(alert)
                triggerPriceAlertNotification(alert, newPrice)
            }
            savePriceAlerts()
        }
    }

    private fun triggerPriceAlertNotification(alert: PriceAlert, currentPrice: Double) {
        val direction = if (alert.isAbove) "above" else "below"
        val msg = String.format(
            java.util.Locale.US,
            "🔔 PRICE ALERT: %s is now $%.2f (Target: $%.2f %s)",
            alert.symbol, currentPrice, alert.targetPrice, direction
        )

        snackString(msg)

        try {
            val channelId = "aetherdex_price_alerts"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Price Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for crypto target price alerts"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle("AetherDex Price Alert 📈")
                .setContentText(msg)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)

            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun savePriceAlerts() {
        try {
            val jsonArray = org.json.JSONArray()
            for (alert in activePriceAlerts) {
                jsonArray.put(alert.toJson())
            }
            getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
                .edit()
                .putString("price_alerts_json", jsonArray.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restorePriceAlerts() {
        try {
            activePriceAlerts.clear()
            val jsonStr = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
                .getString("price_alerts_json", null)
            if (!jsonStr.isNullOrEmpty()) {
                val jsonArray = org.json.JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    activePriceAlerts.add(parsePriceAlertFromJson(jsonArray.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun executeOrder() {
        if (isSpotMode) {
            executeSpotOrder()
            return
        }
        val amountStr = etAmount.text?.toString() ?: ""
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            snackString("Please enter a valid amount (USDC)")
            return
        }
        val availableBal = if (isDemoModeActive) demoBalance else hyperliquidL1UsdcBalance
        if (!isDemoModeActive && hyperliquidL1UsdcBalance <= 0.0) {
            snackString("Insufficient Hyperliquid L1 balance. Deposit USDC to Hyperliquid L1 to trade.")
            return
        }
        if (amount > availableBal) {
            snackString(String.format(java.util.Locale.US, "Insufficient balance! Available: $%.2f USDC", availableBal))
            return
        }

        val isLong = (sideGroup.checkedButtonId == R.id.btnLong) || (btnLong.isChecked && !btnShort.isChecked)
        val leverage = sliderLeverage.value.toInt()

        // Leverage validation
        if (leverage < 1 || leverage > 100) {
            snackString("Invalid leverage. Must be between 1x and 100x.")
            return
        }

        val assetIndex = HyperliquidOrderManager.getAssetIndex(currentDisplaySymbol)
        val szDecimals = HyperliquidOrderManager.getAssetSzDecimals(assetIndex)

        val rawEntryPrice = if (isMarketOrder) {
            if (currentBtcPrice <= 0) {
                snackString("Fetching live market price... Please try again.")
                return
            }
            // For Hyperliquid Market orders, apply 5% slippage price bound (higher for Long, lower for Short)
            if (isLong) currentBtcPrice * 1.05 else currentBtcPrice * 0.95
        } else {
            val limitStr = etEntryPrice.text?.toString() ?: ""
            val limitPrice = limitStr.toDoubleOrNull()
            if (limitPrice == null || limitPrice <= 0) {
                snackString("Please enter a valid limit entry price")
                return
            }
            // Validate limit price is reasonable (within 50% of current price)
            if (currentBtcPrice > 0) {
                val priceDeviation = Math.abs(limitPrice - currentBtcPrice) / currentBtcPrice
                if (priceDeviation > 0.5) {
                    snackString("Limit price is too far from current market price (>50% deviation)")
                    return
                }
            }
            limitPrice
        }

        // Round price to Hyperliquid's 5 significant figures & perp decimal limits
        val entryPrice = HyperliquidOrderManager.roundPriceToHyperliquidRules(rawEntryPrice, szDecimals)

        val tp = etTp.text?.toString()?.toDoubleOrNull() ?: 0.0
        val sl = etSl.text?.toString()?.toDoubleOrNull() ?: 0.0

        // TP/SL validation: cannot be on same side as entry
        if (tp > 0 && sl > 0) {
            if (isLong && tp <= sl) {
                snackString("Invalid TP/SL: Take Profit must be above Stop Loss for Long positions")
                return
            }
            if (!isLong && tp >= sl) {
                snackString("Invalid TP/SL: Take Profit must be below Stop Loss for Short positions")
                return
            }
        }

        val internalAddr = EmbeddedWalletManager.getAddress(this)
        
        // When using internal signing, always use the internal wallet address
        // Only use external wallet address if actually connected via WalletConnect
        val isExternalWcConnected = connectedAddress != null || WalletConnectionManager.getPersistedSession() != null
        val activeWalletAddr = if (isExternalWcConnected) {
            connectedAddress ?: WalletConnectionManager.getPersistedSession()?.address ?: internalAddr
        } else {
            internalAddr
        }

        if (activeWalletAddr == null) {
            snackString("Vault initialization error.")
            return
        }

        android.util.Log.i("MainActivity", "Using wallet address for order: $activeWalletAddr")
        android.util.Log.i("MainActivity", "Internal address: $internalAddr")
        android.util.Log.i("MainActivity", "External connected: $connectedAddress")
        android.util.Log.i("MainActivity", "Persisted session: ${WalletConnectionManager.getPersistedSession()?.address}")

        val sizeUsdc = amount * leverage
        val basePriceForSize = if (currentBtcPrice > 0) currentBtcPrice else entryPrice
        val rawSizeCrypto = if (basePriceForSize > 0) sizeUsdc / basePriceForSize else 0.0
        val sizeCrypto = HyperliquidOrderManager.roundSizeToHyperliquidRules(rawSizeCrypto, szDecimals)

        val orderPayload = HyperliquidOrderManager.HyperliquidOrderPayload(
            asset = assetIndex,
            isBuy = isLong,
            limitPx = entryPrice,
            sz = sizeCrypto,
            reduceOnly = false,
            orderType = if (isMarketOrder) "MARKET" else "LIMIT",
            tpPrice = tp,
            slPrice = sl
        )

        if (!isDemoModeActive) {
            val isCrossMode = (marginModeGroup.checkedButtonId == R.id.btnMarginCross) || btnMarginCross.isChecked
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    HyperliquidOrderManager.updateLeverageOnHyperliquid(
                        ctx = this@MainActivity,
                        asset = assetIndex,
                        leverage = leverage,
                        isCross = isCrossMode
                    )
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Failed updating leverage on Hyperliquid", e)
                }
            }
        }

        if (isDemoModeActive) {
            val mockSig = "0xDEMO_" + System.currentTimeMillis().toString(16)
            snackString("Demo Order Executed (Practice Mode)")
            processOrderWithSignature(mockSig, orderPayload, activeWalletAddr, amount, entryPrice, isLong, leverage, tp, sl)
            return
        }

        if (isExternalWcConnected) {
            val eip712Json = HyperliquidOrderManager.buildEip712TypedDataJson(orderPayload)
            snackString("Requesting Web3 EIP-712 Order Signature...")
            WalletConnectionManager.requestEip712Signature(
                ctx = this,
                userAddress = activeWalletAddr,
                eip712Json = eip712Json,
                onSigned = { signature ->
                    processOrderWithSignature(signature, orderPayload, activeWalletAddr, amount, entryPrice, isLong, leverage, tp, sl)
                },
                onError = { err ->
                    snackString("Signature Error: $err")
                }
            )
        } else {
            snackString("Signing Real Order with Internal Vault (AES-256 KeyStore)...")
            
            val action = HyperliquidOrderManager.buildOrderActionObject(orderPayload)
            
            val signature = EmbeddedWalletManager.signEip712Order(this, action, orderPayload.timestamp)
            processOrderWithSignature(signature, orderPayload, activeWalletAddr, amount, entryPrice, isLong, leverage, tp, sl)
        }
    }

    private fun processOrderWithSignature(
        signature: String,
        orderPayload: HyperliquidOrderManager.HyperliquidOrderPayload,
        userAddr: String,
        amount: Double,
        entryPrice: Double,
        isLong: Boolean,
        leverage: Int,
        tp: Double,
        sl: Double
    ) {
        lifecycleScope.launch {
            try {
                val orderResult = HyperliquidOrderManager.submitSignedOrderDetails(
                    userAddress = userAddr,
                    signatureHex = signature,
                    payload = orderPayload
                )

                withContext(Dispatchers.Main) {
                    if (!orderResult.success && !isDemoModeActive) {
                        snackString("Order failed: ${orderResult.responseMsg}")
                        return@withContext
                    }

                    val actualFillPrice = if (!isDemoModeActive) {
                        orderResult.fillAvgPx ?: (if (currentBtcPrice > 0) currentBtcPrice else entryPrice)
                    } else {
                        entryPrice
                    }

                    if (isDemoModeActive) {
                        demoBalance -= amount
                    } else {
                        // Forcefully fetch exact live balance directly from Hyperliquid API
                        fetchHyperliquidBalance()
                    }
                    updateDemoBalanceUi()
                    updateEmbeddedVaultUi()

                    val position = Position(
                        symbol = currentDisplaySymbol,
                        isLong = isLong,
                        entryPrice = actualFillPrice,
                        leverage = leverage,
                        margin = amount,
                        tp = tp,
                        sl = sl,
                        isDemo = isDemoModeActive
                    )

                    activePositions.add(0, position)
                    saveDemoTradingState()
                    updatePositionsListUi()

                    etAmount.setText("")
                    etTp.setText("")
                    etSl.setText("")
                    updatePositionSizeHint()
                    updateTpSlEstUi()

                    val sideName = if (isLong) "Long" else "Short"
                    val tpSlInfo = if (tp > 0 || sl > 0) {
                        val tpStr = if (tp > 0) " TP: $tp" else ""
                        val slStr = if (sl > 0) " SL: $sl" else ""
                        " ($tpStr$slStr)"
                    } else ""
                    snackString(String.format(
                        java.util.Locale.US,
                        "Hyperliquid L1 %s %s Position Filled at $%.2f%s",
                        currentDisplaySymbol, sideName, actualFillPrice, tpSlInfo
                    ))
                }
            } catch (e: java.net.SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    snackString("Network timeout. Order submission failed.")
                    android.util.Log.e("MainActivity", "Order submission timeout", e)
                }
            } catch (e: java.net.UnknownHostException) {
                withContext(Dispatchers.Main) {
                    snackString("No internet connection. Order submission failed.")
                    android.util.Log.e("MainActivity", "Order submission network error", e)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackString("Order submission error: ${e.message}")
                    android.util.Log.e("MainActivity", "Order submission error", e)
                }
            }
        }
    }

    private fun updatePositionsListUi() {
        val currentPositions = activePositions.filter { it.isDemo == isDemoModeActive }
        tvPositionsBadge.text = currentPositions.size.toString()
        if (currentPositions.isEmpty()) {
            tvNoPositions.visibility = View.VISIBLE
            positionsListContainer.removeAllViews()
            return
        }

        tvNoPositions.visibility = View.GONE
        positionsListContainer.removeAllViews()

        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val redColor = ContextCompat.getColor(this, R.color.brand_red)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val textTertiary = ContextCompat.getColor(this, R.color.text_tertiary)

        currentPositions.forEach { pos ->
            val card = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (10 * resources.displayMetrics.density).toInt()) }
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_input)
                val p = (12 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }

            val posRawSymbol = pos.symbol.replace("/", "").uppercase()
            val markPrice = symbolPrices[posRawSymbol] ?: currentBtcPrice

            val pnl = pos.calculatePnl(markPrice)
            val pnlPercent = pos.calculatePnlPercent(markPrice)
            val isProfit = pnl >= 0
            val pnlColor = if (isProfit) greenColor else redColor
            val pnlSign = if (isProfit) "+" else ""

            // Top Row: Side Badge + Symbol + Leverage | PnL Amount & %
            val topRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val sideBadge = TextView(this).apply {
                text = if (pos.isLong) "LONG" else "SHORT"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val padH = (8 * resources.displayMetrics.density).toInt()
                val padV = (2 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                setBackgroundResource(if (pos.isLong) R.drawable.bg_chip_selected_green else R.drawable.bg_button_red)
                setTextColor(if (pos.isLong) ContextCompat.getColor(context, R.color.black) else ContextCompat.getColor(context, R.color.white))
            }

            val symbolTv = TextView(this).apply {
                text = "${pos.symbol} · ${pos.leverage}x"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textPrimary)
                val marginL = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(marginL, 0, 0, 0)
                }
            }

            val pnlTv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "%s$%.2f (%s%.2f%%)", pnlSign, pnl, pnlSign, pnlPercent)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(pnlColor)
            }

            topRow.addView(sideBadge)
            topRow.addView(symbolTv)
            topRow.addView(pnlTv)

            // Middle Row: Entry Price, Mark Price, Est Liq Price, Margin, Size
            val detailsRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, 0) }
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val col1 = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val entryTv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "Entry: $%.2f  |  Mark: $%.2f", pos.entryPrice, markPrice)
                textSize = 11f
                setTextColor(textSecondary)
            }

            val liqTv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "Est. Liq: $%.2f", pos.estLiquidationPrice)
                textSize = 11f
                setTextColor(textTertiary)
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            val tpStr = if (pos.tp > 0) String.format(java.util.Locale.US, "$%.2f", pos.tp) else "--"
            val slStr = if (pos.sl > 0) String.format(java.util.Locale.US, "$%.2f", pos.sl) else "--"
            val tpSpanStr = "TP: $tpStr"
            val slSpanStr = "SL: $slStr"
            val fullTpSlText = "$tpSpanStr  |  $slSpanStr"

            val tpSlTv = TextView(this).apply {
                textSize = 11f
                val spannable = android.text.SpannableStringBuilder(fullTpSlText)
                if (pos.tp > 0) {
                    spannable.setSpan(
                        android.text.style.ForegroundColorSpan(greenColor),
                        0, tpSpanStr.length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (pos.sl > 0) {
                    val slStart = fullTpSlText.indexOf("SL:")
                    if (slStart >= 0) {
                        spannable.setSpan(
                            android.text.style.ForegroundColorSpan(redColor),
                            slStart, slStart + slSpanStr.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                text = spannable
                setTextColor(textTertiary)
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            col1.addView(entryTv)
            col1.addView(liqTv)
            col1.addView(tpSlTv)

            val col2 = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }

            val marginTv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "Margin: $%.2f  |  Size: $%.2f", pos.margin, pos.positionSizeUsdc)
                textSize = 11f
                setTextColor(textSecondary)
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (6 * resources.displayMetrics.density).toInt(), 0, 0) }
            }

            val editBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Edit TP/SL"
                textSize = 11f
                isAllCaps = false
                minHeight = 0
                minWidth = 0
                val h = (30 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h).apply {
                    setMargins(0, 0, (6 * resources.displayMetrics.density).toInt(), 0)
                }
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bg_elevated))
                setTextColor(greenColor)
                strokeColor = android.content.res.ColorStateList.valueOf(greenColor)
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                cornerRadius = (8 * resources.displayMetrics.density).toInt()
                setOnClickListener { showEditTpSlDialog(pos) }
            }

            val closeBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Close Position"
                textSize = 11f
                isAllCaps = false
                minHeight = 0
                minWidth = 0
                val h = (30 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.bg_elevated))
                setTextColor(redColor)
                strokeColor = android.content.res.ColorStateList.valueOf(redColor)
                strokeWidth = (1 * resources.displayMetrics.density).toInt()
                cornerRadius = (8 * resources.displayMetrics.density).toInt()
                setOnClickListener { closePosition(pos) }
            }

            btnRow.addView(editBtn)
            btnRow.addView(closeBtn)

            col2.addView(marginTv)
            col2.addView(btnRow)

            detailsRow.addView(col1)
            detailsRow.addView(col2)

            card.addView(topRow)
            card.addView(detailsRow)

            positionsListContainer.addView(card)
        }
    }

    private fun showEditTpSlDialog(pos: Position) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val redColor = ContextCompat.getColor(this, R.color.brand_red)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val sideName = if (pos.isLong) "LONG" else "SHORT"
        val titleTv = TextView(this).apply {
            text = "Edit TP/SL — ${pos.symbol} $sideName"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val subtitleTv = TextView(this).apply {
            text = String.format(java.util.Locale.US, "Entry: $%.2f  ·  Margin: $%.2f  ·  Leverage: %dx", pos.entryPrice, pos.margin, pos.leverage)
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (4 * density).toInt(), 0, (14 * density).toInt())
        }

        // TP Field
        val tpLabel = TextView(this).apply {
            text = "Take Profit (USDC)"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(greenColor)
        }

        val etTpDialog = EditText(this).apply {
            hint = "0.00"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(textPrimary)
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pIn = (10 * density).toInt()
            setPadding(pIn, pIn, pIn, pIn)
            if (pos.tp > 0) {
                setText(String.format(java.util.Locale.US, "%.2f", pos.tp))
            }
        }

        val tvTpPreview = TextView(this).apply {
            textSize = 11f
            setTextColor(greenColor)
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
        }

        // SL Field
        val slLabel = TextView(this).apply {
            text = "Stop Loss (USDC)"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(redColor)
        }

        val etSlDialog = EditText(this).apply {
            hint = "0.00"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(textPrimary)
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            val pIn = (10 * density).toInt()
            setPadding(pIn, pIn, pIn, pIn)
            if (pos.sl > 0) {
                setText(String.format(java.util.Locale.US, "%.2f", pos.sl))
            }
        }

        val tvSlPreview = TextView(this).apply {
            textSize = 11f
            setTextColor(redColor)
            setPadding(0, (4 * density).toInt(), 0, (16 * density).toInt())
        }

        val updatePreviews = {
            val tpVal = etTpDialog.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (tpVal > 0 && pos.entryPrice > 0 && pos.margin > 0) {
                val projPnl = if (pos.isLong) {
                    ((tpVal - pos.entryPrice) / pos.entryPrice) * pos.margin * pos.leverage
                } else {
                    ((pos.entryPrice - tpVal) / pos.entryPrice) * pos.margin * pos.leverage
                }
                val projPercent = (projPnl / pos.margin) * 100.0
                val sign = if (projPnl >= 0) "+" else ""
                tvTpPreview.text = String.format(java.util.Locale.US, "Est. Profit: %s$%.2f (%s%.1f%%)", sign, projPnl, sign, projPercent)
                tvTpPreview.setTextColor(if (projPnl >= 0) greenColor else redColor)
            } else {
                tvTpPreview.text = "Est. Profit: -- USDC"
                tvTpPreview.setTextColor(greenColor)
            }

            val slVal = etSlDialog.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (slVal > 0 && pos.entryPrice > 0 && pos.margin > 0) {
                val projPnl = if (pos.isLong) {
                    ((slVal - pos.entryPrice) / pos.entryPrice) * pos.margin * pos.leverage
                } else {
                    ((pos.entryPrice - slVal) / pos.entryPrice) * pos.margin * pos.leverage
                }
                val projPercent = (projPnl / pos.margin) * 100.0
                val sign = if (projPnl >= 0) "+" else ""
                tvSlPreview.text = String.format(java.util.Locale.US, "Est. Loss: %s$%.2f (%s%.1f%%)", sign, projPnl, sign, projPercent)
                tvSlPreview.setTextColor(if (projPnl <= 0) redColor else greenColor)
            } else {
                tvSlPreview.text = "Est. Loss: -- USDC"
                tvSlPreview.setTextColor(redColor)
            }
        }

        etTpDialog.addTextChangedListener { updatePreviews() }
        etSlDialog.addTextChangedListener { updatePreviews() }
        updatePreviews()

        // Button row
        val btnRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val btnCancel = com.google.android.material.button.MaterialButton(this).apply {
            text = "Cancel"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
        }

        val btnSave = com.google.android.material.button.MaterialButton(this).apply {
            text = "Save TP/SL"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(greenColor)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (8 * density).toInt()
            setOnClickListener {
                val newTp = etTpDialog.text?.toString()?.toDoubleOrNull() ?: 0.0
                val newSl = etSlDialog.text?.toString()?.toDoubleOrNull() ?: 0.0
                val index = activePositions.indexOfFirst { it.id == pos.id }
                if (index >= 0) {
                    activePositions[index] = activePositions[index].copy(tp = newTp, sl = newSl)
                    saveDemoTradingState()
                    updatePositionsListUi()
                    snackString("TP/SL updated for ${pos.symbol}")
                }
                dialog.dismiss()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRowContainer.addView(btnCancel)
        btnRowContainer.addView(btnSave)

        container.addView(titleTv)
        container.addView(subtitleTv)
        container.addView(tpLabel)
        container.addView(etTpDialog)
        container.addView(tvTpPreview)
        container.addView(slLabel)
        container.addView(etSlDialog)
        container.addView(tvSlPreview)
        container.addView(btnRowContainer)

        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun closePosition(pos: Position) {
        val posRawSymbol = pos.symbol.replace("/", "").uppercase()
        val markPrice = symbolPrices[posRawSymbol] ?: currentBtcPrice
        val pnl = pos.calculatePnl(markPrice)
        val pnlPercent = pos.calculatePnlPercent(markPrice)
        val returnedAmount = (pos.margin + pnl).coerceAtLeast(0.0)

        if (pos.isDemo) {
            // Demo mode: just update local balance
            demoBalance += returnedAmount
            finalizePositionClose(pos, markPrice, pnl, pnlPercent, "Manual Close")
        } else {
            // Real mode: close position on Hyperliquid L1
            closeRealPositionOnHyperliquid(pos, markPrice, pnl, pnlPercent)
        }
    }

    private fun closeRealPositionOnHyperliquid(pos: Position, markPrice: Double, pnl: Double, pnlPercent: Double) {
        val posRawSymbol = pos.symbol.replace("/", "").uppercase()
        val assetIndex = HyperliquidOrderManager.getAssetIndex(pos.symbol)
        val sizeCrypto = pos.positionSizeUsdc / pos.entryPrice

        val internalAddr = EmbeddedWalletManager.getAddress(this)
        
        // When using internal signing, always use the internal wallet address
        // Only use external wallet address if actually connected via WalletConnect
        val isExternalWcConnected = connectedAddress != null || WalletConnectionManager.getPersistedSession() != null
        val activeWalletAddr = if (isExternalWcConnected) {
            connectedAddress ?: WalletConnectionManager.getPersistedSession()?.address ?: internalAddr
        } else {
            internalAddr
        }

        if (activeWalletAddr == null) {
            snackString("Wallet address not found. Cannot close position.")
            return
        }

        android.util.Log.i("MainActivity", "Using wallet address for close: $activeWalletAddr")
        android.util.Log.i("MainActivity", "Internal address: $internalAddr")

        snackString("Closing position on Hyperliquid L1...")

        lifecycleScope.launch {
            try {
                // Create close order payload
                val closeOrderPayload = HyperliquidOrderManager.HyperliquidOrderPayload(
                    asset = assetIndex,
                    isBuy = !pos.isLong,  // Opposite side to close (Long -> Sell, Short -> Buy)
                    limitPx = markPrice,
                    sz = sizeCrypto,
                    reduceOnly = true,  // reduce-only
                    orderType = "MARKET"  // Market order for immediate close
                )

                // Generate signature
                if (isExternalWcConnected) {
                    val eip712Json = HyperliquidOrderManager.buildEip712TypedDataJson(closeOrderPayload)
                    WalletConnectionManager.requestEip712Signature(
                        ctx = this@MainActivity,
                        userAddress = activeWalletAddr,
                        eip712Json = eip712Json,
                        onSigned = { signature ->
                            processCloseOrderWithSignature(signature, closeOrderPayload, activeWalletAddr, pos, markPrice, pnl, pnlPercent)
                        },
                        onError = { err ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                snackString("Close signature error: $err")
                            }
                        }
                    )
                } else {
                    snackString("Signing Close Order with Internal Vault...")
                    
                    val action = HyperliquidOrderManager.buildCloseOrderActionObject(
                        asset = closeOrderPayload.asset,
                        isLong = pos.isLong,
                        sizeCrypto = closeOrderPayload.sz,
                        currentPrice = closeOrderPayload.limitPx
                    )
                    
                    val signature = EmbeddedWalletManager.signEip712Order(this@MainActivity, action, closeOrderPayload.timestamp)
                    processCloseOrderWithSignature(signature, closeOrderPayload, activeWalletAddr, pos, markPrice, pnl, pnlPercent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error closing position", e)
                    snackString("Error closing position: ${e.message}")
                }
            }
        }
    }

    private fun processCloseOrderWithSignature(
        signature: String,
        orderPayload: HyperliquidOrderManager.HyperliquidOrderPayload,
        userAddr: String,
        pos: Position,
        markPrice: Double,
        pnl: Double,
        pnlPercent: Double
    ) {
        lifecycleScope.launch {
            try {
                val closeResult = HyperliquidOrderManager.closePositionOnHyperliquidDetails(
                    userAddress = userAddr,
                    signatureHex = signature,
                    asset = orderPayload.asset,
                    isLong = pos.isLong,
                    sizeCrypto = orderPayload.sz,
                    currentPrice = orderPayload.limitPx,
                    nonce = orderPayload.timestamp
                )

                withContext(Dispatchers.Main) {
                    if (!closeResult.success) {
                        snackString("Close order failed: ${closeResult.responseMsg}")
                        return@withContext
                    }

                    // Extract actual exit fill price from Hyperliquid fill response
                    val actualExitPrice = closeResult.fillAvgPx ?: markPrice
                    val posSizeUsdc = pos.margin * pos.leverage
                    val actualPnl = if (pos.entryPrice > 0) {
                        if (pos.isLong) {
                            (actualExitPrice - pos.entryPrice) * (posSizeUsdc / pos.entryPrice)
                        } else {
                            (pos.entryPrice - actualExitPrice) * (posSizeUsdc / pos.entryPrice)
                        }
                    } else pnl

                    val actualPnlPercent = if (pos.margin > 0) (actualPnl / pos.margin) * 100.0 else pnlPercent

                    snackString(String.format(java.util.Locale.US, "Position closed at $%.2f (PnL: $%.2f)", actualExitPrice, actualPnl))

                    // Immediate balance refresh
                    fetchHyperliquidBalance()

                    // Delayed balance refresh to catch on-chain settlement
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(1000)
                        fetchHyperliquidBalance()
                    }

                    // Finalize local position tracking with exact execution fill price and PnL
                    finalizePositionClose(pos, actualExitPrice, actualPnl, actualPnlPercent, "Manual Close (Hyperliquid L1)")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error processing close order", e)
                    snackString("Error processing close order: ${e.message}")
                }
            }
        }
    }

    private fun finalizePositionClose(pos: Position, exitPrice: Double, pnl: Double, pnlPercent: Double, closeReason: String) {
        // Remove from active positions
        activePositions.remove(pos)

        // Add to closed positions history
        val closedPos = ClosedPosition(
            symbol = pos.symbol,
            isLong = pos.isLong,
            leverage = pos.leverage,
            margin = pos.margin,
            positionSizeUsdc = pos.positionSizeUsdc,
            entryPrice = pos.entryPrice,
            exitPrice = exitPrice,
            realizedPnl = pnl,
            realizedPnlPercent = pnlPercent,
            closeReason = closeReason,
            timestamp = System.currentTimeMillis(),
            isDemo = pos.isDemo
        )
        closedPositions.add(0, closedPos)
        saveDemoTradingState()

        // Update UI
        updateDemoBalanceUi()
        updateEmbeddedVaultUi()
        updatePositionsListUi()
        updateHistoryListUi()

        // Show success message
        val pnlSign = if (pnl >= 0) "+" else ""
        snackString(String.format(
            java.util.Locale.US,
            "%s Position Closed. Realized PnL: %s$%.2f (%s%.2f%%)",
            pos.symbol, pnlSign, pnl, pnlSign, pnlPercent
        ))
    }

    private fun saveDemoTradingState() {
        try {
            val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
            val jsonArray = org.json.JSONArray()
            for (pos in activePositions) {
                jsonArray.put(pos.toJson())
            }
            val closedJsonArray = org.json.JSONArray()
            for (closedPos in closedPositions) {
                closedJsonArray.put(closedPos.toJson())
            }
            prefs.edit()
                .putFloat("demo_balance", demoBalance.toFloat())
                .putString("active_positions_json", jsonArray.toString())
                .putString("closed_positions_json", closedJsonArray.toString())
                .putString("selected_raw_symbol", currentRawSymbol)
                .putString("selected_display_symbol", currentDisplaySymbol)
                .putBoolean("margin_mode_isolated", marginModeIsolated)
                .apply()

            FirebaseSyncManager.syncUserDataToFirestore(
                context = this,
                userProfile = currentUserProfile,
                demoBalance = demoBalance,
                activePositions = activePositions,
                closedPositions = closedPositions,
                connectedWalletAddress = connectedAddress
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreDemoTradingState() {
        try {
            val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
            demoBalance = prefs.getFloat("demo_balance", 0.0f).toDouble()
            hyperliquidL1UsdcBalance = prefs.getFloat("hyperliquid_l1_balance", 0.0f).toDouble()
            marginModeIsolated = prefs.getBoolean("margin_mode_isolated", true)  // Default to isolated
            currentRawSymbol = prefs.getString("selected_raw_symbol", "BTCUSDC") ?: "BTCUSDC"
            currentDisplaySymbol = prefs.getString("selected_display_symbol", "BTC/USDC") ?: "BTC/USDC"

            if (::actvSymbol.isInitialized) {
                actvSymbol.setText(currentDisplaySymbol, false)
            }

            // Restore margin mode UI state
            applyMarginModeColors()

            activePositions.clear()
            val jsonStr = prefs.getString("active_positions_json", null)
            if (!jsonStr.isNullOrEmpty()) {
                val jsonArray = org.json.JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    activePositions.add(parsePositionFromJson(obj))
                }
            }

            closedPositions.clear()
            val closedJsonStr = prefs.getString("closed_positions_json", null)
            if (!closedJsonStr.isNullOrEmpty()) {
                val jsonArray = org.json.JSONArray(closedJsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    closedPositions.add(parseClosedPositionFromJson(obj))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateHistoryListUi() {
        if (!::historyListContainer.isInitialized) return

        tvHistoryBadge?.apply {
            if (isDemoModeActive) {
                text = "DEMO HISTORY"
                setBackgroundResource(R.drawable.bg_chip_selected_green)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            } else {
                text = "REAL HISTORY"
                setBackgroundResource(R.drawable.bg_chip_unselected)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_green))
            }
        }

        val currentHistory = closedPositions.filter { it.isDemo == isDemoModeActive }
        val totalTrades = currentHistory.size
        val totalPnl = currentHistory.sumOf { it.realizedPnl }
        val pnlSign = if (totalPnl >= 0) "+" else ""
        val formattedTotalPnl = String.format(java.util.Locale.US, "%s$%.2f", pnlSign, totalPnl)

        tvHistorySummary.text = if (totalTrades == 0) "0 Closed Positions" else "$totalTrades Closed Trades  ·  Total PnL: $formattedTotalPnl"
        tvHistorySummary.setTextColor(
            if (totalPnl > 0) ContextCompat.getColor(this, R.color.brand_green)
            else if (totalPnl < 0) ContextCompat.getColor(this, R.color.brand_red)
            else ContextCompat.getColor(this, R.color.text_tertiary)
        )

        if (currentHistory.isEmpty()) {
            tvNoHistory.visibility = View.VISIBLE
            historyListContainer.removeAllViews()
            return
        }

        tvNoHistory.visibility = View.GONE
        historyListContainer.removeAllViews()

        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val redColor = ContextCompat.getColor(this, R.color.brand_red)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val textTertiary = ContextCompat.getColor(this, R.color.text_tertiary)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)

        currentHistory.forEach { pos ->
            val card = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (10 * resources.displayMetrics.density).toInt()) }
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_input)
                val p = (12 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }

            val isProfit = pos.realizedPnl >= 0
            val pnlColor = if (isProfit) greenColor else redColor
            val pnlSignStr = if (isProfit) "+" else ""

            // Top Row: Side Badge + Symbol + Leverage | PnL Amount & %
            val topRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val sideBadge = TextView(this).apply {
                text = if (pos.isLong) "LONG" else "SHORT"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val padH = (8 * resources.displayMetrics.density).toInt()
                val padV = (2 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                setBackgroundResource(if (pos.isLong) R.drawable.bg_chip_selected_green else R.drawable.bg_button_red)
                setTextColor(if (pos.isLong) ContextCompat.getColor(context, R.color.black) else ContextCompat.getColor(context, R.color.white))
            }

            val symbolTv = TextView(this).apply {
                text = "${pos.symbol} · ${pos.leverage}x"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textPrimary)
                val marginL = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(marginL, 0, 0, 0)
                }
            }

            val pnlTv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "%s$%.2f (%s%.2f%%)", pnlSignStr, pos.realizedPnl, pnlSignStr, pos.realizedPnlPercent)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(pnlColor)
            }

            topRow.addView(sideBadge)
            topRow.addView(symbolTv)
            topRow.addView(pnlTv)

            // Middle Row: Entry vs Exit | Margin & Size
            val detailsRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, 0) }
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val col1 = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val entryExitTv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "Entry: $%.2f  |  Exit: $%.2f", pos.entryPrice, pos.exitPrice)
                textSize = 11f
                setTextColor(textSecondary)
            }

            val timeTv = TextView(this).apply {
                text = dateFormat.format(java.util.Date(pos.timestamp))
                textSize = 10f
                setTextColor(textTertiary)
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            col1.addView(entryExitTv)
            col1.addView(timeTv)

            val col2 = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }

            val marginTv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "Margin: $%.2f  |  Size: $%.2f", pos.margin, pos.positionSizeUsdc)
                textSize = 11f
                setTextColor(textSecondary)
            }

            val reasonBadge = TextView(this).apply {
                text = pos.closeReason
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(when(pos.closeReason) {
                    "Take Profit" -> greenColor
                    "Stop Loss" -> redColor
                    "Liquidated" -> redColor
                    else -> textSecondary
                })
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            }

            col2.addView(marginTv)
            col2.addView(reasonBadge)

            detailsRow.addView(col1)
            detailsRow.addView(col2)

            card.addView(topRow)
            card.addView(detailsRow)

            card.isClickable = true
            card.isFocusable = true
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val typedArray = obtainStyledAttributes(attrs)
            card.foreground = typedArray.getDrawable(0)
            typedArray.recycle()

            card.setOnClickListener { showClosedPositionDetailsDialog(pos) }

            historyListContainer.addView(card)
        }
    }

    private fun showClosedPositionDetailsDialog(pos: ClosedPosition) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val greenColor = ContextCompat.getColor(this, R.color.brand_green)
        val redColor = ContextCompat.getColor(this, R.color.brand_red)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val textTertiary = ContextCompat.getColor(this, R.color.text_tertiary)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val sideName = if (pos.isLong) "LONG" else "SHORT"
        val isProfit = pos.realizedPnl >= 0
        val pnlColor = if (isProfit) greenColor else redColor
        val pnlSign = if (isProfit) "+" else ""

        val titleTv = TextView(this).apply {
            text = "Trade Details — ${pos.symbol} $sideName"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val openTimeStr = dateFormat.format(java.util.Date(pos.timestamp - 300000L))
        val closeTimeStr = dateFormat.format(java.util.Date(pos.timestamp))

        val subtitleTv = TextView(this).apply {
            text = "Closed via ${pos.closeReason} · $closeTimeStr"
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (4 * density).toInt(), 0, (14 * density).toInt())
        }

        val addDetailRow = { label: String, value: String, valueColor: Int ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt()) }
            }

            val tvL = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvV = TextView(this).apply {
                text = value
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(valueColor)
                gravity = android.view.Gravity.END
            }

            row.addView(tvL)
            row.addView(tvV)
            container.addView(row)
        }

        val divider = {
            val d = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply { setMargins(0, (8 * density).toInt(), 0, (8 * density).toInt()) }
                setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
            }
            container.addView(d)
        }

        container.addView(titleTv)
        container.addView(subtitleTv)

        val priceChangePct = if (pos.entryPrice > 0) {
            if (pos.isLong) {
                ((pos.exitPrice - pos.entryPrice) / pos.entryPrice) * 100.0
            } else {
                ((pos.entryPrice - pos.exitPrice) / pos.entryPrice) * 100.0
            }
        } else 0.0
        val priceChangeSign = if (priceChangePct >= 0) "+" else ""

        val tradingFee = pos.positionSizeUsdc * 0.0005 * 2.0
        val fundingFee = 0.00
        val netPnl = pos.realizedPnl - tradingFee

        addDetailRow("Realized PnL", String.format(java.util.Locale.US, "%s$%.2f (%s%.2f%%)", pnlSign, pos.realizedPnl, pnlSign, pos.realizedPnlPercent), pnlColor)
        addDetailRow("Net PnL (after fees)", String.format(java.util.Locale.US, "%s$%.2f", if (netPnl >= 0) "+" else "", netPnl), if (netPnl >= 0) greenColor else redColor)
        divider()

        addDetailRow("Entry Price", String.format(java.util.Locale.US, "$%.2f", pos.entryPrice), textPrimary)
        addDetailRow("Exit Price", String.format(java.util.Locale.US, "$%.2f", pos.exitPrice), textPrimary)
        addDetailRow("Price Change", String.format(java.util.Locale.US, "%s%.2f%%", priceChangeSign, priceChangePct), if (priceChangePct >= 0) greenColor else redColor)
        divider()

        addDetailRow("Initial Margin", String.format(java.util.Locale.US, "$%.2f USDC", pos.margin), textPrimary)
        addDetailRow("Leverage", "${pos.leverage}x", textPrimary)
        addDetailRow("Total Position Size", String.format(java.util.Locale.US, "$%.2f USDC", pos.positionSizeUsdc), textPrimary)
        divider()

        addDetailRow("Estimated Taker Fee", String.format(java.util.Locale.US, "$%.2f USDC", tradingFee), textSecondary)
        addDetailRow("Funding Fee", String.format(java.util.Locale.US, "$%.2f USDC", fundingFee), textSecondary)
        addDetailRow("Close Reason", pos.closeReason, textPrimary)
        addDetailRow("Opened At", openTimeStr, textTertiary)
        addDetailRow("Closed At", closeTimeStr, textTertiary)

        val btnClose = com.google.android.material.button.MaterialButton(this).apply {
            text = "Close"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply { setMargins(0, (16 * density).toInt(), 0, 0) }
        }

        container.addView(btnClose)

        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    // -------------------------------------------------------------------- //
    // Draggable Floating Button Helper                                      //
    // -------------------------------------------------------------------- //

    @SuppressLint("ClickableViewAccessibility")
    private fun makeViewDraggableAndClickable(
        view: View,
        parentView: ViewGroup,
        onClick: () -> Unit
    ) {
        val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        var dX = 0f
        var dY = 0f
        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false

        // Position according to saved ratios on layout pass
        parentView.post {
            val savedXRatio = prefs.getFloat("fullscreen_btn_x_ratio", -1f)
            val savedYRatio = prefs.getFloat("fullscreen_btn_y_ratio", -1f)
            if (savedXRatio >= 0f && savedYRatio >= 0f && parentView.width > view.width && parentView.height > view.height) {
                val maxTranslationX = (parentView.width - view.width).toFloat()
                val maxTranslationY = (parentView.height - view.height).toFloat()
                view.x = (savedXRatio * maxTranslationX).coerceIn(0f, maxTranslationX)
                view.y = (savedYRatio * maxTranslationY).coerceIn(0f, maxTranslationY)
            }
        }

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = Math.abs(event.rawX - startRawX)
                    val deltaY = Math.abs(event.rawY - startRawY)
                    if (deltaX > touchSlop || deltaY > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val newX = event.rawX + dX
                        val newY = event.rawY + dY
                        val maxX = (parentView.width - v.width).coerceAtLeast(0).toFloat()
                        val maxY = (parentView.height - v.height).coerceAtLeast(0).toFloat()

                        v.x = newX.coerceIn(0f, maxX)
                        v.y = newY.coerceIn(0f, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!isDragging) {
                        v.performClick()
                        onClick()
                    } else {
                        val maxX = (parentView.width - v.width).coerceAtLeast(1).toFloat()
                        val maxY = (parentView.height - v.height).coerceAtLeast(1).toFloat()
                        val xRatio = (v.x / maxX).coerceIn(0f, 1f)
                        val yRatio = (v.y / maxY).coerceIn(0f, 1f)
                        prefs.edit()
                            .putFloat("fullscreen_btn_x_ratio", xRatio)
                            .putFloat("fullscreen_btn_y_ratio", yRatio)
                            .apply()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    // -------------------------------------------------------------------- //
    // TradingView Chart WebView Integration                                 //
    // -------------------------------------------------------------------- //

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun setupChartWebView() {
        webViewChart.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webViewChart.setBackgroundColor(android.graphics.Color.parseColor("#0B0E14"))
        webViewChart.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        webViewChart.isVerticalScrollBarEnabled = false
        webViewChart.isHorizontalScrollBarEnabled = false

        // 1. Enable Cookie & Session Persistence (First and Third-Party Cookies)
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webViewChart, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Configure WebSettings for DOM Storage, WebSQL/IndexedDB, Cache, and Windowing Persistence
        webViewChart.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true

            // Set dedicated database storage path for WebSQL & IndexedDB
            val webDbPath = applicationContext.getDir("tradingview_webview_db", Context.MODE_PRIVATE).path
            @Suppress("DEPRECATION")
            databasePath = webDbPath

            // Allow multi-touch pointer events to pass through to JS canvas unmodified
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
        }

        webViewChart.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        webViewChart.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                enterFullscreenChart(customView)
            }

            override fun onHideCustomView() {
                exitFullscreenChart()
            }
        }

        webViewChart.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkAndUpdateSymbol(url, view?.title)

                // Re-hydrate localStorage backup if clean environment or new device
                val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
                val savedState = prefs.getString("tradingview_local_storage_json", null)
                if (!savedState.isNullOrBlank()) {
                    val js = """
                        (function() {
                            try {
                                var raw = $savedState;
                                var data = (typeof raw === 'string') ? JSON.parse(raw) : raw;
                                if (typeof data === 'string') data = JSON.parse(data);
                                for (var k in data) {
                                    if (Object.prototype.hasOwnProperty.call(data, k) && !window.localStorage.getItem(k)) {
                                        window.localStorage.setItem(k, data[k]);
                                    }
                                }
                            } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkAndUpdateSymbol(url, view?.title)
            }
        }

        val fullChartUrl = getTradingViewUrlForSymbol(currentRawSymbol)
        webViewChart.loadUrl(fullChartUrl)
    }

    private fun extractAndSaveTradingViewState(onExtracted: ((String) -> Unit)? = null) {
        if (!::webViewChart.isInitialized) {
            onExtracted?.invoke("")
            return
        }
        val script = "(function(){ try { var res = {}; for (var i=0; i<window.localStorage.length; i++) { var k = window.localStorage.key(i); res[k] = window.localStorage.getItem(k); } return JSON.stringify(res); } catch(e) { return ''; } })()"
        webViewChart.evaluateJavascript(script) { value ->
            var jsonStr = value ?: ""
            if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"") && jsonStr.length > 1) {
                try {
                    val tok = org.json.JSONTokener(jsonStr).nextValue()
                    jsonStr = tok.toString()
                } catch (e: Exception) {
                    jsonStr = value
                }
            }
            if (jsonStr.isNotBlank() && jsonStr != "\"\"" && jsonStr != "null") {
                val prefs = getSharedPreferences("aetherdex_prefs", MODE_PRIVATE)
                prefs.edit().putString("tradingview_local_storage_json", jsonStr).apply()
                FirebaseSyncManager.syncTradingViewStateToFirestore(
                    context = this@MainActivity,
                    tradingviewState = jsonStr,
                    accountId = getUserAccountId()
                )
            }
            onExtracted?.invoke(jsonStr)
        }
    }

    private fun injectTradingViewState(tradingviewStateJson: String, onComplete: (() -> Unit)? = null) {
        if (!::webViewChart.isInitialized || tradingviewStateJson.isBlank()) {
            onComplete?.invoke()
            return
        }
        try {
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val escapedJson = org.json.JSONObject.quote(tradingviewStateJson)
        val js = """
            (function() {
                try {
                    var raw = $escapedJson;
                    var data = (typeof raw === 'string') ? JSON.parse(raw) : raw;
                    if (typeof data === 'string') data = JSON.parse(data);
                    for (var k in data) {
                        if (Object.prototype.hasOwnProperty.call(data, k)) {
                            window.localStorage.setItem(k, data[k]);
                        }
                    }
                    return "SUCCESS";
                } catch(e) {
                    return "ERROR: " + e.message;
                }
            })();
        """.trimIndent()

        webViewChart.evaluateJavascript(js) { _ ->
            try {
                android.webkit.CookieManager.getInstance().flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val fullChartUrl = "https://www.tradingview.com/chart/?symbol=BINANCE%3A$currentRawSymbol"
            webViewChart.loadUrl(fullChartUrl)
            onComplete?.invoke()
        }
    }

    private fun updateTradingEnvironmentUi() {
        val badgeVisibility = if (isDemoModeActive) View.VISIBLE else View.GONE
        tvDemoModeBadge?.visibility = badgeVisibility
        tvWalletDemoBadge?.visibility = badgeVisibility

        if (isDemoModeActive) {
            updateDemoBalanceUi()
        } else {
            fetchOnChainBalance()
            updateDemoBalanceUi() // Refresh to show realOnChainBalance immediately
        }
        updateEmbeddedVaultUi()
        updateResetDemoButtonVisibility()
        updatePositionsListUi()
        updateHistoryListUi()
    }

    private fun updateResetDemoButtonVisibility() {
        btnResetDemoBalance?.visibility = if (isDemoModeActive) View.VISIBLE else View.GONE
    }

    private fun updateEmbeddedVaultUi() {
        if (!::tvWalletVaultAddress.isInitialized) return
        val addr = EmbeddedWalletManager.getAddress(this)
        val shortAddr = if (addr.length > 12) "${addr.take(6)}...${addr.takeLast(4)}" else addr
        tvWalletVaultAddress.text = shortAddr

        val backedUp = EmbeddedWalletManager.isBackupVerified(this)
        tvWalletBackupStatusBadge.text = if (backedUp) "Backed Up" else "Unbacked"
        if (backedUp) {
            tvWalletBackupStatusBadge.setBackgroundResource(R.drawable.bg_chip_selected_green)
            tvWalletBackupStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
        } else {
            tvWalletBackupStatusBadge.setBackgroundResource(R.drawable.bg_chip_unselected)
            tvWalletBackupStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.brand_red))
        }

        val currentBal = if (isDemoModeActive) demoBalance else realOnChainBalance
        if (::tvWalletTotalBalance.isInitialized) {
            tvWalletTotalBalance.text = String.format(java.util.Locale.US, "$%.2f", currentBal)
        }

        findViewById<TextView>(R.id.tvWalletAssetBalanceUsdt)?.text = String.format(java.util.Locale.US, "%.2f USDC", currentBal)
        findViewById<TextView>(R.id.tvWalletAssetValueUsdt)?.text = String.format(java.util.Locale.US, "$%.2f", currentBal)
    }

    private fun fetchOnChainBalance() {
        lifecycleScope.launch {
            val balances = withContext(Dispatchers.IO) {
                val evmAddr = EmbeddedWalletManager.getActiveEvmAddress(this@MainActivity)

                // Fetch USDC across multi-chain (BNB Smart Chain, Arbitrum, Polygon, Ethereum + Hyperliquid)
                val usdtBsc = MultiChainAddressDeriver.fetchErc20Balance("https://bsc-dataseed.binance.org", "0x55d398326f99059fF775485246999027B3197955", evmAddr, 18)
                val usdtArb = MultiChainAddressDeriver.fetchErc20Balance("https://arb1.arbitrum.io/rpc", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", evmAddr, 6)
                val usdtPoly = MultiChainAddressDeriver.fetchErc20Balance("https://polygon-rpc.com", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", evmAddr, 6)
                val usdtEth = MultiChainAddressDeriver.fetchErc20Balance("https://cloudflare-eth.com", "0xdAC17F958D2ee523a2206206994597C13D831ec7", evmAddr, 6)
                val usdtHl = EmbeddedWalletManager.fetchOnChainBalance(this@MainActivity)

                val usdtSum = usdtBsc + usdtArb + usdtPoly + usdtEth + usdtHl

                // Fetch USDC across multi-chain (Arbitrum, BNB Chain, Polygon, Ethereum)
                val usdcArb = MultiChainAddressDeriver.fetchErc20Balance("https://arb1.arbitrum.io/rpc", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", evmAddr, 6)
                val usdcBsc = MultiChainAddressDeriver.fetchErc20Balance("https://bsc-dataseed.binance.org", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", evmAddr, 18)
                val usdcPoly = MultiChainAddressDeriver.fetchErc20Balance("https://polygon-rpc.com", "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359", evmAddr, 6)
                val usdcEth = MultiChainAddressDeriver.fetchErc20Balance("https://cloudflare-eth.com", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", evmAddr, 6)

                val usdcSum = usdcArb + usdcBsc + usdcPoly + usdcEth

                // Fetch Native ETH across EVM chains
                val ethMain = MultiChainAddressDeriver.fetchEvmNativeBalance("https://cloudflare-eth.com", evmAddr)
                val ethArb = MultiChainAddressDeriver.fetchEvmNativeBalance("https://arb1.arbitrum.io/rpc", evmAddr)

                val ethSum = ethMain + ethArb

                val bnbBal = MultiChainAddressDeriver.fetchEvmNativeBalance("https://bsc-dataseed.binance.org", evmAddr)

                listOf(usdtSum, usdcSum, ethSum, bnbBal)
            }
            val usdtTotal = (balances[0] as Double)
            val usdcTotal = (balances[1] as Double)
            val ethTotal = (balances[2] as Double)
            val bnbTotal = (balances[3] as Double)

            realOnChainBalance = usdtTotal
            val ethPrice = 2600.0
            val bnbPrice = 580.0
            val totalUsdValue = usdtTotal + usdcTotal + (ethTotal * ethPrice) + (bnbTotal * bnbPrice)

            if (isDemoModeActive) {
                if (totalUsdValue > 0.0) {
                    demoBalance = totalUsdValue
                    updateDemoBalanceUi()
                }
            } else {
                updateEmbeddedVaultUi()
                if (::tvWalletTotalBalance.isInitialized) {
                    tvWalletTotalBalance.text = String.format(java.util.Locale.US, "$%.2f", totalUsdValue)
                }
                findViewById<TextView>(R.id.tvWalletAssetBalanceUsdt)?.text = String.format(java.util.Locale.US, "%.2f USDC", usdtTotal)
                findViewById<TextView>(R.id.tvWalletAssetValueUsdt)?.text = String.format(java.util.Locale.US, "$%.2f", usdtTotal)

                findViewById<TextView>(R.id.tvWalletAssetBalanceUsdc)?.text = String.format(java.util.Locale.US, "%.2f USDC", usdcTotal)
                findViewById<TextView>(R.id.tvWalletAssetValueUsdc)?.text = String.format(java.util.Locale.US, "$%.2f", usdcTotal)

                val ethBalStr = if (ethTotal > 0.0 && ethTotal < 0.0001) {
                    String.format(java.util.Locale.US, "%.8f ETH", ethTotal).trimEnd('0').trimEnd('.')
                } else {
                    String.format(java.util.Locale.US, "%.4f ETH", ethTotal)
                }
                findViewById<TextView>(R.id.tvWalletAssetBalanceEth)?.text = ethBalStr
                findViewById<TextView>(R.id.tvWalletAssetValueEth)?.text = String.format(java.util.Locale.US, "$%.2f", ethTotal * ethPrice)

                val bnbBalStr = if (bnbTotal > 0.0 && bnbTotal < 0.0001) {
                    String.format(java.util.Locale.US, "%.8f BNB", bnbTotal).trimEnd('0').trimEnd('.')
                } else {
                    String.format(java.util.Locale.US, "%.4f BNB", bnbTotal)
                }
                findViewById<TextView>(R.id.tvWalletAssetBalanceBnb)?.text = bnbBalStr
                findViewById<TextView>(R.id.tvWalletAssetValueBnb)?.text = String.format(java.util.Locale.US, "$%.2f", bnbTotal * bnbPrice)
            }

            if (::swipeRefreshWallet.isInitialized) {
                swipeRefreshWallet.isRefreshing = false
            }
        }
    }

    private fun showDepositDialog() {
        startActivity(Intent(this, ReceiveActivity::class.java).apply {
            putExtra("EXTRA_MODE", "DEPOSIT")
        })
    }

    private fun showWithdrawDialog() {
        startActivity(Intent(this, ReceiveActivity::class.java).apply {
            putExtra("EXTRA_MODE", "WITHDRAW")
        })
    }

    private fun showVaultManagementDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val red = ContextCompat.getColor(this, R.color.brand_red)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(this).apply {
            text = "Vault & Keys Management"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val descTv = TextView(this).apply {
            text = "Import an existing 12-word seed phrase or generate a fresh vault key pair."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (4 * density).toInt(), 0, (16 * density).toInt())
        }

        val btnImport = com.google.android.material.button.MaterialButton(this).apply {
            text = "Import Existing 12 Words"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (10 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                showImportMnemonicDialog()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
        }

        val btnReset = com.google.android.material.button.MaterialButton(this).apply {
            text = "Reset Vault / New Key"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = android.content.res.ColorStateList.valueOf(red)
            strokeWidth = (1 * density).toInt()
            setTextColor(red)
            cornerRadius = (10 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                showResetVaultWarningDialog()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
        }

        val btnClose = com.google.android.material.button.MaterialButton(this).apply {
            text = "Close"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (10 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt())
        }

        container.addView(titleTv)
        container.addView(descTv)
        container.addView(btnImport)
        container.addView(btnReset)
        container.addView(btnClose)

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun showImportMnemonicDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val red = ContextCompat.getColor(this, R.color.brand_red)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val scrollView = android.widget.ScrollView(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }
        scrollView.addView(container)

        val titleTv = TextView(this).apply {
            text = "🔐 Import 12-Word Seed Phrase"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val descTv = TextView(this).apply {
            text = "Enter your 12 BIP-39 recovery words separated by spaces. Each word must be from the official BIP-39 English wordlist."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
        }

        val etMnemonic = EditText(this).apply {
            hint = "word1 word2 word3 ... word12"
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setTextColor(textPrimary)
            setBackgroundResource(R.drawable.bg_input)
            val pIn = (12 * density).toInt()
            setPadding(pIn, pIn, pIn, pIn)
            minLines = 3
            gravity = android.view.Gravity.TOP
            textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        // Inline validation error label (hidden until validation fails)
        val tvValidationError = TextView(this).apply {
            textSize = 11f
            setTextColor(red)
            setPadding(0, (6 * density).toInt(), 0, 0)
            visibility = android.view.View.GONE
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        }

        val btnCancel = com.google.android.material.button.MaterialButton(this).apply {
            text = "Cancel"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val btnConfirm = com.google.android.material.button.MaterialButton(this).apply {
            text = "Import Vault"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (8 * density).toInt()
            setOnClickListener {
                val input = etMnemonic.text.toString().trim().lowercase()

                // Step 1: Strict BIP-39 wordlist validation
                val validationError = Bip39Utils.validateMnemonic(input)
                if (validationError != null) {
                    tvValidationError.text = "⚠️ Invalid Seed Phrase. $validationError"
                    tvValidationError.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }

                // Step 2: Attempt encrypted import
                tvValidationError.visibility = android.view.View.GONE
                val success = EmbeddedWalletManager.importWallet(this@MainActivity, input)
                if (success) {
                    updateEmbeddedVaultUi()
                    dialog.dismiss()
                    snackString("✅ Vault successfully imported & encrypted!")
                } else {
                    tvValidationError.text = "⚠️ Import failed. Please check your words and order."
                    tvValidationError.visibility = android.view.View.VISIBLE
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirm)

        container.addView(titleTv)
        container.addView(descTv)
        container.addView(etMnemonic)
        container.addView(tvValidationError)
        container.addView(btnRow)

        dialog.setContentView(scrollView)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun showResetVaultWarningDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val red = ContextCompat.getColor(this, R.color.brand_red)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }

        val titleTv = TextView(this).apply {
            text = "Reset Vault Key?"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(red)
        }

        val descTv = TextView(this).apply {
            text = "WARNING: Resetting your vault will PERMANENTLY ERASE your existing key pair. If you have not backed up your 12 words, your funds will be lost forever!"
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, (6 * density).toInt(), 0, (16 * density).toInt())
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnCancel = com.google.android.material.button.MaterialButton(this).apply {
            text = "Cancel"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val btnConfirmReset = com.google.android.material.button.MaterialButton(this).apply {
            text = "Reset Vault Key"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(red)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            cornerRadius = (8 * density).toInt()
            setOnClickListener {
                dialog.dismiss()
                authenticateAndExecuteVaultReset()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirmReset)

        container.addView(titleTv)
        container.addView(descTv)
        container.addView(btnRow)

        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun authenticateAndExecuteVaultReset() {
        val biometricManager = androidx.biometric.BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        val executeReset = {
            EmbeddedWalletManager.resetWallet(this)
            updateEmbeddedVaultUi()
            snackString("Fresh 12-Word Vault generated successfully.")
        }

        if (canAuthenticate == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = androidx.biometric.BiometricPrompt(
                this,
                executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        executeReset()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        snackString("Reset authorization required: $errString")
                    }
                }
            )

            val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm Vault Reset")
                .setSubtitle("Biometric authentication required to erase existing vault keys")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            prompt.authenticate(promptInfo)
        } else {
            executeReset()
        }
    }

    private fun authenticateAndShowSeedPhraseBackup() {
        val biometricManager = androidx.biometric.BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = androidx.biometric.BiometricPrompt(
                this,
                executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        showBackupSeedPhraseDialog()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        snackString("Authentication required: $errString")
                    }
                }
            )

            val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Biometric Authorization")
                .setSubtitle("Authenticate to view your 12-word seed phrase")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            prompt.authenticate(promptInfo)
        } else {
            showBackupSeedPhraseDialog()
        }
    }

    private fun showBackupSeedPhraseDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = resources.displayMetrics.density
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val red = ContextCompat.getColor(this, R.color.brand_red)
        val bgDark = ContextCompat.getColor(this, R.color.bg_elevated)

        val scrollView = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (20 * density).toInt()
            setPadding(p, p, p, p)
        }
        scrollView.addView(container)

        val titleTv = TextView(this).apply {
            text = "🔑 12-Word Seed Phrase Backup"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrimary)
        }

        val descTv = TextView(this).apply {
            text = "⛔ DO NOT share these 12 words with anyone. Anyone with this phrase can permanently steal your funds."
            textSize = 12f
            setTextColor(red)
            setPadding(0, (4 * density).toInt(), 0, (14 * density).toInt())
        }

        val words = EmbeddedWalletManager.getMnemonicWords(this) ?: listOf("mnemonic", "not", "generated",
            "please", "reset", "vault", "and", "try", "again", "now", "thank", "you")

        val wordsGrid = android.widget.GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (14 * density).toInt()) }
        }

        words.forEachIndexed { idx, word ->
            val chipTv = TextView(this).apply {
                text = "${idx + 1}. $word"
                textSize = 12f
                setTextColor(textPrimary)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_input)
                val pIn = (8 * density).toInt()
                setPadding(pIn, pIn, pIn, pIn)
                val params = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = android.widget.GridLayout.spec(idx % 3, 1f)
                    rowSpec = android.widget.GridLayout.spec(idx / 3)
                    setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                }
                layoutParams = params
            }
            wordsGrid.addView(chipTv)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        }

        val btnVerify = com.google.android.material.button.MaterialButton(this).apply {
            text = "I Have Written It Down ✓"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            setTextColor(ContextCompat.getColor(context, R.color.black))
            cornerRadius = (8 * density).toInt()
            setOnClickListener {
                // Dismiss seed dialog first, then open quiz
                dialog.dismiss()
                showSeedPhraseQuizDialog(words)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        val btnClose = com.google.android.material.button.MaterialButton(this).apply {
            text = "Close"
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgDark)
            setTextColor(textSecondary)
            strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.divider))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (8 * density).toInt()
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(btnVerify)
        btnRow.addView(btnClose)

        container.addView(titleTv)
        container.addView(descTv)
        container.addView(wordsGrid)
        container.addView(btnRow)

        dialog.setContentView(scrollView)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val marginPx = (24 * density).toInt()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (marginPx * 2),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // Enforce FLAG_SECURE to block screenshot & screen recording
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        dialog.setOnDismissListener {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
    }

    /**
     * Shows a 3-question quiz where the user must select the correct word for 3 random positions.
     * Only awards the "Backed Up" badge if all 3 answers are correct.
     */
    private fun showSeedPhraseQuizDialog(words: List<String>) {
        // Pick 3 distinct random positions (0-based) from the 12 words
        val allIndices = (0 until 12).toMutableList()
        allIndices.shuffle()
        val quizIndices = allIndices.take(3).sorted()   // e.g. [2, 6, 10]

        var currentQuestion = 0
        var correctAnswers = 0

        fun showQuestion(questionIndex: Int) {
            if (questionIndex >= quizIndices.size) {
                // All questions answered — check score
                if (correctAnswers == 3) {
                    EmbeddedWalletManager.setBackupVerified(this, true)
                    updateEmbeddedVaultUi()
                    snackString("✅ Seed phrase verified! Backup badge activated.")
                } else {
                    snackString("❌ Verification failed ($correctAnswers/3 correct). Please re-read your seed and try again.")
                }
                return
            }

            val wordIndex = quizIndices[questionIndex]   // 0-based
            val correctWord = words[wordIndex]
            val displayNum = wordIndex + 1               // 1-based for UI

            // Build 4 choices: correct + 3 random distractors from wordlist
            val distractors = Bip39Utils.WORD_LIST
                .filter { it != correctWord }
                .shuffled()
                .take(3)
            val choices = (listOf(correctWord) + distractors).shuffled()

            val quizDialog = Dialog(this)
            quizDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val density2 = resources.displayMetrics.density
            val tp = ContextCompat.getColor(this, R.color.text_primary)
            val ts = ContextCompat.getColor(this, R.color.text_secondary)
            val g = ContextCompat.getColor(this, R.color.brand_green)
            val r = ContextCompat.getColor(this, R.color.brand_red)
            val bg = ContextCompat.getColor(this, R.color.bg_elevated)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val p = (20 * density2).toInt()
                setPadding(p, p, p, p)
            }

            // Progress indicator
            val tvProgress = TextView(this).apply {
                text = "Verification Quiz — Question ${questionIndex + 1} of 3"
                textSize = 11f
                setTextColor(ts)
                setPadding(0, 0, 0, (10 * density2).toInt())
            }

            val tvQuestion = TextView(this).apply {
                text = "Select Word #$displayNum from your seed phrase:"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(tp)
                setPadding(0, 0, 0, (16 * density2).toInt())
            }

            // Feedback label (hidden until answer chosen)
            val tvFeedback = TextView(this).apply {
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (8 * density2).toInt(), 0, 0)
                visibility = android.view.View.GONE
            }

            container.addView(tvProgress)
            container.addView(tvQuestion)

            // Choice buttons — added directly to container so we can walk childCount
            choices.forEach { choice ->
                com.google.android.material.button.MaterialButton(this).apply {
                    text = choice
                    isAllCaps = false
                    backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
                    setTextColor(tp)
                    strokeColor = android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(context, R.color.divider))
                    strokeWidth = (1 * density2).toInt()
                    cornerRadius = (8 * density2).toInt()
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (8 * density2).toInt() }
                    setOnClickListener {
                        val isCorrect = (choice == correctWord)

                        // Disable every choice button immediately to prevent multiple taps
                        for (i in 0 until container.childCount) {
                            val v = container.getChildAt(i)
                            if (v is com.google.android.material.button.MaterialButton) {
                                v.isEnabled = false
                            }
                        }

                        if (isCorrect) {
                            correctAnswers++
                            backgroundTintList = android.content.res.ColorStateList.valueOf(g)
                            setTextColor(ContextCompat.getColor(context, R.color.black))
                            tvFeedback.text = "✅ Correct!"
                            tvFeedback.setTextColor(g)
                            tvFeedback.visibility = android.view.View.VISIBLE

                            // Auto-advance to next question after 1.0s
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                quizDialog.dismiss()
                                showQuestion(questionIndex + 1)
                            }, 1000)
                        } else {
                            // Paint ONLY the wrong selection red — do NOT highlight correct word or expose hints
                            backgroundTintList = android.content.res.ColorStateList.valueOf(r)
                            setTextColor(android.graphics.Color.WHITE)
                            tvFeedback.text = "❌ Incorrect word selected. Verification failed."
                            tvFeedback.setTextColor(r)
                            tvFeedback.visibility = android.view.View.VISIBLE

                            // Fail verification immediately — dismiss dialog and prompt user to restart
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                quizDialog.dismiss()
                                snackString("❌ Backup verification failed. Please re-read your seed and try again.")
                            }, 1200)
                        }
                    }
                    container.addView(this)
                }
            }

            container.addView(tvFeedback)

            quizDialog.setContentView(container)
            quizDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val marginPx = (24 * density2).toInt()
            quizDialog.window?.setLayout(
                resources.displayMetrics.widthPixels - (marginPx * 2),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Keep FLAG_SECURE on the quiz dialog too
            quizDialog.window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            quizDialog.setOnDismissListener {
                quizDialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            quizDialog.setCancelable(false)
            quizDialog.show()
        }

        showQuestion(0)
    }

    private fun updateChartResolution(resolution: String) {
        if (::webViewChart.isInitialized) {
            val tvUrl = getTradingViewUrlForSymbol(currentRawSymbol)
            val fullChartUrl = "$tvUrl&interval=$resolution"
            webViewChart.loadUrl(fullChartUrl)
        }
    }
}

data class Position(
    val id: String = java.util.UUID.randomUUID().toString(),
    val symbol: String = "BTC/USDC",
    val isLong: Boolean,
    val entryPrice: Double,
    val leverage: Int,
    val margin: Double,
    val tp: Double = 0.0,
    val sl: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val isDemo: Boolean = true
) {
    val positionSizeUsdc: Double get() = margin * leverage
    val positionSizeCrypto: Double get() = positionSizeUsdc / entryPrice
    val estLiquidationPrice: Double get() = if (isLong) {
        (entryPrice * (1.0 - (1.0 / leverage))).coerceAtLeast(0.0)
    } else {
        entryPrice * (1.0 + (1.0 / leverage))
    }

    fun calculatePnl(currentPrice: Double): Double {
        return if (isLong) {
            (currentPrice - entryPrice) * positionSizeCrypto
        } else {
            (entryPrice - currentPrice) * positionSizeCrypto
        }
    }

    fun calculatePnlPercent(currentPrice: Double): Double {
        val pnl = calculatePnl(currentPrice)
        return if (margin > 0) (pnl / margin) * 100.0 else 0.0
    }
}

fun Position.toJson(): org.json.JSONObject {
    return org.json.JSONObject().apply {
        put("id", id)
        put("symbol", symbol)
        put("isLong", isLong)
        put("entryPrice", entryPrice)
        put("leverage", leverage)
        put("margin", margin)
        put("tp", tp)
        put("sl", sl)
        put("timestamp", timestamp)
        put("isDemo", isDemo)
    }
}

fun parsePositionFromJson(json: org.json.JSONObject): Position {
    return Position(
        id = json.optString("id", java.util.UUID.randomUUID().toString()),
        symbol = json.optString("symbol", "BTC/USDC"),
        isLong = json.optBoolean("isLong", true),
        entryPrice = json.optDouble("entryPrice", 0.0),
        leverage = json.optInt("leverage", 10),
        margin = json.optDouble("margin", 0.0),
        tp = json.optDouble("tp", 0.0),
        sl = json.optDouble("sl", 0.0),
        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        isDemo = json.optBoolean("isDemo", true)
    )
}

data class ClosedPosition(
    val id: String = java.util.UUID.randomUUID().toString(),
    val symbol: String,
    val isLong: Boolean,
    val leverage: Int,
    val margin: Double,
    val positionSizeUsdc: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val realizedPnl: Double,
    val realizedPnlPercent: Double,
    val closeReason: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isDemo: Boolean = true
)

fun ClosedPosition.toJson(): org.json.JSONObject {
    return org.json.JSONObject().apply {
        put("id", id)
        put("symbol", symbol)
        put("isLong", isLong)
        put("leverage", leverage)
        put("margin", margin)
        put("positionSizeUsdc", positionSizeUsdc)
        put("entryPrice", entryPrice)
        put("exitPrice", exitPrice)
        put("realizedPnl", realizedPnl)
        put("realizedPnlPercent", realizedPnlPercent)
        put("closeReason", closeReason)
        put("timestamp", timestamp)
        put("isDemo", isDemo)
    }
}

fun parseClosedPositionFromJson(json: org.json.JSONObject): ClosedPosition {
    return ClosedPosition(
        id = json.optString("id", java.util.UUID.randomUUID().toString()),
        symbol = json.optString("symbol", "BTC/USDC"),
        isLong = json.optBoolean("isLong", true),
        leverage = json.optInt("leverage", 10),
        margin = json.optDouble("margin", 0.0),
        positionSizeUsdc = json.optDouble("positionSizeUsdc", 0.0),
        entryPrice = json.optDouble("entryPrice", 0.0),
        exitPrice = json.optDouble("exitPrice", 0.0),
        realizedPnl = json.optDouble("realizedPnl", 0.0),
        realizedPnlPercent = json.optDouble("realizedPnlPercent", 0.0),
        closeReason = json.optString("closeReason", "Manual Close"),
        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        isDemo = json.optBoolean("isDemo", true)
    )
}

data class UserProfile(
    val fullName: String = "",
    val email: String = "",
    val password: String = "",
    val accountId: String = "",
    val isLoggedIn: Boolean = false,
    val streetAddress: String = "",
    val postalCodeCity: String = "",
    val country: String = "Germany",
    val baseCurrency: String = "USD"
) {
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("fullName", fullName)
            put("email", email)
            put("password", password)
            put("accountId", accountId)
            put("isLoggedIn", isLoggedIn)
            put("streetAddress", streetAddress)
            put("postalCodeCity", postalCodeCity)
            put("country", country)
            put("baseCurrency", baseCurrency)
        }
    }
}

fun parseUserProfileFromJson(json: org.json.JSONObject): UserProfile {
    return UserProfile(
        fullName = json.optString("fullName", ""),
        email = json.optString("email", ""),
        password = json.optString("password", ""),
        accountId = json.optString("accountId", ""),
        isLoggedIn = json.optBoolean("isLoggedIn", false),
        streetAddress = json.optString("streetAddress", ""),
        postalCodeCity = json.optString("postalCodeCity", ""),
        country = json.optString("country", "Germany"),
        baseCurrency = json.optString("baseCurrency", "USD")
    )
}

data class PriceAlert(
    val id: String = java.util.UUID.randomUUID().toString(),
    val symbol: String,
    val targetPrice: Double,
    val isAbove: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

fun PriceAlert.toJson(): org.json.JSONObject {
    return org.json.JSONObject().apply {
        put("id", id)
        put("symbol", symbol)
        put("targetPrice", targetPrice)
        put("isAbove", isAbove)
        put("createdAt", createdAt)
    }
}

fun parsePriceAlertFromJson(json: org.json.JSONObject): PriceAlert {
    return PriceAlert(
        id = json.optString("id", java.util.UUID.randomUUID().toString()),
        symbol = json.optString("symbol", "BTC/USDC"),
        targetPrice = json.optDouble("targetPrice", 0.0),
        isAbove = json.optBoolean("isAbove", true),
        createdAt = json.optLong("createdAt", System.currentTimeMillis())
    )
}

data class Tuple4<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

class AssetDropdownAdapter(
    context: Context,
    private val allAssets: List<HyperliquidOrderManager.AssetInfo>
) : android.widget.ArrayAdapter<HyperliquidOrderManager.AssetInfo>(
    context,
    R.layout.item_asset_dropdown,
    ArrayList(allAssets)
), android.widget.Filterable {

    private var filteredAssets: List<HyperliquidOrderManager.AssetInfo> = ArrayList(allAssets)

    override fun getCount(): Int = filteredAssets.size
    override fun getItem(position: Int): HyperliquidOrderManager.AssetInfo? = filteredAssets.getOrNull(position)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: android.view.LayoutInflater.from(context)
            .inflate(R.layout.item_asset_dropdown, parent, false)

        val item = getItem(position) ?: return view

        val tvSymbol = view.findViewById<TextView>(R.id.tvDropdownSymbol)
        val tvLeverageBadge = view.findViewById<TextView>(R.id.tvDropdownLeverageBadge)
        val tvPrice = view.findViewById<TextView>(R.id.tvDropdownPrice)
        val tv24hChange = view.findViewById<TextView>(R.id.tvDropdown24hChange)

        tvSymbol?.text = item.displaySymbol
        tvLeverageBadge?.text = "${item.maxLeverage}x"

        if (item.markPrice > 0.0) {
            tvPrice?.text = if (item.markPrice >= 10.0) {
                String.format(Locale.US, "$%,.2f", item.markPrice)
            } else if (item.markPrice >= 1.0) {
                String.format(Locale.US, "$%,.4f", item.markPrice)
            } else {
                String.format(Locale.US, "$%,.6f", item.markPrice)
            }

            val pct = item.change24hPercent
            val sign = if (pct >= 0) "+" else ""
            tv24hChange?.text = String.format(Locale.US, "%s%.2f%%", sign, pct)
            val changeColor = if (pct >= 0) {
                ContextCompat.getColor(context, R.color.brand_green)
            } else {
                ContextCompat.getColor(context, R.color.brand_red)
            }
            tv24hChange?.setTextColor(changeColor)
            tvPrice?.visibility = View.VISIBLE
            tv24hChange?.visibility = View.VISIBLE
        } else {
            tvPrice?.visibility = View.GONE
            tv24hChange?.visibility = View.GONE
        }

        return view
    }

    override fun getFilter(): android.widget.Filter {
        return object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?): android.widget.Filter.FilterResults {
                val results = android.widget.Filter.FilterResults()
                if (constraint.isNullOrBlank()) {
                    results.values = allAssets
                    results.count = allAssets.size
                } else {
                    val query = constraint.toString().trim().lowercase()
                    val filtered = allAssets.filter {
                        it.name.lowercase().contains(query) ||
                        it.displaySymbol.lowercase().contains(query)
                    }
                    results.values = filtered
                    results.count = filtered.size
                }
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: android.widget.Filter.FilterResults?) {
                filteredAssets = (results?.values as? List<HyperliquidOrderManager.AssetInfo>) ?: allAssets
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                return (resultValue as? HyperliquidOrderManager.AssetInfo)?.displaySymbol ?: super.convertResultToString(resultValue)
            }
        }
    }
}

class SpotDropdownAdapter(
    context: Context,
    private val allAssets: List<HyperliquidOrderManager.SpotAssetInfo>
) : android.widget.ArrayAdapter<HyperliquidOrderManager.SpotAssetInfo>(
    context,
    R.layout.item_asset_dropdown,
    ArrayList(allAssets)
), android.widget.Filterable {

    private var filteredAssets: List<HyperliquidOrderManager.SpotAssetInfo> = ArrayList(allAssets)

    override fun getCount(): Int = filteredAssets.size
    override fun getItem(position: Int): HyperliquidOrderManager.SpotAssetInfo? = filteredAssets.getOrNull(position)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: android.view.LayoutInflater.from(context)
            .inflate(R.layout.item_asset_dropdown, parent, false)

        val item = getItem(position) ?: return view

        val tvSymbol = view.findViewById<TextView>(R.id.tvDropdownSymbol)
        val tvLeverageBadge = view.findViewById<TextView>(R.id.tvDropdownLeverageBadge)
        val tvPrice = view.findViewById<TextView>(R.id.tvDropdownPrice)
        val tv24hChange = view.findViewById<TextView>(R.id.tvDropdown24hChange)

        tvSymbol?.text = item.displaySymbol
        tvLeverageBadge?.text = "SPOT"
        tvLeverageBadge?.setBackgroundResource(R.drawable.bg_chip_selected_green)

        if (item.markPrice > 0.0) {
            tvPrice?.text = if (item.markPrice >= 10.0) {
                String.format(Locale.US, "$%,.2f", item.markPrice)
            } else if (item.markPrice >= 1.0) {
                String.format(Locale.US, "$%,.4f", item.markPrice)
            } else {
                String.format(Locale.US, "$%,.6f", item.markPrice)
            }

            val pct = item.change24hPercent
            val sign = if (pct >= 0) "+" else ""
            tv24hChange?.text = String.format(Locale.US, "%s%.2f%%", sign, pct)
            val changeColor = if (pct >= 0) {
                ContextCompat.getColor(context, R.color.brand_green)
            } else {
                ContextCompat.getColor(context, R.color.brand_red)
            }
            tv24hChange?.setTextColor(changeColor)
            tvPrice?.visibility = View.VISIBLE
            tv24hChange?.visibility = View.VISIBLE
        } else {
            tvPrice?.visibility = View.GONE
            tv24hChange?.visibility = View.GONE
        }

        return view
    }

    override fun getFilter(): android.widget.Filter {
        return object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?): android.widget.Filter.FilterResults {
                val results = android.widget.Filter.FilterResults()
                if (constraint.isNullOrBlank()) {
                    results.values = allAssets
                    results.count = allAssets.size
                } else {
                    val query = constraint.toString().trim().lowercase()
                    val filtered = allAssets.filter {
                        it.name.lowercase().contains(query) ||
                        it.displaySymbol.lowercase().contains(query)
                    }
                    results.values = filtered
                    results.count = filtered.size
                }
                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: android.widget.Filter.FilterResults?) {
                filteredAssets = (results?.values as? List<HyperliquidOrderManager.SpotAssetInfo>) ?: allAssets
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                return (resultValue as? HyperliquidOrderManager.SpotAssetInfo)?.displaySymbol ?: super.convertResultToString(resultValue)
            }
        }
    }
}


