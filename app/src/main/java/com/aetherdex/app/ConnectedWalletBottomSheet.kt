package com.aetherdex.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

class ConnectedWalletBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onDisconnectConfirmed()
    }

    private var listener: Listener? = null

    fun setListener(l: Listener) {
        listener = l
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_AetherDex_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_connected_wallet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(
            requireContext().getColor(R.color.bg_root)
        )

        val walletName = arguments?.getString(ARG_WALLET_NAME).orEmpty().ifBlank { "Wallet" }
        val address = arguments?.getString(ARG_ADDRESS).orEmpty()

        view.findViewById<TextView>(R.id.tvConnectedWalletName).text = walletName
        view.findViewById<TextView>(R.id.tvConnectedAddress).text =
            WalletConnectionManager.shorten(address).ifBlank { address }

        view.findViewById<ImageView>(R.id.imgConnectedWallet).setImageResource(
            iconForWalletName(walletName)
        )

        view.findViewById<MaterialButton>(R.id.btnDisconnectWallet).setOnClickListener {
            listener?.onDisconnectConfirmed()
            dismissAllowingStateLoss()
        }
        view.findViewById<MaterialButton>(R.id.btnCancelDisconnect).setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener = null
    }

    private fun iconForWalletName(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("metamask") -> R.drawable.ic_metamask
            lower.contains("trust") -> R.drawable.ic_trust_wallet
            else -> R.drawable.ic_wallet
        }
    }

    companion object {
        const val TAG = "ConnectedWalletBottomSheet"
        private const val ARG_WALLET_NAME = "wallet_name"
        private const val ARG_ADDRESS = "address"

        fun newInstance(walletName: String, address: String): ConnectedWalletBottomSheet {
            return ConnectedWalletBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_WALLET_NAME, walletName)
                    putString(ARG_ADDRESS, address)
                }
            }
        }
    }
}
