package com.jono.githubwidget

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves the saved repo/app icons to the launcher by URI, so icons don't travel inside every
 * widget update (which has a size limit), even with a 60-repo list in several layouts.
 *
 * URI shape: content://com.jono.githubwidget.icons/<16 hex chars>
 */
class IconProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.pathSegments.firstOrNull()
        val ctx = context
        // Icon ids are short hex hashes; anything else (e.g. a path) is refused.
        if (ctx == null || id == null || !id.matches(Regex("[0-9a-f]{16}"))) {
            throw FileNotFoundException(uri.toString())
        }
        val file = Apps.iconFile(ctx, id)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0
}
