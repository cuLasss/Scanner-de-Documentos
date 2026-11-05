package br.com.scanlimpo.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Área com pinça e duplo toque. Só "segura" o gesto de um dedo quando há zoom,
 * senão deixa ele passar para o pager trocar de página.
 *
 * @param rotationDegrees giro visual por cima do conteúdo, usado na animação de girar a página.
 *   É uma função para ser lida só na hora de desenhar: a animação não recompõe a tela a cada quadro.
 * @param contentAspect largura/altura do conteúdo, para ele caber na tela durante o giro.
 */
@Composable
fun ZoomableBox(
    resetKey: Any?,
    modifier: Modifier = Modifier,
    rotationDegrees: () -> Float = { 0f },
    contentAspect: Float = 0f,
    onZoomChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(scale > 1.01f) { onZoomChanged(scale > 1.01f) }

    fun clamp(value: Offset, currentScale: Float): Offset {
        val maxX = size.width * (currentScale - 1f) / 2f
        val maxY = size.height * (currentScale - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, MAX_SCALE)
                            offset = clamp(offset + event.calculatePan(), nextScale)
                            scale = nextScale
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.02f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val next = DOUBLE_TAP_SCALE
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = next
                            offset = clamp((center - tap) * (next - 1f), next)
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val degrees = rotationDegrees()
                    val fit = rotationFit(degrees, contentAspect, size)
                    rotationZ = degrees
                    scaleX = scale * fit
                    scaleY = scale * fit
                    translationX = offset.x
                    translationY = offset.y
                },
            content = content,
        )
    }
}

/**
 * Escala para o conteúdo caber na tela em qualquer ângulo do giro.
 * Em 0° é 1. Em 90° dá exatamente o tamanho que a imagem girada de verdade vai ter, então a troca não dá salto.
 */
private fun rotationFit(degrees: Float, aspect: Float, box: IntSize): Float {
    if (degrees == 0f || aspect <= 0f || box.width == 0 || box.height == 0) return 1f
    val boxW = box.width.toFloat()
    val boxH = box.height.toFloat()
    val fit = min(boxW / aspect, boxH)
    val shownW = fit * aspect
    val shownH = fit
    val angle = degrees * PI.toFloat() / 180f
    val c = abs(cos(angle))
    val s = abs(sin(angle))
    return min(boxW / (shownW * c + shownH * s), boxH / (shownW * s + shownH * c))
}

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.6f
