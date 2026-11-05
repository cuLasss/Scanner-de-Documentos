package br.com.scanlimpo.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import br.com.scanlimpo.core.docx.DocxWriter
import br.com.scanlimpo.core.imaging.ImageFilters
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.core.pdf.PdfImage
import br.com.scanlimpo.core.pdf.PdfWord
import br.com.scanlimpo.core.pdf.PdfWriter
import br.com.scanlimpo.core.text.OcrText
import br.com.scanlimpo.core.tiff.TiffWriter
import br.com.scanlimpo.data.DocumentRepository
import br.com.scanlimpo.data.ScanDocument
import br.com.scanlimpo.data.ScanPage
import br.com.scanlimpo.imaging.PageRenderer
import br.com.scanlimpo.ocr.OcrCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ExportFormat(val label: String, val hint: String, val extension: String, val mime: String) {
    PDF("PDF", "O mais comum", "pdf", "application/pdf"),
    PDF_SEARCHABLE("PDF pesquisável", "Dá pra buscar e copiar o texto", "pdf", "application/pdf"),
    DOCX("Word", "Texto editável", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    JPG("JPG", "Imagem leve", "jpg", "image/jpeg"),
    PNG("PNG", "Imagem sem perdas", "png", "image/png"),
    WEBP("WebP", "Imagem bem compacta", "webp", "image/webp"),
    TIFF("TIFF", "Arquivo multipágina", "tif", "image/tiff"),
    TXT("Texto", "Só o texto reconhecido", "txt", "text/plain"),
    ZIP("ZIP", "Imagens em um arquivo só", "zip", "application/zip"),
    ;

    val isPdf get() = this == PDF || this == PDF_SEARCHABLE
}

enum class ExportQuality(val label: String, val detail: String, val spec: ImageSpec) {
    ORIGINAL("Original", "Sem reduzir", ImageSpec(0, 92)),
    HIGH("Alta", "300 DPI", ImageSpec(3508, 85)),
    MEDIUM("Média", "200 DPI", ImageSpec(2339, 72)),
    LOW("Baixa", "130 DPI", ImageSpec(1520, 55)),
}

/** maxDim = 0 significa sem reduzir. */
data class ImageSpec(val maxDim: Int, val jpegQuality: Int)

enum class PaperSize(val label: String, val width: Float, val height: Float) {
    A4("A4", 595.28f, 841.89f),
    LETTER("Carta", 612f, 792f),
    LEGAL("Ofício", 612f, 1008f),
    FIT("Ajustar à imagem", 0f, 0f),
}

enum class DocxMode(val label: String) {
    TEXT("Só texto"),
    IMAGE("Só imagem"),
    BOTH("Imagem e texto"),
}

data class ExportOptions(
    val format: ExportFormat,
    val fileName: String,
    val pageIds: List<String>? = null,
    val quality: ExportQuality = ExportQuality.HIGH,
    val paper: PaperSize = PaperSize.A4,
    val maxBytes: Long? = null,
    val password: String? = null,
    val watermark: String? = null,
    val docxMode: DocxMode = DocxMode.TEXT,
)

class ExportResult(
    val files: List<File>,
    val format: ExportFormat,
    /** Aviso para a pessoa, por exemplo quando o limite de tamanho não foi alcançado. */
    val note: String? = null,
    val hasPassword: Boolean = false,
) {
    val totalBytes: Long get() = files.sumOf { it.length() }
}

class Exporter(
    private val context: Context,
    private val repo: DocumentRepository,
    private val ocr: OcrCoordinator,
) {
    private val outputRoot get() = File(context.cacheDir, EXPORTS_DIR)

    suspend fun export(
        docId: String,
        options: ExportOptions,
        progress: (Float, String) -> Unit,
    ): ExportResult = withContext(Dispatchers.Default) {
        val initial = repo.document(docId) ?: error("Documento não encontrado")
        val ids = options.pageIds?.takeIf { it.isNotEmpty() } ?: initial.pages.map { it.id }

        progress(0f, "Preparando as páginas…")
        repo.awaitRendered(docId, ids)

        // Sempre garante o OCR atualizado antes de usar texto, para nada sair de uma versão anterior às tarjas.
        val needsText = options.format == ExportFormat.PDF_SEARCHABLE || options.format == ExportFormat.TXT ||
            (options.format == ExportFormat.DOCX && options.docxMode != DocxMode.IMAGE)
        if (needsText) {
            ocr.ensure(docId, ids) { done, total ->
                progress(0.3f * done / max(1, total), "Lendo o texto da página ${min(done + 1, total)} de $total…")
            }
        }

        val doc = repo.document(docId) ?: error("Documento não encontrado")
        val byId = doc.pages.associateBy { it.id }
        val pages = ids.mapNotNull { byId[it] }
        check(pages.isNotEmpty()) { "Nenhuma página para exportar" }

        val dir = File(outputRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
        val base = sanitizeFileName(options.fileName).ifEmpty { "Documento" }
        val start = if (needsText) 0.3f else 0f
        val step: (Float, String) -> Unit = { fraction, message -> progress(start + (1f - start) * fraction, message) }

        when (options.format) {
            ExportFormat.PDF, ExportFormat.PDF_SEARCHABLE -> exportPdf(doc, pages, options, dir, base, step)
            ExportFormat.DOCX -> exportDocx(doc, pages, options, dir, base, step)
            ExportFormat.JPG, ExportFormat.PNG, ExportFormat.WEBP -> exportImages(doc, pages, options, dir, base, step)
            ExportFormat.TIFF -> exportTiff(doc, pages, options, dir, base, step)
            ExportFormat.TXT -> exportText(pages, dir, base)
            ExportFormat.ZIP -> exportZip(doc, pages, options, dir, base, step)
        }
    }

    /** Estimativa de tamanho a partir de uma amostra de páginas, para mostrar antes de gerar. Null quando não se aplica. */
    suspend fun estimate(docId: String, options: ExportOptions): Long? = withContext(Dispatchers.Default) {
        val usesImages = when (options.format) {
            ExportFormat.PDF, ExportFormat.PDF_SEARCHABLE, ExportFormat.JPG, ExportFormat.WEBP, ExportFormat.ZIP -> true
            ExportFormat.DOCX -> options.docxMode != DocxMode.TEXT
            else -> false
        }
        if (!usesImages) return@withContext null
        val doc = repo.document(docId) ?: return@withContext null
        val ids = options.pageIds?.takeIf { it.isNotEmpty() }?.toSet()
        val pages = if (ids != null) doc.pages.filter { it.id in ids } else doc.pages
        if (pages.isEmpty()) return@withContext null
        val samples = listOf(pages.first(), pages[pages.size / 2]).distinctBy { it.id }
        var sum = 0L
        for (page in samples) {
            coroutineContext.ensureActive()
            sum += if (options.format == ExportFormat.WEBP) {
                val bitmap = loadForExport(doc, page, options.quality.spec.maxDim, options.watermark)
                val size = encode(bitmap, ExportFormat.WEBP, options.quality.spec.jpegQuality).size
                bitmap.recycle()
                size.toLong()
            } else {
                encodePdfImage(doc, page, options.quality.spec, options.watermark).payloadSize.toLong()
            }
        }
        sum * pages.size / samples.size
    }

    // ---- PDF ----

    private suspend fun exportPdf(
        doc: ScanDocument,
        pages: List<ScanPage>,
        o: ExportOptions,
        dir: File,
        base: String,
        progress: (Float, String) -> Unit,
    ): ExportResult {
        val file = File(dir, "$base.pdf")
        val target = o.maxBytes
        var note: String? = null

        if (target == null) {
            writePdf(doc, pages, o, file, o.quality.spec) { i ->
                progress(i.toFloat() / pages.size, "Montando a página ${min(i + 1, pages.size)} de ${pages.size}…")
            }
        } else {
            // Primeiro acha um bom ponto de partida com amostras, depois confere gerando o arquivo de verdade.
            progress(0f, "Calculando a melhor qualidade para caber em ${formatSize(target)}…")
            var index = estimateLadderStart(doc, pages, o, target)
            while (true) {
                val spec = SIZE_LADDER[index]
                writePdf(doc, pages, o, file, spec) { i ->
                    val overall = (index + i.toFloat() / pages.size) / SIZE_LADDER.size
                    progress(overall, "Ajustando o tamanho do arquivo…")
                }
                if (file.length() <= target || index == SIZE_LADDER.lastIndex) break
                index++
            }
            if (file.length() > target) {
                note = "Não deu pra chegar em ${formatSize(target)} sem deixar ilegível. O menor possível ficou com ${formatSize(file.length())}."
            }
        }
        return ExportResult(listOf(file), o.format, note, hasPassword = !o.password.isNullOrEmpty())
    }

    private suspend fun estimateLadderStart(doc: ScanDocument, pages: List<ScanPage>, o: ExportOptions, target: Long): Int {
        val samples = listOf(pages.first(), pages[pages.size / 2], pages.last()).distinctBy { it.id }
        for ((index, spec) in SIZE_LADDER.withIndex()) {
            coroutineContext.ensureActive()
            var sum = 0L
            for (page in samples) sum += encodePdfImage(doc, page, spec, o.watermark).payloadSize
            val projected = sum * pages.size / samples.size
            if (projected <= target * 0.95) return index
        }
        return SIZE_LADDER.lastIndex
    }

    private suspend fun writePdf(
        doc: ScanDocument,
        pages: List<ScanPage>,
        o: ExportOptions,
        file: File,
        spec: ImageSpec,
        onPage: (Int) -> Unit,
    ) {
        PdfWriter(file.outputStream().buffered(), o.password, o.fileName).use { pdf ->
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                onPage(index)
                val image = encodePdfImage(doc, page, spec, o.watermark)
                val words = if (o.format == ExportFormat.PDF_SEARCHABLE) {
                    repo.loadOcrWords(doc.id, page.id).map { PdfWord(it.t, it.x, it.y, it.w, it.h) }
                } else {
                    emptyList()
                }
                val (w, h) = pageSize(o.paper, image.width, image.height)
                pdf.addFittedPage(w, h, image, words)
            }
        }
        onPage(pages.size)
    }

    /** P&B puro vira imagem de 1 bit. O resto, JPEG. Sem marca d'água e sem reduzir, aproveita o JPEG como está. */
    private fun encodePdfImage(doc: ScanDocument, page: ScanPage, spec: ImageSpec, watermark: String?): PdfImage {
        val file = repo.displayFile(doc.id, page)
        val isBilevel = page.renderedEdit.filter == PageFilter.BW && PageRenderer.usesLosslessFile(page.renderedEdit)
        if (isBilevel && watermark == null) {
            val bitmap = loadForExport(doc, page, spec.maxDim, null)
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            bitmap.recycle()
            return PdfImage.Bilevel(w, h, ImageFilters.packBilevel(px, w, h))
        }
        if (spec.maxDim == 0 && watermark == null && file.extension == "jpg") {
            return PdfImage.Jpeg(file.readBytes())
        }
        val bitmap = loadForExport(doc, page, spec.maxDim, watermark)
        val bytes = encode(bitmap, ExportFormat.JPG, spec.jpegQuality)
        bitmap.recycle()
        return PdfImage.Jpeg(bytes)
    }

    private fun pageSize(paper: PaperSize, imageW: Int, imageH: Int): Pair<Float, Float> {
        if (paper == PaperSize.FIT) {
            val scale = 842f / max(imageW, imageH)
            return (imageW * scale) to (imageH * scale)
        }
        return if (imageW > imageH) paper.height to paper.width else paper.width to paper.height
    }

    // ---- Outros formatos ----

    private suspend fun exportImages(
        doc: ScanDocument,
        pages: List<ScanPage>,
        o: ExportOptions,
        dir: File,
        base: String,
        progress: (Float, String) -> Unit,
    ): ExportResult {
        val files = pages.mapIndexed { index, page ->
            coroutineContext.ensureActive()
            progress(index.toFloat() / pages.size, "Gerando a imagem ${index + 1} de ${pages.size}…")
            val name = numberedName(base, index, pages.size, o.format.extension)
            val bitmap = loadForExport(doc, page, o.quality.spec.maxDim, o.watermark)
            File(dir, name).apply { writeBytes(encode(bitmap, o.format, o.quality.spec.jpegQuality)) }
                .also { bitmap.recycle() }
        }
        return ExportResult(files, o.format)
    }

    private suspend fun exportZip(
        doc: ScanDocument,
        pages: List<ScanPage>,
        o: ExportOptions,
        dir: File,
        base: String,
        progress: (Float, String) -> Unit,
    ): ExportResult {
        val file = File(dir, "$base.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                progress(index.toFloat() / pages.size, "Compactando a página ${index + 1} de ${pages.size}…")
                val bitmap = loadForExport(doc, page, o.quality.spec.maxDim, o.watermark)
                val bytes = encode(bitmap, ExportFormat.JPG, o.quality.spec.jpegQuality)
                bitmap.recycle()
                zip.putNextEntry(ZipEntry(numberedName(base, index, pages.size, "jpg")))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ExportResult(listOf(file), o.format)
    }

    private suspend fun exportTiff(
        doc: ScanDocument,
        pages: List<ScanPage>,
        o: ExportOptions,
        dir: File,
        base: String,
        progress: (Float, String) -> Unit,
    ): ExportResult {
        val file = File(dir, "$base.tif")
        val dpi = if (o.quality == ExportQuality.ORIGINAL) 300 else (o.quality.spec.maxDim / 11.69f).roundToInt()
        TiffWriter(file.outputStream().buffered(), pages.size).use { tiff ->
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                progress(index.toFloat() / pages.size, "Gravando a página ${index + 1} de ${pages.size}…")
                val bitmap = loadForExport(doc, page, o.quality.spec.maxDim, o.watermark)
                val w = bitmap.width
                val h = bitmap.height
                val px = IntArray(w * h)
                bitmap.getPixels(px, 0, w, 0, 0, w, h)
                bitmap.recycle()
                val filter = page.renderedEdit.filter
                when {
                    filter == PageFilter.BW && ImageFilters.isBilevel(px) ->
                        tiff.addBilevelPage(ImageFilters.packBilevel(px, w, h), w, h, dpi)
                    else -> {
                        val fullGray = filter == PageFilter.GRAY && page.renderedEdit.filterStrength >= 1f && !page.renderedEdit.hasOverlays
                        tiff.addPage(px, w, h, grayscale = fullGray, dpi)
                    }
                }
            }
        }
        return ExportResult(listOf(file), o.format)
    }

    private suspend fun exportDocx(
        doc: ScanDocument,
        pages: List<ScanPage>,
        o: ExportOptions,
        dir: File,
        base: String,
        progress: (Float, String) -> Unit,
    ): ExportResult {
        val file = File(dir, "$base.docx")
        var anyText = false
        DocxWriter(file.outputStream().buffered(), o.fileName).use { docx ->
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                progress(index.toFloat() / pages.size, "Montando a página ${index + 1} de ${pages.size}…")
                if (index > 0) docx.pageBreak()

                if (o.docxMode != DocxMode.TEXT) {
                    val bitmap = loadForExport(doc, page, o.quality.spec.maxDim, o.watermark)
                    val bytes = encode(bitmap, ExportFormat.JPG, o.quality.spec.jpegQuality)
                    docx.addJpeg(bytes, bitmap.width, bitmap.height)
                    bitmap.recycle()
                    if (o.docxMode == DocxMode.BOTH) docx.pageBreak()
                }
                if (o.docxMode != DocxMode.IMAGE) {
                    val paragraphs = OcrText.paragraphs(page.text)
                    if (paragraphs.isEmpty()) {
                        docx.addParagraph(if (pages.size > 1) "(Página ${index + 1} sem texto reconhecido)" else "")
                    } else {
                        anyText = true
                        paragraphs.forEach { docx.addParagraph(it) }
                    }
                }
            }
        }
        val note = if (o.docxMode == DocxMode.TEXT && !anyText) {
            "Não encontrei texto nessas páginas. Use \"Só imagem\" se quiser o documento como foto."
        } else {
            null
        }
        return ExportResult(listOf(file), o.format, note)
    }

    private fun exportText(pages: List<ScanPage>, dir: File, base: String): ExportResult {
        val file = File(dir, "$base.txt")
        val text = if (pages.size == 1) {
            pages[0].text
        } else {
            pages.mapIndexed { i, page -> "--- Página ${i + 1} ---\n${page.text}" }.joinToString("\n\n")
        }
        file.writeText(text.trim() + "\n", Charsets.UTF_8)
        val empty = pages.all { it.text.isBlank() }
        return ExportResult(
            listOf(file),
            ExportFormat.TXT,
            if (empty) "Não encontrei texto nessas páginas." else null,
        )
    }

    // ---- Apoio ----

    private fun loadForExport(doc: ScanDocument, page: ScanPage, maxDim: Int, watermark: String?): Bitmap {
        val decoded = PageRenderer.decode(repo.displayFile(doc.id, page), maxDim)
        val longest = max(decoded.width, decoded.height)
        val scaled = if (maxDim > 0 && longest > maxDim) {
            val scale = maxDim.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (decoded.width * scale).roundToInt()),
                max(1, (decoded.height * scale).roundToInt()),
                true,
            ).also { decoded.recycle() }
        } else {
            decoded
        }
        if (watermark.isNullOrBlank()) return scaled
        val mutable = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
        drawWatermark(mutable, watermark)
        return mutable
    }

    private fun drawWatermark(bitmap: Bitmap, text: String) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 120, 120, 120)
            textSize = min(w, h) / 13f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.rotate(-32f, w / 2f, h / 2f)
        val diagonal = kotlin.math.hypot(w, h)
        val rowGap = paint.textSize * 4.2f
        val columnGap = paint.measureText(text) + paint.textSize * 2.5f
        var y = h / 2f - diagonal
        var odd = false
        while (y < h / 2f + diagonal) {
            var x = w / 2f - diagonal + if (odd) columnGap / 2f else 0f
            while (x < w / 2f + diagonal) {
                canvas.drawText(text, x, y, paint)
                x += columnGap
            }
            y += rowGap
            odd = !odd
        }
        canvas.restore()
    }

    private fun encode(bitmap: Bitmap, format: ExportFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        when (format) {
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.WEBP -> {
                @Suppress("DEPRECATION")
                val webp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(webp, quality, out)
            }
            else -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return out.toByteArray()
    }

    private fun numberedName(base: String, index: Int, total: Int, extension: String): String =
        if (total == 1) "$base.$extension" else "${base}_${(index + 1).toString().padStart(3, '0')}.$extension"

    companion object {
        const val EXPORTS_DIR = "exports"

        // Do melhor para o menor. Usado quando há limite de tamanho (os portais do governo costumam ter).
        private val SIZE_LADDER = listOf(
            ImageSpec(3508, 85),
            ImageSpec(2900, 80),
            ImageSpec(2339, 75),
            ImageSpec(1900, 70),
            ImageSpec(1600, 62),
            ImageSpec(1350, 55),
            ImageSpec(1100, 48),
            ImageSpec(900, 40),
            ImageSpec(720, 32),
        )

        private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun sanitizeFileName(name: String): String =
            ILLEGAL_NAME_CHARS.replace(name, " ").replace(Regex("\\s+"), " ").trim().trim('.').take(120)

        fun formatSize(bytes: Long): String {
            val locale = java.util.Locale.forLanguageTag("pt-BR")
            return when {
                bytes >= 1024 * 1024 -> String.format(locale, "%.1f MB", bytes / (1024f * 1024f))
                bytes >= 1024 -> "${bytes / 1024} KB"
                else -> "$bytes bytes"
            }
        }

        /** Tira da frente exportações antigas. As recentes ficam, porque outro app pode estar lendo. */
        fun cleanOldExports(context: Context) {
            val limit = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
            File(context.cacheDir, EXPORTS_DIR).listFiles().orEmpty().forEach { dir ->
                if (dir.lastModified() < limit) dir.deleteRecursively()
            }
        }
    }
}
