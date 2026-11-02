package com.nxdump

import android.content.Context
import android.net.Uri
import com.nxdump.core.Keys
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object KeyStore {
    private const val PROD_KEYS_NAME = "prod.keys"
    private const val TITLE_KEYS_NAME = "title.keys"

    fun load(ctx: Context): Keys? {
        val prod = File(ctx.filesDir, PROD_KEYS_NAME)
        if (!prod.exists()) return null

        val title = File(ctx.filesDir, TITLE_KEYS_NAME)
        val prodStream = FileInputStream(prod)
        val titleStream = if (title.exists()) FileInputStream(title) else null

        return try {
            Keys.load(prodStream, titleStream)
        } catch (_: IOException) {
            null
        } finally {
            try {
                prodStream.close()
            } catch (_: Exception) {
            }
            try {
                titleStream?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun import(ctx: Context, uri: Uri, title: Boolean): String? {
        val input = ctx.contentResolver.openInputStream(uri)
            ?: return "Cannot open key file"

        val outFile = File(ctx.filesDir, if (title) TITLE_KEYS_NAME else PROD_KEYS_NAME)

        return try {
            FileOutputStream(outFile).use { out ->
                input.use { it.copyTo(out) }
            }
            null
        } catch (e: Exception) {
            e.message ?: "Failed to import keys"
        }
    }

    fun summary(keys: Keys): String {
        val prodCount = keys.count()
        val titleCount = keys.titleKeyCount()

        return when {
            prodCount == 0 && titleCount == 0 -> "No keys loaded"
            titleCount == 0 -> "$prodCount prod keys loaded"
            prodCount == 0 -> "$titleCount title keys loaded"
            else -> "$prodCount prod keys + $titleCount title keys loaded"
        }
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, PROD_KEYS_NAME).delete()
        File(ctx.filesDir, TITLE_KEYS_NAME).delete()
    }
}
