package com.aetherdex.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/**
 * Modern Search Filter Modal for selecting Hyperliquid Spot Assets.
 *
 * Features:
 *  - ★ Star/pin assets to top of list (persisted locally via SharedPreferences).
 *  - Bot-enabled assets float just below starred, above the rest.
 *  - Sort order: [Starred] → [Bot-ON] → [Rest]
 *  - Real-time search filter.
 *  - BottomSheet drag-to-dismiss only via the top handle; scrolling the asset list
 *    never collapses the sheet.
 *  - Singleton guard prevents duplicate dialogs.
 */
object SpotAssetSearchBottomSheet {

    // ── Singleton guard ──────────────────────────────────────────────────
    private var activeDialog: BottomSheetDialog? = null

    // ── Star / Pinned persistence ────────────────────────────────────────
    private const val STAR_PREFS   = "aetherdex_starred_assets"
    private const val KEY_STARRED  = "starred_assets_set"

    private fun getStarredAssets(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(STAR_PREFS, Context.MODE_PRIVATE)
        return (prefs.getStringSet(KEY_STARRED, emptySet()) ?: emptySet()).toMutableSet()
    }

    private fun setAssetStarred(context: Context, baseToken: String, starred: Boolean) {
        val clean = HyperliquidOrderManager.cleanSymbolString(baseToken).uppercase(Locale.US)
        val prefs = context.getSharedPreferences(STAR_PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_STARRED, emptySet()) ?: emptySet()).toMutableSet()
        if (starred) set.add(clean) else set.remove(clean)
        prefs.edit().putStringSet(KEY_STARRED, set).apply()
    }

    private fun isAssetStarred(context: Context, baseToken: String): Boolean {
        val clean = HyperliquidOrderManager.cleanSymbolString(baseToken).uppercase(Locale.US)
        return getStarredAssets(context).contains(clean)
    }

    // ── Sort helper ──────────────────────────────────────────────────────
    private fun buildSortedList(
        source: List<HyperliquidOrderManager.SpotAssetInfo>,
        context: Context,
        query: String
    ): List<HyperliquidOrderManager.SpotAssetInfo> {
        val starred    = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
        val botOn      = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
        val rest       = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
        val priorityFilt = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
        val regularFilt  = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()

        val q = query.trim().lowercase(Locale.US)

        for (item in source) {
            val isStarred = isAssetStarred(context, item.baseToken)
            val isBotOn   = BotAssetPermissionManager.isAssetEnabled(context, item.baseToken)

            if (q.isEmpty()) {
                when {
                    isStarred -> starred.add(item)
                    isBotOn   -> botOn.add(item)
                    else      -> rest.add(item)
                }
            } else {
                val sym  = item.displaySymbol.lowercase(Locale.US)
                val base = item.baseToken.lowercase(Locale.US)
                val name = item.name.lowercase(Locale.US)

                val isBtcMatch = (q == "btc" || q == "bitcoin") && (base == "btc" || base == "ubtc" || name.contains("btc"))
                val isEthMatch = (q == "eth" || q == "ethereum" || q == "ether") && (base == "eth" || base == "ueth" || name.contains("eth"))
                val isSolMatch = (q == "sol" || q == "solana") && (base == "sol" || base == "usol" || name.contains("sol"))

                val isPriority = base == q || sym.startsWith(q) || isBtcMatch || isEthMatch || isSolMatch
                val isRegular  = !isPriority && (sym.contains(q) || base.contains(q) || name.contains(q))

                if (isPriority) priorityFilt.add(item)
                else if (isRegular) regularFilt.add(item)
            }
        }

        return if (q.isEmpty()) {
            starred + botOn + rest
        } else {
            // Within filtered results, still preserve starred → bot → rest ordering
            fun sortFiltered(list: List<HyperliquidOrderManager.SpotAssetInfo>): List<HyperliquidOrderManager.SpotAssetInfo> {
                val s = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
                val b = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
                val r = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
                for (item in list) {
                    when {
                        isAssetStarred(context, item.baseToken)           -> s.add(item)
                        BotAssetPermissionManager.isAssetEnabled(context, item.baseToken) -> b.add(item)
                        else -> r.add(item)
                    }
                }
                return s + b + r
            }
            sortFiltered(priorityFilt) + sortFiltered(regularFilt)
        }
    }

    // ── Public entry point ───────────────────────────────────────────────
    fun show(
        context: Context,
        currentSymbol: String,
        onAssetSelected: (symbol: String) -> Unit
    ) {
        if (activeDialog?.isShowing == true) return

        val density = context.resources.displayMetrics.density
        val dp      = { v: Int -> (v * density).toInt() }

        val dialog = BottomSheetDialog(context)

        // ── Root vertical container ──────────────────────────────────────
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151921"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Drag handle (the ONLY area that dismisses on swipe-down) ────
        val handleArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            val vPad = dp(12)
            setPadding(dp(16), vPad, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A3241"))
                cornerRadius = density * 2f
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        }
        handleArea.addView(handle)
        root.addView(handleArea)

        // ── Header (title + count + close) ──────────────────────────────
        val headerArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = dp(16)
            setPadding(hPad, 0, hPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val tvTitle = TextView(context).apply {
            text     = "Select Spot Asset"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(tvTitle)

        val allAssets = HyperliquidOrderManager.getAllSpotAssetInfos()
        val tvCount = TextView(context).apply {
            text     = "${allAssets.size} Pairs"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1000E676"))
                cornerRadius = density * 6f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }
        titleRow.addView(tvCount)

        val btnClose = TextView(context).apply {
            text     = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#8A96A8"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(btnClose)
        headerArea.addView(titleRow)

        // ── Search bar ───────────────────────────────────────────────────
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            background  = GradientDrawable().apply {
                setColor(Color.parseColor("#1F2430"))
                cornerRadius = density * 10f
                setStroke(dp(1), Color.parseColor("#2A3241"))
            }
            val p = dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val ivSearch = TextView(context).apply {
            text     = "🔍"
            textSize = 13f
            setPadding(dp(4), 0, dp(6), 0)
        }
        searchBox.addView(ivSearch)

        val etSearch = EditText(context).apply {
            hint     = "Search asset (e.g. PURR, HFUN, BTC)..."
            setHintTextColor(Color.parseColor("#8A96A8"))
            setTextColor(Color.WHITE)
            textSize   = 14f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(etSearch)

        val btnClear = TextView(context).apply {
            text     = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#8A96A8"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE
            setOnClickListener { etSearch.setText("") }
        }
        searchBox.addView(btnClear)
        headerArea.addView(searchBox)
        root.addView(headerArea)

        // ── Divider ──────────────────────────────────────────────────────
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#1A202C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
        }
        root.addView(divider)

        // ── Asset list (scroll strictly inside; never collapses sheet) ───
        val displayedList = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
        displayedList.addAll(buildSortedList(allAssets, context, ""))

        val listView = ListView(context).apply {
            this.divider = ColorDrawable(Color.parseColor("#1A202C"))
            dividerHeight = dp(1)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            // Prevent the BottomSheetBehavior from intercepting vertical scroll on the list.
            // When user scrolls the list, we tell the sheet NOT to drag itself.
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }

        // ── Adapter ──────────────────────────────────────────────────────
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int  = displayedList.size
            override fun getItem(pos: Int) = displayedList[pos]
            override fun getItemId(pos: Int): Long = pos.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = getItem(position)
                val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = Gravity.CENTER_VERTICAL
                    val rp = dp(12)
                    setPadding(rp, dp(10), rp, dp(10))
                }
                row.removeAllViews()

                // ── Star column ──────────────────────────────────────────
                val isStarred = isAssetStarred(context, item.baseToken)
                val tvStar = TextView(context).apply {
                    text     = if (isStarred) "★" else "☆"
                    textSize = 16f
                    setTextColor(if (isStarred) Color.parseColor("#FFD700") else Color.parseColor("#4A5568"))
                    setPadding(0, 0, dp(8), 0)
                    isFocusable = false
                    isClickable = true
                    setOnClickListener {
                        val nowStarred = !isAssetStarred(context, item.baseToken)
                        setAssetStarred(context, item.baseToken, nowStarred)
                        // Re-sort in-place
                        val q = etSearch.text?.toString() ?: ""
                        val resorted = buildSortedList(allAssets, context, q)
                        displayedList.clear()
                        displayedList.addAll(resorted)
                        notifyDataSetChanged()
                    }
                }
                row.addView(tvStar)

                // ── Left col: symbol + badge ─────────────────────────────
                val leftCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val symRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity     = Gravity.CENTER_VERTICAL
                }

                val tvSym = TextView(context).apply {
                    text     = item.displaySymbol
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                symRow.addView(tvSym)

                val badge = TextView(context).apply {
                    text     = "SPOT"
                    textSize = 9f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#00E676"))
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1500E676"))
                        cornerRadius = density * 4f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(6) }
                }
                symRow.addView(badge)
                leftCol.addView(symRow)

                val tvBase = TextView(context).apply {
                    text     = item.baseToken
                    textSize = 11f
                    setTextColor(Color.parseColor("#8A96A8"))
                    setPadding(0, dp(2), 0, 0)
                }
                leftCol.addView(tvBase)
                row.addView(leftCol)

                // ── Middle col: Bot toggle ───────────────────────────────
                val midCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity     = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(10)
                        marginEnd   = dp(14)
                    }
                }

                val isBotEnabled = BotAssetPermissionManager.isAssetEnabled(context, item.baseToken)

                val switchBot = SwitchMaterial(context).apply {
                    isFocusable = false
                    isClickable = true
                    setOnCheckedChangeListener(null)
                    isChecked = isBotEnabled

                    val states = arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(-android.R.attr.state_checked)
                    )
                    thumbTintList = ColorStateList(states, intArrayOf(
                        Color.parseColor("#00E676"), Color.parseColor("#8A96A8")
                    ))
                    trackTintList = ColorStateList(states, intArrayOf(
                        Color.parseColor("#4D00E676"), Color.parseColor("#2A3241")
                    ))
                }

                val tvBotLabel = TextView(context).apply {
                    text     = if (isBotEnabled) "BOT ON" else "BOT OFF"
                    textSize = 9f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (isBotEnabled) Color.parseColor("#00E676") else Color.parseColor("#606E82"))
                    gravity  = Gravity.CENTER_HORIZONTAL
                }

                switchBot.setOnCheckedChangeListener { _, checked ->
                    BotAssetPermissionManager.setAssetEnabled(context, item.baseToken, checked)
                    tvBotLabel.text = if (checked) "BOT ON" else "BOT OFF"
                    tvBotLabel.setTextColor(if (checked) Color.parseColor("#00E676") else Color.parseColor("#606E82"))
                    Toast.makeText(
                        context,
                        if (checked) "🤖 Bot ENABLED for ${item.baseToken}"
                        else         "🔒 Bot DISABLED for ${item.baseToken}",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Re-sort list so newly-enabled/disabled asset moves to its correct band
                    val q = etSearch.text?.toString() ?: ""
                    val resorted = buildSortedList(allAssets, context, q)
                    displayedList.clear()
                    displayedList.addAll(resorted)
                    notifyDataSetChanged()
                }

                midCol.addView(switchBot)
                midCol.addView(tvBotLabel)
                row.addView(midCol)

                // ── Right col: price + 24h change ───────────────────────
                val rightCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity     = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val effectivePrice = if (item.markPrice > 0.0) item.markPrice
                    else HyperliquidOrderManager.getAssetInfo(item.baseToken)?.markPrice ?: 0.0

                if (effectivePrice > 0.0) {
                    rightCol.addView(TextView(context).apply {
                        text     = "$" + BotStrategyChartView.formatPriceValue(effectivePrice)
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity  = Gravity.END
                    })

                    val pct = if (item.change24hPercent != 0.0) item.change24hPercent
                        else HyperliquidOrderManager.getAssetInfo(item.baseToken)?.change24hPercent ?: 0.0
                    rightCol.addView(TextView(context).apply {
                        text     = String.format(Locale.US, "%s%.2f%%", if (pct >= 0) "+" else "", pct)
                        textSize = 11f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (pct >= 0) Color.parseColor("#00E676") else Color.parseColor("#FF1744"))
                        gravity  = Gravity.END
                        setPadding(0, dp(2), 0, 0)
                    })
                } else {
                    rightCol.addView(TextView(context).apply {
                        val isSel = item.displaySymbol.equals(currentSymbol, ignoreCase = true)
                        text      = if (isSel) "✓ Active" else "Select"
                        textSize  = 12f
                        setTextColor(if (isSel) Color.parseColor("#00E676") else Color.parseColor("#8A96A8"))
                    })
                }

                row.addView(rightCol)

                // Selected highlight
                val isSelected = item.displaySymbol.equals(currentSymbol, ignoreCase = true)
                row.background = if (isSelected) GradientDrawable().apply {
                    setColor(Color.parseColor("#1500E676"))
                    cornerRadius = density * 8f
                } else null

                row.setOnClickListener {
                    onAssetSelected(item.displaySymbol)
                    dialog.dismiss()
                }

                return row
            }
        }

        listView.adapter = adapter
        // Also handle list-level item click (belt-and-suspenders)
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = displayedList.getOrNull(position) ?: return@setOnItemClickListener
            onAssetSelected(selected.displaySymbol)
            dialog.dismiss()
        }

        root.addView(listView)

        // ── Real-time search ─────────────────────────────────────────────
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                btnClear.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                val resorted = buildSortedList(allAssets, context, q)
                displayedList.clear()
                displayedList.addAll(resorted)
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // ── Dialog config ────────────────────────────────────────────────
        dialog.setContentView(root)

        // Style the bottom sheet container
        val sheetView = dialog.window
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheetView?.background = GradientDrawable().apply {
            setColor(Color.parseColor("#151921"))
            cornerRadii = floatArrayOf(
                density * 16f, density * 16f,
                density * 16f, density * 16f,
                0f, 0f, 0f, 0f
            )
        }

        // Fix scroll vs drag-to-dismiss conflict:
        //  • Expand the sheet fully so it occupies the screen height.
        //  • Set isDraggable = false so only an explicit drag from the handle area
        //    (which we do via the BottomSheetBehavior callback below) dismisses it.
        //  • Re-enable dragging ONLY from the handle strip.
        if (sheetView != null) {
            val behavior = BottomSheetBehavior.from(sheetView)
            behavior.state       = BottomSheetBehavior.STATE_EXPANDED
            behavior.peekHeight  = BottomSheetBehavior.PEEK_HEIGHT_AUTO
            behavior.skipCollapsed = true
            behavior.isDraggable = false   // Prevent dragging from the list area

            // Allow drag-to-dismiss only when the gesture starts from the handle area
            handleArea.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        behavior.isDraggable = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        behavior.isDraggable = false
                    }
                }
                false   // Let the event propagate so the swipe-down still works
            }

            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == BottomSheetBehavior.STATE_HIDDEN ||
                        newState == BottomSheetBehavior.STATE_COLLAPSED) {
                        behavior.isDraggable = false
                        dialog.dismiss()
                    }
                }
                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            })
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        dialog.setOnDismissListener {
            if (activeDialog == dialog) activeDialog = null
        }
        activeDialog = dialog
        dialog.show()
    }
}
