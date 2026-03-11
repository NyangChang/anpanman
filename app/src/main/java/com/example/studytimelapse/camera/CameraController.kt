package com.example.studytimelapse.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thin wrapper around CameraX.
 *
 * Binds a [Preview] use case (for live viewfinder) and an [ImageAnalysis]
 * use case (for frame-by-frame processing).  The [ImageAnalysis] output is
 * intentionally exposed via a callback so the timelapse layer and any future
 * motion-detection layer can each subscribe independently.
 *
 * @param context        Application / activity context.
 * @param lifecycleOwner The lifecycle owner that controls camera lifetime.
 * @param previewView    Surface on which the live preview is rendered.
 * @param onFrame        Called on [analysisExecutor] for every frame delivered
 *                       by [ImageAnalysis].  Callers must close [ImageProxy]
 *                       when done.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (ImageProxy) -> Unit,
) {

    /** Dedicated single-thread executor for image analysis. */
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null

    /** Start camera and bind use cases.  Safe to call multiple times. */
    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases(cameraProvider!!)
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbind all use cases and release resources. */
    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            // Keep only the latest frame; drop stale frames automatically.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    onFrame(proxy)
                }
            }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
