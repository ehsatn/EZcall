package app.ezcall.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder

internal class SenderPairingCode(value: String, private val size: Int, logo: IntArray, logoWidth: Int) {
    val pixels: IntArray

    init {
        require(logoWidth > 0 && logo.size == logoWidth * logoWidth)
        val code = Encoder.encode(value, ErrorCorrectionLevel.H)
        val matrix = requireNotNull(code.matrix)
        // Some mask patterns confuse the receiver's alignment search once the center is branded.
        val masks = listOf(code.maskPattern) + (0..7).filterNot { it == code.maskPattern }
        pixels = masks.firstNotNullOfOrNull { mask ->
            val candidateMatrix = if (mask == code.maskPattern) matrix else requireNotNull(
                Encoder.encode(value, ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_MASK_PATTERN to mask)).matrix
            )
            render(candidateMatrix, logo, logoWidth).takeIf { decodes(it, value) }
        } ?: render(matrix, null, logoWidth)
    }

    private fun render(matrix: ByteMatrix, logo: IntArray?, logoWidth: Int): IntArray {
        val modules = matrix.width
        val scale = size / (modules + 8)
        require(scale > 0)
        val start = (size - modules * scale) / 2
        val center = modules / 2
        val bitmap = IntArray(size * size) { WHITE }
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (matrix[x, y].toInt() != 1) continue
                if (logo != null && x in center - 3..center + 3 && y in center - 3..center + 3) continue
                val finder = (x < 7 && (y < 7 || y >= modules - 7)) || (y < 7 && x >= modules - 7)
                val left = start + x * scale
                val top = start + y * scale
                for (py in top until top + scale) {
                    bitmap.fill(if (finder) BLUE else INK, py * size + left, py * size + left + scale)
                }
            }
        }
        if (logo != null) {
            val width = 5 * scale
            val inset = start + (center - 2) * scale
            for (y in 0 until width) {
                for (x in 0 until width) {
                    val color = logo[(y * logoWidth / width) * logoWidth + x * logoWidth / width]
                    if ((color ushr 24) >= 128) bitmap[(inset + y) * size + inset + x] = color or 0xFF000000.toInt()
                }
            }
        }
        return bitmap
    }

    private fun decodes(pixels: IntArray, value: String): Boolean = try {
        val source = RGBLuminanceSource(size, size, pixels)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text == value
    } catch (_: ReaderException) {
        false
    }

    private companion object {
        const val WHITE = -1
        const val BLUE = 0xFF174AC0.toInt()
        const val INK = 0xFF101D37.toInt()
    }
}
