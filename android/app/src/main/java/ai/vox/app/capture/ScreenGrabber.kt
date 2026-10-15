package ai.vox.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Wraps an approved [MediaProjection]. It keeps only the *latest* raw image (no per-frame conversion), and encodes
 * a frame only when asked, so a screen that is playing video doesn't burn CPU or battery.
 * Owns the VirtualDisplay: [release] stops all capture.
 */
class ScreenGrabber(private val context: Context, private val projection: MediaProjection, private val onStoppedBySystem: () -> Unit) {
    private val thread = HandlerThread("vox-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var latest: Image? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var released = false

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system (or the user, via the status-bar chip) ended the capture.
            release()
            onStoppedBySystem()
        }
    }

    init {
        // Android 14+ requires a callback to be registered before creating the VirtualDisplay.
        projection.registerCallback(callback, handler)
        val (w, h, dpi) = captureSize()
        width = w; height = h
        val r = newReader(w, h)
        reader = r
        display = projection.createVirtualDisplay(
            "VoxScreen", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { r ->
            r.setOnImageAvailableListener({ rd ->
                val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                synchronized(lock) { latest?.close(); latest = img }
            }, handler)
        }

    private fun captureSize(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (rw, rh, dpi) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), context.resources.configuration.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val m = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(rw, rh))
        return Triple((rw * scale).toInt().coerceAtLeast(2), (rh * scale).toInt().coerceAtLeast(2), dpi)
    }

    /** Re-creates the surface if the device rotated. */
    private fun adaptToRotation() {
        val (w, h, dpi) = captureSize()
        if (w == width && h == height) return
        synchronized(lock) { latest?.close(); latest = null }
        val old = reader
        val r = newReader(w, h)
        reader = r
        display?.resize(w, h, dpi)
        display?.surface = r.surface
        width = w; height = h
        old?.close()
    }

    /** Captures the current screen as a [Frame], or null if nothing was produced. Black frames are returned as-is; callers check [ai.vox.app.core.vision.FrameDiff.looksBlank]. */
    suspend fun grab(maxEdge: Int = 1600): Frame? = withContext(Dispatchers.Default) {
        if (released) return@withContext null
        runCatching { adaptToRotation() }
        var waited = 0
        while (waited < 2000) {
            val bmp = synchronized(lock) { latest?.let(::toBitmap) }
            if (bmp != null) {
                return@withContext try { BitmapUtils.toFrame(bmp, maxEdge) } finally { bmp.recycle() }
            }
            delay(100); waited += 100
        }
        null
    }

    private fun toBitmap(image: Image): Bitmap? = runCatching {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }.getOrNull()

    /** Stops capture and frees every resource. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        runCatching { projection.unregisterCallback(callback) }
        runCatching { display?.release() }
        synchronized(lock) { latest?.close(); latest = null }
        runCatching { reader?.close() }
        runCatching { projection.stop() }
        display = null; reader = null
        thread.quitSafely()
    }

    private companion object { const val MAX_EDGE = 1600 }
}
