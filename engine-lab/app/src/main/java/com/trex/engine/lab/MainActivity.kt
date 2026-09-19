package com.trex.engine.lab

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.trex.engine.*
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: PoseCamera? = null // 분석 스레드 소유
    private var engine: LabEngine? = null
    private var extractor = LandmarkFeatures()
    private var log: SessionLog? = null
    private var latest: EngineOutput? = null
    private var startTime = 0L
    private var lastFrame = 0L
    private var captureFloor = false
    private var uiRunning = false
    @Volatile private var generation = 0
    private var bound = false
    private var resumed = false
    private lateinit var preview: PreviewView
    private lateinit var exercise: Spinner
    private lateinit var pattern: Spinner
    private lateinit var side: CheckBox
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var metrics: TextView
    private lateinit var guide: TextView
    private lateinit var toggle: Button
    private lateinit var share: Button
    private lateinit var annotate: Button
    private var choices = listOf(RepMovementPattern.SIMULTANEOUS)
    private var lastFile: File? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCamera() else status.text = "카메라 권한이 필요합니다. 시작을 누르면 다시 확인합니다."
    }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(30,36,42)); setPadding(0,dp(4),0,dp(4))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(248,249,250)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16),bars.top,dp(16),bars.bottom); insets
        }
        root.addView(text("TREX 실험실",22f))
        root.addView(text("독립 엔진 ${BuildConfig.VERSION_NAME} · ${BuildConfig.ENGINE_SHA256.take(8)}",12f))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        exercise = Spinner(this).apply {
            contentDescription = "운동 선택"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ExerciseRepProfiles.all.map { it.exercise })
        }
        body.addView(exercise)
        pattern = Spinner(this).apply { contentDescription = "횟수 방식" }; body.addView(pattern)
        side = CheckBox(this).apply { text = "몸 옆에서 전신이 보이도록 촬영했습니다" }; body.addView(side)
        guide = text(""); body.addView(guide)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        body.addView(preview,LinearLayout.LayoutParams(-1,dp(240)))
        body.addView(text("전면 카메라 · 화면은 거울처럼 표시 · 왼쪽/오른쪽은 본인 기준",11f))
        count = text("관측 0회",28f); body.addView(count)
        status = text("카메라 준비 중"); status.setTextColor(Color.rgb(30,90,140)); body.addView(status)
        metrics = text("시작하면 관측값이 표시됩니다."); body.addView(metrics)
        body.addView(text("자세 값은 연구용 관측입니다. 정자세 점수·자동 교정은 아직 제공하지 않습니다.",12f))
        body.addView(Button(this).apply { text = "이 운동의 구현 범위"; setOnClickListener { showScope() } })
        val row = LinearLayout(this)
        annotate = Button(this).apply { text = "실제 횟수 / 메모"; setOnClickListener { lastFile?.let(::showAnnotation) ?: toast("먼저 실험을 종료해 주세요") } }
        share = Button(this).apply { text = "로그 보내기"; setOnClickListener { shareLogs() } }
        row.addView(annotate,LinearLayout.LayoutParams(0,-2,1f)); row.addView(share,LinearLayout.LayoutParams(0,-2,1f)); root.addView(row)
        toggle = Button(this).apply { text = "실험 시작"; setOnClickListener { if (uiRunning) stopCapture("user_stop",true) else startCapture() } }
        root.addView(toggle)
        setContentView(root)
        lastFile = SessionLog.directory(this).listFiles()?.filter { it.extension == "jsonl" }?.maxByOrNull { it.name }
        exercise.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = selectionChanged()
        }
        selectionChanged()
    }
    private fun selected() = ExerciseRepProfiles.all[exercise.selectedItemPosition.coerceAtLeast(0)]
    private fun modeLabel(m: RepMovementPattern) = when(m) {
        RepMovementPattern.SIMULTANEOUS -> "양쪽 함께 · 복귀 한 쌍 = 1회"
        RepMovementPattern.ALTERNATING_EACH -> "각 동작 1회 · 좌우 별도 관측"
        RepMovementPattern.LEFT_ONLY -> "왼쪽만"
        RepMovementPattern.RIGHT_ONLY -> "오른쪽만"
    }
    private fun selectionChanged() {
        val p = selected()
        choices = p.allowedPatterns.ifEmpty { listOf(p.defaultPattern) }
        pattern.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices.map { modeLabel(it) })
        pattern.setSelection(choices.indexOf(p.defaultPattern).coerceAtLeast(0))
        pattern.visibility = if (p.allowedPatterns.size > 1) View.VISIBLE else View.GONE
        side.visibility = if (p.floor) View.VISIBLE else View.GONE; side.isChecked = false
        guide.text = if (p.floor) "휴대폰을 고정하고 몸 옆에서 촬영해 주세요. ${p.countDefinition}" else
            "휴대폰을 세워 고정하고 정면에서 시작해 주세요. ${p.countDefinition}"
        if (p.sideAttribution == RepSideAttribution.USER_DECLARED_LEAD) guide.append("\n전체 방식은 측 미확정 합계이며, 한쪽 선택은 본인의 앞/지지 다리 기준입니다.")
        count.text = if (p.isometric) "관측 유지 0.0초" else "관측 0회"
        metrics.text = "관측 항목: "+FormMetrics.forExercise(p).joinToString { it.label }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }
    override fun onPause() {
        resumed = false
        if (uiRunning) stopCapture("background",false)
        provider?.unbindAll(); bound = false
        worker.execute { camera?.close(); camera = null }
        super.onPause()
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    private fun bindCamera() {
        if (!resumed || bound) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!resumed || isDestroyed || bound) return@addListener
            try {
                provider = future.get()
                val previewUse = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { image ->
                    try {
                        val current = engine ?: return@setAnalyzer
                        val now = SystemClock.elapsedRealtime()
                        if (now-lastFrame < 200) return@setAnalyzer
                        lastFrame = now
                        val t = now-startTime
                        val result = (camera ?: PoseCamera(this).also { camera = it }).detect(image, now)
                        val features = result.frame?.let { extractor.extract(it,current.profile.floor) } ?: run { extractor.reset(); emptyMap() }
                        val quality = result.error == null && features.isNotEmpty()
                        val output = current.process(t,features,quality,captureFloor)
                        latest = output
                        log?.frame(result,features,output,quality)
                        val capturedGeneration = generation
                        runOnUiThread { if (uiRunning && capturedGeneration == generation) render(output,result) }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "기록/추론 오류: ${e.message}"; if (uiRunning) stopCapture("error",false) }
                    } finally { image.close() }
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(this,CameraSelector.DEFAULT_FRONT_CAMERA,previewUse,analysis)
                bound = true; status.text = "준비됐습니다. 시작 후 준비 위치에서 잠시 멈춰 주세요."
            } catch(e: Exception) { status.text = "카메라 연결 실패: ${e.message}" }
        },ContextCompat.getMainExecutor(this))
    }
    private fun startCapture() {
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { permission.launch(Manifest.permission.CAMERA); return }
        if (!bound) { bindCamera(); toast("카메라 연결을 기다려 주세요"); return }
        val profile = selected(); val mode = choices[pattern.selectedItemPosition.coerceAtLeast(0)]
        if (profile.floor && !side.isChecked) { toast("측면 촬영 확인을 선택해 주세요"); return }
        val confirmed = side.isChecked
        generation++; uiRunning = true; controls(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "모델 준비 중 · 준비 위치에서 잠시 멈춰 주세요"
        worker.execute {
            try {
                extractor = LandmarkFeatures(); latest = null; lastFrame = 0
                startTime = SystemClock.elapsedRealtime(); captureFloor = confirmed
                log = SessionLog(this,profile,mode,confirmed)
                engine = LabEngine(profile,mode)
                val file = log!!.file
                runOnUiThread { lastFile = file }
            } catch(e: Exception) {
                engine = null; log?.close(); log = null
                runOnUiThread { uiRunning = false; controls(false); status.text = "기록 시작 실패: ${e.message}" }
            }
        }
    }
    private fun stopCapture(reason: String, askTruth: Boolean) {
        uiRunning = false; generation++; toggle.isEnabled = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.execute {
            val file = log?.file; val final = latest
            engine = null
            var error: String? = null
            try { log?.finish(reason,final) } catch(e: Exception) { error = e.message } finally { log = null }
            runOnUiThread {
                controls(false); lastFile = file ?: lastFile
                status.text = if (error != null) "로그 저장 오류: $error" else "저장됨 · 실제 횟수와 메모를 입력한 뒤 로그를 보내 주세요."
                if (askTruth && resumed && file != null) showAnnotation(file)
            }
        }
    }
    private fun controls(running: Boolean) {
        exercise.isEnabled = !running; pattern.isEnabled = !running; side.isEnabled = !running
        share.isEnabled = !running; annotate.isEnabled = !running
        toggle.isEnabled = true; toggle.text = if (running) "실험 종료 · 저장" else "실험 시작"
    }
    private fun render(o: EngineOutput, camera: CameraResult) {
        count.text = if (selected().isometric) "관측 유지 ${String.format(Locale.KOREA,"%.1f",o.observedHoldMs/1000.0)}초" else
            "관측 ${o.counts.total}회\n왼 ${o.counts.left} · 오른 ${o.counts.right} · 측 미확정 ${o.counts.unknown}"
        status.text = (camera.error ?: o.status)+"\n${o.view} · ${camera.delegate} ${camera.inferMs}ms"
        metrics.text = FormMetrics.forExercise(selected()).joinToString("\n") { metric ->
            "${metric.label}: ${o.measurements[metric.key]?.let { String.format(Locale.KOREA,"%.2f",it) } ?: "관측 불가"}"
        }
    }
    private fun showScope() {
        val p = selected()
        val catalog = JSONObject(assets.open("research-catalog.json").bufferedReader().use { it.readText() }).getJSONArray("items")
        val items = (0 until catalog.length()).map { catalog.getJSONObject(it) }.filter { it.getString("exercise") == p.exercise }
        AlertDialog.Builder(this).setTitle(p.exercise).setMessage(
            "실행 중: 독립 횟수/유지 관측과 관절 기하 측정\n${p.limitations}\n\n아래 ${items.size}개 자세 항목은 연구 설계이며 자동 판정·교정 미구현입니다.\n"+
                items.joinToString("\n") { "• "+it.getString("item") }).setPositiveButton("닫기",null).show()
    }
    private fun showAnnotation(file: File) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0) }
        box.addView(text("${file.name}\n직접 센 횟수만 입력해 주세요. 모르면 비워 둡니다.",12f))
        fun field(hint: String, numeric: Boolean = true) = EditText(this).apply {
            this.hint = hint; inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            box.addView(this)
        }
        val total = field("실제 총횟수 (선택)"); val left = field("실제 왼쪽 (선택)"); val right = field("실제 오른쪽 (선택)")
        val scenario = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("조건 미지정","평소 동작","의도적으로 바꾼 동작")); box.addView(this) }
        val note = field("예: 왼발을 들었음 / 3회째 누락 / 본인 왼쪽이 앱 오른쪽으로 나옴",false)
        val dialog = AlertDialog.Builder(this).setTitle("실험 메모").setView(box).setNegativeButton("나중에",null).setPositiveButton("메모 저장",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val fields = listOf(total,left,right)
            if (fields.any { it.text.isNotBlank() && it.text.toString().toIntOrNull() == null }) { toast("횟수는 0 이상의 정수로 입력해 주세요"); return@setOnClickListener }
            val values = fields.map { it.text.toString().toIntOrNull() }
            val condition = scenario.selectedItem.toString(); val memo = note.text.toString()
            worker.execute {
                try { SessionLog.truth(file,values[0],values[1],values[2],condition,memo); runOnUiThread { toast("메모 저장됨") } }
                catch(e: Exception) { runOnUiThread { toast("메모 저장 실패: ${e.message}") } }
            }
            dialog.dismiss()
        } }
        dialog.show()
    }
    private fun shareLogs() {
        share.isEnabled = false
        worker.execute {
            try {
                val file = SessionLog.export(this)
                val uri = FileProvider.getUriForFile(this,"$packageName.files",file)
                runOnUiThread {
                    share.isEnabled = true
                    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = android.content.ClipData.newRawUri("TREX 실험 로그",uri)
                    startActivity(Intent.createChooser(send,"로그 ZIP 보내기"))
                }
            } catch(e: Exception) { runOnUiThread { share.isEnabled = true; toast(e.message ?: "내보내기 실패") } }
        }
    }
    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_LONG).show()
}
