package ai.vox.app.capture

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** A JPEG-encoded frame plus a tiny luma grid used only for change detection. */
class Frame(val jpegBase64: String, val luma: IntArray, val width: Int, val height: Int) {
    override fun toString() = "Frame(${width}x$height)"
}

interface FrameSource {
    val label: String
    /** Latest frame, or null if none is available (yet). */
    suspend fun nextFrame(): Frame?
}

object BitmapUtils {
    private const val GRID = 16

    /** Downscales so the long edge is at most [maxEdge], JPEG-encodes, and builds the change-detection grid. */
    fun toFrame(source: Bitmap, maxEdge: Int, quality: Int = 80): Frame {
        val scaled = scaleDown(source, maxEdge)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val luma = lumaGrid(scaled)
        val f = Frame(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), luma, scaled.width, scaled.height)
        if (scaled !== source) scaled.recycle()
        return f
    }

    fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val s = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    }

    fun lumaGrid(bmp: Bitmap): IntArray {
        val tiny = Bitmap.createScaledBitmap(bmp, GRID, GRID, true)
        val px = IntArray(GRID * GRID)
        tiny.getPixels(px, 0, GRID, 0, 0, GRID, GRID)
        if (tiny !== bmp) tiny.recycle()
        return IntArray(px.size) { i ->
            val c = px[i]
            (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
        }
    }
}
