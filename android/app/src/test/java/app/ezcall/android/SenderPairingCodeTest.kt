package app.ezcall.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SenderPairingCodeTest {
    @Test fun productionPayloadsDecodeWithTheBrandTileAtSupportedSizes() {
        val logo = ImageIO.read(File("src/main/res/drawable-nodpi/sender_header_brand.png"))
        val brand = logo.getRGB(0, 0, logo.width, logo.height, null, 0, logo.width)
        val random = Random(27)
        repeat(64) { sample ->
            val secret = random.nextBytes(32)
            val payload = "xcall:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
            for (size in intArrayOf(176, 256, 512)) {
                val pixels = SenderPairingCode(payload, size, brand, logo.width).pixels
                val rotated = IntArray(pixels.size) { index -> pixels[(size - 1 - index % size) * size + index / size] }
                val source = RGBLuminanceSource(size, size, if (sample % 2 == 0) pixels else rotated)
                val result = runCatching {
                    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
                }
                    .getOrElse { throw AssertionError("Could not decode sample $sample at ${size}px", it) }
                assertEquals(payload, result.text)
                assertArrayEquals(secret, PairKey.parseQr(result.text))
            }
        }
    }
}
