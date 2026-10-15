package ai.vox.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Optional live camera source. Bound only while the user has the camera switched on in an active session and the
 * Live Video screen is visible; unbound again as soon as either stops.
 */
class CameraFrameSource(private val context: Context, private val maxEdge: Int = 1024) : FrameSource {
    override val label = "Camera"
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var latest: Bitmap? = null
    @Volatile private var lastConvertedAt = 0L
    @Volatile var bound = false
        private set

    /** Binds preview + analysis. Returns false if the camera can't be opened. */
    suspend fun bind(owner: LifecycleOwner, previewView: PreviewView): Boolean = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val ok = runCatching {
                val p = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                provider = p
                bound = true
            }.isSuccess
            if (cont.isActive) cont.resume(ok)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastConvertedAt < 700) return // we only ever need ~1 frame/sec
            lastConvertedAt = now
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val bmp = if (rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true).also { raw.recycle() } else raw
            latest?.recycle()
            latest = bmp
        } finally {
            image.close()
        }
    }

    override suspend fun nextFrame(): Frame? {
        val bmp = latest ?: return null
        return runCatching { BitmapUtils.toFrame(bmp, maxEdge) }.getOrNull()
    }

    /** Releases the camera and drops any buffered frame. */
    fun unbind() {
        runCatching { provider?.unbindAll() }
        provider = null
        bound = false
        latest?.recycle()
        latest = null
    }
}
