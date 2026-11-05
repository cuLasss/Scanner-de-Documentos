@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.scanlimpo.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.scanlimpo.app
import br.com.scanlimpo.core.docx.DocxBlock
import br.com.scanlimpo.core.docx.DocxFormatException
import br.com.scanlimpo.core.docx.DocxReader
import br.com.scanlimpo.core.text.TextFiles
import br.com.scanlimpo.core.tiff.TiffFormatException
import br.com.scanlimpo.imaging.FileKind
import br.com.scanlimpo.imaging.FileKinds
import br.com.scanlimpo.imaging.ImageFiles
import br.com.scanlimpo.imaging.LockedPdfException
import br.com.scanlimpo.ui.common.BusyOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

private sealed interface ViewerContent {
    data object Loading : ViewerContent
    class Pages(val source: PageSource, val editableImage: Boolean) : ViewerContent
    class Blocks(val blocks: List<DocxBlock>) : ViewerContent
    class Failure(val message: String) : ViewerContent
}

private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

private fun load(context: Context, uri: Uri, hintedMime: String?): ViewerContent = try {
    when (FileKinds.detect(context, uri, hintedMime)) {
        FileKind.PDF -> ViewerContent.Pages(PdfPageSource(context, uri), editableImage = false)
        FileKind.TIFF -> ViewerContent.Pages(TiffPageSource(context, uri), editableImage = false)
        FileKind.IMAGE -> ViewerContent.Pages(ImagePageSource(context, uri), editableImage = true)
        FileKind.DOCX -> {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Sem acesso ao arquivo")
            ViewerContent.Blocks(stream.use { DocxReader.read(it) })
        }
        FileKind.TEXT -> ViewerContent.Blocks(readText(context, uri))
        FileKind.UNKNOWN -> ViewerContent.Failure("Esse tipo de arquivo não é suportado.")
    }
} catch (e: LockedPdfException) {
    Log.w("Viewer", "PDF com senha", e)
    ViewerContent.Failure("Esse PDF tem senha, e o leitor do Android não abre esse tipo de arquivo.")
} catch (e: TiffFormatException) {
    Log.w("Viewer", "TIFF não suportado", e)
    ViewerContent.Failure(e.message ?: "Não consegui ler esse TIFF.")
} catch (e: DocxFormatException) {
    Log.w("Viewer", "Word inválido", e)
    ViewerContent.Failure(e.message ?: "Não consegui ler esse arquivo do Word.")
} catch (e: SecurityException) {
    Log.w("Viewer", "Sem permissão", e)
    ViewerContent.Failure("O Android não deu permissão para ler esse arquivo.")
} catch (e: OutOfMemoryError) {
    Log.w("Viewer", "Sem memória", e)
    ViewerContent.Failure("Arquivo grande demais para a memória do celular.")
} catch (e: Exception) {
    Log.w("Viewer", "Falha ao abrir", e)
    ViewerContent.Failure("Não consegui abrir esse arquivo.")
}

private fun readText(context: Context, uri: Uri): List<DocxBlock> {
    var truncated = false
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val data = ByteArray(MAX_TEXT_BYTES)
        var total = 0
        while (total < data.size) {
            val read = input.read(data, total, data.size - total)
            if (read < 0) break
            total += read
        }
        truncated = total == data.size && input.read() >= 0
        data.copyOf(total)
    } ?: throw IOException("Sem acesso ao arquivo")
    val lines = TextFiles.decode(bytes).lineSequence().map { DocxBlock.Paragraph(it) as DocxBlock }.toMutableList()
    if (truncated) lines += DocxBlock.Paragraph("(Arquivo muito grande: mostrando só o começo.)", bold = true)
    return lines
}

@Composable
fun ViewerScreen(uri: Uri, hintedMime: String?, onClose: () -> Unit, onOpenDocument: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val title = remember(uri) { FileKinds.displayName(context, uri) ?: "Documento" }
    var busy by remember { mutableStateOf<String?>(null) }

    val content by produceState<ViewerContent>(ViewerContent.Loading, uri) {
        value = withContext(Dispatchers.IO) { load(context, uri, hintedMime) }
        awaitDispose { (value as? ViewerContent.Pages)?.source?.close() }
    }

    fun edit() {
        scope.launch {
            busy = "Abrindo para editar…"
            val outcome = try {
                app.repo.createDocument(listOf(uri)) { done, total ->
                    busy = "Preparando ${min(done + 1, total)} de $total…"
                }
            } finally {
                busy = null
            }
            val id = outcome.docId
            if (id != null) {
                onOpenDocument(id)
            } else {
                Toast.makeText(context, outcome.message ?: "Não consegui importar esse arquivo.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                    },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        when (val current = content) {
                            is ViewerContent.Pages -> TextButton(onClick = ::edit) {
                                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                                Text(" Editar")
                            }
                            is ViewerContent.Blocks -> IconButton(onClick = { copyAll(context, title, current.blocks) }) {
                                Icon(Icons.Outlined.ContentCopy, "Copiar todo o texto")
                            }
                            else -> Unit
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (val current = content) {
                    ViewerContent.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is ViewerContent.Failure -> FailureContent(current.message)
                    is ViewerContent.Pages -> PagesContent(current.source)
                    is ViewerContent.Blocks -> BlocksContent(current.blocks)
                }
            }
        }
        busy?.let { BusyOverlay(it) }
    }
}

private fun copyAll(context: Context, label: String, blocks: List<DocxBlock>) {
    val text = blocks.filterIsInstance<DocxBlock.Paragraph>().joinToString("\n") { it.text }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Texto copiado", Toast.LENGTH_SHORT).show()
}

@Composable
private fun FailureContent(message: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Páginas em rolagem contínua, com pinça e duplo toque para ampliar. */
@Composable
private fun PagesContent(source: PageSource) {
    val listState = rememberLazyListState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val cache = remember {
        object : LruCache<Int, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
        }
    }
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clipToBounds(),
    ) {
        val viewWidth = constraints.maxWidth
        val renderWidth = min(MAX_RENDER_WIDTH, (viewWidth * RENDER_DENSITY).toInt())

        fun clampX(value: Float, zoom: Float): Float {
            val limit = viewWidth * (zoom - 1f) / 2f
            return value.coerceIn(-limit, limit)
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                // Olha o gesto antes da lista: com dois dedos é zoom. Com um, a lista rola normalmente.
                .pointerInput(viewWidth) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            if (fingers >= 2) {
                                val next = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                offsetX = clampX(offsetX + pan.x, next)
                                scale = next
                                listState.dispatchRawDelta(-pan.y / next)
                                event.changes.forEach { it.consume() }
                            } else if (scale > 1f && pan != Offset.Zero) {
                                offsetX = clampX(offsetX + pan.x, scale)
                            }
                        } while (event.changes.any { it.pressed })
                        if (scale < 1.03f) {
                            scale = 1f
                            offsetX = 0f
                        }
                    }
                }
                .pointerInput(viewWidth) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > 1.03f) {
                                scale = 1f
                                offsetX = 0f
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                                offsetX = clampX((viewWidth / 2f - tap.x) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                            }
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                },
        ) {
            items(source.pageCount) { index ->
                PageItem(source, index, renderWidth, cache)
            }
        }

        if (source.pageCount > 1) {
            Text(
                "$currentPage / ${source.pageCount}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun PageItem(source: PageSource, index: Int, renderWidth: Int, cache: LruCache<Int, Bitmap>) {
    val image by produceState<ImageBitmap?>(cache.get(index)?.asImageBitmap(), index, renderWidth) {
        if (value == null) {
            val bitmap = try {
                source.render(index, renderWidth)
            } catch (e: IOException) {
                Log.w("Viewer", "Falha ao desenhar a página $index", e)
                null
            } catch (e: IllegalStateException) {
                Log.w("Viewer", "Falha ao desenhar a página $index", e)
                null
            } catch (e: TiffFormatException) {
                Log.w("Viewer", "Falha ao desenhar a página $index", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w("Viewer", "Sem memória para a página $index", e)
                null
            }
            if (bitmap != null) {
                cache.put(index, bitmap)
                value = bitmap.asImageBitmap()
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(source.aspectRatio(index).coerceIn(0.1f, 10f))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val current = image
        if (current != null) {
            Image(
                current,
                contentDescription = "Página ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.Gray)
        }
    }
}

/** Texto do TXT e do Word. Dá para selecionar e copiar. */
@Composable
private fun BlocksContent(blocks: List<DocxBlock>) {
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        ) {
            itemsIndexed(blocks) { _, block ->
                when (block) {
                    is DocxBlock.Paragraph -> Text(
                        block.text,
                        style = if (block.heading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (block.bold) FontWeight.Bold else null,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                    is DocxBlock.Picture -> PictureItem(block.bytes)
                }
            }
        }
    }
}

@Composable
private fun PictureItem(bytes: ByteArray) {
    val density = LocalDensity.current
    val targetWidth = with(density) { 900.dp.roundToPx() }
    val image by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                ImageFiles.decodeUpright({ bytes.inputStream() }, max(targetWidth, 1024)).asImageBitmap()
            }.getOrNull()
        }
    }
    image?.let {
        Image(
            it,
            contentDescription = "Imagem do documento",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentScale = ContentScale.FillWidth,
        )
    }
}

private const val CACHE_BYTES = 96 * 1024 * 1024
private const val MAX_RENDER_WIDTH = 2160
private const val RENDER_DENSITY = 1.6f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
