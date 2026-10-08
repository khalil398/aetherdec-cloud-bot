package com.aetherdex.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * BottomSheet for choosing between MetaMask and Trust Wallet.
 * Reports the user's choice back through [Listener] so the host Activity can
 * dispatch the deep-link launch.
 */
class WalletPickerBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onWalletPicked(wallet: WalletConnectionManager.Wallet)
    }

    private var listener: Listener? = null

    fun setListener(l: Listener) {
        listener = l
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_wallet_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        bindRow(
            row = view.findViewById(R.id.rowMetaMask),
            badge = view.findViewById(R.id.badgeMetaMask),
            status = view.findViewById(R.id.tvMetaMaskStatus),
            wallet = WalletConnectionManager.Wallet.METAMASK
        )
        bindRow(
            row = view.findViewById(R.id.rowTrust),
            badge = view.findViewById(R.id.badgeTrust),
            status = view.findViewById(R.id.tvTrustStatus),
            wallet = WalletConnectionManager.Wallet.TRUST
        )
    }

    private fun bindRow(
        row: LinearLayout,
        badge: TextView,
        status: TextView,
        wallet: WalletConnectionManager.Wallet
    ) {
        val ctx = requireContext()
        val installed = WalletConnectionManager.isInstalled(ctx, wallet)
        val green = ContextCompat.getColor(ctx, R.color.brand_green)
        val red = ContextCompat.getColor(ctx, R.color.brand_red)
        val elevated = ContextCompat.getColor(ctx, R.color.bg_elevated)
        val black = ContextCompat.getColor(ctx, R.color.black)
        val white = ContextCompat.getColor(ctx, R.color.white)

        if (installed) {
            badge.text = getString(R.string.wallet_installed)
            badge.setBackgroundResource(R.drawable.bg_chip_selected_green)
            badge.setTextColor(black)
            status.text = getString(R.string.wallet_installed)
            status.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        } else {
            badge.text = getString(R.string.wallet_not_installed)
            badge.setBackgroundColor(elevated)
            badge.setTextColor(white)
            status.text = getString(R.string.wallet_not_installed)
            status.setTextColor(red)
        }

        row.setOnClickListener {
            if (!installed) {
                WalletConnectionManager.openPlayStore(ctx, wallet)
            } else {
                listener?.onWalletPicked(wallet)
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener = null
    }

    companion object {
        const val TAG = "WalletPickerBottomSheet"
    }
}
