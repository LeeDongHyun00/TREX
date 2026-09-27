package com.example.trex_kotlin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapHoriz
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trex_kotlin.TrexText as Text
import com.example.trex_kotlin.food.FoodDetectionResult
import com.example.trex_kotlin.food.FoodDetector
import com.example.trex_kotlin.food.FoodMemoryStore
import com.example.trex_kotlin.food.FoodRegion
import com.example.trex_kotlin.food.decodeScaledBitmap
import com.example.trex_kotlin.food.rotated
import com.example.trex_kotlin.food.scaledToMax
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ============================================================= 사진 식단 기록

private enum class PhotoStep { Pick, Camera, Analyzing, Result, Failed }

/**
 * 결과·실패 화면과 라이트박스가 함께 쓰는 사진의 최대 변. 전체 화면으로 키워도 버틸 만하면서,
 * 갤러리 5장을 다 들고 있어도 메모리가 감당되는 선으로 잡았다(장당 약 4MB).
 */
private const val PREVIEW_MAX_PX = 1024

/**
 * 음식 고르기 창이 무엇을 하려는지. 인덱스 센티넬(-1) 대신 타입으로 가른다 —
 * 센티넬이면 "제목은 추가인데 동작은 바꾸기" 같은 어긋난 상태가 표현 가능해진다.
 */
private sealed interface PickTarget {
    /** 목록에 없는 음식을 새로 더한다. */
    data object Add : PickTarget

    /**
     * [index] 번째 줄을 다른 음식으로 바꾼다. 사진의 자리를 눌러 왔으면 [regionId] 가 그 자리다 —
     * 한 줄이 여러 자리(두 그릇 모두 "김치")에서 왔을 때 누른 자리만 떼어 바꾼다.
     */
    data class Replace(val index: Int, val regionId: Int? = null) : PickTarget

    /** 사진의 "?" 자리([regionId])에 이름을 붙인다. 고른 음식은 그 자리와 이어진 줄이 된다. */
    data class Name(val regionId: Int) : PickTarget
}

/**
 * 결과 한 줄이 어디서 왔는지.
 *
 * 확신도와 출처 사진을 각각 nullable 로 두면 한쪽만 채워진 상태가 만들어져 화면에서 말이
 * 엇갈린다("확신 62%"와 "직접 고름"이 같이 뜨는 식). 타입으로 묶어 그걸 막는다.
 */
private sealed interface FoodSource {
    /** 모델이 [photoIndex] 번째 사진에서 [confidence] 로 잡았다. */
    data class Detected(val confidence: Float, val photoIndex: Int) : FoodSource

    /**
     * 사용자가 직접 골랐다. 확신도도 출처 사진도 없다 —
     * 판정하지 않은 것을 판정한 것처럼 말하지 않고, 아무 사진이나 붙여 거기서 잡힌 것처럼 보이게 하지 않는다.
     */
    data object Picked : FoodSource

    /**
     * 내 음식 기억이 이 자리를 전에 사용자가 고른 음식과 매우 비슷하다고 봤다([FoodMemory.AUTO_NAME_AT] 이상).
     * 모델 판정도 이번의 직접 선택도 아니라 따로 둔다 — 화면에는 "기억한 음식" 으로 밝힌다.
     */
    data class Remembered(val similarity: Float, val photoIndex: Int) : FoodSource
}

/**
 * 사진 위 자리의 이름표. [byModel] 은 모델이 이 자리에 붙인 이름 그대로인지 — 사용자가 붙이거나 바꾼 이름과
 * 사진 위에서 같은 모양으로 그리면, 판정하지 않은 것을 판정한 것처럼 보인다(목록의 "직접 고름" 과 같은 이유).
 */
private data class RegionLabel(val name: String, val kind: LabelKind)

/** 자리 이름표가 어디서 왔는지. 사진 위에서 모양으로 구분한다. */
private enum class LabelKind { MODEL, MEMORY, USER }

/**
 * 결과 한 줄. 수량은 직접 기록 시트와 같은 qty 스테퍼로 조절한다. nutrition 이 없으면 기록에서 제외한다.
 *
 * [regionIds] 는 이 줄이 사진의 어느 자리([FoodRegion.id])에서 왔는지 — 사진 위 이름표가 이것으로 정해진다.
 * 자리의 이름표를 따로 들고 있지 않는 이유: 줄을 바꾸거나 지웠을 때 사진과 목록이 어긋나지 않게, 줄 하나만 정본으로 둔다.
 * 줄이 지워지면 그 자리는 다시 "?" 가 되어 이름을 새로 붙일 수 있다.
 */
private data class RecognizedItem(
    val name: String,
    val nutrition: Nutrition?,
    val source: FoodSource,
    val qty: Int = 1,
    val regionIds: Set<Int> = emptySet(),
)

/**
 * 같은 이름을 한 줄로 합친다. 저장 경로의 [AppViewModel.appendFoods] 와 같은 규칙이라,
 * 저장 전 화면과 저장된 기록이 어긋나지 않는다.
 *
 * 합치지 않으면 "쌀밥 1 / 쌀밥 1" 두 줄이 생겨, 한 줄을 지워 뺐다고 생각해도 다른 줄이 남는다.
 *
 * "?" 자리에 이미 있는 음식을 고르면 그 줄로 합쳐져 수량이 +1 된다. 그 줄의 출처(확신도 표시)는 앞 줄 것을 따르므로
 * 모델이 잡은 줄에 사용자가 더한 한 그릇이 섞일 수 있다 — 사진 위에서는 자리마다 [RegionLabel.byModel] 로 구분된다.
 *
 * 영양값은 없을 때만 뒤에서 채운다. 직접 등록으로 방금 적은 값이 버려지는 것을 막으면서도,
 * 이미 있는 줄의 값을 조용히 바꾸지는 않는다.
 */
private fun List<RecognizedItem>.mergedByName(): List<RecognizedItem> =
    fold(emptyList()) { acc, item ->
        val at = acc.indexOfFirst { it.name == item.name }
        if (at < 0) {
            acc + item
        } else {
            acc.mapIndexed { i, kept ->
                if (i == at) {
                    kept.copy(qty = kept.qty + item.qty, nutrition = kept.nutrition ?: item.nutrition, regionIds = kept.regionIds + item.regionIds)
                } else {
                    kept
                }
            }
        }
    }

private fun List<RecognizedItem>.toEntries(): List<FoodEntry> =
    mapNotNull { item -> item.nutrition?.let { FoodEntry(item.name, it, item.qty) } }

/**
 * 사진 식단 기록 시트: 촬영/갤러리 → 온디바이스 YOLO 분석 → 결과 확인(끼니·수량) → 현재 끼니에 저장.
 * 사진은 기기 밖으로 나가지 않고 디스크에도 쓰지 않는다. 분석이 안 됐으면 결과를 만들지 않는다 — 고정 음식을 결과처럼 저장하지 않는다.
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
    var fromGallery by remember { mutableStateOf(false) }
    var analysisRequest by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failureTitle by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<RecognizedItem>>(emptyList()) }
    // 임계 미만이라 결과에 넣지 않은 후보. "빠진 음식 추가"에서 고를 거리로만 쓴다.
    var candidates by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    // 2단계 인식이 찾은 자리. 비어 있으면 전체 사진 1회 경로라 사진 위에 아무것도 그리지 않는다.
    var regions by remember { mutableStateOf<List<FoodRegion>>(emptyList()) }
    // 비어 보이는 자리(빈 그릇·컵, FoodRegion.looksEmpty)를 사진에 그릴지. 기본은 숨기고 개수만 알린다.
    var showEmpty by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf<Bitmap?>(null) }
    // 음식 고르기 창의 대상. null 이면 닫힘.
    var pickTarget by remember { mutableStateOf<PickTarget?>(null) }

    // 모델(12MB)·인터프리터 초기화를 첫 분석이 아니라 시트를 여는 시점에 미리 해 둔다.
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { FoodDetector.warmUp(context) } }

    fun analyze(bitmaps: List<Bitmap>, uris: List<Uri>) {
        photos = bitmaps
        pickedUris = uris
        fromGallery = uris.isNotEmpty()
        progress = 0f
        analysisRequest++
        step = PhotoStep.Analyzing
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5),
    ) { uris -> if (uris.isNotEmpty()) analyze(emptyList(), uris) }
    fun openGallery() = galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun retry() {
        photos = emptyList()
        if (fromGallery) openGallery() else step = PhotoStep.Camera
    }
    val retryLabel = if (fromGallery) "다시 선택하기" else "다시 촬영하기"

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
        // 화면에는 썸네일만 필요하다 — 추론용 1280px 비트맵(장당 수 MB)을 붙들지 않고 작은 사본만 상태에 남긴다.
        photos = bitmaps.map { it.scaledToMax(PREVIEW_MAX_PX) }
        pickedUris = emptyList()
        progress = 1f
        delay(200)
        when (result) {
            // 이름이 하나도 안 붙었어도 자리를 찾았으면 결과로 간다 — "?" 를 눌러 이름을 붙이면 된다.
            is FoodDetectionResult.Success -> if (result.foods.isEmpty() && result.regions.isEmpty()) {
                failureTitle = "음식을 찾지 못했어룡"
                failure = "사진에서 음식을 찾지 못했어요. 음식이 잘 보이게 다시 찍어 주세요."
                step = PhotoStep.Failed
            } else {
                regions = result.regions
                showEmpty = false
                val detected = result.foods.map { food ->
                    RecognizedItem(
                        food.name, app.findFood(food.name), FoodSource.Detected(food.confidence, food.photoIndex),
                        regionIds = result.regions.filter { it.name == food.name }.mapTo(HashSet()) { it.id },
                    )
                }
                // 기억이 매우 비슷하다고 한 자리는 그 이름으로 줄을 만든다. 영양값을 못 찾는 이름(지운 내 음식)은 만들지 않는다.
                val remembered = result.regions.mapNotNull { region ->
                    val name = region.rememberedName ?: return@mapNotNull null
                    val nutrition = app.findFood(name) ?: return@mapNotNull null
                    RecognizedItem(
                        name, nutrition, FoodSource.Remembered(region.remembered.first().second, region.photoIndex),
                        regionIds = setOf(region.id),
                    )
                }
                // 같은 이름이면 한 줄로 합친다. 기억으로 합쳐진 한 그릇이 수량을 늘리지 않게 수량은 1 로 둔다(모델 결과와 같은 규칙).
                items = (detected + remembered).mergedByName().map { it.copy(qty = 1) }
                // 영양값을 못 찾는 이름은 골라도 기록에 못 들어가므로 후보에서 뺀다.
                candidates = result.candidates
                    .filter { app.findFood(it.name) != null }
                    .map { it.name to it.confidence }
                step = PhotoStep.Result
            }
            FoodDetectionResult.ModelMissing -> {
                failureTitle = "인식 모델이 없어룡"
                failure = "음식 인식 모델이 앱에 설치되어 있지 않아요. 직접 입력으로 기록해 주세요."
                step = PhotoStep.Failed
            }
            FoodDetectionResult.Error -> {
                failureTitle = "분석하지 못했어룡"
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
        // 원인(검출 0건·모델 부재·분석 오류)마다 다르다 — 판정하지 않은 것을 "못 찾았다"고 말하지 않는다.
        PhotoStep.Failed -> failureTitle
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
                        onGallery = { openGallery() },
                        onManual = { manual = true },
                    )
                    PhotoStep.Camera -> CameraStep(
                        onCapture = { analyze(listOf(it), emptyList()) },
                        onBack = { step = PhotoStep.Pick },
                        onManual = { manual = true },
                    )
                    PhotoStep.Analyzing -> AnalyzingStep(photos = photos, progress = progress, onZoom = { zoomed = it })
                    PhotoStep.Result -> ResultStep(
                        photos = photos,
                        regions = regions,
                        showEmpty = showEmpty,
                        onToggleEmpty = { showEmpty = !showEmpty },
                        labelOf = { region ->
                            items.firstOrNull { region.id in it.regionIds }?.let { row ->
                                RegionLabel(
                                    row.name,
                                    // 사용자가 고른 줄(Picked)은 이름이 기억과 같아도 사용자 선택이다 — 기억이 붙인 줄을 지운 뒤
                                    // "?" 에 같은 이름을 직접 고른 경우가 그렇다.
                                    when {
                                        row.name == region.name -> LabelKind.MODEL
                                        row.name == region.rememberedName && row.source != FoodSource.Picked -> LabelKind.MEMORY
                                        else -> LabelKind.USER
                                    },
                                )
                            }
                        },
                        onRegion = { region ->
                            val row = items.indexOfFirst { region.id in it.regionIds }
                            pickTarget = if (row >= 0) PickTarget.Replace(row, region.id) else PickTarget.Name(region.id)
                        },
                        onZoom = { zoomed = it },
                        slot = slot,
                        items = items,
                        retryLabel = if (fromGallery) "다시 선택" else "다시 촬영",
                        onSlot = { slot = it },
                        onQty = { index, delta ->
                            items = items.mapIndexedNotNull { i, item ->
                                if (i != index) item else (item.qty + delta).let { q -> if (q <= 0) null else item.copy(qty = q) }
                            }
                        },
                        hasApproximateNutrition = items.any { it.name in approximateNutritionNames || it.name in app.customFoods },
                        candidateCount = candidates.count { (name, _) -> items.none { it.name == name } },
                        onAdd = { pickTarget = PickTarget.Add },
                        onReplace = { index -> pickTarget = PickTarget.Replace(index) },
                        onRetry = { retry() },
                        onSave = {
                            app.appendFoods(0, slot, items.toEntries())
                            rememberUserNamed(context, photos, regions, items)
                            onClose()
                        },
                        onSaveAndManual = {
                            // 인식된 것을 이 끼니에 먼저 담고, 빠진 음식은 직접 기록 시트에서 이어서 추가한다.
                            // 직접 기록 시트가 같은 끼니를 열어 방금 담은 항목을 스테퍼로 바로 고칠 수 있다.
                            val entries = items.toEntries()
                            if (entries.isNotEmpty()) app.appendFoods(0, slot, entries)
                            rememberUserNamed(context, photos, regions, items)
                            manual = true
                        },
                    )
                    PhotoStep.Failed -> FailedStep(
                        photos = photos,
                        onZoom = { zoomed = it },
                        message = failure,
                        retryLabel = retryLabel,
                        onRetry = { retry() },
                        onManual = { manual = true },
                    )
                }
            }
        }
    }

    zoomed?.let { photo -> PhotoLightbox(photo) { zoomed = null } }

    pickTarget?.let { target ->
        // 제목과 동작을 같은 한 번의 읽기에서 뽑는다. 따로 읽으면 그 사이 목록이 바뀌었을 때
        // 제목은 "추가"인데 동작은 "바꾸기"로 가서 아무 일도 안 일어나는 상태가 된다.
        val replacing = (target as? PickTarget.Replace)?.let { items.getOrNull(it.index) }
        val naming = (target as? PickTarget.Name)?.let { t -> regions.firstOrNull { it.id == t.regionId } }
        // 사진에서 누른 자리. 그 줄에 든 자리일 때만 쓴다(그 사이 목록이 바뀌었으면 줄 전체 바꾸기로 돌아간다).
        val tapped = (target as? PickTarget.Replace)?.regionId
            ?.takeIf { id -> replacing != null && id in replacing.regionIds }
            ?.let { id -> regions.firstOrNull { it.id == id } }
        // 자리에서 온 줄·자리를 고칠 때는 그 자리에서 본 상위 후보를 먼저 보여준다(정답이 3위 안에 드는 경우가 많다, FOOD_EVAL §8).
        // 누른 자리가 있으면 그 자리, 목록에서 왔으면 그 줄의 자리 중 가장 확신한 자리의 후보를 쓴다.
        val spot = naming ?: tapped ?: replacing?.let { row -> regions.filter { it.id in row.regionIds }.maxByOrNull { it.confidence } }
        // 누른 자리가 여러 자리를 묶은 줄의 하나면, 그 자리만 떼어 새 줄로 만든다 — 두 그릇 중 하나만 틀렸을 수 있다.
        val splitting = tapped != null && replacing != null && replacing.regionIds.size > 1
        val spotRemembered = (naming ?: tapped)?.remembered.orEmpty()
            .map { it.first }
            .filter { it != replacing?.name && app.findFood(it) != null }
        // 기억 후보에 이미 든 이름은 모델 후보에서 뺀다 — 같은 음식이 두 번 뜨지 않게.
        val spotCandidates = spot?.top
            ?.filter { (name, _) -> name != replacing?.name && name !in spotRemembered && app.findFood(name) != null }
            .orEmpty()
        // 누른 자리(또는 이름 붙일 자리)를 사진에서 잘라 고르는 창 맨 위에 보여준다. 목록에서 들어온 바꾸기는 자리를 누른 게 아니라 보여주지 않는다.
        val shownSpot = naming ?: tapped
        val spotPreview = remember(shownSpot?.id, photos) { shownSpot?.let { cropSpot(photos.getOrNull(it.photoIndex), it) } }
        FoodPicker(
            app = app,
            spotPreview = spotPreview,
            title = when {
                splitting -> "이 자리의 ${replacing.name} 을(를) 바꾸기"
                replacing != null -> "${replacing.name} 을(를) 바꾸기"
                naming != null -> "이 자리의 음식 고르기"
                else -> "빠진 음식 추가"
            },
            // 이미 목록에 든 것은 빼고 넘긴다. 바꾸기는 그 줄을 다른 것으로 만드는 일이라 후보가 그대로 쓸모 있다.
            // 자리 이름 붙이기는 이미 든 음식도 그대로 보여준다 — 같은 음식 한 그릇 더(수량 +1)일 수 있다.
            candidates = if (spot != null) spotCandidates else candidates.filterNot { (name, _) -> items.any { it.name == name } },
            candidatesTitle = if (spot != null) "이 자리에서 본 후보" else null,
            // 누른 자리와 비슷하다고 기억이 말한 음식. 모델 후보보다 위에 둔다 — 그 사람이 실제로 먹은 음식이다.
            rememberedCandidates = spotRemembered,
            onPick = { name, nutrition ->
                val picked = RecognizedItem(name, nutrition, FoodSource.Picked, regionIds = naming?.let { setOf(it.id) }.orEmpty())
                items = if (replacing == null) {
                    (items + picked).mergedByName()
                } else if (splitting) {
                    val id = tapped.id
                    (items.map { if (it === replacing) it.copy(regionIds = it.regionIds - id) else it } + picked.copy(regionIds = setOf(id)))
                        .mergedByName()
                } else {
                    // 바꿔 넣은 이름은 모델이 판정한 게 아니다. 판정 흔적을 떼어 낸다. 자리는 그대로 따라간다.
                    items.map { if (it === replacing) picked.copy(qty = it.qty, regionIds = it.regionIds) else it }.mergedByName()
                }
                pickTarget = null
            },
            onDismiss = { pickTarget = null },
        )
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
    // 촬영 결과(수천만 화소 JPEG)의 디코딩·축소·회전은 메인 스레드에서 하지 않는다.
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraReleased by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) } // 카메라를 열지 못함 — 촬영 불가
    var captureError by remember { mutableStateOf<String?>(null) } // 한 번의 촬영 실패 — 재시도 가능
    DisposableEffect(Unit) {
        onDispose {
            cameraReleased = true
            cameraProvider?.unbindAll()
            captureExecutor.shutdown()
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
                            // 시트가 먼저 닫힌 뒤 리스너가 실행되면 바인딩하지 않는다(카메라 점유 누수 방지).
                            if (cameraReleased) return@addListener
                            try {
                                val provider = providerFuture.get()
                                cameraProvider = provider
                                val preview = Preview.Builder().build()
                                preview.setSurfaceProvider(view.surfaceProvider)
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                                cameraError = null
                            } catch (e: Exception) {
                                // 후면 카메라가 없거나 다른 앱이 점유 중인 경우 — 앱을 죽이지 않고 갤러리/직접 입력으로 안내한다.
                                Log.w("PhotoFoodSheet", "카메라 열기 실패", e)
                                cameraError = "카메라를 열 수 없어요. 다른 앱이 카메라를 쓰고 있지 않은지 확인하거나 갤러리에서 선택해 주세요."
                            }
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
        (cameraError ?: captureError)?.let { message ->
            Box(Modifier.padding(top = 10.dp)) { WashBanner(message, Icons.Rounded.Info, warnTone = true) }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("뒤로", onBack, Modifier.weight(1f), height = 52.dp)
            Cta(
                text = "촬영",
                icon = Icons.Rounded.PhotoCamera,
                height = 52.dp,
                modifier = Modifier.weight(2f),
                enabled = hasPermission && !capturing && cameraError == null,
                onClick = {
                    if (!capturing) {
                        capturing = true
                        captureError = null
                        val mainExecutor = ContextCompat.getMainExecutor(context)
                        imageCapture.takePicture(
                            captureExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    // 축소를 먼저 하고 회전한다 — 원본 크기 복사본을 하나 더 만들지 않기 위해서다(저사양 기기 OOM 방지).
                                    val bitmap = image.use { proxy ->
                                        proxy.toBitmap().scaledToMax(1280).rotated(proxy.imageInfo.rotationDegrees)
                                    }
                                    mainExecutor.execute {
                                        capturing = false
                                        onCapture(bitmap)
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.w("PhotoFoodSheet", "촬영 실패", exception)
                                    mainExecutor.execute {
                                        capturing = false
                                        captureError = "촬영에 실패했어요. 다시 시도해 주세요."
                                    }
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
private fun AnalyzingStep(photos: List<Bitmap>, progress: Float, onZoom: (Bitmap) -> Unit) {
    val c = Trex.c
    // 분석은 전부 온디바이스라 '업로드' 문구를 쓰지 않는다.
    val status = when {
        progress < 0.3f -> "사진 준비 중"
        progress < 0.7f -> "음식 인식 중"
        else -> "영양 정보 계산 중"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PhotoStrip(photos, Modifier.fillMaxWidth().height(240.dp), onZoom = onZoom)
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
    photos: List<Bitmap>,
    /** 2단계 인식이 찾은 자리. 비어 있으면 예전처럼 사진 줄만 보여준다(전체 사진 1회 경로). */
    regions: List<FoodRegion>,
    showEmpty: Boolean,
    onToggleEmpty: () -> Unit,
    /** 자리의 이름표 — 그 자리와 이어진 줄의 이름. null 이면 "?" 다. */
    labelOf: (FoodRegion) -> RegionLabel?,
    onRegion: (FoodRegion) -> Unit,
    onZoom: (Bitmap) -> Unit,
    slot: String,
    items: List<RecognizedItem>,
    retryLabel: String,
    hasApproximateNutrition: Boolean,
    /** 임계 미만이라 결과에서 뺀 후보의 개수. 0 이면 알리지 않는다. */
    candidateCount: Int,
    onSlot: (String) -> Unit,
    onQty: (index: Int, delta: Int) -> Unit,
    onAdd: () -> Unit,
    onReplace: (index: Int) -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onSaveAndManual: () -> Unit,
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
            if (regions.isEmpty()) {
                PhotoStrip(photos, Modifier.fillMaxWidth().height(150.dp), onZoom = onZoom)
            } else {
                // 이름표가 있는 자리는 늘 그린다. 이름 없고 비어 보이는 자리만 숨길 수 있다.
                val hidden = regions.filter { labelOf(it) == null && it.looksEmpty }
                val drawn = if (showEmpty) regions else regions - hidden.toSet()
                RegionPhotos(photos, drawn, labelOf, onRegion, onZoom)
                val unnamed = drawn.count { labelOf(it) == null }
                // 모델이 이름을 알았는데 사용자가 줄을 지운 자리도 "?" 로 돌아가므로 "모르는" 이 아니라 "없는" 이라고 말한다.
                // "?" 는 기록에 들어가지 않는다. 음식이 아닌 자리(컵·빈 그릇)도 여기 섞이므로 지우라고 하지 않고 그냥 두면 된다고 말한다.
                Text(
                    if (unnamed > 0) {
                        "이름이 없는 자리 ${unnamed}곳 · 사진의 ? 를 눌러 알려 주세요. 음식이 아니면 그냥 두면 기록되지 않아요."
                    } else {
                        "사진의 이름표를 누르면 다른 음식으로 바꿀 수 있어요."
                    },
                    color = c.text3, fontSize = 11.5.sp, lineHeight = 17.sp,
                )
                // 숨긴 것은 숨겼다고 밝힌다 — 그중에는 단무지·생강 같은 작은 곁들이도 섞여 있다(FOOD_EVAL §8.3).
                if (hidden.isNotEmpty()) {
                    Text(
                        if (showEmpty) "빈 그릇·컵으로 보이는 자리 ${hidden.size}곳 숨기기" else "빈 그릇·컵으로 보이는 자리 ${hidden.size}곳을 숨겼어요 · 보기",
                        color = c.primaryText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onToggleEmpty)
                            .padding(vertical = 6.dp),
                    )
                }
            }
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
                            // 여러 장을 분석했을 때는 이 음식이 잡힌 사진을 번호와 함께 보여준다.
                            if (photos.size > 1) {
                                val from = when (val source = item.source) {
                                    is FoodSource.Detected -> source.photoIndex
                                    is FoodSource.Remembered -> source.photoIndex
                                    FoodSource.Picked -> null
                                }
                                if (from != null) {
                                    PhotoThumb(photos.getOrNull(from), number = from + 1, size = 44.dp)
                                } else {
                                    // 직접 고른 항목은 출처 사진이 없다. 아무 사진이나 붙이면 거기서 잡힌 것처럼 보인다.
                                    Box(
                                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, tint = c.text3, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        // 이 커밋의 핵심 동선이라 최소 터치 크기를 지킨다.
                                        .heightIn(min = 40.dp)
                                        .clickable(role = Role.Button, onClickLabel = "다른 음식으로 바꾸기") { onReplace(i) }
                                        .padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(item.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Rounded.SwapHoriz, contentDescription = "다른 음식으로 바꾸기",
                                        tint = c.text3, modifier = Modifier.size(14.dp),
                                    )
                                    // 확신도는 모델이 판정했을 때만 붙인다. 직접 고른 것에 붙이면 안 한 판정을 한 것처럼 말하게 된다.
                                    when (val source = item.source) {
                                        is FoodSource.Detected ->
                                            // 모델이 잡은 줄에 기억이 붙인 자리가 합쳐졌으면 그렇다고 밝힌다 — 확신도는 모델이 잡은 자리의 것이다.
                                            Text(
                                                "확신 ${(source.confidence * 100).toInt()}%" +
                                                    if (regions.any { it.id in item.regionIds && labelOf(it)?.kind == LabelKind.MEMORY }) " · 기억 포함" else "",
                                                color = c.text3, fontSize = 10.5.sp, modifier = Modifier.padding(start = 6.dp),
                                            )
                                        FoodSource.Picked ->
                                            Text("직접 고름", color = c.primaryText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                                        // "직접 고름" 과 구분되게 강조색을 쓰지 않는다(사진 위에서는 연두 이름표로 구분된다).
                                        is FoodSource.Remembered ->
                                            Text("기억한 음식", color = c.text2, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                                val n = item.nutrition
                                Text(
                                    if (n == null) {
                                        "영양 정보가 없어 기록에서 제외돼요 · 이름을 눌러 다른 음식으로 바꿔 주세요"
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
                    // 놓친 음식을 이 자리에서 더한다. 저장한 뒤 직접 기록 시트로 가는 길보다 짧다.
                    if (items.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = c.primaryText, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("빠진 음식 추가", color = c.text2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        // 후보가 있다는 것을 눌러 보기 전에 알린다. 있는 줄 모르면 없는 기능이다.
                        // 개수만 말하고 무슨 음식인지는 말하지 않는다 — 확실하지 않은 것을 결과처럼 읽히게 하지 않는다.
                        if (candidateCount > 0) {
                            Text(
                                "사진에서 본 후보 ${candidateCount}개", color = c.text3, fontSize = 11.sp,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
            Text("합계 ${total.kcal} kcal · 탄 ${total.carb.toInt()} · 단 ${total.protein.toInt()} · 지 ${total.fat.toInt()}", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            // 실측값(AI Hub 영양DB)과 추정값을 같은 확신으로 말하지 않는다 — 추정이 섞였을 때만 그렇다고 밝힌다.
            // 직접 등록한 음식도 사용자가 적은 값이라 실측이 아니다(hasApproximateNutrition 이 둘 다 본다).
            if (hasApproximateNutrition) {
                WashBanner("일부 항목은 아직 실측 영양값이 없어 추정치로 보여드려요. 참고용으로 봐 주세요.", Icons.Rounded.Info)
            } else {
                WashBanner("영양값은 1인분 기준이에요. 실제로 드신 양이 다르면 수량으로 조절해 주세요.", Icons.Rounded.Info)
            }
            Text("음식 이름을 누르면 다른 음식으로 바꿀 수 있어요. 잘못 인식된 건 수량을 줄여 빼 주세요.", color = c.text3, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                retryLabel, color = c.text3, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRetry).padding(horizontal = 6.dp, vertical = 8.dp),
            )
            Text(
                "기록하고 직접 추가", color = c.text3, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onSaveAndManual).padding(horizontal = 6.dp, vertical = 8.dp),
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
private fun FailedStep(photos: List<Bitmap>, message: String, retryLabel: String, onZoom: (Bitmap) -> Unit, onRetry: () -> Unit, onManual: () -> Unit) {
    val c = Trex.c
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        PhotoStrip(photos, Modifier.fillMaxWidth().height(220.dp), dim = true, onZoom = onZoom)
        Text(
            message, color = c.text2, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
        Spacer(Modifier.weight(1f))
        GhostButton(retryLabel, onRetry, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh)
        Cta(text = "직접 입력하기", onClick = onManual, icon = Icons.Rounded.Edit, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
    }
}

/** 사진이 한 장이면 크게, 여러 장이면 가로로 넘겨 보는 썸네일 줄. 갤러리 5장을 골랐는데 첫 장만 보이던 문제를 없앤다. */
@Composable
private fun PhotoStrip(photos: List<Bitmap>, modifier: Modifier, dim: Boolean = false, onZoom: (Bitmap) -> Unit = {}) {
    if (photos.size <= 1) {
        PhotoPreview(photos.firstOrNull(), modifier, dim, onZoom)
        return
    }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEachIndexed { index, photo ->
            Box(Modifier.fillMaxHeight().width(150.dp)) {
                PhotoPreview(photo, Modifier.fillMaxSize(), dim, onZoom)
                NumberBadge(index + 1, Modifier.padding(8.dp))
            }
        }
    }
}

/**
 * 2단계 인식 결과를 사진 위에 그린다. 한 장이면 크게, 여러 장이면 가로로 넘겨 본다.
 *
 * 자리마다 박스와 이름표를 단다 — 이름이 붙은 자리는 실선과 이름, 붙지 않은 자리는 점선과 "?".
 * 자리를 누르면 [onRegion](이름 바꾸기 또는 이름 붙이기), 자리 밖을 누르면 [onZoom](크게 보기).
 * 자리가 겹치면 더 작은 쪽을 고른다 — 큰 자리 안의 작은 반찬 그릇을 누를 수 있어야 한다.
 */
@Composable
private fun RegionPhotos(
    photos: List<Bitmap>,
    regions: List<FoodRegion>,
    labelOf: (FoodRegion) -> RegionLabel?,
    onRegion: (FoodRegion) -> Unit,
    onZoom: (Bitmap) -> Unit,
) {
    if (photos.size <= 1) {
        val photo = photos.firstOrNull() ?: return
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RegionPhoto(
                photo, regions, labelOf, onRegion, onZoom,
                Modifier.heightIn(max = 420.dp).aspectRatio(photo.width.toFloat() / photo.height, matchHeightConstraintsFirst = true),
            )
        }
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEachIndexed { index, photo ->
            Box {
                RegionPhoto(
                    photo, regions.filter { it.photoIndex == index }, labelOf, onRegion, onZoom,
                    Modifier.height(280.dp).aspectRatio(photo.width.toFloat() / photo.height),
                )
                NumberBadge(index + 1, Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun RegionPhoto(
    photo: Bitmap,
    regions: List<FoodRegion>,
    labelOf: (FoodRegion) -> RegionLabel?,
    onRegion: (FoodRegion) -> Unit,
    onZoom: (Bitmap) -> Unit,
    modifier: Modifier,
) {
    val c = Trex.c
    val named = c.primary
    val remembered = c.lime
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) }
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface2)
            .pointerInput(regions) {
                detectTapGestures { tap ->
                    val x = tap.x / size.width
                    val y = tap.y / size.height
                    val hit = regions
                        .filter { x in it.box.left..it.box.right && y in it.box.top..it.box.bottom }
                        .minByOrNull { it.box.area }
                    if (hit != null) onRegion(hit) else onZoom(photo)
                }
            },
    ) {
        androidx.compose.foundation.Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = "인식한 사진",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            regions.forEach { region ->
                val label = labelOf(region)
                val topLeft = Offset(region.box.left * size.width, region.box.top * size.height)
                val boxSize = Size(region.box.width * size.width, region.box.height * size.height)
                // 어두운 테두리를 먼저 깔아 밝은 식탁에서도 선이 보이게 한다.
                drawRect(Color.Black.copy(alpha = 0.35f), topLeft, boxSize, style = Stroke(stroke + 2.dp.toPx()))
                // 모델 이름 = 강조색 실선, 기억한 이름 = 연두 실선, 직접 붙인 이름 = 흰 실선, 이름 없음 = 흰 점선.
                drawRect(
                    when (label?.kind) {
                        LabelKind.MODEL -> named
                        LabelKind.MEMORY -> remembered
                        else -> Color.White
                    },
                    topLeft, boxSize,
                    style = Stroke(stroke, pathEffect = if (label != null) null else dash),
                )
            }
        }
        regions.forEach { region ->
            val label = labelOf(region)
            val left = maxWidth * region.box.left
            // 이름표는 박스 왼쪽 위에 붙는다. 사진 오른쪽 끝 박스의 긴 이름이 사진 밖으로 잘리지 않게 남은 폭 안에서 줄인다.
            val room = (maxWidth - left - 8.dp).coerceAtLeast(28.dp)
            Text(
                // 기억한 이름은 "기억" 을 붙여 모델 판정이 아님을 밝힌다.
                when (label?.kind) {
                    null -> "?"
                    LabelKind.MEMORY -> "기억 · ${label.name}"
                    else -> label.name
                },
                // 모델 이름 = 강조색 바탕, 기억한 이름 = 연두 바탕, 직접 붙인 이름 = 흰 바탕, 이름 없음 = 어두운 바탕.
                color = if (label == null || label.kind == LabelKind.MODEL) Color.White else Color.Black,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(x = left, y = maxHeight * region.box.top)
                    .padding(4.dp)
                    .widthIn(max = room)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when (label?.kind) {
                            null -> Color.Black.copy(alpha = 0.6f)
                            LabelKind.MODEL -> named
                            LabelKind.MEMORY -> remembered
                            LabelKind.USER -> Color.White
                        },
                    )
                    // 이름표가 박스 밖으로 나온 부분을 눌러도 그 자리로 간다. 없으면 옆 자리나 크게 보기로 새어 엉뚱한 음식을 바꾼다.
                    .clickable(role = Role.Button, onClickLabel = if (label == null) "이 자리 이름 붙이기" else "다른 음식으로 바꾸기") { onRegion(region) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 기록할 때, 사용자가 사진의 자리에 **직접 붙이거나 바꾼** 이름을 내 음식 기억에 남긴다.
 *
 * 모델이 붙인 이름과 기억이 자동으로 붙인 이름은 남기지 않는다 — 사용자가 확인했다고 볼 근거가 약하고,
 * 틀린 자동 이름이 다시 기억이 되면 같은 틀림이 스스로 굳는다. 기록하지 않고 닫으면 아무것도 남지 않는다.
 * 기록된 줄(영양값이 있는 줄)의 자리만 남긴다.
 */
private fun rememberUserNamed(context: android.content.Context, photos: List<Bitmap>, regions: List<FoodRegion>, items: List<RecognizedItem>) {
    val spots = regions.mapNotNull { region ->
        val row = items.firstOrNull { region.id in it.regionIds && it.nutrition != null } ?: return@mapNotNull null
        if (row.name == region.name) return@mapNotNull null
        if (row.name == region.rememberedName && row.source != FoodSource.Picked) return@mapNotNull null
        // 비어 보이는 자리는 다음 분석에서 기억과 견주지 않는다(FoodDetector.withMemory) — 남겨도 다시 찾아지지 않고 자리만 차지한다.
        if (region.looksEmpty) return@mapNotNull null
        val photo = photos.getOrNull(region.photoIndex) ?: return@mapNotNull null
        Triple(row.name, photo, region.box)
    }
    FoodMemoryStore.rememberAsync(context, spots)
}

/** 사진에서 [region] 자리를 둘레 10% 여유를 두고 잘라낸다. 고르는 창 맨 위 "누른 자리" 그림에 쓴다. */
private fun cropSpot(photo: Bitmap?, region: FoodRegion): Bitmap? {
    if (photo == null) return null
    val box = region.box
    val padX = box.width * 0.1f
    val padY = box.height * 0.1f
    val left = ((box.left - padX) * photo.width).toInt().coerceIn(0, photo.width - 1)
    val top = ((box.top - padY) * photo.height).toInt().coerceIn(0, photo.height - 1)
    val right = ((box.right + padX) * photo.width).toInt().coerceIn(left + 1, photo.width)
    val bottom = ((box.bottom + padY) * photo.height).toInt().coerceIn(top + 1, photo.height)
    return Bitmap.createBitmap(photo, left, top, right - left, bottom - top)
}

/** 결과 행 옆에 붙는 작은 사진 — 어느 사진에서 인식됐는지 번호와 함께 보여준다. */
@Composable
private fun PhotoThumb(photo: Bitmap?, number: Int, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size)) {
        PhotoPreview(photo, Modifier.fillMaxSize())
        NumberBadge(number, Modifier.padding(3.dp), small = true)
    }
}

@Composable
private fun NumberBadge(number: Int, modifier: Modifier = Modifier, small: Boolean = false) {
    Box(
        modifier
            .size(if (small) 16.dp else 22.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", color = Color.White, fontSize = if (small) 9.sp else 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PhotoPreview(photo: Bitmap?, modifier: Modifier, dim: Boolean = false, onZoom: (Bitmap) -> Unit = {}) {
    val c = Trex.c
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface2)
            .then(if (photo != null) Modifier.clickable { onZoom(photo) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
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

/**
 * 사진을 전체 화면으로 크게 본다. 시트 안에 그리면 시트 영역에 잘리므로 Dialog 로 띄운다.
 * 아무 데나 누르거나 뒤로가기로 닫는다.
 */
@Composable
private fun PhotoLightbox(photo: Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "사진 크게 보기",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 48.dp),
            )
            Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) { SheetClose(onDismiss) }
        }
    }
}
