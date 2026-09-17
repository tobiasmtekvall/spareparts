package se.spareparts.inventory.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import se.spareparts.inventory.AppContainer
import se.spareparts.inventory.ui.components.EmptyState
import se.spareparts.inventory.ui.components.PartRow
import se.spareparts.inventory.ui.theme.AppIcons
import se.spareparts.inventory.ui.theme.CodeStyle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScannerScreen(
    c: AppContainer,
    banner: @Composable () -> Unit,
    openPart: (String) -> Unit,
    searchFor: (String) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it; askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) launcher.launch(Manifest.permission.CAMERA)
    }
    // Permission may be granted from system settings while we are in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val vm: ScannerViewModel = viewModel(factory = viewModelFactory { initializer { ScannerViewModel(c.repository) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val currentOpen by rememberUpdatedState(openPart)
    var manualOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { ev ->
            when (ev) {
                ScanEvent.Hit -> hapticHit(view)
                is ScanEvent.Open -> currentOpen(ev.pn)
            }
        }
    }
    // Coming back from a part: resume scanning.
    DisposableEffect(Unit) {
        if (vm.state.value is ScanState.Looking || vm.state.value is ScanState.Scanning) vm.resume()
        onDispose { }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraLayer(vm)
        } else {
            val activity = context as? Activity
            val permanentlyDenied = askedOnce && activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                EmptyState(
                    icon = AppIcons.QrScan,
                    title = "Camera access needed",
                    body = "Spare Parts uses the camera to read QR codes, barcodes and part labels. " +
                        "Images never leave the phone.",
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (permanentlyDenied) {
                            Button(onClick = { openAppSettings(context) }) { Text("Open app settings") }
                        } else {
                            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                        }
                        OutlinedButton(onClick = { manualOpen = true }) { Text("Enter a code instead") }
                    }
                }
            }
        }

        // Top: banner + title
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            banner()
        }

        if (granted) {
            ScannerOverlay(
                state = state,
                onReadLabel = { vm.startLabelRead() },
                onManual = { manualOpen = true },
            )
        }
    }

    when (val s = state) {
        is ScanState.Choose -> PickerSheet(s, onPick = { vm.dismiss(); openPart(it) }, onDismiss = vm::dismiss)
        is ScanState.NoMatch -> NoMatchDialog(
            s,
            onSearch = { q -> vm.dismiss(); searchFor(q) },
            onDismiss = vm::dismiss,
        )
        else -> Unit
    }

    if (manualOpen) {
        ManualEntryDialog(onDismiss = { manualOpen = false }, onSubmit = { manualOpen = false; vm.manual(it) })
    }
}

@Composable
private fun CameraLayer(vm: ScannerViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analyzer = remember { ScanAnalyzer(vm) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(analyzer, lifecycleOwner) {
        try {
            val provider = cameraProvider(context)
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analyzer.executor, analyzer) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            camera?.cameraControl?.enableTorch(torch)
        } catch (e: Exception) {
            cameraError = e.message ?: "Camera unavailable"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analyzer.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    cameraError?.let {
        Text(it, color = Color.White, modifier = Modifier.padding(24.dp).fillMaxWidth(), textAlign = TextAlign.Center)
    }

    // Torch button (top-right, below the banner area)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp)) {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            FilledIconButton(
                onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (torch) MaterialTheme.colorScheme.secondary else Color.Black.copy(alpha = 0.45f),
                    contentColor = if (torch) MaterialTheme.colorScheme.onSecondary else Color.White,
                ),
            ) {
                Icon(if (torch) AppIcons.FlashOn else AppIcons.FlashOff, if (torch) "Torch off" else "Torch on")
            }
        }
    }

    // Kick off OCR on the next frame when requested.
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        val s = state
        if (s is ScanState.Looking && s.source == ScanSource.LABEL) analyzer.requestOcr()
    }
}

@Composable
private fun ScannerOverlay(state: ScanState, onReadLabel: () -> Unit, onManual: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val sweep by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "sweep",
    )
    val accent = MaterialTheme.colorScheme.primary
    val scanning = state == ScanState.Scanning

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * 0.78f
        val h = w * 0.72f
        val left = (size.width - w) / 2
        val top = size.height * 0.40f - h / 2
        val rect = Rect(left, top, left + w, top + h)
        val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(28.dp.toPx()))) }
        clipPath(path, clipOp = ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = 0.45f))
        }
        drawRoundRect(
            color = if (scanning) accent else Color.White,
            topLeft = rect.topLeft, size = rect.size,
            cornerRadius = CornerRadius(28.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
        if (scanning) {
            val y = top + 16.dp.toPx() + (h - 32.dp.toPx()) * sweep
            drawLine(accent.copy(alpha = 0.8f), Offset(left + 20.dp.toPx(), y), Offset(left + w - 20.dp.toPx(), y),
                strokeWidth = 2.dp.toPx())
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 20.dp),
        verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
        val hint = when (state) {
            is ScanState.Looking -> if (state.source == ScanSource.LABEL) "Reading label…" else "Looking up…"
            else -> "Point at a QR code or barcode"
        }
        Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = RoundedCornerShape(50)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state is ScanState.Looking) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(hint, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FilledIconButton(
                    onClick = onManual,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
                ) { Icon(Icons.Filled.Edit, "Type a code") }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LargeFloatingActionButton(
                    onClick = { if (scanning) onReadLabel() },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Icon(AppIcons.TextScan, "Read label", Modifier.size(36.dp)) }
                Spacer(Modifier.height(6.dp))
                Text("Read label", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(s: ScanState.Choose, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Text("${s.parts.size} matching parts", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp))
        Text(s.text.lines().first().take(80), style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(s.parts, key = { it.pn }) { p -> PartRow(p, onClick = { onPick(p.pn) }) }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun NoMatchDialog(s: ScanState.NoMatch, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    val query = remember(s.text) { suggestQuery(s.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Search, null) },
        title = { Text(if (s.text.isBlank()) "Nothing read" else "No match") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.text.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                        Text(s.text.take(400), style = CodeStyle, modifier = Modifier.padding(10.dp),
                            maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
                s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (s.text.isNotBlank()) FilledTonalButton(onClick = { onSearch(query) }) { Text("Search manually") }
            else TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = { if (s.text.isNotBlank()) TextButton(onClick = onDismiss) { Text("Scan again") } },
    )
}

@Composable
private fun ManualEntryDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter code") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("Part no, model or barcode") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(text) }),
            )
        },
        confirmButton = { Button(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Look up") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks the most code-like token of a scan to prefill the search box. */
internal fun suggestQuery(text: String): String {
    val tokens = text.split(Regex("\\s+")).map { it.trim(',', ';', ':', '.') }.filter { it.length >= 3 }
    return tokens.filter { t -> t.any(Char::isDigit) }.maxByOrNull { it.length }
        ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40).orEmpty()
}

private suspend fun cameraProvider(ctx: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e.cause ?: e)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

private fun hasCameraPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun hapticHit(view: View) {
    val constant = if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
    else HapticFeedbackConstants.LONG_PRESS
    view.performHapticFeedback(constant)
}

/** Runs ML Kit on camera frames: barcodes continuously, OCR on request. */
private class ScanAnalyzer(private val vm: ScannerViewModel) : ImageAnalysis.Analyzer {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ocrRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val barcodes: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
        )
    }
    private val text: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun requestOcr() = ocrRequested.set(true)

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || closed.get()) {
            proxy.close(); return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        if (ocrRequested.getAndSet(false)) {
            text.process(image)
                .addOnSuccessListener { vm.onLabelText(it.text) }
                .addOnFailureListener { vm.onLabelFailed(it.message ?: "Text recognition failed") }
                .addOnCompleteListener { proxy.close() }
            return
        }
        if (vm.paused) {
            proxy.close(); return
        }
        barcodes.process(image)
            .addOnSuccessListener { list ->
                list.firstNotNullOfOrNull { b -> b.rawValue?.takeIf { it.isNotBlank() } ?: b.displayValue }
                    ?.let(vm::onBarcode)
            }
            .addOnCompleteListener { proxy.close() }
    }

    fun close() {
        if (closed.getAndSet(true)) return
        executor.shutdown()
        runCatching { barcodes.close() }
        runCatching { text.close() }
    }
}
