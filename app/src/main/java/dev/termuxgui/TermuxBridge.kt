package dev.termuxgui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class CmdResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && (exitCode == null || exitCode == 0)

    val text: String
        get() {
            val sb = StringBuilder()
            if (stdout.isNotEmpty()) sb.append(stdout)
            if (stderr.isNotEmpty()) {
                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                sb.append(stderr)
            }
            if (error != null) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("⚠ ").append(error)
            }
            return sb.toString().trimEnd()
        }
}

object TermuxBridge {
    const val TERMUX_PKG = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "$PREFIX/bin/bash"
    const val RESULT_ACTION = "dev.termuxgui.TERMUX_RESULT"

    const val SETUP_COMMAND =
        "mkdir -p ~/.termux; sed -i '/^allow-external-apps/d' ~/.termux/termux.properties 2>/dev/null; " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; termux-reload-settings"

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<CmdResult>>()
    private val ids = AtomicInteger(1)

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TERMUX_PKG, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun openTermux(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(TERMUX_PKG)?.let { ctx.startActivity(it) }
    }

    fun complete(id: Int, r: CmdResult) {
        pending.remove(id)?.complete(r)
    }

    suspend fun run(
        ctx: Context,
        command: String,
        workdir: String = HOME,
        timeoutMs: Long = 120_000L,
    ): CmdResult {
        val app = ctx.applicationContext
        if (!isInstalled(app)) return CmdResult(error = "Termux غير مثبّت")
        if (!hasPermission(app)) return CmdResult(error = "صلاحية RUN_COMMAND غير ممنوحة")

        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<CmdResult>()
        pending[id] = deferred

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val resultIntent = Intent(app, ResultReceiver::class.java).apply {
            action = RESULT_ACTION
            putExtra("id", id)
        }
        val pi = PendingIntent.getBroadcast(app, id, resultIntent, flags)

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        }

        try {
            app.startService(intent)
        } catch (e: Exception) {
            pending.remove(id)
            return CmdResult(error = e.message ?: e.javaClass.simpleName)
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: CmdResult(error = "انتهت المهلة")
        } finally {
            pending.remove(id)
        }
    }
}

class ResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val b = intent.getBundleExtra("result")
        val res = if (b == null) {
            CmdResult(error = "لم يصل ناتج من Termux")
        } else {
            CmdResult(
                stdout = b.getString("stdout").orEmpty(),
                stderr = b.getString("stderr").orEmpty(),
                exitCode = if (b.containsKey("exitCode")) b.getInt("exitCode") else null,
                error = b.getString("errmsg")?.takeIf { it.isNotBlank() },
            )
        }
        TermuxBridge.complete(id, res)
    }
}
