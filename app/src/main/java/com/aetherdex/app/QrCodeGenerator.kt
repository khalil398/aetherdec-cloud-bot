package com.aetherdex.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Generates real, scannable QR code bitmaps using ZXing.
 * H-level error correction leaves enough capacity for a 20%-area logo overlay.
 */
object QrCodeGenerator {

    /**
     * Generates a scannable QR code for [content] with an optional network-logo
     * circle overlay in the centre.
     *
     * @param content     URI string to encode (e.g. "bitcoin:<address>")
     * @param sizePx      Output bitmap side length in pixels
     * @param logoBgColor Filled circle colour behind the logo label (network colour)
     * @param logoLabel   1–3 char text drawn in white inside the logo circle
     */
    fun generateQrWithLogo(
        content: String,
        sizePx: Int,
        logoBgColor: Int = Color.parseColor("#627EEA"),
        logoLabel: String = "ETH"
    ): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN           to 1,
            EncodeHintType.CHARACTER_SET    to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        // Convert BitMatrix → Bitmap (black + white)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx)
            for (y in 0 until sizePx)
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)

        // Overlay: white backing circle + coloured network badge + label text
        val canvas = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val backR  = sizePx * 0.13f  // white backing circle radius
        val logoR  = sizePx * 0.10f  // coloured logo circle radius
        val textSz = sizePx * 0.065f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy, backR, bgPaint)

        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = logoBgColor }
        canvas.drawCircle(cx, cy, logoR, logoPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSz
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(logoLabel, cx, textY, textPaint)

        return bmp
    }

    /**
     * Backward-compatible wrapper (used by old deposit dialog).
     * Generates a real ZXing QR code without a logo overlay.
     */
    fun generateDepositQrBitmap(text: String, sizePx: Int = 512): Bitmap =
        generateQrWithLogo(text, sizePx, Color.parseColor("#627EEA"), "EVM")
}
