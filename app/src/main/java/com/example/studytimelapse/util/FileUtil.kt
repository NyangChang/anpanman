package com.example.studytimelapse.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtil {

    private const val TIMELAPSE_DIR = "StudyTimeLapse"
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Returns a [File] pointing to a new (not yet created) MP4 in the
     * app's external files directory.  Works on API 26+.
     */
    fun newTimeLapseFile(context: Context): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On API 29+ use scoped storage (app-specific external dir requires no permission)
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), TIMELAPSE_DIR)
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                TIMELAPSE_DIR
            )
        }
        dir.mkdirs()

        val timestamp = dateFormat.format(Date())
        return File(dir, "timelapse_$timestamp.mp4")
    }

    /**
     * Notifies the media scanner about a saved video file so it appears in
     * the system gallery.  Uses [MediaScannerConnection] which correctly
     * handles both app-specific and public external storage paths on all
     * supported API levels.
     */
    fun addToMediaStore(context: Context, file: File) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4"),
            null,
        )
    }
}
