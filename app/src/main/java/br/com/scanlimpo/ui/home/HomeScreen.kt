@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package br.com.scanlimpo.ui.home

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.core.text.OcrText
import br.com.scanlimpo.data.ImportOutcome
import br.com.scanlimpo.data.ScanDocument
import br.com.scanlimpo.ui.common.BusyOverlay
import br.com.scanlimpo.ui.common.ConfirmDialog
import br.com.scanlimpo.ui.common.GridSizeButton
import br.com.scanlimpo.ui.common.TextInputDialog
import br.com.scanlimpo.ui.common.formatDate
import br.com.scanlimpo.ui.common.pagesLabel
import br.com.scanlimpo.ui.common.rememberPickers
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private class SearchHit(val doc: ScanDocument, val snippet: String?)

@Composable
fun HomeScreen(
    scanRequested: Boolean,
    onScanConsumed: () -> Unit,
    onScan: () -> Unit,
    onOpenDocument: (String, String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val docs by app.repo.documents.collectAsStateWithLifecycle()
    val ready by app.repo.ready.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mergeNameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ScanDocument?>(null) }
    var columns by remember { mutableIntStateOf(app.settings.homeColumns) }

    val pickers = rememberPickers(
        onResult = { uris: List<Uri> ->
            scope.launch {
                busy = "Preparando…"
                val outcome = try {
                    app.repo.createDocument(uris) { done, total ->
                        busy = "Preparando ${minOf(done + 1, total)} de $total…"
                    }
                } catch (e: IOException) {
                    Log.e("HomeScreen", "Falha ao criar o documento", e)
                    ImportOutcome(null, 0, uris.size, "Não consegui salvar. O armazenamento do celular pode estar cheio.")
                } finally {
                    busy = null
                }
                val id = outcome.docId
                if (id != null) {
                    onOpenDocument(id, outcome.singlePhotoPageId)
                    if (outcome.failed > 0) snackbar.showSnackbar("${outcome.failed} arquivo(s) não puderam ser lidos.")
                } else {
                    snackbar.showSnackbar(outcome.message ?: "Não consegui ler esse arquivo.")
                }
            }
        },
        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
    )

    // Atalho "Escanear agora" do ícone do app.
    LaunchedEffect(scanRequested) {
        if (scanRequested) {
            onScanConsumed()
            onScan()
        }
    }

    // A busca varre também o texto reconhecido de todas as páginas.
    val results by produceState(emptyList<SearchHit>(), docs, query) {
        delay(if (query.isBlank()) 0 else 120)
        value = withContext(Dispatchers.Default) {
            if (query.isBlank()) {
                docs.map { SearchHit(it, null) }
            } else {
                val key = OcrText.searchKey(query.trim())
                docs.mapNotNull { doc ->
                    when {
                        OcrText.searchKey(doc.name).contains(key) -> SearchHit(doc, null)
                        else -> doc.pages.firstNotNullOfOrNull { OcrText.snippet(it.text, query.trim()) }
                            ?.let { SearchHit(doc, it) }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (selection.isEmpty()) {
                    HomeHeader(
                        query = query,
                        onQueryChange = { query = it },
                        showSearchTools = docs.isNotEmpty(),
                        columns = columns,
                        onColumnsChange = {
                            columns = it
                            app.settings.homeColumns = it
                        },
                        onOpenSettings = onOpenSettings,
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selection = emptySet() }) {
                                Icon(Icons.Outlined.Close, "Cancelar seleção")
                            }
                        },
                        title = { Text("${selection.size} selecionado${if (selection.size > 1) "s" else ""}") },
                        actions = {
                            val visibleIds = results.map { it.doc.id }.toSet()
                            val allSelected = selection.containsAll(visibleIds)
                            TextButton(
                                onClick = { selection = if (allSelected) emptySet() else visibleIds },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            ) { Text(if (allSelected) "Nenhum" else "Todos") }
                            if (selection.size == 1) {
                                IconButton(onClick = { renameTarget = docs.find { it.id in selection } }) {
                                    Icon(Icons.Outlined.DriveFileRenameOutline, "Renomear")
                                }
                            }
                            if (selection.size >= 2) {
                                IconButton(onClick = { mergeNameDialog = true }) {
                                    Icon(Icons.AutoMirrored.Outlined.MergeType, "Juntar em um documento")
                                }
                            }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Outlined.DeleteOutline, "Excluir")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (selection.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalIconButton(
                            onClick = { showImportSheet = true },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(Icons.Outlined.FileUpload, "Importar arquivo")
                        }
                        ExtendedFloatingActionButton(
                            onClick = onScan,
                            icon = { Icon(Icons.Outlined.DocumentScanner, null) },
                            text = { Text("Escanear") },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            },
        ) { padding ->
            when {
                !ready -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                docs.isEmpty() -> EmptyLibrary(Modifier.padding(padding))

                results.isEmpty() && query.isNotBlank() -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        "Nada encontrado para \"$query\".",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(results, key = { it.doc.id }) { hit ->
                        val selected = hit.doc.id in selection
                        DocumentCard(
                            hit = hit,
                            selected = selected,
                            columns = columns,
                            onClick = {
                                if (selection.isEmpty()) {
                                    onOpenDocument(hit.doc.id, null)
                                } else {
                                    selection = if (selected) selection - hit.doc.id else selection + hit.doc.id
                                }
                            },
                            onLongClick = { selection = if (selected) selection - hit.doc.id else selection + hit.doc.id },
                        )
                    }
                }
            }
        }

        busy?.let { BusyOverlay(it) }
    }

    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Column(Modifier.navigationBarsPadding()) {
                ImportRow(Icons.Outlined.PhotoLibrary, "Fotos da galeria") {
                    showImportSheet = false
                    pickers.pickImages()
                }
                ImportRow(Icons.Outlined.PictureAsPdf, "Arquivo (PDF ou imagem)") {
                    showImportSheet = false
                    pickers.pickFile()
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (confirmDelete) {
        val count = selection.size
        ConfirmDialog(
            title = if (count == 1) "Excluir documento?" else "Excluir $count documentos?",
            message = "Isso apaga do celular e não tem como desfazer.",
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = {
                val ids = selection
                confirmDelete = false
                selection = emptySet()
                scope.launch { app.repo.delete(ids) }
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (mergeNameDialog) {
        // Mantém a ordem em que os documentos aparecem na tela.
        val ordered = docs.filter { it.id in selection }
        TextInputDialog(
            title = "Juntar ${ordered.size} documentos",
            initial = "${ordered.firstOrNull()?.name.orEmpty()} (junto)",
            confirmLabel = "Juntar",
            onConfirm = { name ->
                mergeNameDialog = false
                selection = emptySet()
                scope.launch {
                    busy = "Juntando…"
                    val id = app.repo.merge(ordered.map { it.id }, name)
                    busy = null
                    if (id != null) onOpenDocument(id, null)
                }
            },
            onDismiss = { mergeNameDialog = false },
        )
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "Renomear",
            initial = target.name,
            suggestions = target.suggestedNames(),
            onConfirm = { name ->
                renameTarget = null
                selection = emptySet()
                scope.launch { app.repo.rename(target.id, name) }
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun ImportRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun HomeHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    showSearchTools: Boolean,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scan Limpo", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (showSearchTools) GridSizeButton(columns, onColumnsChange)
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Configurações") }
        }
        if (showSearchTools) {
            Spacer(Modifier.height(12.dp))
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Buscar nos documentos", maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, "Limpar busca") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun DocumentCard(
    hit: SearchHit,
    selected: Boolean,
    columns: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val doc = hit.doc
    val firstPage = doc.pages.firstOrNull()
    val compact = columns >= 4
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.82f)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(if (compact) 4.dp else 10.dp),
            ) {
                if (firstPage != null) {
                    AsyncImage(
                        model = if (columns == 1) {
                            context.app.repo.displayFile(doc.id, firstPage)
                        } else {
                            context.app.repo.thumbFile(doc.id, firstPage)
                        },
                        contentDescription = "Primeira página de ${doc.name}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Selecionado",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(2.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = if (compact) 6.dp else 12.dp, vertical = if (compact) 6.dp else 10.dp)) {
                Text(
                    doc.name,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        hit.snippet ?: "${pagesLabel(doc.pages.size)} · ${formatDate(doc.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hit.snippet != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (hit.snippet != null) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.DocumentScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Nenhum documento ainda",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Toque em Escanear e aponte a câmera para o papel. O app acha as bordas e endireita a folha sozinho.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
