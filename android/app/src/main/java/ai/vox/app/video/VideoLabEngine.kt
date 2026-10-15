package ai.vox.app.video

import ai.vox.app.core.video.VideoPlan
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

class VideoMeta(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    val durationMs: Long?,
    val thumbnail: Bitmap?,
)

class SampledFrame(val timeMs: Long, val jpegBase64: String)

/** A video the user shared via the Android share sheet. It sits here until Video Lab picks it up. */
class SharedVideoInbox {
    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending.asStateFlow()
    fun offer(uri: Uri) { _pending.value = uri }
    fun take(): Uri? = _pending.value.also { _pending.value = null }
    fun clear() { _pending.value = null }
}

/**
 * Local video handling: metadata, thumbnail and frame sampling via MediaMetadataRetriever.
 * The video file itself is never uploaded - only the handful of JPEG frames the user confirmed.
 */
class VideoLabEngine(private val context: Context) {

    suspend fun readMeta(uri: Uri): VideoMeta = withContext(Dispatchers.IO) {
        var name = "video"
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val thumb = runCatching { r.getFrameAtTime(((dur ?: 0L) / 4) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            VideoMeta(uri, name, size, mime, dur, thumb?.let { scale(it, 480) })
        } catch (e: Exception) {
            VideoMeta(uri, name, size, mime, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Extracts frames at [times]. Cancellable between frames. [onProgress] gets (done, total).
     * Frames that can't be decoded are skipped; an empty result means the format isn't supported on this device.
     */
    suspend fun sampleFrames(uri: Uri, times: List<Long>, maxEdge: Int = 768, onProgress: (Int, Int) -> Unit): List<SampledFrame> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<SampledFrame>(times.size)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                times.forEachIndexed { i, t ->
                    coroutineContext.ensureActive()
                    val bmp = runCatching { r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
                    if (bmp != null) {
                        val s = scale(bmp, maxEdge)
                        val bytes = ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                        out += SampledFrame(t, Base64.encodeToString(bytes, Base64.NO_WRAP))
                        if (s !== bmp) s.recycle()
                        bmp.recycle()
                    }
                    onProgress(i + 1, times.size)
                }
            } finally {
                runCatching { r.release() }
            }
            out
        }

    private fun scale(src: Bitmap, maxEdge: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxEdge) return src
        val s = maxEdge.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    }

    companion object {
        fun describe(meta: VideoMeta): String = buildString {
            append(meta.durationMs?.let { VideoPlan.format(it) } ?: "unknown length")
            meta.sizeBytes?.let { append(" · ").append(String.format("%.1f MB", it / 1048576.0)) }
        }
    }
}
