package com.example.studytimelapse.timelapse

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a sequence of [Bitmap] frames into an H.264/MP4 file using
 * [MediaCodec] in synchronous mode and [MediaMuxer].
 *
 * ### Usage
 * ```
 * val encoder = VideoEncoder(outputFile, width = 720, height = 1280, fps = 30)
 * encoder.start()
 * encoder.encodeFrame(bitmap, presentationTimeUs)
 * ...
 * encoder.finish()   // flushes, stops codec, releases muxer
 * ```
 *
 * ### Thread safety
 * Not thread-safe.  Call all methods from the same thread (typically a
 * dedicated coroutine dispatcher / executor).
 *
 * @param outputFile  Destination MP4 file.  Parent directory must exist.
 * @param width       Output frame width  (must be even).
 * @param height      Output frame height (must be even).
 * @param fps         Output frames-per-second (determines timing in the MP4).
 * @param bitrateBps  Target bitrate in bits/second.
 */
class VideoEncoder(
    private val outputFile: File,
    val width: Int,
    val height: Int,
    private val fps: Int = 30,
    private val bitrateBps: Int = 4_000_000,
) {

    private val tag = "VideoEncoder"
    private val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC

    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var started = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun start() {
        check(!started) { "VideoEncoder already started" }

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            // COLOR_FormatYUV420Flexible is the correct value when using the Image API
            // (getInputImage).  The encoder internally chooses the actual sub-format
            // (NV12, I420, etc.) and fillYuvImage() respects each plane's rowStride /
            // pixelStride, so this works correctly on all devices.
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        codec = MediaCodec.createEncoderByType(mimeType)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        started = true
        Log.d(tag, "Encoder started → ${outputFile.absolutePath}")
    }

    /**
     * Feed one [Bitmap] into the encoder.
     *
     * @param bitmap            Frame to encode.  Must match [width] × [height].
     * @param presentationTimeUs Monotonically increasing presentation timestamp in µs.
     */
    fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        check(started) { "Call start() first" }

        val scaledBitmap = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                // Use the Image API (required for COLOR_FormatYUV420Flexible).
                // It correctly handles rowStride / pixelStride for any device.
                val image = codec.getInputImage(inputIndex)
                if (image != null) {
                    fillYuvImage(scaledBitmap, image)
                } else {
                    Log.w(tag, "getInputImage returned null – frame will be blank")
                }
                // size = 0 is required when using getInputImage()
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            } else {
                Log.w(tag, "No input buffer available – frame dropped")
            }
        } finally {
            if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        }

        drainEncoder(endOfStream = false)
    }

    /**
     * Signal end-of-stream, flush remaining output, stop codec, and close muxer.
     * After this call the object must not be used again.
     */
    fun finish() {
        if (!started) return

        // Signal EOS – retry until a buffer slot is available (avoid dropping EOS)
        var eosSent = false
        for (attempt in 0 until 20) {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                eosSent = true
                break
            }
        }
        if (!eosSent) Log.w(tag, "Could not send EOS; file may be truncated")

        drainEncoder(endOfStream = true)

        codec.stop()
        codec.release()
        // Only stop/release muxer if it was actually started
        if (trackIndex >= 0) {
            muxer.stop()
        }
        muxer.release()
        started = false
        Log.d(tag, "Encoder finished → ${outputFile.absolutePath}")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Pull all available encoded output from the codec and write to the muxer.
     */
    private fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        var sawEos = false
        var timeoutRetries = 0
        val maxTimeoutRetries = if (endOfStream) 50 else 0

        while (!sawEos) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex == -1) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        Log.d(tag, "Muxer started, trackIndex=$trackIndex")
                    } else {
                        // Some hardware encoders spuriously re-send format change;
                        // safe to ignore after the track has been added.
                        Log.w(tag, "INFO_OUTPUT_FORMAT_CHANGED received again, ignoring")
                    }
                }

                outputIndex >= 0 -> {
                    timeoutRetries = 0
                    val outputBuffer: ByteBuffer = codec.getOutputBuffer(outputIndex)!!

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0 // Skip codec-specific data
                    }

                    if (bufferInfo.size > 0 && trackIndex >= 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEos = true
                    }
                }

                else -> {
                    // INFO_TRY_AGAIN_LATER or other transient status
                    if (!endOfStream) break
                    if (++timeoutRetries > maxTimeoutRetries) {
                        Log.w(tag, "Drain timed out waiting for EOS")
                        break
                    }
                }
            }
        }
    }

    /**
     * Writes ARGB [bitmap] data into the YUV planes of [image].
     *
     * Uses absolute ByteBuffer positions and honours each plane's [Image.Plane.rowStride]
     * and [Image.Plane.pixelStride], so it works for any sub-format the encoder
     * chooses under [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible]
     * (NV12, NV21, I420, etc.).
     */
    private fun fillYuvImage(bitmap: Bitmap, image: Image) {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val yPlane  = image.planes[0]
        val uPlane  = image.planes[1]
        val vPlane  = image.planes[2]

        val yBuf        = yPlane.buffer
        val uBuf        = uPlane.buffer
        val vBuf        = vPlane.buffer
        val yRowStride  = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride

        for (row in 0 until h) {
            for (col in 0 until w) {
                val pixel = argb[row * w + col]
                val r = pixel shr 16 and 0xFF
                val g = pixel shr 8  and 0xFF
                val b = pixel        and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(row * yRowStride + col, y.coerceIn(0, 255).toByte())

                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uvOffset = (row / 2) * uvRowStride + (col / 2) * uvPixStride
                    uBuf.put(uvOffset, u.coerceIn(0, 255).toByte())
                    vBuf.put(uvOffset, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
