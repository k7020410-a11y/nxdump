package com.nxdump

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nxdump.core.Dumper
import com.nxdump.core.Keys
import com.nxdump.core.UpdateNsp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class Mode {
    /** ExeFS / RomFS extraction, optionally with an update merged in. */
    EXTRACT,

    /** Export one update as a standalone NSP (baseUri/baseName = the file that holds the update). */
    UPDATE_NSP,

    /** Decrypted ExeFS + RomFS of one update on its own, without the base game. */
    UPDATE_FILES,
}

data class Request(
    val appId: Long,
    val title: String,
    val baseUri: String,
    val baseName: String,
    val updateUri: String?,
    val updateName: String?,
    val exefs: Boolean,
    val romfs: Boolean,
    val mode: Mode = Mode.EXTRACT,
    /** UPDATE_NSP / UPDATE_FILES only: title id / version of the update to use. */
    val titleId: Long = 0,
    val version: Long = -1,
)

enum class Phase { IDLE, RUNNING, DONE, FAILED, CANCELLED }

data class ExtractState(
    val phase: Phase = Phase.IDLE,
    val title: String = "",
    val file: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val message: String? = null,
    /** true while / after exporting an update NSP (changes the wording in the dialog). */
    val nsp: Boolean = false,
    /** Extra remarks for the user (for example a missing ticket). */
    val note: String? = null,
)

object Extractor {
    val state = MutableStateFlow(ExtractState())

    @Volatile
    var cancelFlag = false

    @Volatile
    var pending: Request? = null

    @Volatile
    var worker: Thread? = null

    /** true from start() until the worker thread is completely finished. */
    val running = AtomicBoolean(false)

    fun reset() {
        pending = null
        worker = null
        running.set(false)
    }

    fun start(ctx: Context, r: Request) {
        if (!running.compareAndSet(false, true)) return

        pending = r
        cancelFlag = false
        state.value = ExtractState(Phase.RUNNING, r.title, nsp = r.mode == Mode.UPDATE_NSP)

        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ExtractService::class.java),
            )
        } catch (e: Throwable) {
            pending = null
            running.set(false)
            state.value = ExtractState(
                phase = Phase.FAILED,
                title = r.title,
                message = e.message ?: e.toString(),
                nsp = r.mode == Mode.UPDATE_NSP,
            )
        }
    }

    /** Stops for real: raises the flag and interrupts the worker thread. */
    fun cancel() {
        cancelFlag = true
        worker?.interrupt()
    }

    fun dismiss() {
        if (state.value.phase != Phase.RUNNING) {
            state.value = ExtractState()
        }
    }
}
