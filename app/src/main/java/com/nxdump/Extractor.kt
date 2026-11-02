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
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class Request(
    val appId: Long,
    val title: String,
    val baseUri: String,
    val baseName: String,
    val updateUri: String?,
    val updateName: String?,
    val exefs: Boolean,
    val romfs: Boolean,
)

enum class Phase { IDLE, RUNNING, DONE, FAILED, CANCELLED }

data class ExtractState(
    val phase: Phase = Phase.IDLE,
    val title: String = "",
    val file: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val message: String? = null,
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
        state.value = ExtractState(Phase.RUNNING, r.title)

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
        ).joinToString("|")

        return MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") {
                "%02x".format(it)
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

        try {
            val keys = KeyStore.load(ctx)
                ?: throw IOException("Keys are not set up")

            val tree = Prefs(ctx)
                .outputTree
                ?.let { Uri.parse(it) }
                ?: throw IOException("Output folder is not set")

            val folder =
                (
                    r.title
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .trim()
                        .ifEmpty { "game" }
                    ) +
                    " [%016X]".format(r.appId) +
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

            sink = s
            msg = s.topName

            var lastUi = 0L
            var lastNotif = 0L

            val progress = object : Dumper.Progress {
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
                                "$pct%  ·  $file",
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
        } catch (e: Throwable) {
            if (
                Extractor.cancelFlag ||
                e is InterruptedIOException ||
                e is ClosedByInterruptException
            ) {
                phase = Phase.CANCELLED
                msg =
                    "Cancelled. Finished files were kept; " +
                    "start the same extraction again to continue."
            } else {
                phase = Phase.FAILED
                msg = e.message ?: e.toString()
            }
        } finally {
            // Remove only the half-written file, if one exists.
            sink?.discardPartial()
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

        val text =
            when (phase) {
                Phase.DONE -> "Extraction complete"
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
