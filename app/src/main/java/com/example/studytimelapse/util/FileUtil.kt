package com.example.studytimelapse.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
     * Registers a saved video file with the MediaStore so it appears in the
     * system gallery immediately.  Required on API 29+; harmless on older versions.
     */
    fun addToMediaStore(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$TIMELAPSE_DIR")
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }

        try {
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
