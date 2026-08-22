# TREX 자세 판정 — Kotlin 포팅 명세 (rules_mp_v0)

`rules/rules_mp_v0.json` 을 Android 앱(MediaPipe Pose Landmarker)에서 그대로 평가할 수 있도록 좌표 변환·피처 정의·집계·규칙 평가·보정 절차를 고정한다.
근거 실험: 실험 B(GT 3D 각도 피처의 조건 판별력), 룰엔진 v0(물리 피처 화이트리스트), 실험 A/A-2(MediaPipe 단일 뷰 전이·재적합). 수치는 `outputs/*_summary.md` 참조.

## 0. 한 줄 요약
- 규칙 141개 중 **ship 59 / beta 12 / exclude 70** (rules_mp_v0.1). ship = 전방 반구 최적 단일 뷰에서 MediaPipe 피처 재적합 AUC ≥ 0.85 (수행자 홀드아웃).
  v0.1 은 좌/우 카메라 위치에 무관한 **미러 불변 규칙을 우선 채택**(미러 안전 화이트리스트로 별도 재적합, 최적 뷰 AUC 중앙값 0.812 vs 비제약 0.818) — 미러 불변 ship 38 → **53**, 교체된 16개의 비제약 규칙은 `alt_rule` 로 보존, 남은 비안전 ship 6개는 대안이 약해 유지(+주의). (§7, §14)
- MediaPipe 피처 위 규칙의 최적 뷰 AUC 중앙값 0.818 vs 같은 표본의 GT 3D 통제군 0.843 → 랜드마크 노이즈 비용 −0.025. 고정 뷰는 정면/전방 사선 −0.06, 후방 −0.13.
- **임계값은 GT → MP 로 전이되지 않는다**(GT 임계값 그대로 쓰면 균형정확도 0.55~0.59). JSON 의 임계값은 MP 피처로 재적합한 값이지만, 스튜디오 분포 기준이므로 **자체 촬영 데이터로 재보정(§9) 필수**.

## 1. 파이프라인
```
CameraX(ImageAnalysis, KEEP_ONLY_LATEST)
 → PoseLandmarker(VIDEO mode, full 모델)            §2
 → worldLandmarks(33) → cm, y/z 부호 반전 → 24관절 매핑   §3
 → 신체좌표계(골반 원점, x_b 좌, y_b 상, z_b 전)          §4
 → 프레임 피처(필요한 패밀리만)                        §5
 → 세트 창 집계 mean/min/max/std/range                   §6
 → 규칙 평가(violation_if) + 유보 처리                   §7
 → 세트 종료 후 리포트(실시간 음성 큐는 2차)
```

## 2. MediaPipe 설정
- 의존성: `com.google.mediapipe:tasks-vision` (Pose Landmarker). 모델: `pose_landmarker_full.task` (assets, 9.4 MB, 기본) / `pose_landmarker_lite.task` (5.8 MB, 발열·저사양 대안; 임계값 재보정 필요).
- 옵션: `runningMode=VIDEO`, `numPoses=1`, `minPoseDetectionConfidence=0.5`, `minPosePresenceConfidence=0.5`, `minTrackingConfidence=0.5`, `outputSegmentationMasks=false`.
- **델리게이트: GPU 우선, 실패 시 CPU 폴백** (`BaseOptions.setDelegate`). GPU 는 GL 컨텍스트가 생성 스레드에 묶이므로 랜드마커를 **분석 스레드에서 지연 생성**하고 같은 스레드에서만 `detectForVideo` 한다 (`PostureAnalyzer.ensureReady`).
- 사용 출력: `worldLandmarks()[0]` (m, 골반 중점 원점) 과 `landmarks()[0]` 의 `visibility()/presence()` (유보 판단용).
- 처리율: 데스크톱 CPU 29 ms/장(1920×1080). Galaxy Note10 5G(Exynos 9825) CPU full 모델 150~245 ms/장.

### 2-1. 발열 대책 (실측으로 확인된 원인과 수정)
증상: 실험실 화면 2분 사용 시 기기 발열. 기준 측정(수정 전): **앱 CPU 140~173%**, AP 39.8→41.8°C, 표면 33.7→35.4°C (2분).
원인(코드): ① `KEEP_ONLY_LATEST` + 분석기 즉시 재진입 → full 모델 CPU 추론이 **쉬지 않고** 돌았음(듀티 ≈100%, 판정에 필요한 건 300ms 당 1회).
IDLE/RESULT 화면에서도 동일. ② CPU 델리게이트. ③ `OUTPUT_IMAGE_FORMAT_RGBA_8888` → CameraX 가 전달되는 **모든** 프레임(30fps)을 CPU 변환, 추가로 프레임마다 비트맵 2장 할당. ④ 720×960 분석 스트림(모델 입력은 256px).
수정: **추론 스케줄러** `InferencePolicy` (RECORDING = 샘플 간격 1:1, IDLE 400ms, RESULT 1500ms, OS 열 상태별 ×1.5/×2/×3 감속 — 심한 발열에서도 10초 세트에 ≥16프레임 유지),
GPU 델리게이트 우선, YUV 입력 + 추론 프레임만 변환 + 회전 비트맵 재사용, 분석 640×480 / 프리뷰 ≤1280×960, 엔진 상태(모델·델리게이트·간격·추론 ms·듀티·열 상태) 표시와 full/lite·GPU/CPU 토글.

## 3. 좌표 변환 & 관절 매핑
```
P_cm = worldLandmark × 100
P_cm.y = −P_cm.y ;  P_cm.z = −P_cm.z      // MediaPipe world 는 y 아래+ → y 위+ 로, 오른손계 유지
```
| 24관절(내부명) | MediaPipe 인덱스 | 비고 |
|---|---|---|
| Nose | 0 | |
| LEye / REye | 2 / 5 | eye center |
| LEar / REar | 7 / 8 | |
| LShoulder / RShoulder | 11 / 12 | |
| LElbow / RElbow | 13 / 14 | |
| LWrist / RWrist | 15 / 16 | |
| LHip / RHip | 23 / 24 | |
| LKnee / RKnee | 25 / 26 | |
| LAnkle / RAnkle | 27 / 28 | |
| Neck | (11+12)/2 | ※ AIHub Neck(목 기저)보다 낮음 — `shoulder_neck_gap`, `shoulder_fwd`, `neck_angle` 은 정의상 사용 불가(제외됨) |
| LPalm / RPalm | (17+19)/2 / (18+20)/2 | pinky·index 중점 |
| LFoot / RFoot | 31 / 32 | foot_index. 뒤꿈치 관련은 heel(29/30) 사용 권장 |
| Back / Waist | 없음 | spine_* 피처 전부 미사용 |

MediaPipe 의 Left/Right 는 사람 기준(해부학적)이며 AIHub 와 동일 (실험 A 에서 직접 매핑 오차 0.052 vs 반전 0.319 로 확인).
파생점: `HipMid=(LHip+RHip)/2`, `ShMid=(LSh+RSh)/2 (=Neck)`, `EarMid`, `PalmMid`, `KneeMid`, `AnkleMid`.

## 4. 신체좌표계 (매 프레임)
```
y_b = up                                        // 중력 반대 방향 (IMU, 아래 참조)
x_b = unit( flat(LHip − RHip) ),  flat(v) = v − y_b·(v·y_b)   // 사람의 왼쪽 +
z_b = unit( x_b × y_b )                          // 전방 +
body(P) = ( (P−HipMid)·x_b , (P−HipMid)·y_b , (P−HipMid)·z_b )
height(P) = P·y_b                                // 모든 "높이" 피처는 화면 세로축이 아니라 이 값
```

**up 은 IMU 중력축에서 구한다** (`PostureOrientation.kt`). 화면 세로축을 중력으로 가정하면 폰이 기울거나 회전할 때
모든 높이·수직 피처가 틀어지므로, `TYPE_GRAVITY`(없으면 가속도계 저역통과) 벡터를 world 좌표계로 옮겨 `up = −g` 로 쓴다.

| 프레임 | 정의 |
|---|---|
| 센서(디바이스 자연좌표) | x 오른쪽, y 기기 상단, z 화면 바깥 |
| 디스플레이 | 회전별 (right, up) 을 디바이스 좌표로 표현 — `displayAxes(rotation)` |
| 이미지 | up = 디스플레이 up, right = viewDir × up → 후면은 디스플레이 right, **전면은 그 반대** |
| world | X = 이미지 right, Y = 이미지 up, Z = 카메라 쪽(후면 +z_dev, 전면 −z_dev) |

`gravityUpInWorld(g, displayRotation, isFront)` = `−normalize( (g·imageRight, g·displayUp, g·towardCamera) )`.
센서를 못 쓰면 `SCREEN_UP=(0,1,0)` 로 폴백하고 UI 에 그 사실을 표시한다.
정규화 분모(실패 프레임 방지): `torso_len=|Neck−HipMid| (<20cm → NaN)`, `leg_len=mean(|LHip−LAnkle|,|RHip−RAnkle|) (<40cm → NaN)`, `sh_w=|LSh−RSh| (<15cm → NaN)`, `hip_w=|LHip−RHip| (<8cm → NaN)`, `body_h=Neck.y−AnkleMid.y (|·|<30cm → NaN)`.

## 5. 피처 (프레임 단위)
기본 연산: `angle3(A,B,C)` = B 꼭짓점 각(도), `angleVec(u,v)`, `pointLineDist(P, A→B)`, `horiz(v)` = y 성분 0.
ship/beta 규칙이 쓰는 25개 패밀리와 계산식·필요 랜드마크는 `rules/rules_mp_v0.json → features_used` 및 `rules/rules_mp_v0.md` 하단 표에 기계 생성되어 있다(그 표가 정본). 대표 예:

| 패밀리 | 계산식 | 변형 |
|---|---|---|
| knee | ∠(Hip, Knee, Ankle) | _L/_R, _mean=(L+R)/2, _minside=min, _maxside=max, _asym=L−R |
| elbow / hip / shoulder | ∠(Sh,El,Wr) / ∠(Sh,Hip,Knee) / ∠(El,Sh,Hip) | 동일 |
| torso_incl / torso_pitch | angle(Neck−HipMid, UP) / atan2(z_b(Neck), y_b(Neck)) | 부호: pitch + = 앞으로 숙임 |
| head_pitch / face_vs_torso / face_vs_forward | face=Nose−EarMid; 고도각 / angle(face, Neck−HipMid) / angle(face, z_b) | |
| knee_out | 무릎 x_b 와 Hip→Ankle 선 위 같은 높이 x_b 의 차 / |Hip−Ankle|, L:+외측, R:부호반전 | _mean; 음수 = valgus. **정면 뷰 전용** |
| ear_shoulder_gap | (EarMid.y − ShMid.y)/torso_len | 으쓱/목 프록시 |
| grip_w / stance_w | |LPalm−RPalm|/sh_w / |LAnkle−RAnkle|/hip_w | |
| palm_h_rel / palm_lat / palm_head_dist / hand_h_asym | (PalmMid.y−AnkleMid.y)/body_h / x_b(PalmMid)/sh_w / |PalmMid−EarMid|/torso_len / (LPalm.y−RPalm.y)/torso_len | |
| shoulder_asym / shoulder_h | (LSh.y−RSh.y)/sh_w / (Sh.y−HipMid.y)/torso_len | lateral 척추 규칙 핵심 |
| knee_h / knee_lat / knee_elbow_dist / elbow_torso | (Knee.y−HipMid.y)/leg_len / sign·(x_b(Knee)−x_b(Hip))/hip_w / min 같은쪽 |Knee−Elbow|/torso_len / pointLineDist(El, Hip→Sh)/|Sh−Hip| | |
| foot_pitch | asin(unit(Foot−Ankle).y) | 앱은 heel.y 변화량으로 대체 검토 |

NaN 규칙: 관절 visibility 또는 presence < 0.5 → 그 관절을 쓰는 피처는 해당 프레임 NaN. 집계는 NaN 무시(nanmean 등).

## 6. 집계 (세트 창)
- 통계: `mean, min, max, std(모집단), range=max−min` — JSON 의 `stat` 필드.
- **창 정의가 임계값의 전제다.** AIHub 클립 = 여러 렙(≈4렙)에 걸친 16프레임의 성긴 샘플링. 앱에서는 **세트 전체(또는 최소 3~4렙) 동안 2~4 fps 로 샘플한 프레임**으로 같은 통계를 내야 std/range 계열 규칙(무릎 반동, 발바닥 고정 등)이 같은 스케일이 된다. 30 fps 전 프레임을 쓰면 std 는 비슷하지만 min/max 가 극단값에 더 민감해진다 → 재보정 시 창을 고정하고 임계값을 다시 맞출 것.
- 유효 프레임 < 8 이면 그 세트는 "판정 유보".

## 7. 규칙 평가
`rules/rules_mp_v0.json`
```json
{ "version":"mp_v0", "coordinate_convention":{...}, "status_definition":{...},
  "rules":[ { "id":"바벨 스쿼트|발과 무릎의 방향 일치", "exercise":"바벨 스쿼트", "condition":"발과 무릎의 방향 일치", "subtype":null,
              "status":"ship", "feature":"knee_out_mean__mean", "base_feature":"knee_out_mean", "stat":"mean", "family":"knee_out",
              "op":"<", "threshold":0.0076, "violation_if":"knee_out_mean__mean < 0.0076",
              "view_best_front":"C", "cv_auc":0.96, "cv_balacc":0.91, "n":60, "cautions":["valgus 는 정면(C) 뷰에서만 신뢰 ..."] , ... } ],
  "features_used":[ { "family":"knee_out", "bases":[...], "description":"...", "formula":"...", "mp_landmarks":[23,24,25,26,27,28] } ] }
```
- 평가: `value = aggregate(stat, frameFeature(base))`; `violated = (op=="<" ? value < threshold : value > threshold)`; value 가 NaN 이면 "유보".
- v0 출시 범위: `status=="ship"` 만 활성. `beta` 는 플래그 뒤에서 수집만. `exclude` 는 UI 에 노출하지 않음(사유는 JSON `reason`).
- 척추: `subtype` 이 있는 규칙이 정본(lateral/forward_lean/flexion/lumbar_swing). `[all]` 은 하위유형이 하나뿐인 종목에서만 동일 규칙이므로 중복 노출하지 말 것. `cervical` 은 exclude.
- 피드백 문구는 조건명이 아니라 **연기된 편차**(`research/aihub_fitness/README.md` "라벨명 ≠ 연기 편차" 항목) 기준으로 작성: 예) OHP "전완 지면과 수직" 위반 → "팔꿈치가 앞으로 벌어졌어요(그립/팔꿈치 위치)".
- **좌/우 미러(`mirror_safe`)**: MediaPipe 의 L/R 은 해부학적이라 값 자체는 카메라 위치와 무관하지만, 카메라 반대편 관절은 먼 쪽(가림)이 되어 정밀도가 떨어진다. `mirror_safe=false` 규칙(`*_L/*_R` 지정, 또는 반대칭 피처의 mean/min/max)은
  (a) 촬영 측을 감지해(어깨 z_b 비교: 카메라에 가까운 어깨가 사용자의 어느 쪽인지) 가까운 쪽 관절로 L/R 을 바꿔 계산하거나, (b) mean/minside/maxside·std/range 변형으로 재적합해 대체한다. v0 에서는 `mirror_safe=true` 규칙을 우선 활성.

## 8. 촬영 가이드 (앱 UX 로 강제)
- 위치: **정면 ~ 전방 45°** (B/C/D). 후방에서는 AUC −0.13. 무릎 모임(valgus) 규칙은 정면에서만.
- 프레이밍: 머리~발이 모두 들어오게(랜드마크 0, 27/28, 31/32 visibility 체크), 폰 수평(IMU), 허리 높이 거치, 3초 카운트다운.
- 순수 측면(시상면) 뷰는 데이터셋에 없어 **미검증** — 측면 촬영을 지원하려면 자체 데이터로 별도 검증.

## 9. 보정·검증 절차 (출시 전 필수)
1. 자체 촬영 셋: 종목당 ≥ 30세트(정상/위반 균형), 폰 1대, §8 가이드대로. 코치 2명 독립 라벨 → 일치도(κ) 먼저 측정.
2. 앱에서 §5 피처 + §6 집계를 **그대로 로그**(프레임 피처 원본 포함) → 오프라인에서 `fit_rule_cv` 동일 절차로 임계값 재적합(Youden). 피처·부호는 유지, 임계값만 바꾸는 것이 1차.
3. 모델(lite/full)·해상도·거리 변경 시 2 반복. 사용자별 기준선(첫 세트 캘리브레이션 대비 편차)은 v1.
4. 허용 기준 예: 조건별 균형정확도 ≥ 0.80, 오탐률 ≤ 10% 미만이면 ship 유지, 아니면 beta 로 강등.

## 10. 알려진 한계
- 라벨은 연기된 오류(스튜디오, 무부하/경부하) — 실중량 피로 오류 분포와 다를 수 있음.
- 종목당 ≤60클립으로 재적합(AUC 표준오차 ≈ ±0.05). 바닥/누운 종목, 손목각·어깨 으쓱·뒤꿈치·시계열 동시성·경추는 exclude.
- 실시간(프레임 단위) 판정 미검증 — v0 는 세트 종료 후 리포트.

## 11. Kotlin 스케치
```kotlin
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm get() = kotlin.math.sqrt(this dot this)
    fun unit(): Vec3? = if (norm < 1e-6f) null else this * (1f / norm)
}
fun mid(a: Vec3, b: Vec3) = (a + b) * 0.5f
fun angle3(a: Vec3, b: Vec3, c: Vec3): Float? {          // 도(deg), b 꼭짓점
    val u = (a - b).unit() ?: return null; val w = (c - b).unit() ?: return null
    return Math.toDegrees(kotlin.math.acos((u dot w).coerceIn(-1f, 1f)).toDouble()).toFloat()
}
fun angleVec(u: Vec3, w: Vec3): Float? { val a = u.unit() ?: return null; val b = w.unit() ?: return null
    return Math.toDegrees(kotlin.math.acos((a dot b).coerceIn(-1f, 1f)).toDouble()).toFloat() }

/** MediaPipe worldLandmarks(m, y아래+) → cm, y위+ */
fun toCm(p: Landmark) = Vec3(p.x() * 100f, -p.y() * 100f, -p.z() * 100f)

class BodyFrame(lHip: Vec3, rHip: Vec3) {
    val origin = mid(lHip, rHip)
    val xb: Vec3 = Vec3(lHip.x - rHip.x, 0f, lHip.z - rHip.z).unit() ?: Vec3(1f, 0f, 0f)
    val yb = Vec3(0f, 1f, 0f)
    val zb: Vec3 = (xb cross yb).unit() ?: Vec3(0f, 0f, 1f)
    fun body(p: Vec3): Vec3 { val d = p - origin; return Vec3(d dot xb, d dot yb, d dot zb) }
}

/** 한 프레임의 24관절(매핑 완료, NaN 가능) → 필요한 피처만 계산. null = 해당 프레임 유보 */
class FrameFeatures(j: Map<String, Vec3?>) {
    private fun g(n: String) = j[n]
    val hipMid = mid(g("LHip")!!, g("RHip")!!)          // 골반은 필수 (없으면 프레임 스킵)
    val neck = mid(g("LShoulder")!!, g("RShoulder")!!)
    val frame = BodyFrame(g("LHip")!!, g("RHip")!!)
    val torsoLen = (neck - hipMid).norm.takeIf { it >= 20f }
    val kneeL = g("LKnee")?.let { k -> g("LHip")?.let { h -> g("LAnkle")?.let { a -> angle3(h, k, a) } } }
    val kneeR = g("RKnee")?.let { k -> g("RHip")?.let { h -> g("RAnkle")?.let { a -> angle3(h, k, a) } } }
    val kneeMean = if (kneeL != null && kneeR != null) (kneeL + kneeR) / 2 else null
    val kneeMinside = if (kneeL != null && kneeR != null) minOf(kneeL, kneeR) else null
    val torsoIncl = angleVec(neck - hipMid, frame.yb)
    val torsoPitch = frame.body(neck).let { Math.toDegrees(kotlin.math.atan2(it.z, it.y).toDouble()).toFloat() }
    val shoulderAsym = g("LShoulder")?.let { l -> g("RShoulder")?.let { r -> (l - r).norm.takeIf { it >= 15f }?.let { w -> (l.y - r.y) / w } } }
    fun kneeOut(side: Char): Float? {                    // + 외측, − valgus
        val hip = g("${side}Hip") ?: return null; val knee = g("${side}Knee") ?: return null; val ank = g("${side}Ankle") ?: return null
        val hb = frame.body(hip); val kb = frame.body(knee); val ab = frame.body(ank)
        val denom = hb.y - ab.y; if (kotlin.math.abs(denom) < 1e-3f) return null
        val t = (kb.y - ab.y) / denom; val expX = ab.x + t * (hb.x - ab.x)
        val leg = (hip - ank).norm; if (leg < 1e-3f) return null
        return (if (side == 'L') 1f else -1f) * (kb.x - expX) / leg
    }
    val kneeOutMean = kneeOut('L')?.let { l -> kneeOut('R')?.let { r -> (l + r) / 2 } }
    // ... features_used 의 나머지 패밀리는 rules_mp_v0.md 표의 식대로 동일 패턴
}

/** 세트 창 집계: NaN(null) 무시 */
class Agg { private val v = ArrayList<Float>()
    fun add(x: Float?) { if (x != null && !x.isNaN()) v.add(x) }
    val n get() = v.size
    fun stat(s: String): Float? = if (v.isEmpty()) null else when (s) {
        "mean" -> v.average().toFloat(); "min" -> v.min(); "max" -> v.max()
        "std" -> { val m = v.average(); kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size).toFloat() }
        "range" -> v.max() - v.min(); else -> null } }

data class Rule(val id: String, val exercise: String, val condition: String, val subtype: String?, val status: String,
                val base: String, val stat: String, val op: String, val threshold: Float, val view: String)
enum class Verdict { OK, VIOLATION, ABSTAIN }
fun evaluate(rule: Rule, aggs: Map<String, Agg>, minFrames: Int = 8): Verdict {
    val a = aggs[rule.base] ?: return Verdict.ABSTAIN
    if (a.n < minFrames) return Verdict.ABSTAIN
    val v = a.stat(rule.stat) ?: return Verdict.ABSTAIN
    val violated = if (rule.op == "<") v < rule.threshold else v > rule.threshold
    return if (violated) Verdict.VIOLATION else Verdict.OK
}
```

## 14. 재보정 툴체인 (§9 의 구현) — 세트 로그 → 코치 라벨 → 임계값 재적합
출시 전 §9 를 실제로 돌리기 위한 두 조각. 앱 쪽은 **새 파일만** 추가했고(랩 화면 수정 없음), 연구 쪽은 로그를 읽어 규칙 JSON 을 갱신한다.

### 14-1. 앱: `PostureSetLog.kt` — 세트 로그 작성기 (JSON Lines, 스키마 `trex.posture.setlog/1`)
- `SetLog.build(exercise, samples, results, rulesVersion, model, delegate, frontCamera, sampleIntervalMs, subjectId?, note?)`
  → 기록 구간의 **프레임 피처 원본**(집계 전) + 가시성 33개 + 추론 ms + 규칙 판정을 담는다. 집계 창 정의를 나중에 바꿔도 재계산 가능.
- `SetLogStore(context).append(log)` → `<externalFilesDir>/posture_logs/sets-yyyyMMdd.jsonl` (권한 불필요, `adb pull`/공유로 회수). `totalSets()/clear()`.
- org.json 을 쓰지 않는 직접 직렬화(NaN/Inf → null, 로케일 무관 숫자, 문자열 이스케이프). 테스트: `PostureSetLogTest` 3개.
- **랩 화면 연결(3줄, 미적용 — 랩 화면은 수정하지 않았음)**: RESULT 로 넘어가며 `results` 를 만든 직후
  ```kotlin
  val log = SetLog.build(exercise, recordedSamples, results, ruleSet.version, poseModel.label, stats?.delegate ?: "-",
                         useFrontCamera, policy.sampleIntervalMs, subjectId = null)
  SetLogStore(context).append(log)   // IO 스레드 권장
  ```
  + 설정 토글("세트 로그 저장")과 저장 건수/지우기 표시. `subject_id` 는 재보정 시 GroupKFold 그룹이므로 같은 사람이면 같은 값을 넣을 것.

### 14-2. 연구: `calibrate_from_logs.py`
```bash
python calibrate_from_logs.py --logs <posture_logs 폴더 또는 *.jsonl> --labels labels.csv --rules rules/rules_mp_v0.json --out outputs/calib --suggest
```
- `labels.csv`: `set_id, condition, value[, subtype, subject_id]` — value 1/정상 = 조건 충족, 0/위반 = 위반 (AIHub 와 동일 의미). 척추처럼 하위유형이 있는 조건은 위반 세트에 `subtype`(flexion/lateral/…) 기재.
- 규칙마다: 앱과 동일한 집계(mean/min/max/std/range, NaN 무시) → 세트 ≥30·클래스당 ≥8 이면 **피처·방향 고정, Youden J 임계값만 재적합**. 수행자 ≥2 이면 GroupKFold, 아니면 StratifiedKFold(+경고). CV AUC < 0.70 → `feature_weak`, `--suggest` 시 같은 패밀리 화이트리스트 안 대안 피처 제안. 임계값 이동이 표본 표준편차 1배 초과면 `threshold_shift` 경고.
- 출력: `rules_calibrated.json`(version `+calib-YYYYMMDD`, 규칙별 `calibration{n_sets,n_pos,n_neg,n_subjects,method,cv_auc,cv_balacc,warnings,suggested_feature}`), `calibration_report.md/.csv`. 데이터 부족 규칙은 이전 임계값 유지 + 표시.
- **검증(데모)**: `demo_setlogs_from_aihub.py` 가 실험 A 의 MediaPipe 결과(정면 뷰 C, 4종목 × 60클립, 수행자 59명)를 같은 스키마의 로그+라벨로 변환 → 툴 실행 시 236세트 / 14규칙 재보정, 강한 규칙은 임계값이 기존과 근접(예: 스쿼트 valgus 동일, 고개 정면 77.5→76.7), 약한 규칙은 경고+대안 제안 — 파이프라인 엔드투엔드 동작 확인.

## 12. 파일
- `rules/rules_mp_v0.json` (앱이 읽을 정본), `rules/rules_mp_v0.md` (사람용 표, 피처 공식 표 포함), `export_rules_mp.py` (재생성)
- 실험 코드/요약: `experiment_a.py`, `experiment_a_refit.py`, `outputs/experiment_a_summary.md`, `outputs/expA_refit_summary.md`

## 13. 앱 구현 현황 (2026-08-22)
구현 위치: `app/src/main/java/com/example/trex_kotlin/posture/`
| 파일 | 역할 |
|---|---|
| `PostureCore.kt` | Vec3/기하, 24관절 매핑 상수, `PoseFrame(joints, up).features()` (§3~§5), `FeatureAggregator` (§6) |
| `PostureOrientation.kt` | `GravityTracker`(TYPE_GRAVITY 구독), `gravityUpInWorld()` — IMU 중력축 → world up (§4) |
| `InferencePolicy.kt` | 단계·열 상태 기반 추론 간격, `ThermalMonitor`(PowerManager 열 상태 구독) (§2-1) |
| `PostureRules.kt` | `rules_mp_v0.json` 로더, `PostureRule.isViolated`, `PostureRuleSet.evaluate` (§7) |
| `PostureAnalyzer.kt` | MediaPipe Pose Landmarker(VIDEO) 래퍼, ImageProxy→회전보정→월드 피처, 오버레이용 연결선 |
| `PostureLabScreen.kt` | 실험 화면(카메라+골격 오버레이+종목 선택+세트 기록+판정 리포트) |
에셋: `app/src/main/assets/posture/pose_landmarker_full.task`, `rules_mp_v0.json` (`noCompress += "task"`).
진입: 로그인 화면의 **"자세 교정 실험실 (개발용)"** 버튼 → `TrexApp` 의 `postureLab` 라우트.

**검증 테스트**: `app/src/test/java/.../PostureCoreParityTest.kt` (6개)
1. **파리티** — 연구 코드(features.py)로 계산한 40프레임 × 129피처를 같은 입력에서 Kotlin 결과와 비교(각도 0.05° / 상대 1e-3). 픽스처: `export_port_fixture.py`
2. **회전 불변성** — 장면(관절)과 up 을 같은 회전(롤 30° / 피치 20° / 복합 47°)으로 돌리면 모든 피처가 불변. IMU up 일반화가 옳다는 근거
3. **중력 매핑** — `gravityUpInWorld` 의 세로 거치 / 롤 90° / 평평히 눕힘 / 전면·후면 / 디스플레이 회전 케이스
4. 기울기 각도, 5. 집계 통계, 6. 규칙 위반 방향
```bash
./gradlew :app:testDebugUnitTest --tests "com.example.trex_kotlin.posture.*"
```

**실기기 확인**(Galaxy, 전면 카메라): 검출 O, 가시 22/33, 추론 147~245 ms/프레임(720×960), 골격 오버레이 정렬,
규칙 로드/실시간 값/세트 기록(18프레임)/판정 리포트 정상. 추론이 150 ms 대이므로 실시간 프레임 판정은 무리이고
§6 의 2~4 fps 샘플링 + 세트 종료 후 리포트 구조가 적절하다.

**남은 한계**: 임계값은 여전히 AIHub 스튜디오 분포 기준 — §9 재보정 전에는 위반/정상 판정을 신뢰하지 말 것.
또한 AIHub GT 3D 자체가 리그(스튜디오) 기준이라 up 이 곧 화면 세로축이었으므로, IMU up 은 **앱 쪽 정확도만 개선**한다.
재보정 데이터를 모을 때는 폰 기울기(`tiltFromScreenUpDegrees`)도 함께 기록해 두면 축 보정 효과를 사후 검증할 수 있다.
