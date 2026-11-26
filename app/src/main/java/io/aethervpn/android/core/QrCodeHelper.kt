package io.aethervpn.android.core

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Lightweight QR code encoder & gallery decoder backed by `com.google.zxing:core`.
 */
object QrCodeHelper {

    fun encodeQrMatrix(content: String, sizePx: Int = 640): Result<BitMatrix> = runCatching {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "QR content cannot be empty" }
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        QRCodeWriter().encode(trimmed, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    fun renderQrBitmap(content: String, sizePx: Int = 640): Result<Bitmap> = runCatching {
        val matrix = encodeQrMatrix(content, sizePx).getOrThrow()
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun decodeQrFromUri(contentResolver: ContentResolver, uri: Uri): Result<String> = runCatching {
        val bitmap = decodeSampledBitmap(contentResolver, uri, maxDimension = 1024)
            ?: throw IllegalArgumentException("Unable to read selected image")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
            val decoded = QRCodeReader().decode(binaryBitmap, hints)
            decoded.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("QR code is empty")
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeSampledBitmap(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (rawW / sampleSize > maxDimension || rawH / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }
}
