package com.akkos.print

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<SharedFile?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1565C0))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(shared.value) { shared.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Recebe fotos/PDFs vindos do "Compartilhar" ou do "Abrir com". */
    private fun handleIntent(intent: Intent?) {
        val i = intent ?: return
        val uri: Uri = when (i.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        shared.value = SharedFile(uri, i.type ?: contentResolver.getType(uri))
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val t: Template) : Screen
    data object Settings : Screen
    data class Photo(val initial: SharedFile?) : Screen
}

@Composable
private fun App(sharedFile: SharedFile?, onSharedConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    LaunchedEffect(sharedFile) {
        if (sharedFile != null) {
            screen = Screen.Photo(sharedFile)
            onSharedConsumed()
        }
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            prefs,
            onOpen = { screen = Screen.Edit(it) },
            onSettings = { screen = Screen.Settings },
            onImage = { screen = Screen.Photo(null) },
        )
        is Screen.Photo -> ImageScreen(s.initial, prefs) { screen = Screen.Home }
        is Screen.Edit -> EditorScreen(s.t, prefs) { screen = Screen.Home }
        Screen.Settings -> SettingsScreen(prefs) { screen = Screen.Home }
    }
}

private suspend fun printBitmap(ctx: Context, prefs: Prefs, bmp: Bitmap, copies: Int = 1): Boolean =
    printBitmaps(ctx, prefs, listOf(bmp), copies)

private suspend fun printBitmaps(ctx: Context, prefs: Prefs, bmps: List<Bitmap>, copies: Int = 1): Boolean {
    val addr = prefs.printer
    if (addr == null) {
        toast(ctx, "Escolha a impressora na tela inicial")
        return false
    }
    return try {
        if (bmps.isEmpty()) {
            toast(ctx, "Nada para imprimir")
            return false
        }
        val one = withContext(Dispatchers.Default) {
            bmps.map { EscPos.fromBitmap(it) }.reduce { a, b -> a + b }
        }
        var data = ByteArray(0)
        repeat(copies) { data += one }
        Printer.send(ctx, addr, data)
        toast(ctx, "Impresso!")
        true
    } catch (e: Exception) {
        toast(ctx, "Falha ao imprimir: ${e.message ?: e.javaClass.simpleName}")
        false
    }
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Voltar") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

// ---------------- Tela inicial ----------------

@Composable
private fun HomeScreen(prefs: Prefs, onOpen: (Template) -> Unit, onSettings: () -> Unit, onImage: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var selected by remember { mutableStateOf(prefs.printer) }
    var busy by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBtPermission(ctx)) devices = Printer.paired(ctx)
        else toast(ctx, "Sem a permissão de Bluetooth o app não consegue imprimir")
    }

    fun refresh() {
        if (hasBtPermission(ctx)) devices = Printer.paired(ctx) else launcher.launch(btPermissions())
    }

    LaunchedEffect(Unit) { refresh() }

    ScreenColumn {
        Text("Akko's Print", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Impressão térmica simples, sem anúncios.", style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Impressora", style = MaterialTheme.typography.titleMedium)
                if (devices.isEmpty()) {
                    Text(
                        "Nenhum dispositivo pareado. Ligue a impressora, pareie em " +
                            "Configurações › Bluetooth (senha 0000 ou 1234) e toque em Atualizar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                devices.forEach { (name, addr) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = addr; prefs.printer = addr },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == addr, onClick = { selected = addr; prefs.printer = addr })
                        Column {
                            Text(name)
                            Text(addr, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refresh() }) { Text("Atualizar") }
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                printBitmap(ctx, prefs, Renderer.render(testPage()))
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Imprimindo…" else "Página de teste") }
                }
            }
        }

        Button(onClick = onImage, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Imprimir foto ou PDF")
        }

        Text("Modelos", style = MaterialTheme.typography.titleLarge)
        TEMPLATES.forEach { t ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(t) }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium)
                    Text(t.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Dados da empresa / cabeçalho")
        }
    }
}

// ---------------- Editor de modelo ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(t: Template, prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, String>().apply { t.fields.forEach { put(it.key, it.default) } } }
    val items = remember { mutableStateListOf(Item()) }
    var number by remember { mutableIntStateOf(prefs.nextNumber) }
    var copies by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    val company = remember { prefs.company }

    val valuesSnap = values.toMap()
    val itemsSnap = items.toList()
    val bmp = remember(valuesSnap, itemsSnap, number) {
        Renderer.render(t.build(Data(valuesSnap, itemsSnap, company, if (t.numbered) number else 0)))
    }

    ScreenColumn {
        TopBar(t.name, onBack)

        t.fields.forEach { f ->
            OutlinedTextField(
                value = values[f.key] ?: "",
                onValueChange = { values[f.key] = it },
                label = { Text(f.label) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = !f.multiline,
                minLines = if (f.multiline) 3 else 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (f.numeric) KeyboardType.Decimal else KeyboardType.Text,
                    capitalization = if (f.numeric) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                ),
            )
        }

        if (t.hasItems) {
            Text("Itens", style = MaterialTheme.typography.titleMedium)
            items.forEachIndexed { i, item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = item.desc,
                            onValueChange = { v -> items[i] = items[i].copy(desc = v) },
                            label = { Text("Descrição") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = item.qty,
                                onValueChange = { v -> items[i] = items[i].copy(qty = v) },
                                label = { Text("Qtd") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            OutlinedTextField(
                                value = item.price,
                                onValueChange = { v -> items[i] = items[i].copy(price = v) },
                                label = { Text("Valor unit.") },
                                modifier = Modifier.weight(1.6f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            TextButton(onClick = { items.removeAt(i) }, enabled = items.size > 1) { Text("Tirar") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { items.add(Item()) }) { Text("+ Adicionar item") }
        }

        Text("Pré-visualização", style = MaterialTheme.typography.titleMedium)
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFDDDDDD))
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Pré-visualização do cupom",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vias:")
            listOf(1, 2, 3).forEach { n ->
                FilterChip(selected = copies == n, onClick = { copies = n }, label = { Text("$n") })
            }
        }

        Button(
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            onClick = {
                scope.launch {
                    busy = true
                    val ok = printBitmap(ctx, prefs, bmp, copies)
                    if (ok && t.numbered) {
                        number += 1
                        prefs.nextNumber = number
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Imprimindo…" else "Imprimir") }
    }
}

// ---------------- Dados da empresa ----------------

@Composable
private fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = remember { prefs.company }
    var name by remember { mutableStateOf(c.name) }
    var doc by remember { mutableStateOf(c.doc) }
    var address by remember { mutableStateOf(c.address) }
    var phone by remember { mutableStateOf(c.phone) }
    var footer by remember { mutableStateOf(c.footer) }
    var seq by remember { mutableStateOf(prefs.nextNumber.toString()) }

    ScreenColumn {
        TopBar("Dados da empresa", onBack)
        Text(
            "Aparecem no topo dos cupons. Deixe em branco o que não quiser imprimir.",
            style = MaterialTheme.typography.bodySmall,
        )
        Input(name, "Nome / Razão social") { name = it }
        Input(doc, "CNPJ / CPF") { doc = it }
        Input(address, "Endereço") { address = it }
        Input(phone, "Telefone", KeyboardType.Phone) { phone = it }
        Input(footer, "Mensagem do rodapé") { footer = it }
        Input(seq, "Próximo número de documento", KeyboardType.Number) { seq = it }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                prefs.company = Company(name.trim(), doc.trim(), address.trim(), phone.trim(), footer.trim())
                seq.toIntOrNull()?.let { prefs.nextNumber = it.coerceAtLeast(1) }
                toast(ctx, "Salvo")
                onBack()
            },
        ) { Text("Salvar") }
    }
}

@Composable
private fun Input(value: String, label: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = type,
            capitalization = if (type == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
        ),
    )
}

// ---------------- Foto / PDF ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageScreen(initial: SharedFile?, prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Source?>(null) }
    var loading by remember { mutableStateOf(false) }
    var dither by remember { mutableStateOf(true) }
    var rotate by remember { mutableStateOf(false) }
    var brightLive by remember { mutableStateOf(0f) }
    var bright by remember { mutableIntStateOf(0) }
    var pagesText by remember { mutableStateOf("") }
    var processed by remember { mutableStateOf(emptyList<Bitmap>()) }
    var copies by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }

    fun open(file: SharedFile) {
        scope.launch {
            loading = true
            try {
                val s = withContext(Dispatchers.IO) { ImageTools.load(ctx, file.uri, file.mime) }
                source = s
                dither = !s.isPdf // foto: pontilhado; PDF: preto e branco puro
                rotate = false
                pagesText = ""
                if (s.isPdf && s.totalPages > s.pages.size) {
                    toast(ctx, "PDF com ${s.totalPages} páginas: só as ${s.pages.size} primeiras foram carregadas")
                }
            } catch (e: Exception) {
                toast(ctx, "Não consegui abrir: ${e.message ?: e.javaClass.simpleName}")
            }
            loading = false
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) open(SharedFile(uri, "image/*"))
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) open(SharedFile(uri, "application/pdf"))
    }

    LaunchedEffect(initial) { if (initial != null) open(initial) }

    LaunchedEffect(source, dither, rotate, bright, pagesText) {
        val s = source ?: return@LaunchedEffect
        processed = withContext(Dispatchers.Default) {
            ImageTools.parsePages(pagesText, s.pages.size).map { s.pages[it] }
                .map { ImageTools.prepare(it, rotate, dither, bright) }
        }
    }

    ScreenColumn {
        TopBar("Foto ou PDF", onBack)
        Text(
            "Dica: na galeria, no WhatsApp ou no gerenciador de arquivos, use Compartilhar › Akko's Print.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickImage.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Escolher foto") }
            Button(onClick = { pickPdf.launch("application/pdf") }, modifier = Modifier.weight(1f)) { Text("Escolher PDF") }
        }

        if (loading) Text("Carregando…")

        val s = source
        if (s != null) {
            Text(
                if (s.isPdf) "${s.name} — ${s.totalPages} página(s)" else s.name,
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Ajustes", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = dither, onClick = { dither = true }, label = { Text("Foto (pontilhado)") })
                FilterChip(selected = !dither, onClick = { dither = false }, label = { Text("Texto / código") })
            }
            FilterChip(
                selected = rotate,
                onClick = { rotate = !rotate },
                label = { Text("Girar 90° (fica maior no papel)") },
            )
            Text("Clarear / escurecer", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = brightLive,
                onValueChange = { brightLive = it },
                onValueChangeFinished = { bright = brightLive.toInt() },
                valueRange = -100f..100f,
            )
            if (s.isPdf && s.pages.size > 1) {
                OutlinedTextField(
                    value = pagesText,
                    onValueChange = { pagesText = it },
                    label = { Text("Páginas (ex.: 1-3, 5) — vazio = todas") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            }

            Text("Pré-visualização", style = MaterialTheme.typography.titleMedium)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFDDDDDD))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                processed.forEach { b ->
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = "Pré-visualização",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White),
                    )
                }
                if (processed.isEmpty()) Text("Nenhuma página selecionada")
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vias:")
                listOf(1, 2, 3).forEach { n ->
                    FilterChip(selected = copies == n, onClick = { copies = n }, label = { Text("$n") })
                }
            }

            Button(
                enabled = !busy && processed.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                onClick = {
                    scope.launch {
                        busy = true
                        printBitmaps(ctx, prefs, processed, copies)
                        busy = false
                    }
                },
            ) { Text(if (busy) "Imprimindo…" else "Imprimir") }
        }
    }
}
