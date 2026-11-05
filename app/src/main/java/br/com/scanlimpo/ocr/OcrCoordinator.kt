package br.com.scanlimpo.ocr

import android.util.Log
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.core.text.DocumentNamer
import br.com.scanlimpo.core.text.OcrBlockInfo
import br.com.scanlimpo.core.text.OcrLineInfo
import br.com.scanlimpo.core.text.OcrText
import br.com.scanlimpo.data.DocumentRepository
import br.com.scanlimpo.data.OcrPageData
import br.com.scanlimpo.data.OcrWord
import br.com.scanlimpo.data.ScanPage
import br.com.scanlimpo.data.Settings
import br.com.scanlimpo.imaging.PageRenderer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** OCR totalmente no aparelho: o modelo vai dentro do APK e nada sai do celular. */
class OcrCoordinator(
    private val repo: DocumentRepository,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val lock = Mutex()
    private val backgroundJobs = ConcurrentHashMap<String, Job>()
    private val progressState = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())

    /** Por documento: páginas prontas e total, enquanto o OCR roda. */
    val progress: StateFlow<Map<String, Pair<Int, Int>>> = progressState.asStateFlow()

    /** Roda depois de uma pequena pausa, para juntar edições seguidas e não refazer trabalho. */
    fun scheduleBackground(docId: String) {
        if (!settings.autoOcr) return
        backgroundJobs.remove(docId)?.cancel()
        backgroundJobs[docId] = scope.launch {
            delay(900)
            runCatching { ensure(docId) }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "OCR em segundo plano falhou", it)
            }
        }
    }

    /** Garante o OCR atualizado das páginas pedidas (ou de todas). */
    suspend fun ensure(
        docId: String,
        pageIds: Collection<String>? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) = lock.withLock {
        val doc = repo.document(docId) ?: return@withLock
        val pending = doc.pages.filter { (pageIds == null || it.id in pageIds) && !it.hasCurrentOcr }
        if (pending.isEmpty()) {
            onProgress(1, 1)
            return@withLock
        }
        try {
            pending.forEachIndexed { index, page ->
                onProgress(index, pending.size)
                progressState.value = progressState.value + (docId to (index to pending.size))
                try {
                    recognize(docId, page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "OCR falhou na página ${page.id}", e)
                }
            }
            onProgress(pending.size, pending.size)
        } finally {
            progressState.value = progressState.value - docId
        }
    }

    private suspend fun recognize(docId: String, page: ScanPage) {
        val key = page.edit.ocrKey
        val dir = repo.pageDir(docId, page.id)
        // A imagem do OCR tem o recorte e as tarjas, mas não o filtro: o P&B perde detalhes que ajudam a ler.
        val input = page.edit.copy(
            filter = PageFilter.ORIGINAL,
            filterStrength = 1f,
            brightness = 0f,
            contrast = 0f,
            signatures = emptyList(),
        )
        val bitmap = withContext(Dispatchers.Default) { PageRenderer.render(dir, page.sourceFile, input, maxDim = 2600) }
        try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val words = ArrayList<OcrWord>()
            val lines = ArrayList<OcrLineInfo>()
            val blocks = result.textBlocks.mapIndexed { index, block ->
                for (line in block.lines) {
                    line.boundingBox?.let { box ->
                        lines += OcrLineInfo(line.text, box.left / w, box.top / h, box.width() / w, box.height() / h)
                    }
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        words += OcrWord(element.text, box.left / w, box.top / h, box.width() / w, box.height() / h)
                    }
                }
                val box = block.boundingBox
                OcrBlockInfo(
                    block.lines.map { it.text },
                    (box?.left ?: 0) / w,
                    // Sem posição, mantém a ordem em que veio, depois do que tem posição.
                    box?.let { it.top / h } ?: (1f + index),
                    (box?.width() ?: 0) / w,
                    (box?.height() ?: 0) / h,
                )
            }
            val text = OcrText.arrange(blocks)
            repo.setOcrResult(
                docId,
                page.id,
                key,
                text,
                OcrPageData(words),
                DocumentNamer.suggest(lines),
                DocumentNamer.automaticName(lines),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "OcrCoordinator"
    }
}
