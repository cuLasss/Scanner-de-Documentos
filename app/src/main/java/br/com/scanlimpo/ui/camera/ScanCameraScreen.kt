package br.com.scanlimpo.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import br.com.scanlimpo.app
import br.com.scanlimpo.core.imaging.AutoCaptureTracker
import br.com.scanlimpo.data.CapturedPage
import br.com.scanlimpo.data.Quad
import br.com.scanlimpo.imaging.ImageFiles
import br.com.scanlimpo.imaging.PageRenderer
import br.com.scanlimpo.ui.common.ConfirmDialog
import br.com.scanlimpo.ui.common.DarkSystemBars
import br.com.scanlimpo.ui.common.findActivity
import br.com.scanlimpo.ui.crop.QuadEditor
import br.com.scanlimpo.ui.crop.toOffsets
import br.com.scanlimpo.ui.crop.toQuadOrNull
import br.com.scanlimpo.ui.theme.EditorAccent
import br.com.scanlimpo.ui.theme.EditorBackground
import br.com.scanlimpo.ui.theme.EditorMuted
import br.com.scanlimpo.ui.theme.EditorOnSurface
import br.com.scanlimpo.ui.theme.EditorSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

private const val TAG = "ScanCamera"

/** Foto tirada, esperando a pessoa conferir as bordas. */
private class Review(val file: File, val image: ImageBitmap, val quad: Quad?)

/**
 * O scanner do app: câmera com detecção de bordas ao vivo, foto automática ou manual, e depois o
 * ajuste das bordas. Roda inteiro dentro do APK, sem rede e sem depender de nenhum outro app.
 */
@Composable
fun ScanCameraScreen(singleShot: Boolean, onFinish: (List<CapturedPage>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()

    DarkSystemBars()
    LockPortrait()

    val session = remember { File(context.cacheDir, "scan_session").apply { deleteRecursively(); mkdirs() } }
    DisposableEffect(Unit) { onDispose { session.deleteRecursively() } }

    val pages = remember { mutableStateListOf<CapturedPage>() }
    val tracker = remember { AutoCaptureTracker() }
    var review by remember { mutableStateOf<Review?>(null) }
    var processing by remember { mutableStateOf(false) }
    var autoCapture by remember { mutableStateOf(app.settings.autoCapture) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var askedPermission by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        askedPermission = true
    }
    LaunchedEffect(Unit) { if (!hasPermission) permission.launch(Manifest.permission.CAMERA) }
    // Ao voltar das configurações do Android, a permissão pode ter mudado.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasPermission = context.hasCameraPermission() }

    fun leave() {
        if (pages.isEmpty()) onCancel() else confirmDiscard = true
    }

    fun finish() {
        onFinish(pages.toList())
    }

    BackHandler {
        val current = review
        if (current != null) {
            current.file.delete()
            tracker.allowSamePageAgain()
            review = null
        } else {
            leave()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val current = review
        when {
            !hasPermission -> {
                if (askedPermission) {
                    NoPermissionContent(
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                        onClose = onCancel,
                    )
                }
            }
            current != null -> ReviewContent(
                review = current,
                pageNumber = pages.size + 1,
                singleShot = singleShot,
                onRetake = {
                    current.file.delete()
                    tracker.allowSamePageAgain()
                    review = null
                },
                onAccept = { quad, done ->
                    pages += CapturedPage(current.file, quad)
                    review = null
                    if (done) finish()
                },
            )
            !processing -> CameraContent(
                session = session,
                tracker = tracker,
                pageCount = pages.size,
                autoCapture = autoCapture,
                onAutoCaptureChange = {
                    autoCapture = it
                    app.settings.autoCapture = it
                },
                onPhoto = { raw, liveQuad ->
                    processing = true
                    scope.launch {
                        val prepared = withContext(Dispatchers.Default) {
                            runCatching { prepareReview(raw, session, liveQuad) }
                        }
                        raw.delete()
                        prepared.onSuccess { review = it }.onFailure {
                            Log.e(TAG, "Falha ao preparar a foto", it)
                            Toast.makeText(context, "Não consegui processar a foto. Tente de novo.", Toast.LENGTH_LONG).show()
                        }
                        processing = false
                    }
                },
                onDone = ::finish,
                onClose = ::leave,
            )
        }
        if (processing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = EditorAccent)
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Descartar as páginas?",
            message = "As ${pages.size} páginas escaneadas agora serão perdidas.",
            confirmLabel = "Descartar",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onCancel()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Endireita a foto e procura as bordas na imagem final, mais precisa do que a da prévia. */
private fun prepareReview(raw: File, session: File, liveQuad: FloatArray?): Review {
    val file = File(session, "pagina_${System.nanoTime()}.jpg")
    val size = ImageFiles.writeNormalizedJpeg({ raw.inputStream() }, file, PageRenderer.MAX_SIDE)
    val bitmap = PageRenderer.decode(file, 1800)
    // A prévia é sempre em pé: as bordas dela só valem se a foto também saiu em pé.
    val fallback = liveQuad?.takeIf { size.height >= size.width }?.let(Quad::of)
    val quad = runCatching { PageRenderer.detectQuad(bitmap) }.getOrNull() ?: fallback
    return Review(file, bitmap.asImageBitmap(), quad)
}

/** A tela fica em pé enquanto a câmera está aberta. A foto ainda sai certa com o celular deitado. */
@Composable
private fun LockPortrait() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity.requestedOrientation = previous }
    }
}

/** Desenho do documento detectado, deslizando suave entre os quadros da análise. */
@Stable
private class QuadOverlay {
    @Volatile
    private var target: FloatArray? = null

    @Volatile
    private var lastSeen = 0L

    var shape by mutableStateOf<FloatArray?>(null)
        private set
    var alpha by mutableFloatStateOf(0f)
        private set

    /** Último documento visto, para quem tirar a foto naquele instante. */
    val latest: FloatArray? get() = target

    /** Roda na thread de análise. Um quadro sem detecção não apaga o desenho na hora. */
    fun update(quad: FloatArray?, now: Long) {
        if (quad != null) {
            target = quad
            lastSeen = now
        } else if (now - lastSeen > HOLD_MILLIS) {
            target = null
        }
    }

    /** Roda a cada quadro de tela. */
    fun step() {
        val goal = target
        val current = shape
        if (goal != null) {
            shape = if (current == null) goal.copyOf() else FloatArray(8) { current[it] + (goal[it] - current[it]) * 0.3f }
            if (alpha < 1f) alpha = (alpha + 0.12f).coerceAtMost(1f)
        } else if (alpha > 0f) {
            alpha = (alpha - 0.12f).coerceAtLeast(0f)
            if (alpha == 0f) shape = null
        }
    }

    private companion object {
        const val HOLD_MILLIS = 350L
    }
}

@Composable
private fun CameraContent(
    session: File,
    tracker: AutoCaptureTracker,
    pageCount: Int,
    autoCapture: Boolean,
    onAutoCaptureChange: (Boolean) -> Unit,
    onPhoto: (File, FloatArray?) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val currentAuto by rememberUpdatedState(autoCapture)
    val currentOnPhoto by rememberUpdatedState(onPhoto)

    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val overlay = remember { QuadOverlay() }

    val previewView = remember {
        PreviewView(context).apply {
            // Mostra o quadro inteiro, sem cortar: o que aparece na tela é exatamente o que vai na foto.
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setResolutionSelector(selector(Size(4000, 3000)))
            .build()
    }

    fun takePhoto() {
        if (capturing) return
        capturing = true
        progress = 0f
        val quad = overlay.latest
        tracker.markCaptured(quad)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val raw = File(session, "foto_${System.nanoTime()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    currentOnPhoto(raw, quad)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Falha ao tirar a foto", exception)
                    capturing = false
                    Toast.makeText(context, "Não consegui tirar a foto. Tente de novo.", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().setResolutionSelector(selector(Size(1440, 1080))).build()
                preview.surfaceProvider = previewView.surfaceProvider
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector(Size(640, 480)))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(
                    analysisExecutor,
                    QuadAnalyzer { quad ->
                        val now = SystemClock.elapsedRealtime()
                        overlay.update(quad, now)
                        if (currentAuto) {
                            val status = tracker.onFrame(quad, now)
                            mainExecutor.execute {
                                if (!capturing) progress = status.progress
                                if (status.fire) takePhoto()
                            }
                        } else {
                            mainExecutor.execute { progress = 0f }
                        }
                    },
                )
                // Os três usos enxergam o mesmo recorte do sensor. Sem isso, as bordas da análise não bateriam com a foto.
                val group = UseCaseGroup.Builder()
                    .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).build())
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .addUseCase(imageCapture)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível abrir a câmera", e)
                cameraFailed = true
            }
        }, mainExecutor)
        onDispose {
            runCatching { future.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    // Só vale com o celular claramente na horizontal. De cabeça para baixo é quase sempre o aparelho
    // inclinado sobre a mesa, e virar a foto nesse caso estragaria mais do que ajudaria.
    DisposableEffect(imageCapture) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 60..120 -> Surface.ROTATION_270
                    in 240..300 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(overlay) {
        while (isActive) {
            androidx.compose.runtime.withFrameMillis { overlay.step() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Fechar", tint = Color.White) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CaptureModeSwitch(autoCapture, onAutoCaptureChange)
            }
            val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
            IconButton(
                enabled = hasFlash,
                onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                },
            ) {
                Icon(
                    if (torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    if (torch) "Desligar lanterna" else "Ligar lanterna",
                    tint = if (hasFlash) Color.White else Color.White.copy(alpha = 0.3f),
                )
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            val unit = min(viewW / 3f, viewH / 4f)
            val frameW = unit * 3f
            val frameH = unit * 4f
            val left = (viewW - frameW) / 2f
            val top = (viewH - frameH) / 2f

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val point = previewView.meteringPointFactory.createPoint(tap.x, tap.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                            focusPoint = tap
                        }
                    },
            ) {
                val shape = overlay.shape
                if (shape != null && overlay.alpha > 0f) {
                    val path = Path().apply {
                        moveTo(left + shape[0] * frameW, top + shape[1] * frameH)
                        for (i in 1 until 4) lineTo(left + shape[i * 2] * frameW, top + shape[i * 2 + 1] * frameH)
                        close()
                    }
                    drawPath(path, EditorAccent.copy(alpha = 0.22f * overlay.alpha))
                    drawPath(path, EditorAccent.copy(alpha = overlay.alpha), style = Stroke(3.dp.toPx()))
                }
                focusPoint?.let { point ->
                    drawCircle(Color.White, 30.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                }
            }

            if (focusPoint != null) {
                LaunchedEffect(focusPoint) {
                    kotlinx.coroutines.delay(900)
                    focusPoint = null
                }
            }

            val hint = when {
                cameraFailed -> "Não consegui abrir a câmera."
                !autoCapture || capturing -> null
                overlay.shape != null -> "Segure firme…"
                else -> "Aponte para o documento"
            }
            if (hint != null) {
                Text(
                    hint,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            ShutterButton(
                progress = progress,
                enabled = camera != null && !capturing,
                onClick = ::takePhoto,
            )
            if (pageCount > 0) {
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color(0xFF00382D)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Concluir ($pageCount)", maxLines = 1)
                }
            }
        }
    }
}

private fun selector(target: Size): ResolutionSelector =
    ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

@Composable
private fun CaptureModeSwitch(auto: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        listOf(true to "Automática", false to "Manual").forEach { (value, label) ->
            val selected = auto == value
            Text(
                label,
                color = if (selected) Color(0xFF00382D) else Color.White,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) EditorAccent else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** O anel se completa enquanto o documento fica parado: é a contagem da foto automática. */
@Composable
private fun ShutterButton(progress: Float, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "Tirar foto" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = 4.dp.toPx()
            drawCircle(Color.White, size.minDimension / 2f - ring / 2f, style = Stroke(ring))
            if (progress > 0f) {
                drawArc(
                    EditorAccent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(ring / 2f, ring / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - ring, size.height - ring),
                    style = Stroke(ring, cap = StrokeCap.Round),
                )
            }
            drawCircle(if (enabled) Color.White else Color.White.copy(alpha = 0.4f), size.minDimension / 2f - 9.dp.toPx())
        }
    }
}

@Composable
private fun ReviewContent(
    review: Review,
    pageNumber: Int,
    singleShot: Boolean,
    onRetake: () -> Unit,
    onAccept: (Quad?, Boolean) -> Unit,
) {
    var corners by remember(review) { mutableStateOf((review.quad ?: Quad.FULL).toOffsets()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(EditorBackground),
    ) {
        Text(
            if (singleShot) "Ajustar bordas" else "Ajustar bordas · página $pageNumber",
            color = EditorOnSurface,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
        QuadEditor(
            review.image,
            corners,
            { corners = it },
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(EditorSurface)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRetake) {
                    Icon(Icons.Outlined.Replay, null, tint = EditorOnSurface, modifier = Modifier.size(18.dp))
                    Text(" Refazer", color = EditorOnSurface)
                }
                Box(Modifier.weight(1f))
                if (review.quad != null) {
                    TextButton(onClick = { corners = review.quad.toOffsets() }) {
                        Icon(Icons.Outlined.AutoFixHigh, null, tint = EditorAccent, modifier = Modifier.size(18.dp))
                        Text(" Auto", color = EditorAccent)
                    }
                }
                TextButton(onClick = { corners = Quad.FULL.toOffsets() }) {
                    Icon(Icons.Outlined.CropFree, null, tint = EditorOnSurface, modifier = Modifier.size(18.dp))
                    Text(" Tudo", color = EditorOnSurface)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!singleShot) {
                    FilledTonalButton(
                        onClick = { onAccept(corners.toQuadOrNull(), false) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.AddAPhoto, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Mais uma página", maxLines = 1)
                    }
                }
                Button(
                    onClick = { onAccept(corners.toQuadOrNull(), true) },
                    colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color(0xFF00382D)),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (singleShot) "Usar esta foto" else "Concluir", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun NoPermissionContent(onOpenSettings: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(EditorBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.NoPhotography, null, tint = EditorMuted, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Para escanear, o app precisa da câmera",
            color = EditorOnSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color(0xFF00382D)),
        ) { Text("Abrir permissões") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClose) { Text("Voltar", color = EditorOnSurface) }
    }
}
