package br.com.scanlimpo.ui.crop

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.scanlimpo.data.Pt
import br.com.scanlimpo.data.Quad
import br.com.scanlimpo.ui.theme.EditorAccent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

fun Quad.toOffsets(): List<Offset> = listOf(tl, tr, br, bl).map { Offset(it.x, it.y) }

fun List<Offset>.toQuad() = Quad(
    Pt(this[0].x, this[0].y),
    Pt(this[1].x, this[1].y),
    Pt(this[2].x, this[2].y),
    Pt(this[3].x, this[3].y),
)

/** Null quando os cantos cobrem a imagem inteira: não há recorte a aplicar. */
fun List<Offset>.toQuadOrNull(): Quad? {
    val full = Quad.FULL.toOffsets()
    val isFull = indices.all { abs(this[it].x - full[it].x) < 0.004f && abs(this[it].y - full[it].y) < 0.004f }
    return if (isFull) null else toQuad()
}

/** Um canto arrastado não pode cruzar a figura, senão a página sairia espelhada ou torcida. */
private fun isConvex(points: List<Offset>): Boolean {
    var sign = 0
    for (i in 0 until 4) {
        val a = points[i]
        val b = points[(i + 1) % 4]
        val c = points[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
        if (s == 0) return false
        if (sign == 0) sign = s else if (s != sign) return false
    }
    return true
}

private fun clampUnit(p: Offset) = Offset(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

/**
 * A grade de ajuste: mostra a imagem com os quatro cantos e os meios das bordas para arrastar.
 * A grade acompanha a perspectiva, e uma lupa mostra o que o dedo está cobrindo.
 *
 * @param corners cantos normalizados, na ordem TL, TR, BR, BL.
 */
@Composable
fun QuadEditor(
    image: ImageBitmap,
    corners: List<Offset>,
    onCornersChange: (List<Offset>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCorners by rememberUpdatedState(corners)
    val currentOnChange by rememberUpdatedState(onCornersChange)
    var activeHandle by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val margin = with(density) { 28.dp.toPx() }
        val grabRadius = with(density) { 44.dp.toPx() }
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val scale = min((containerW - margin * 2) / image.width, (containerH - margin * 2) / image.height)
        val drawW = image.width * scale
        val drawH = image.height * scale
        val left = (containerW - drawW) / 2f
        val top = (containerH - drawH) / 2f

        fun toScreen(p: Offset) = Offset(left + p.x * drawW, top + p.y * drawH)

        fun handlePositions(): List<Offset> {
            val c = currentCorners.map(::toScreen)
            return c + List(4) { i -> (c[i] + c[(i + 1) % 4]) / 2f }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(left, top, drawW, drawH) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val handles = handlePositions()
                        // Prefere o canto: quando estão muito perto, o meio da borda não pode roubar o toque.
                        val nearest = handles.indices.minByOrNull { i ->
                            (handles[i] - down.position).getDistance() + if (i >= 4) grabRadius * 0.35f else 0f
                        } ?: return@awaitEachGesture
                        if ((handles[nearest] - down.position).getDistance() > grabRadius) return@awaitEachGesture
                        activeHandle = nearest
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            change.consume()
                            val move = Offset(delta.x / drawW, delta.y / drawH)
                            val next = currentCorners.toMutableList()
                            if (nearest < 4) {
                                next[nearest] = clampUnit(next[nearest] + move)
                            } else {
                                val a = nearest - 4
                                val b = (a + 1) % 4
                                next[a] = clampUnit(next[a] + move)
                                next[b] = clampUnit(next[b] + move)
                            }
                            if (isConvex(next)) currentOnChange(next)
                        }
                        activeHandle = null
                    }
                },
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )

            val screen = corners.map(::toScreen)
            val outline = Path().apply {
                moveTo(screen[0].x, screen[0].y)
                for (i in 1 until 4) lineTo(screen[i].x, screen[i].y)
                close()
            }
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(left, top, left + drawW, top + drawH))
                addPath(outline)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.58f))
            drawGrid(screen)
            drawPath(outline, EditorAccent, style = Stroke(2.dp.toPx()))

            val handles = handlePositions()
            handles.forEachIndexed { i, p ->
                val outer = if (i < 4) 12.dp.toPx() else 7.dp.toPx()
                drawCircle(Color.White, outer, p)
                drawCircle(EditorAccent, outer - 3.dp.toPx(), p)
            }

            activeHandle?.let { drawLoupe(image, handles[it], left, top, scale) }
        }
    }
}

/** Linhas que dividem a folha em terços, seguindo a perspectiva: ajudam a ver se o papel vai ficar reto. */
private fun DrawScope.drawGrid(screen: List<Offset>) {
    val matrix = Matrix()
    val unit = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    val target = floatArrayOf(
        screen[0].x, screen[0].y,
        screen[1].x, screen[1].y,
        screen[2].x, screen[2].y,
        screen[3].x, screen[3].y,
    )
    if (!matrix.setPolyToPoly(unit, 0, target, 0, 4)) return
    val color = Color.White.copy(alpha = 0.5f)
    val width = 1.dp.toPx()
    val points = FloatArray(4)
    for (i in 1..2) {
        val t = i / 3f
        floatArrayOf(0f, t, 1f, t).copyInto(points)
        matrix.mapPoints(points)
        drawLine(color, Offset(points[0], points[1]), Offset(points[2], points[3]), width)
        floatArrayOf(t, 0f, t, 1f).copyInto(points)
        matrix.mapPoints(points)
        drawLine(color, Offset(points[0], points[1]), Offset(points[2], points[3]), width)
    }
}

/** Lupa: o dedo cobre o canto bem na hora de acertar. Fica sempre do lado oposto. */
private fun DrawScope.drawLoupe(image: ImageBitmap, target: Offset, left: Float, top: Float, scale: Float) {
    val radius = 64.dp.toPx()
    val zoom = 2.4f
    val center = Offset(
        if (target.x < size.width / 2f) size.width - radius - 12.dp.toPx() else radius + 12.dp.toPx(),
        radius + 8.dp.toPx(),
    )
    val k = scale * zoom
    val srcSize = (radius * 2f / k).roundToInt().coerceAtMost(min(image.width, image.height))
    val focusX = (target.x - left) / scale
    val focusY = (target.y - top) / scale
    val srcLeft = (focusX - srcSize / 2f).roundToInt().coerceIn(0, image.width - srcSize)
    val srcTop = (focusY - srcSize / 2f).roundToInt().coerceIn(0, image.height - srcSize)
    val circle = Path().apply { addOval(Rect(center, radius)) }
    clipPath(circle) {
        drawRect(Color.Black, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
        drawImage(
            image,
            srcOffset = IntOffset(srcLeft, srcTop),
            srcSize = IntSize(srcSize, srcSize),
            dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
            dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
    drawCircle(Color.White, radius, center, style = Stroke(2.5.dp.toPx()))
    val unit = radius * 2f / srcSize
    val crossX = center.x - radius + (focusX - srcLeft) * unit
    val crossY = center.y - radius + (focusY - srcTop) * unit
    val arm = 11.dp.toPx()
    val stroke = 1.5.dp.toPx()
    drawLine(EditorAccent, Offset(crossX - arm, crossY), Offset(crossX + arm, crossY), stroke)
    drawLine(EditorAccent, Offset(crossX, crossY - arm), Offset(crossX, crossY + arm), stroke)
}
