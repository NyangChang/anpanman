package com.example.studytimelapse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.studytimelapse.camera.CameraController
import com.example.studytimelapse.camera.toBitmapCompat
import com.example.studytimelapse.databinding.ActivityMainBinding
import com.example.studytimelapse.ui.MainViewModel
import com.example.studytimelapse.ui.RecordingState

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var cameraController: CameraController? = null

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG)
                    .show()
                binding.btnRecord.isEnabled = false
            }
        }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRecord.setOnClickListener { onRecordButtonClicked() }

        viewModel.recordingState.observe(this) { state ->
            renderState(state)
        }

        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController?.stop()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCamera()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    private fun startCamera() {
        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = binding.previewView,
            onFrame = { proxy ->
                try {
                    val bitmap = proxy.toBitmapCompat()
                    val ts = System.currentTimeMillis()
                    viewModel.onCameraFrame(bitmap, ts)
                    bitmap.recycle()
                } finally {
                    proxy.close()
                }
            },
        )
        cameraController!!.start()
    }

    // -------------------------------------------------------------------------
    // UI interactions
    // -------------------------------------------------------------------------

    private fun onRecordButtonClicked() {
        when (viewModel.recordingState.value) {
            is RecordingState.Recording -> viewModel.stopRecording()
            else -> viewModel.startRecording()
        }
    }

    // -------------------------------------------------------------------------
    // State rendering
    // -------------------------------------------------------------------------

    private fun renderState(state: RecordingState) {
        when (state) {
            RecordingState.Idle -> {
                binding.btnRecord.setText(R.string.btn_start)
                binding.btnRecord.isEnabled = true
                binding.recordingIndicator.visibility = View.INVISIBLE
                binding.tvStatus.text = getString(R.string.status_idle)
            }

            is RecordingState.Recording -> {
                binding.btnRecord.setText(R.string.btn_stop)
                binding.recordingIndicator.visibility = View.VISIBLE
                binding.tvStatus.text = getString(
                    R.string.status_recording,
                    state.elapsedSeconds,
                    state.frameCount,
                )
                // Future: display state.motionScore on a progress bar or map character
            }

            is RecordingState.Saved -> {
                binding.btnRecord.setText(R.string.btn_start)
                binding.recordingIndicator.visibility = View.INVISIBLE
                binding.tvStatus.text = getString(
                    R.string.status_saved,
                    state.outputFile.name,
                    state.frameCount,
                )
                Toast.makeText(
                    this,
                    getString(R.string.toast_saved, state.outputFile.absolutePath),
                    Toast.LENGTH_LONG,
                ).show()
            }

            is RecordingState.Error -> {
                binding.btnRecord.isEnabled = true
                binding.recordingIndicator.visibility = View.INVISIBLE
                binding.tvStatus.text = getString(R.string.status_error, state.message)
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
