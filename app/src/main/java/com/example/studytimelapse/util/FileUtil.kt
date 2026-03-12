package com.example.studytimelapse.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
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
     * Returns a [File] for encoding output.
     * On API 29+ this is a temp file in app-specific storage; after encoding,
     * call [addToMediaStore] to copy it into the public Movies folder.
     * On API <29 it is written directly to the public Movies folder.
     */
    fun newTimeLapseFile(context: Context): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
     * Makes [file] visible in the system gallery.
     *
     * On API 29+ [MediaScannerConnection] cannot make files inside
     * `getExternalFilesDir()` visible to other apps; the file is instead
     * copied into the public Movies directory via the MediaStore API and
     * the temporary source file is deleted.
     *
     * On API <29 a plain MediaScanner scan suffices.
     */
    fun addToMediaStore(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$TIMELAPSE_DIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            )
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                file.delete()
            }
        } else {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4"),
                null,
            )
        }
    }
}
