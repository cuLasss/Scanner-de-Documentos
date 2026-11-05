@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.scanlimpo.ui.document

import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.data.ImportOutcome
import br.com.scanlimpo.data.ScanPage
import br.com.scanlimpo.ui.common.BusyOverlay
import br.com.scanlimpo.ui.common.ConfirmDialog
import br.com.scanlimpo.ui.common.GridSizeButton
import br.com.scanlimpo.ui.common.TextInputDialog
import br.com.scanlimpo.ui.common.formatDate
import br.com.scanlimpo.ui.common.pagesLabel
import br.com.scanlimpo.ui.common.rememberPickers
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.IOException
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@Composable
fun DocumentScreen(
    docId: String,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenText: () -> Unit,
    onExport: (List<String>) -> Unit,
    onOpenDocument: (String) -> Unit,
    onScanMore: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val docs by app.repo.documents.collectAsStateWithLifecycle()
    val ocrProgress by app.ocr.progress.collectAsStateWithLifecycle()
    val doc = docs.find { it.id == docId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    var selecting by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var filterSheet by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDocDialog by remember { mutableStateOf(false) }
    var deletePagesDialog by remember { mutableStateOf(false) }
    var combineDialog by remember { mutableStateOf(false) }
    var columns by remember { mutableIntStateOf(app.settings.pageColumns) }

    // Cópia local: enquanto arrasta, a ordem muda aqui e só é gravada ao soltar.
    var pages by remember { mutableStateOf(doc?.pages.orEmpty()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(doc?.pages) {
        if (!dragging) pages = doc?.pages.orEmpty()
    }

    val pickers = rememberPickers(
        onResult = { uris: List<Uri> ->
            scope.launch {
                busy = "Adicionando…"
                val outcome = try {
                    app.repo.addPages(docId, uris) { done, total ->
                        busy = "Adicionando ${minOf(done + 1, total)} de $total…"
                    }
                } catch (e: IOException) {
                    Log.e("DocumentScreen", "Falha ao adicionar páginas", e)
                    ImportOutcome(docId, 0, uris.size, "Não consegui salvar. O armazenamento do celular pode estar cheio.")
                } finally {
                    busy = null
                }
                if (outcome.added == 0) snackbar.showSnackbar(outcome.message ?: "Não consegui ler esse arquivo.")
            }
        },
        onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
    )

    if (doc == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    fun exitSelection() {
        selecting = false
        selection = emptySet()
    }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        pages = pages.toMutableList().apply {
            val fromIndex = indexOfFirst { it.id == from.key }
            val toIndex = indexOfFirst { it.id == to.key }
            if (fromIndex >= 0 && toIndex >= 0) add(toIndex, removeAt(fromIndex))
        }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (selecting) {
                    SelectionBar(
                        count = selection.size,
                        allSelected = selection.size == pages.size,
                        onClose = ::exitSelection,
                        onSelectAll = {
                            selection = if (selection.size == pages.size) emptySet() else pages.map { it.id }.toSet()
                        },
                        onRotate = {
                            val ids = selection
                            scope.launch { app.repo.updateEdits(docId, ids) { it.rotatedCw() } }
                        },
                        onFilter = { filterSheet = true },
                        onDelete = { deletePagesDialog = true },
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                        },
                        title = {
                            Text(
                                doc.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { renameDialog = true },
                            )
                        },
                        actions = {
                            GridSizeButton(columns) {
                                columns = it
                                app.settings.pageColumns = it
                            }
                            IconButton(onClick = { selecting = true }) { Icon(Icons.Outlined.Checklist, "Selecionar páginas") }
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "Mais opções") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Renomear") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                    onClick = {
                                        menuOpen = false
                                        renameDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicar documento") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            busy = "Duplicando…"
                                            val id = app.repo.duplicate(docId)
                                            busy = null
                                            if (id != null) onOpenDocument(id)
                                        }
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Excluir documento", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        deleteDocDialog = true
                                    },
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "Escaneado em ${formatDate(doc.createdAt)}" +
                                if (doc.pages.size > 1) " · ${pagesLabel(doc.pages.size)}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                        )
                        if (selecting) {
                            SelectionActions(
                                selected = selection.size,
                                onCombine = { combineDialog = true },
                                onExport = { onExport(pages.map { it.id }.filter { it in selection }) },
                            )
                        } else {
                            NormalActions(
                                onAdd = { addSheet = true },
                                onText = onOpenText,
                                onExport = { onExport(emptyList()) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ocrProgress[docId]?.let { (done, total) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Lendo o texto: página ${done + 1} de $total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (pages.size > 1 && !selecting) {
                    Text(
                        "Segure e arraste uma página para mudar a ordem",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                    )
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pages, key = { it.id }) { page ->
                        ReorderableItem(reorderState, key = page.id, enabled = !selecting) { isDragging ->
                            val elevation by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "elevação")
                            val selected = page.id in selection
                            PageTile(
                                docId = docId,
                                page = page,
                                index = pages.indexOf(page),
                                columns = columns,
                                selecting = selecting,
                                selected = selected,
                                elevation = elevation,
                                modifier = Modifier.longPressDraggableHandle(
                                    enabled = !selecting,
                                    onDragStarted = {
                                        dragging = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        dragging = false
                                        val order = pages.map { it.id }
                                        scope.launch { app.repo.reorder(docId, order) }
                                    },
                                ),
                                onClick = {
                                    if (selecting) {
                                        selection = if (selected) selection - page.id else selection + page.id
                                    } else {
                                        onOpenPage(page.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        busy?.let { BusyOverlay(it) }
    }

    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }) {
            Column(Modifier.navigationBarsPadding()) {
                SheetRow(Icons.Outlined.DocumentScanner, "Escanear mais páginas") {
                    addSheet = false
                    onScanMore()
                }
                SheetRow(Icons.Outlined.PhotoLibrary, "Fotos da galeria") {
                    addSheet = false
                    pickers.pickImages()
                }
                SheetRow(Icons.Outlined.PictureAsPdf, "Arquivo (PDF ou imagem)") {
                    addSheet = false
                    pickers.pickFile()
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }) {
            Column(Modifier.navigationBarsPadding()) {
                Text(
                    "Filtro para ${pagesLabel(selection.size)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                PageFilter.entries.forEach { filter ->
                    SheetRow(Icons.Outlined.AutoAwesome, filter.label) {
                        val ids = selection
                        filterSheet = false
                        exitSelection()
                        scope.launch {
                            app.repo.updateEdits(docId, ids) { it.copy(filter = filter, filterStrength = filter.defaultStrength) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (renameDialog) {
        TextInputDialog(
            title = "Nome do documento",
            initial = doc.name,
            suggestions = doc.suggestedNames(),
            onConfirm = { name ->
                renameDialog = false
                scope.launch { app.repo.rename(docId, name) }
            },
            onDismiss = { renameDialog = false },
        )
    }

    if (deleteDocDialog) {
        ConfirmDialog(
            title = "Excluir documento?",
            message = "\"${doc.name}\" será apagado do celular. Não tem como desfazer.",
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = {
                deleteDocDialog = false
                scope.launch {
                    app.repo.delete(setOf(docId))
                    onBack()
                }
            },
            onDismiss = { deleteDocDialog = false },
        )
    }

    if (deletePagesDialog) {
        val everything = selection.size == doc.pages.size
        ConfirmDialog(
            title = "Excluir ${pagesLabel(selection.size)}?",
            message = if (everything) {
                "Essas são todas as páginas, então o documento inteiro será apagado."
            } else {
                "Não tem como desfazer."
            },
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = {
                val ids = selection
                deletePagesDialog = false
                exitSelection()
                scope.launch { app.repo.deletePages(docId, ids) }
            },
            onDismiss = { deletePagesDialog = false },
        )
    }

    if (combineDialog) {
        ConfirmDialog(
            title = "Juntar em uma folha?",
            message = "As ${selection.size} páginas viram uma única folha A4, como frente e verso de RG ou CNH. As páginas separadas deixam de existir.",
            confirmLabel = "Juntar",
            onConfirm = {
                val ids = pages.map { it.id }.filter { it in selection }
                combineDialog = false
                exitSelection()
                scope.launch {
                    busy = "Juntando…"
                    app.repo.combinePages(docId, ids)
                    busy = null
                }
            },
            onDismiss = { combineDialog = false },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onRotate: () -> Unit,
    onFilter: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Sair da seleção") } },
        title = { Text(if (count == 0) "Selecione" else "$count") },
        actions = {
            TextButton(
                onClick = onSelectAll,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) { Text(if (allSelected) "Nenhuma" else "Todas") }
            IconButton(onClick = onRotate, enabled = count > 0) { Icon(Icons.AutoMirrored.Outlined.RotateRight, "Girar") }
            IconButton(onClick = onFilter, enabled = count > 0) { Icon(Icons.Outlined.AutoAwesome, "Aplicar filtro") }
            IconButton(onClick = onDelete, enabled = count > 0) { Icon(Icons.Outlined.DeleteOutline, "Excluir") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun NormalActions(onAdd: () -> Unit, onText: () -> Unit, onExport: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Páginas")
        }
        FilledTonalButton(onClick = onText, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Icon(Icons.AutoMirrored.Outlined.TextSnippet, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Texto")
        }
        Button(
            onClick = onExport,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Exportar", maxLines = 1)
        }
    }
}

@Composable
private fun SelectionActions(selected: Int, onCombine: () -> Unit, onExport: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onCombine,
            enabled = selected in 2..6,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Outlined.GridView, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Juntar em 1 folha", maxLines = 1)
        }
        Button(
            onClick = onExport,
            enabled = selected > 0,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Exportar estas", maxLines = 1)
        }
    }
}

@Composable
private fun PageTile(
    docId: String,
    page: ScanPage,
    index: Int,
    columns: Int,
    selecting: Boolean,
    selected: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = elevation,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        // Uma por linha: a página aparece no formato dela. Em grade, todas do mesmo tamanho.
        val ratio = if (columns == 1) {
            (page.renderWidth.toFloat() / page.renderHeight.coerceAtLeast(1)).coerceIn(0.5f, 2f)
        } else {
            0.75f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .padding(8.dp),
        ) {
            AsyncImage(
                // Em poucas colunas a miniatura ficaria borrada. O Coil lê o arquivo inteiro já no tamanho da tela.
                model = if (columns <= 2) context.app.repo.displayFile(docId, page) else context.app.repo.thumbFile(docId, page),
                contentDescription = "Página ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.66f), CircleShape)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            if (page.isStale) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .size(26.dp),
                    strokeWidth = 3.dp,
                )
            }
            if (selecting) {
                Icon(
                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selecionada" else "Não selecionada",
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
                            CircleShape,
                        )
                        .padding(2.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
