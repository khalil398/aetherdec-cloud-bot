package com.aetherdex.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hyperliquid-Style Deposit & Withdraw Modal Activity.
 *
 * Supports:
 *   - Top Mode Switcher: [ 📥 Deposit ] | [ 📤 Withdraw ]
 *   - Dual Inline Selectors: [ Asset Dropdown ] | [ Network Dropdown ]
 *   - Deposit View: Title, ZXing QR Code with Asset logo, Monospace Address + Copy,
 *                   Live Details Box (Receive ratio, Fee, Processing time), Warning Banner
 *   - Withdraw View: Title, Recipient Address field + Paste, Amount field + MAX + USD valuation,
 *                    Live Fee Box (Network Fee, Est. Time, Net Payout), Dynamic Action Button
 */
class ReceiveActivity : AppCompatActivity() {

    enum class Mode { DEPOSIT, WITHDRAW }

    private var currentMode = Mode.DEPOSIT
    private var currentAsset = MultiChainAddressDeriver.AssetToken.USDC
    private var currentNetwork = MultiChainAddressDeriver.Network.ARBITRUM
    private var currentAccountIndex = 0
    private var currentAddress = ""
    private var currentAvailableBalance = 0.0
    private var isHyperliquidDeposit = false

    // Hyperliquid Bridge2 Contract Address
    private val HYPERLIQUID_BRIDGE2_ADDRESS = "0x2Df1c51E09aECF9cacB7bc98cB1742757f163dF7"

    // UI elements
    private lateinit var btnTabDeposit: TextView
    private lateinit var btnTabWithdraw: TextView
    private lateinit var tvAssetChip: TextView
    private lateinit var tvNetworkChip: TextView

    // Deposit containers
    private lateinit var depositContainer: LinearLayout
    private lateinit var tvDepositTitle: TextView
    private lateinit var ivQr: ImageView
    private lateinit var qrProgress: ProgressBar
    private lateinit var qrCard: FrameLayout
    private lateinit var tvAddress: TextView
    private lateinit var tvRecvRatio: TextView
    private lateinit var tvDepFee: TextView
    private lateinit var tvDepTime: TextView
    private lateinit var tvDepWarning: TextView

    // Withdraw containers
    private lateinit var withdrawContainer: LinearLayout
    private lateinit var tvWithdrawTitle: TextView
    private lateinit var etRecipient: EditText
    private lateinit var etAmount: EditText
    private lateinit var tvUsdValuation: TextView
    private lateinit var tvAvailBal: TextView
    private lateinit var tvWdFee: TextView
    private lateinit var tvWdTime: TextView
    private lateinit var tvNetPayout: TextView
    private lateinit var tvWdGasError: TextView
    private lateinit var btnWithdrawAction: MaterialButton
    private var currentNativeGasBalance: Double = 0.0
    private var currentEstimatedFeeNative: Double = 0.0
    private var currentNativeSymbol: String = "ETH"

    private val bgMain   get() = Color.parseColor("#0B0E14")
    private val bgCard   get() = Color.parseColor("#151A24")
    private val bgChip   get() = Color.parseColor("#1E2638")
    private val green    get() = Color.parseColor("#00C896")
    private val red      get() = Color.parseColor("#FF4D4D")
    private val amber    get() = Color.parseColor("#F59E0B")
    private val textPrim get() = Color.parseColor("#E8EAF0")
    private val textSec  get() = Color.parseColor("#8892A4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgMain
        window.navigationBarColor = bgMain

        currentAccountIndex = EmbeddedWalletManager.getActiveAccountIndex(this)

        // Read intent extras if mode or asset specified
        val modeExtra = intent.getStringExtra("EXTRA_MODE")
        if (modeExtra == "WITHDRAW") {
            currentMode = Mode.WITHDRAW
        }
        val assetExtra = intent.getStringExtra("EXTRA_ASSET")
        if (!assetExtra.isNull_or_empty()) {
            MultiChainAddressDeriver.AssetToken.entries.find { it.symbol.equals(assetExtra, ignoreCase = true) }?.let {
                currentAsset = it
            }
        }
        // Check if this is a Hyperliquid deposit
        isHyperliquidDeposit = intent.getBooleanExtra("EXTRA_HYPERLIQUID", false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        root.addView(buildToolbar())
        root.addView(buildTopModeTabs())
        // Only show inline selectors for non-Hyperliquid deposits
        if (!isHyperliquidDeposit) {
            root.addView(buildInlineSelectors())
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        depositContainer = buildDepositView()
        withdrawContainer = buildWithdrawView()

        content.addView(depositContainer)
        content.addView(withdrawContainer)
        scroll.addView(content)

        root.addView(scroll)
        setContentView(root)

        updateModeUI()
        deriveAddressFor(currentNetwork, currentAccountIndex)
        refreshLiveFees()
        fetchCurrentAvailableBalance()
        lifecycleScope.launch {
            MultiChainAddressDeriver.refreshAssetBalances(this@ReceiveActivity)
            fetchCurrentAvailableBalance()
        }

        // Update UI for Hyperliquid deposit if applicable
        if (isHyperliquidDeposit) {
            updateHyperliquidDepositUI()
        }
    }

    private fun String?.isNull_or_empty() = this == null || this.isEmpty()

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private fun buildToolbar(): View {
        val density = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bgMain)
            val p = (16 * density).toInt()
            setPadding(p, (12 * density).toInt(), p, (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val btnBack = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(textPrim)
            setPadding((8 * density).toInt(), 0, (20 * density).toInt(), 0)
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = "Transfer & Bridge"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
        }
        bar.addView(btnBack)
        bar.addView(tvTitle)
        return bar
    }

    // ── Mode Switcher Segmented Tabs ──────────────────────────────────────────

    private fun buildTopModeTabs(): View {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(bgChip, 12f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(20), dp(4), dp(20), dp(12))
            }
        }

        btnTabDeposit = TextView(this).apply {
            text = "📥 Deposit"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (currentMode != Mode.DEPOSIT) {
                    currentMode = Mode.DEPOSIT
                    updateModeUI()
                }
            }
        }

        btnTabWithdraw = TextView(this).apply {
            text = "📤 Withdraw"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (currentMode != Mode.WITHDRAW) {
                    currentMode = Mode.WITHDRAW
                    updateModeUI()
                }
            }
        }

        container.addView(btnTabDeposit)
        container.addView(btnTabWithdraw)
        return container
    }

    // ── Dual Inline Selectors Row (Asset Dropdown & Network Dropdown) ────────

    private fun buildInlineSelectors(): View {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(20), 0, dp(20), dp(16))
            }
        }

        // Left: Asset Selector Chip
        tvAssetChip = TextView(this).apply {
            text = "${currentAsset.iconLabel} ${currentAsset.symbol} ▼"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBg(bgChip, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { showAssetSelector() }
        }

        // Right: Network Selector Chip
        tvNetworkChip = TextView(this).apply {
            text = "● ${currentNetwork.displayName} ▼"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(currentNetwork.networkColor))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBg(bgChip, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showNetworkSelector() }
        }

        row.addView(tvAssetChip)
        row.addView(tvNetworkChip)
        return row
    }

    // ── Deposit View ──────────────────────────────────────────────────────────

    private fun buildDepositView(): LinearLayout {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), 0, dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        tvDepositTitle = TextView(this).apply {
            text = "Deposit ${currentAsset.symbol} from ${currentNetwork.displayName}"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        col.addView(tvDepositTitle)

        // QR Card
        val qrSize = (resources.displayMetrics.widthPixels * 0.68f).toInt()
        qrCard = FrameLayout(this).apply {
            background = roundedBg(Color.WHITE, 20f)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }
        ivQr = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        qrProgress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(green)
        }
        qrCard.addView(ivQr)
        qrCard.addView(qrProgress)
        col.addView(qrCard)

        // Monospace Address Card with Copy Button
        val addrCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(bgCard, 12f)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        tvAddress = TextView(this).apply {
            text = "Deriving address…"
            textSize = 12f
            setTextColor(green)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnCopyAddress = TextView(this).apply {
            text = "📋 Copy"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBg(bgChip, 8f)
            setOnClickListener {
                if (currentAddress.isNotBlank()) {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Deposit Address", currentAddress))
                    Toast.makeText(this@ReceiveActivity, "Address copied!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        addrCard.addView(tvAddress)
        addrCard.addView(btnCopyAddress)
        col.addView(addrCard)

        // Fees are paid by the sender on deposit, so details card is removed.
        tvRecvRatio = TextView(this)
        tvDepFee    = TextView(this)
        tvDepTime   = TextView(this)

        // Warning Banner
        tvDepWarning = TextView(this).apply {
            text = "⚠️ Minimum deposit: ${currentNetwork.minDepositStr}. ${currentNetwork.warningText}"
            textSize = 11f
            setTextColor(amber)
            background = roundedBg(Color.parseColor("#1F1700"), 10f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        col.addView(tvDepWarning)

        return col
    }

    // ── Hyperliquid Deposit UI Update ───────────────────────────────────────────

    private fun updateHyperliquidDepositUI() {
        // Update title
        tvDepositTitle.text = "Deposit USDC to Hyperliquid L1"

        // Update warning with Hyperliquid-specific messages
        tvDepWarning.text = "⚠️ Deposit USDC on Arbitrum One to Bridge2 contract. Do NOT send USDT - it will be stuck and cannot be recovered."
    }

    // ── Withdraw View ─────────────────────────────────────────────────────────

    private fun buildWithdrawView(): LinearLayout {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        tvWithdrawTitle = TextView(this).apply {
            text = "Withdraw ${currentAsset.symbol} on ${currentNetwork.displayName}"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        col.addView(tvWithdrawTitle)

        // Recipient Address Field
        val lblAddr = TextView(this).apply {
            text = "Recipient Address"
            textSize = 12f
            setTextColor(textSec)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        col.addView(lblAddr)

        val addrInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(bgCard, 12f)
            val p = dp(10)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }

        etRecipient = EditText(this).apply {
            hint = "Enter recipient ${currentNetwork.displayName} address"
            setHintTextColor(Color.parseColor("#555F73"))
            setTextColor(textPrim)
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateWithdrawState()
                    refreshLiveFees()
                }
            })
        }
        val btnPaste = TextView(this).apply {
            text = "Paste"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(green)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = roundedBg(bgChip, 8f)
            setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cb.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val pasted = clip.getItemAt(0).text.toString().trim()
                    etRecipient.setText(pasted)
                }
            }
        }
        addrInputRow.addView(etRecipient)
        addrInputRow.addView(btnPaste)
        col.addView(addrInputRow)

        // Amount Input Field with MAX Button & USD Valuation
        val amountHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        val lblAmt = TextView(this).apply {
            text = "Amount"
            textSize = 12f
            setTextColor(textSec)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        tvAvailBal = TextView(this).apply {
            text = "Available: 0.00 %s".format(currentAsset.symbol)
            textSize = 12f
            setTextColor(textSec)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        amountHeaderRow.addView(lblAmt)
        amountHeaderRow.addView(tvAvailBal)
        col.addView(amountHeaderRow)

        val amtInputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgCard, 12f)
            val p = dp(12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }

        val amtRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        etAmount = EditText(this).apply {
            hint = "0.00"
            setHintTextColor(Color.parseColor("#555F73"))
            setTextColor(textPrim)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateWithdrawState()
                    refreshLiveFees()
                }
            })
        }

        val btnMax = TextView(this).apply {
            text = "MAX"
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(green)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBg(bgChip, 8f)
            setOnClickListener {
                val nativeSymbol = currentNativeSymbol
                val isNativeAsset = (currentAsset.symbol.equals(nativeSymbol, ignoreCase = true) ||
                                    (currentAsset == MultiChainAddressDeriver.AssetToken.ETH && (currentNetwork == MultiChainAddressDeriver.Network.ETHEREUM || currentNetwork == MultiChainAddressDeriver.Network.ARBITRUM)) ||
                                    (currentAsset == MultiChainAddressDeriver.AssetToken.BNB && currentNetwork == MultiChainAddressDeriver.Network.BNB))

                val gasFee = if (currentEstimatedFeeNative > 0.0) currentEstimatedFeeNative else 0.0000105
                val sendable = if (isNativeAsset) {
                    if (currentAvailableBalance > gasFee) {
                        currentAvailableBalance - gasFee
                    } else {
                        0.0
                    }
                } else {
                    currentAvailableBalance
                }

                if (sendable > 0) {
                    val bd = java.math.BigDecimal.valueOf(sendable).setScale(6, java.math.RoundingMode.DOWN)
                    val formatted = bd.stripTrailingZeros().toPlainString()
                    etAmount.setText(formatted)
                } else {
                    etAmount.setText("0.00")
                }
                updateWithdrawState()
                refreshLiveFees()
            }
        }

        amtRow.addView(etAmount)
        amtRow.addView(btnMax)
        amtInputCard.addView(amtRow)

        tvUsdValuation = TextView(this).apply {
            text = "≈ $0.00 USD"
            textSize = 12f
            setTextColor(textSec)
            setPadding(0, dp(4), 0, 0)
        }
        amtInputCard.addView(tvUsdValuation)
        col.addView(amtInputCard)

        // Live Fee Box (Network Fee, Estimated Time, Net Payout)
        val wdFeeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgCard, 14f)
            val p = dp(14)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }

        fun makeWdDetailRow(label: String, initVal: String): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
            }
            val l = TextView(this).apply { text = label; textSize = 12f; setTextColor(textSec) }
            val v = TextView(this).apply {
                text = initVal; textSize = 12f; setTextColor(textPrim)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(l); row.addView(v)
            wdFeeCard.addView(row)
            return v
        }

        tvWdFee     = makeWdDetailRow("Network Fee", currentNetwork.estFeeUsd)
        tvWdTime    = makeWdDetailRow("Est. Processing Time", currentNetwork.estTimeStr)
        tvNetPayout = makeWdDetailRow("Net Payout", "0.00 ${currentAsset.symbol}")

        col.addView(wdFeeCard)

        // Gas Fee Error Banner
        tvWdGasError = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FF6B6B"))
            background = roundedBg(Color.parseColor("#2D1619"), 10f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        col.addView(tvWdGasError)

        // Dynamic Action Button
        btnWithdrawAction = MaterialButton(this).apply {
            text = "Enter a Recipient"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.BLACK)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2A3447"))
            cornerRadius = dp(14)
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54))
            setOnClickListener { showReviewSendDialog() }
        }
        col.addView(btnWithdrawAction)

        return col
    }

    // ── Asset Selector Dialog ─────────────────────────────────────────────────

    private fun showAssetSelector() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCard)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgCard)
                cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        val title = TextView(this).apply {
            text = "Select Asset"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }
        root.addView(title)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        MultiChainAddressDeriver.AssetToken.entries.forEach { asset ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = dp(12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
                background = if (asset == currentAsset) roundedBg(Color.parseColor("#1A2A1A"), 12f)
                             else roundedBg(bgChip, 12f)
                setOnClickListener {
                    dialog.dismiss()
                    if (asset != currentAsset) {
                        currentAsset = asset
                        updateAssetUI()
                    }
                }
            }

            val icon = TextView(this).apply {
                text = asset.iconLabel
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            }

            val colInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = "${asset.symbol} - ${asset.displayName}"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textPrim)
            }
            val tvBal = TextView(this).apply {
                val cachedBal = MultiChainAddressDeriver.getCachedBalance(asset)
                text = "Balance: " + formatBalanceDisplay(cachedBal, asset.symbol)
                textSize = 12f
                setTextColor(textSec)
            }
            colInfo.addView(tvName); colInfo.addView(tvBal)

            val check = TextView(this).apply {
                text = if (asset == currentAsset) "✓" else ""
                setTextColor(green); textSize = 16f
            }

            row.addView(icon); row.addView(colInfo); row.addView(check)
            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    // ── Network Selector Dialog ───────────────────────────────────────────────

    private fun showNetworkSelector() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCard)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgCard)
                cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        val title = TextView(this).apply {
            text = "Select Network"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }
        root.addView(title)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val validNets = MultiChainAddressDeriver.getValidNetworksForToken(currentAsset)

        MultiChainAddressDeriver.Network.entries.forEach { network ->
            val isValid = network in validNets
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = dp(12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
                alpha = if (isValid) 1.0f else 0.4f
                background = if (network == currentNetwork) roundedBg(Color.parseColor("#1A2A1A"), 12f)
                             else roundedBg(bgChip, 12f)
                setOnClickListener {
                    if (isValid) {
                        dialog.dismiss()
                        if (network != currentNetwork) {
                            currentNetwork = network
                            updateNetworkUI()
                        }
                    } else {
                        Toast.makeText(this@ReceiveActivity, "${network.displayName} is not supported for ${currentAsset.symbol}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val logo = TextView(this).apply {
                text = network.symbol.take(3)
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(network.networkColor))
                }
            }

            val colInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                text = network.displayName
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textPrim)
            }
            val tvSub = TextView(this).apply {
                text = if (isValid) "Fee: ${network.estFeeUsd} • ${network.estTimeStr}" else "Not supported for ${currentAsset.symbol}"
                textSize = 12f
                setTextColor(if (isValid) textSec else red)
            }
            colInfo.addView(tvName); colInfo.addView(tvSub)

            val check = TextView(this).apply {
                text = if (network == currentNetwork) "✓" else if (!isValid) "🚫" else ""
                setTextColor(if (network == currentNetwork) green else red); textSize = 16f
            }

            row.addView(logo); row.addView(colInfo); row.addView(check)
            list.addView(row)
        }

        scroll.addView(list)
        root.addView(scroll)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    // ── UI State Updating Helpers ─────────────────────────────────────────────

    private fun updateModeUI() {
        if (currentMode == Mode.DEPOSIT) {
            btnTabDeposit.background = roundedBg(green, 10f)
            btnTabDeposit.setTextColor(Color.BLACK)
            btnTabWithdraw.background = null
            btnTabWithdraw.setTextColor(textSec)
            depositContainer.visibility = View.VISIBLE
            withdrawContainer.visibility = View.GONE
        } else {
            btnTabWithdraw.background = roundedBg(green, 10f)
            btnTabWithdraw.setTextColor(Color.BLACK)
            btnTabDeposit.background = null
            btnTabDeposit.setTextColor(textSec)
            depositContainer.visibility = View.GONE
            withdrawContainer.visibility = View.VISIBLE
        }
        updateWithdrawState()
    }

    private fun updateAssetUI() {
        val validNets = MultiChainAddressDeriver.getValidNetworksForToken(currentAsset)
        if (currentNetwork !in validNets) {
            currentNetwork = validNets.first()
        }
        tvAssetChip.text = "${currentAsset.iconLabel} ${currentAsset.symbol} ▼"
        tvNetworkChip.text = "● ${currentNetwork.displayName} ▼"
        tvNetworkChip.setTextColor(Color.parseColor(currentNetwork.networkColor))
        tvDepositTitle.text = "Deposit ${currentAsset.symbol} from ${currentNetwork.displayName}"
        tvWithdrawTitle.text = "Withdraw ${currentAsset.symbol} on ${currentNetwork.displayName}"
        tvRecvRatio.text = "1:1 ${currentAsset.symbol}"
        tvAvailBal.text = "Available: " + formatBalanceDisplay(currentAvailableBalance, currentAsset.symbol)
        deriveAddressFor(currentNetwork, currentAccountIndex)
        fetchCurrentAvailableBalance()
        refreshLiveFees()
        updateWithdrawState()
    }

    private fun updateNetworkUI() {
        tvNetworkChip.text = "● ${currentNetwork.displayName} ▼"
        tvNetworkChip.setTextColor(Color.parseColor(currentNetwork.networkColor))
        tvDepositTitle.text = "Deposit ${currentAsset.symbol} from ${currentNetwork.displayName}"
        tvWithdrawTitle.text = "Withdraw ${currentAsset.symbol} on ${currentNetwork.displayName}"
        // Show fallback values immediately while live fetch is in flight
        tvDepFee.text = currentNetwork.estFeeUsd
        tvDepTime.text = currentNetwork.estTimeStr
        tvDepWarning.text = "⚠️ Minimum deposit: ${currentNetwork.minDepositStr}. ${currentNetwork.warningText}"
        tvWdFee.text = currentNetwork.estFeeUsd
        tvWdTime.text = currentNetwork.estTimeStr
        if (::etRecipient.isInitialized) {
            etRecipient.hint = "Enter recipient ${currentNetwork.displayName} address"
        }
        deriveAddressFor(currentNetwork, currentAccountIndex)
        fetchCurrentAvailableBalance()
        refreshLiveFees()
        updateWithdrawState()
    }

    /**
     * Fetches real-time fees for [currentNetwork] on a background thread and updates
     * all fee / processing-time / warning text views on the UI thread.
     * Shows static fallback values while the request is in flight.
     */
    private fun formatBalanceDisplay(bal: Double, symbol: String): String {
        return if (bal > 0.0 && bal < 0.0001) {
            String.format(java.util.Locale.US, "%.8f %s", bal, symbol).trimEnd('0').trimEnd('.')
        } else {
            String.format(java.util.Locale.US, "%.4f %s", bal, symbol)
        }
    }

    private fun fetchCurrentAvailableBalance() {
        val cached = MultiChainAddressDeriver.getCachedBalance(currentAsset, currentNetwork)
        currentAvailableBalance = cached
        if (::tvAvailBal.isInitialized) {
            tvAvailBal.text = "Available: " + formatBalanceDisplay(cached, currentAsset.symbol)
        }
        val asset = currentAsset
        val network = currentNetwork
        lifecycleScope.launch {
            val (liveBal, liveGasBal) = withContext(Dispatchers.IO) {
                val b = MultiChainAddressDeriver.fetchSpecificNetworkBalance(this@ReceiveActivity, asset, network)
                val g = MultiChainAddressDeriver.fetchNativeGasBalance(this@ReceiveActivity, network)
                Pair(b, g)
            }
            if (asset == currentAsset && network == currentNetwork) {
                currentAvailableBalance = liveBal
                currentNativeGasBalance = liveGasBal
                if (::tvAvailBal.isInitialized) {
                    tvAvailBal.text = "Available: " + formatBalanceDisplay(liveBal, currentAsset.symbol)
                }
                updateWithdrawState()
            }
        }
    }

    private fun refreshLiveFees() {
        val network = currentNetwork   // capture snapshot so late result applies to same network
        val asset = currentAsset
        val recipient = if (::etRecipient.isInitialized) etRecipient.text.toString().trim() else ""
        val amount = if (::etAmount.isInitialized) etAmount.text.toString().trim().toDoubleOrNull() ?: 0.0 else 0.0

        lifecycleScope.launch {
            if (network.chainId != null) {
                val senderAddr = EmbeddedWalletManager.getActiveEvmAddress(this@ReceiveActivity)
                val gasEstimate = withContext(Dispatchers.IO) {
                    MultiChainAddressDeriver.estimateLiveEvmGasFee(network, asset, senderAddr, recipient, amount)
                }
                if (network == currentNetwork && asset == currentAsset) {
                    currentEstimatedFeeNative = gasEstimate.feeNative
                    currentNativeSymbol = gasEstimate.nativeSymbol
                    tvWdFee.text = gasEstimate.combinedStr
                    tvWdTime.text = network.estTimeStr
                    tvDepFee.text = gasEstimate.combinedStr
                    tvDepTime.text = network.estTimeStr
                    updateWithdrawState()
                }
            } else {
                val stats = withContext(Dispatchers.IO) {
                    MultiChainAddressDeriver.fetchLiveNetworkStats(network)
                }
                if (network == currentNetwork) {
                    currentEstimatedFeeNative = 0.0
                    tvWdFee.text     = stats.feeUsd
                    tvWdTime.text    = stats.timeStr
                    tvDepFee.text    = stats.feeUsd
                    tvDepTime.text   = stats.timeStr
                    tvDepWarning.text = "⚠️ Minimum deposit: ${stats.minDepositStr}. ${network.warningText}"
                    updateWithdrawState()
                }
            }
        }
    }

    private fun updateWithdrawState() {
        if (!::etRecipient.isInitialized || !::etAmount.isInitialized) return

        val recipient = etRecipient.text.toString().trim()
        val amtStr = etAmount.text.toString().trim()
        val amount = amtStr.toDoubleOrNull() ?: 0.0
        val usdVal = amount * currentAsset.priceUsd

        tvUsdValuation.text = "≈ $%.2f USD".format(usdVal)

        val nativeSymbol = currentNativeSymbol
        val isNativeAsset = (currentAsset.symbol.equals(nativeSymbol, ignoreCase = true) ||
                            (currentAsset == MultiChainAddressDeriver.AssetToken.ETH && (currentNetwork == MultiChainAddressDeriver.Network.ETHEREUM || currentNetwork == MultiChainAddressDeriver.Network.ARBITRUM)) ||
                            (currentAsset == MultiChainAddressDeriver.AssetToken.BNB && currentNetwork == MultiChainAddressDeriver.Network.BNB))

        val effectivePayout = if (isNativeAsset && amount >= (currentAvailableBalance - 0.0001) && currentAvailableBalance > currentEstimatedFeeNative) {
            currentAvailableBalance - currentEstimatedFeeNative
        } else {
            amount
        }
        tvNetPayout.text = if (effectivePayout > 0) "%.4f %s".format(effectivePayout, currentAsset.symbol) else "0.00 ${currentAsset.symbol}"

        // Use BigDecimal and epsilon comparison (0.00001 tolerance) to prevent floating-point rounding bugs
        val bdAmount = java.math.BigDecimal.valueOf(amount)
        val bdBalance = java.math.BigDecimal.valueOf(currentAvailableBalance)
        val bdGasFee = java.math.BigDecimal.valueOf(currentEstimatedFeeNative)
        val bdNativeGasBalance = java.math.BigDecimal.valueOf(currentNativeGasBalance)

        val diffToken = bdAmount.subtract(bdBalance).toDouble()
        val hasEnoughToken = diffToken <= 0.00001

        val hasEnoughGas = if (isNativeAsset) {
            val totalRequired = bdAmount.add(bdGasFee)
            val diffGas = totalRequired.subtract(bdNativeGasBalance).toDouble()
            diffGas <= 0.00001 || (amount >= (currentAvailableBalance - 0.0001) && currentNativeGasBalance >= currentEstimatedFeeNative)
        } else {
            val diffGas = bdGasFee.subtract(bdNativeGasBalance).toDouble()
            diffGas <= 0.00001
        }

        when {
            recipient.isEmpty() -> {
                if (::tvWdGasError.isInitialized) tvWdGasError.visibility = View.GONE
                btnWithdrawAction.text = "Enter a Recipient"
                btnWithdrawAction.isEnabled = false
                btnWithdrawAction.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2A3447"))
                btnWithdrawAction.setTextColor(textSec)
            }
            amount <= 0 -> {
                if (::tvWdGasError.isInitialized) tvWdGasError.visibility = View.GONE
                btnWithdrawAction.text = "Enter an Amount"
                btnWithdrawAction.isEnabled = false
                btnWithdrawAction.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2A3447"))
                btnWithdrawAction.setTextColor(textSec)
            }
            !hasEnoughToken -> {
                if (::tvWdGasError.isInitialized) tvWdGasError.visibility = View.GONE
                btnWithdrawAction.text = "Insufficient Balance"
                btnWithdrawAction.isEnabled = false
                btnWithdrawAction.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A1E1E"))
                btnWithdrawAction.setTextColor(red)
            }
            !hasEnoughGas -> {
                val reqStr = if (currentEstimatedFeeNative > 0) String.format(java.util.Locale.US, "%.6f %s", currentEstimatedFeeNative, nativeSymbol).trimEnd('0').trimEnd('.') else "gas"
                val availStr = String.format(java.util.Locale.US, "%.6f %s", currentNativeGasBalance, nativeSymbol).trimEnd('0').trimEnd('.')
                val errorMsg = if (currentNativeGasBalance <= 0.0) {
                    "⚠️ Insufficient $nativeSymbol for gas fees. Required $reqStr. You have 0 $nativeSymbol."
                } else {
                    "⚠️ Insufficient $nativeSymbol for gas fees. Required $reqStr. You have $availStr."
                }
                if (::tvWdGasError.isInitialized) {
                    tvWdGasError.text = errorMsg
                    tvWdGasError.visibility = View.VISIBLE
                }
                btnWithdrawAction.text = "Insufficient Gas Fee"
                btnWithdrawAction.isEnabled = false
                btnWithdrawAction.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4A1E1E"))
                btnWithdrawAction.setTextColor(red)
            }
            else -> {
                if (::tvWdGasError.isInitialized) tvWdGasError.visibility = View.GONE
                btnWithdrawAction.text = "Review Transfer"
                btnWithdrawAction.isEnabled = true
                btnWithdrawAction.backgroundTintList = android.content.res.ColorStateList.valueOf(green)
                btnWithdrawAction.setTextColor(Color.BLACK)
                btnWithdrawAction.setOnClickListener { showReviewSendDialog() }
            }
        }
    }

    private fun showReviewSendDialog() {
        if (!::etRecipient.isInitialized || !::etAmount.isInitialized) return

        val recipient = etRecipient.text.toString().trim()
        val amtStr = etAmount.text.toString().trim()
        val rawAmount = amtStr.toDoubleOrNull() ?: 0.0
        val senderAddr = EmbeddedWalletManager.getActiveEvmAddress(this)

        val nativeSymbol = currentNativeSymbol
        val isNativeAsset = (currentAsset.symbol.equals(nativeSymbol, ignoreCase = true) ||
                            (currentAsset == MultiChainAddressDeriver.AssetToken.ETH && (currentNetwork == MultiChainAddressDeriver.Network.ETHEREUM || currentNetwork == MultiChainAddressDeriver.Network.ARBITRUM)) ||
                            (currentAsset == MultiChainAddressDeriver.AssetToken.BNB && currentNetwork == MultiChainAddressDeriver.Network.BNB))

        val sendAmount = if (isNativeAsset && rawAmount >= currentAvailableBalance && currentAvailableBalance > currentEstimatedFeeNative) {
            currentAvailableBalance - currentEstimatedFeeNative
        } else {
            rawAmount
        }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCard)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgCard)
                cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        val title = TextView(this).apply {
            text = "Review Send"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }
        root.addView(title)

        // Amount Card
        val amtCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg(bgChip, 12f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }
        val tvAmtBig = TextView(this).apply {
            text = "%.4f %s".format(sendAmount, currentAsset.symbol)
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(green)
            gravity = Gravity.CENTER
        }
        val tvUsdSub = TextView(this).apply {
            text = "≈ $%.2f USD".format(sendAmount * currentAsset.priceUsd)
            textSize = 12f
            setTextColor(textSec)
            gravity = Gravity.CENTER
        }
        amtCard.addView(tvAmtBig)
        amtCard.addView(tvUsdSub)
        root.addView(amtCard)

        // Details Container
        val detailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgMain, 12f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }

        fun addReviewRow(label: String, value: String, valColor: Int = textPrim) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val l = TextView(this).apply { text = label; textSize = 12f; setTextColor(textSec) }
            val v = TextView(this).apply {
                text = value; textSize = 12f; setTextColor(valColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(l); row.addView(v)
            detailsContainer.addView(row)
        }

        val shortSender = if (senderAddr.length > 12) "${senderAddr.take(6)}...${senderAddr.takeLast(4)}" else senderAddr
        val shortRecip = if (recipient.length > 12) "${recipient.take(6)}...${recipient.takeLast(4)}" else recipient

        addReviewRow("From", shortSender)
        addReviewRow("To (Recipient)", shortRecip)
        addReviewRow("Network", currentNetwork.displayName)
        addReviewRow("Network Fee", tvWdFee.text.toString())
        addReviewRow("App / Platform Fee", "Free ($0.00)", green)
        addReviewRow("Total Payout", "%.4f %s".format(sendAmount, currentAsset.symbol))

        root.addView(detailsContainer)

        // Gas Fee Warning Banner if insufficient gas
        val hasEnoughGas = if (isNativeAsset) {
            val totalReq = java.math.BigDecimal.valueOf(sendAmount).add(java.math.BigDecimal.valueOf(currentEstimatedFeeNative))
            val diff = totalReq.subtract(java.math.BigDecimal.valueOf(currentNativeGasBalance)).toDouble()
            diff <= 0.00001 || (rawAmount >= (currentAvailableBalance - 0.0001) && currentNativeGasBalance >= currentEstimatedFeeNative)
        } else {
            val diff = java.math.BigDecimal.valueOf(currentEstimatedFeeNative).subtract(java.math.BigDecimal.valueOf(currentNativeGasBalance)).toDouble()
            diff <= 0.00001
        }

        if (!hasEnoughGas) {
            val reqStr = if (currentEstimatedFeeNative > 0) String.format(java.util.Locale.US, "%.6f %s", currentEstimatedFeeNative, nativeSymbol).trimEnd('0').trimEnd('.') else "gas"
            val availStr = String.format(java.util.Locale.US, "%.6f %s", currentNativeGasBalance, nativeSymbol).trimEnd('0').trimEnd('.')
            val errTv = TextView(this).apply {
                text = "⚠️ Insufficient $nativeSymbol for gas fee. Required $reqStr. You have $availStr."
                textSize = 12f
                setTextColor(Color.parseColor("#FF6B6B"))
                background = roundedBg(Color.parseColor("#2D1619"), 10f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(16)
                }
            }
            root.addView(errTv)
        }

        // Action Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }

        val btnCancel = MaterialButton(this).apply {
            text = "Back"
            isAllCaps = false
            textSize = 14f
            setTextColor(textPrim)
            backgroundTintList = android.content.res.ColorStateList.valueOf(bgChip)
            cornerRadius = dp(12)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { dialog.dismiss() }
        }

        val btnConfirm = MaterialButton(this).apply {
            text = if (hasEnoughGas) "Send Now" else "Insufficient Gas"
            isAllCaps = false
            textSize = 14f
            setTextColor(if (hasEnoughGas) Color.BLACK else red)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (hasEnoughGas) green else Color.parseColor("#4A1E1E"))
            cornerRadius = dp(12)
            isEnabled = hasEnoughGas
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                isEnabled = false
                text = "Broadcasting..."
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        MultiChainAddressDeriver.EvmTransactionSigner.sendRawEvmTransaction(
                            this@ReceiveActivity,
                            currentNetwork,
                            currentAsset,
                            recipient,
                            sendAmount
                        )
                    }
                    dialog.dismiss()
                    if (result.success && !result.txHash.isNullOrEmpty()) {
                        showSuccessTxDialog(result.txHash!!, sendAmount, recipient)
                    } else {
                        android.app.AlertDialog.Builder(this@ReceiveActivity)
                            .setTitle("Broadcast Failed")
                            .setMessage(result.error ?: "Transaction failed to broadcast")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnConfirm)
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    private fun showSuccessTxDialog(txHash: String, amount: Double, recipient: String) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bgCard)
            background = roundedBg(bgCard, 20f)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val iconSuccess = TextView(this).apply {
            text = "✅"
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }

        val title = TextView(this).apply {
            text = "Transaction Broadcasted!"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(textPrim)
            gravity = Gravity.CENTER
        }

        val desc = TextView(this).apply {
            text = "Successfully submitted %.4f %s to blockchain network.".format(amount, currentAsset.symbol)
            textSize = 13f
            setTextColor(textSec)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(16))
        }

        val hashBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgMain, 10f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(18)
            }
        }
        val hashLbl = TextView(this).apply { text = "Transaction Hash (TxHash):"; textSize = 11f; setTextColor(textSec) }
        val hashVal = TextView(this).apply {
            text = txHash
            textSize = 12f
            setTextColor(green)
            setTypeface(android.graphics.Typeface.MONOSPACE)
        }
        hashBox.addView(hashLbl)
        hashBox.addView(hashVal)

        val btnCopy = MaterialButton(this).apply {
            text = "Copy TxHash"
            isAllCaps = false
            setTextColor(Color.BLACK)
            backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            cornerRadius = dp(12)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
            setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("TxHash", txHash))
                Toast.makeText(this@ReceiveActivity, "TxHash copied!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                finish()
            }
        }

        root.addView(iconSuccess)
        root.addView(title)
        root.addView(desc)
        root.addView(hashBox)
        root.addView(btnCopy)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((320 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        Toast.makeText(this, "Transaction Broadcast Successfully!", Toast.LENGTH_LONG).show()
    }

    // ── Async Address Derivation ──────────────────────────────────────────────

    private fun deriveAddressFor(network: MultiChainAddressDeriver.Network, accountIndex: Int) {
        if (!::ivQr.isInitialized) return
        ivQr.visibility = View.INVISIBLE
        qrProgress.visibility = View.VISIBLE
        tvAddress.text = "Deriving address…"

        lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                try {
                    // If Hyperliquid deposit, use Bridge2 contract address
                    if (isHyperliquidDeposit) {
                        HYPERLIQUID_BRIDGE2_ADDRESS
                    } else {
                        when (network) {
                            MultiChainAddressDeriver.Network.ETHEREUM,
                            MultiChainAddressDeriver.Network.BNB,
                            MultiChainAddressDeriver.Network.ARBITRUM,
                            MultiChainAddressDeriver.Network.POLYGON -> {
                                EmbeddedWalletManager.getActiveEvmAddress(this@ReceiveActivity)
                            }
                            else -> {
                                MultiChainAddressDeriver.deriveAddress(this@ReceiveActivity, network, accountIndex)
                            }
                        }
                    }
                }
                catch (e: Exception) { "Error: ${e.message}" }
            }

            currentAddress = address
            val uri = MultiChainAddressDeriver.getQrUri(network, address)
            val color = Color.parseColor(network.networkColor)
            val qrBitmap = withContext(Dispatchers.Default) {
                val sizePx = (resources.displayMetrics.widthPixels * 0.64f).toInt()
                QrCodeGenerator.generateQrWithLogo(uri, sizePx, color, currentAsset.symbol.take(3))
            }

            ivQr.setImageBitmap(qrBitmap)
            ivQr.visibility = View.VISIBLE
            qrProgress.visibility = View.GONE
            tvAddress.text = address
        }
    }

    // ── Drawable helper ───────────────────────────────────────────────────────

    private fun roundedBg(color: Int, radiusDp: Float): GradientDrawable {
        val r = radiusDp * resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = r
        }
    }
}
