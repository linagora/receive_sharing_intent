package com.kasem.receive_sharing_intent

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*
import android.webkit.MimeTypeMap


object FileDirectory {

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @author paulburke
     */
    fun getAbsolutePath(context: Context, uri: Uri): String? {

        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                return if ("primary".equals(type, ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                } else {
                    getDataColumn(context, uri, null, null)
                }
            } else if (isDownloadsDocument(uri)) {
                return try {
                    val id = DocumentsContract.getDocumentId(uri)
                    val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id))

                    getDataColumn(context, contentUri, null, null)
                } catch (exception: Exception) {
                    getDataColumn(context, uri, null, null)
                }
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                var contentUri: Uri? = null
                when (type) {
                    "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                if (contentUri == null) return null

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }// MediaProvider
            // DownloadsProvider
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        }

        return uri.path
    }

    /**
     * [getAbsolutePath], or null when [uri] cannot be read (no grant, file gone,
     * provider error), so a bad share never crashes the host app.
     */
    fun getReadablePath(context: Context, uri: Uri): String? {
        return try {
            getAbsolutePath(context, uri)
        } catch (e: Exception) {
            // External input boundary: any provider failure means skip the file.
            Log.w("FileDirectory", "Cannot read shared uri from ${uri.authority}: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    private fun getDataColumn(context: Context, uri: Uri, selection: String?,
                              selectionArgs: Array<String>?): String? {

        if (uri.authority != null) return copyToCacheDir(context, uri, selection, selectionArgs)

        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    /** Copy the content behind [uri] into the cache dir; null when it has no readable stream. */
    private fun copyToCacheDir(context: Context, uri: Uri, selection: String?,
                               selectionArgs: Array<String>?): String? {
        val targetFile = runCatching { displayNameFile(context, uri, selection, selectionArgs) }.getOrNull()
                ?: generatedNameFile(context, uri)
        // A null stream means nothing was copied, so there is no file to report.
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return copyOrCleanUp(input, targetFile)
    }

    private fun displayNameFile(context: Context, uri: Uri, selection: String?,
                                selectionArgs: Array<String>?): File? {
        val column = "_display_name"
        val cursor = context.contentResolver.query(uri, arrayOf(column), selection, selectionArgs, null)
        return cursor?.use {
            if (!it.moveToFirst()) return null
            cacheFileFor(context.cacheDir, it.getString(it.getColumnIndexOrThrow(column)))
        }
    }

    private fun generatedNameFile(context: Context, uri: Uri): File {
        val mimeType = context.contentResolver.getType(uri)
        val prefix = with(mimeType ?: "") {
            when {
                startsWith("image") -> "IMG"
                startsWith("video") -> "VID"
                else -> "FILE"
            }
        }
        val type = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return File(context.cacheDir, "${prefix}_${Date().time}.$type")
    }

    private fun copyOrCleanUp(input: InputStream, targetFile: File): String {
        // Copy aside and move on success, so a failed copy never touches an existing cache entry.
        input.use { stream ->
            val partFile = File.createTempFile("share_", ".part", targetFile.parentFile)
            try {
                FileOutputStream(partFile).use { stream.copyTo(it) }
                if (!partFile.renameTo(targetFile)) throw IOException("Cannot move shared file to $targetFile")
            } finally {
                partFile.delete()
            }
        }
        return targetFile.path
    }

    /**
     * Resolve a provider-supplied display name to a file directly inside
     * [cacheDir]. Only the base name is used, so the result is never a nested
     * or parent path.
     *
     * @param cacheDir The app cache directory.
     * @param displayName The `_display_name` reported by the content provider.
     * @return The target file, or null if no usable name is left or it is
     * not a valid path.
     */
    internal fun cacheFileFor(cacheDir: File, displayName: String?): File? {
        val fileName = displayName?.let { File(it).name }
        if (fileName.isNullOrEmpty()) return null
        val candidate = File(cacheDir, fileName)
        val cacheRoot = cacheDir.canonicalPath + File.separator
        return try {
            if (candidate.canonicalPath.startsWith(cacheRoot)) candidate else null
        } catch (e: IOException) {
            null
        }
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }
}