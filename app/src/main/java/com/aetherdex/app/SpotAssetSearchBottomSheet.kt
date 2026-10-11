package com.aetherdex.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale

/**
 * Modern Search Filter Modal for selecting Hyperliquid Spot Assets.
 * Provides real-time filtering as users type currency names or symbols (e.g., PURR, PEPE, BTC).
 */
object SpotAssetSearchBottomSheet {

    fun show(
        context: Context,
        currentSymbol: String,
        onAssetSelected: (symbol: String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val dialog = BottomSheetDialog(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151921"))
            val pad = dp(16)
            setPadding(pad, dp(12), pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Drag Handle
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
        root.addView(handle)

        // Title Row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val tvTitle = TextView(context).apply {
            text = "Select Spot Asset"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(tvTitle)

        val allAssets = HyperliquidOrderManager.getAllSpotAssetInfos()
        val tvCount = TextView(context).apply {
            text = "${allAssets.size} Pairs"
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
            ).apply {
                marginEnd = dp(10)
            }
        }
        titleRow.addView(tvCount)

        val btnClose = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#8A96A8"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(btnClose)
        root.addView(titleRow)

        // Search Bar Container
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F2430"))
                cornerRadius = density * 10f
                setStroke(dp(1), Color.parseColor("#2A3241"))
            }
            val p = dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val ivSearch = TextView(context).apply {
            text = "🔍"
            textSize = 13f
            setPadding(dp(4), 0, dp(6), 0)
        }
        searchBox.addView(ivSearch)

        val etSearch = EditText(context).apply {
            hint = "Search asset (e.g. PURR, HFUN, BTC)..."
            setHintTextColor(Color.parseColor("#8A96A8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBox.addView(etSearch)

        val btnClear = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#8A96A8"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = View.GONE
            setOnClickListener {
                etSearch.setText("")
            }
        }
        searchBox.addView(btnClear)
        root.addView(searchBox)

        // List View & Adapter
        val displayedList = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
        displayedList.addAll(allAssets)

        val listView = ListView(context).apply {
            divider = ColorDrawable(Color.parseColor("#1A202C"))
            dividerHeight = dp(1)
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(380)
            )
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = displayedList.size
            override fun getItem(position: Int): HyperliquidOrderManager.SpotAssetInfo = displayedList[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = getItem(position)
                val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val rp = dp(12)
                    setPadding(rp, dp(10), rp, dp(10))
                }
                row.removeAllViews()

                // Left column: Symbol + SPOT Badge
                val leftCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val symRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val tvSym = TextView(context).apply {
                    text = item.displaySymbol
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                symRow.addView(tvSym)

                val badge = TextView(context).apply {
                    text = "SPOT"
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
                    ).apply {
                        marginStart = dp(6)
                    }
                }
                symRow.addView(badge)
                leftCol.addView(symRow)

                val tvBase = TextView(context).apply {
                    text = item.baseToken
                    textSize = 11f
                    setTextColor(Color.parseColor("#8A96A8"))
                    setPadding(0, dp(2), 0, 0)
                }
                leftCol.addView(tvBase)
                row.addView(leftCol)

                // Right column: Price + 24h Change
                val rightCol = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val effectivePrice = if (item.markPrice > 0.0) {
                    item.markPrice
                } else {
                    HyperliquidOrderManager.getAssetInfo(item.baseToken)?.markPrice ?: 0.0
                }

                if (effectivePrice > 0.0) {
                    val tvPrice = TextView(context).apply {
                        text = "$" + BotStrategyChartView.formatPriceValue(effectivePrice)
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.END
                    }
                    rightCol.addView(tvPrice)

                    val pct = if (item.change24hPercent != 0.0) {
                        item.change24hPercent
                    } else {
                        HyperliquidOrderManager.getAssetInfo(item.baseToken)?.change24hPercent ?: 0.0
                    }
                    val sign = if (pct >= 0) "+" else ""
                    val tvChange = TextView(context).apply {
                        text = String.format(Locale.US, "%s%.2f%%", sign, pct)
                        textSize = 11f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (pct >= 0) Color.parseColor("#00E676") else Color.parseColor("#FF1744"))
                        gravity = Gravity.END
                        setPadding(0, dp(2), 0, 0)
                    }
                    rightCol.addView(tvChange)
                } else {
                    val tvActive = TextView(context).apply {
                        val isSel = item.displaySymbol.equals(currentSymbol, ignoreCase = true)
                        text = if (isSel) "✓ Active" else "Select"
                        textSize = 12f
                        setTextColor(if (isSel) Color.parseColor("#00E676") else Color.parseColor("#8A96A8"))
                    }
                    rightCol.addView(tvActive)
                }

                row.addView(rightCol)

                // Selected highlight background
                val isSelected = item.displaySymbol.equals(currentSymbol, ignoreCase = true)
                if (isSelected) {
                    row.background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1500E676"))
                        cornerRadius = density * 8f
                    }
                } else {
                    row.background = null
                }

                return row
            }
        }

        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = displayedList.getOrNull(position) ?: return@setOnItemClickListener
            onAssetSelected(selected.displaySymbol)
            dialog.dismiss()
        }

        root.addView(listView)

        // Real-time search filter watcher
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                btnClear.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                displayedList.clear()
                if (q.isEmpty()) {
                    displayedList.addAll(allAssets)
                } else {
                    val priorityMatches = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()
                    val regularMatches = mutableListOf<HyperliquidOrderManager.SpotAssetInfo>()

                    for (item in allAssets) {
                        val sym = item.displaySymbol.lowercase(Locale.US)
                        val base = item.baseToken.lowercase(Locale.US)
                        val name = item.name.lowercase(Locale.US)

                        val isBtcMatch = (q == "btc" || q == "bitcoin") && (base == "btc" || base == "ubtc" || name.contains("btc"))
                        val isEthMatch = (q == "eth" || q == "ethereum" || q == "ether") && (base == "eth" || base == "ueth" || name.contains("eth"))
                        val isSolMatch = (q == "sol" || q == "solana") && (base == "sol" || base == "usol" || name.contains("sol"))

                        if (base == q || sym.startsWith(q) || isBtcMatch || isEthMatch || isSolMatch) {
                            priorityMatches.add(item)
                        } else if (sym.contains(q) || base.contains(q) || name.contains(q)) {
                            regularMatches.add(item)
                        }
                    }

                    displayedList.addAll(priorityMatches)
                    displayedList.addAll(regularMatches)
                }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialog.setContentView(root)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = GradientDrawable().apply {
            setColor(Color.parseColor("#151921"))
            cornerRadii = floatArrayOf(
                density * 16f, density * 16f,
                density * 16f, density * 16f,
                0f, 0f, 0f, 0f
            )
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }
}
