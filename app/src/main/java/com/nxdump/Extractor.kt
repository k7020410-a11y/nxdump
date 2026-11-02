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

class ExtractService : Service() {
    private val channelId = "extract"

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val req = Extractor.pending

        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Extraction",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        // Android requires startForeground() after every startForegroundService().
        ServiceCompat.startForeground(
            this,
            1,
            notification(
                req?.title ?: "NxDump",
                "Preparing…",
                0,
                true,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (req == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Never run two extractions at the same time.
        if (Extractor.worker?.isAlive == true) {
            return START_NOT_STICKY
        }

        acquireWakeLock()

        Extractor.worker = Thread(
            { run(req, nm) },
            "nxdump-extract",
        ).also {
            it.start()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        this.releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Keeps the CPU running with the screen off.
     *
     * Requires:
     * <uses-permission android:name="android.permission.WAKE_LOCK" />
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)

            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "nxdump:extract",
            ).apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Throwable) {
            // Permission missing or not allowed: continue without WakeLock.
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Throwable) {
            // Ignore WakeLock release errors.
        }

        wakeLock = null
    }

    private fun notification(
        title: String,
        text: String,
        pct: Int,
        indeterminate: Boolean,
        ongoing: Boolean = true,
    ): Notification =
        Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .apply {
                if (ongoing) {
                    setProgress(100, pct, indeterminate)
                }
            }
            .build()

    /**
     * Same request + same input files = same fingerprint.
     * Used to decide whether an existing output folder can be resumed.
     */
    private fun fingerprint(
        ctx: Context,
        r: Request,
    ): String {
        fun sizeOf(uriString: String?): Long =
            try {
                if (uriString == null) {
                    -1L
                } else {
                    ctx.contentResolver
                        .openFileDescriptor(Uri.parse(uriString), "r")
                        ?.use { it.statSize }
                        ?: -1L
                }
            } catch (e: Exception) {
                -1L
            }

        val raw = listOf(
            "v1",
            r.appId,
            r.baseUri,
            sizeOf(r.baseUri),
            r.updateUri ?: "-",
            sizeOf(r.updateUri),
            r.exefs,
            r.romfs,
        ).joinToString("|") +
            if (r.mode == Mode.EXTRACT) {
                ""
            } else {
                "|${r.mode}|${r.titleId}|${r.version}"
            }

        return MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") {
                "%02x".format(it)
            }
    }

    private fun progressFor(
        r: Request,
        nm: NotificationManager,
    ): Dumper.Progress {
        var lastUi = 0L
        var lastNotif = 0L

        return object : Dumper.Progress {
            override fun update(
                file: String,
                done: Long,
                total: Long,
            ) {
                val now = SystemClock.elapsedRealtime()

                if (done < total && now - lastUi < 150) {
                    return
                }

                lastUi = now

                Extractor.state.value =
                    Extractor.state.value.copy(
                        file = file,
                        done = done,
                        total = total,
                    )

                if (now - lastNotif > 1000) {
                    lastNotif = now

                    val pct =
                        if (total > 0) {
                            (done * 100 / total).toInt()
                        } else {
                            0
                        }

                    nm.notify(
                        1,
                        notification(
                            r.title,
                            "$pct%  \u00B7  $file",
                            pct,
                            false,
                        ),
                    )
                }
            }

            override fun cancelled(): Boolean {
                return Extractor.cancelFlag
            }
        }
    }

    /** ExeFS / RomFS extraction. Returns the output folder name. */
    private fun extractFiles(
        ctx: Context,
        r: Request,
        keys: Keys,
        tree: Uri,
        progress: Dumper.Progress,
        onSink: (SafSink) -> Unit,
    ): String {
        val folder =
            (
                r.title
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                    .ifEmpty { "game" }
                ) +
                (
                    if (r.mode == Mode.UPDATE_FILES) {
                        " [%016X] update v%d".format(r.titleId, r.version)
                    } else {
                        " [%016X]".format(r.appId)
                    }
                    ) +
                if (r.updateUri != null) {
                    " + update"
                } else {
                    ""
                }

        val s = SafSink(
            ctx,
            tree,
            folder,
            fingerprint(ctx, r),
        )

        onSink(s)

        if (r.mode == Mode.UPDATE_FILES) {
            openContainer(
                ctx,
                Uri.parse(r.baseUri),
                r.baseName,
            ).use { c ->
                val res = Dumper.dumpUpdateOnly(
                    c,
                    r.titleId,
                    r.version,
                    r.exefs,
                    r.romfs,
                    keys,
                    s,
                    progress,
                )

                Extractor.state.value =
                    Extractor.state.value.copy(note = res.note)
            }

            return s.topName
        }

        openContainer(
            ctx,
            Uri.parse(r.baseUri),
            r.baseName,
        ).use { base ->
            val updateUri = r.updateUri

            val update =
                if (updateUri == null) {
                    null
                } else if (updateUri == r.baseUri) {
                    base
                } else {
                    openContainer(
                        ctx,
                        Uri.parse(updateUri),
                        r.updateName ?: "update",
                    )
                }

            try {
                Dumper.dump(
                    base,
                    update,
                    r.appId,
                    r.exefs,
                    r.romfs,
                    keys,
                    s,
                    progress,
                )
            } finally {
                if (update != null && update !== base) {
                    update.close()
                }
            }
        }

        return s.topName
    }

    /** Exports the update as a standalone NSP in the output folder. Returns the NSP file name. */
    private fun exportUpdateNsp(
        ctx: Context,
        r: Request,
        keys: Keys,
        tree: Uri,
        progress: Dumper.Progress,
        onSink: (NspSink) -> Unit,
    ): String {
        return openContainer(
            ctx,
            Uri.parse(r.baseUri),
            r.baseName,
        ).use { c ->
            val plan = UpdateNsp.plan(
                c,
                keys,
                r.titleId,
                r.version,
            )

            val s = NspSink(ctx, tree)

            onSink(s)

            UpdateNsp.export(c, plan, s, progress)

            if (plan.warnings.isNotEmpty()) {
                Extractor.state.value =
                    Extractor.state.value.copy(
                        note = plan.warnings.joinToString("\n"),
                    )
            }

            s.finalName ?: plan.fileName
        }
    }

    private fun run(
        r: Request,
        nm: NotificationManager,
    ) {
        val ctx = applicationContext

        var phase = Phase.DONE
        var msg: String? = null
        var sink: SafSink? = null
        var nspSink: NspSink? = null

        try {
            val keys = KeyStore.load(ctx)
                ?: throw IOException("Keys are not set up")

            val tree = Prefs(ctx)
                .outputTree
                ?.let { Uri.parse(it) }
                ?: throw IOException("Output folder is not set")

            val progress = progressFor(r, nm)

            msg =
                if (r.mode == Mode.UPDATE_NSP) {
                    exportUpdateNsp(ctx, r, keys, tree, progress) { nspSink = it }
                } else {
                    extractFiles(ctx, r, keys, tree, progress) { sink = it }
                }
        } catch (e: Throwable) {
            if (
                Extractor.cancelFlag ||
                e is InterruptedIOException ||
                e is ClosedByInterruptException
            ) {
                phase = Phase.CANCELLED
                msg =
                    if (r.mode == Mode.UPDATE_NSP) {
                        "Cancelled. The unfinished NSP was removed."
                    } else {
                        "Cancelled. Finished files were kept; " +
                            "start the same extraction again to continue."
                    }
            } else {
                phase = Phase.FAILED
                msg = e.message ?: e.toString()
            }
        } finally {
            // Remove only the half-written file, if one exists.
            sink?.discardPartial()
            nspSink?.discardPartial()
            this@ExtractService.releaseWakeLock()
        }

        Extractor.state.value =
            Extractor.state.value.copy(
                phase = phase,
                message = msg,
            )

        ServiceCompat.stopForeground(
            this,
            ServiceCompat.STOP_FOREGROUND_REMOVE,
        )

        val done = if (r.mode == Mode.UPDATE_NSP) "Export complete" else "Extraction complete"

        val text =
            when (phase) {
                Phase.DONE -> done
                Phase.CANCELLED -> "Cancelled"
                else -> "Failed: $msg"
            }

        nm.notify(
            2,
            notification(
                r.title,
                text,
                0,
                false,
                ongoing = false,
            ),
        )

        Extractor.pending = null
        Extractor.worker = null
        Extractor.running.set(false)

        stopSelf()
    }
}
