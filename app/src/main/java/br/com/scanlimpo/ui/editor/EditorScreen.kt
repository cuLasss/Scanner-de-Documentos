package br.com.scanlimpo.ui.editor

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.scanlimpo.app
import br.com.scanlimpo.core.imaging.ImageFilters
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.data.PageEdit
import br.com.scanlimpo.data.ScanPage
import br.com.scanlimpo.imaging.PageRenderer
import br.com.scanlimpo.ui.common.ConfirmDialog
import br.com.scanlimpo.ui.common.DarkSystemBars
import br.com.scanlimpo.ui.common.ZoomableBox
import br.com.scanlimpo.ui.theme.EditorAccent
import br.com.scanlimpo.ui.theme.EditorBackground
import br.com.scanlimpo.ui.theme.EditorMuted
import br.com.scanlimpo.ui.theme.EditorOnSurface
import br.com.scanlimpo.ui.theme.EditorSurface
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class EditorPanel { FILTERS, ADJUST }

@Composable
fun EditorScreen(
    docId: String,
    initialPageId: String,
    onBack: () -> Unit,
    onCrop: (String) -> Unit,
    onAnnotate: (String) -> Unit,
    onOpenText: () -> Unit,
    onRescan: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val docs by app.repo.documents.collectAsStateWithLifecycle()
    val doc = docs.find { it.id == docId }
    val scope = rememberCoroutineScope()

    if (doc == null || doc.pages.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    DarkSystemBars()
    val pages = doc.pages
    val initialIndex = remember { pages.indexOfFirst { it.id == initialPageId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialIndex) { pages.size }
    val page = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]

    var zoomed by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf(EditorPanel.FILTERS) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    var brightness by remember(page.id, page.edit.brightness) { mutableFloatStateOf(page.edit.brightness) }
    var contrast by remember(page.id, page.edit.contrast) { mutableFloatStateOf(page.edit.contrast) }
    var level by remember(page.id, page.edit.filter) { mutableFloatStateOf(page.edit.filterStrength) }
    var draggingLevel by remember { mutableStateOf(false) }
    LaunchedEffect(page.edit.filterStrength) {
        if (!draggingLevel) level = page.edit.filterStrength
    }

    // Quantas vezes cada página foi girada nesta sessão, para a animação.
    val turns = remember { mutableStateMapOf<String, Int>() }

    // As duas pontas do filtro, para nível, brilho e contraste mudarem na tela sem esperar o processamento da imagem final.
    // As camadas antigas ficam até as novas chegarem, e a escolha de qual usar confere se ainda valem.
    val currentPage by rememberUpdatedState(page)
    val layerEdit = page.edit.forLayers()
    val layers by produceState<FilterLayers?>(null, page.id, layerEdit) {
        // Durante um giro a tela ainda mostra a imagem antiga. Calcula depois, sem disputar processador com a imagem final.
        snapshotFlow { currentPage.renderedEdit.geometryKey == layerEdit.geometryKey }.first { it }
        value = withContext(Dispatchers.Default) { renderLayers(app.repo.pageDir(docId, page.id), page.sourceFile, layerEdit) }
    }

    // Em troca rápida o indicador só atrapalharia: aparece só quando a espera for de verdade.
    val slowRender by produceState(false, page.isStale) {
        value = false
        if (page.isStale) {
            delay(600)
            value = true
        }
    }

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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar", tint = EditorOnSurface)
            }
            Text(
                "Página ${pagerState.currentPage + 1} de ${pages.size}",
                color = EditorOnSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (slowRender) {
                CircularProgressIndicator(Modifier.size(20.dp), color = EditorAccent, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, "Mais opções", tint = EditorOnSurface)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Escanear esta página de novo") },
                        leadingIcon = { Icon(Icons.Outlined.DocumentScanner, null) },
                        onClick = {
                            menuOpen = false
                            onRescan(page.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir esta página", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            confirmDelete = true
                        },
                    )
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            key = { pages[it].id },
            pageSpacing = 12.dp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { index ->
            val item = pages[index]
            val isCurrent = item.id == page.id
            PageCanvas(
                page = item,
                file = app.repo.displayFile(docId, item),
                index = index,
                turns = turns[item.id] ?: 0,
                level = if (isCurrent) level else item.edit.filterStrength,
                dragging = isCurrent && draggingLevel,
                brightness = if (isCurrent) brightness else item.edit.brightness,
                contrast = if (isCurrent) contrast else item.edit.contrast,
                layers = if (isCurrent) layers else null,
                onZoomChanged = { if (isCurrent) zoomed = it },
            )
        }

        when (panel) {
            EditorPanel.FILTERS -> FilterPanel(
                docId = docId,
                page = page,
                level = level,
                onLevelChange = {
                    draggingLevel = true
                    level = it
                },
                onLevelCommit = {
                    draggingLevel = false
                    val value = level
                    scope.launch { app.repo.updateEdit(docId, page.id) { it.copy(filterStrength = value) } }
                },
                onSelect = { filter ->
                    scope.launch {
                        app.repo.updateEdit(docId, page.id) {
                            it.copy(filter = filter, filterStrength = filter.defaultStrength)
                        }
                    }
                },
                onApplyAll = {
                    val filter = page.edit.filter
                    val value = level
                    scope.launch {
                        app.repo.updateEdits(docId, pages.map { it.id }) { it.copy(filter = filter, filterStrength = value) }
                    }
                    Toast.makeText(context, "\"${filter.label}\" aplicado em todas as páginas", Toast.LENGTH_SHORT).show()
                },
            )
            EditorPanel.ADJUST -> AdjustPanel(
                isBlackAndWhite = page.edit.filter == PageFilter.BW,
                brightness = brightness,
                contrast = contrast,
                onBrightness = { brightness = it },
                onContrast = { contrast = it },
                onCommit = {
                    val b = brightness
                    val c = contrast
                    scope.launch {
                        app.repo.updateEdit(docId, page.id) { it.copy(brightness = b, contrast = c) }
                    }
                },
                onReset = {
                    brightness = 0f
                    contrast = 0f
                    scope.launch {
                        app.repo.updateEdit(docId, page.id) { it.copy(brightness = 0f, contrast = 0f) }
                    }
                },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(EditorSurface)
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolButton(Icons.Outlined.AutoAwesome, "Filtros", panel == EditorPanel.FILTERS) { panel = EditorPanel.FILTERS }
            ToolButton(Icons.Outlined.Tune, "Ajustes", panel == EditorPanel.ADJUST) { panel = EditorPanel.ADJUST }
            ToolButton(Icons.AutoMirrored.Outlined.RotateRight, "Girar", false) {
                turns[page.id] = (turns[page.id] ?: 0) + 1
                scope.launch { app.repo.updateEdit(docId, page.id) { it.rotatedCw() } }
            }
            ToolButton(Icons.Outlined.Crop, "Recortar", false) { onCrop(page.id) }
            ToolButton(Icons.Outlined.BorderColor, "Editar", false) { onAnnotate(page.id) }
            ToolButton(Icons.AutoMirrored.Outlined.TextSnippet, "Texto", false) { onOpenText() }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Excluir esta página?",
            message = if (pages.size == 1) {
                "É a única página, então o documento inteiro será apagado."
            } else {
                "Não tem como desfazer."
            },
            confirmLabel = "Excluir",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                val id = page.id
                scope.launch { app.repo.deletePages(docId, setOf(id)) }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * As duas pontas de um filtro, em imagem pronta para a tela. O nível é a mistura entre elas.
 * Brilho e contraste ficam de fora e entram por cima na hora de desenhar, na mesma ordem e com a mesma conta da imagem
 * final: partir de uma imagem já ajustada não dá, porque o que estourou no branco não volta.
 *
 * @param threshold no P&B, [filtered] é a página em cinza e este é o limiar sem ajuste. O corte também é feito ao desenhar.
 */
private class FilterLayers(val edit: PageEdit, val base: ImageBitmap, val filtered: ImageBitmap, val threshold: Int? = null)

private fun PageEdit.forLayers() = copy(filterStrength = 1f, brightness = 0f, contrast = 0f)

/** O que está de fato na tela, e a edição que aquela imagem representa. */
private class ShownImage(val bitmap: ImageBitmap, val edit: PageEdit, val aspect: Float)

/**
 * Uma página do editor, sem piscar. A regra é uma só: a imagem na tela nunca sai antes de a próxima estar
 * pronta. O arquivo novo é lido em segundo plano e a troca acontece de uma vez, em um único quadro.
 *
 * Enquanto a imagem final não chega, nível, brilho, contraste e troca de filtro aparecem pelas camadas,
 * e o giro aparece girando a imagem atual.
 */
@Composable
private fun PageCanvas(
    page: ScanPage,
    file: File,
    index: Int,
    turns: Int,
    level: Float,
    dragging: Boolean,
    brightness: Float,
    contrast: Float,
    layers: FilterLayers?,
    onZoomChanged: (Boolean) -> Unit,
) {
    val rotation = remember { Animatable(turns * 90f) }
    // Quanto do giro acumulado já está "dentro" da imagem em exibição.
    var baked by remember { mutableFloatStateOf(turns * 90f) }
    var shown by remember { mutableStateOf<ShownImage?>(null) }
    val appear = remember { Animatable(0f) }
    val currentPage by rememberUpdatedState(page)

    LaunchedEffect(turns) {
        // Ao abrir a página não há o que girar. Animar de 0° para 0° faria a primeira imagem esperar à toa.
        val target = turns * 90f
        if (rotation.targetValue != target) {
            rotation.animateTo(target, tween(320, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(file) {
        val rendered = page.renderedEdit
        val aspect = page.renderWidth.toFloat() / page.renderHeight.coerceAtLeast(1)
        val bitmap = withContext(Dispatchers.IO) { PageRenderer.decodeForScreen(file) } ?: return@LaunchedEffect
        // A troca espera o giro terminar. Se a pessoa girou de novo, o que falta continua como giro visual.
        snapshotFlow { rotation.isRunning }.first { !it }
        val pending = (currentPage.edit.rotation - rendered.rotation).mod(360)
        baked = rotation.targetValue - pending
        shown = ShownImage(bitmap.asImageBitmap(), rendered, aspect)
        appear.animateTo(1f, tween(140))
    }

    val current = shown
    ZoomableBox(
        resetKey = page.id,
        rotationDegrees = { rotation.value - baked },
        contentAspect = current?.aspect ?: 0f,
        onZoomChanged = onZoomChanged,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
    ) {
        if (current != null) {
            // No P&B o brilho muda o corte, não os tons. Clarear a imagem pronta só deixaria o papel cinza.
            val isBilevel = page.edit.filter == PageFilter.BW
            Image(
                current.bitmap,
                contentDescription = "Página ${index + 1}",
                contentScale = ContentScale.Fit,
                colorFilter = if (isBilevel) null else previewFilter(brightness, contrast, current.edit),
                alpha = appear.value,
                modifier = Modifier.fillMaxSize(),
            )
            // As camadas valem enquanto o que se pede for diferente do que a imagem na tela já tem.
            val adjusting = brightness != current.edit.brightness || contrast != current.edit.contrast
            val changesLook = dragging || adjusting || level != current.edit.filterStrength || page.edit.filter != current.edit.filter
            val mix = layers?.takeIf {
                changesLook && it.edit == page.edit.forLayers() && it.edit.geometryKey == current.edit.geometryKey
            }
            if (mix != null) {
                // Só a troca de filtro faz transição. Barra arrastada precisa responder na hora.
                val fade = remember(mix) { Animatable(if (dragging || adjusting) 1f else 0f) }
                LaunchedEffect(mix) { fade.animateTo(1f, tween(160)) }
                val tones = if (isBilevel) null else previewFilter(brightness, contrast, mix.edit)
                val layerPaint = remember { Paint() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            // Tudo numa camada só: o brilho entra sobre a mistura pronta, na mesma ordem da imagem final.
                            layerPaint.alpha = fade.value
                            layerPaint.colorFilter = tones
                            drawIntoCanvas { canvas ->
                                canvas.saveLayer(size.toRect(), layerPaint)
                                drawContent()
                                canvas.restore()
                            }
                        },
                ) {
                    Image(mix.base, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    Image(
                        mix.filtered,
                        null,
                        contentScale = ContentScale.Fit,
                        alpha = level,
                        colorFilter = mix.threshold?.let { thresholdFilter(ImageFilters.bilevelThreshold(it, brightness)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun renderLayers(dir: File, sourceFile: String, edit: PageEdit): FilterLayers? = runCatching {
    fun show(bitmap: Bitmap) = PageRenderer.forScreen(bitmap).asImageBitmap()
    val base = show(PageRenderer.render(dir, sourceFile, edit.copy(filter = PageFilter.ORIGINAL), maxDim = LAYER_SIDE))
    when (edit.filter) {
        PageFilter.ORIGINAL -> FilterLayers(edit, base, base)
        PageFilter.BW -> {
            val (gray, threshold) = PageRenderer.renderBilevelBase(dir, sourceFile, edit, LAYER_SIDE)
            FilterLayers(edit, base, show(gray), threshold)
        }
        else -> FilterLayers(edit, base, show(PageRenderer.render(dir, sourceFile, edit, maxDim = LAYER_SIDE)))
    }
}.getOrNull()

/** O corte do P&B feito na placa de vídeo: abaixo do limiar vira preto, acima vira branco, com poucos tons de transição. */
private fun thresholdFilter(threshold: Int): ColorFilter {
    val shift = 128f - THRESHOLD_GAIN * threshold
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                THRESHOLD_GAIN, 0f, 0f, 0f, shift,
                0f, THRESHOLD_GAIN, 0f, 0f, shift,
                0f, 0f, THRESHOLD_GAIN, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/**
 * Prévia instantânea do brilho e contraste, enquanto a imagem final é processada em segundo plano.
 * É a mesma conta de [ImageFilters.adjust]: partindo de uma imagem sem ajuste, sai igual à imagem final.
 */
private fun previewFilter(brightness: Float, contrast: Float, rendered: PageEdit): ColorFilter? {
    if (brightness == rendered.brightness && contrast == rendered.contrast) return null
    val factor = ImageFilters.contrastFactor(contrast) / ImageFilters.contrastFactor(rendered.contrast)
    val shift = 128f * (1f - factor) + ImageFilters.brightnessOffset(brightness - rendered.brightness)
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                factor, 0f, 0f, 0f, shift,
                0f, factor, 0f, 0f, shift,
                0f, 0f, factor, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

@Composable
private fun FilterPanel(
    docId: String,
    page: ScanPage,
    level: Float,
    onLevelChange: (Float) -> Unit,
    onLevelCommit: () -> Unit,
    onSelect: (PageFilter) -> Unit,
    onApplyAll: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    // Miniaturas reais de cada filtro aplicado nesta página.
    val previews by produceState<Map<PageFilter, ImageBitmap>>(emptyMap(), page.id, page.edit.geometryKey) {
        val old = value
        value = withContext(Dispatchers.Default) {
            runCatching {
                val base = PageRenderer.render(
                    app.repo.pageDir(docId, page.id),
                    page.sourceFile,
                    page.edit.copy(
                        filter = PageFilter.ORIGINAL,
                        filterStrength = 1f,
                        brightness = 0f,
                        contrast = 0f,
                        marks = emptyList(),
                        signatures = emptyList(),
                    ),
                    maxDim = 220,
                )
                val w = base.width
                val h = base.height
                val source = IntArray(w * h)
                base.getPixels(source, 0, w, 0, 0, w, h)
                base.recycle()
                PageFilter.entries.associateWith { filter ->
                    val px = source.copyOf()
                    ImageFilters.apply(filter, px, w, h, filter.defaultStrength)
                    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            }.getOrDefault(old)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(EditorSurface)
            .padding(top = 8.dp, bottom = 4.dp),
    ) {
        LevelBar(page.edit.filter != PageFilter.ORIGINAL, level, onLevelChange, onLevelCommit)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PageFilter.entries.forEach { filter ->
                val selected = page.edit.filter == filter
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(filter) }
                        .padding(2.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 58.dp, height = 74.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A322E))
                            .border(
                                BorderStroke(2.dp, if (selected) EditorAccent else Color.Transparent),
                                RoundedCornerShape(8.dp),
                            ),
                    ) {
                        previews[filter]?.let {
                            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        filter.label,
                        color = if (selected) EditorAccent else EditorOnSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        TextButton(
            onClick = onApplyAll,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = 4.dp),
        ) {
            Text("Aplicar em todas as páginas", color = EditorAccent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A barra aparece sem empurrar a imagem: o espaço dela fica sempre reservado. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelBar(visible: Boolean, level: Float, onLevelChange: (Float) -> Unit, onLevelCommit: () -> Unit) {
    val colors = SliderDefaults.colors(
        thumbColor = EditorAccent,
        activeTrackColor = EditorAccent,
        inactiveTrackColor = Color(0xFF38423D),
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        AnimatedVisibility(visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nível", color = EditorMuted, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = level,
                    onValueChange = onLevelChange,
                    onValueChangeFinished = onLevelCommit,
                    colors = colors,
                    interactionSource = interaction,
                    // O controle é fino e, em 100%, fica na ponta da barra. A folga invisível em volta dele
                    // faz parte da barra, então encostar um pouco ao lado também pega.
                    thumb = {
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            SliderDefaults.Thumb(interactionSource = interaction, colors = colors)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("nivel_filtro"),
                )
                Text(
                    "${(level * 100).roundToInt()}%",
                    color = EditorOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(38.dp),
                )
            }
        }
    }
}

@Composable
private fun AdjustPanel(
    isBlackAndWhite: Boolean,
    brightness: Float,
    contrast: Float,
    onBrightness: (Float) -> Unit,
    onContrast: (Float) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = SliderDefaults.colors(
        thumbColor = EditorAccent,
        activeTrackColor = EditorAccent,
        inactiveTrackColor = Color(0xFF38423D),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(EditorSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            if (isBlackAndWhite) "Mais tinta / menos tinta" else "Brilho",
            color = EditorMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = brightness,
            onValueChange = onBrightness,
            onValueChangeFinished = onCommit,
            valueRange = -1f..1f,
            colors = colors,
        )
        if (!isBlackAndWhite) {
            Text("Contraste", color = EditorMuted, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = contrast,
                onValueChange = onContrast,
                onValueChangeFinished = onCommit,
                valueRange = -1f..1f,
                colors = colors,
            )
        }
        OutlinedButton(
            onClick = onReset,
            enabled = brightness != 0f || contrast != 0f,
            modifier = Modifier.align(Alignment.End),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text("Redefinir", color = if (brightness != 0f || contrast != 0f) EditorOnSurface else EditorMuted)
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) EditorAccent else EditorOnSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

private const val LAYER_SIDE = 1400
private const val THRESHOLD_GAIN = 24f
