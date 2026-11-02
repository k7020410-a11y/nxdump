package com.nxdump

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.nxdump.core.Dumper
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Writes a single file (the exported update NSP) straight into the root of the SAF output folder.
 *
 * - The data goes to "<name>.part" and is renamed to "<name>" only after every byte is there, so a half-written
 *   NSP never looks finished (same idea as SafSink).
 * - An existing file with the same name is never overwritten: "name (2).nsp", "name (3).nsp" ... is used instead.
 */
class NspSink(
    ctx: Context,
    private val tree: Uri,
) : Dumper.Sink {

    private val resolver = ctx.contentResolver

    private val root: Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )

    private var curUri: Uri? = null
    private var curName: String? = null
    private var curTemp = false

    /** Final file name of the finished NSP (may differ from the wanted one if that name was taken). */
    var finalName: String? = null
        private set

    private fun children(): HashMap<String, Uri> {
        val out = HashMap<String, Uri>()

        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(root),
            )

        resolver
            .query(
                childrenUri,
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                        ?: continue

                    out[name] = DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        cursor.getString(0),
                    )
                }
            }

        return out
    }

    private fun delete(uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            false
        }

    private fun supportsRename(uri: Uri): Boolean =
        try {
            resolver
                .query(
                    uri,
                    arrayOf(Document.COLUMN_FLAGS),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    cursor.moveToFirst() &&
                        (
                            cursor.getInt(0) and
                                Document.FLAG_SUPPORTS_RENAME
                            ) != 0
                } ?: false
        } catch (e: Exception) {
            false
        }

    private fun sizeOf(uri: Uri): Long =
        try {
            resolver
                .query(
                    uri,
                    arrayOf(Document.COLUMN_SIZE),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (
                        cursor.moveToFirst() &&
                        !cursor.isNull(0)
                    ) {
                        cursor.getLong(0)
                    } else {
                        -1L
                    }
                } ?: -1L
        } catch (e: Exception) {
            -1L
        }

    override fun open(relativePath: String): OutputStream {
        val kids = children()

        val stem = relativePath.removeSuffix(".nsp")

        var name = relativePath
        var n = 2

        while (kids.containsKey(name)) {
            name = "$stem ($n).nsp"
            n++
        }

        // Leftover from an earlier failed attempt: remove it, otherwise the provider creates "name.part (1)".
        kids["$name.part"]?.let {
            if (!delete(it)) {
                throw IOException("Cannot replace the old file \"$name.part\"")
            }
        }

        var uri = DocumentsContract.createDocument(
            resolver,
            root,
            MIME,
            "$name.part",
        ) ?: throw IOException("Cannot create \"$name\" in the output folder")

        var temp = true

        if (!supportsRename(uri)) {
            delete(uri)

            uri = DocumentsContract.createDocument(
                resolver,
                root,
                MIME,
                name,
            ) ?: throw IOException("Cannot create \"$name\" in the output folder")

            temp = false
        }

        curUri = uri
        curName = name
        curTemp = temp

        val output = resolver.openOutputStream(uri, "w")
            ?: throw IOException("Cannot write $name")

        return BufferedOutputStream(output, 1 shl 20)
    }

    override fun commit(
        relativePath: String,
        size: Long,
    ) {
        val uri = curUri
            ?: return

        val name = curName
            ?: return

        curUri = null
        curName = null

        val actual = sizeOf(uri)

        if (actual >= 0 && actual != size) {
            delete(uri)

            throw IOException(
                "Incomplete output for $name ($actual of $size bytes)",
            )
        }

        if (curTemp) {
            DocumentsContract.renameDocument(
                resolver,
                uri,
                name,
            ) ?: throw IOException("Cannot finish $name (rename failed)")
        }

        finalName = name
    }

    /** Deletes the file being written when the export was cancelled or failed. */
    fun discardPartial() {
        val uri = curUri
            ?: return

        curUri = null
        curName = null

        delete(uri)
    }

    private companion object {
        const val MIME = "application/octet-stream"
    }
}
