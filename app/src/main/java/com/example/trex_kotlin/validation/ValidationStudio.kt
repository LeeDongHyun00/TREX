package com.example.trex_kotlin.validation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trex.engine.ExerciseCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class StudioModel(application: Application): AndroidViewModel(application) {
    val store = StudyStore(application)
    val processor = StudyProcessor(application)
    var sessions by mutableStateOf(store.sessions()); private set
    var selected by mutableStateOf<File?>(null)
    var busy by mutableStateOf(false); private set
    var canCancel by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var revision by mutableIntStateOf(0); private set
    val cancelled = AtomicBoolean(false)
    fun error(t: Throwable) { message = t.message ?: t.javaClass.simpleName }
    fun work(cancellable: Boolean = false, block: suspend () -> String) {
        if(busy) return
        busy=true; canCancel=cancellable; cancelled.set(false); message="처리 중입니다."
        viewModelScope.launch {
            try { message=withContext(Dispatchers.IO) { block() } }
            catch(t: Throwable) { error(t) }
            finally { busy=false; canCancel=false; sessions=store.sessions(); revision++ }
        }
    }
    fun progress(text: String) { viewModelScope.launch { message=text } }
    override fun onCleared() { cancelled.set(true); super.onCleared() }
}

@Composable
fun ValidationStudio(model: StudioModel = viewModel()) {
    val context=LocalContext.current
    com.example.trex_kotlin.KeepScreenOn()
    val prefs=remember { context.getSharedPreferences("validation_ui",0) }
    var person by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var split by rememberSaveable { mutableStateOf("calibration") }
    var exercise by rememberSaveable { mutableStateOf("바벨 스쿼트") }
    val profile=remember(exercise) { ExerciseCatalog.profiles.first { it.exercise == exercise } }
    var pattern by rememberSaveable(exercise) { mutableStateOf(profile.defaultPattern.name) }
    var view by rememberSaveable(exercise) { mutableStateOf(if(profile.floor) "측면" else "정면") }
    var floor by rememberSaveable(exercise) { mutableStateOf(false) }
    var fromFloor by rememberSaveable(exercise) { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    fun pending(): File = model.store.folder(prefs.getString("pending",null) ?: error("진행 중인 촬영이 없습니다."))
    fun uri(dir: File): Uri = FileProvider.getUriForFile(context,context.packageName+".fileprovider",File(dir,"video.mp4"))
    val capture=rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val dir=runCatching { pending() }.getOrNull()
        if(dir != null) {
            model.selected=dir
            if(ok) model.work { model.store.finishVideo(dir); "영상 저장 완료. 관절 추출을 시작해 주세요." }
            else model.error(IllegalStateException("촬영이 취소됐습니다. 저장된 영상이 있다면 복구할 수 있습니다."))
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok) runCatching { capture.launch(uri(pending())) }.onFailure(model::error)
        else model.error(IllegalStateException("촬영하려면 카메라 권한이 필요합니다. 영상 가져오기도 사용할 수 있습니다."))
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { incoming ->
        if(incoming != null) {
            val dir=pending(); model.selected=dir
            model.work { model.store.importVideo(dir,incoming); "영상 복사 완료. 관절 추출을 시작해 주세요." }
        }
    }
    fun prepare(source: String): File {
        require(consent) { "영상과 관절 좌표의 로컬 저장에 동의해 주세요." }
        val dir=model.store.create(person.trim(),day,split,exercise,pattern,view,floor,fromFloor,source)
        prefs.edit().putString("pending",dir.name).apply()
        return dir
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=androidx.compose.ui.graphics.Color(0xFFACF77A))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=18.dp)) {
                Text("TREX 검증 스튜디오",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(vertical=12.dp))
                if(model.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); if(model.canCancel) TextButton({model.cancelled.set(true)}) { Text("분석 중단 요청") } }
                if(model.message.isNotEmpty()) Text(model.message,modifier=Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall)
                val selected=model.selected
                if(selected != null) {
                    BackHandler(enabled=!model.busy) { model.selected=null }
                    StudyDetail(model,selected) { model.selected=null }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("촬영 → 정답 표시 → 엔진 비교",style=MaterialTheme.typography.titleLarge)
                        Text("한 영상으로 반복 비교합니다. 예측은 정답 확정 뒤에 공개됩니다. 분석은 녹화 후 실행되며 음성 교정 효과는 평가하지 않습니다.")
                        OutlinedTextField(person,{person=it},label={Text("수행자 코드 (예: P001)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(day,{day=it},label={Text("촬영일 · YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        Choice("자료 용도",split,listOf("train" to "학습","calibration" to "보정","test" to "최종 시험")) { split=it }
                        Choice("운동",exercise,ExerciseCatalog.profiles.map { it.exercise to it.exercise }) { exercise=it }
                        Choice("수행 방식",pattern,profile.allowedPatterns.map { it.name to patternLabel(it.name) }) { pattern=it }
                        Text(profile.countDefinition,style=MaterialTheme.typography.bodySmall)
                        Choice("촬영 방향 · 사용자 선언",view,listOf("정면","오른쪽 앞","왼쪽 앞","측면","뒤","미상").map { it to it }) { view=it }
                        if(profile.floor) Check("측면에서 몸 전체가 보이는 촬영을 확인했습니다",floor) { floor=it }
                        if(exercise == "바벨 데드리프트") Check("바닥에서 들어 올리는 방식입니다",fromFloor) { fromFloor=it }
                        Check("촬영 대상의 동의를 받았으며 영상·좌표를 이 기기에 저장합니다",consent) { consent=it }
                        Text("자동 업로드·자동 만료 삭제는 없습니다. 촬영 영상에 카메라 앱 설정에 따른 소리가 포함될 수 있습니다.",style=MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(enabled=!model.busy,onClick={ runCatching {
                                val dir=prepare("camera")
                                if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) capture.launch(uri(dir))
                                else permission.launch(Manifest.permission.CAMERA)
                            }.onFailure(model::error) }) { Text("운동 촬영") }
                            OutlinedButton(enabled=!model.busy,onClick={runCatching { prepare("import"); import.launch(arrayOf("video/*")) }.onFailure(model::error)}) { Text("영상 가져오기") }
                        }
                        HorizontalDivider()
                        Text("수집한 영상 ${model.sessions.size}개",style=MaterialTheme.typography.titleMedium)
                        model.sessions.forEach { dir ->
                            val data=remember(dir,model.revision) { readJson(File(dir,"session.json")) }
                            OutlinedButton(onClick={model.selected=dir},enabled=!model.busy,modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("${data.getString("exercise")} · ${data.getString("person")}")
                                    Text("${data.getString("day")} · ${data.getString("split")} · ${data.getString("status")}",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyDetail(model: StudioModel, dir: File, back: () -> Unit) = key(dir.path) {
    val context=LocalContext.current
    val data=remember(model.revision) { readJson(File(dir,"session.json")) }
    val extraction=remember(model.revision) { model.store.latest(dir,"extractions") }
    val label=remember(model.revision) { model.store.latest(dir,"labels") }
    val run=remember(model.revision) { model.store.latest(dir,"runs") }
    var editor by rememberSaveable { mutableStateOf(false) }
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var delete by remember { mutableStateOf(false) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri != null) model.work { model.store.export(dir,uri); "원본·좌표·정답·평가와 해시 목록을 내보냈습니다." }
    }
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("이 영상과 검증 자료를 삭제할까요?")},text={Text("이 기기의 원본·좌표·정답·평가 이력을 지웁니다. 따로 내보낸 ZIP은 유지됩니다.")},
        confirmButton={TextButton({delete=false;model.work {model.store.deleteSession(dir);"검증 자료를 삭제했습니다."};back()}) {Text("삭제")}},dismissButton={TextButton({delete=false}) {Text("취소")}})
    if(editor) {
        TruthEditor(model,dir,label) { editor=false }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick=back,enabled=!model.busy) { Text("영상 목록으로") }
        Text(data.getString("exercise"),style=MaterialTheme.typography.titleLarge)
        Text("${data.getString("person")} · ${data.getString("day")} · ${data.getString("split")}")
        Text("1  영상: ${if(data.optString("status") == "VIDEO_READY") "보존 완료 · ${data.getLong("duration_ms")/1000}초" else "저장 확인 필요"}")
        Text("2  좌표: ${if(extraction == null) "추출 전" else "추출 완료"}")
        Text("3  정답: ${if(label == null) "검토 전" else "개정 저장됨"}")
        Text("4  비교: ${if(run == null) "실행 전" else "보고서 있음"}")
        if(run != null && label != null && readJson(File(run,"manifest.json")).getString("labels_sha256") != label.sha256()) Text("정답이 바뀌었습니다. 현재 정답으로 비교를 다시 실행해 주세요.",color=MaterialTheme.colorScheme.primary)
        if(data.optString("status") != "VIDEO_READY") {
            Button(enabled=!model.busy,onClick={model.work {model.store.finishVideo(dir); "저장된 영상을 복구했습니다."}}) {Text("저장된 영상 확인·복구")}
        } else {
            Button(enabled=!model.busy,onClick={model.work(cancellable=true) {model.processor.extract(dir,model.cancelled,model::progress); "좌표 추출 완료. 정답을 검토해 주세요."}},modifier=Modifier.fillMaxWidth()) {Text(if(extraction == null) "관절 좌표 추출" else "좌표 새 실행으로 재추출")}
            OutlinedButton(enabled=!model.busy,onClick={editor=true},modifier=Modifier.fillMaxWidth()) {Text(if(label == null) "영상 보며 정답 표시" else "정답 검토·새 개정 저장")}
            Button(enabled=!model.busy && extraction != null && label != null,onClick={model.work(cancellable=true) {
                model.processor.evaluate(dir,extraction!!,label!!,model.cancelled,model::progress); "엔진 비교 완료. 결과를 열어 확인해 주세요."
            }},modifier=Modifier.fillMaxWidth()) {Text("같은 좌표로 엔진 비교")}
            if(run != null) OutlinedButton(enabled=!model.busy,onClick={runCatching {
                writeJson(File(dir,"prediction-exposed.json"),JSONObject().put("first_or_latest_view_utc_ms",System.currentTimeMillis()))
                report=readJson(File(run,"report.json"))
            }.onFailure(model::error)}) {Text("비교 결과 열기")}
        }
        OutlinedButton(enabled=!model.busy,onClick={export.launch("TREX-${dir.name}.zip")},modifier=Modifier.fillMaxWidth()) {Text("영상·좌표·정답·결과 ZIP 저장")}
        TextButton(enabled=!model.busy,onClick={delete=true}) {Text("이 영상의 검증 자료 삭제")}
        Text("원본 영상은 재추론용, 좌표는 동일 입력 비교용입니다. 라벨은 사람이 판단한 자료이며 전문가 합의 정답 여부는 별도로 확인해야 합니다.",style=MaterialTheme.typography.bodySmall)
        report?.let { ReportView(it) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun TruthEditor(model: StudioModel, dir: File, label: File?, back: () -> Unit) {
    val draft=remember { File(dir,"draft.json").takeIf {it.exists() && readJson(it).optString("base_label") == (label?.name ?: "")} }
    val original=remember { (draft ?: label)?.let { model.store.truth(it) } }
    val data=remember { readJson(File(dir,"session.json")) }
    val duration=data.getLong("duration_ms")
    var reviewer by rememberSaveable { mutableStateOf(original?.reviewer ?: "") }
    var start by rememberSaveable { mutableStateOf(original?.startMs?.toString() ?: "0") }
    var end by rememberSaveable { mutableStateOf(original?.endMs?.toString() ?: duration.toString()) }
    var repsReviewed by rememberSaveable { mutableStateOf(original?.repsReviewed ?: false) }
    val reps=remember { mutableStateListOf<TruthRep>().apply { addAll(original?.reps.orEmpty()) } }
    val forms=remember { mutableStateListOf<TruthForm>().apply { addAll(original?.forms.orEmpty()) } }
    val holds=remember { mutableStateListOf<TimeSpan>().apply { addAll(original?.holds.orEmpty()) } }
    var holdsReviewed by rememberSaveable { mutableStateOf(original?.holdsReviewed ?: false) }
    var intervalStart by rememberSaveable { mutableStateOf("0") }
    var intervalEnd by rememberSaveable { mutableStateOf("0") }
    var side by rememberSaveable { mutableStateOf("COMMON") }
    var phase by rememberSaveable { mutableStateOf("전체") }
    var note by rememberSaveable { mutableStateOf("") }
    var customName by rememberSaveable { mutableStateOf("") }
    val options=remember { model.processor.rules().rules.filter { it.exercise == data.getString("exercise") }.map { it.id to "${it.condition} · ${it.baseFeature}" }.distinctBy { it.first } }
    var item by rememberSaveable { mutableStateOf(options.firstOrNull()?.first ?: "custom") }
    var verdict by rememberSaveable { mutableStateOf("UNKNOWN") }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var media by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var current by remember { mutableLongStateOf(0L) }
    var playError by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    fun leave() { if(dirty) confirmLeave=true else back() }
    BackHandler { leave() }
    DisposableEffect(Unit) { onDispose { player?.stopPlayback() } }
    LaunchedEffect(player) { while(true) { current=(player?.currentPosition ?: 0).toLong(); delay(100) } }
    LaunchedEffect(reviewer,start,end,repsReviewed,reps.toList(),forms.toList(),holdsReviewed,holds.toList()) {
        if(dirty) {
            delay(350)
            val snapshot=Truth(reviewer,start.toLongOrNull() ?: 0,end.toLongOrNull() ?: duration,repsReviewed,reps.toList(),forms.toList(),holdsReviewed=holdsReviewed,holds=holds.toList())
            val saved=withContext(Dispatchers.IO) { runCatching { model.store.saveDraft(dir,snapshot,label?.name) } }
            saved.onFailure(model::error)
        }
    }
    if(confirmLeave) AlertDialog(onDismissRequest={confirmLeave=false},title={Text("확정하지 않은 정답이 있습니다")},text={Text("입력 중인 내용은 초안으로 보관합니다. 이번 입력을 닫을까요?")},
        confirmButton={TextButton({back()}) {Text("이번 입력 닫기")}},dismissButton={TextButton({confirmLeave=false}) {Text("계속 입력")}})
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton({leave()},enabled=!model.busy) {Text("영상 상세로")}
        Text("독립 정답 입력",style=MaterialTheme.typography.titleLarge)
        if(draft != null) Text("이전에 입력하던 초안을 복구했습니다. 아직 확정 정답이 아닙니다.",style=MaterialTheme.typography.bodySmall)
        Text("예측을 복사하지 않습니다. 반복은 출발→복귀 완료까지, 자세는 항목별 구간으로 표시해 주세요. 숫자의 단위는 영상 시작부터 ms입니다.",style=MaterialTheme.typography.bodySmall)
        AndroidView(factory={ c -> VideoView(c).apply {
            setVideoPath(File(dir,"video.mp4").path)
            setMediaController(MediaController(c).also { it.setAnchorView(this) })
            setOnPreparedListener { media=it;it.seekTo(0L,android.media.MediaPlayer.SEEK_CLOSEST) }
            setOnErrorListener { _,what,extra -> playError="영상 재생 오류: $what/$extra"; true }
            player=this
        }},modifier=Modifier.fillMaxWidth().height(230.dp))
        playError?.let { Text(it) }
        Text("재생 위치 ${current}ms / ${duration}ms")
        Slider(value=current.toFloat().coerceIn(0f,duration.toFloat()),onValueChange={player?.pause(); media?.seekTo(it.toLong(),android.media.MediaPlayer.SEEK_CLOSEST); current=it.toLong()},valueRange=0f..duration.toFloat())
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton({player?.pause(); media?.seekTo((current-200).coerceAtLeast(0),android.media.MediaPlayer.SEEK_CLOSEST)}) {Text("−0.2초")}
            TextButton({if(player?.isPlaying == true) player?.pause() else player?.start()}) {Text("재생/정지")}
            TextButton({player?.pause(); media?.seekTo((current+200).coerceAtMost(duration),android.media.MediaPlayer.SEEK_CLOSEST)}) {Text("+0.2초")}
        }
        OutlinedTextField(reviewer,{reviewer=it;dirty=true},label={Text("평가자 코드")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(start,{start=it;dirty=true},label={Text("평가 시작 ms")},modifier=Modifier.weight(1f))
            OutlinedTextField(end,{end=it;dirty=true},label={Text("평가 끝 ms")},modifier=Modifier.weight(1f))
        }
        HorizontalDivider()
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(intervalStart,{intervalStart=it},label={Text("표시 시작 ms")},modifier=Modifier.weight(1f))
            OutlinedTextField(intervalEnd,{intervalEnd=it},label={Text("표시 끝 ms")},modifier=Modifier.weight(1f))
        }
        Row { TextButton({intervalStart=(player?.currentPosition?.toLong() ?: current).toString()}) {Text("현재를 시작으로")}; TextButton({intervalEnd=(player?.currentPosition?.toLong() ?: current).toString()}) {Text("현재를 끝으로")} }
        Choice("좌우 · 사용자 기준",side,listOf("COMMON" to "공통/구분 없음","LEFT" to "왼쪽","RIGHT" to "오른쪽","BOTH" to "양쪽 동시","UNKNOWN" to "판단 불가")) {side=it}
        fun checkRange(): Pair<Long,Long> {
            val a=intervalStart.toLongOrNull() ?: error("시작 시각을 숫자로 입력해 주세요.")
            val b=intervalEnd.toLongOrNull() ?: error("끝 시각을 숫자로 입력해 주세요.")
            require(a>=0 && b>a && b<=duration) {"영상 안의 시작·끝을 지정해 주세요."}
            return a to b
        }
        OutlinedButton(onClick={runCatching {val (a,b)=checkRange(); reps+=TruthRep(a,b,side); repsReviewed=false; dirty=true}.onFailure(model::error)},modifier=Modifier.fillMaxWidth()) {Text("이 구간의 완료 반복 1회 추가")}
        reps.toList().forEachIndexed { i,r -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${i+1}회 · ${r.startMs}–${r.endMs}ms · ${r.side}",modifier=Modifier.weight(1f))
            TextButton({reps.removeAt(i);repsReviewed=false;dirty=true}) {Text("삭제")}
        } }
        Check("평가 구간 전체의 반복을 확인했습니다 (${reps.size}회, 0회도 명시적 확인)",repsReviewed) {repsReviewed=it;dirty=true}
        if(data.getString("exercise") == "플랭크") {
            Text("플랭크는 자세가 올바른지와 별도로 실제 동작을 유지한 구간을 표시합니다.")
            OutlinedButton(onClick={runCatching {val (a,b)=checkRange();holds+=TimeSpan(a,b);holdsReviewed=false;dirty=true}.onFailure(model::error)}) {Text("플랭크 유지 구간 추가")}
            holds.toList().forEachIndexed { i,h -> Row {Text("${h.startMs}–${h.endMs}ms",modifier=Modifier.weight(1f));TextButton({holds.removeAt(i);holdsReviewed=false;dirty=true}) {Text("삭제")}} }
            Check("전체 유지 구간을 확인했습니다",holdsReviewed) {holdsReviewed=it;dirty=true}
        }
        HorizontalDivider()
        Choice("자세 평가 항목",item,options+listOf("custom" to "기타 · 미지원 항목")) {item=it}
        if(item == "custom") {
            OutlinedTextField(customName,{customName=it},label={Text("기타 평가 항목 이름")},modifier=Modifier.fillMaxWidth())
            Text("같은 항목은 같은 이름을 사용해 주세요. 자동 판정은 미지원으로 처리합니다.",style=MaterialTheme.typography.bodySmall)
        }
        Choice("사람이 판단한 상태",verdict,listOf("UNKNOWN" to "판단 불가","OK" to "정상","VIOLATION" to "오류")) {verdict=it}
        Choice("동작 단계",phase,listOf("전체","출발","내려감","최저/최고점","복귀","유지").map {it to it}) {phase=it}
        OutlinedTextField(note,{note=it},label={Text("관찰 근거·오류 방향·의도한 변형")},modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={runCatching {val (a,b)=checkRange(); require(item != "custom" || customName.isNotBlank()) {"기타 항목의 이름을 적어 주세요."}; forms+=TruthForm(a,b,if(item=="custom") "custom:${customName.trim()}" else item,verdict,side,phase,note);dirty=true}.onFailure(model::error)},modifier=Modifier.fillMaxWidth()) {Text("자세 정답 구간 추가")}
        forms.toList().forEachIndexed { i,f -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("${options.firstOrNull {it.first==f.item}?.second ?: f.item}\n${f.startMs}–${f.endMs}ms · ${f.verdict}",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton({forms.removeAt(i);dirty=true}) {Text("삭제")}
        } }
        Button(enabled=!model.busy,onClick={runCatching {
            val t=Truth(reviewer.trim(),start.toLong(),end.toLong(),repsReviewed,reps.toList(),forms.toList(),holdsReviewed=holdsReviewed,holds=holds.toList())
            model.store.saveTruth(dir,t);dirty=false
            model.work {"정답을 새 개정으로 저장했습니다."}; back()
        }.onFailure(model::error)},modifier=Modifier.fillMaxWidth()) {Text("정답 개정 저장")}
        Text("미라벨 구간은 정상으로 처리하지 않습니다. 자세 항목 이름만으로 판단하기 어렵다면 판단 불가로 남겨 주세요.",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportView(report: JSONObject) {
    HorizontalDivider()
    Text("동일 입력 비교 결과",style=MaterialTheme.typography.titleLarge)
    Text("반복: 구형 출시 소스의 반복 코어 ↔ v2. 자세: 공통 ship/window 기준선 ↔ v2 피드백. 기존 APK 전체 재현은 아닙니다.",style=MaterialTheme.typography.bodySmall)
    report.optJSONObject("v2_hold")?.let { h -> Text("플랭크 유지 시간\n정답 ${h.getLong("truth_ms")}ms · 관측 ${h.getLong("predicted_ms")}ms\n누락 ${h.getLong("missed_ms")}ms · 추가 ${h.getLong("extra_ms")}ms · 정자세 유지 시간 아님") }
    for((key,title) in listOf("legacy_reps" to "구형 반복 코어","v2_reps" to "v2 반복")) {
        val score=report.optJSONObject(key)
        Text(if(score == null) "$title · 미지원 또는 반복 정답 미확정" else "$title\n정답 ${score.getInt("truth")} · 예측 ${score.getInt("predicted")}\n일치 ${score.getInt("matched")} · 누락 ${score.getInt("missed")} · 추가 ${score.getInt("extra")}\n완료 시각 평균 오차 ${if(score.isNull("mean_timing_error_ms")) "산출 불가" else "%.0fms".format(score.getDouble("mean_timing_error_ms"))}")
    }
    for((key,title) in listOf("reference_form" to "자세 기준선","v2_form" to "v2 자세 피드백")) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        val rows=report.getJSONArray(key).rows()
        if(rows.isEmpty()) Text("자세 정답 구간 없음 · 정확도 산출 불가")
        rows.forEach { row ->
            fun pct(name: String) = if(row.isNull(name)) "산출 불가" else "%.1f%%".format(row.getDouble(name)*100)
            Text("${row.optString("label",row.getString("item"))}\n정상 구간 오탐 ${pct("false_alarm_rate")} · 오류 검출 ${pct("sensitivity")}\n판정 범위 ${pct("coverage")} · 정답 판단 불가 ${row.getLong("unknown_truth_ms")}ms",style=MaterialTheme.typography.bodySmall)
            if(row.getLong("judged_ms") == 0L) Text("자동 판정 없음 · 유보/미지원 · 정상 인증 아님",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
        }
    }
    Text(report.getString("warning"),color=MaterialTheme.colorScheme.primary)
}

@Composable
private fun Choice(label: String, selected: String, options: List<Pair<String,String>>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()) {Text("$label: ${options.firstOrNull {it.first==selected}?.second ?: selected}")}
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=360.dp)) {
            options.forEach { (key,text) -> DropdownMenuItem(text={Text(text)},onClick={change(key);open=false}) }
        }
    }
}
@Composable
private fun Check(text: String, value: Boolean, change: (Boolean) -> Unit) {
    Row { Checkbox(checked=value,onCheckedChange=change); Text(text,modifier=Modifier.weight(1f).padding(top=12.dp)) }
}
private fun patternLabel(value: String) = when(value) {
    "SIMULTANEOUS" -> "양쪽 동시 · 한 쌍 1회"
    "ALTERNATING_EACH" -> "좌우 교대 · 각 측 1회"
    "LEFT_ONLY" -> "왼쪽만"
    "RIGHT_ONLY" -> "오른쪽만"
    else -> value
}
