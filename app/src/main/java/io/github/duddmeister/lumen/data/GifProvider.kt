package io.github.duddmeister.lumen.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves sent GIFs, read-only, to the app they were sent to. It is not
 * exported: the receiving app gets access to one GIF at a time through the
 * read grant that comes with the keyboard's commitContent call.
 */
class GifProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String = "image/gif"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("GIFs are read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val f = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment
        val context = context
        if (context == null || name == null || uri.pathSegments.size != 1 || !NAME.matches(name)) {
            throw FileNotFoundException(uri.toString())
        }
        val f = File(GifFiles.dir(context), name)
        if (!f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    private companion object {
        val NAME = Regex("[A-Za-z0-9]+\\.gif")
    }
}
