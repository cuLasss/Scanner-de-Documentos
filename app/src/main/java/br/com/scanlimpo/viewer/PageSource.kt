package br.com.scanlimpo.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import br.com.scanlimpo.core.tiff.TiffReader
import br.com.scanlimpo.imaging.ImageFiles
import br.com.scanlimpo.imaging.OpenedPdf
import br.com.scanlimpo.imaging.PdfOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Um arquivo feito de páginas, desenhadas só quando aparecem na tela. */
interface PageSource : Closeable {
    val pageCount: Int
    fun aspectRatio(index: Int): Float
    suspend fun render(index: Int, targetWidth: Int): Bitmap
}

class PdfPageSource(context: Context, uri: Uri) : PageSource {
    private val opened: OpenedPdf = PdfOpener.open(context, uri)

    // O leitor do Android só abre uma página por vez.
    private val mutex = Mutex()
    private val ratios: FloatArray = FloatArray(opened.renderer.pageCount) { index ->
        opened.renderer.openPage(index).use { it.width.toFloat() / max(1, it.height) }
    }

    override val pageCount: Int get() = ratios.size

    override fun aspectRatio(index: Int): Float = ratios[index]

    override suspend fun render(index: Int, targetWidth: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            opened.renderer.openPage(index).use { page ->
                val scale = targetWidth.toFloat() / max(1, page.width)
                val bitmap = Bitmap.createBitmap(
                    max(1, targetWidth),
                    max(1, (page.height * scale).roundToInt()),
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    override fun close() {
        runCatching { opened.close() }
    }
}

class ImagePageSource(context: Context, uri: Uri) : PageSource {
    private val resolver = context.contentResolver
    private val open = { resolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir a imagem") }
    private val ratio: Float = ImageFiles.uprightSize(open).let { it.width.toFloat() / max(1, it.height) }

    override val pageCount: Int = 1

    override fun aspectRatio(index: Int): Float = ratio

    override suspend fun render(index: Int, targetWidth: Int): Bitmap =
        withContext(Dispatchers.IO) { ImageFiles.decodeUpright(open, MAX_SIDE) }

    override fun close() = Unit

    private companion object {
        const val MAX_SIDE = 4096
    }
}

class TiffPageSource(context: Context, uri: Uri) : PageSource {
    private val reader = TiffReader(
        ImageFiles.readLimited(
            { context.contentResolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o arquivo") },
            MAX_BYTES,
        ),
    )

    override val pageCount: Int = reader.pages.size

    override fun aspectRatio(index: Int): Float = reader.pages[index].let { it.width.toFloat() / max(1, it.height) }

    override suspend fun render(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.Default) {
        // A leitura reduz por amostragem, então pede o dobro para não perder nitidez ao ampliar.
        val decoded = reader.decode(index, max(targetWidth * 2, 2048))
        Bitmap.createBitmap(decoded.pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
    }

    override fun close() = Unit

    private companion object {
        const val MAX_BYTES = 256 * 1024 * 1024
    }
}
