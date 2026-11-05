package br.com.scanlimpo.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import br.com.scanlimpo.core.imaging.EdgeDetector
import br.com.scanlimpo.core.imaging.ImageFilters
import br.com.scanlimpo.core.imaging.PageFilter
import br.com.scanlimpo.data.MarkKind
import br.com.scanlimpo.data.PageEdit
import br.com.scanlimpo.data.Pt
import br.com.scanlimpo.data.Quad
import br.com.scanlimpo.data.RectMark
import br.com.scanlimpo.data.SignatureMark
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aplica as edições sobre a imagem original, sem nunca alterá-la:
 * geometria (giro e recorte em perspectiva), filtro e, por último, marcações.
 */
object PageRenderer {
    const val MAX_SIDE = 4096
    private const val THUMB_SIDE = 480
    private const val SCREEN_SIDE = 2800

    /** Decodifica reduzindo em potências de 2 quando possível. O lado maior pode ficar acima de [maxDim]. */
    fun decode(file: File, maxDim: Int = 0): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0) { "Imagem inválida: ${file.name}" }
        var sample = 1
        if (maxDim > 0) {
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= maxDim) sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return checkNotNull(BitmapFactory.decodeFile(file.path, options)) { "Não foi possível ler ${file.name}" }
    }

    /** Lê para mostrar na tela: 2800 px bastam para letra miúda com zoom, e a imagem já vai para a placa de vídeo. */
    fun decodeForScreen(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SCREEN_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.HARDWARE
        }
        BitmapFactory.decodeFile(file.path, options) ?: decode(file, SCREEN_SIDE)
    }.getOrNull()

    /** Converte uma imagem processada para a placa de vídeo, em segundo plano, sem engasgar no primeiro desenho. */
    fun forScreen(bitmap: Bitmap): Bitmap {
        val ready = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: return bitmap
        bitmap.recycle()
        return ready
    }

    fun render(dir: File, sourceFile: String, edit: PageEdit, maxDim: Int = MAX_SIDE): Bitmap {
        val mutable = renderGeometry(dir, sourceFile, edit, maxDim)
        if (edit.filter != PageFilter.ORIGINAL || edit.brightness != 0f || edit.contrast != 0f) {
            val w = mutable.width
            val h = mutable.height
            val px = IntArray(w * h)
            mutable.getPixels(px, 0, w, 0, 0, w, h)
            ImageFilters.apply(edit.filter, px, w, h, edit.filterStrength, edit.brightness, edit.contrast)
            mutable.setPixels(px, 0, w, 0, 0, w, h)
        }
        drawOverlays(mutable, dir, edit)
        return mutable
    }

    /**
     * Base da prévia ao vivo do P&B: a página em cinza, antes do corte, e o limiar sem ajuste.
     * O corte em si é feito na hora de desenhar, para "mais tinta / menos tinta" responder enquanto a barra anda.
     */
    fun renderBilevelBase(dir: File, sourceFile: String, edit: PageEdit, maxDim: Int): Pair<Bitmap, Int> {
        val mutable = renderGeometry(dir, sourceFile, edit, maxDim)
        val w = mutable.width
        val h = mutable.height
        val px = IntArray(w * h)
        mutable.getPixels(px, 0, w, 0, 0, w, h)
        val threshold = ImageFilters.bilevelBase(px, w, h)
        mutable.setPixels(px, 0, w, 0, 0, w, h)
        drawOverlays(mutable, dir, edit)
        return mutable to threshold
    }

    private fun renderGeometry(dir: File, sourceFile: String, edit: PageEdit, maxDim: Int): Bitmap {
        val source = decode(File(dir, sourceFile), maxDim)
        val out = applyGeometry(source, edit, maxDim)
        if (out !== source) source.recycle()
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
    }

    /** Imagem original girada, sem recorte. É a base da tela de recorte. */
    fun rotatedSource(dir: File, sourceFile: String, rotation: Int, maxDim: Int): Bitmap {
        val source = decode(File(dir, sourceFile), maxDim)
        val out = applyGeometry(source, PageEdit(rotation = rotation), maxDim)
        if (out !== source) source.recycle()
        return out
    }

    /** O P&B puro fica sem perdas e bem menor em PNG. Com cor por cima, volta a ser foto. */
    fun usesLosslessFile(edit: PageEdit): Boolean =
        edit.filter == PageFilter.BW && edit.filterStrength >= 1f &&
            edit.signatures.isEmpty() && edit.marks.none { it.kind == MarkKind.HIGHLIGHT }

    fun writeThumbnail(bitmap: Bitmap, file: File) {
        val scale = min(1f, THUMB_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        file.outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (thumb !== bitmap) thumb.recycle()
    }

    fun writeThumbnailFrom(source: File, file: File) {
        val bitmap = decode(source, THUMB_SIDE)
        writeThumbnail(bitmap, file)
        bitmap.recycle()
    }

    /** Procura as bordas do documento em uma foto. */
    fun detectQuad(file: File): Quad? {
        val bitmap = decode(file, 640)
        val quad = detectQuad(bitmap)
        bitmap.recycle()
        return quad
    }

    fun detectQuad(bitmap: Bitmap): Quad? {
        val scale = min(1f, EdgeDetector.IDEAL_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true,
            )
        } else {
            bitmap
        }
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return EdgeDetector.detect(px, w, h)?.let(Quad::of)
    }

    private fun applyGeometry(source: Bitmap, edit: PageEdit, maxDim: Int): Bitmap {
        val rotation = edit.rotation.mod(360)
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()

        if (rotation == 0 && edit.quad == null) {
            val scale = if (maxDim > 0) min(1f, maxDim / max(sw, sh)) else 1f
            if (scale >= 1f) return source
            return Bitmap.createScaledBitmap(
                source,
                max(1, (sw * scale).roundToInt()),
                max(1, (sh * scale).roundToInt()),
                true,
            )
        }

        val rotatedWidth = if (rotation == 90 || rotation == 270) sh else sw
        val rotatedHeight = if (rotation == 90 || rotation == 270) sw else sh
        val quad = edit.quad ?: Quad.FULL

        fun toRotatedPixels(p: Pt) = floatArrayOf(p.x * rotatedWidth, p.y * rotatedHeight)

        // Caminho inverso: da imagem girada para o arquivo original.
        fun toSource(p: Pt): FloatArray {
            val px = p.x * rotatedWidth
            val py = p.y * rotatedHeight
            return when (rotation) {
                90 -> floatArrayOf(py, sh - px)
                180 -> floatArrayOf(sw - px, sh - py)
                270 -> floatArrayOf(sw - py, px)
                else -> floatArrayOf(px, py)
            }
        }

        fun distance(a: Pt, b: Pt): Float {
            val pa = toRotatedPixels(a)
            val pb = toRotatedPixels(b)
            return hypot(pa[0] - pb[0], pa[1] - pb[1])
        }

        var outW = max(distance(quad.tl, quad.tr), distance(quad.bl, quad.br))
        var outH = max(distance(quad.tl, quad.bl), distance(quad.tr, quad.br))
        if (maxDim > 0) {
            val scale = min(1f, maxDim / max(outW, outH))
            outW *= scale
            outH *= scale
        }
        val width = max(1, outW.roundToInt())
        val height = max(1, outH.roundToInt())

        val src = toSource(quad.tl) + toSource(quad.tr) + toSource(quad.br) + toSource(quad.bl)
        val dst = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix()
        check(matrix.setPolyToPoly(src, 0, dst, 0, 4)) { "Recorte inválido" }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    private fun drawOverlays(bitmap: Bitmap, dir: File, edit: PageEdit) {
        if (!edit.hasOverlays) return
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        edit.marks.forEach { drawMark(canvas, it, w, h) }
        edit.signatures.forEach { drawSignature(canvas, dir, it, w, h) }
    }

    private fun drawMark(canvas: Canvas, mark: RectMark, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (mark.kind) {
            MarkKind.BLACK -> paint.color = Color.BLACK
            MarkKind.WHITE -> paint.color = Color.WHITE
            MarkKind.HIGHLIGHT -> {
                paint.color = Color.rgb(255, 225, 60)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }
        canvas.drawRect(mark.x * w, mark.y * h, (mark.x + mark.w) * w, (mark.y + mark.h) * h, paint)
    }

    private fun drawSignature(canvas: Canvas, dir: File, mark: SignatureMark, w: Float, h: Float) {
        val file = File(dir, mark.file)
        if (!file.exists()) return
        val signature = BitmapFactory.decodeFile(file.path) ?: return
        val width = mark.size * min(w, h)
        val height = width * signature.height / signature.width
        canvas.save()
        canvas.translate(mark.cx * w, mark.cy * h)
        canvas.rotate(mark.rotation.toFloat())
        canvas.drawBitmap(
            signature,
            null,
            RectF(-width / 2f, -height / 2f, width / 2f, height / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        canvas.restore()
        signature.recycle()
    }
}
