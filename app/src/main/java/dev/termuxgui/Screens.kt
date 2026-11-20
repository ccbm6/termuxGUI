@file:OptIn(ExperimentalMaterial3Api::class)

package dev.termuxgui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF0D1117)
private val TermFg = Color(0xFFE6EDF3)
private val TermGreen = Color(0xFF3FB950)
private val TermRed = Color(0xFFF85149)
private val Mono = FontFamily.Monospace

@Composable
private fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { content() }
}

// ============================================================ Root
@Composable
fun AppRoot(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "الطرفية" to Icons.Default.PlayArrow,
        "الحزم" to Icons.Default.Search,
        "الملفات" to Icons.Default.Home,
        "السكربتات" to Icons.Default.Menu,
        "النظام" to Icons.Default.Info,
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text("Termux GUI") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.second, contentDescription = null) },
                        label = { Text(t.first, maxLines = 1, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusBanner(vm)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> TerminalScreen(vm)
                    1 -> PackagesScreen(vm)
                    2 -> FilesScreen(vm)
                    3 -> ScriptsScreen(vm)
                    else -> SystemScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(vm: AppViewModel) {
    val ctx = LocalContext.current
    val ready = remember(vm.resumeTick) {
        TermuxBridge.isInstalled(ctx) && TermuxBridge.hasPermission(ctx)
    }
    if (!ready) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Text(
                "⚠ الاتصال بـ Termux غير جاهز — افتح تبويب «النظام» واتبع خطوات الإعداد.",
                modifier = Modifier.padding(12.dp),
                fontSize = 13.sp,
            )
        }
    }
}

// ============================================================ Terminal
@Composable
private fun TermLine(e: TermEntry) {
    val shown = e.cwd.replace(TermuxBridge.HOME, "~")
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text("$shown \$ ${e.cmd}", color = TermGreen, fontFamily = Mono, fontSize = 13.sp)
            val out = e.output
            if (out == null) {
                Text("⏳ ...", color = Color.Gray, fontFamily = Mono, fontSize = 12.sp)
            } else if (out.isNotEmpty()) {
                Text(
                    out,
                    color = if (e.ok) TermFg else TermRed,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun TerminalScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var hIdx by remember { mutableIntStateOf(-1) }
    val list = rememberLazyListState()
    LaunchedEffect(vm.terminal.size, vm.terminal.lastOrNull()?.output) {
        if (vm.terminal.isNotEmpty()) list.animateScrollToItem(vm.terminal.lastIndex)
    }
    val cmds = listOf("ls -la", "pwd", "cd ..", "cd ~", "clear")
    val syms = listOf("|", "/", "~", "-", ">", "&&", "*")

    Column(Modifier.fillMaxSize()) {
        Surface(color = TermBg, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Ltr {
                if (vm.terminal.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("اكتب أمراً بالأسفل ثم اضغط إرسال", color = Color.Gray)
                    }
                } else {
                    LazyColumn(state = list, contentPadding = PaddingValues(8.dp)) {
                        items(vm.terminal) { e -> TermLine(e) }
                    }
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            cmds.forEach { k ->
                AssistChip(onClick = { vm.runTerminal(k) }, label = { Text(k, fontFamily = Mono) })
            }
            syms.forEach { k ->
                AssistChip(onClick = { input += k }, label = { Text(k, fontFamily = Mono) })
            }
            AssistChip(onClick = { TermuxBridge.openTermux(ctx) }, label = { Text("Termux ↗") })
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (vm.history.isNotEmpty()) {
                    hIdx = (hIdx + 1).coerceAtMost(vm.history.lastIndex)
                    input = vm.history[hIdx]
                }
            }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "السجل") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; hIdx = -1 },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp),
                placeholder = { Text("أمر...") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    vm.runTerminal(input)
                    input = ""
                }),
            )
            IconButton(
                enabled = !vm.busy,
                onClick = {
                    vm.runTerminal(input)
                    input = ""
                },
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال") }
        }
    }
}

// ============================================================ Packages
@Composable
fun PackagesScreen(vm: AppViewModel) {
    var q by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (vm.installed.isEmpty()) vm.loadInstalled() }
    val names = remember(vm.installed) { vm.installed.map { it.name }.toSet() }
    val data = if (mode == 0) {
        vm.installed.filter { it.name.contains(q.trim(), ignoreCase = true) }
    } else {
        vm.results
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("المثبتة (${vm.installed.size})") },
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("بحث في المستودع") },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("اسم الحزمة") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { if (mode == 1) vm.search(q) }),
            )
            if (mode == 1) {
                IconButton(onClick = { vm.search(q) }) {
                    Icon(Icons.Default.Search, contentDescription = "بحث")
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = !vm.pkgBusy,
                onClick = { vm.pkgAction("تحديث القوائم", "pkg update -y") },
            ) { Text("تحديث القوائم") }
            OutlinedButton(
                enabled = !vm.pkgBusy,
                onClick = {
                    vm.pkgAction("ترقية الحزم", "DEBIAN_FRONTEND=noninteractive pkg upgrade -y")
                },
            ) { Text("ترقية الكل") }
            IconButton(onClick = { vm.loadInstalled() }) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث")
            }
        }
        if (vm.pkgBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(data) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = {
                        Text(p.info, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        if (p.name in names) {
                            TextButton(enabled = !vm.pkgBusy, onClick = { confirm = p.name }) {
                                Text("حذف")
                            }
                        } else {
                            TextButton(
                                enabled = !vm.pkgBusy,
                                onClick = {
                                    vm.pkgAction("تثبيت ${p.name}", "pkg install -y ${sq(p.name)}")
                                },
                            ) { Text("تثبيت") }
                        }
                    },
                )
            }
        }
        if (vm.pkgLog.isNotEmpty()) {
            Surface(color = TermBg, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                Ltr {
                    Text(
                        vm.pkgLog,
                        color = TermFg,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(6.dp),
                    )
                }
            }
        }
    }

    confirm?.let { name ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("حذف $name؟") },
            text = { Text("حذف حزم أساسية قد يعطّل Termux.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.pkgAction("حذف $name", "pkg uninstall -y ${sq(name)}")
                    confirm = null
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("إلغاء") } },
        )
    }
}

// ============================================================ Files
@Composable
fun FilesScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var editable by remember { mutableStateOf(false) }
    var saveErr by remember { mutableStateOf("") }
    var del by remember { mutableStateOf<FileItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var cname by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.openDir(vm.filesPath) }

    fun full(name: String) = vm.filesPath.trimEnd('/') + "/" + name

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openDir(parentOf(vm.filesPath)) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "أعلى")
            }
            Ltr {
                Text(
                    vm.filesPath.replace(TermuxBridge.HOME, "~"),
                    modifier = Modifier.weight(1f),
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    maxLines = 2,
                )
            }
            IconButton(onClick = { vm.openDir(vm.filesPath) }) {
                Icon(Icons.Default.Refresh, contentDescription = "تحديث")
            }
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "جديد")
            }
        }
        if (vm.filesBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.filesMsg.isNotEmpty()) {
            Text(
                vm.filesMsg,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(vm.files) { f ->
                ListItem(
                    modifier = Modifier.clickable {
                        if (f.isDir) {
                            vm.openDir(full(f.name))
                        } else {
                            val path = full(f.name)
                            scope.launch {
                                val r = vm.readFile(path)
                                val big = r.stdout.trim() == "__TOO_BIG__"
                                editable = r.ok && !big
                                content = when {
                                    big -> "الملف أكبر من 100KB — العرض غير مدعوم هنا"
                                    r.ok -> r.stdout
                                    else -> r.text
                                }
                                saveErr = ""
                                viewing = path
                            }
                        }
                    },
                    headlineContent = {
                        Text((if (f.isDir) "📁 " else "📄 ") + f.name, maxLines = 1)
                    },
                    trailingContent = {
                        IconButton(onClick = { del = f }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف")
                        }
                    },
                )
            }
        }
    }

    viewing?.let { path ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(path.substringAfterLast('/'), fontSize = 16.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        readOnly = !editable,
                        textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                    )
                    if (saveErr.isNotEmpty()) {
                        Text(saveErr, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editable,
                    onClick = {
                        scope.launch {
                            val r = vm.writeFile(path, content)
                            if (r.ok) {
                                saveErr = ""
                                viewing = null
                            } else {
                                saveErr = r.text
                            }
                        }
                    },
                ) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text("إغلاق") } },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("إنشاء جديد") },
            text = {
                OutlinedTextField(
                    value = cname,
                    onValueChange = { cname = it },
                    singleLine = true,
                    label = { Text("الاسم") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (cname.isNotBlank()) {
                        vm.fileOp("mkdir -p -- ${sq(full(cname.trim()))}")
                        cname = ""
                        creating = false
                    }
                }) { Text("إنشاء مجلد") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (cname.isNotBlank()) {
                        vm.fileOp("touch -- ${sq(full(cname.trim()))}")
                        cname = ""
                        creating = false
                    }
                }) { Text("إنشاء ملف") }
            },
        )
    }

    del?.let { f ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("حذف ${f.name}؟") },
            text = { Text("لا يمكن التراجع عن الحذف.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.fileOp("rm -rf -- ${sq(full(f.name))}")
                    del = null
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { del = null }) { Text("إلغاء") } },
        )
    }
}

// ============================================================ Scripts
@Composable
fun ScriptsScreen(vm: AppViewModel) {
    var adding by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.scripts) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            Ltr {
                                Text(
                                    s.cmd,
                                    fontFamily = Mono,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(enabled = !vm.scriptBusy, onClick = { vm.runScript(s) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "تشغيل")
                        }
                        IconButton(onClick = { vm.deleteScript(s) }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف")
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "إضافة") }
    }

    if (adding) {
        AddScriptDialog(
            onSave = { n, c ->
                vm.addScript(n, c)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }

    vm.scriptOut?.let { out ->
        AlertDialog(
            onDismissRequest = { if (!vm.scriptBusy) vm.dismissScript() },
            title = { Text(vm.scriptTitle) },
            text = {
                Column(Modifier.heightIn(max = 400.dp)) {
                    if (vm.scriptBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    SelectionContainer {
                        Ltr {
                            Text(
                                out,
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !vm.scriptBusy, onClick = { vm.dismissScript() }) {
                    Text("إغلاق")
                }
            },
        )
    }
}

@Composable
private fun AddScriptDialog(onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("سكربت جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = n,
                    onValueChange = { n = it },
                    singleLine = true,
                    label = { Text("الاسم") },
                )
                OutlinedTextField(
                    value = c,
                    onValueChange = { c = it },
                    minLines = 3,
                    label = { Text("الأمر") },
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (n.isNotBlank() && c.isNotBlank()) onSave(n, c) }) {
                Text("حفظ")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

// ============================================================ System
private val MonitorCmd = """
echo "== النظام =="; uname -srm; uptime 2>/dev/null
echo; echo "== الذاكرة =="; grep -E 'MemTotal|MemAvailable|SwapTotal|SwapFree' /proc/meminfo
echo; echo "== التخزين =="; df -h ~ | tail -n 1
echo; echo "== عدد الحزم =="; dpkg-query -W | wc -l
echo; echo "== العمليات =="; ps -ef 2>/dev/null | head -n 25
""".trimIndent()

@Composable
fun SystemScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val installed = remember(vm.resumeTick) { TermuxBridge.isInstalled(ctx) }
    val granted = remember(vm.resumeTick) { TermuxBridge.hasPermission(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.resumeTick++
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("الإعداد", fontWeight = FontWeight.Bold)
                Text((if (installed) "✅" else "❌") + " Termux مثبّت")
                Text((if (granted) "✅" else "❌") + " صلاحية RUN_COMMAND")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = installed && !granted,
                        onClick = { launcher.launch(TermuxBridge.PERMISSION) },
                    ) { Text("منح الصلاحية") }
                    OutlinedButton(
                        enabled = installed,
                        onClick = { TermuxBridge.openTermux(ctx) },
                    ) { Text("فتح Termux") }
                }
                Text("1) افتح Termux والصق هذا الأمر، ثم أغلق Termux وافتحه من جديد:", fontSize = 13.sp)
                Surface(color = TermBg, modifier = Modifier.fillMaxWidth()) {
                    Ltr {
                        SelectionContainer {
                            Text(
                                TermuxBridge.SETUP_COMMAND,
                                color = TermFg,
                                fontFamily = Mono,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { clip.setText(AnnotatedString(TermuxBridge.SETUP_COMMAND)) }) {
                    Text("نسخ الأمر")
                }
                Text(
                    "2) إن لم يظهر طلب الصلاحية: إعدادات التطبيق ← الأذونات ← Run commands in Termux environment.",
                    fontSize = 13.sp,
                )
                Button(
                    enabled = !vm.sysBusy,
                    onClick = { vm.sysRun("اختبار الاتصال", "echo OK && whoami") },
                ) { Text("اختبار الاتصال") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("المراقبة والخدمات", fontWeight = FontWeight.Bold)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = !vm.sysBusy,
                        onClick = { vm.sysRun("مراقب النظام", MonitorCmd) },
                    ) { Text("مراقب النظام") }
                    OutlinedButton(
                        enabled = !vm.sysBusy,
                        onClick = {
                            vm.sysRun(
                                "تشغيل SSH",
                                "pkg install -y openssh >/dev/null 2>&1; sshd && " +
                                    "echo 'SSH يعمل على المنفذ 8022 — اضبط كلمة المرور بالأمر passwd'",
                            )
                        },
                    ) { Text("تشغيل SSH") }
                    OutlinedButton(
                        enabled = !vm.sysBusy,
                        onClick = {
                            vm.sysRun(
                                "إيقاف SSH",
                                "kill $(cat ~/../usr/var/run/sshd.pid) && echo 'تم الإيقاف'",
                            )
                        },
                    ) { Text("إيقاف SSH") }
                    OutlinedButton(
                        enabled = !vm.sysBusy,
                        onClick = { vm.sysRun("Wake Lock", "termux-wake-lock && echo 'مفعّل'") },
                    ) { Text("تفعيل Wake Lock") }
                    OutlinedButton(
                        enabled = !vm.sysBusy,
                        onClick = { vm.sysRun("Wake Lock", "termux-wake-unlock && echo 'تم الإلغاء'") },
                    ) { Text("إلغاء Wake Lock") }
                }
            }
        }

        if (vm.sysBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.sysOut.isNotEmpty()) {
            Surface(color = TermBg, modifier = Modifier.fillMaxWidth()) {
                Ltr {
                    SelectionContainer {
                        Text(
                            vm.sysOut,
                            color = TermFg,
                            fontFamily = Mono,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}
