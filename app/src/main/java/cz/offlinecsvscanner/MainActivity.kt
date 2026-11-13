package cz.offlinecsvscanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

data class CsvData(val headers: MutableList<String>, val rows: MutableList<MutableList<String>>, val delimiter: Char)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ScannerApp() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerApp() {
    val context = LocalContext.current
    var csv by remember { mutableStateOf<CsvData?>(null) }
    var codeColumn by remember { mutableIntStateOf(0) }
    var statusColumn by remember { mutableIntStateOf(-1) }
    var detected by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Importujte CSV soubor a poté skenujte kódy.") }
    var manual by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf(false) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val content = context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
            val data = parseCsv(content)
            require(data.headers.isNotEmpty()) { "CSV neobsahuje hlavičku." }
            csv = data
            codeColumn = data.headers.indexOfFirst { it.contains("kod", true) || it.contains("kód", true) || it.contains("code", true) }.let { if (it < 0) 0 else it }
            statusColumn = data.headers.indexOfFirst { it.equals("Stav", true) }
            if (statusColumn < 0) {
                data.headers.add("Stav")
                data.rows.forEach { it.add("") }
                statusColumn = data.headers.lastIndex
            } else data.rows.forEach { while (it.size < data.headers.size) it.add("") }
            message = "Načteno ${data.rows.size} řádků."
        } catch (e: Exception) { message = "Chyba importu: ${e.message}" }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && csv != null) try {
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter(Charsets.UTF_8).use { it.write(encodeCsv(csv!!)) }
            message = "CSV bylo exportováno."
        } catch (e: Exception) { message = "Chyba exportu: ${e.message}" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        camera = granted
        if (!granted) message = "Pro skenování povolte fotoaparát."
    }

    fun match(raw: String) {
        detected = raw
        val data = csv ?: return
        val key = normalize(raw)
        val index = data.rows.indexOfFirst { normalize(it.getOrNull(codeColumn).orEmpty()) == key && key.isNotBlank() }
        if (index >= 0) {
            data.rows[index][statusColumn] = "OK"
            message = "Nalezeno: ${data.rows[index][codeColumn]} — Stav = OK."
        } else {
            message = "Kód '$raw' nebyl nalezen. Můžete jej ručně opravit."
            manualCode = raw
            manual = true
        }
    }

    val total = csv?.rows?.size ?: 0
    val found = csv?.rows?.count { it.getOrNull(statusColumn) == "OK" } ?: 0

    Scaffold(topBar = { TopAppBar(title = { Text("Offline CSV Scanner") }) }) { inset ->
        Column(Modifier.padding(inset).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { import.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FileOpen, null); Text(" Import CSV") }
                OutlinedButton(onClick = { export.launch("upraveny_soubor.csv") }, enabled = csv != null, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FileDownload, null); Text(" Export") }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Total", total); Metric("Found", found); Metric("Remaining", total - found)
                }
            }
            if (csv != null) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(value = csv!!.headers.getOrElse(codeColumn) { "" }, onValueChange = {}, readOnly = true, label = { Text("Sloupec s kódem") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        csv!!.headers.forEachIndexed { i, h -> DropdownMenuItem(text = { Text(h) }, onClick = { codeColumn = i; expanded = false }) }
                    }
                }
            }
            Button(onClick = {
                if (csv == null) message = "Nejprve importujte CSV."
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera = true
                else permission.launch(Manifest.permission.CAMERA)
            }, enabled = csv != null, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CameraAlt, null); Text(" Spustit skener") }
            if (camera) CameraScanner(onSearch = { match(it) }, onClose = { camera = false })
            OutlinedButton(onClick = { manualCode = detected; manual = true }, enabled = csv != null, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Edit, null); Text(" Ruční zadání / oprava") }
            if (detected.isNotBlank()) Text("Poslední OCR: $detected")
            Text(message)
            if (csv != null) {
                Text("Náhled CSV", style = MaterialTheme.typography.titleMedium)
                Text(csv!!.headers.joinToString(" | "), style = MaterialTheme.typography.labelMedium)
                csv!!.rows.take(10).forEach { Text(it.joinToString(" | "), style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
                if (total > 10) Text("… dalších ${total - 10} řádků")
            }
        }
    }
    if (manual) AlertDialog(
        onDismissRequest = { manual = false },
        title = { Text("Ruční zadání / oprava") },
        text = { Column { Text("Zadejte kód, např. 473-5845 P20"); OutlinedTextField(value = manualCode, onValueChange = { manualCode = it }, label = { Text("Kód") }, singleLine = true) } },
        confirmButton = { TextButton(onClick = { manual = false; match(manualCode) }) { Text("Vyhledat") } },
        dismissButton = { TextButton(onClick = { manual = false }) { Text("Zrušit") } }
    )
}

@Composable
private fun Metric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CameraScanner(onSearch: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var processing by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("") }
    var scanMessage by remember { mutableStateOf("Zaměřte označení dílu a stiskněte SPUSTIT SKENOVÁNÍ.") }

    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            executor.shutdown()
        }
    }

    fun captureAndRead() {
        val capture = imageCapture
        if (capture == null) {
            scanMessage = "Kamera se ještě připravuje. Zkuste to za chvíli."
            return
        }
        if (processing) return
        processing = true
        scanMessage = "Pořizuji snímek a rozpoznávám text…"
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val mediaImage = image.image
                if (mediaImage == null) {
                    image.close()
                    processing = false
                    scanMessage = "Nepodařilo se načíst snímek. Zkuste skenování znovu."
                    return
                }
                val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                recognizer.process(input)
                    .addOnSuccessListener { result ->
                        val code = extractCode(result.text)
                        recognizedText = if (code.isNotBlank()) code else result.text.trim().replace(Regex("\\s+"), " ")
                        scanMessage = if (code.isNotBlank()) "Text rozpoznán. Zkontrolujte jej a stiskněte HLEDAT." else "Kód se nepodařilo jednoznačně rozpoznat. Upravte text ručně a stiskněte HLEDAT."
                    }
                    .addOnFailureListener { error ->
                        scanMessage = "Chyba OCR: ${error.localizedMessage ?: "neznámá chyba"}"
                    }
                    .addOnCompleteListener {
                        image.close()
                        processing = false
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                processing = false
                scanMessage = "Chyba fotoaparátu: ${exception.message ?: "snímek se nepodařilo pořídit"}"
            }
        })
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AndroidView(factory = { ctx ->
                val view = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val stillCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100)
                        .build()
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, stillCapture)
                        imageCapture = stillCapture
                    } catch (e: Exception) {
                        scanMessage = "Kameru se nepodařilo spustit: ${e.localizedMessage}"
                    }
                }, ContextCompat.getMainExecutor(ctx))
                view
            }, modifier = Modifier.fillMaxWidth().height(300.dp))

            Text("Kamera běží. OCR je vypnuté, dokud nestisknete tlačítko.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { captureAndRead() },
                enabled = !processing && imageCapture != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CameraAlt, null)
                Text(if (processing) " ZPRACOVÁVÁM SNÍMEK…" else " SPUSTIT SKENOVÁNÍ")
            }
            OutlinedTextField(
                value = recognizedText,
                onValueChange = { recognizedText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Rozpoznaný text – lze ručně opravit") },
                placeholder = { Text("Např. 473-5845 P20") },
                singleLine = true
            )
            Button(
                onClick = { onSearch(recognizedText.trim()) },
                enabled = recognizedText.isNotBlank() && !processing,
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.Search, null); Text(" HLEDAT") }
            Text(scanMessage, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Zavřít kameru") }
        }
    }
}

private fun extractCode(text: String): String {
    // Supports the usual marking 473-5845 P20 and OCR variants with missing spaces/dash.
    val compact = text.replace('\n', ' ').replace('–', '-').replace('—', '-')
    val regex = Regex("(?<!\\d)(\\d{3})\\s*-?\\s*(\\d{4})\\s*P\\s*(\\d+)(?!\\d)", RegexOption.IGNORE_CASE)
    val match = regex.find(compact) ?: return ""
    return "${match.groupValues[1]}-${match.groupValues[2]} P${match.groupValues[3]}"
}
private fun normalize(value: String) = value.uppercase().replace(Regex("\\s+"), "").replace("–", "-").replace("—", "-")

private fun parseCsv(content: String): CsvData {
    val first = content.lineSequence().firstOrNull().orEmpty()
    val delimiter = listOf(';', ',', '\t').maxByOrNull { d -> first.count { it == d } } ?: ';'
    val records = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            c == '"' && quoted && i + 1 < content.length && content[i + 1] == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == delimiter && !quoted -> { row.add(field.toString()); field.clear() }
            (c == '\n' || c == '\r') && !quoted -> {
                if (c == '\r' && i + 1 < content.length && content[i + 1] == '\n') i++
                row.add(field.toString()); field.clear()
                if (row.any { it.isNotEmpty() }) records.add(row)
                row = mutableListOf()
            }
            else -> field.append(c)
        }; i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); records.add(row) }
    val headers = records.firstOrNull()?.toMutableList() ?: mutableListOf()
    val rows = records.drop(1).map { r -> MutableList(headers.size) { j -> r.getOrElse(j) { "" } } }.toMutableList()
    return CsvData(headers, rows, delimiter)
}
private fun encodeCsv(data: CsvData): String {
    fun escape(s: String) = if (s.contains(data.delimiter) || s.contains('"') || s.contains('\n') || s.contains('\r')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    return (listOf(data.headers) + data.rows).joinToString("\r\n") { row -> row.joinToString(data.delimiter.toString()) { escape(it) } }
}
