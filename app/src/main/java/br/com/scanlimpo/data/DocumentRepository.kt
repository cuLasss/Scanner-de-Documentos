package br.com.scanlimpo.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.core.text.DocumentNamer
import br.com.scanlimpo.core.tiff.TiffFormatException
import br.com.scanlimpo.core.tiff.TiffReader
import br.com.scanlimpo.imaging.FileKind
import br.com.scanlimpo.imaging.FileKinds
import br.com.scanlimpo.imaging.ImageFiles
import br.com.scanlimpo.imaging.LockedPdfException
import br.com.scanlimpo.imaging.PageRenderer
import br.com.scanlimpo.imaging.PdfOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ImportOutcome(
    val docId: String?,
    val added: Int,
    val failed: Int,
    val message: String? = null,
    /** Quando veio uma única foto: a página, para abrir já no ajuste das bordas. */
    val singlePhotoPageId: String? = null,
)

/** Foto já endireitada, tirada na câmera do app, com as bordas escolhidas pela pessoa. */
class CapturedPage(val file: File, val quad: Quad?)

/**
 * Guarda os documentos em arquivos, na área privada do app:
 *   documents/<doc>/doc.json
 *   documents/<doc>/pages/<página>/{source.jpg, render_*, thumb_*, ocr.json, sig_*.png}
 *
 * O original nunca muda: toda edição gera uma imagem nova em segundo plano.
 */
class DocumentRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings,
) {
    private val root = File(context.filesDir, "documents")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()
    private val state = MutableStateFlow<List<ScanDocument>>(emptyList())
    val documents: StateFlow<List<ScanDocument>> = state.asStateFlow()

    private val readyState = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = readyState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val renderJobs = ConcurrentHashMap<String, Job>()

    /** Avisado quando o conteúdo de um documento muda. O OCR se pendura aqui. */
    var onContentChanged: (String) -> Unit = {}

    private val resolver: ContentResolver get() = context.contentResolver

    /** Carrega do disco. Fica fora do construtor para o OCR já estar ligado quando os documentos chegarem. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            load()
            // Termina o que ficou pendente caso o app tenha sido fechado no meio de uma edição.
            for (doc in state.value) {
                for (page in doc.pages) if (page.isStale) scheduleRender(doc.id, page.id)
                onContentChanged(doc.id)
            }
            readyState.value = true
        }
    }

    fun document(id: String): ScanDocument? = state.value.find { it.id == id }

    fun docDir(docId: String) = File(root, docId)
    fun pageDir(docId: String, pageId: String) = File(docDir(docId), "pages/$pageId")
    fun displayFile(docId: String, page: ScanPage) = File(pageDir(docId, page.id), page.displayFile)
    fun thumbFile(docId: String, page: ScanPage) = File(pageDir(docId, page.id), page.thumbFile)

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- Importação ----

    suspend fun createDocument(
        uris: List<Uri>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val id = newId()
        val result = importUris(id, uris, onProgress)
        if (result.pages.isEmpty()) {
            docDir(id).deleteRecursively()
            return@withContext ImportOutcome(null, 0, result.failed, result.message)
        }
        storeNewDocument(id, result.pages)
        ImportOutcome(
            id,
            result.pages.size,
            result.failed,
            result.message,
            singlePhotoPageId = result.pages.singleOrNull()?.id?.takeIf { result.photos == 1 },
        )
    }

    suspend fun createFromCaptures(captures: List<CapturedPage>): String = withContext(Dispatchers.IO) {
        val id = newId()
        storeNewDocument(id, captures.map { importCapture(id, it) })
        id
    }

    suspend fun addCaptures(docId: String, captures: List<CapturedPage>, insertAt: Int? = null) = withContext(Dispatchers.IO) {
        val pages = captures.map { importCapture(docId, it) }
        mutate(docId) { doc ->
            val index = (insertAt ?: doc.pages.size).coerceIn(0, doc.pages.size)
            doc.copy(pages = doc.pages.take(index) + pages + doc.pages.drop(index))
        }
        afterPagesAdded(docId, pages)
    }

    /** Troca uma página por uma nova foto, na mesma posição. */
    suspend fun replaceWithCapture(docId: String, pageId: String, capture: CapturedPage) {
        val index = document(docId)?.pages?.indexOfFirst { it.id == pageId } ?: -1
        addCaptures(docId, listOf(capture), insertAt = index.takeIf { it >= 0 })
        deletePages(docId, setOf(pageId))
    }

    private suspend fun storeNewDocument(id: String, pages: List<ScanPage>) {
        val now = System.currentTimeMillis()
        val doc = ScanDocument(id, defaultName(now), now, now, pages)
        lock.withLock {
            persist(doc)
            state.value = (state.value + doc).sortedByDescending { it.updatedAt }
        }
        afterPagesAdded(id, pages)
    }

    private fun importCapture(docId: String, capture: CapturedPage): ScanPage {
        val pageId = newId()
        val dir = pageDir(docId, pageId).apply { mkdirs() }
        try {
            val dest = File(dir, SOURCE_FILE)
            if (!capture.file.renameTo(dest)) {
                capture.file.copyTo(dest, overwrite = true)
                capture.file.delete()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(dest.path, bounds)
            return newPage(pageId, dir, bounds.outWidth, bounds.outHeight, capture.quad)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    suspend fun addPages(
        docId: String,
        uris: List<Uri>,
        insertAt: Int? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val result = importUris(docId, uris, onProgress)
        if (result.pages.isNotEmpty()) {
            mutate(docId) { doc ->
                val index = (insertAt ?: doc.pages.size).coerceIn(0, doc.pages.size)
                doc.copy(pages = doc.pages.take(index) + result.pages + doc.pages.drop(index))
            }
            afterPagesAdded(docId, result.pages)
        }
        ImportOutcome(docId, result.pages.size, result.failed, result.message)
    }

    private class ImportResult(val pages: List<ScanPage>, val failed: Int, val message: String?, val photos: Int)

    private suspend fun importUris(
        docId: String,
        uris: List<Uri>,
        onProgress: (Int, Int) -> Unit,
    ): ImportResult {
        val pages = ArrayList<ScanPage>()
        var failed = 0
        var photos = 0
        var message: String? = null
        uris.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            onProgress(index, uris.size)
            try {
                when (FileKinds.detect(context, uri)) {
                    FileKind.PDF -> pages += importPdf(docId, uri)
                    FileKind.TIFF -> pages += importTiff(docId, uri)
                    else -> {
                        pages += importImage(docId, uri)
                        photos++
                    }
                }
            } catch (e: LockedPdfException) {
                Log.w(TAG, "PDF com senha: $uri", e)
                failed++
                message = "Esse PDF tem senha. Tire a senha antes de importar."
            } catch (e: SecurityException) {
                Log.w(TAG, "Sem acesso ao arquivo: $uri", e)
                failed++
                message = "O Android não deu permissão para ler esse arquivo."
            } catch (e: TiffFormatException) {
                Log.w(TAG, "TIFF não suportado: $uri", e)
                failed++
                message = e.message
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao importar $uri", e)
                failed++
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Sem memória ao importar $uri", e)
                failed++
                message = "Arquivo grande demais para a memória do celular."
            }
        }
        onProgress(uris.size, uris.size)
        return ImportResult(pages, failed, message, photos)
    }

    private fun importImage(docId: String, uri: Uri): ScanPage {
        val pageId = newId()
        val dir = pageDir(docId, pageId).apply { mkdirs() }
        try {
            val dest = File(dir, SOURCE_FILE)
            val size = ImageFiles.writeNormalizedJpeg({ openStream(uri) }, dest, PageRenderer.MAX_SIDE)
            val quad = runCatching { PageRenderer.detectQuad(dest) }.getOrNull()
            return newPage(pageId, dir, size.width, size.height, quad)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun importPdf(docId: String, uri: Uri): List<ScanPage> {
        val pages = ArrayList<ScanPage>()
        PdfOpener.open(context, uri).use { opened ->
            val renderer = opened.renderer
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { pdfPage ->
                    val scale = min(PDF_IMPORT_DPI / 72f, PDF_IMPORT_MAX_SIDE.toFloat() / max(pdfPage.width, pdfPage.height))
                    val bitmap = Bitmap.createBitmap(
                        max(1, (pdfPage.width * scale).roundToInt()),
                        max(1, (pdfPage.height * scale).roundToInt()),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    pages += pageFromBitmap(docId, bitmap, quality = 92)
                }
            }
        }
        return pages
    }

    private fun importTiff(docId: String, uri: Uri): List<ScanPage> {
        val bytes = ImageFiles.readLimited({ openStream(uri) }, MAX_TIFF_BYTES)
        val reader = TiffReader(bytes)
        return reader.pages.indices.map { index ->
            val decoded = reader.decode(index, PageRenderer.MAX_SIDE)
            val bitmap = Bitmap.createBitmap(decoded.pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            pageFromBitmap(docId, bitmap, quality = 95)
        }
    }

    private fun pageFromBitmap(docId: String, bitmap: Bitmap, quality: Int): ScanPage {
        val pageId = newId()
        val dir = pageDir(docId, pageId).apply { mkdirs() }
        File(dir, SOURCE_FILE).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return newPage(pageId, dir, width, height, quad = null)
    }

    private fun newPage(pageId: String, dir: File, width: Int, height: Int, quad: Quad?): ScanPage {
        val filter = settings.defaultFilter
        val edit = PageEdit(filter = filter, filterStrength = filter.defaultStrength, quad = quad)
        // A miniatura já sai recortada, para a lista não mostrar a mesa em volta nem por um instante.
        val thumb = PageRenderer.render(dir, SOURCE_FILE, PageEdit(quad = quad), maxDim = 480)
        PageRenderer.writeThumbnail(thumb, File(dir, THUMB_FILE))
        thumb.recycle()
        return ScanPage(id = pageId, sourceWidth = width, sourceHeight = height, edit = edit)
    }

    private fun openStream(uri: Uri) = resolver.openInputStream(uri) ?: error("Não foi possível abrir $uri")

    private fun afterPagesAdded(docId: String, pages: List<ScanPage>) {
        pages.filter { it.isStale }.forEach { scheduleRender(docId, it.id) }
        onContentChanged(docId)
    }

    // ---- Edição ----

    suspend fun rename(docId: String, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) mutate(docId) { it.copy(name = clean) }
    }

    suspend fun updateEdit(docId: String, pageId: String, transform: (PageEdit) -> PageEdit) {
        val staleOcr = updatePageEdit(docId, pageId, transform)
        scheduleRender(docId, pageId)
        if (staleOcr) onContentChanged(docId)
    }

    suspend fun updateEdits(docId: String, pageIds: Collection<String>, transform: (PageEdit) -> PageEdit) {
        var anyStaleOcr = false
        for (id in pageIds) {
            if (updatePageEdit(docId, id, transform)) anyStaleOcr = true
            scheduleRender(docId, id)
        }
        if (anyStaleOcr) onContentChanged(docId)
    }

    /** Devolve true se a edição invalidou o texto reconhecido. */
    private suspend fun updatePageEdit(docId: String, pageId: String, transform: (PageEdit) -> PageEdit): Boolean {
        var staleOcr = false
        mutatePage(docId, pageId) { page ->
            val edit = transform(page.edit)
            if (edit.ocrKey != page.edit.ocrKey) {
                // Texto antigo some na hora: uma tarja nova não pode continuar vazando na busca nem na exportação.
                staleOcr = true
                page.copy(edit = edit, ocrKey = null, ocrText = null, userText = null, nameSuggestions = emptyList())
            } else {
                page.copy(edit = edit)
            }
        }
        if (staleOcr) File(pageDir(docId, pageId), OCR_FILE).delete()
        return staleOcr
    }

    suspend fun setUserText(docId: String, pageId: String, text: String) {
        mutatePage(docId, pageId) { page ->
            page.copy(userText = text.takeIf { it != page.ocrText.orEmpty() })
        }
    }

    /**
     * Guarda o resultado do OCR, mas só se a página ainda estiver como quando ele começou.
     * A gravação fica dentro da trava de propósito: se alguém colocou uma tarja no meio do caminho,
     * as palavras lidas antes dela não podem chegar ao disco.
     */
    suspend fun setOcrResult(
        docId: String,
        pageId: String,
        key: String,
        text: String,
        words: OcrPageData,
        suggestions: List<String>,
        automaticName: String?,
    ) {
        val dir = pageDir(docId, pageId)
        mutate(docId, touch = false) { doc ->
            val pages = doc.pages.map { page ->
                if (page.id == pageId && page.edit.ocrKey == key && dir.exists()) {
                    File(dir, OCR_FILE).writeText(json.encodeToString(OcrPageData.serializer(), words))
                    page.copy(ocrKey = key, ocrText = text, nameSuggestions = suggestions)
                } else {
                    page
                }
            }
            withAutomaticName(doc.copy(pages = pages), automaticName)
        }
    }

    /** Enquanto o documento tiver o nome padrão, ele ganha o nome sugerido. Nome escolhido pela pessoa nunca muda. */
    private fun withAutomaticName(doc: ScanDocument, name: String?): ScanDocument =
        if (name != null && DEFAULT_NAME.matches(doc.name)) doc.copy(name = name) else doc

    fun loadOcrWords(docId: String, pageId: String): List<OcrWord> = runCatching {
        val file = File(pageDir(docId, pageId), OCR_FILE)
        if (file.exists()) json.decodeFromString(OcrPageData.serializer(), file.readText()).words else emptyList()
    }.getOrDefault(emptyList())

    suspend fun reorder(docId: String, orderedIds: List<String>) {
        mutate(docId) { doc ->
            val byId = doc.pages.associateBy { it.id }
            val reordered = orderedIds.mapNotNull { byId[it] }
            doc.copy(pages = reordered + doc.pages.filter { it.id !in orderedIds })
        }
    }

    /** Remove as páginas. Um documento sem nenhuma página é apagado junto. */
    suspend fun deletePages(docId: String, pageIds: Set<String>) {
        val doc = mutate(docId) { it.copy(pages = it.pages.filter { p -> p.id !in pageIds }) } ?: return
        withContext(Dispatchers.IO) {
            pageIds.forEach { id ->
                renderJobs.remove("$docId/$id")?.cancel()
                pageDir(docId, id).deleteRecursively()
            }
        }
        if (doc.pages.isEmpty()) delete(setOf(docId))
    }

    suspend fun delete(docIds: Set<String>) {
        lock.withLock { state.value = state.value.filter { it.id !in docIds } }
        withContext(Dispatchers.IO) { docIds.forEach { docDir(it).deleteRecursively() } }
    }

    suspend fun duplicate(docId: String): String? {
        val doc = document(docId) ?: return null
        return mergeInternal(listOf(doc), "${doc.name} (cópia)")
    }

    suspend fun merge(docIds: List<String>, name: String): String? {
        val docs = docIds.mapNotNull(::document)
        return if (docs.isEmpty()) null else mergeInternal(docs, name)
    }

    private suspend fun mergeInternal(sources: List<ScanDocument>, name: String): String =
        withContext(Dispatchers.IO) {
            val id = newId()
            val pages = ArrayList<ScanPage>()
            for (doc in sources) {
                for (page in doc.pages) {
                    val pageId = newId()
                    pageDir(doc.id, page.id).copyRecursively(pageDir(id, pageId), overwrite = true)
                    pages += page.copy(id = pageId)
                }
            }
            val now = System.currentTimeMillis()
            val merged = ScanDocument(id, name, now, now, pages)
            lock.withLock {
                persist(merged)
                state.value = (state.value + merged).sortedByDescending { it.updatedAt }
            }
            pages.filter { it.isStale }.forEach { scheduleRender(id, it.id) }
            id
        }

    /**
     * Junta páginas em uma só folha A4, como frente e verso de RG ou CNH.
     * A página nova entra no lugar da primeira selecionada e as originais saem.
     */
    suspend fun combinePages(docId: String, pageIds: List<String>) {
        val doc = document(docId) ?: return
        val selected = doc.pages.filter { it.id in pageIds }
        if (selected.size < 2) return
        awaitRendered(docId, selected.map { it.id })
        val fresh = document(docId) ?: return
        val pages = fresh.pages.filter { it.id in pageIds }
        val firstIndex = fresh.pages.indexOfFirst { it.id in pageIds }

        val newPage = withContext(Dispatchers.Default) {
            val sheetW = 2067
            val sheetH = 2923
            val columns = if (pages.size <= 2) 1 else 2
            val rows = (pages.size + columns - 1) / columns
            val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.WHITE)
            val margin = 110f
            val gap = 70f
            val cellW = (sheetW - margin * 2 - gap * (columns - 1)) / columns
            val cellH = (sheetH - margin * 2 - gap * (rows - 1)) / rows
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            pages.forEachIndexed { i, page ->
                val bitmap = PageRenderer.decode(displayFile(docId, page), max(cellW, cellH).toInt())
                val scale = min(cellW / bitmap.width, cellH / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val x = margin + (i % columns) * (cellW + gap) + (cellW - w) / 2f
                val y = margin + (i / columns) * (cellH + gap) + (cellH - h) / 2f
                canvas.drawBitmap(bitmap, null, RectF(x, y, x + w, y + h), paint)
                bitmap.recycle()
            }
            val pageId = newId()
            val dir = pageDir(docId, pageId).apply { mkdirs() }
            File(dir, "source.jpg").outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            PageRenderer.writeThumbnail(sheet, File(dir, "thumb.jpg"))
            sheet.recycle()
            ScanPage(id = pageId, sourceWidth = sheetW, sourceHeight = sheetH)
        }

        mutate(docId) { current ->
            val without = current.pages.filter { it.id !in pageIds }
            val index = firstIndex.coerceIn(0, without.size)
            current.copy(pages = without.take(index) + newPage + without.drop(index))
        }
        withContext(Dispatchers.IO) { pageIds.forEach { pageDir(docId, it).deleteRecursively() } }
        onContentChanged(docId)
    }

    // ---- Renderização ----

    private fun scheduleRender(docId: String, pageId: String) {
        val key = "$docId/$pageId"
        renderJobs.remove(key)?.cancel()
        val job = scope.launch(renderDispatcher) {
            try {
                renderNow(docId, pageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Falha ao processar a página $pageId", e)
            }
        }
        renderJobs[key] = job
        job.invokeOnCompletion { renderJobs.remove(key, job) }
    }

    /** Espera as imagens ficarem atualizadas. Usado antes de exportar. */
    suspend fun awaitRendered(docId: String, pageIds: Collection<String>) {
        for (id in pageIds) {
            renderJobs["$docId/$id"]?.join()
            val page = document(docId)?.pages?.find { it.id == id } ?: continue
            if (page.isStale) withContext(renderDispatcher) { renderNow(docId, id) }
        }
    }

    private suspend fun renderNow(docId: String, pageId: String) {
        val page = document(docId)?.pages?.find { it.id == pageId } ?: return
        if (!page.isStale) return
        val edit = page.edit
        val dir = pageDir(docId, pageId)
        val stamp = System.nanoTime()
        val thumbName = "thumb_$stamp.jpg"

        var renderName: String? = null
        var width = page.sourceWidth
        var height = page.sourceHeight

        if (edit.isIdentity) {
            PageRenderer.writeThumbnailFrom(File(dir, page.sourceFile), File(dir, thumbName))
        } else {
            val bitmap = try {
                PageRenderer.render(dir, page.sourceFile, edit)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Pouca memória: processando a página em resolução menor", e)
                PageRenderer.render(dir, page.sourceFile, edit, maxDim = 2200)
            }
            coroutineContext.ensureActive()
            val lossless = PageRenderer.usesLosslessFile(edit)
            renderName = "render_$stamp.${if (lossless) "png" else "jpg"}"
            File(dir, renderName).outputStream().use {
                if (lossless) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }
            }
            PageRenderer.writeThumbnail(bitmap, File(dir, thumbName))
            width = bitmap.width
            height = bitmap.height
            bitmap.recycle()
        }
        if (!coroutineContext.isActive) {
            // Cancelado depois de gravar: não deixa arquivo órfão para trás.
            listOfNotNull(renderName, thumbName).forEach { File(dir, it).delete() }
            coroutineContext.ensureActive()
        }

        var obsolete: List<String> = emptyList()
        mutatePage(docId, pageId, touch = false) { current ->
            if (current.edit != edit) {
                // Veio outra edição enquanto esta rodava: descarta o resultado.
                obsolete = listOfNotNull(renderName, thumbName)
                current
            } else {
                obsolete = listOfNotNull(current.renderFile, current.thumbFile)
                current.copy(
                    renderedEdit = edit,
                    renderFile = renderName,
                    renderWidth = width,
                    renderHeight = height,
                    thumbFile = thumbName,
                )
            }
        }
        obsolete.forEach { File(dir, it).delete() }
    }

    // ---- Persistência ----

    private fun load() {
        val docs = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val file = File(dir, DOC_FILE)
            if (!file.exists()) return@mapNotNull null
            runCatching { json.decodeFromString(ScanDocument.serializer(), file.readText()) }
                .onFailure { Log.e(TAG, "Documento corrompido: ${dir.name}", it) }
                .getOrNull()
        }
        // Documentos de antes do nome automático, que já têm o texto lido.
        state.value = docs.map { doc ->
            val name = doc.pages.firstOrNull { it.text.isNotBlank() }?.let { DocumentNamer.automaticNameFromText(it.text) }
            withAutomaticName(doc, name).also { if (it != doc) runCatching { persist(it) } }
        }.sortedByDescending { it.updatedAt }
    }

    /** Grava em arquivo temporário e renomeia, para uma queda no meio nunca corromper o documento. */
    private fun persist(doc: ScanDocument) {
        val dir = docDir(doc.id).apply { mkdirs() }
        val temp = File(dir, "$DOC_FILE.tmp")
        temp.writeText(json.encodeToString(ScanDocument.serializer(), doc))
        check(temp.renameTo(File(dir, DOC_FILE))) { "Não foi possível salvar o documento" }
    }

    private suspend fun mutate(
        docId: String,
        touch: Boolean = true,
        block: (ScanDocument) -> ScanDocument,
    ): ScanDocument? = lock.withLock {
        val current = state.value.find { it.id == docId } ?: return null
        val changed = block(current)
        if (changed == current) return current
        val updated = if (touch) changed.copy(updatedAt = System.currentTimeMillis()) else changed
        withContext(Dispatchers.IO) { persist(updated) }
        state.value = state.value.map { if (it.id == docId) updated else it }.sortedByDescending { it.updatedAt }
        updated
    }

    private suspend fun mutatePage(
        docId: String,
        pageId: String,
        touch: Boolean = true,
        block: (ScanPage) -> ScanPage,
    ) {
        mutate(docId, touch) { doc ->
            doc.copy(pages = doc.pages.map { if (it.id == pageId) block(it) else it })
        }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun defaultName(time: Long): String =
        "Scan " + SimpleDateFormat("dd-MM-yyyy HH'h'mm", Locale.forLanguageTag("pt-BR")).format(Date(time))

    private companion object {
        const val TAG = "DocumentRepository"
        const val DOC_FILE = "doc.json"
        const val SOURCE_FILE = "source.jpg"
        val DEFAULT_NAME = Regex("^Scan \\d{2}-\\d{2}-\\d{4} \\d{2}h\\d{2}$")
        const val THUMB_FILE = "thumb.jpg"
        const val MAX_TIFF_BYTES = 256 * 1024 * 1024
        const val OCR_FILE = "ocr.json"
        const val PDF_IMPORT_DPI = 220f
        const val PDF_IMPORT_MAX_SIDE = 3000
    }
}
