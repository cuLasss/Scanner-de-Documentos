package br.com.scanlimpo.ui.annotate

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val INK_COLORS = listOf(Color(0xFF111111), Color(0xFF12389C))

/** Liga os pontos com curvas pelos pontos médios: o traço fica liso mesmo com o dedo rápido. */
private fun buildPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    if (points.size == 1) {
        lineTo(points[0].x + 0.1f, points[0].y)
        return@apply
    }
    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        quadraticTo(previous.x, previous.y, (previous.x + current.x) / 2f, (previous.y + current.y) / 2f)
    }
    lineTo(points.last().x, points.last().y)
}

@Composable
fun SignaturePadDialog(onDismiss: () -> Unit, onSave: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var colorIndex by remember { mutableStateOf(0) }
    var padWidth by remember { mutableStateOf(0) }
    val strokeWidthPx = with(LocalDensity.current) { 3.2.dp.toPx() }
    val inkColor = INK_COLORS[colorIndex]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Cancelar") }
                    Text("Sua assinatura", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { strokes.removeLastOrNull() }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, "Desfazer traço")
                    }
                }
                Spacer(Modifier.size(12.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .onSizeChanged { padWidth = it.width }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                current.clear()
                                current.add(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    current.add(change.position)
                                    change.consume()
                                }
                                strokes.add(current.toList())
                                current.clear()
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val guideY = size.height * 0.72f
                        drawLine(Color(0xFFCCCCCC), Offset(32f, guideY), Offset(size.width - 32f, guideY), 2f)
                        val style = Stroke(strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        strokes.forEach { drawPath(buildPath(it), inkColor, style = style) }
                        if (current.isNotEmpty()) drawPath(buildPath(current.toList()), inkColor, style = style)
                    }
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Text(
                            "Assine aqui com o dedo",
                            color = Color(0xFF8A8A8A),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    INK_COLORS.forEachIndexed { index, color ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    3.dp,
                                    if (index == colorIndex) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable { colorIndex = index },
                        )
                    }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Limpar") }
                    Button(
                        enabled = strokes.isNotEmpty(),
                        onClick = { onSave(renderSignature(strokes.toList(), inkColor, strokeWidthPx, padWidth)) },
                    ) { Text("Usar assinatura") }
                }
            }
        }
    }
}

/** Desenha só a área usada, com fundo transparente e em resolução boa para impressão. */
private fun renderSignature(strokes: List<List<Offset>>, color: Color, strokeWidth: Float, padWidth: Int): Bitmap {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (stroke in strokes) {
        for (p in stroke) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
    }
    val padding = strokeWidth * 2f
    val contentWidth = maxX - minX + padding * 2f
    val contentHeight = maxY - minY + padding * 2f
    // Quanto menos a pessoa ocupou da tela, mais ampliamos, para não perder nitidez ao imprimir.
    val scale = (max(1200f, padWidth.toFloat()) / max(contentWidth, 1f)).coerceIn(1f, 3f)

    val bitmap = Bitmap.createBitmap(
        max(1, ceil(contentWidth * scale).toInt()),
        max(1, ceil(contentHeight * scale).toInt()),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = AndroidCanvas(bitmap)
    canvas.scale(scale, scale)
    canvas.translate(padding - minX, padding - minY)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { canvas.drawPath(buildPath(it).asAndroidPath(), paint) }
    return bitmap
}
