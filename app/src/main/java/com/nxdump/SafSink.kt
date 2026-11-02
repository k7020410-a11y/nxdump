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
 * Writes extracted files into a SAF tree.
 *
 * - Every file is written to "<name>.part" and renamed to "<name>" only after all bytes are there,
 *   so a half-written file never looks finished.
 * - The top folder carries a small marker file holding a fingerprint of the request. If a folder with the
 *   wanted name already has the same fingerprint, it is reused and finished files are skipped (resume).
 *   Otherwise a new "name (2)", "name (3)" ... folder is used, like before.
 */
class SafSink(
    ctx: Context,
    private val tree: Uri,
    wantedName: String,
    private val fingerprint: String,
) : Dumper.Sink {

    private class Child(
        val uri: Uri,
        val isDir: Boolean,
        val size: Long,
    )

    private class Cur(
        val parentPath: String,
        val name: String,
        val uri: Uri,
        val temp: Boolean,
    )

    private val resolver = ctx.contentResolver

    private val dirs =
        HashMap<String, Uri>() // relative dir path -> uri

    private val kids =
        HashMap<String, HashMap<String, Child>>() // relative dir path -> children by name

    private var cur: Cur? = null
    private var renameOk: Boolean? = null

    val topName: String
    val resumed: Boolean

    init {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )

        val existingTop = listChildren(root)

        var name = wantedName
        var n = 2
        var topUri: Uri? = null
        var inner: HashMap<String, Child>? = null
        var reuse = false

        while (topUri == null) {
            val existing = existingTop[name]

            if (existing == null) {
                topUri = create(
                    root,
                    Document.MIME_TYPE_DIR,
                    name,
                )
            } else {
                val found =
                    if (existing.isDir) {
                        listChildren(existing.uri)
                    } else {
                        null
                    }

                if (
                    found != null &&
                    readText(found[MARKER]?.uri) == fingerprint
                ) {
                    topUri = existing.uri
                    inner = found
                    reuse = true
                } else {
                    name = "$wantedName ($n)"
                    n++
                }
            }
        }

        val top = topUri
            ?: throw IOException("Cannot create the output folder")

        topName = name
        resumed = reuse

        dirs[""] = top

        val innerKids = inner

        if (innerKids != null) {
            kids[""] = innerKids
        } else {
            kids[""] = HashMap()

            try {
                // Without the marker this run cannot be resumed later.
                val marker = create(
                    top,
                    MIME,
                    MARKER,
                )

                resolver
                    .openOutputStream(marker, "w")
                    ?.use {
                        it.write(
                            fingerprint.toByteArray(
                                Charsets.UTF_8,
                            ),
                        )
                    }

                kids[""]!![MARKER] = Child(
                    uri = marker,
                    isDir = false,
                    size = -1,
                )
            } catch (e: Exception) {
                // Marker failure is not fatal.
            }
        }
    }

    // -------------------------------------------------------------------------
    // SAF helpers
    // -------------------------------------------------------------------------

    private fun listChildren(
        parent: Uri,
    ): HashMap<String, Child> {
        val out = HashMap<String, Child>()

        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(parent),
            )

        resolver
            .query(
                children,
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                        ?: continue

                    out[name] = Child(
                        uri = DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            cursor.getString(0),
                        ),
                        isDir = cursor.getString(2) ==
                            Document.MIME_TYPE_DIR,
                        size =
                            if (cursor.isNull(3)) {
                                -1L
                            } else {
                                cursor.getLong(3)
                            },
                    )
                }
            }

        return out
    }

    private fun create(
        parent: Uri,
        mime: String,
        name: String,
    ): Uri =
        DocumentsContract.createDocument(
            resolver,
            parent,
            mime,
            name,
        ) ?: throw IOException(
            "Cannot create \"$name\" in the output folder",
        )

    private fun delete(uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(
                resolver,
                uri,
            )
        } catch (e: Exception) {
            false
        }

    private fun readText(uri: Uri?): String? {
        if (uri == null) {
            return null
        }

        return try {
            resolver
                .openInputStream(uri)
                ?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
        } catch (e: Exception) {
            null
        }
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

    /**
     * If the provider cannot rename, fall back to writing the final name directly.
     */
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

    private fun parentOf(path: String): String =
        path.substringBeforeLast('/', "")

    private fun dir(path: String): Uri {
        dirs[path]?.let {
            return it
        }

        val parentPath = parentOf(path)
        val parent = dir(parentPath)
        val name = path.substringAfterLast('/')

        val siblings = kidsOf(parentPath)
        val existing = siblings[name]

        val uri =
            if (existing != null) {
                if (!existing.isDir) {
                    throw IOException(
                        "\"$name\" already exists in the output folder " +
                            "and is not a folder",
                    )
                }

                existing.uri
            } else {
                create(
                    parent,
                    Document.MIME_TYPE_DIR,
                    name,
                ).also {
                    siblings[name] = Child(
                        uri = it,
                        isDir = true,
                        size = -1,
                    )

                    // Brand-new folder is known to be empty.
                    kids[path] = HashMap()
                }
            }

        dirs[path] = uri
        return uri
    }

    private fun kidsOf(
        path: String,
    ): HashMap<String, Child> {
        val uri = dir(path)

        return kids.getOrPut(path) {
            listChildren(uri)
        }
    }

    // -------------------------------------------------------------------------
    // Dumper.Sink
    // -------------------------------------------------------------------------

    override fun isComplete(
        relativePath: String,
        size: Long,
    ): Boolean {
        if (!resumed) {
            return false
        }

        val child =
            kidsOf(parentOf(relativePath))[
                relativePath.substringAfterLast('/'),
            ] ?: return false

        return !child.isDir && child.size == size
    }

    override fun open(
        relativePath: String,
    ): OutputStream {
        val parentPath = parentOf(relativePath)
        val name = relativePath.substringAfterLast('/')

        val parent = dir(parentPath)
        val siblings = kidsOf(parentPath)

        // Remove leftovers from an earlier attempt.
        // Otherwise the provider may create "file (1)".
        for (stale in listOf(name, "$name.part")) {
            val old = siblings.remove(stale)
                ?: continue

            if (old.isDir) {
                throw IOException(
                    "\"$stale\" is a folder in the output, expected a file",
                )
            }

            if (!delete(old.uri)) {
                throw IOException(
                    "Cannot replace the old file \"$stale\"",
                )
            }
        }

        var uri = create(
            parent,
            MIME,
            "$name.part",
        )

        var temp = true

        if (renameOk == null) {
            renameOk = supportsRename(uri)
        }

        if (renameOk == false) {
            delete(uri)

            uri = create(
                parent,
                MIME,
                name,
            )

            temp = false
        }

        cur = Cur(
            parentPath = parentPath,
            name = name,
            uri = uri,
            temp = temp,
        )

        val output = resolver.openOutputStream(
            uri,
            "w",
        ) ?: throw IOException(
            "Cannot write $relativePath",
        )

        return BufferedOutputStream(
            output,
            1 shl 20,
        )
    }

    override fun commit(
        relativePath: String,
        size: Long,
    ) {
        val current = cur
            ?: return

        cur = null

        val actual = sizeOf(current.uri)

        if (actual >= 0 && actual != size) {
            delete(current.uri)

            throw IOException(
                "Incomplete output for $relativePath " +
                    "($actual of $size bytes)",
            )
        }

        var finalUri = current.uri

        if (current.temp) {
            finalUri =
                DocumentsContract.renameDocument(
                    resolver,
                    current.uri,
                    current.name,
                ) ?: throw IOException(
                    "Cannot finish $relativePath " +
                        "(rename failed)",
                )
        }

        kidsOf(current.parentPath)[current.name] = Child(
            uri = finalUri,
            isDir = false,
            size = size,
        )
    }

    /**
     * Deletes the file being written when cancelled or failed.
     */
    fun discardPartial() {
        val current = cur
            ?: return

        cur = null
        delete(current.uri)
    }

    private companion object {
        const val MIME = "application/octet-stream"
        const val MARKER = "nxdump-job"
    }
}
