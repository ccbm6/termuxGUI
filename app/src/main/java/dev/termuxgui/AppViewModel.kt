package dev.termuxgui

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class TermEntry(val cwd: String, val cmd: String) {
    var output by mutableStateOf<String?>(null)
    var ok by mutableStateOf(true)
}

data class PkgItem(val name: String, val info: String)
data class Script(val name: String, val cmd: String)
data class FileItem(val name: String, val isDir: Boolean)

/** Single-quote a string for safe use inside a shell command. */
fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun parentOf(p: String): String {
    if (p == "/") return "/"
    val t = p.trimEnd('/').substringBeforeLast('/')
    return if (t.isEmpty()) "/" else t
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val prefs = app.getSharedPreferences("termuxgui", Context.MODE_PRIVATE)

    var resumeTick by mutableIntStateOf(0)

    // ---------------- Terminal ----------------
    val terminal = mutableStateListOf<TermEntry>()
    val history = mutableStateListOf<String>()
    var cwd by mutableStateOf(TermuxBridge.HOME)
        private set
    var busy by mutableStateOf(false)
        private set

    fun runTerminal(raw: String) {
        val c = raw.trim()
        if (c.isEmpty() || busy) return
        if (c == "clear") {
            terminal.clear()
            return
        }
        history.remove(c)
        history.add(0, c)
        if (history.size > 100) history.removeAt(history.lastIndex)
        val e = TermEntry(cwd, c)
        terminal.add(e)
        busy = true
        viewModelScope.launch {
            val isCd = c == "cd" || c.startsWith("cd ")
            val r = TermuxBridge.run(ctx, if (isCd) "$c && pwd" else c, cwd, 600_000L)
            if (isCd && r.ok) {
                val p = r.stdout.trim().lines().lastOrNull()
                if (p != null && p.startsWith("/")) cwd = p
                e.output = ""
            } else {
                e.output = r.text.takeLast(30_000)
            }
            e.ok = r.ok
            busy = false
        }
    }

    // ---------------- Packages ----------------
    var installed by mutableStateOf<List<PkgItem>>(emptyList())
        private set
    var results by mutableStateOf<List<PkgItem>>(emptyList())
        private set
    var pkgBusy by mutableStateOf(false)
        private set
    var pkgLog by mutableStateOf("")
        private set

    fun loadInstalled() = viewModelScope.launch {
        pkgBusy = true
        val r = TermuxBridge.run(ctx, "dpkg-query -W -f='\${Package} \${Version}\\n'")
        if (r.ok) {
            installed = r.stdout.lines().mapNotNull { l ->
                val p = l.trim().split(" ", limit = 2)
                if (p[0].isBlank()) null else PkgItem(p[0], p.getOrElse(1) { "" })
            }.sortedBy { it.name }
        } else {
            pkgLog = r.text
        }
        pkgBusy = false
    }

    fun search(q: String) = viewModelScope.launch {
        val query = q.trim()
        if (query.isEmpty()) return@launch
        pkgBusy = true
        val r = TermuxBridge.run(ctx, "apt-cache search ${sq(query)} | head -n 80")
        results = r.stdout.lines().mapNotNull { l ->
            val i = l.indexOf(" - ")
            if (i <= 0) null else PkgItem(l.substring(0, i).trim(), l.substring(i + 3).trim())
        }
        if (results.isEmpty()) pkgLog = r.text.ifBlank { "لا توجد نتائج" }
        pkgBusy = false
    }

    fun pkgAction(title: String, cmd: String) = viewModelScope.launch {
        if (pkgBusy) return@launch
        pkgBusy = true
        pkgLog = "⏳ $title ..."
        val r = TermuxBridge.run(ctx, cmd, timeoutMs = 900_000L)
        pkgLog = (if (r.ok) "✅ $title\n" else "❌ $title\n") + r.text.takeLast(8_000)
        pkgBusy = false
        loadInstalled()
    }

    // ---------------- Files ----------------
    var filesPath by mutableStateOf(TermuxBridge.HOME)
        private set
    var files by mutableStateOf<List<FileItem>>(emptyList())
        private set
    var filesBusy by mutableStateOf(false)
        private set
    var filesMsg by mutableStateOf("")
        private set

    fun openDir(path: String) = viewModelScope.launch {
        filesBusy = true
        val r = TermuxBridge.run(ctx, "ls -1Ap --group-directories-first", path)
        if (r.ok) {
            filesPath = path
            files = r.stdout.lines().filter { it.isNotBlank() }.map {
                if (it.endsWith("/")) FileItem(it.dropLast(1), true) else FileItem(it, false)
            }
            filesMsg = ""
        } else {
            filesMsg = r.text
        }
        filesBusy = false
    }

    fun fileOp(cmd: String) = viewModelScope.launch {
        val r = TermuxBridge.run(ctx, cmd, filesPath)
        val err = if (r.ok) "" else r.text
        openDir(filesPath).join()
        if (err.isNotEmpty()) filesMsg = err
    }

    suspend fun readFile(path: String): CmdResult {
        val q = sq(path)
        return TermuxBridge.run(
            ctx,
            "if [ \"$(wc -c < $q)\" -le 100000 ]; then cat $q; else echo __TOO_BIG__; fi"
        )
    }

    suspend fun writeFile(path: String, text: String): CmdResult {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return TermuxBridge.run(ctx, "printf %s $b64 | base64 -d > ${sq(path)}")
    }

    // ---------------- Scripts ----------------
    val scripts = mutableStateListOf<Script>()
    var scriptOut by mutableStateOf<String?>(null)
        private set
    var scriptTitle by mutableStateOf("")
        private set
    var scriptBusy by mutableStateOf(false)
        private set

    init {
        val s = prefs.getString("scripts", null)
        val list = if (s == null) {
            defaultScripts()
        } else {
            try {
                val a = JSONArray(s)
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Script(o.getString("name"), o.getString("cmd"))
                }
            } catch (e: Exception) {
                defaultScripts()
            }
        }
        scripts.addAll(list)
        if (s == null) saveScripts()
    }

    private fun defaultScripts() = listOf(
        Script("تحديث النظام", "pkg update -y && DEBIAN_FRONTEND=noninteractive pkg upgrade -y"),
        Script("تثبيت Python", "pkg install -y python && python --version"),
        Script("تثبيت Node.js", "pkg install -y nodejs && node --version"),
        Script("تثبيت Git", "pkg install -y git && git --version"),
        Script("تثبيت Ubuntu (proot-distro)", "pkg install -y proot-distro && proot-distro install ubuntu"),
        Script("معلومات الجهاز", "uname -a; echo; head -n 20 /proc/cpuinfo"),
    )

    private fun saveScripts() {
        val a = JSONArray()
        scripts.forEach { a.put(JSONObject().put("name", it.name).put("cmd", it.cmd)) }
        prefs.edit().putString("scripts", a.toString()).apply()
    }

    fun addScript(name: String, cmd: String) {
        scripts.add(Script(name.trim(), cmd))
        saveScripts()
    }

    fun deleteScript(s: Script) {
        scripts.remove(s)
        saveScripts()
    }

    fun runScript(s: Script) = viewModelScope.launch {
        if (scriptBusy) return@launch
        scriptTitle = s.name
        scriptBusy = true
        scriptOut = ""
        val r = TermuxBridge.run(ctx, s.cmd, timeoutMs = 900_000L)
        val head = if (r.ok) "✅ تم\n\n" else "❌ فشل (exit=${r.exitCode})\n\n"
        scriptOut = (head + r.text).takeLast(30_000)
        scriptBusy = false
    }

    fun dismissScript() {
        scriptOut = null
    }

    // ---------------- System ----------------
    var sysOut by mutableStateOf("")
        private set
    var sysBusy by mutableStateOf(false)
        private set

    fun sysRun(title: String, cmd: String) = viewModelScope.launch {
        if (sysBusy) return@launch
        sysBusy = true
        val r = TermuxBridge.run(ctx, cmd)
        sysOut = (title + "\n" + r.text).trim()
        sysBusy = false
    }
}
