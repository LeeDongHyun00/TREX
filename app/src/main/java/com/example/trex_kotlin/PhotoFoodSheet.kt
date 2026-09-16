package com.example.trex_kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.TrexText as Text
import com.example.trex_kotlin.food.FoodDetectionResult
import com.example.trex_kotlin.food.FoodDetector
import com.example.trex_kotlin.food.decodeScaledBitmap
import com.example.trex_kotlin.food.scaledToMax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

// ============================================================= 사진 식단 기록

private enum class PhotoStep { Pick, Camera, Analyzing, Result, Failed }

/** 인식 결과 한 줄. 수량은 직접 기록 시트와 같은 qty 스테퍼로 조절한다. nutrition 이 없으면 기록에서 제외한다. */
private data class RecognizedItem(val name: String, val nutrition: Nutrition?, val confidence: Float, val qty: Int = 1)

private fun List<RecognizedItem>.toEntries(): List<FoodEntry> =
    mapNotNull { item -> item.nutrition?.let { FoodEntry(item.name, it, item.qty) } }

/**
 * 사진 식단 기록 시트: 촬영/갤러리 → 온디바이스 YOLO 분석 → 결과 확인(끼니·수량) → 현재 끼니에 저장.
 * 사진은 기기 밖으로 나가지 않는다. 분석이 안 됐으면 결과를 만들지 않는다 — 고정 음식을 결과처럼 저장하지 않는다.
 */
@Composable
internal fun PhotoFoodSheet(app: AppViewModel, onClose: () -> Unit) {
    var slot by remember { mutableStateOf(currentMealId()) }
    var manual by remember { mutableStateOf(false) }
    if (manual) {
        ManualSheet(app, slot, onClose)
        return
    }
    val c = Trex.c
    val context = LocalContext.current
    var step by remember { mutableStateOf(PhotoStep.Pick) }
    var photos by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var pickedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var analysisRequest by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failure by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<RecognizedItem>>(emptyList()) }

    fun analyze(bitmaps: List<Bitmap>, uris: List<Uri>) {
        photos = bitmaps
        pickedUris = uris
        progress = 0f
        analysisRequest++
        step = PhotoStep.Analyzing
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5),
    ) { uris -> if (uris.isNotEmpty()) analyze(emptyList(), uris) }

    // analysisRequest 를 키로 써서, 아래에서 photos 를 갱신해도 효과가 재시작되지 않게 한다.
    LaunchedEffect(step, analysisRequest) {
        if (step != PhotoStep.Analyzing) return@LaunchedEffect
        val sources = photos
        val uris = pickedUris
        if (sources.isEmpty() && uris.isEmpty()) return@LaunchedEffect
        // 추론은 백그라운드에서, 메인에서는 진행률 연출만 갱신한다.
        val job = async(Dispatchers.Default) {
            val bitmaps = sources.ifEmpty { uris.mapNotNull { decodeScaledBitmap(context, it) } }
            FoodDetector.detect(context, bitmaps) to bitmaps
        }
        while (!job.isCompleted) {
            delay(200)
            progress = (progress + 0.05f).coerceAtMost(0.9f)
        }
        val (result, bitmaps) = job.await()
        photos = bitmaps
        pickedUris = emptyList()
        progress = 1f
        delay(200)
        when (result) {
            is FoodDetectionResult.Success -> if (result.foods.isEmpty()) {
                failure = "사진에서 음식을 찾지 못했어요. 음식이 잘 보이게 다시 찍어 주세요."
                step = PhotoStep.Failed
            } else {
                items = result.foods.map { RecognizedItem(it.name, foodDatabase[it.name], it.confidence) }
                step = PhotoStep.Result
            }
            FoodDetectionResult.ModelMissing -> {
                failure = "음식 인식 모델이 앱에 설치되어 있지 않아요. 직접 입력으로 기록해 주세요."
                step = PhotoStep.Failed
            }
            FoodDetectionResult.Error -> {
                failure = "분석 중 문제가 생겼어요. 다시 시도하거나 직접 입력해 주세요."
                step = PhotoStep.Failed
            }
        }
    }

    val title = when (step) {
        PhotoStep.Pick -> "어떻게 기록할까룡?"
        PhotoStep.Camera -> "음식이 잘 보이게 찍어 주세룡"
        PhotoStep.Analyzing -> "사진을 살펴보고 있어룡"
        PhotoStep.Result -> "인식한 음식을 확인해 주세룡"
        PhotoStep.Failed -> "음식을 찾지 못했어룡"
    }

    SheetHost(onDismiss = onClose) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            SheetHandle()
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("사진 식단 기록")
                    Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                }
                SheetClose(onClose)
            }
            Box(Modifier.weight(1f)) {
                when (step) {
                    PhotoStep.Pick -> PickStep(
                        onCamera = { step = PhotoStep.Camera },
                        onGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onManual = { manual = true },
                    )
                    PhotoStep.Camera -> CameraStep(
                        onCapture = { analyze(listOf(it), emptyList()) },
                        onBack = { step = PhotoStep.Pick },
                        onManual = { manual = true },
                    )
                    PhotoStep.Analyzing -> AnalyzingStep(photo = photos.firstOrNull(), progress = progress)
                    PhotoStep.Result -> ResultStep(
                        photo = photos.firstOrNull(),
                        slot = slot,
                        items = items,
                        onSlot = { slot = it },
                        onQty = { index, delta ->
                            items = items.mapIndexedNotNull { i, item ->
                                if (i != index) item else (item.qty + delta).let { q -> if (q <= 0) null else item.copy(qty = q) }
                            }
                        },
                        onRetake = { photos = emptyList(); step = PhotoStep.Camera },
                        onSave = {
                            app.appendFoods(0, slot, items.toEntries())
                            onClose()
                        },
                        onManual = {
                            // 인식된 것은 먼저 담고, 빠진 음식은 직접 기록 시트에서 이어서 추가한다.
                            val entries = items.toEntries()
                            if (entries.isNotEmpty()) app.appendFoods(0, slot, entries)
                            manual = true
                        },
                    )
                    PhotoStep.Failed -> FailedStep(
                        photo = photos.firstOrNull(),
                        message = failure,
                        onRetake = { photos = emptyList(); step = PhotoStep.Camera },
                        onManual = { manual = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickStep(onCamera: () -> Unit, onGallery: () -> Unit, onManual: () -> Unit) {
    val c = Trex.c
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OptionCard("사진 촬영", "접시가 다 보이게 바로 촬영", Icons.Rounded.PhotoCamera, primary = true, onClick = onCamera)
        OptionCard("갤러리에서 선택", "최대 5장까지 한 번에 분석", Icons.Rounded.Image, primary = false, onClick = onGallery)
        WashBanner("사진은 기기 안에서만 분석되고 어디에도 전송되지 않아요.", Icons.Rounded.Info)
        Text(
            "수동으로 입력하기", color = c.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onManual).padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun OptionCard(title: String, subtitle: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val c = Trex.c
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (primary) c.primary else c.surface,
        contentColor = if (primary) Color.White else c.text,
        border = if (primary) null else BorderStroke(1.dp, c.line),
        shadowElevation = if (primary) 2.dp else 0.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(if (primary) Color.White.copy(alpha = 0.18f) else c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (primary) Color.White else c.primaryText, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 11.5.sp, color = if (primary) Color.White.copy(alpha = 0.8f) else c.text3, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun CameraStep(onCapture: (Bitmap) -> Unit, onBack: () -> Unit, onManual: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var permissionAsked by remember { mutableStateOf(hasPermission) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionAsked = true
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraReleased by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            cameraReleased = true
            cameraProvider?.unbindAll()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val view = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            // 시트가 먼저 닫힌 뒤 리스너가 실행되면 바인딩하지 않는다(카메라 점유 누수 방지).
                            if (cameraReleased) return@addListener
                            cameraProvider = provider
                            val preview = Preview.Builder().build()
                            preview.setSurfaceProvider(view.surfaceProvider)
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }, ContextCompat.getMainExecutor(ctx))
                        view
                    },
                )
            } else if (permissionAsked) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("카메라 권한이 필요해요", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "사진은 기기 안에서만 분석되고 외부로 전송되지 않아요.",
                        color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    GhostButton("권한 다시 요청", { permissionLauncher.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp).fillMaxWidth())
                    GhostButton("직접 입력하기", onManual, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = Icons.Rounded.Edit)
                }
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("뒤로", onBack, Modifier.weight(1f), height = 52.dp)
            Cta(
                text = "촬영",
                icon = Icons.Rounded.PhotoCamera,
                height = 52.dp,
                modifier = Modifier.weight(2f),
                enabled = hasPermission && !capturing,
                onClick = {
                    if (!capturing) {
                        capturing = true
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    // 원본(수천만 화소)을 그대로 들고 있으면 저사양 기기에서 OOM 위험이 있어 추론·표시용 크기로 줄인다.
                                    val bitmap = image.use { proxy ->
                                        val raw = proxy.toBitmap()
                                        val degrees = proxy.imageInfo.rotationDegrees
                                        val rotated = if (degrees == 0) {
                                            raw
                                        } else {
                                            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                                        }
                                        rotated.scaledToMax(1280)
                                    }
                                    capturing = false
                                    onCapture(bitmap)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.w("PhotoFoodSheet", "촬영 실패", exception)
                                    capturing = false
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun AnalyzingStep(photo: Bitmap?, progress: Float) {
    val c = Trex.c
    // 분석은 전부 온디바이스라 '업로드' 문구를 쓰지 않는다.
    val status = when {
        progress < 0.3f -> "사진 준비 중"
        progress < 0.7f -> "음식 인식 중"
        else -> "영양 정보 계산 중"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PhotoPreview(photo, Modifier.fillMaxWidth().height(240.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.padding(top = 22.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)),
            color = c.primary,
            trackColor = c.track,
        )
        Text(status, color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Text("기기 안에서 분석 중이라 네트워크가 필요 없어요.", color = c.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ResultStep(
    photo: Bitmap?,
    slot: String,
    items: List<RecognizedItem>,
    onSlot: (String) -> Unit,
    onQty: (index: Int, delta: Int) -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit,
    onManual: () -> Unit,
) {
    val c = Trex.c
    val slotIndex = mealMetas.indexOfFirst { it.id == slot }.coerceAtLeast(0)
    val entries = items.toEntries()
    val total = entries.totalNutrition()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PhotoPreview(photo, Modifier.fillMaxWidth().height(150.dp))
            SegmentedTabs(
                options = mealMetas.map { it.label },
                selected = slotIndex,
                onSelect = { onSlot(mealMetas[it].id) },
                height = 38.dp,
                filled = true,
            )
            DCard(radius = 22.dp) {
                Column {
                    items.forEachIndexed { i, item ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("확신 ${(item.confidence * 100).toInt()}%", color = c.text3, fontSize = 10.5.sp, modifier = Modifier.padding(start = 6.dp))
                                }
                                val n = item.nutrition
                                Text(
                                    if (n == null) {
                                        "영양 정보가 없어 기록에서 제외돼요 · 직접 입력으로 추가해 주세요"
                                    } else {
                                        "${n.kcal * item.qty} kcal · 탄 ${(n.carb * item.qty).toInt()} · 단 ${(n.protein * item.qty).toInt()} · 지 ${(n.fat * item.qty).toInt()}"
                                    },
                                    color = if (n == null) c.warn else c.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            StepperControl(
                                valueLabel = "${item.qty}",
                                onDec = { onQty(i, -1) },
                                onInc = { onQty(i, +1) },
                                decIcon = if (item.qty > 1) Icons.Rounded.Remove else Icons.Rounded.Delete,
                                valueMinWidth = 24.dp,
                            )
                        }
                    }
                }
            }
            Text("합계 ${total.kcal} kcal · 탄 ${total.carb.toInt()} · 단 ${total.protein.toInt()} · 지 ${total.fat.toInt()}", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("잘못 인식된 음식은 빼고, 빠진 음식은 직접 추가에서 더해 주세요. 수량은 1인분 기준이에요.", color = c.text3, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "다시 촬영", color = c.text3, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRetake).padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Text(
                "직접 추가", color = c.text3, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onManual).padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Cta(
                text = "${entries.sumOf { it.qty }}개 기록하기",
                icon = Icons.Rounded.Check,
                onClick = onSave,
                enabled = entries.isNotEmpty(),
                height = 52.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FailedStep(photo: Bitmap?, message: String, onRetake: () -> Unit, onManual: () -> Unit) {
    val c = Trex.c
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        PhotoPreview(photo, Modifier.fillMaxWidth().height(220.dp), dim = true)
        Text(
            message, color = c.text2, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
        Spacer(Modifier.weight(1f))
        GhostButton("다시 촬영하기", onRetake, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh)
        Cta(text = "직접 입력하기", onClick = onManual, icon = Icons.Rounded.Edit, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
    }
}

@Composable
private fun PhotoPreview(photo: Bitmap?, modifier: Modifier, dim: Boolean = false) {
    val c = Trex.c
    Box(modifier.clip(RoundedCornerShape(22.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
        if (photo != null) {
            androidx.compose.foundation.Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Rounded.RestaurantMenu, contentDescription = null, tint = c.text3, modifier = Modifier.size(36.dp))
        }
        if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}
