package br.com.scanlimpo.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/** Leitura de imagens de qualquer origem, sempre com a orientação certa. */
object ImageFiles {
    private fun bounds(open: () -> InputStream): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, options) }
        return options
    }

    private fun orientation(open: () -> InputStream): Int = runCatching {
        open().use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun swapsSides(orientation: Int) = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE,
        -> true
        else -> false
    }

    private fun exifMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }

    /** Tamanho da imagem já considerando a orientação. */
    fun uprightSize(open: () -> InputStream): Size {
        val options = bounds(open)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Arquivo não é uma imagem")
        return if (swapsSides(orientation(open))) {
            Size(options.outHeight, options.outWidth)
        } else {
            Size(options.outWidth, options.outHeight)
        }
    }

    /** Decodifica em pé, sem transparência e com o lado maior limitado a [maxSide]. */
    fun decodeUpright(open: () -> InputStream, maxSide: Int): Bitmap {
        val info = bounds(open)
        if (info.outWidth <= 0 || info.outHeight <= 0) throw IOException("Arquivo não é uma imagem")
        val longest = max(info.outWidth, info.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Não foi possível ler a imagem")

        val matrix = exifMatrix(orientation(open))
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        if (scale < 1f) matrix.postScale(scale, scale)
        val upright = if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        if (upright !== decoded) decoded.recycle()
        if (!upright.hasAlpha()) return upright

        // PNG com transparência: o JPEG não tem canal alfa, então vai sobre fundo branco.
        val flat = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(upright, 0f, 0f, null)
        }
        upright.recycle()
        return flat
    }

    /**
     * Grava em [dest] um JPEG em pé e dentro do limite de tamanho.
     * Se o arquivo já estiver assim, copia sem recomprimir, para não perder qualidade.
     */
    fun writeNormalizedJpeg(open: () -> InputStream, dest: File, maxSide: Int): Size {
        val info = bounds(open)
        if (info.outWidth <= 0 || info.outHeight <= 0) throw IOException("Arquivo não é uma imagem")
        val alreadyGood = info.outMimeType == "image/jpeg" &&
            orientation(open) == ExifInterface.ORIENTATION_NORMAL &&
            max(info.outWidth, info.outHeight) <= maxSide
        if (alreadyGood) {
            open().use { input -> dest.outputStream().use { input.copyTo(it) } }
            return Size(info.outWidth, info.outHeight)
        }
        val bitmap = decodeUpright(open, maxSide)
        val size = Size(bitmap.width, bitmap.height)
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return size
    }

    /** Lê o arquivo inteiro, recusando o que passar do limite. */
    fun readLimited(open: () -> InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        open().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > limit) throw IOException("Arquivo grande demais")
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
