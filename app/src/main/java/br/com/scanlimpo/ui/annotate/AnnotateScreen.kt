@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.scanlimpo.ui.annotate

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixOff
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.data.MarkKind
import br.com.scanlimpo.data.RectMark
import br.com.scanlimpo.data.SignatureMark
import br.com.scanlimpo.imaging.PageRenderer
import br.com.scanlimpo.ui.common.ConfirmDialog
import br.com.scanlimpo.ui.common.DarkSystemBars
import br.com.scanlimpo.ui.theme.EditorAccent
import br.com.scanlimpo.ui.theme.EditorBackground
import br.com.scanlimpo.ui.theme.EditorMuted
import br.com.scanlimpo.ui.theme.EditorOnSurface
import br.com.scanlimpo.ui.theme.EditorSurface
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Tool(val label: String, val icon: ImageVector, val kind: MarkKind, val hint: String) {
    BLACK("Tarja", Icons.Outlined.Rectangle, MarkKind.BLACK, "Passe o dedo sobre o que quer esconder. No arquivo final, o que está embaixo deixa de existir."),
    WHITE("Corretivo", Icons.Outlined.AutoFixOff, MarkKind.WHITE, "Passe o dedo sobre manchas ou anotações para apagar com branco."),
    HIGHLIGHT("Marca-texto", Icons.Outlined.BorderColor, MarkKind.HIGHLIGHT, "Passe o dedo sobre o trecho que quer destacar."),
}

private sealed interface Selection {
    data class Mark(val index: Int) : Selection
    data class Signature(val index: Int) : Selection
}

/** O que o dedo está fazendo no gesto atual. */
private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * Tudo aqui passa por duas fases: rascunho, que dá para mover, redimensionar e excluir à vontade,
 * e aplicado, que fica fixo na página e só sai pelo "Desfazer", um passo de cada vez.
 *
 * A ordem de aplicação fica gravada em cada item, por isso dá para desfazer mesmo depois de concluir e voltar.
 */
@Composable
fun AnnotateScreen(docId: String, pageId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val docs by app.repo.documents.collectAsStateWithLifecycle()
    val saved by app.signatures.signatures.collectAsStateWithLifecycle()
    val page = docs.find { it.id == docId }?.pages?.find { it.id == pageId }
    val scope = rememberCoroutineScope()

    if (page == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    DarkSystemBars()
    val dir = remember(docId, pageId) { app.repo.pageDir(docId, pageId) }

    var base by remember { mutableStateOf<ImageBitmap?>(null) }
    // Aplicados: fixos na página.
    var marks by remember { mutableStateOf(page.edit.marks) }
    var signatures by remember { mutableStateOf(page.edit.signatures) }
    // Rascunhos: ainda dá para mexer.
    var draftMarks by remember { mutableStateOf(emptyList<RectMark>()) }
    var draftSignatures by remember { mutableStateOf(emptyList<SignatureMark>()) }

    var tool by remember { mutableStateOf<Tool?>(null) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var drawing by remember { mutableStateOf<Rect?>(null) }
    var signatureSheet by remember { mutableStateOf(false) }
    var signaturePad by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmPending by remember { mutableStateOf(false) }
    val signatureImages = remember { mutableStateMapOf<String, ImageBitmap>() }

    val hasDrafts = draftMarks.isNotEmpty() || draftSignatures.isNotEmpty()
    val changed = marks != page.edit.marks || signatures != page.edit.signatures || hasDrafts
    val canUndo = hasDrafts || marks.isNotEmpty() || signatures.isNotEmpty()

    LaunchedEffect(pageId) {
        val edit = page.edit.copy(marks = emptyList(), signatures = emptyList())
        base = withContext(Dispatchers.Default) {
            PageRenderer.render(dir, page.sourceFile, edit, maxDim = 1800).asImageBitmap()
        }
    }
    LaunchedEffect(signatures.map { it.file }, draftSignatures.map { it.file }) {
        for (name in signatures.map { it.file } + draftSignatures.map { it.file }) {
            if (name !in signatureImages) {
                withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(dir, name).path) }?.let {
                    signatureImages[name] = it.asImageBitmap()
                }
            }
        }
    }

    fun addSignature(library: File) {
        val name = "sig_${UUID.randomUUID().toString().take(8)}.png"
        library.copyTo(File(dir, name), overwrite = true)
        draftSignatures = draftSignatures + SignatureMark(name, cx = 0.5f, cy = 0.78f, size = 0.42f)
        selection = Selection.Signature(draftSignatures.lastIndex)
        tool = null
    }

    fun applyDrafts() {
        var order = max(
            marks.maxOfOrNull { it.order } ?: 0L,
            signatures.maxOfOrNull { it.order } ?: 0L,
        ) + 1
        marks = marks + draftMarks.map { it.copy(order = order++) }
        signatures = signatures + draftSignatures.map { it.copy(order = order++) }
        draftMarks = emptyList()
        draftSignatures = emptyList()
        selection = null
    }

    fun undo() {
        selection = null
        when {
            draftSignatures.isNotEmpty() -> draftSignatures = draftSignatures.dropLast(1)
            draftMarks.isNotEmpty() -> draftMarks = draftMarks.dropLast(1)
            else -> {
                val lastMark = marks.withIndex().maxByOrNull { it.value.order * 10_000 + it.index }
                val lastSignature = signatures.withIndex().maxByOrNull { it.value.order * 10_000 + it.index }
                when {
                    lastMark == null && lastSignature == null -> Unit
                    lastSignature == null || (lastMark != null && lastMark.value.order > lastSignature.value.order) ->
                        marks = marks.filterIndexed { i, _ -> i != lastMark!!.index }
                    else -> signatures = signatures.filterIndexed { i, _ -> i != lastSignature.index }
                }
            }
        }
    }

    fun deleteSelected() {
        when (val current = selection) {
            is Selection.Mark -> draftMarks = draftMarks.filterIndexed { i, _ -> i != current.index }
            is Selection.Signature -> draftSignatures = draftSignatures.filterIndexed { i, _ -> i != current.index }
            null -> Unit
        }
        selection = null
    }

    fun finish() {
        val finalMarks = marks
        val finalSignatures = signatures
        scope.launch {
            app.repo.updateEdit(docId, pageId) { it.copy(marks = finalMarks, signatures = finalSignatures) }
            withContext(Dispatchers.IO) {
                val used = finalSignatures.map { it.file }.toSet()
                dir.listFiles { f -> f.name.startsWith("sig_") && f.name !in used }?.forEach { it.delete() }
            }
            // Navegação só pode acontecer na thread principal, e aqui o código veio do trabalho em segundo plano.
            withContext(Dispatchers.Main.immediate) { onDone() }
        }
    }

    BackHandler(enabled = changed) { confirmDiscard = true }

    Column(
        Modifier
            .fillMaxSize()
            .background(EditorBackground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (changed) confirmDiscard = true else onDone() }) {
                Icon(Icons.Outlined.Close, "Cancelar", tint = EditorOnSurface)
            }
            Text(
                "Editar página",
                color = EditorOnSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(enabled = canUndo, onClick = ::undo) {
                Icon(
                    Icons.AutoMirrored.Outlined.Undo,
                    "Desfazer",
                    tint = if (canUndo) EditorOnSurface else EditorMuted.copy(alpha = 0.5f),
                )
            }
            Button(
                onClick = { if (hasDrafts) confirmPending = true else finish() },
                enabled = base != null,
                colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color(0xFF00382D)),
                modifier = Modifier.padding(end = 8.dp),
            ) { Text("Concluir") }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val image = base
            if (image == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = EditorAccent)
            } else {
                val containerW = constraints.maxWidth.toFloat()
                val containerH = constraints.maxHeight.toFloat()
                val scale = min(containerW / image.width, containerH / image.height) * 0.97f
                val drawW = image.width * scale
                val drawH = image.height * scale
                val left = (containerW - drawW) / 2f
                val top = (containerH - drawH) / 2f
                val shortSide = min(drawW, drawH)

                fun toPage(p: Offset) = Offset(
                    ((p.x - left) / drawW).coerceIn(0f, 1f),
                    ((p.y - top) / drawH).coerceIn(0f, 1f),
                )

                fun signatureRect(mark: SignatureMark): Rect {
                    val bitmap = signatureImages[mark.file]
                    val width = mark.size * shortSide
                    val height = if (bitmap != null) width * bitmap.height / bitmap.width else width / 3f
                    val cx = left + mark.cx * drawW
                    val cy = top + mark.cy * drawH
                    val swap = mark.rotation % 180 != 0
                    val halfW = (if (swap) height else width) / 2f
                    val halfH = (if (swap) width else height) / 2f
                    return Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
                }

                fun markRect(mark: RectMark) = Rect(
                    left + mark.x * drawW,
                    top + mark.y * drawH,
                    left + (mark.x + mark.w) * drawW,
                    top + (mark.y + mark.h) * drawH,
                )

                fun cornerAt(rect: Rect, point: Offset, radius: Float): Corner? = listOf(
                    Corner.TOP_LEFT to rect.topLeft,
                    Corner.TOP_RIGHT to rect.topRight,
                    Corner.BOTTOM_RIGHT to rect.bottomRight,
                    Corner.BOTTOM_LEFT to rect.bottomLeft,
                ).filter { (it.second - point).getDistance() <= radius }
                    .minByOrNull { (it.second - point).getDistance() }?.first

                fun DrawScope.drawMark(mark: RectMark) {
                    val r = markRect(mark)
                    when (mark.kind) {
                        MarkKind.BLACK -> drawRect(Color.Black, r.topLeft, r.size)
                        MarkKind.WHITE -> drawRect(Color.White, r.topLeft, r.size)
                        MarkKind.HIGHLIGHT -> drawRect(Color(0xFFFFE13C), r.topLeft, r.size, blendMode = BlendMode.Multiply)
                    }
                }

                fun DrawScope.drawSignature(mark: SignatureMark) {
                    val bitmap = signatureImages[mark.file] ?: return
                    val width = mark.size * shortSide
                    val height = width * bitmap.height / bitmap.width
                    val center = Offset(left + mark.cx * drawW, top + mark.cy * drawH)
                    rotate(mark.rotation.toFloat(), center) {
                        drawImage(
                            bitmap,
                            dstOffset = IntOffset((center.x - width / 2f).roundToInt(), (center.y - height / 2f).roundToInt()),
                            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .testTag("pagina")
                        .pointerInput(left, top, drawW, drawH) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val touch = down.position
                                val slop = 14.dp.toPx()

                                // Canto de uma marcação selecionada: muda o tamanho.
                                val selectedMark = (selection as? Selection.Mark)?.index?.takeIf { it in draftMarks.indices }
                                val corner = selectedMark?.let { cornerAt(markRect(draftMarks[it]), touch, 30.dp.toPx()) }
                                if (selectedMark != null && corner != null) {
                                    down.consume()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val p = toPage(change.position)
                                        draftMarks = draftMarks.mapIndexed { i, mark ->
                                            if (i != selectedMark) mark else resized(mark, corner, p)
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                val hitSignature = draftSignatures.indices.lastOrNull {
                                    signatureRect(draftSignatures[it]).inflate(slop).contains(touch)
                                }
                                val hitMark = if (hitSignature == null) {
                                    draftMarks.indices.lastOrNull { markRect(draftMarks[it]).inflate(slop / 2f).contains(touch) }
                                } else {
                                    null
                                }

                                if (hitSignature != null || hitMark != null) {
                                    selection = hitSignature?.let { Selection.Signature(it) } ?: Selection.Mark(hitMark!!)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        if (pan != Offset.Zero || zoom != 1f) {
                                            if (hitSignature != null) {
                                                draftSignatures = draftSignatures.mapIndexed { i, s ->
                                                    if (i != hitSignature) {
                                                        s
                                                    } else {
                                                        s.copy(
                                                            cx = (s.cx + pan.x / drawW).coerceIn(0f, 1f),
                                                            cy = (s.cy + pan.y / drawH).coerceIn(0f, 1f),
                                                            size = (s.size * zoom).coerceIn(0.08f, 1.2f),
                                                        )
                                                    }
                                                }
                                            } else {
                                                draftMarks = draftMarks.mapIndexed { i, m ->
                                                    if (i != hitMark) m else moved(m, pan.x / drawW, pan.y / drawH, zoom)
                                                }
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    return@awaitEachGesture
                                }

                                selection = null
                                val kind = tool?.kind ?: return@awaitEachGesture

                                // Área livre com uma ferramenta na mão: começa um rascunho novo.
                                val start = toPage(touch)
                                drawing = Rect(start, Size.Zero)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val end = toPage(change.position)
                                    drawing = Rect(
                                        min(start.x, end.x),
                                        min(start.y, end.y),
                                        max(start.x, end.x),
                                        max(start.y, end.y),
                                    )
                                    change.consume()
                                }
                                val finished = drawing
                                drawing = null
                                // Com pouca altura ou largura foi só um toque sem querer.
                                if (finished != null && abs(finished.width) > 0.012f && abs(finished.height) > 0.006f) {
                                    draftMarks = draftMarks + RectMark(kind, finished.left, finished.top, finished.width, finished.height)
                                    selection = Selection.Mark(draftMarks.lastIndex)
                                }
                            }
                        },
                ) {
                    drawImage(
                        image,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    marks.forEach { drawMark(it) }
                    signatures.forEach { drawSignature(it) }

                    draftMarks.forEachIndexed { i, mark ->
                        drawMark(mark)
                        val rect = markRect(mark)
                        drawDraftOutline(rect)
                        if (selection == Selection.Mark(i)) drawCornerHandles(rect)
                    }
                    draftSignatures.forEachIndexed { i, mark ->
                        drawSignature(mark)
                        val rect = signatureRect(mark)
                        drawDraftOutline(rect)
                        if (selection == Selection.Signature(i)) drawCornerHandles(rect)
                    }

                    drawing?.let { d ->
                        val r = Rect(
                            left + d.left * drawW,
                            top + d.top * drawH,
                            left + d.right * drawW,
                            top + d.bottom * drawH,
                        )
                        val color = when (tool) {
                            Tool.WHITE -> Color.White
                            Tool.HIGHLIGHT -> Color(0xFFFFE13C).copy(alpha = 0.6f)
                            else -> Color.Black
                        }
                        drawRect(color, r.topLeft, r.size)
                        drawDraftOutline(r)
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(EditorSurface)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val selectedSignature = (selection as? Selection.Signature)?.index?.takeIf { it in draftSignatures.indices }
            if (selectedSignature != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamanho", color = EditorMuted, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = draftSignatures[selectedSignature].size.coerceIn(0.08f, 1.2f),
                        onValueChange = { value ->
                            draftSignatures = draftSignatures.mapIndexed { i, s ->
                                if (i == selectedSignature) s.copy(size = value) else s
                            }
                        },
                        valueRange = 0.08f..1.2f,
                        colors = SliderDefaults.colors(
                            thumbColor = EditorAccent,
                            activeTrackColor = EditorAccent,
                            inactiveTrackColor = Color(0xFF38423D),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    )
                }
            }
            if (hasDrafts) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Arraste para mover. Pelos cantos, muda o tamanho.",
                        color = EditorMuted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (selection != null) {
                        IconButton(onClick = ::deleteSelected) {
                            Icon(Icons.Outlined.DeleteOutline, "Excluir", tint = EditorOnSurface)
                        }
                    }
                    Button(
                        onClick = ::applyDrafts,
                        colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color(0xFF00382D)),
                    ) { Text("Aplicar") }
                }
            } else {
                Text(
                    tool?.hint ?: "Escolha uma ferramenta.",
                    color = EditorMuted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolChip(Icons.Outlined.Draw, "Assinar", false) { signatureSheet = true }
                Tool.entries.forEach { entry ->
                    ToolChip(entry.icon, entry.label, tool == entry) {
                        tool = if (tool == entry) null else entry
                        selection = null
                    }
                }
            }
        }
    }

    if (signatureSheet) {
        ModalBottomSheet(onDismissRequest = { signatureSheet = false }) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Text("Escolha a assinatura", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    items(saved, key = { it.name }) { file ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .clickable {
                                        signatureSheet = false
                                        addSignature(file)
                                    }
                                    .padding(10.dp),
                            ) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = "Assinatura salva",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(4.2f),
                                )
                            }
                            IconButton(onClick = { app.signatures.delete(file) }) {
                                Icon(Icons.Outlined.DeleteOutline, "Apagar assinatura salva")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        signatureSheet = false
                        signaturePad = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Criar nova assinatura")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (signaturePad) {
        SignaturePadDialog(
            onDismiss = { signaturePad = false },
            onSave = { bitmap ->
                signaturePad = false
                val file = app.signatures.add(bitmap)
                bitmap.recycle()
                addSignature(file)
            },
        )
    }

    if (confirmPending) {
        AlertDialog(
            onDismissRequest = { confirmPending = false },
            title = { Text("Aplicar o que falta?") },
            text = { Text("Tem marcação na página que ainda não foi aplicada.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPending = false
                    applyDrafts()
                    finish()
                }) { Text("Aplicar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmPending = false
                    draftMarks = emptyList()
                    draftSignatures = emptyList()
                    selection = null
                    finish()
                }) { Text("Descartar") }
            },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Descartar alterações?",
            message = "O que você fez agora nesta página será perdido.",
            confirmLabel = "Descartar",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onDone()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

private fun moved(mark: RectMark, dx: Float, dy: Float, zoom: Float): RectMark {
    val w = (mark.w * zoom).coerceIn(MIN_W, 1f)
    val h = (mark.h * zoom).coerceIn(MIN_H, 1f)
    val cx = mark.x + mark.w / 2f + dx
    val cy = mark.y + mark.h / 2f + dy
    return mark.copy(
        x = (cx - w / 2f).coerceIn(0f, 1f - w),
        y = (cy - h / 2f).coerceIn(0f, 1f - h),
        w = w,
        h = h,
    )
}

/** O canto oposto fica parado. O retângulo nunca inverte nem fica menor que o mínimo. */
private fun resized(mark: RectMark, corner: Corner, point: Offset): RectMark {
    val right = mark.x + mark.w
    val bottom = mark.y + mark.h
    return when (corner) {
        Corner.TOP_LEFT -> {
            val x = point.x.coerceAtMost(right - MIN_W)
            val y = point.y.coerceAtMost(bottom - MIN_H)
            mark.copy(x = x, y = y, w = right - x, h = bottom - y)
        }
        Corner.TOP_RIGHT -> {
            val y = point.y.coerceAtMost(bottom - MIN_H)
            mark.copy(y = y, w = (point.x - mark.x).coerceAtLeast(MIN_W), h = bottom - y)
        }
        Corner.BOTTOM_RIGHT -> mark.copy(
            w = (point.x - mark.x).coerceAtLeast(MIN_W),
            h = (point.y - mark.y).coerceAtLeast(MIN_H),
        )
        Corner.BOTTOM_LEFT -> {
            val x = point.x.coerceAtMost(right - MIN_W)
            mark.copy(x = x, w = right - x, h = (point.y - mark.y).coerceAtLeast(MIN_H))
        }
    }
}

private fun DrawScope.drawDraftOutline(rect: Rect) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
    val area = rect.inflate(3.dp.toPx())
    drawRect(Color.White, area.topLeft, area.size, style = Stroke(2.dp.toPx()))
    drawRect(EditorAccent, area.topLeft, area.size, style = Stroke(2.dp.toPx(), pathEffect = dash))
}

private fun DrawScope.drawCornerHandles(rect: Rect) {
    val area = rect.inflate(3.dp.toPx())
    listOf(area.topLeft, area.topRight, area.bottomRight, area.bottomLeft).forEach { point ->
        drawCircle(Color.White, 9.dp.toPx(), point)
        drawCircle(EditorAccent, 6.dp.toPx(), point)
    }
}

@Composable
private fun ToolChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) EditorAccent else EditorOnSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private const val MIN_W = 0.012f
private const val MIN_H = 0.006f
