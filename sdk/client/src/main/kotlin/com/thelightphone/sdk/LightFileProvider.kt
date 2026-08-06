package com.thelightphone.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class LightFileProvider : ContentProvider() {

    internal companion object {
        const val SHARED_DIR = "shared"
    }

    private fun checkCaller() {
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw SecurityException("Access denied")
        }
    }

    private fun resolveFile(uri: Uri): File {
        val path = uri.path?.removePrefix("/").orEmpty()
        val root = File(context!!.filesDir, SHARED_DIR).canonicalFile
        val file = if (path.isEmpty()) root else File(root, path).canonicalFile
        if (!file.path.startsWith(root.path)) {
            throw SecurityException("Path traversal not allowed")
        }
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        val file = resolveFile(uri)
        val pfdMode = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            else -> throw IllegalArgumentException("Unsupported mode: $mode")
        }
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkCaller()
        val file = resolveFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)

        val children = if (file.isDirectory) file.listFiles().orEmpty() else arrayOf(file)
        for (child in children) {
            cursor.addRow(columns.map { col ->
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> child.name
                    OpenableColumns.SIZE -> if (child.isFile) child.length() else null
                    else -> null
                }
            })
        }
        return cursor
    }
    override fun getType(uri: Uri): String? {
        val path = uri.path ?: return null
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(path.substringAfterLast('.', ""))
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
