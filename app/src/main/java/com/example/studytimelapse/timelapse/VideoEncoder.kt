package com.example.studytimelapse.timelapse

import android.graphics.Bitmap
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

        val nv21 = bitmapToNv21(scaledBitmap)
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()

        // Feed raw data to the codec input buffer
        val inputIndex = codec.dequeueInputBuffer(10_000L)
        if (inputIndex >= 0) {
            val inputBuffer: ByteBuffer = codec.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            inputBuffer.put(nv21)
            codec.queueInputBuffer(inputIndex, 0, nv21.size, presentationTimeUs, 0)
        } else {
            Log.w(tag, "No input buffer available – frame dropped")
        }

        drainEncoder(endOfStream = false)
    }

    /**
     * Signal end-of-stream, flush remaining output, stop codec, and close muxer.
     * After this call the object must not be used again.
     */
    fun finish() {
        if (!started) return

        // Signal EOS via an empty input buffer
        val inputIndex = codec.dequeueInputBuffer(10_000L)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        drainEncoder(endOfStream = true)

        codec.stop()
        codec.release()
        muxer.stop()
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

        while (!sawEos) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(trackIndex == -1) { "Format changed twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    Log.d(tag, "Muxer started, trackIndex=$trackIndex")
                }

                outputIndex >= 0 -> {
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
                }
            }
        }
    }

    /**
     * Convert an ARGB [Bitmap] to a tightly-packed NV21 byte array.
     *
     * NV21 layout: Y plane (width×height bytes) followed by interleaved V/U
     * chroma plane (width×height/2 bytes).  This matches
     * [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible] on most
     * devices when using the software encoder path.
     */
    private fun bitmapToNv21(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val nv21 = ByteArray(w * h * 3 / 2)
        var yIdx = 0
        var uvIdx = w * h

        for (row in 0 until h) {
            for (col in 0 until w) {
                val pixel = argb[row * w + col]
                val r = (pixel shr 16 and 0xFF)
                val g = (pixel shr 8 and 0xFF)
                val b = (pixel and 0xFF)

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                nv21[yIdx++] = y.coerceIn(0, 255).toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    nv21[uvIdx++] = v.coerceIn(0, 255).toByte()
                    nv21[uvIdx++] = u.coerceIn(0, 255).toByte()
                }
            }
        }

        return nv21
    }
}
