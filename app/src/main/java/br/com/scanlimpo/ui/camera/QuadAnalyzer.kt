package br.com.scanlimpo.ui.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import br.com.scanlimpo.core.imaging.EdgeDetector
import kotlin.math.ceil
import kotlin.math.max

/** Procura o documento em cada quadro da câmera. Devolve os cantos normalizados, já em pé, ou null. */
class QuadAnalyzer(private val onResult: (FloatArray?) -> Unit) : ImageAnalysis.Analyzer {
    private var plane = ByteArray(0)
    private var gray = IntArray(0)
    private var frames = 0
    private var totalMillis = 0L

    override fun analyze(image: ImageProxy) {
        val start = System.nanoTime()
        val quad = try {
            detect(image)
        } finally {
            image.close()
        }
        onResult(quad)
        totalMillis += (System.nanoTime() - start) / 1_000_000
        if (++frames == 60) {
            Log.d(TAG, "Detecção ao vivo: ${totalMillis / frames} ms por quadro")
            frames = 0
            totalMillis = 0
        }
    }

    private fun detect(image: ImageProxy): FloatArray? {
        val crop = image.cropRect
        val y = image.planes[0]
        val rowStride = y.rowStride
        val pixelStride = y.pixelStride
        val buffer = y.buffer
        buffer.rewind()
        val size = buffer.remaining()
        if (plane.size < size) plane = ByteArray(size)
        buffer.get(plane, 0, size)

        // Reduz fazendo a média dos pixels: além de leve, tira o ruído do sensor.
        val step = max(1, ceil(max(crop.width(), crop.height()) / EdgeDetector.IDEAL_SIDE.toDouble()).toInt())
        val w = crop.width() / step
        val h = crop.height() / step
        if (w < 48 || h < 48) return null

        val rotation = image.imageInfo.rotationDegrees
        val swap = rotation == 90 || rotation == 270
        val outW = if (swap) h else w
        val outH = if (swap) w else h
        if (gray.size != outW * outH) gray = IntArray(outW * outH)

        val area = step * step
        for (row in 0 until h) {
            val baseY = crop.top + row * step
            for (col in 0 until w) {
                val baseX = crop.left + col * step
                var sum = 0
                for (dy in 0 until step) {
                    val line = (baseY + dy) * rowStride + baseX * pixelStride
                    for (dx in 0 until step) sum += plane[line + dx * pixelStride].toInt() and 0xFF
                }
                val value = sum / area
                val index = when (rotation) {
                    90 -> col * outW + (h - 1 - row)
                    180 -> (h - 1 - row) * outW + (w - 1 - col)
                    270 -> (w - 1 - col) * outW + row
                    else -> row * outW + col
                }
                gray[index] = value
            }
        }
        return EdgeDetector.detect(gray, outW, outH)
    }

    private companion object {
        const val TAG = "QuadAnalyzer"
    }
}
