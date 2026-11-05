@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.scanlimpo.ui.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.export.DocxMode
import br.com.scanlimpo.export.ExportActions
import br.com.scanlimpo.export.ExportFormat
import br.com.scanlimpo.export.ExportOptions
import br.com.scanlimpo.export.ExportQuality
import br.com.scanlimpo.export.ExportResult
import br.com.scanlimpo.export.Exporter
import br.com.scanlimpo.export.PaperSize
import br.com.scanlimpo.ui.common.findActivity
import br.com.scanlimpo.ui.common.pagesLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SIZE_LIMITS = listOf<Pair<String, Long?>>(
    "Sem limite" to null,
    "5 MB" to 5L * 1024 * 1024,
    "2 MB" to 2L * 1024 * 1024,
    "1 MB" to 1024L * 1024,
    "500 KB" to 500L * 1024,
)

private val ExportFormat.icon: ImageVector
    get() = when (this) {
        ExportFormat.PDF -> Icons.Outlined.PictureAsPdf
        ExportFormat.PDF_SEARCHABLE -> Icons.AutoMirrored.Outlined.ManageSearch
        ExportFormat.DOCX -> Icons.Outlined.Description
        ExportFormat.JPG, ExportFormat.PNG, ExportFormat.WEBP -> Icons.Outlined.Image
        ExportFormat.TIFF -> Icons.Outlined.Layers
        ExportFormat.TXT -> Icons.AutoMirrored.Outlined.TextSnippet
        ExportFormat.ZIP -> Icons.Outlined.FolderZip
    }

/** "Salvar como" já com o tipo certo. O contrato padrão fixa o tipo na criação. */
private class CreateFileContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}

@Composable
fun ExportScreen(docId: String, pageIds: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val docs by app.repo.documents.collectAsStateWithLifecycle()
    val doc = docs.find { it.id == docId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    if (doc == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var format by rememberSaveable {
        mutableStateOf(
            runCatching { ExportFormat.valueOf(app.settings.lastFormat.orEmpty()) }.getOrDefault(ExportFormat.PDF),
        )
    }
    var quality by rememberSaveable { mutableStateOf(ExportQuality.ORIGINAL) }
    var paper by rememberSaveable { mutableStateOf(PaperSize.A4) }
    var sizeLimitIndex by rememberSaveable { mutableStateOf(0) }
    var useWatermark by rememberSaveable { mutableStateOf(false) }
    var watermark by rememberSaveable { mutableStateOf("") }
    var docxMode by rememberSaveable { mutableStateOf(DocxMode.TEXT) }
    var fileName by rememberSaveable { mutableStateOf(Exporter.sanitizeFileName(doc.name)) }
    // A senha não vai para o estado salvo do Android: fica só na memória enquanto a tela está aberta.
    var usePassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    var progress by remember { mutableStateOf<Pair<Float, String>?>(null) }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val pageCount = if (pageIds.isEmpty()) doc.pages.size else pageIds.size
    val showsImageOptions = format != ExportFormat.TXT && !(format == ExportFormat.DOCX && docxMode == DocxMode.TEXT)
    val sizeLimit = if (format.isPdf) SIZE_LIMITS[sizeLimitIndex].second else null

    fun buildOptions() = ExportOptions(
        format = format,
        fileName = fileName.ifBlank { doc.name },
        pageIds = pageIds.takeIf { it.isNotEmpty() },
        quality = quality,
        paper = paper,
        maxBytes = sizeLimit,
        password = password.takeIf { usePassword && format.isPdf && it.isNotEmpty() },
        watermark = watermark.trim().takeIf { useWatermark && showsImageOptions && it.isNotEmpty() },
        docxMode = docxMode,
    )

    // Estimativa de tamanho: recalcula quando muda algo que afeta o arquivo.
    val estimate by produceState<Long?>(null, format, quality, docxMode, useWatermark, watermark, doc.updatedAt) {
        value = null
        delay(250)
        value = runCatching { app.exporter.estimate(docId, buildOptions()) }.getOrNull()
    }

    val createFile = rememberLauncherForActivityResult(CreateFileContract()) { uri ->
        val done = result
        if (uri != null && done != null) {
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { ExportActions.copyToUri(context, done.files[0], uri) }
                }
                snackbar.showSnackbar(if (outcome.isSuccess) "Arquivo salvo." else "Não consegui salvar nesse local.")
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val done = result
        if (uri != null && done != null) {
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { ExportActions.copyToTree(context, done.files, done.format.mime, uri) }
                }
                snackbar.showSnackbar(
                    if (outcome.isSuccess) "${done.files.size} arquivos salvos." else "Não consegui salvar nessa pasta.",
                )
            }
        }
    }

    fun generate() {
        app.settings.lastFormat = format.name
        val options = buildOptions()
        job = scope.launch {
            progress = 0f to "Começando…"
            try {
                result = app.exporter.export(docId, options) { fraction, message ->
                    progress = fraction to message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                snackbar.showSnackbar("Sem memória para gerar nesta qualidade. Tente uma qualidade menor.")
            } catch (e: Exception) {
                android.util.Log.e("ExportScreen", "Falha ao exportar", e)
                snackbar.showSnackbar("Não consegui gerar o arquivo. ${e.message.orEmpty()}")
            } finally {
                progress = null
            }
        }
    }

    BackHandler(enabled = progress != null || result != null) {
        if (progress != null) {
            job?.cancel()
            progress = null
        } else {
            result = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (result != null) result = null else onBack()
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                },
                title = { Text(if (result != null) "Arquivo pronto" else "Exportar") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            val running = progress
            if (result == null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (running != null) {
                            Text(running.second, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { running.first }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    job?.cancel()
                                    progress = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Cancelar") }
                        } else {
                            val estimateText = estimate?.let { "Tamanho aproximado: ${Exporter.formatSize(it)}" }
                            if (estimateText != null && sizeLimit == null) {
                                Text(
                                    estimateText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            Button(
                                onClick = ::generate,
                                enabled = !(usePassword && format.isPdf && password.isEmpty()) && pageCount > 0,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Gerar ${format.label}") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val done = result
        if (done != null) {
            ResultView(
                result = done,
                modifier = Modifier.padding(padding),
                onShare = { ExportActions.share(context, done.files, done.format.mime) },
                onOpen = {
                    runCatching { ExportActions.open(context, done.files[0], done.format.mime) }
                        .onFailure { scope.launch { snackbar.showSnackbar("Não achei um app que abra esse tipo de arquivo.") } }
                },
                onSaveDownloads = {
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    ExportActions.saveToDownloads(context, done.files, done.format.mime)
                                }
                            }
                        }
                        snackbar.showSnackbar(
                            if (outcome.isSuccess) {
                                "Salvo em Downloads/${ExportActions.DOWNLOADS_FOLDER}"
                            } else {
                                "Não consegui salvar em Downloads."
                            },
                        )
                    }
                },
                onSaveAs = {
                    if (done.files.size == 1) {
                        createFile.launch(done.files[0].name to done.format.mime)
                    } else {
                        pickFolder.launch(null)
                    }
                },
                onPrint = { ExportActions.print(context.findActivity(), done.files[0], done.files[0].name) },
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    if (pageIds.isEmpty()) "Documento inteiro: ${pagesLabel(pageCount)}" else "Só as selecionadas: ${pagesLabel(pageCount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Section("Formato") {
                    FlowRow(
                        maxItemsInEachRow = 3,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ExportFormat.entries.forEach { item ->
                            FormatCard(
                                format = item,
                                selected = item == format,
                                onClick = { format = item },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        format.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("Nome do arquivo") },
                    suffix = { Text(".${format.extension}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (format == ExportFormat.DOCX) {
                    Section("Conteúdo do Word") {
                        ChipRow(DocxMode.entries, docxMode, { it.label }) { docxMode = it }
                        Text(
                            when (docxMode) {
                                DocxMode.TEXT -> "Texto editável. Tabelas e colunas podem precisar de ajuste à mão."
                                DocxMode.IMAGE -> "Cada página entra como imagem, igual ao papel."
                                DocxMode.BOTH -> "A imagem da página e depois o texto editável."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (showsImageOptions) {
                    if (sizeLimit == null) {
                        Section("Qualidade") {
                            ChipRow(ExportQuality.entries, quality, { it.label }) { quality = it }
                            Text(
                                quality.detail + if (format == ExportFormat.PNG) " · sem perda de qualidade, arquivo maior" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (format.isPdf) {
                    Section("Limite de tamanho do arquivo") {
                        ChipRow(SIZE_LIMITS.indices.toList(), sizeLimitIndex, { SIZE_LIMITS[it].first }) { sizeLimitIndex = it }
                    }
                    Section("Tamanho do papel") {
                        ChipRow(PaperSize.entries, paper, { it.label }) { paper = it }
                    }
                    ToggleRow("Proteger com senha", usePassword) { usePassword = it }
                    if (usePassword) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Senha do PDF") },
                            singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        if (showPassword) "Esconder senha" else "Mostrar senha",
                                    )
                                }
                            },
                            supportingText = { Text("Sem a senha ninguém abre, e não há como recuperar.") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (showsImageOptions) {
                    ToggleRow("Marca d'água", useWatermark) { useWatermark = it }
                    if (useWatermark) {
                        OutlinedTextField(
                            value = watermark,
                            onValueChange = { watermark = it },
                            label = { Text("Texto da marca d'água") },
                            placeholder = { Text("Ex.: Cópia para o RH") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FormatCard(format: ExportFormat, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLowest,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
        modifier = modifier
            .height(82.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(format.icon, null, Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                format.label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResultView(
    result: ExportResult,
    modifier: Modifier,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onSaveDownloads: () -> Unit,
    onSaveAs: () -> Unit,
    onPrint: () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            if (result.files.size == 1) result.files[0].name else "${result.files.size} arquivos",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            Exporter.formatSize(result.totalBytes) + if (result.hasPassword) " · protegido com senha" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        result.note?.let { note ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    note,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            ActionLabel(Icons.Outlined.IosShare, "Compartilhar (WhatsApp, e-mail…)")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            OutlinedButton(onClick = onSaveDownloads, modifier = Modifier.fillMaxWidth()) {
                ActionLabel(Icons.Outlined.Download, "Salvar em Downloads")
            }
        }
        OutlinedButton(onClick = onSaveAs, modifier = Modifier.fillMaxWidth()) {
            ActionLabel(Icons.Outlined.DriveFolderUpload, "Salvar em outro lugar…")
        }
        if (result.files.size == 1) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                ActionLabel(Icons.AutoMirrored.Outlined.OpenInNew, "Abrir")
            }
        }
        if (result.format.isPdf && !result.hasPassword) {
            OutlinedButton(onClick = onPrint, modifier = Modifier.fillMaxWidth()) {
                ActionLabel(Icons.Outlined.Print, "Imprimir")
            }
        }
        Box(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionLabel(icon: ImageVector, text: String) {
    Icon(icon, null, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
