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

`gravityUpInWorld(g, displayRotation, isFront)` = `−normalize( (g·imageRight, g·displayUp, g·towardCamera) )`, **g 는 아래 방향 중력**.
센서를 못 쓰면 `SCREEN_UP=(0,1,0)` 로 폴백하고 UI 에 그 사실을 표시한다.

**센서 부호 규약(버그 이력, 2026-08-23 실기기 로그로 발견)**: Android `TYPE_GRAVITY`/`TYPE_ACCELEROMETER` 는 정지 시 **반작용(위) 벡터**를 보고한다 —
기기를 화면 위로 평평히 놓으면 z=+9.81. 초기 구현은 이를 아래 방향으로 가정해 up 이 180° 뒤집혔다(세운 폰에서 tilt 175°, 70° 젖힌 폰에서 106~111°;
높이·수직 피처 전부 부호 반전 — 예: 귀-어깨 간격 −0.34 vs AIHub +0.36). 수정: `sensorGravityToDown()` 으로 센서 값을 뒤집어 아래 방향으로 만든 뒤 사용.
**자가검증 안전장치** `checkUpSanity(joints, up)`: 서 있는 자세에서 (HipMid−AnkleMid)·up < −30cm(다리 미검출 시 (EarMid−ShMid)·up < −6cm)이면 뒤집힘으로 보고 −up 으로 보정(`PoseSample.upFlipped`),
높이차가 작아 판단 불가(누운 자세 등)면 `upVerified=false` 로만 표시하고 보정하지 않는다. 세트 로그에 `up_flipped_frames / up_verified_frames` 기록.
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
- **랩 화면 연결(적용됨, `PostureLabScreen.kt` 추가형 편집)**: RECORDING 중 검출 프레임을 `recordedSamples/recordedTimesMs` 에 모으고(분석 스레드, synchronized),
  "세트 종료" 시 `SetLog.build(...)` → `SetLogStore.append` 를 분석 스레드(`executor`)에서 수행. 컨트롤 행: **로그 저장 ON/OFF**(기본 ON, rememberSaveable) · 누적 N세트 · **내보내기**(공유 시트) · **지우기**.
  내보내기는 `PostureSetLogExport.share()` — `FileProvider`(`${applicationId}.fileprovider`, `res/xml/file_paths.xml`) 로 `posture_logs/*.jsonl` 을 ACTION_SEND(_MULTIPLE) 로 전달 → 드라이브/메신저/PC 로 바로 회수.
  `subject_id` 는 아직 UI 가 없어 null — 재보정 시 `labels.csv` 의 `subject_id` 컬럼으로 보완(같은 사람이면 같은 값). 기록 시각은 실제 추론 시각(`t_ms`, 열 감속 반영)으로 남는다.

### 14-2. 연구: `calibrate_from_logs.py`
```bash
python calibrate_from_logs.py --logs <posture_logs 폴더 또는 *.jsonl> --labels labels.csv --rules rules/rules_mp_v0.json --out outputs/calib --suggest
```
- `labels.csv`: `set_id, condition, value[, subtype, subject_id]` — value 1/정상 = 조건 충족, 0/위반 = 위반 (AIHub 와 동일 의미). 척추처럼 하위유형이 있는 조건은 위반 세트에 `subtype`(flexion/lateral/…) 기재.
- 규칙마다: 앱과 동일한 집계(mean/min/max/std/range, NaN 무시) → 세트 ≥30·클래스당 ≥8 이면 **피처·방향 고정, Youden J 임계값만 재적합**. 수행자 ≥2 이면 GroupKFold, 아니면 StratifiedKFold(+경고). CV AUC < 0.70 → `feature_weak`, `--suggest` 시 같은 패밀리 화이트리스트 안 대안 피처 제안. 임계값 이동이 표본 표준편차 1배 초과면 `threshold_shift` 경고.
- 출력: `rules_calibrated.json`(version `+calib-YYYYMMDD`, 규칙별 `calibration{n_sets,n_pos,n_neg,n_subjects,method,cv_auc,cv_balacc,warnings,suggested_feature}`), `calibration_report.md/.csv`. 데이터 부족 규칙은 이전 임계값 유지 + 표시.
- **검증(데모)**: `demo_setlogs_from_aihub.py` 가 실험 A 의 MediaPipe 결과(정면 뷰 C, 4종목 × 60클립, 수행자 59명)를 같은 스키마의 로그+라벨로 변환 → 툴 실행 시 236세트 / 14규칙 재보정, 강한 규칙은 임계값이 기존과 근접(예: 스쿼트 valgus 동일, 고개 정면 77.5→76.7), 약한 규칙은 경고+대안 제안 — 파이프라인 엔드투엔드 동작 확인.

## 15. 룰엔진 v1 탐색 결과 (`rule_engine_v1.py`, GT 3D · MP 가능·미러 불변 화이트리스트 · 수행자 GroupKFold)
| 가설 | 결과 | 결정 |
|---|---|---|
| (A) 2-피처 규칙(깊이-2 트리, 폴드별 상위 6피처 쌍 탐색)이 단일 규칙보다 낫다 | 단일 0.860 → 2-피처 0.842 (Δ 중앙값 −0.020; 개선 ≥+0.03 3개 / 악화 24개). GBM 우세 조건(단일<0.80, GBM≥0.90, n=7)에서도 Δ −0.007 | **채택 안 함.** GBM 의 우위는 2-피처 상호작용이 아니라 다수 피처 조합에서 나옴. 단일 규칙 유지 |
| (B) 개인 기준선 오프셋(수행자 '정상' 앞 3세트 중앙값을 뺀 값) | 전체 Δ 중앙값 +0.001(115조건). 그러나 **level 피처**(ear_shoulder_gap +0.044, neck_over_ankle +0.029, head_pitch +0.028, palm_h_rel +0.026, sh_over_hip_fwd +0.021, torso_incl +0.015)는 개선, **변동성 피처**(knee std/range 등)는 악화(−0.03~−0.06) | **조건부 채택.** `personal_baseline.eligible=true`(gain ≥ 0.02 이고 stat ∈ mean/min/max) 규칙에만 사용자 기준선 보정 적용, std/range 는 보정 금지. JSON 에 주석으로 포함(`personal_baseline{gt_raw_auc, gt_adjusted_auc, gain, eligible}`) |

앱 반영(다음 단계): 첫 세트를 "기준선 세트(정상 자세)"로 표시하면 eligible 규칙의 값에서 사용자 기준선(해당 피처의 세트 통계 중앙값)을 빼고 임계값과 비교 — 임계값도 기준선-상대 스케일로 재보정해야 하므로 §9 재보정과 함께 진행.

## 16. 개인화 실험 4종 결과 (`personalization_experiments.py`, GT 3D · 수행자 홀드아웃 · 규칙 132개 · 수행자 평균 38명/규칙)
"사용자별 임계값"을 앱에 넣기 전에 결판낸 네 가지.

| 실험 | 결과 | 결정 |
|---|---|---|
| 1. 개인별 임계값 재적합(A) 오라클 상한 | 균형정확도: 인구 임계값 0.778 = **정직한 개인 분할반 0.778 (Δ +0.002, 개선 25 vs 악화 28)**, 인샘플 오라클 0.886(과적합). 수행자 내 AUC 0.894 vs 섞인 pooled 0.858 | **(A) 기본 경로에서 제외.** 개인차는 실재하나 사용자 1명의 라벨 규모(클래스당 5~10세트)로는 노이즈를 못 이김 |
| 2. 임계값 분산 분해 | ICC 중앙값 **0.66** (≥0.5 규칙 106/130): 개인 간 임계값 SD 0.53σ vs 부트스트랩 노이즈 0.28σ. ICC 높은 패밀리 = grip_w·torso_incl·ear_shoulder_gap·sh_over_hip_fwd·palm_fwd_hip(level) | 개인차가 큰 것은 **level 피처** → 라벨 없는 기준선 정규화(B)가 같은 정보를 잡는다 |
| 3. 체형 조건화(라벨 0) vs raw vs 기준선(정상 3클립) | 피처~체형 R² 중앙값 **0.08**(≥0.2 는 12/132); 균형정확도 raw 0.780 / 체형 0.769 / 기준선 0.784; Δ(체형) −0.006, Δ(기준선, 전 규칙) −0.003 | **체형 조건화 기각**(이 인구의 체형 비율 범위가 좁고 피처 수준을 설명 못함 — 앞서 "1층"으로 제안했던 것을 철회). 기준선은 전역 적용 중립 → `personal_baseline.eligible` 규칙(12개)에만 |
| 4. 기준선 오염 | 규칙별 Δ 중앙값: 1개 오염 AUC +0.001(중앙값 기준선이 흡수), 2개 오염 AUC −0.006 / 균형정확도 −0.014 (수준 중앙값으로는 0.853→0.837 / 0.789→0.757). 인구 규칙 가드: 거부 과다(규칙당 45~69클립, 기준선 불가 수행자 10~27명), 2개 오염에선 수준 중앙값이 오히려 악화(0.837→0.814) | 가드는 그대로 못 씀. **기준선 5세트 중앙값**(2개 오염 허용) + 심한 이탈만 거부하는 느슨한 가드 + 수집 UX(정자세 안내) |

앱 설계 함의: 기본 = 인구 임계값(§9 재보정은 **여러 사용자** 로그로) / eligible 규칙만 사용자 기준선(5세트 중앙값) 정규화 / (A)·체형 조건화는 로드맵에서 제외 /
"내 자세로 임계값 맞추기"는 정확도 향상이 아니라 **파이프라인 점검**과 단일 사용자 앱의 불가피한 선택으로만 의미가 있다.

## 17. 촬영 프로토콜 — "어떤 운동을 몇 세트" (`calibration_protocol.py`, 실험 5·6)
| 목적 | 필요한 것 | 근거 |
|---|---|---|
| **임계값 재보정**(인구) | 종목당 **최소 12 / 실용 30 / 권장 40세트**, **여러 사람(최소 3, 권장 6명+)**, 조건별 라벨 | 표본 크기 곡선: 상한 대비 회복률 12세트 95.5% → 30세트 97.9% → 40세트 98.2%(이후 수익 체감). 세트를 늘리는 주 효과는 평균보다 **재보정 안정성**(반복 SD 0.060→0.042→0.039) |
| **개인 기준선**(개인화) | eligible 규칙 보유 6종목만, 사용자당 **정자세 3세트**(라벨·오류 세트 불필요) | k 곡선: 없음 0.761 → k=1 0.792 → **k=3 0.809** 포화. 전 규칙 적용 시 이득 소멸 → eligible 규칙 전용 |

- **설계**: 세트마다 조건별로 정상/위반을 **무작위 절반씩** 배정(여러 조건 동시 위반 허용, AIHub 완전요인과 동일). 조건이 몇 개든 총 세트 수는 같다 — 한 세트가 그 종목 모든 조건에 라벨을 주기 때문. 한 번에 한 조건만 틀리는 설계는 조건 수만큼 세트가 곱해져 비효율.
- **부하 분할**: 세트당 3~4렙 → 30세트 = 90~120렙. 바벨/덤벨 20세트·기구 25세트·맨몸 40세트를 세션 상한으로 나눌 것(피로로 자세가 무너지면 라벨이 오염된다).
- **단계**: ① 파이프라인 점검 스쿼트 12세트(9분, 정확도 주장 불가) → ② 최소 유효 3종목(스쿼트·OHP·데드) 90세트(1시간 7분, 6세션) → ③ 실사용 8종목 240세트(3시간, 15세션).
- **인원 > 세트**: 개인 임계값의 정직한 이득이 +0.002(§16)이므로 같은 총량이면 **1명 × 90세트보다 6명 × 15세트**.
- 전체 표·라벨 CSV 작성법: `outputs/CALIBRATION_PROTOCOL.md` (자동 생성).

## 18. 기준선 설정 UI (앱 구현) — 운동 목록 + 세트 가이드
§15~§17 의 결론을 앱에 넣은 것. 진입: 로그인 화면 "자세 기준선 설정 (정자세 3세트)" → `TrexApp` 의 `baselineGuide` 라우트 → `BaselineGuideScreen`.

| 파일 | 역할 |
|---|---|
| `posture/PostureBaseline.kt` | `ExerciseBaseline`(종목 → feature → 중앙값, 세트값, k, 생성시각) · `BaselineProfile` · `BaselineCollector`(세트별 집계값 수집 → 중앙값) · `BaselineStore`(`filesDir/posture_baseline.tsv`, org.json 비의존) |
| `posture/PostureRules.kt` | `PostureRule.baselineEligible / baselineThresholdRel / baselineK / baselineGain`(JSON `personal_baseline` 파싱), `supportsBaseline`, `isViolatedRelative`, `PostureRuleSet.baselineExercises / baselineRulesFor / baselineFeaturesFor / baselineSetsFor`, **`evaluate(..., baseline)`** — 기준선이 있고 규칙이 supportsBaseline 이면 (값 − 기준선) 을 `threshold_rel` 과 비교, `RuleResult.baselineApplied/rawValue` |
| `posture/BaselineGuideScreen.kt` | ① **목록**: `baselineExercises`(JSON 기준 7종목)만 — 종목별 기준선 규칙·권장 뷰·설정 여부(세트 수·날짜·값)·초기화. ② **가이드/촬영**: 진행 칩(세트 1/2/3), 안내(정자세 3~4렙·권장 뷰·전신·세로 거치), 카메라+골격 오버레이, 세트 시작/종료 → 세트 값 확인(REVIEW: 프레임 부족·값 계산 불가·인구 기준 위반 경고는 **안내만, 강제 거부 없음** §16) → 저장/다시 → k세트 완료 시 중앙값 기준선 표시 → 기준선 저장. 세트 로그도 `note=baseline i/k` 로 남김(재보정용) |
| `PostureLabScreen.kt` | 세트 종료 시 `BaselineStore.load().valuesFor(exercise)` 를 `evaluate` 에 전달 (추가형 2줄) |
| 규칙 JSON | `personal_baseline.threshold_rel`(`baseline_thresholds.py`, GT 3D 에서 수행자별 정상 앞 3클립 기준선으로 Youden 적합; eligible 12규칙, AUC 0.82~0.99), `k=3` |

검증: `PostureBaselineTest`(수집 중앙값·희소 피처 제외, 집계기→세트값, 저장소 왕복, 기준선 적용 평가 4케이스) 포함 posture 유닛 테스트 통과.
한계: `threshold_rel` 은 GT 3D 기준 — MP 스케일 차이는 앱 세트 로그(note=baseline)와 §9 재보정으로 맞출 것. `subject_id` 입력 UI 는 아직 없음.

## 19. 개인화 이득의 출처와 앱 격차 (`personalization_gap.py`)
§18 에서 넣은 기준선 기능이 실제로 얼마나 들을지 — AIHub 에서 잰 이득이 어디서 온 것인지 분해했다. 결론: **세 계층에서 격차가 생기고, 그중 둘은 앱에서 이득을 깎는 방향**이다.

| 계층 | 측정 | 앱에서의 함의 |
|---|---|---|
| **① 세션(같은 사람, 다른 날)** | 분산 분해: person 45%(eligible 73%), day 13%(근거 ≥10명 규칙 7개). **세션 전이 실험**: same-day 기준선 이득 **+0.009** → **cross-day 이득 −0.009**(6규칙, 수행자 중앙값 29명). 같은 사람의 day A↔B 기준선 차이 절대 중앙값 2.6(각도 기준), SD 5.2 | 앱은 **항상 cross-day**(기준선 찍은 날 ≠ 사용하는 날) → AIHub 이득의 상당분이 세션 효과일 수 있음. 다만 eligible 종목엔 multi-day 수행자가 0~2명이라 **직접 확인 불가**(하루에 몰아 촬영) |
| **② 측정(GT 3D → MediaPipe)** | eligible 피처의 MP 세트 단위 MAE / \|threshold_rel\| 중앙값 **1.12**(GT 기준선 잡음비 0.30). 최악은 덤벨 인클라인 `elbow_mean__mean` 3.62 | `threshold_rel` 은 GT 에서 적합 — bias 는 기준선을 빼며 상쇄되지만 **잡음이 판정 경계와 맞먹는다**. MP 스케일 재보정 전에는 이득을 기대하지 말 것 |
| **③ 모집단·프로토콜** | 연기된 오류(무·경부하), 체형 범위 좁음(대퇴/경골 0.91~1.07), 5뷰 고정 리그·통제 조명·타이트 복장, 16프레임 성긴 샘플링, 피트니스 모델 인구 | 앱은 실중량·자연 오류·단일 뷰·조밀 샘플링·일반 인구. §16 의 "체형 조건화 기각"도 이 좁은 범위 때문일 수 있음 |

**앱 반영**: 기준선 기능은 유지하되 (a) `RuleResult.rawValue/baselineApplied` 로 **절대·상대 판정을 모두 로그에 남겨** 실사용 데이터로 A/B 판정, (b) 기준선 세트는 `note=baseline` 이라 이후 세트와 날짜가 다르므로 **그 자체가 cross-day 실험**이 된다, (c) `threshold_rel` 을 MP 스케일로 재보정(§9 에 baseline-relative 모드 추가), (d) 세션 이동량을 고려해 기준선 **유효기간/재촬영 유도** 검토.

## 20. 체형 불변성 — 신체구조가 달라도 유지되는 것 (`invariance_analysis.py`)
**조작적 정의**: 수행자를 체형 지표(키·대퇴/경골·몸통/다리) 4분위로 나눠, *다른 3분위에서 학습한 임계값* 으로 남은 분위를 평가.
**귀무 대조군**: 같은 크기의 **무작위** 4그룹으로 20회 반복 — 분위당 수행자가 ~28명이라 편차는 표본 잡음만으로도 생기므로, **초과분(체형 편차 − 무작위 편차)** 만 체형 효과로 본다.

| 결과 | 값 |
|---|---|
| 체형 분위 편차 / 무작위 분위 편차 / **초과분** | 0.083 / 0.079 / **+0.004** (활성 규칙 62개 중앙값) |
| 초과분 ≤0.02 (체형 효과 없음) | **48/62 (77%)** |
| 체형 회귀 R² (정상 클립 수준 ~ 체형) | 중앙값 0.08 (≥0.2 는 2개) |
| 정상 클립 분산의 person 비중 | 중앙값 45% |
| 등급 | 완전 불변 20 · 체형 불변·개인차 있음 28 · 체형 의존 14 |

**핵심**: 각도·비율 피처는 설계상 스케일 불변이라 **체형이 달라도 임계값이 그대로 통한다**(초과분 +0.004). 사람마다 다른 45%는 체형이 아니라 **습관·수행 스타일**이다 — §16 에서 체형 조건화가 기각되고 기준선 정규화만 일부 통한 이유가 여기서 설명된다.

**통계별**: range −0.001 · max −0.006 · min +0.003 · mean +0.004 · std +0.009 → 변동성·극값 통계가 가장 안정. mean 은 체형엔 불변이나 person 비중 67% 로 습관 의존이 가장 큼(기준선 개인화 후보와 정확히 일치).
**패밀리별 완전 불변**: knee(무릎각) · knee_out(valgus) · torso_pitch · shoulder · sh_over_hip_fwd · face_vs_torso · palm_lat.
**체형 의존(초과분 큼)**: `grip_w`(+0.046, 그립 폭 — 어깨폭 정규화로도 남는 팔 길이 효과) · `torso_incl`(+0.046, 부호 없는 절대 기울기) · `face_vs_forward`(+0.030). 이 셋을 쓰는 규칙 14개는 재보정 시 체형 분위별 성능을 반드시 확인할 것.
**축별 민감도**: 키 +0.012 > 몸통/다리 +0.008 > 대퇴/경골 +0.005 — 비율보다 **절대 크기**에 조금 더 민감.

한계: AIHub 체형 범위가 좁다(대퇴/경골 0.91~1.07, 키 프록시 108~141cm). 더 넓은 인구에서는 체형 효과가 커질 수 있다.

## 21. 체형보존 알고리즘 — 정준 골격 리타게팅 (`canonical_retarget.py`)
**알고리즘**: 각 프레임 골격을 관절 **방향(단위벡터)** 과 **뼈 길이**로 분해 → 뼈 길이만 인구 중앙값(정준 체형)으로 바꿔 forward kinematics 로 재조립(루트=골반 중점, 머리는 강체로 단일 배율). 각도 피처는 정의상 불변(무릎각 최대 변화 0.02°), 위치·거리 피처는 "표준 체형 위에서의 자세"가 된다 — 기존 정규화(몸통 길이·어깨폭 나눗셈)가 못 지우는 **체절 간 비율 차이**(팔/몸통 등)까지 제거.

| 결과 (활성 규칙 62개, 값 바뀐 26개) | 원본 | 정준 | Δ |
|---|---|---|---|
| 수행자 홀드아웃 AUC 중앙값 | 0.868 | 0.867 | +0.002 |
| 체형 4분위 이식 초과분 | +0.006 | +0.006 | +0.002 |
| 체형 회귀 R² | 0.07 | 0.07 | 0 |

- 팔 길이에 의존하던 규칙은 고쳐진다: 행잉 레그 레이즈 `grip_w` 초과분 +0.062→**+0.001**, 페이스 풀 `grip_w` +0.089→+0.039, `stance_w` AUC 0.890→0.951, `palm_head_dist` 0.783→0.803.
- **전체 이득은 0** — 피처가 이미 각도·비율이라 남은 체형 효과가 거의 없었기 때문(§20). §20 의 '체형 의존' 규칙 중 `torso_incl`/`face_vs_forward` 는 각도라 리타게팅과 무관 → 그 의존은 체형이 아니라 **키와 상관된 수행 습관**이다.
- 구현 교훈: 머리(코·귀·눈)를 개별 뼈로 리타게팅하면 얼굴 방향이 왜곡돼 `head_pitch` 규칙이 −0.05 → **강체 처리 필수**.
- 앱 적용: MediaPipe world landmark 에 같은 리타게팅 가능(트리·정준 길이 JSON화). 단 AIHub 범위(대퇴/경골 0.91~1.07)에선 이득이 없으므로 **넓은 체형 인구 데이터가 생겼을 때** 켤 옵션으로 보관.

**문헌 근거**: 재활 평가 골격 정규화(흉-골반 뼈 단위길이 스케일·흉부 원점·회전 정렬; [rotation-invariant rehab assessment](https://www.researchgate.net/publication/371312818_A_Skeleton-based_Rehabilitation_Exercise_Assessment_System_with_Rotation_Invariance), [2D gait skeleton normalization](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9185346/)), [bone-length adjustment for 3D pose](https://arxiv.org/html/2410.20731v2), [skeleton-aware motion retargeting](https://link.springer.com/chapter/10.1007/978-3-031-92387-6_21), 보행 분석의 무차원 정규화([Hof 1996](https://www.semanticscholar.org/paper/Scaling-gait-data-to-body-size-Hof/356c4891c81e3633d22181d01c5eba7a29e14f19), [비교 연구](https://www.sciencedirect.com/science/article/abs/pii/S0167945709000165)). 체형이 스쿼트 운동학에 미치는 영향([FTR–무릎·발목 굴곡](https://www.sciencedirect.com/science/article/pii/S1728869X21000332), [요추골반 굴곡과 체형](https://ijspt.scholasticahq.com/article/122637-are-anthropometric-measures-range-of-motion-or-movement-control-tests-associated-with-lumbopelvic-flexion-during-barbell-back-squats)) — 대퇴가 길면 상체 숙임 *또는* 무릎 전방 이동으로 보상하므로 "긴 대퇴 = 나쁜 자세" 단순화는 틀림 → 체형보존 규칙은 **개인 전략 차이를 오류로 찍지 않는 것**이 핵심.

## 21. '올바르지 않은 자세' 의 정의 요건 (`definition_quality.py`, [DEFINITION_QUALITY.md](DEFINITION_QUALITY.md))
새 종목을 추가하거나 기존 조건을 고칠 때의 기준. 이론이 아니라 이 프로젝트에서 깨진 지점에서 역산했다.

| 요건 | 실측 근거 |
|---|---|
| **1. 한 조건 = 한 메커니즘** | 다중 방향이 섞인 조건 6개에서 하위유형 분리 이득 중앙값 **+0.132**(런지 0.765→0.978). 더 중요한 건 **방향별 검출률**: 통합 규칙은 다수 방향 90~98% 를 잡지만 **소수 방향(n<50)은 검출률 중앙값 8%**(스티프 데드 신전 **3%**, 굴곡 12%). AUC 0.86 이 "가장 흔한 방향만 잡는 상태"를 가린다 |
| **2. 관측 가능** | GT 3D 로도 AUC<0.75 인 조건 **26/62** — 정의가 관절 좌표에 없는 것(긴장·템포·숄더패킹)을 가리킨 경우. 각도 / 정규화 거리 / 시계열 통계로 표현되지 않으면 스코프 아웃 |
| **3. 라벨명 = 실제 편차** | 행잉레그 '어깨-귀 거리'=좁은 그립, OHP '전완 수직'=팔꿈치 내밀기, 페이스풀 '외회전'=팔꿈치 모으기. 규칙은 라벨명이 아니라 연기된 편차를 학습하므로 **피드백 문구는 후자 기준** |
| **4. 방향 특정** | 양방향 오류를 무부호 절대값으로 재면 한 방향만 잡힌다. **AUC 로는 검증 불가**(AIHub 가 한 방향만 연기해 오히려 AUC 가 높다) — 체형 초과분에서만 흔적: `torso_incl` +0.046 vs `torso_pitch` −0.009 |

**새 종목 정의 절차**: ① 실패 모드를 메커니즘 단위로 나열(상위어 금지) → ② 기하량으로 표현 가능한지 확인 → ③ 양방향이면 두 조건으로 분리하거나 부호 있는 피처 → ④ 통계 선택(자세=mean/min/max, 반동=std/range) → ⑤ **위반 재현 방법까지 문서화** → ⑥ 조건별 무작위 절반 배정 30세트 × 3~6명(§17) → ⑦ **검증 3종: 인구 AUC + 방향별 검출률 + 체형 분위 이식**. ⑦에서 AUC 만 보면 요건 1·4 실패를 놓친다.

**남은 한계**: '올바름' 은 코치 합의물(inter-rater 미측정) · 연기 오류 ≠ 자연 오류 · 부하 불가시 · 이진 판정에 심각도 없음.

## 22. 실시간 음성 코칭 — "어디가, 처음부터인지 점점인지" (`PostureCoach.kt`, [ERROR_ONSET.md](ERROR_ONSET.md))
| 구성 | 내용 |
|---|---|
| **원리** | 세트 **초반 창**(첫 8프레임)과 **최근 창**(마지막 8프레임)을 같은 규칙으로 따로 평가 → 둘 다 위반 = **HABIT**(처음부터), 초반 정상→최근 위반 = **DRIFT**(점점 흐트러짐), 위반→정상 = **RECOVERED**(교정됨). 근거: 8프레임 창 GroupKFold AUC **0.912 ≈ 전체 16프레임 0.903**(첫 5프레임 0.889) — 슬라이딩 창 판정이 성립. 300ms 샘플링이면 8프레임 ≈ 2.4초 ≈ 1렙 |
| `LiveCoach` | `onFrame(features)`(분석 스레드) → `evaluate(nowMs)`: 최근/초반 창을 `PostureRuleSet.evaluate(..., baseline)` 로 평가(개인 기준선 적용), 규칙별 `OnsetState`(early/recent verdict·값·kind). **발화 억제**: persistence(연속 2회 위반) · 규칙 쿨다운 12s · 전역 간격 4s · 한 번에 1문장(가장 오래 지속된 위반, 동률이면 AUC 높은 규칙). `summarize()`: 세트 종료 후 전반/후반 창 기준 규칙별 onset |
| `CoachCues` | 조건명(+척추 하위유형) → 한국어 문구 {bodyPart, habit, drift, recovered}. **라벨명이 아니라 연기된 편차 기준**(요건 3): 예 OHP '전완 지면과 수직' → "팔꿈치가 앞으로 벌어져 있어요", 행잉레그 '어깨-귀 거리' → 어깨 올라감. 43개 활성 조건 커버, 미등록은 조건명 폴백 |
| `SpeechCoach` | Android `TextToSpeech`(ko-KR, 비동기 초기화, 속도 1.05, QUEUE_FLUSH 로 최신 안내 우선). 한국어 음성 없으면 `ready=false` → 화면 배너만 |
| 랩 화면 | 세트 시작 시 `LiveCoach` 생성(종목·규칙·기준선 고정) → 기록 중 프레임마다 `onFrame`+`evaluate` → 배너(처음부터/점점/교정됨 색 구분 + 문구 + 현재 창 카운트) + 음성. "음성 코칭 ON/OFF" 토글. 세트 종료 시 `summarize()` → 리포트에 **"세트 내 변화(전반→후반)"** 블록: 규칙별 처음부터/점점/교정됨 + 값 변화 |
| 테스트 | `PostureCoachTest` 5개: HABIT(persistence·쿨다운), DRIFT(초반 정상→최근 위반, 요약 일치), RECOVERED(1회), 후보 선택·전역 간격, 문구 카탈로그 커버리지·하위유형·폴백 |

한계(정직하게): **임계값은 습관형(AIHub)으로 보정된 값**이라 DRIFT 도 같은 임계값을 쓴다 — 피로형 전용 임계값은 실측 로그 후. 순간 붕괴(삐끗)는 2~4fps 샘플링에서 놓칠 수 있어 이번 범위에서 제외. "왜 틀렸는가"(피로 vs 습관)는 좌표만으로는 추정이며, 세트 번호·렙 수·부하 같은 맥락과 합쳐야 신뢰도가 오른다. 음성 안내는 오탐이 나면 사용자를 잘못 교정시키므로 §9 재보정 전까지는 랩(개발용)에서만.

## 23. 관측 가능성 목록 — 무엇이 보이고 무엇이 안 보이나 (`observability_inventory.py`, [OBSERVABILITY.md](OBSERVABILITY.md))
AIHub 조건 132개를 해부학적 자유도로 분류해, 완벽한 GT 3D 상한과 MediaPipe 전이 후를 비교했다.

| 자유도 | 조건 | GT(상한) | MP | ship 비율 | 판정 |
|---|---|---|---|---|---|
| 척추(중립 조건 판정) | 20 | 0.865 | 0.938 | 55% | ✅ **프록시로** |
| 굴곡·신전 (관절각) | 39 | 0.873 | 0.877 | 54% | ✅ |
| 머리·시선 | 6 | 0.882 | 0.880 | 50% | ✅ |
| 발·접지 | 2 | 0.924 | 0.761 | 50% | △ 깊이 의존 |
| 외전·내전 (무릎 내/외, 손 위치) | 24 | 0.873 | 0.901 | 42% | ✅ |
| 몸통 정렬 | 5 | 0.900 | 0.820 | 40% | ✅ |
| **긴장·부하** | 13 | 0.670 | 0.756 | 15% | ❌ 원리적 불가 |
| **견갑·골반** | 9 | 0.821 | 0.731 | 11% | ⚠ MP 붕괴 |
| **축회전** | 10 | 0.826 | 0.647 | **0%** | ⚠ MP 붕괴 (GT 값은 프록시라 부풀려짐) |

### 척추는 두 가지를 구분해야 한다 (중요)
- **척추 중립 *조건 판정*: 잘 된다.** 활성 규칙 21개(ship 17), MP AUC 0.76~0.98. 하위유형별로 lateral 0.97 · forward_lean 0.96 · flexion 0.88 · lumbar_swing 0.88.
- **척추 곡률을 *각도로 측정*: 안 된다.** MediaPipe 33점에 척추 중간 랜드마크가 없다. AIHub GT 의 Back/Waist 기반 `spine_*` 피처를 쓴 규칙 6개는 **전부 exclude**(GT AUC 0.546~0.730).
- 즉 앱은 **동반 증상 프록시**로 판정한다: 측굴→`shoulder_asym`/`hand_h_asym`, 굴곡→`head_pitch`/`sh_over_hip_fwd`/`ear_shoulder_gap`, 앞숙임→`torso_incl`/`torso_pitch`. 출시된 척추 규칙 중 `spine_*` 를 쓰는 것은 **0개**.
- 검증: flexion 위반에서 프록시 AUC 0.73~0.90 > GT 곡률 지표 AUC 0.64~0.76. 둘의 상관은 |r|<0.35 로 약해 **서로 다른 것을 재고 있다**. 잔여 위험 — 곡률은 큰데 프록시가 정상인 케이스 **3~14%**(연기 오류 기준; 자연 오류에서는 다를 수 있음).

**뷰 의존**: 무릎 내/외(valgus)는 **정면 필수** — 스쿼트 MP AUC 정면 0.993 vs 전방사선 0.79~0.82, 데드리프트 0.909 vs 0.51~0.67. 사선 뷰에서는 **유보(ABSTAIN)** 가 오탐보다 낫다.

**설계 함의**: 판정 가능한 것은 **관절이 얼마나 굽었나 · 사지가 어디에 있나 · 몸통이 어디를 향하나 · 얼마나 흔들리나** 넷. "힘주세요/견갑 고정" 같은 지시는 검증 불가. 축회전이 핵심인 종목은 회전 대신 그 결과인 위치 변화로 조건을 재정의할 것(§21 요건 3).

> **정정 이력**: 이 절의 초판은 `subtype==""` 필터로 척추 조건 45행을 통째로 누락해 "척추 곡률 ship 0/4, 원리적 관측 불가"라고 적었다. 실제로 그 4개는 주변부 조건(등 아치·허리 휨)이었고, 본 척추 조건 20개는 ship 11/20 이다. 조건 단위 대표값(하위유형 중 최고)으로 재집계해 수정했다.

## 24. 양방향 검출 — 반대측 가드 (`bidirectional_analysis.py` → `add_opposite_guards.py`, [BIDIRECTIONAL.md](BIDIRECTIONAL.md))

질문: "스쿼트에서 무릎이 **안쪽**뿐 아니라 **바깥**으로 벌어져도 잡을 수 있나?" — AIHub 라벨은 종목당 한 방향만 연기했으므로(스쿼트 무릎 바깥 클립 0개) 기존 규칙은 단방향이다.

**근거 3종** (모두 `bidirectional_analysis.py`):
1. **부호 분리** — 서서 하는 전 종목에서 knee_out 부호가 방향을 가른다: 발끝 안쪽 위반 −0.030 vs 바깥쪽 위반 +0.044 vs 정자세 그 사이. 좌/우(head_yaw 90%), 상/하(head_pitch·face_vs_torso 78%), 기울기(shoulder_asym 69~76%)도 중앙값 분리로 방향 판별 가능.
2. **가드 방식 검증** — 양방향 라벨이 있는 조건(고개 좌/우/상/하, 좌우 손 높이)에서 "정상 분포 반대측 경계(med±2.5·MAD)" 가드의 수행자-홀드아웃 recall: hand_h_asym 96/93%, head_yaw 97/98% (FPR 6.5~8%), shoulder_asym 66/61%, head_pitch 38/59%. **foot_open(발끝 방향) 5/25% — 가드 불가**(정상 발각도 분산이 큼).
3. **주입** — 검증 통과 피처를 쓰는 활성 규칙 7개에 `opposite_guard {op, threshold, desc, method, n_norm, validated}` 를 MP 스케일(규칙의 view_best_front 정상 클립)로 주입. 앱 주입은 med±**3.0**·MAD 로 검증(2.5)보다 보수화 — 음성 안내라 오탐을 눌렀다. `validated=false`(런지 상체 앞숙임, 딥스 고개 숙임)는 그 방향 라벨이 없어 **오탐률만 통제, 검출률 미보증**.

**앱 동작** (`PostureRules.kt`/`PostureCoach.kt`): 기본 방향 정상 && 원값이 가드 초과 → `VIOLATION(direction=OPPOSITE)`. 가드는 모집단 경계이므로 개인 기준선 보정 없이 **원값**으로 판정. 코칭 문구는 반대측 카탈로그(무릎 "바깥으로 벌어져 있어요", 고개 "젖혀져", 시선 "아래로", 상체 "앞으로 숙여져") → 없으면 guard.desc 폴백. UI 는 "위반(반대측)" 태그.

**한계**: (1) lateral(좌/우 기울기) 규칙은 std/range 라 이미 양방향 — 가드 대신 **방향 명명**이 과제인데 좌/우 판별 71~76%는 음성으로 단정하기 위험해 이번엔 미명명("옆으로"). head_yaw 90%는 명명 후보. (2) 발끝 안/바깥은 knee_out 프록시로만 커버 — 전용 foot_open 피처는 검증 실패로 보류. (3) 반대측 임계값도 AIHub 분포 기준 — §9 재보정 대상에 포함할 것.

## 25. 바닥 운동 — 2D 평면 경로 (`floor_2d_rules.py`, [FLOOR_2D.md](FLOOR_2D.md))
바닥 종목 9개(크런치·푸시업·니푸쉬업·플랭크·라잉 레그 레이즈·힙쓰러스트·시저크로스·바이시클 크런치·Y-Exercise)는 3D GT 불량(73~92%)으로 전부 제외돼 있었다. 원인을 다시 보니 **촬영 리그가 선 자세용**이었고, 2D 뼈 길이 변동계수가 뷰마다 4~5배 차이 난다(푸시업 A 0.519 vs **E 0.110**, 크런치 **C 0.047** = 서 있는 종목 수준). **바닥 운동이 어려운 게 아니라 카메라 각도가 문제**다.

**접근**: 3D 를 우회하고 동작 평면에 평행한 뷰의 **2D 좌표만** 사용. 피처는 전부 신체 내재라 **중력축이 필요 없다**(바닥에서는 '높이'가 무의미).
- 신체 주축(어깨→발목) 대비 **부호 있는 이탈**(허리 처짐/엉덩이 들림), 분절 각도(팔꿈치·무릎·몸통-허벅지·머리-몸통), 몸통 정규화 거리
- **접지선(지면) 대비 이탈** — 클립 안에서 가장 덜 움직이는 접지점 쌍(골반↔발목 또는 손목↔발목)의 중앙값 위치로 추정. 지면 검출 모델 불필요

**결과** (35 조건, 수행자 GroupKFold, 최적 뷰): AUC ≥0.85 **6개**, 0.75~0.85 **11개**, <0.75 18개. 중앙값 0.721.

| 되는 것 | AUC | 안 되는 것 | AUC |
|---|---|---|---|
| 머리·시선 각도(고개 젖힘/숙임, 시선 고정) | 0.87~0.94 | **'허리 지면 고정'**(4종목 전부) | 0.57~0.63 |
| 큰 분절 각도(무릎-어깨 일자, 허벅지-종아리) | 0.81~0.90 | '긴장 유지' | 0.58~0.66 |
| 몸통 정렬·거리(손 위치, 가슴 이동, 플랭크 정렬) | 0.75~0.82 | 미세 위치(무릎 교차, 엄지 방향) | <0.65 |

**핵심 발견**: AIHub 2D 에 있는 **허리(Waist)·등(Back) 랜드마크를 추가해도 이득이 정확히 0** — 즉 MediaPipe 에 척추 랜드마크가 없다는 것이 바닥 운동의 병목이 **아니다**. '허리 지면 고정'은 요추 아치가 측면 2D 에서 몇 픽셀이라 랜드마크가 있어도 안 잡힌다(§23 의 척추 결론과 같은 구조).

**적용 순서**: ① 힙쓰러스트(3조건 중 2개 ≥0.90) → ② 푸시업/니푸쉬업(5중 3) → ③ 라잉 레그 레이즈(4중 2) → ④ 시저크로스 → ⑤ 크런치·플랭크(핵심 조건이 안 됨).

**구현 시 주의**: (a) 중력축 의존 경로를 **분기**해야 한다 — `PoseFrame(joints, up)` 대신 신체 주축 기반 프레임. (b) `checkUpSanity` 는 누운 자세에서 오작동 가능(현재는 '미검증' 처리라 안전하나 명시적 분기 필요). (c) 종목별 최적 뷰가 다르다(크런치류 C, 푸시업·플랭크 E) → 촬영 가이드도 종목별. (d) **임계값은 AIHub 값을 쓰면 안 된다** — 5개 뷰 모두 서 있는 높이 카메라라 이상적이지 않다. 바닥 높이·측면으로 §17 프로토콜 재수집 필요. (e) 최적 뷰를 사후 선택한 값이라 낙관 편향이 있다.

**§25a. 어려움의 근본 원인 (`floor_mp_gap.py`, [FLOOR_MP_GAP.md](FLOOR_MP_GAP.md))** — 지금까지의 바닥 수치는 전부 **사람 주석 GT 2D** 기준이었고, 앱의 실제 측정 도구(MediaPipe)는 측정된 적이 없었다. 실험 A 저장 추론(바닥 14,400장) + 회전 재추론(43,200장)으로 원인을 분해했다:
| 판정 | 근거 |
|---|---|
| **1차 병목 = 측정방식**: MP 가 누운/접힌 자세에서 무너짐 | 같은 프레임 GT 대비 관절 오차 **0.120 vs 서있는 0.040 (3.0×)**, PCK@0.2 64% vs 95%. 검출률은 97% → **조용한 실패**(검출은 되는데 좌표가 틀림) |
| 원인은 '방향'이 아니라 **접힘·자기 가림** | 이미지를 세워 넣는 회전 전처리 **기각** — 9종목 전부 개선 0.000~0.003. 팔꿈치 0.22·손목 0.17·무릎 0.17 만 나쁘고 머리(귀·코)는 정확 |
| std/min/max 형 규칙 사망 원인 = **동작 미추적(under-tracking)** | 프레임 간 이동량 MP/GT 비 바닥 **0.56×** vs 서있는 1.05× — 접힌 관절이 얼어붙어 분산 신호 소실. 지터(>1 예상)와 **반대**라 시간 평활도 기각(역효과) |
| 규칙 통계량 충실도(GT↔MP Spearman, 뷰 풀링 n≈100) | 중앙값 0.59. **17규칙 중 5개 측정-사망**(ρ<0.35, 전부 std/min 형: 시저크로스 무릎각 std −0.23·shoulder_ground max −0.08, 니푸쉬업 elbow_width std 0.24, 바이시클 head_trunk min 0.29, 레그레이즈 knee_ang std 0.30). 생존 상위는 전부 머리·큰 분절의 mean 형(0.63~0.89) |
| 기하(뷰)는 별개 축으로 실재 | MP 오차도 뷰 의존: A 0.163 vs C 0.088 (2배) — 뷰 가이드 필수 유지 |

**해결책 (증거 순)**: ① 측정-사망 5규칙 exclude 강등(임계값 보정으로 복원 불가 — 남는 12규칙 ρ 중앙값 0.65), ② 팔꿈치·손목 의존 규칙에 **가시성 기반 ABSTAIN**, ③ 규칙 선별 원칙을 '머리·큰 분절 mean 형'으로 명문화, ④ 뷰 가이드 유지 + 바닥 높이 거치(정량 검증은 자체 수집으로만 가능), ⑤ 세트 로그 재보정. **기각된 해법**: 이미지 회전(개선 0), 시간 평활(방향 반대), heavy 모델(가림 자체는 모델 크기로 안 풀림 — 저순위). AUC 체인은 표본 20클립이라 포화 — 충실도가 주 증거.

**적용 (2026-08-27)**: ①③ → `export_floor_rules.py` 에 충실도 게이트(`RHO_CUT=0.35`, `floor_stat_fidelity_all.csv` 입력)로 구현, 사후 강등이 아니라 **후보 제한 후 재적합** — 죽은 5규칙 중 2개가 충실 피처로 구제되고 3개 탈락, v0.1 = 14규칙/8종목(바이시클 크런치 0). ② → `PostureFloor.kt` 피처별 가시성 게이트(`FEATURE_VIS_CUT=0.35`, 휴리스틱): 가려진 관절의 피처만 프레임 단위 유보 → 그 규칙은 측정 프레임 부족으로 자연 ABSTAIN. 세션 연결: `PostureLive` 가 rules_mp_v0 + rules_floor_v0.1 을 병합하고 바닥 종목이면 2D 평면 피처로 분기, `postureExerciseMap` 에 바닥 8종목 추가(운동 카탈로그에 푸쉬업·힙 쓰러스트·시저 크로스·Y 레이즈 신설, 크런치·레그 레이즈·니 푸쉬업·플랭크 posture=true). 시작 안내가 바닥이면 "휴대폰을 바닥 높이, 몸 옆에" 로 분기.

**정상-앵커 재배치 (v0.2, 2026-08-28, [FLOOR_ANCHOR_VALIDATION.md](FLOOR_ANCHOR_VALIDATION.md))**: 임계값이 채택 뷰 투영에 묶인 문제(플래그율 뷰 간 33%p 요동)의 배포 가능한 해법. 각 규칙에 `normal_median`(채택 뷰 정상 클립 중앙값)·`normal_fpr`(진단용)을 싣고 `personal_baseline{eligible, threshold_rel = threshold − normal_median, k:3, mode:"reanchor"}` 로 기존 기준선 배관을 재사용 — 사용자의 **실제 폰 위치**에서 찍은 정자세 k세트 중앙값으로 임계값 위치를 옮긴다(각도·높이 구분 불필요). 검증(AIHub 교차 뷰): **타인 앵커로는 손해**(k=3 Δ−0.021 — 사람 간 분산이 앵커 노이즈), **동일-수행자 앵커(앱 상황)에서는 이득**: k=3 **Δ+0.027**(34승 9패), k=5 +0.042, k=10 +0.056; 채택 뷰 무해성 k=3 −0.007(≈무해). quant(분위수) 방식은 모든 k 에서 shift 이하 → shift 채택. 앱: `BaselineGuideScreen` 이 바닥 규칙을 병합 로드하고 바닥 종목이면 `FloorFeatureExtractor` 로 수집(기준선과 세션 평가의 피처 정의 일치), 배치 안내도 floor 분기. 남는 가정이던 '순위 보존이 높이 변화에도 유지되는가'는 §25d 재투영 실험으로 ρ=0.98 확인(기하 축에서는 해소, 실측 확인만 남음).

**앱 구현 (2026-08-24, `PostureFloor.kt` + `export_floor_rules.py`)**
| 구성 | 내용 |
|---|---|
| `rules_floor_v0.json` | **v0.1: 14규칙 / 8종목**, 전부 **beta**. 종목당 **단일 뷰 고정** + **MP 충실도 게이트 ρ≥0.35**(§25a — 측정에서 죽는 후보 피처 제외 후 재적합): 푸시업 C 3 · 니푸쉬업 B 3 · 힙쓰러스트 B 2 · 시저크로스 C 2 · 레그레이즈 E 1 · 크런치 E 1 · 플랭크 B 1 · Y-Ex B 1 · **바이시클 크런치 0**(충실한 피처로는 AUC 컷 미달). v0 대비: 사망 5규칙 중 2개는 충실 피처로 교체 구제(시저크로스 다리-지면 ρ−0.08→hip_ang 0.86, 니푸쉬업 가슴이동 0.24→0.45), 3개 탈락. ρ<0.5 채택 4건은 caution + `mp_fidelity` 필드. 임계값 = 전체 데이터 Youden — **미보정**(바닥 높이 카메라 아님), 세트 로그 재보정 대상 |
| 부호 정준화 | 부호 있는 이탈의 법선을 **화면 위쪽 = 양수**로 고정(n0=(−u_y,u_x), n0_y>0 이면 반전) → 좌우 어느 방향으로 누워도 값 불변. `elbow_ang`/`elbow_width`/`shoulder_asym2d` 는 한쪽 사지 기준이라 mirror_safe=false + caution |
| `FloorFeatureExtractor` | 2D 피처 23개(이탈 4 + 각도 6 + 정규화 거리 5 + 접지선 대비 5 + 비대칭 3), Double 연산. **스트리밍 접지선**: 골반↔발목 vs 손목↔발목 중 누적 이동량이 작은 쌍의 prefix 중앙값 — 연구 익스포터와 동일 알고리즘이라 임계값 적합·앱 계산이 같은 정의. 핵심 관절(어깨·골반·발목) 가시성 < 0.2 면 프레임 스킵(접지선도 미갱신) |
| 랩 연결 | `rules_mp_v0 + rules_floor_v0` 병합 로드. 바닥 종목 선택 시: 분석 콜백에서 `s.features`(중력 3D) 대신 `floorExtractor.compute(normalizedXy…)` 로 교체해 집계·코치·세트 로그에 그대로 흘림(재보정 파이프라인 §9/§14 재사용). 세트 시작마다 접지선 리셋. 가이드 줄: "바닥 모드(2D) · 폰을 바닥 높이에 · <뷰 힌트> · 임계값 미보정(beta)". `checkUpSanity` 는 우회 불필요 — up 을 아예 안 쓴다(누운 자세에서 '미검증'으로 남는 게 정상) |
| 음성 코칭 | 바닥 조건 10종 문구를 CoachCues 상단에 추가(일반 패턴보다 먼저 매칭) — 습관형/점진형 모두 |
| 파리티 | `floor_port_fixture.txt`: AIHub 2D 3클립×16프레임의 px 좌표+기대 피처. `FloorFeaturesTest` 4개: 연구 코드 일치(공차 각도 0.02°), 좌우 반전 불변, 접지쌍 선택·위=양수 부호, 바닥 문구 커버리지 |


## 26. 촬영 뷰를 사람 말로 (`view_geometry.py`, `PostureViewGuide.kt`, [VIEW_GEOMETRY.md](VIEW_GEOMETRY.md))
규칙 JSON 의 `view_best_front`(A~E)는 AIHub 카메라 코드라 사용자에게 의미가 없다. '정면/측면'으로 번역하려면 각 코드의 실제 방향을 알아야 하는데, **관측해 보니 서서 하는 종목과 바닥 종목에서 같은 코드가 다른 뜻**이었다 — 카메라는 방에 고정이고 사람이 누우면 서 있을 때 정면이던 카메라가 몸의 측면을 보게 되기 때문.

| 지표 | A | B | C | D | E |
|---|---|---|---|---|---|
| 서서: front_ratio(왼어깨가 화면 오른쪽 비율) | 0.085 | 0.833 | **0.920** | 0.919 | 0.170 |
| 서서: sh_ratio(어깨폭/몸통) | 0.517 | 0.541 | **0.650** | 0.520 | 0.509 |
| 바닥: body_sh((어깨→발목)/어깨폭) | **2.96** | 4.77 | **15.77** | 4.24 | 5.70 |

| 코드 | 서서 하는 종목 | 바닥 종목 |
|---|---|---|
| C | **정면** | **측면** |
| B, D | 앞 비스듬히 (±40°) | 측면 비스듬히 |
| A | 뒤 비스듬히 | 머리·발 쪽 (몸 축 방향) |
| E | 뒤 비스듬히 | 측면 비스듬히 |

**구현**: `ViewGuide` 가 단일 출처. `shortName(view, floor)` = 배지용 짧은 이름, `placement(view, floor, mirrorSafe)` = "폰을 허리 높이에 세로로 세우고, 몸을 정면에서 마주보게" 같은 배치 지시(바닥은 "폰을 바닥에 눕히듯 낮게 두고, 몸 옆(측면)에서 몸 전체가 옆으로 길게 보이게"), `summary(rules, floor)` = "정면 3개, 앞 비스듬히 1개". **미러 안전 규칙이면 좌우를 강요하지 않는다**("좌우 어느 쪽이든") — B/D 구분은 mirror_safe=false 일 때만 의미가 있다. 교체 위치 5곳: 랩 화면 규칙 요약·가이드 줄·규칙별 상세, 기준선 목록·기준선 가이드.

> **정정**: `rules_floor_v0.json` 초판은 C 를 "누운 몸의 정면 축(머리 쪽 또는 발 쪽)"이라고 적었는데 **정반대**였다(C 는 측면, body_sh 15.77 로 최대). 코드를 문구로 그대로 옮기면 사용자를 반대 방향으로 안내하게 된다 — 그래서 뷰 문구는 추정이 아니라 관측으로 고정했다. `ViewGuideTest` 가 이 반전을 회귀 테스트로 잠근다.

## 25b. 촬영 커버리지 — 필요 변수 명시와 해결책 안내 (`PostureFloorCoverage.kt`, [FLOOR_REQUIREMENTS.md](FLOOR_REQUIREMENTS.md))
실기기 첫 로그(§25a 실측)에서 드러난 문제: MediaPipe 는 464프레임 **전부 사람을 검출**했는데도 푸시업 한 세트의 **85% 프레임이 버려졌다**. 원인은 발목이 프레임 밖으로 잘린 것(가시성 0.14)인데, **그 세트의 규칙 3개는 발목을 쓰지도 않았다**. 즉 고정 '코어 관절' 요구가 과잉 차단이었다.

**① 필요 부위를 규칙에서 역산한다.** `FLOOR_FEATURE_PARTS`(피처→부위)로 활성 규칙이 요구하는 부위만 계산한다. 코어는 **어깨·골반**뿐 — 몸통 길이 정규화와 신체 주축의 전제라 모든 피처가 쓴다. 발목은 필요한 피처(`trunk_ankle_ang`·`knee_ang`·`*_ground` 등)만 개별 유보.

| 종목 | 규칙 | 화면에 필요한 부위 |
|---|---|---|
| 푸시업 | 3 | 머리·어깨·골반·손목 (**발목 불필요**) |
| 니푸쉬업 | 3 | 머리·어깨·골반·손목·팔꿈치 |
| 힙쓰러스트 | 2 | 머리·어깨·골반·발목 |
| 시저크로스 | 2 | 머리·어깨·골반·무릎 |
| 플랭크 | 1 | 어깨·골반·발목 |
| 크런치 | 1 | 머리·골반·발목 |
| 라잉 레그 레이즈 | 1 | 머리·골반 |
| Y-Exercise | 1 | 어깨·골반·무릎 |

**② 못 잡는 이유를 구분해 해결책을 다르게 안내한다.** MediaPipe 는 화면 밖 관절도 외삽 좌표를 내므로 원인 구분이 가능하다.

| 상황 | 판정 | 안내 |
|---|---|---|
| 좌표가 화면 밖 · 한 방향 | 프레임 문제 | "{부위}가 화면 {방향}으로 벗어났어요. 폰을 그쪽으로 옮기거나 반대로 이동하세요" |
| 화면 밖 · 여러 방향/부위 | 전신 미포함 | "몸 전체가 안 들어와요. 폰을 더 멀리(2~3걸음) 두거나 가로로 놓으세요" |
| 화면 안인데 가시성 낮음 | 가림 | "{부위}가 몸에 가려졌어요. 폰을 몸 옆으로 옮겨 옆모습이 보이게 하세요" |

전면 카메라는 좌우 반전 표시라 안내의 좌/우도 뒤집는다. 1초(3프레임) 연속으로 막힐 때만 표시하고 음성은 8초 간격. 커버리지가 막힌 동안에는 **자세 코칭보다 우선**한다 — 판정 근거가 없는데 자세를 지적하면 안 되기 때문. 막힌 규칙 이름을 "판정 보류"로 함께 보여준다.

**실측 검증** (받은 로그 5세트 재생): 프레임 보존율 **55.4% → 86.2%**(+143). 푸시업 세트1의 판정 표본 **27 → 131~142프레임**. 플랭크 세트4는 133 → 78로 줄었는데, 발목 가시성 컷을 0.2→0.35 로 올려 흐릿한 발목으로 `trunk_ankle_ang` 을 계산하던 프레임을 뺀 결과다(품질 개선, 표본은 여전히 충분). 몸이 안 잡힌 2~3초 세트는 그대로 ABSTAIN 유지.

## 25c. 세션 화면 레이아웃과 회전 (실기기 피드백)
실기기 사용에서 두 가지가 드러났다.

**① 하단 UI 가 카메라를 가렸다.** 기기(411×868dp) 기준 하단 글래스 패널이 **약 270dp = 화면의 31%** 를 덮었다. 게다가 `PreviewView` 가 `FILL_CENTER` 라 4:3 영상을 9:19.5 화면에 채우느라 **좌우 약 37% 를 잘라내** 사용자가 카메라의 실제 시야보다 좁게 보고 프레이밍을 판단하고 있었다 — §25a 에서 발목이 프레임 밖으로 나간 것과 무관하지 않다.

→ **카메라 영역과 조작 영역을 분리**한다. `FIT_CENTER` 로 바꿔 카메라가 보는 전체를 남는 공간에 채우고, 패널은 그 **바깥**에 둔다(세로: 아래, 가로: 우측 320dp 고정폭+스크롤). 겹침 0. 오버레이 좌표 변환도 `maxOf`→`minOf` 로 함께 바꿔야 스켈레톤이 어긋나지 않는다(같은 실수를 랩 화면에서 반복하지 말 것 — 그쪽은 아직 FILL 이라 `maxOf` 가 맞다). 패널은 타이머 32→24sp 등으로 압축했다.

**② 회전하면 세트가 초기화됐다.** 매니페스트에 `configChanges` 가 없어 회전 시 Activity 가 통째로 재생성됐다. 세션 진행도(`sessionIndex`·`sessionTimeLeft`)는 `rememberSaveable` 이라 살아남지만, 세트 내부 상태 — 코치 창, **접지선 추정**, 수집 버퍼, 커버리지 — 는 전부 날아갔다.

→ `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`. **주의: 이걸 넣으면 회전값을 캐시한 코드가 전부 깨진다.** `remember(context)` 로 잡아둔 `displayRotation` 이 갱신되지 않아 중력축(`gravityUpInWorld`)과 이미지 정립이 틀어진다. 그래서 세 화면(`PostureLive`·`PostureLabScreen`·`BaselineGuideScreen`) 모두 `remember(configuration)` 으로 바꾸고, 분석 스레드용 `rotationRef` 와 `ImageAnalysis.targetRotation` 갱신을 함께 넣었다.


## 25d. 카메라 높이 축의 첫 정량 검증 — 가상 재투영 (`floor_height_projection.py`, [FLOOR_HEIGHT_PROJECTION.md](FLOOR_HEIGHT_PROJECTION.md))

§25a~c 가 전부 "AIHub 5뷰는 서있는 높이뿐이라 폰의 바닥 거치는 검증 불가(바닥 3D 붕괴로 재투영도 불가)"로 남긴 축. 그러나 **QC 통과 중간 프레임이 클립의 8~27% 존재**하고 v0.1 채택 14조건 전부에서 정상/위반 표본이 남는다(양호 클립 580개, 수행자/종목 중앙값 10명). 신체 기준 가상 카메라(몸 옆, D=250cm, **클립별 지지평면에 높이 앵커**)로 **서있는 높이(145cm, 하향 ~27°≈AIHub) vs 폰 바닥 거치(25cm, ~1° 측면)** 를 직접 비교했다. 피처는 `floor_2d_rules.frame_features` 그대로, 주석 2D 게이트도 같은 clip×frame 창.

> 검증 이력: 1차 실행은 바닥 클립 3D 가 세계 y=0 이 아니라 **y≈95cm 평면에 떠 있는** 리그 오프셋을 놓쳐 카메라가 지면 아래에서 올려다보는 스윕을 측정했다(적대 검증 워크플로가 발견). 지지평면 앵커로 수정 — 수정 전후 결론 방향은 동일하며 수치는 수정본 기준.

**결과 — 걱정하던 축(높이)은 무해하고, 진짜 적은 방위다** (판정 = 균형정확도, H145 적합 임계값, 쌍대 기하 비교):
| 축 | 순위 보존 | 판정(raw) | oracle |
|---|---|---|---|
| **높이** 145cm(하향27°)→25cm(측면) | **ρ=0.98** (0.92~1.00) | 0.747 | 0.782 |
| **방위** +25°(발쪽) @바닥 | (뷰 간 ρ=0.65, §25c 실측) | **0.608 붕괴** | 0.783 |
| 방위 −25°(머리쪽) @바닥 | | 0.712 | 0.778 |
| 거리 1.8m @바닥 | | 0.734 | 0.782 |

- 정상 중앙값 이동 |중앙값| **0.21 IQR** — 높이 축에서는 raw 임계값이 거의 그대로 이전된다.
- **동일수행자 앵커 k=3 (v0.2 경로, 같은 모집단 raw 대비)**: 거치 정확(옆) **Δ−0.007(≈중립)**, **방위 +25° 어긋남 Δ+0.109** (0.625→0.701). 전역(비개인) 앵커는 손해(Δ−0.041) — §25c 교차 뷰 결론(동일인 +0.027/타인 −0.021)과 독립 표본에서 방향 재현. **앵커는 상시 보정이 아니라 '거치 오차 보험'이다.**
- 방위 비대칭: 발쪽 치우침(+25°: 0.608)이 머리쪽(−25°: 0.712)보다 나쁘다 → 거치 가이드에 "치우친다면 머리쪽으로".
- 부수 발견: 측면 투영이 **채택 뷰 주석 2D 보다 나은** 규칙들(힙쓰러스트 일자 0.53→0.90, Y 0.54→0.77) — 이탈/거리형 규칙은 진짜 측면이 AIHub 의 어떤 뷰보다 유리. **앱이 요구하는 거치가 데이터의 카메라보다 좋다.** 게이트 탈락 1건(시저 시선: 주석 0.92→투영 0.80).

**설계 확정**: ① 거치에서 높이·거리는 관대해도 된다 — **엄격해야 하는 건 '몸 옆(측면)' 방위**(±25° 안, 특히 발쪽 금지). ② v0.2 정상-앵커 재배치는 거치가 어긋났을 때 +0.11 복구하는 보험 — 유지하되, 실기기 세트 품질 게이트(§25c)가 선행. ③ '순위 보존이 높이에도 유지되는가'라는 §25c 의 남은 가정은 기하적으로 해소(ρ=0.98) — 남은 것은 MP 측정 노이즈(§25a, 1차 병목)와 실측 확인.

한계: 이상적 핀홀(왜곡·프레이밍·**MP 측정오차 없음** — 기하 효과만 분리) → 실제 격차의 하한. in-sample 임계값의 쌍대 비교(신규 수행자 일반화 아님). 양호-3D 클립 14~26% 표본 편향 가능. 표본 작은 규칙 4개(플랭크·힙쓰·크런치·Y)는 방향만. 최종 판정은 라벨 있는 기준선 + 세트 로그 실측.


## 25e. 앱 반영 — 세션 소비·감사 반영 (2026-08-31)

연구가 확정했지만 앱에 없던 네 가지를 반영했다:

1. **세션이 기준선을 소비** (`PostureLive.kt`): v0.2 재배치의 가장 큰 격차 — `BaselineGuideScreen` 이 앵커를 수집해도 세션은 `baseline=null` 로 평가해 재배치가 실전에서 죽어 있었다. 운동 시작 시 `BaselineStore` 에서 그 종목 기준선을 읽어 `LiveCoach` 와 세트 종료 `evaluate` 양쪽에 전달하고, 상태 줄에 "기준선 ✓" 표시. §25d 실측 기준 거치 오차 시 +0.11 복구가 이제 실제로 작동한다.
2. **감사 4건(§25b FLOOR_RULE_AUDIT) 정직성 반영** (`PostureCoach.kt`): (B) 플랭크 정렬 — `CoachCues.directional()` 이 최근 창의 부호 있는 `hip_dev_ankle` 로 "엉덩이 솟음→내려라 / 처짐→올려라"를 가른다(값 없으면 병합 문구 폴백). (C) 힙쓰러스트 고개 — 판정 근거(흔들림 std)와 문구 일치: "처음부터 고개가 흔들리고 있어요". (A) 크런치 견갑골 — "견갑골이 뜨게" 약속 제거, "머리·어깨를 함께" 로. (D) Y 경추 — 몸 라인 문구로. (A/C/D) `measurementNote()` 가 판정 근거를 화면에 정직하게 공개(ⓘ, TTS 제외).
3. **§25d 거치 가이드** (`PostureViewGuide.kt`): 바닥 배치 문구에 "발쪽으로 치우치지 않게(높이·거리는 자유)" — 엄격해야 하는 축(방위)과 관대한 축(높이·거리)을 실측대로 구분. 세션 시작 TTS 도 동일.
4. **기준선 무측정 세트 게이트 명시화** (`BaselineGuideScreen.kt`): 저장 버튼은 도입 시점부터 `enabled = lastSetValues.isNotEmpty()` 로 이미 비활성화돼 있었다(리뷰 정정 — 초판의 "저장이 가능했다"는 과장). 이번 변경은 **왜 눌리지 않는지**를 라벨로 밝히고("측정값 없음 — 저장 불가") onClick 이중 가드를 추가한 것.

검증: `FloorCoachHonestyTest` 5개(방향 분리 양/음/폴백, measurementNote 4건, 흔들림 문구) 포함 posture 유닛 테스트 통과, assembleDebug 성공. 적대 리뷰(2에이전트)가 잡은 4건 반영: **세트 로그 좌표계 고정**(재배치 적용 시 로그 value 가 차감값이 되어 재보정 입력이 오염되던 것 → value 는 항상 절대값 + `baseline_applied`/`value_rel` 추가 필드), 운동 전환 시 배너·ⓘ 리셋, 랩 화면에도 ⓘ 일관 표시, TTS 표기 통일.

남은 것(데이터 필요): 임계값 실측 재보정(라벨 있는 기준선·세트 로그), 크런치 견갑골 규칙의 제거 여부 판단(현재는 근거 공개로 유지), MP 충실도 ρ<0.5 4규칙 재확인.


## 27. 렙 신호 전수 조사 — 전 종목 "무엇으로 렙을 나누나" (`rep_signal_survey.py`, [REP_SIGNALS.md](REP_SIGNALS.md))

렙 라벨 없는 AIHub 에서 렙 신호를 고르는 원리: **클립별 합의(consensus)** — 몸 전체가 렙 주기로 움직이므로 (종목×MP계산가능 피처) 전수에 자기보정 카운터를 돌려 다수 피처가 합의하는 카운트를 참값 프록시로 삼고, 합의 일치율 최대 피처를 채택.

**카운터 v3 (극값-중점 밴드)**: center=(p10+p90)/2, 밴드 = center ± 0.15×(p90−p10), 히스테리시스 전체 사이클 = 렙 1. v2(분위수 밴드+진폭 게이트)는 적대 검증에서 **비대칭 듀티 사이클 붕괴**가 확인돼 폐기 — 컬은 대부분 프레임이 신전 근처라 분위수 밴드가 굴곡 딥을 못 보고 게이트가 '무활동' 기각(육안 대조 2/10). v3 캘리브레이션: 스쿼트 육안 6클립 오차합 2, 컬 0카운트 31%→0%·최빈 4. **밴드·평활 모두 샘플 밀도 적응**(성기면 원시값, 렙당 ~17샘플이면 3점 중앙값 평활). 앱 구현 시 진폭 게이트는 분위수식이 아니라 **종목별 최소 진폭(물리 단위)** 으로.

- **서서 32종목: 전부 판별** (합의일치 0.81~1.00): 데드·스티프·굿모닝→고관절 각(hip hinge) 0.98~1.00, 스쿼트→`knee_fwd_mean`(무릎 전방이동) 0.98, 풀업→`shoulder_R` 0.97, 딥스→`elbow_h_mean` 0.94, 컬→`upperarm_vert` 0.84~0.85, 레터럴·랫풀·업라이트→`forearm_vert` 0.84~0.92, 로잉→`palm_fwd_knee` 0.96 등. 일부 종목(OHP→발목각 0.87, 푸시다운→발피치 0.81)은 **전신 공진**이 이긴 것 — 의미론적 러너업 선호, M0 실기기에서 확정.
- **바닥 9종목: 판별 신뢰 불가** (0.6s 주석 언더카운트 + off-by-one 수정 후에도 순위 불안정) → **기기 실측이 결정**: 푸시업류 `wrist_shoulder_d`(실기기 4/4 적중), 크런치 `head_ground`·레그레이즈 `hip_ang`·힙쓰 `hip_dev_ankle`(설문 1위 일치 0.93) 등 운동학 채택 후 M0 검증.
- **플랭크 = 등척성 (설계 확정)**: 설문 카운트는 진입/이탈+미세 흔들림 아티팩트 — 상태기계 SETTLING/END 흡수 후 HoldTimer.
- **교대형**(시저·바이시클·니업·사이드크런치): 좌우 역위상이 0.6s 에일리어싱으로 검출 불가(음의 편향을 준 nanmin 집계로도 전부 문턱 미달 — a fortiori 유효) — 정의 기반 분류, 기기 3.3fps 재검.

**빠른 렙 정책 (실측)**: 렙 주기 2.4~4.8s(서서), 렙당 0.6s 기준 중앙 5.3샘플 — 절반 서브샘플 시 회복률 **0.50 붕괴** → **최소 렙당 ~4샘플**. 앱 3.3fps 안전선 = 주기 ≥1.2s(평상시 여유 2~4×). 대처 사다리: ① 주기추정 기반 ACTIVE 한정 5~6fps 부스트 ② 측정주기 ≈ 2~3×샘플간격 시 에일리어싱 자가진단 → 카운트 미확정 표시 ③ 반사이클 카운트 폴백 ④ UX 최후선("조금 천천히").

**M0 재생 하니스 결과 (`rep_replay.py`, [REP_REPLAY.md](REP_REPLAY.md))**: 스트리밍 카운터(Kotlin 이식 규약)를 실기기 13세트에 재생 — 라벨 세트 **적중 1(4 vs 정답 3~4) · ±1 1(폰 이동 세트) · 실패 0**, 플랭크 3세트 잡음 교차 0(게이트 35° 적용 후), 가림 세트(측정 27프레임)는 0으로 거부. 배치 카운터는 같은 세트를 5~7로 과카운트 — **스트리밍(불응기 1.2s)이 셋업 오염에 더 강함**을 실측. 이식 규약 확정: ① 평활은 렙당 ≥8샘플일 때만 3점 중앙값 ② 밴드 = (p10+p90)/2 ± **0.15**×스팬 고정(0.30 확장은 얕은 렙을 놓침 — M0 실측 후퇴) ③ 진폭 게이트는 물리 단위, **각도형 신호는 ≥35°**(플랭크 유지 중 기기 잡음 바닥 10~30°/5s 실측) ④ 가림 프레임은 일시정지. 남은 것: 정자세·다양한 템포 라벨 세트 확충(현재 2개), 교대·나머지 바닥 종목 신호의 기기 확정.

**앱 이식 (RepCounter.kt, 2026-08-31)**: M0 규약을 그대로 Kotlin 으로 — `RepCounter`(적응 평활·극값-중점 밴드 0.15·물리 단위 게이트·불응기 1.2s·가림 일시정지·periodMs 노출) + `RepSignals` 등록부(바닥 8 동적 + 서서 28, 플랭크·미신뢰 종목은 미등록 — 오카운트보다 미표시). **패리티 테스트**: 실기기 세트 픽스처(`rep_fixture_baseline1.txt`)에서 파이썬 레퍼런스와 동일 4렙(정답 3~4) 재현. 서서 종목은 설문 승자 중 앱 피처 집합에 없는 것(hip_R·knee_fwd_mean·elbow_h 등)을 같은 패밀리 가용 피처로 매핑 — 전부 beta. 세션 연결: 타이머 행 "렙" 열(주기<1.5s 면 "빠름·미확정" 경고 — 렙당 4샘플 하한), TTS 숫자는 QUEUE_ADD(코칭 문구 우선), 세트 로그에 `reps{count, signal, t_ms[]}` 호환 필드. `RepCounterTest` 6개 포함 80/80 통과.

**렙 유효성(ROM) — "얕으면 무효 렙" 수정판 (`rep_validity_thresholds.py`, [REP_VALIDITY.md](REP_VALIDITY.md), 2026-08-31)**: 사용자 제안(운동을 단계로 나눠 단계별 기준 충족 시에만 1회 인정)의 데이터 수정판. 4단계 전부가 아니라 **사이클 하단/수축 극값 하나**만 기준으로 — 단계 라벨 데이터가 없고 문장 기준의 좌표 직역은 정상 스쿼트 98% 를 실격시키기 때문. 노력 방향은 듀티사이클 비대칭으로 자동판정, 임계값은 AIHub **전 조건 정상 클립** 렙 극값의 90% 통과 분위수(39종목). **판별력 검증**: ROM 성격의 AIHub 조건 보유 5종목 전부에서 위반 클립 무효율이 정상의 2.8~3.8배(푸시업 0.13→0.50, 니푸쉬업 0.12→0.37, 크런치 0.27→0.67, 바벨 런지 0.16→0.71, 딥스 0.24→0.90). 앱: `RepCounter` 가 렙별 사이클 극값 노출 → `RepSignal.isValidRep` → **세되 무효**("N · 무효 M" 표시) + 사유 발화 — 검증 5종목은 구체 사유("가슴을 더 내려 주세요"), 나머지는 방향 자동판정이 복귀 끝을 잡았을 수 있어 **방향 중립 사유**("끝까지 움직여 주세요")만(정직성). 세트 로그에 `reps.invalid` 추가. 한계: AIHub 0.6s 극값은 얕게 잡혀 임계값이 관대(beta 안전 방향), 실기기 라벨("N회 중 깊은 것 M회")로 재보정 대상.

**카운터 v4 — 반전(reversal) 방식 (2026-08-31, 라벨 세트 실측으로 확정)**: 사용자 라벨 세트(깊3·얕3·깊3·얕3=12)가 v3(창 분위수 밴드)의 결함 2건을 실증 — ① 손목-붕괴 잡프레임(값≈0.01, 15프레임)이 창 p10 을 끌어내려 밴드 전체가 내려앉음(얕은 렙 미카운트의 주범), ② 깊/얕 혼합 세트에서 밴드 상단이 깊은 렙 기준으로 높아져 얕은 렙 복귀가 못 닿음. v4 는 절대 위치를 버리고 **방향 반전 ≥ h(minAmp) 에서 극점 확정**(만보기 원리) + **물리 타당 범위 게이트**(AIHub 프레임 p0.1 기반, 푸시업류 하한 0.10) + 평활을 샘플 간격 기반으로(주기 대기 조건은 초기 성긴 신호를 오평활). 실측: 라벨 세트 5/12→**9/12(유효 5·무효 4 — 렙 단위 깊/얕 혼동 0)**, 폰-이동 세트 2/2 첫 적중, baseline1 4 유지, 플랭크 잡음 0·0·0·1, AIHub 성긴(0.6s) 분석은 밴드 v3(batch)가 우세(스쿼트 오차 2 vs 8)라 연구 코드는 유지 — **밀도별 알고리즘 분리**. 놓친 3렙은 잡프레임 구간·3.3fps 융합(±예산).

**모든-사용자 원칙 (사용자 지시로 명문화)**: 사용자 개인의 라벨 세트는 **검증에만** 쓰고 튜닝에는 쓰지 않는다. v4 에서 이 세트로 조정한 파라미터는 0개다(h·ROM 임계·물리하한 전부 AIHub 모집단산, 반전은 구조 변경). 회귀 게이트로 AIHub 픽스처(스쿼트 육안·컬 분포·플랭크)와 타 세트를 함께 재생해 특정 사용자 개선이 다른 데이터를 해치지 않음을 매번 확인한다. 역방향 증거: 모집단 ROM 0.71 이 이 사용자의 깊(0.43~0.67)/얕(0.73~0.88)을 무튜닝으로 정확히 분리 — 모집단 기준의 이전 가능성.

> 검증 이력: 적대 검증(3에이전트)이 v2 의 결함 3건(MP 불가 피처 채택 shoulder_neck_gap · 바닥 off-by-one 프레임 유실 · 컬형 파형 붕괴)을 발견 — v3 로 수정 후 수치 갱신. 합의 자기포함 순환성은 LOO 재계산으로 무시 가능 확인(Δ<0.003), 단 근중복 피처 패밀리의 블록 투표는 남은 한계.

## 28. '발바닥 지면 고정'(스쿼트) 오탐의 근본 원인 — 조건-피처 불일치의 실기기 실증 (2026-08-31)

증상: 신발 착용 스쿼트에서 뒤꿈치를 들지 않아도 4/4 세트 위반. 사용자 가설은 '신발'.

실측 원인 사슬 (사용자 4세트 + AIHub C뷰 MP 재적합 표본 대조):
1. **피처-조건 불일치**: `foot_pitch_R__min < −53.4` 는 뒤꿈치 들림의 직접 측정이 아니다. 사용자 프레임에서 임계 아래 프레임의 무릎각 중앙 92~95° vs 정상 프레임 161° (상관 r=0.77~0.80) — **위반 프레임 = 딥 스쿼트 하단**. 하단에서 발목 배굴 + 무릎·허벅지가 발을 가리며 MP 발 랜드마크가 아래로 미끄러진다.
2. **촬영 기하가 결합을 증폭**: AIHub C뷰(원거리 스튜디오)에서는 깊이↔발피치 결합이 약해(정상 클립 내 r=0.19, 깊은 25% 정상도 임계 아래 17%) 임계가 성립했지만, 폰(근접·저각·발이 프레임 가장자리)에서는 정상 딥 스쿼트의 min 이 −57~−62 로 — **AIHub '뒤꿈치 연기' 위반 분포(중앙 −60.7)와 겹치는 영역**까지 이동.
3. **min 통계**: 하단은 매 렙 반복되므로 min 이 항상 그 프레임을 뽑는다 → '어떤 경우에도' 위반. (16프레임 무작위 재표본도 89~97% 위반 — 표본 크기 효과가 아니라 계통 결합.)
4. **신발 가설 판정**: AIHub 수행자도 운동화 착용으로 추정되어 신발 단독으로는 격차를 설명 못 함 — 기각 방향(기여 가능성은 미확정으로 남김. 신발/맨발 대조 데이터 없음).

조치: 규칙 **exclude**(양쪽 rules_mp_v0.json, reason 명기) — 측정이 조건을 재지 못하는 §25b 감사 A 와 같은 부류. 재정의 경로: MP **heel(29/30) 랜드마크 직접 사용**(발 내 heel↔foot_index 상대 높이 — 발목 배굴과 분리됨), 자체 수집으로 임계 적합. 부수 관찰: '고개 정면'(face_vs_torso__min)도 min-꼬리 상시 위반 패턴(p10 은 임계 위) — 실제 시선 이탈인지 사용자 확인 필요, 동일 구조 의심.

**위반 부위 시각화** (`RuleHighlight.kt`): 위반 중 규칙의 base feature → 측정 관절 매핑(접두 일치, 미지 피처는 강조 없음 — 오지시보다 무지시)을 스켈레톤 오버레이에 붉은 강조로. LiveCoach lastStates 의 recent=VIOLATION 합집합을 매 평가마다 갱신.

## 28b. 재설계 설계서 — '고개 정면'과 '발뒤꿈치' (2026-08-31, 데이터 검증 포함)

### A. '고개 정면' — 같은 병(하단 결합)이되 원인은 정의 층위
진단(사용자 4세트): 저값 프레임 = 스쿼트 하단(무릎 80~95°, r=0.83~0.88), **상체 기울기와 r=−0.82~−0.92** — `face_vs_torso` 는 얼굴 방향을 **몸통축 기준**으로 재므로, 하단에서 상체를 숙이면 시선을 정면에 둬도 각도가 무너진다. AIHub 얕은 스쿼트(§25d 실측: 하단에서도 고관절이 무릎 위)에서는 미노출.

후보 검증 (GT n=706, 위반=1):
| 후보 | AUC | 정상 내 깊이상관 | MP 충실도(C) | 판정 |
|---|---|---|---|---|
| face_vs_torso__p10 | 0.946 | +0.13 | 0.44 | AUC 최고지만 결합 잔존 + 저충실 — 이번 사고의 조합, 기각 |
| **head_pitch__mean** | **0.899** | **−0.08** | **0.80** | **채택** — 결합 해소 + mean 통계(min-꼬리 원천 제거) |
| face_vs_forward__* | 0.59~0.65 | ~0 | 0.76 | 라벨과 안 이어짐(위반 연기가 몸통-상대) — 기각 |

**확정 설계**: 스쿼트 '고개 정면' 규칙을 `head_pitch__mean` 으로 교체. MP 재적합(C뷰 n=56): CV AUC 0.795, 임계 > −17.2, 정상 중앙값 −23.6. **personal_baseline eligible**(mean 수준 피처 — 재배치 배관 그대로): 소급 검증에서 사용자 mean −12.7~−19.3 로 임계와 얇게 겹침(3/4 위반) — 시선 정답이 없는 세트들이라 오탐 여부 미확정이며, 기준선 재배치가 개인·거치 기하를 흡수하는 것이 설계 경로. 최종 확정 조건: **시선 정면 고정 라벨 세트 1회**(정상이어야 함) + 기준선 수집.

**[구현 완료 2026-08-31]** 재추론 불필요 판명 — expA 랜드마크 캐시(33점, world 포함)가 보존돼 있어 즉시 검증: 게이트 ① 하단 결합 r=−0.07~−0.12 전부 통과(설계 예측 적중) ② `heel_lift__p90` CV AUC **0.866**, 임계 > 0.580 (mean 은 0.763 — p90 채택, 극값 금지 원칙 준수). 앱: `Joints.L/R_HEEL`(29/30) 매핑 추가, `PoseFrame.heel_lift`(좌우 평균, 중력 up 기준 — 폰 기울기 불변), `FeatureAggregator` 에 p10/p90(numpy 선형보간 패리티). 규칙 갱신: '발바닥 지면 고정' → `heel_lift__p90 > 0.580`, **beta**(기기 미검증 신규 피처), personal_baseline eligible(rel +0.058, k3 — 신발 밑창 오프셋 흡수). '고개 정면' → `head_pitch__mean > −17.21`, ship + eligible(rel +6.39). HeelLiftTest 4개 포함 테스트 통과.

### B. '발뒤꿈치' — 발목 배굴과 분리되는 발 내부 기하로 재정의
§28 원인(foot_pitch = ankle→toe 벡터 → 하단 배굴과 결합)의 구조 해법: **발 내부** 기하만 사용.
- 새 피처 `heel_lift` = (heel_y − foot_index_y) 를 중력 up 축 성분으로, 발 길이(heel↔foot_index)로 정규화 — 좌우 평균. 발이 바닥에 붙어 있으면 무릎·발목이 어떻게 굽어도 **발 안의 heel↔toe 상대 높이는 불변** → 하단 결합이 구조적으로 없음. 뒤꿈치 들림 시에만 heel 이 toe 대비 상승.
- 데이터 현황: 연구 24관절 매핑이 MP heel(29/30)을 버려 GT·기존 MP 집계 모두에 없음 → **검증 = 스쿼트 표본 MP 재추론(33점 보존)** 이 유일 경로. 단계: ① mp_sample 스쿼트 C뷰 한정 재추론(~1.8천장, 수 분) ② heel_lift 프레임 분포에서 하단 결합 부재 확인(무릎각과 상관 |r|<0.2 게이트) ③ '발바닥 지면 고정' 라벨 AUC + Youden 임계(통계는 mean/p90 — max 금지, min-꼬리 교훈) ④ 게이트 통과 시 ship, 실패 시 조건 유지 불가로 계속 exclude(정직).
- 앱: PoseLandmarker 는 33점 world 를 이미 제공 — PostureCore 에 heel_lift 계산 추가만 필요(신규 파이프라인 없음). 신발 오프셋은 사용자 공통 상수라 기준선 재배치가 흡수(eligible).

**공통 원칙 (이번 3연속 사고의 일반화)**: ① 극값 통계(min/max) 규칙은 기기에서 꼬리 결합·잡음에 취약 — 신규/재적합 규칙은 mean 또는 p10/p90 만 허용 ② 채택 전 게이트에 '주 동작(렙 깊이)과의 정상 내 상관' 검사 추가(|r| 게이트) — 조건과 무관한 동작 결합을 사전 차단 ③ 임계 여유가 IQR 급으로 얇으면 personal_baseline eligible 필수.

## 28c. 전 규칙 동작-결합 감사 — 스쿼트의 병이 다른 종목에도 있었다 (`rule_coupling_audit.py`, [RULE_COUPLING_AUDIT.md](RULE_COUPLING_AUDIT.md))

§28/§28b 는 스쿼트 2건만 고쳤다. 같은 두 병(주 동작 결합 · 극값 통계)을 **활성 전 규칙 70건에 소급 적용**한 결과:

- **결합 의심 15건** (정상 클립 내 |r(피처, 렙 진폭)| ≥ 0.35) — 조건이 아니라 렙 위상을 따라가는 규칙이 스쿼트 밖에도 실재
- **극값(min/max) 19건** 중 강건 대안이 AUC 손실 ≤0.02 인 **7건 무손실 교체**

| 조치 | 건수 | 예 |
|---|---|---|
| 극값 → 강건 통계 교체 | **7** | 데드 무릎방향 min→**p10**(AUC 0.947→0.940, r −0.03→0.00), 스탠딩사이드크런치 손위치 min→**mean**(0.912→**0.944** 향상), 스티프 궤적 max→**p90**(0.948→**0.961** 향상), 덤벨플라이 팔꿈치 max→**mean**(0.820→**0.868**) |
| 결합 극심(\|r\|≥0.70) ship→**beta** 강등 | **4** | 사이드 레터럴 '상완-전완 각도 고정' r=**−0.96**, 크로스런지 '앞다리 90도' r=+0.89, 풀업 '몸통-팔꿈치 모아줌' r=+0.84, 라잉 트라이셉스 '팔꿈치 위치 고정' r=−0.70 |

결과: 활성 71건의 극값 규칙 19→**12**, mean 32→34, p10/p90 신설 6. ship 55 / beta 16.

**강등의 의미**: 통계 교체로 안 풀리는 결합은 조건-피처 불일치(§25b 감사 A 와 같은 부류)다 — AUC 가 높아도(크로스런지 0.984) 그 AUC 는 '얼마나 깊이 앉았나'를 맞히는 것일 수 있다. 판정은 유지하되 beta 로 표시하고 실기기 오탐 관찰 대상으로 남긴다. 재정의는 조건별 개별 작업(§28b 의 heel_lift 같은).

**한계**: GT 3D 기준 감사다. 스쿼트 실측에서 **GT r=0.19 → 기기 r=0.78** 로 결합이 4배 증폭됐으므로(근접·저각 촬영), 여기서 r<0.35 로 통과한 규칙도 기기에서는 결합이 클 수 있다. 바닥 9종목은 3D 불량이라 이번 감사 제외 — rules_floor 2D 경로에 같은 감사 필요(남은 작업).

## 28d. 바닥 규칙 감사 — 결합은 적었으나 **규칙 2건이 근본적으로 고장나 있었다** (`floor_coupling_audit.py`, [FLOOR_COUPLING_AUDIT.md](FLOOR_COUPLING_AUDIT.md))

§28c 에서 빠졌던 바닥 9종목을 채택 뷰 **주석 2D**(앱과 같은 시점·피처 정의)로 감사. 결합 자체는 서있는 종목보다 적었으나(14건 중 3건), **발화율·방향 검사에서 치명적 결함 2건**이 드러났다 — 결합 감사보다 이쪽이 큰 수확.

| 규칙 | 결함 | 수정 |
|---|---|---|
| 힙쓰러스트 '수축시 무릎부터 어깨까지 일자' | `hip_dev_ankle__max < 0.1632` — 임계값이 **분포 밖**이라 정상 클립의 **92%** 를 위반 판정, 게다가 **방향 역전**(위반이 값이 큰 쪽인데 op `<`). CV AUC **0.553 = 무작위** | → `hip_dev_ankle__p10 > −0.1398`, CV AUC **0.838**, 정상 발화율 92%→**20%**, 결합 +0.62→**−0.33** |
| Y-Exercise '경추 중립/후인' | `hip_dev_knee__min > −0.0488` — **방향 역전**(정상 55% vs 위반 41% 발화 = 뒤집힘) | → `hip_dev_knee__p90 < −0.0020`, CV AUC 0.593→**0.844**, 정상 발화율 55%→**5%** |

나머지 12건은 건전(정상 발화율 9~34%, 위반 60~88% — 방향 일치). 감사 후 결합 3→2건.

**교훈(감사 방법 자체의 개선)**: 결합·극값 게이트만으로는 이 2건을 못 잡았다 — **정상 클립 발화율**과 **라벨 방향 일치**를 같이 봐야 한다. export 파이프라인이 AUC 를 방향 자동정렬(max(a,1−a))로 계산하면서 op/threshold 기록과 어긋난 것이 원인으로 추정 — 신규 규칙 출시 게이트에 "정상 발화율 <40% + 방향 일치" 검사를 상설화할 것.

## 28e. 실기기 검증 — 고개는 해결, 뒤꿈치는 '기준선 필수'로 (2026-09-01 스쿼트 세트)

§28b 수정 후 첫 실기기 스쿼트(116초, 288측정프레임)로 검증:

| 규칙 | 결과 | 근거 |
|---|---|---|
| **고개 정면** (head_pitch__mean > −17.21) | ✅ **정상** — 오탐 해소 | 기기 mean **−20.94** vs 임계 −17.21 (여유 3.7). 이전 face_vs_torso 는 4/4 세트 위반이었다 |
| 발과 무릎 방향 | ✅ 정상 | 0.031 |
| **발바닥** (heel_lift__p90 > 0.580) | ⚠ 위반 — 그러나 **원인이 다름** | 결합은 개선(foot_pitch r=+0.62 → heel_lift **−0.58**, 결합/신호폭 0.58→**0.44**). 진짜 원인은 **오프셋**: 기기 p10~p90 = 0.506~0.637 인데 AIHub 임계 0.580 이 **분포 한가운데** = 동전던지기 |

**조치 — `requiresBaseline` 도입**: 임계가 기기 분포 중앙에 놓인 규칙은 raw 판정 자체가 무의미하므로, `personal_baseline.required=true` 인 규칙은 **기준선 없으면 ABSTAIN**(판정 보류). 스쿼트 heel_lift 에 적용. 기준선(정자세 3세트) 수집 후에는 실효 임계 = 기준선 p90 + 0.058 로 이동해 정상 통과·실제 들림 검출이 모두 성립(테스트로 검증).

**일반 원칙 (신규 규칙 출시 게이트에 추가)**: ① 정상 발화율 <40% + 방향 일치(§28d) ② 주 동작 결합 |r| < 0.35(§28c) ③ **임계가 기기 분포 중앙이면 requiresBaseline**(§28e) — 셋 다 통과해야 raw 판정 허용, 아니면 기준선 필수 또는 exclude.

**남은 관찰**: heel_lift 결합 r=−0.58 은 GT(−0.07~−0.12) 대비 기기에서 5배 증폭 — §28 의 foot_pitch 와 같은 증폭 패턴(GT 0.19→기기 0.78)이 재현됐다. 발 랜드마크는 근접·저각 촬영에서 구조적으로 취약하므로, 기준선으로도 안 풀리면 발 관련 조건은 폰 1대로는 포기하는 것이 정직한 결론이 될 수 있다.

## 29. 세션 모드 — 초보(코치) / 숙련(기록) (`PostureMode.kt`, 2026-09-02)

**동기**: 관측 시스템은 스타일과 오류를 구분할 수 없다(실측: 사용자의 깊은 스쿼트가 AIHub 표준에 위반 판정). 숙련자의 습관 폼은 의도된 폼이므로, 모집단 기준 실시간 지적은 그들에게 범주 오류다. 해법은 **같은 엔진, 반대 정책**: 초보 = "모집단이 기준, 앱이 가르침" / 숙련 = "본인이 기준, 앱이 기록함".

**구현 (v1, 정책 레이어만 — 판정·임계값·로그는 두 모드 동일)**:
| 항목 | 코치 (기본) | 기록 (숙련) |
|---|---|---|
| 음성 | 코칭 문구 + 유효 렙 숫자, ROM 미달 시 사유 발화 | **렙 숫자만(파셜 포함 전체 수)**. HABIT("처음부터") 침묵 — 스타일일 수 있음. **DRIFT("점점")만 발화** — 세트 내 변화(피로)는 숙련자에게도 정보. RECOVERED 는 말한 DRIFT 에 대해서만 |
| 렙 표기 | "유효 N · 무효 M" | "전체 N · **파셜** M" — 파셜은 기법이지 잘못이 아님 |
| 위반 부위 붉은 강조 | ON | OFF (모집단 임계의 "틀림" 표시는 스타일 오판 위험) |
| 패널 지표 | 자세 점수(%) | **템포**(렙 간격 중앙값, `RepMetrics.medianPeriodMs` — EMA 는 휴식 끼임에 끌려가 중앙값 사용) |

- 모드는 **종목별 저장**(`ModeStore`, SharedPreferences) — 스쿼트는 숙련, 새 종목은 코치일 수 있다. 세션 패널 헤더의 칩으로 전환.
- **렙별 극값 로깅**: `RepRecord(tMs, cycleMin, cycleMax, valid)` 를 세트 로그 `reps.min/max/valid` 배열로 기록(양 모드) + `mode` 필드. 후반 드리프트(피로)·깊이 일관성을 오프라인에서 렙 단위로 분석하는 원자재 — §29 설계의 "장기 드리프트 스냅샷"과 "자기 참조 판정" 검증이 여기서 출발한다.
- **모든 사용자 원칙 유지**: 모드는 발화·표시 정책만 가른다. 임계값·파라미터는 모집단(AIHub) 산출 그대로이며 특정 사용자로 튜닝하지 않는다.

**미구현(다음 단계)**: 세트 후 자가 라벨("좋았음/의도적 변형/무너짐" 한 탭 — 숙련자 라벨은 최고 품질 재보정 데이터), 세트 의도 태그(파셜 블록/템포/PR), 자기 참조(폼 프로필) 판정 — 프로필의 세트 간 산포 실측이 선행 조건, 온보딩 모드 질문·행동 감지 제안.

## 30. 세트/세션 자세 리포트 + 자가 라벨 (`PostureSetReport.kt`, `PostureSetLabel.kt`, 2026-09-02)

**동기**: §22 의 실시간 음성은 흘러가고 세트가 끝나면 남는 것이 없었다 — 전반→후반 요약은 랩 화면(개발용)에만 있었고, 기록 화면의 정확도 %·"자세 지적" 은 `defaultPostureFocus()` 하드코딩 목업이었다. 실측 세션을 돌려도 사용자가 다시 볼 수 있는 결과가 없으니 §9 재보정에 필요한 라벨도 모이지 않는다. 리포트는 **세트 로그(§14-1)에서 파생되는 값**이고 `setId` 로 그 JSONL 을 가리킨다 — 진실은 로그 하나이며, 리포트·기록 화면·라벨은 전부 그 id 로 묶인다.

**모델** (`PostureSetReport.kt`, posture 패키지 — app 패키지 의존 없음):
| 항목 | 내용 |
|---|---|
| `RuleOutcome` | 규칙 하나의 세트 결과: `overall`(세트 전체 집계 `PostureRuleSet.evaluate` 판정) + `kind`(`LiveCoach.summarize()` 의 초반 8프레임 vs 후반 8프레임 onset, 없으면 null) + `direction`(반대측 가드 §24) + `observation`/`fix`(끝 마침표 없는 문장 조각) + `note`(`measurementNote`, §25e) + `beta`(`RuleStatus.BETA`) + `cvAuc`. `label` 은 `OnsetState.label` 과 같은 어휘("처음부터/점점 흐트러짐/교정됨") 에 세트 중간 위반(kind null + VIOLATION) 의 "위반" 만 추가, 유보/정상은 그대로 |
| 랭킹 (`rank`) | 0: overall VIOLATION 이고 kind ∈ {HABIT, DRIFT}(가장 확실) → 1: DRIFT(피로형, 두 모드 모두 가치) → 2: HABIT → 3: overall VIOLATION 이지만 창에서 안 잡힘(세트 중간 위반) → 4: RECOVERED → 9: 후보 아님. 동률은 ship 우선 → cvAuc 내림차순 |
| `PostureSetReport` | `items`(랭킹순 전체 규칙, ABSTAIN 포함) 위에 파생값: `judged`(OK+VIOLATION), `abstained`, `okCount`, `candidates`(rank<9), `demoted`, `headline`(첫 **non-beta** 후보), `highlights`(후보 3개), `verdict`, `accuracy`, `summaryLine`(기록 화면 한 줄), `voiceLine`(세트 종료 발화). 렙 카운터(§27) 적용 종목이면 `repsValid/repsPartial/tempoMs`, 미적용이면 null |
| `verdict` 5종 | `UNJUDGED`(judged==0) / `ISSUE`(non-beta 후보 중 rank≤3) / `RECOVERED`(non-beta 후보가 전부 교정됨) / `REFERENCE`(non-beta 후보 없고 beta 후보만) / `CLEAN`(그 외) |
| 베타 = 헤드라인 불가 | §28 실기기 오탐 3건이 **전부 베타/미보정 규칙**이었다. 베타 위반은 `highlights` 에 남기되 verdict 는 REFERENCE 로 낮추고, 발화도 "아직 검증 중인 항목이라 참고만 하세요" 로 밝힌다 |
| 점수 | `accuracy = round(100·shipOk/shipJudged)` — 분모는 **검증된(ship) 규칙 중 판정한 수**지 전체 규칙 수가 아니다. 유보를 정상으로 세면 화면에 덜 잡힌 세트일수록 점수가 오르는 거짓 신호가 되고, 베타를 세면 "참고만 하세요" 라던 항목이 점수를 깎는 자기모순이 된다. UI 는 `shipOk/shipJudged` 분수 표기로 분모를 드러내고 베타는 "참고 n건" 으로 따로 센다. judged==0 이면 점수·"깨끗" 둘 다 금지(UNJUDGED: "화면에 충분히 잡히지 않아 판정하지 못했어요"), 베타만 판정된 세트(바닥 종목 전부)는 `betaOnly` — 점수 없이 "검증 중인 항목 기준으로는 이상 없었어요" |
| TRACK(기록) 모드 | §29 의 연장: `accuracy` 는 **항상 null**(모집단 판정을 숙련자에게 점수로 보이지 않는다). 후보는 **세트 내 변화(DRIFT/RECOVERED)만** — HABIT 도, 창에서 안 잡힌 세트 전체 위반(kind null)도 모집단 임계 기준이라 본인 스타일일 수 있으므로 `candidates` 에서 빼 `demoted`("측정 기록" 으로 접어 표시) 로 강등. `summaryLine`/`voiceLine` 은 "N렙 · 파셜 M · 템포 x.x초 (· {부위} 점점)" — 렙·템포가 없으면 "기록됨" |
| 문구 조립 | `CoachCues.cueFor(rule, direction)` 의 습관/드리프트/교정 문장을 `splitCue("A. B.") → ("A","B")` 로 관찰·교정으로 가르고, kind==null(세트 중간 위반) 이면 관찰 앞의 "처음부터 " 를 뗀다(초반 창이 정상이었으므로 그 말은 거짓) |

**데이터 흐름**: `PostureLive` 의 세트 마감 람다(`finalizeRef`) — **✓/✕ 핸들러에서 먼저** 호출, `onDispose` 는 안전망(멱등) — → `onSetReport` → `AppViewModel.sessionPostureReports`(workoutId → report, 맵에만 둔다) → 마지막 운동의 `nextSession()` 이 `recordCompletedSession()` 으로 `createWorkoutHistoryDay(plan, elapsed, reports)` 병합 → `WorkoutHistoryItem.postureCorrection`(확장 필드: kind/bodyPart/fix/note/beta/setId/mode/judged/abstained/reps/tempo/actualReps/formLabel). 병합은 이 한 곳뿐 — 도착 즉시 오늘 기록을 채우는 "지연 도착 보정" 은 ✕ 중도 이탈 세트를 그날 앞선 세션 기록에 섞어 넣어 뺐다. 이어하기(✕ 뒤 재시작)는 이미 마친 운동의 리포트를 `clearSessionReports(keep)` 로 남긴다. 세트 마감의 규칙 평가·렙 시각 복사는 분석 스레드의 `aggregator.add`/`onFrame` 과 같은 락 안에서 — 겹치면 CME 로 결과가 비어 멀쩡한 세트가 UNJUDGED 가 됐다. §30 이전 기록(`postureKind` 없음)의 자세 칸·정확도는 전부 시드 목업이라 `loadHistory` 가 버린다.

**화면 3곳**: ① 세트 종료 음성 `voiceLine` 한 줄 — 스피커(`SpeechCoach`)는 **세션 스코프**(`TrexApp` 소유): 라이브 화면이 소유하면 자세→타이머 전환마다 `shutdown()` 이 문장을 0.4초 만에 끊는다(기본 플랜은 스쿼트→플랭크, 런지→푸쉬업이라 매번). 마지막 운동도 말하고, 완료 화면의 세션 요약은 같은 큐 뒤에 붙는다(음소거도 공유). 세트 경계 9초 동안 코치·커버리지 발화는 flush 대신 큐잉, 시작 안내도 QUEUE_ADD. 바닥→서서 하는 종목 전환 시 커버리지 상태를 리셋한다(안 풀면 새 종목 내내 코칭이 막힌다). ② 완료 화면 자세 블록(운동별 verdict·헤드라인 관찰/교정·`shipOk/shipJudged` 분수·베타 "참고 n건"·ⓘ 근거·TRACK 은 렙/템포와 접힌 "측정 기록"), ③ 기록 화면은 COACH 면 관찰(`observation`)+"다음엔 {fix}" 두 줄, TRACK 만 `summaryLine` — 정확도 %는 `accuracy`(TRACK 은 null 이라 숨김), 자가 라벨은 "내 평가 · 좋았음 · 실제 n회 (앱 m)" 로 카운터 오차를 드러낸다.

**자가 라벨** (`PostureSetLabel.kt`): `FormLabel { GOOD, INTENDED, BROKE }` = §29 가 미구현으로 남긴 "좋았음 / 의도적 변형 / 무너짐" 한 탭 + 실제 횟수 입력. `SetLabelStore(context)` 는 `SetLogStore` 와 같은 디렉터리(`externalFilesDir/posture_logs`) 라 adb pull 한 번에 같이 나온다:
| 파일 | 언제 | 형식 |
|---|---|---|
| `labels/set_labels.jsonl` | 항상 한 줄 | `{"set_id","exercise","actual_reps"(null 가능),"reps_source"(edited/confirmed, null 가능),"form"(good/intended/broke, null 가능),"created_at"}` — 앱 쪽 진실 기록. **하위 폴더**인 이유: `SetLogStore.files()/clear()/totalSets()` 와 `pull_logs.py` 가 `*.jsonl` 로 세트 로그를 고르므로 같은 폴더면 랩 "지우기"가 라벨을 삭제하고 세트 수에 라벨 줄이 섞인다 |
| `rep_truth.csv` | `actualReps` 있을 때만 | 헤더 `set_id,reps_min,reps_max,exercise,form,source,created_at`, reps_min=reps_max=actualReps. `rep_replay.py` 가 `set_id,reps_min,reps_max` 를 `int()` 로 읽으므로 횟수 없는 행은 넣지 않는다(파싱이 깨진다). `source`=edited(스테퍼로 고침)/confirmed("이 숫자 맞아요" 로 확인) — 확인·수정 없이 폼만 고른 저장은 렙을 정답으로 넣지 않는다(앱 카운트가 정답으로 흘러가면 재생 검증이 자기 답을 채점한다). 저장은 companion 락으로 직렬화(헤더 중복 방지). `pull_logs.py` 가 세트 로그와 함께 받는다 |

이것이 **렙 카운터 v5 의 정답 데이터**가 되는 이유: [REP_REPLAY.md](REP_REPLAY.md) 의 재생 검증은 라벨 세트 **3개**(적중 2·실패 1) 로 돌아갔고 v4(§27) 확정도 라벨 12렙 한 세트였다 — 알고리즘이 아니라 정답 수가 병목이다. 완료 화면에서 매 세트 횟수를 한 번 입력하면 라벨이 세션마다 쌓이고, `set_id` 로 로그와 1:1 결합되므로 재생 스크립트 변경 없이 곧바로 정답이 늘어난다. `form` 은 §29 의 "숙련자 라벨은 최고 품질 재보정 데이터" 를 위한 것 — INTENDED 세트는 임계값 재적합(§14)에서 위반 정답으로 쓰면 안 된다는 표식이다. 직렬화는 org.json 없이 `SetLogJson.str` 패턴(유닛테스트에서 org.json 이 스텁).

**결정 3건**:
1. **시드 목업 자세 데이터 제거** — `seedWorkoutHistory()` 의 `postureCorrection`/`accuracy` 를 null 로. 지어낸 지적("코어 긴장 유지" 류)이 실데이터의 신뢰를 깎고, 정직성 원칙(판정하지 않은 것을 판정한 것처럼 말하지 않는다)에 반한다.
2. **운동 사이 인터스티셜 없음** — 세트 종료 결과는 음성 한 줄로만, 다음 운동으로 바로 넘어간다. 세션 리듬(휴식 타이머)을 화면 하나가 끊는 것보다 완료 화면에서 한 번에 보는 편이 낫다.
3. **세션 중도 이탈은 기록하지 않음(유지)** — 기존 정책 그대로. 리포트 맵은 남지만 기록으로 병합되지 않으며, 라벨도 받지 않는다.

**한계·다음**:
- 초반 창 8프레임에 **준비 동작(셋업)이 섞일 수 있다** — 렙 카운터가 잡은 첫 사이클에 초반 창을 앵커링하면 HABIT/DRIFT 구분이 정확해진다(예정).
- 완료 화면 TTS 는 세션 스코프 스피커를 같이 쓰므로 음소거를 따른다(해결). 남은 것: TTS 엔진 초기화 전(첫 세트 직후) 발화는 무음으로 떨어진다(기존 동작).
- 세션 리포트는 **운동 블록 단위**(workoutId 하나 = 리포트 하나) — 한 운동 안의 세트 분할은 없다. 세트별 리포트는 세션 화면이 세트를 구분하게 된 뒤.
- 리포트의 판정·임계값은 여전히 모집단(AIHub) 산출이며 §9 재보정 전이다 — 그래서 점수는 분수, 베타는 참고, TRACK 은 점수 없음이다.

**테스트**:
- `PostureSetReportTest`(15): 랭킹 순서(전체 위반+onset → DRIFT → HABIT) · 동률 ship→베타→AUC · 베타만 위반이면 REFERENCE 이고 헤드라인 없음·점수는 ship 만(100) · 전부 ABSTAIN 이면 UNJUDGED 이고 점수 없음 · 위반 없음 CLEAN · TRACK 은 HABIT 과 kind null 위반 강등·DRIFT 유지·점수 숨김 · TRACK 렙·드리프트 없으면 "기록됨" · 베타만 판정된 세트는 betaOnly(점수 없음·"검증 중") · `splitCue` 관찰/교정 분리 · 세트 중간 위반은 "처음부터" 제거 · 반올림 · RECOVERED 만이면 RECOVERED · rule.id 조인·반대측 · FormLabel 왕복.
- `SetLabelStoreTest`(5): CSV 헤더 1회 + 라벨당 1행(source 열) · reps null 이면 jsonl 에만 · 콤마·따옴표 CSV 인용 · 파일 없으면 count 0 · 라벨 파일이 `SetLogStore` 의 `*.jsonl` 규약(files/totalSets/clear) 밖에 있음.

## 31. 코칭 신뢰성 — 앵커·베타 침묵·평가 범위·음성 채널 (`PostureScope.kt`, 2026-09-03)

**동기**: 엔진은 판정을 하는데, 그 판정이 사용자에게 닿는 경로가 사용자를 잘못 이끄는 자리가 다섯 군데 있었다. 전부 "모르는 것을 아는 것처럼 말한다" 는 한 가지 병의 변형이다.

| 문제 | 실제로 일어나던 일 | 조치 |
|---|---|---|
| **초반 창 = 준비 동작** | `LiveCoach` 의 초반 창(첫 8프레임)은 사용자가 폰을 놓고 걸어와 자세를 잡는 구간이다. 그 구간이 "정상 기준" 이 되므로 첫 코칭이 "처음부터 …" 오탐이 된다 | `LiveCoach(requireAnchor = true)` — `anchor()` 전에는 프레임을 버리고 `evaluate`/`summarize` 가 판정하지 않는다(`lastStates` 도 비어 붉은 강조가 안 뜬다). 호출부는 **첫 렙 완료**에 앵커하고, 렙 신호 없는 종목은 4초·있는 종목은 10초 시간 폴백 |
| **베타가 ship 과 같은 확신으로 말함** | §28 실기기 오탐 3건이 **전부 베타/미보정 규칙**이었는데 라이브는 `includeBeta = true` 로 똑같이 발화·붉은 강조 | `LiveCoach(speakBeta = false)` — 베타는 **발화 후보에서만** 빠진다(판정·`lastStates`·`summarize`·리포트는 그대로). 화면은 붉은색 대신 **호박색**(`0xFFFFC24B`) 강조 + "참고 · … — 아직 검증 중인 항목이에요" 줄. 침묵을 "이상 없음" 으로 읽지 않게 하는 것이 핵심 |
| **못 보는 것을 안 밝힘** | 바벨 데드리프트는 '척추의 중립' 규칙이 전부 `exclude` 라, 허리를 말아도 ship 2규칙(바 궤적·무릎)만 통과하면 리포트가 "이번 세트 깨끗했어요" 라고 말한다 — 거짓 안심 | `PostureScope.of(ruleSet, exercise)`: 조건을 등급별로 묶어 **부위명**으로 압축 → `watched`(ship) / `provisional`(beta) / `blind`(전부 exclude). 세트 시작 안내 셋째 문장("무릎·상체를 봐요. 등·허리는 못 봐요."), 운동 카드 부제(`cardLine`), 카드의 "무엇을 보나요?" 3줄(`introLines`) |
| **음성이 조용히 죽음** | TTS 초기화가 비동기라 세트 시작 안내가 통째로 버려지고, 한국어 음성이 없으면 영원히 무음인데 화면은 이유를 말하지 않는다. 헬스장 음악 위로도 안 들린다 | `SpeechCoach`: 준비 전 발화 **대기 큐**(최대 3건·10초 TTL), 오디오 포커스 `TRANSIENT_MAY_DUCK`, `unavailableReason` 을 패널에 표시. 렙 숫자는 TTS 불가 시 **짧은 톤**으로 대체 |
| **권한 거부가 운동을 삼킴** | 카메라 거부 화면의 "타이머로 계속" 이 `onNext` 를 불러 그 운동을 **완료 처리하고 건너뛰었다** | `onFallbackToTimer` — 같은 운동을 `TimerSession` 라우트로 다시 연다(`postureFallback` 세션 목록). ✕ 종료도 완료한 운동이 있으면 "여기까지 기록하고 끝내기" 를 묻는다(묻지 않고 버리지 않는다) |

**부수**: 무효 렙 사유 발화는 **세트당 2회 상한** + `romValidated == false` 종목은 침묵 — 미검증 ROM 의 "끝까지 움직이세요" 는 잘못된 가동범위를 유도할 수 있다(REP_VALIDITY.md). 패널 배너는 앵커 전 "보고 있어요 — 편하게 시작하세요" 로, 판정하기 전에 칭찬("좋아요")하지 않는다.

**바닥 종목 주의**: 8종목 14규칙이 **전부 beta** 라 `speakBeta = false` 에서 자세 음성이 사라진다. 그래서 `PostureScope.provisionalOnly` 종목은 시작 안내가 "검증 중이라 지적 없이 횟수와 촬영 상태만 알려드려요" 라고 **먼저 밝힌다**. 화면에는 호박색 강조·참고 줄이 남으므로 정보가 사라지는 것은 아니다.

**기본값**: `requireAnchor`·`speakBeta` 는 서로 **독립**이고 기본값은 종전 동작(`false`/`true`)이다 — 랩 화면(`PostureLabScreen`)은 베타를 일부러 듣는 자리다. 실사용 경로(`PostureLive`)만 `requireAnchor = true, speakBeta = false` 로 명시한다.

**테스트**: `LiveCoachAnchorTest`(7) 앵커 전 침묵·앵커 후 HABIT·재앵커 거부·`summarize` 빈 값·`reset` 후 재앵커 필요·베타 침묵/`speakBeta` 옵트인. `PostureScopeTest`(8) 등급 분류·ship+exclude 혼재 시 watched·부위 압축·`startLine`/`cardLine`/`provisionalOnly`·규칙 없는 종목. 전체 133건 통과.

**한계·다음**: 규칙별 **위험 등급**(척추/무릎 vs 시선)이 없어 발화 후보 선택은 여전히 지속시간→AUC 순이다 — `risk` 필드와 트리거 큐(짧은 말 즉시 + 교정문은 렙 사이)가 다음이다. 피로 드리프트의 **세트 종료 권고**도 미구현(지금은 같은 교정을 12초마다 반복). `SpeechCoach` 의 대기 큐·오디오 포커스는 유닛 테스트 대상이 아니라 실기기 검증이 필요하다.

## 31a. 세트 점수도 앵커한다 — 준비 동작이 range 통계를 뒤집던 실기기 오탐 (2026-09-03)

**사건**: 실기기 스쿼트 5회 세션(`sets-20260903.jsonl`, `2026-09-03T01:47:57Z`)에서 자세 점수가 2/3 로 나왔다. 사용자는 "너무 박하다" 고 보고했고, 로그를 열어 보니 자세 문제가 아니었다.

| 확인 | 값 |
|---|---|
| 렙 카운터 | **정상** — 5회 전부 유효 (t = 53.7 / 56.5 / 59.4 / 62.3 / 64.9s) |
| 위반 판정 | '척추의 중립[flexion]' = `torso_incl__range` **68.6** > 임계 30.85 |
| 실제 5렙 구간(47.5s~)만 재계산 | range **27.4** → **정상** |
| 68.6 을 만든 것 | 43.8~46.8s 의 **3초짜리 사건**(상체 69.5°) — 렙이 시작되기 전의 준비 동작 |

99프레임 중 10프레임이 세트 전체 판정을 뒤집었다.

**원인**: §31 의 앵커는 `LiveCoach`(실시간 코칭·onset 분류)에만 걸려 있었고, 세트 판정과 점수를 내는 `aggregator` 는 **첫 검출 프레임부터** 계속 누적하고 있었다. "초반 창을 실제 운동 구간으로 옮긴다" 는 §31 의 취지가 정작 사용자가 보는 숫자에는 적용되지 않았다. `range`/`min`/`max` 처럼 극값에 좌우되는 통계에서 이 구멍은 판정을 통째로 바꾼다 — §28b 의 '극값 통계 금지' 원칙과 같은 병이다.

**조치**
- 앵커가 잡히는 순간 `aggregator.reset()` — 첫 렙 완료 또는 시간 폴백 시점부터만 집계한다. 프레임 기록(`recordedSamples`)은 그대로 전부 남긴다: 재보정 도구가 원본에서 다시 계산한다.
- `SetLog.anchorTMs` → 로그의 `anchor_t_ms`. `results` 가 어느 창을 본 값인지 오프라인 재계산이 알아야 한다.
- 라이브 점수 분모에 beta 가 섞여 화면과 리포트가 다른 숫자를 말하던 것 → 리포트와 같은 **ship 전용 분모**로 통일.
- **"67%" → "2/3" 분수 표기.** 검증된 규칙이 종목당 2~4개뿐이라 한 건 위반이 33%p 를 깎는다. 퍼센트는 성적처럼 읽히지만 실제 의미는 "3가지 중 2가지 정상" 이다.
- 종목 전환 시 이전 운동 점수가 남던 버그 수정.

**남는 문제**: 앵커 후에는 '고개 정면'(`head_pitch__mean` −15.6 vs 임계 −17.2)이 위반으로 바뀐다. 이 규칙은 JSON 의 caution 이 이미 "임계 여유 얇음(사용자 소급 −12.7~−19.3 vs 임계 −17.2) — 기준선 재배치 권장" 이라고 적어 뒀고 `personal_baseline.threshold_rel = 6.39` 를 갖고 있다. **개인 기준 세트(§31 설계, 미구현)가 있어야 제대로 판정된다.**

**후속 수정 — 앵커 기준 시점 (검증에서 발견)**: 위 조치만으로는 **이 세션이 고쳐지지 않았다**. 폴백의 기준 시점이 '첫 검출 프레임' 이었는데, `PostureAnalyzer` 는 가시 관절이 몇 개든 `detected = true` 를 돌려준다(이 로그의 첫 프레임은 관절 11개). 그래서 사람이 아직 프레임에 제대로 없는 동안 10초가 다 흘러 **앵커가 10.0s 에 걸렸고**, 43.8s 준비 동작이 그대로 집계에 들어갔다(10.0s~ 창의 range 도 68.6 그대로).

| 창 | 프레임 | range | 판정 |
|---|---|---|---|
| 전체 | 99 | 68.6 | 위반 |
| 첫 **검출** + 10초 (= 10.0s~, 수정 전) | 99 | 68.6 | 위반 |
| 스파이크(>55°) 10프레임만 제거 | 89 | **37.3** | **여전히 위반** |
| 첫 **측정 가능** + 10초 (= 47.5s~, 수정 후) | 67 | 27.4 | 정상 |

→ "스파이크 몇 프레임이 판정을 뒤집었다" 는 틀린 요약이다. 원인은 **앵커 이전 32프레임 전체**(최솟값 0.93°·최댓값 69.5° 가 모두 준비 구간)다. 기준 시점을 `features.isNotEmpty()` 인 첫 프레임으로 바꿔 해결했다. **"검출됐다" 와 "측정 가능하다" 는 다르다** — 이 구분은 앵커 밖에서도 유효하다.

**로그 해석 주의**: `aggregator` 는 앵커 시점에 리셋되지만 프레임 기록은 세트 시작부터 계속이라, 로그의 `results` 와 `frames` 는 **서로 다른 창**을 본다. 오프라인 재계산은 `anchor_t_ms` 이후 프레임으로 다시 계산해야 하고, 그 필드가 없는 구버전 로그는 전체 창 기준이다.

**렙 카운터**: 이 세션은 5회로 카운트됐지만 신호상 **6사이클**이 있었다 — 마지막 렙은 상단 확정에 다음 하강이 필요한 구조 때문에 세트가 끝나며 누락됐다. 세트 마감 시 `pendingBottom` 승격 여부는 미해결.

**부수 관찰**: 이 세션의 `tilt_deg = 42.9` — 폰이 43° 눕혀져 있었다. 중력축 보정은 들어가지만 카메라 투영 왜곡은 보정되지 않는다.

**로그 스키마 주의**: 렙 정보는 중첩된 `reps` 객체(`{count, invalid, signal, t_ms, min, max, valid}`)에 있다. `rep_count` 같은 평면 키로 찾으면 "렙 카운터가 죽었다" 는 잘못된 결론에 이른다(이번 분석에서 실제로 한 번 헛짚었다).

## 32. 규칙별 오탐률·신뢰구간 + §28c 임계값 재적합 (`rule_confidence.py`, [RULE_CONFIDENCE.md](RULE_CONFIDENCE.md), 2026-09-05)

**동기**: 규칙 JSON 은 교차검증 AUC·균형정확도의 **점추정만** 실었다. 규칙당 39~60클립·수행자 24~44명이라 AUC 표준오차가 ±0.05 안팎인데 그 폭이 어디에도 없었고, 서서 종목 JSON 에는 바닥 JSON 의 `normal_fpr` 에 해당하는 "정상 클립을 위반이라 하는 비율"이 없었다. 게이트가 AUC 하나뿐이라 **임계값이 죽은 규칙**(검출률 0)을 걸러낼 수 없었다.

**방법**: `outputs/` 를 처음부터 재생성(파싱 34,468클립 → QC → 실험 B → 룰엔진 v0 → MP 재추론 166,923장 38분 → 실험 A → 재적합 2종, pandas 3.0 에서 전부 동작 확인) 한 뒤, 실험 A 와 같은 경로로 MP 클립 피처를 만들되 **p10/p90 을 추가**하고(Kotlin `FeatureAggregator` 와 같은 정의) 각 ship/beta 규칙을 `view_best_front` 뷰에서 **출시 임계값 그대로** 판정했다. 수행자 복원추출 부트스트랩 B=1000 으로 95% 구간. 피처 경로 검증: MP 로 처음 맞춘 beta 규칙 3건(라잉 트라이셉스 팔꿈치·사이드 레터럴 상완-전완·크로스 런지 앞다리)은 같은 절차의 재적합에서 임계값이 소수점까지 재현됐다(83.51 / 76.0 / 13.02).

**발견 1 — §28c 로 통계를 바꾼 규칙 7건의 임계값이 GT 3D 분포 위에 있었다.** §28c 감사는 GT 3D 기준이었고('한계' 항목), 극값→p10/p90/mean 교체 뒤 임계값을 MP 피처에서 다시 맞추지 않았다. MP 실측:

| 규칙 (통계) | 재적합 전 실측 | 재적합 후 |
|---|---|---|
| 덤벨 체스트 플라이 팔꿈치 (mean) | 검출률 **0.00** — 죽은 규칙. 항상 OK 라 점수를 부풀림(원칙 1 위반) | 154.5→138.2, 검출률 0.85, 정상 오탐률 0.23 |
| 스탠딩 사이드 크런치 양손 (mean) | 정상 오탐률 **0.77** | 0.40→1.03, 오탐률 0.10, 검출률 0.93 |
| 딥스 이완 팔꿈치 (p90) | 정상 오탐률 0.39 | 50.9→43.6, 오탐률 0.11, 검출률 0.69 |
| 바벨 런지 뒤다리 (p10) | 검출률 0.35 | 0.380→0.440, 검출률 0.82, 오탐률 0.17 |
| 바벨 데드리프트 궤적 (p10) | JSON `cv_auc` **0.60** 은 낡은 값, 검출률 0.64 | −0.103→−0.038, cv AUC **0.97**, 검출률 1.00, 오탐률 0.06 |
| 바벨 스티프 데드 궤적 (p90) | 검출률 0.70 | 1.033→1.012, 검출률 0.78, 오탐률 0.08 |
| 바벨 데드리프트 무릎 방향 (p10) | 정상 | −0.0298→−0.0260, 오탐률 0.19 |

조치: 피처·통계·방향은 유지하고 임계값만 MP 피처에서 `fit_rule_cv`(단일 피처·수행자 GroupKFold·Youden)로 재적합. 이전 값은 `threshold_before_s32`·`cv_auc_before_s32`·`cv_balacc_before_s32` 에 보존, `violation_if` 문자열 갱신. 데이터가 고른 방향이 JSON `op` 와 다르면 건드리지 않는다(이번엔 0건).

**발견 2 — 분포.** ship 55건의 정상 오탐률 중앙값 **0.13**, 구간 상한 중앙값 0.29. AUC 구간 하한 중앙값 0.86, 최소 0.67. 정상 오탐률 ≤ 0.10 인 ship 은 **15건**뿐 — 스펙 §9 의 출시 기준(균형정확도 ≥ 0.80 · 오탐률 ≤ 10%)을 스튜디오 데이터에서조차 통과하는 규칙이 15/55 다. §9 재보정의 시급성이 이 숫자다.

**게이트 s32** (`status` 재판정): ship 유지 = AUC ≥ 0.85 · AUC 구간 하한 ≥ 0.75 · 균형정확도 ≥ 0.75 · 정상 오탐률 ≤ 0.35 · 검출률 ≥ 0.50. 탈락 4건 → beta(`status_before_s32` 보존, caution 에 사유):
- 바벨 스쿼트 '고개 정면' — 하한 0.73 · 균형정확도 0.74. §31a·핸드오프 §4 에서 실기기 오탐하던 그 규칙. 이제 음성 침묵·호박색·점수 분모 제외. 스쿼트 ship 은 척추 중립·무릎 방향 2건.
- 사이드 런지 '앞다리 무릎 각도 90도' — 하한 0.73. 이 종목의 유일한 ship 이라 `PostureScope.provisionalOnly` 가 된다.
- 바벨 스티프 데드리프트 '척추의 중립[lateral]' — 하한 0.72(n=41). 앱 미노출.
- 스텝 백워드 다이나믹 런지 '뒤다리 무릎 각도 90도' — AUC 0.78 · 오탐률 0.36. 앱 미노출.

결과: **ship 51 · beta 20 · exclude 70**. 헤더 `counts`·`mirror_safe_counts` 는 이제 스크립트가 실제 분포로 갱신한다.

**JSON 필드**: ship/beta 규칙마다 `confidence{method, n, n_normal, n_violation, n_performers, normal_fpr, normal_fpr_ci95, normal_fpr_with_guard, tpr, tpr_ci95, balacc, balacc_ci95, auc, auc_ci95, normal_median}`. Kotlin 로더는 모르는 필드를 무시하므로 앱 코드 변경 없음(유닛 테스트 통과). 기준선 재배치가 쓰는 `normal_median` 이 서서 종목에도 생겼다.

**한계**: (1) 스튜디오 수치다 — 실기기 오탐률은 별개다(§28·§31a). (2) 임계값 고정 in-sample 이라 Youden 선택 편향만큼 낙관적이다. (3) `heel_lift` 는 연구 피처에 없어 제외(beta·requiresBaseline 이라 영향 없음). (4) `rules/rules_mp_v0.md` 는 export 산출물이라 §28 이후 갱신되지 않았다 — JSON 이 정본. (5) `[all]`/하위유형 중복과 로더 필터 문제는 이 절의 범위 밖(핸드오프 §8).

**재현**: `python rule_confidence.py --refit-s28c`(보고) → `--refit-s28c --apply --gate s32`(JSON 갱신) → `cp rules/rules_mp_v0.json ../../app/src/main/assets/posture/`.

## 33. 촬영 뷰 추정기 + 규칙별 판정 허용 뷰 (`view_estimator.py`, `PostureView.kt`, [VIEW_ESTIMATOR.md](VIEW_ESTIMATOR.md), [RULE_VIEWS.md](RULE_VIEWS.md), 2026-09-05)

**동기**: 규칙의 `view_best_front`·`mirror_safe`·"valgus 는 정면(C)에서만" caution 은 전부 **사용자가 안내대로 폰을 놓았다는 가정**이다. 앱에 그 가정을 확인하는 코드가 없어서(§32 감사), 폰이 뒤·옆·반대편에 있어도 ship 미러 비안전 5건·정면 전용 3건이 그대로 점수에 들어갔다.

**추정기**: MediaPipe 월드 좌표는 카메라 정렬 좌표계(x 오른쪽, y 위, z 카메라 쪽 — 앱·연구 같은 뒤집기)라 어깨선·골반선의 x–z 평면 방향이 곧 몸의 요(yaw)다. `u = unit(−(RSh−LSh).x, (RSh−LSh).z) + unit(−(RHip−LHip).x, (RHip−LHip).z)`, `yaw = atan2(u.z, u.x)` — 정면 0°, 후방 ±180°, 부호가 좌/우. 프레임마다 `view_cos`/`view_sin` 을 다른 프레임 피처와 같이 내고(`PostureAnalyzer`), 창 평균의 atan2 로 세트 요를 잡는다(원형 평균, 결과 벡터 길이 R = 일관성). 등급: |yaw| ≤ 16.4° **C**, ≤ 46.2° **B/D**(yaw 양수 = B), < 81.7° SIDE_B/SIDE_D(미검증, 각각 B·D 와 같은 쪽의 옆), < 163.6° **A/E**, 그 이상 **R**(순수 후방); R < 0.7 또는 프레임 < 8 → UNKNOWN. **좌우는 사용자 기준이다**: yaw > 0 은 오른어깨가 카메라에 더 가깝다는 뜻이고(z 가 카메라 쪽), 그것이 AIHub 코드 B 다 — 데이터셋 이름 '전방사선L' 은 카메라 쪽에서 본 왼쪽 = 사용자의 오른쪽. `ViewGuide.placement` 가 미러 비안전 규칙에 "왼쪽" 이라 하던 문구를 "사용자 기준 오른쪽" 으로 고쳤다(거울처럼 반대로 놓게 하던 문구). MP 는 ±40° 를 약 ±33° 로 압축해 읽는다(깊이 축 과소추정).

**정답은 카메라 코드가 아니었다.** 서서 종목 (클립,뷰) 8,042개 중 **13.7%** 에서 수행자가 명목 정면을 등지고 있었다(GT 2D 어깨 순서 실측). 케이블 푸시 다운·페이스 풀·케이블 크런치는 **100%** 기구를 향해 카메라 C 를 등졌고, 로잉머신 50%·덤벨 풀 오버 41%. 처음 카메라 코드로 채점했을 때 "정면 클립의 16% 가 후방으로 오분류"로 보였던 것은 추정기가 아니라 정답의 문제였다. 어긋난 클립을 C→R, B→E, D→A, A→D, E→B 로 보정한 정답 기준 결과: UNKNOWN 4.7% 제외 **등급 정확도 0.965 · 계열(C/B/D/후방) 0.968 · 전/후 반구 0.982**. 남은 혼동은 후방 사선의 좌우(A↔E)와 전방 사선 46° 초과의 SIDE 편입. 벤치 종목(체스트 플라이 2종·라잉 트라이셉스·덤벨 풀 오버)과 로잉머신은 기하가 달라 무의미하다 — 전부 앱 미노출. 시도했다가 버린 단서: 얼굴 랜드마크 가시성·presence(전 뷰에서 1.0), 코 깊이(어깨선 부호와 같은 케이스에서 같이 틀림).

**규칙별 판정 허용 뷰 `views_ok`** (`rule_confidence.py --views`): 5뷰 카메라의 (클립,뷰) 전부를 **추정기가 낸 등급**으로 나눠 출시 임계값 그대로 정상 오탐률·검출률·균형정확도를 재고, 표본 ≥ 20(각 라벨 ≥ 5)·균형정확도 ≥ 0.75·오탐률 ≤ 0.35·검출률 ≥ 0.5 인 등급만 허용한다. 런타임이 보는 것도 추정기 출력이므로 같은 단위여야 자기 일관적이다. 결과: 규칙 70개 중 학습 뷰 등급에서 허용 55개, B·D 양쪽 허용(미러 무관) 26개. **같은 종목의 규칙이 서로 다른 뷰를 요구하는 경우가 드러났다** — 랫풀 다운은 한 규칙이 B 만, 다른 규칙이 D 만; 바벨 데드리프트는 궤적 D·무릎 방향 C. export 가 규칙별 최적 전방 뷰를 따로 골랐기 때문이며, 게이팅 전에는 한쪽이 미검증 뷰에서 판정되고 있었다. 종목별 최적 배치 표는 RULE_VIEWS.md §3.

**앱** (`PostureView.kt`, `PostureRules.kt`, `PostureSetLog.kt`, `PostureSetReport.kt`, `PostureLive.kt`):
- `PostureRuleSet.evaluate` 가 창의 `view_cos/view_sin` 평균에서 뷰를 추정하고, `views_ok` 가 비어 있지 않은 규칙은 추정 등급이 목록 밖이면 **ABSTAIN**(`RuleResult.abstainReason = "촬영 방향 · 뒤"`). UNKNOWN·프레임 부족·방향 피처 없음(바닥 경로)이면 게이팅하지 않는다 = 종전 동작. LiveCoach 의 초반/최근 창에도 같은 게이팅이 걸리므로 음성도 막힌다.
- 세트 로그에 `view{yaw_deg, r, class, frames}`(세트 전체 프레임 기준). 실기기 로그에서 사용자가 실제로 폰을 어디에 두는지가 처음으로 남는다.
- 리포트 라벨 "유보 · 촬영 방향 · 뒤", 라이브 패널에 "촬영 방향 · 정면" 또는 경고 "카메라가 뒤에 있어요 — 앞쪽에서 비스듬히 두어야 자세를 판정해요".
- 테스트 `ViewEstimatorTest`: 파리티 픽스처 120 프레임(yaw 0.05°·cos/sin 1e-4·등급 일치), 합성 회전, 원형 평균·UNKNOWN, 게이팅·비게이팅.

**한계**: (1) 실기기 미검증 — 폰을 정면·좌우 사선·옆·뒤에 두고 로그의 `view` 를 대조하는 세션이 필요하다. (2) SIDE 는 미검증 등급이라 어떤 규칙도 허용하지 않는다 = 옆에서 찍으면 전부 유보. 이건 결함이 아니라 스펙 §8("순수 측면 뷰 미검증")의 집행이다. (3) 한 종목의 규칙이 뷰를 나눠 갖는 문제는 뷰별 임계값(`thresholds_by_view`) 또는 `ViewGuide` 가 RULE_VIEWS §3 의 최적 배치를 안내하는 것으로 풀어야 한다. (4) 바닥 경로는 손대지 않았다(누운 자세는 뷰 코드의 뜻이 다름).

**재현**: `python view_estimator.py`(VIEW_ESTIMATOR.md·view_thresholds.json·view_fixture.txt) → `python rule_confidence.py --views --apply`(views_ok 주입) → 에셋 복사. 임계값 상수를 바꾸면 `PostureView.kt` 와 픽스처를 함께 갱신한다.

**실기기 검증 프로토콜** (`device_validation_plan.py` → [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md), `device_validation_check.py`): 어떤 종목을 몇 세트, 폰을 어디에 두고 찍을지와 그 배치에서 판정/유보돼야 할 규칙을 규칙 JSON 의 `views_ok`·`personal_baseline.required` 에서 생성한다(JSON 이 바뀌면 프로토콜도 바뀐다). 최소 코스 = 맨몸 7종목 16세트(기본 스쿼트 8방향 전수 + 뷰가 갈리는 굿모닝·사이드 크런치 + 재적합 규칙 위반 세트), 전체 33세트. 채점기는 `pull_logs.py` 로 회수한 로그를 같은 종목의 시각 순서로 계획과 짝지어 추정 등급·반구·좌우, 판정/유보 일치, 위반 세트의 대상 규칙 verdict 를 표로 낸다. 합격 기준은 문서 §0.

## 34. 바닥 종목 '올바른 자세' 규정 ([FLOOR_POSTURE_DEFINITION.md](FLOOR_POSTURE_DEFINITION.md), 2026-09-10)

**동기**: 바닥 8종목의 자세 평가가 실사용에서 아무것도 말하지 않는다. 14규칙 전부 beta 라 §31 이후 설계상 침묵이고, 그중 2건(크런치 견갑골·플랭크 정렬)은 감사에서 신뢰 불가였다. 2026-09-10 실기기 플랭크 51초: 사용자가 '무너짐'으로 라벨한 세트에서 35~40초에 골반이 +0.13 몸통길이(25~30° 꺾임) 올라갔는데, 규칙은 세트 **평균** 158° vs 임계 133° 로 OK — 임계까지 47° 남았고 평균이 유지 구간에 묻힌다. 조건 목록이 아니라 **기하 규정·판정 유형·방향·임계값 출처**까지 정한 것이 규정이라는 결론.

**규정 원칙 4**: ① 측면 2D 에서 관측 가능한 것(머리 각·큰 분절 정렬·각도)만 판정하고 못 보는 것(허리 지면 고정·긴장 유지·견갑골·교차·엄지)은 규정만 남겨 `PostureScope` 로 밝힌다 ② 판정 유형 셋 — 홀드형(밴드 이탈 2초 지속 = 무너짐 이벤트, 세트 평균 금지) · 렙형 ROM(렙별 극값, `RepSignal` 경로) · 유지형(앵커 이후 렙 구간 p10/p90, 세트 끝 정리 동작 컷) ③ 방향 분리(처짐/솟음·젖힘/숙임) ④ 임계값은 정상 분포 p10/p90 + 사용자 접지선 reanchor(k=3), 연기 위반 분포에 맞추지 않는다.

**처분**: 유지 5(시선·고개 3·손 위치) · 세트→렙별 통계 교체 2(깊이·힙쓰러스트 상단) · 재정의 3(플랭크 정렬 부호+시간 이벤트, 힙쓰러스트 고개 std→수준형, 시저 다리 거리 방향 분리) · 판정 중단 2(크런치 견갑골, Y 레이즈 경추) · 신규 후보(푸시업 몸통 일직선 등). 크런치·Y 레이즈는 폰 1대로 확정 판정 가능한 조건이 0개라 "횟수 + 근사 + 못 보는 것 명시"로 스코프 축소. 엔진에 필요한 것: 홀드형 판정기, 렙형 판정의 규칙 승격, 세트 끝 컷(§31b 후보), 바닥 높이 자체 수집(§17).

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
| `PostureSetReport.kt` | 세트 종료 리포트 — 집계 판정 + onset 을 규칙별 `RuleOutcome` 으로 조인, 랭킹·verdict·점수(분수)·요약/발화 문구 (§30) |
| `PostureScope.kt` | 종목별 평가 범위 — 조건 등급(ship/beta/exclude)을 부위명으로 압축, 시작 안내·카드 부제 문구 (§31) |
| `PostureScopeUi.kt` | 규칙셋 캐시 + `rememberPostureScope` — 운동 카드가 세션 밖에서 범위를 읽는다 (§31) |
| `PostureSetLabel.kt` | 세트 자가 라벨 저장 — `set_labels.jsonl`(항상) + `rep_truth.csv`(횟수 있을 때, `rep_replay.py` 정답) (§30) |
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


## §35 — 바닥 시간·반복 측정 구현 (2026-09-11)

§34 처분표를 `floor_v0.3`에 반영했다. `FloorTemporal.kt`는 Android에 의존하지 않는 시간·반복 엔진이다.

- `kind=window|hold|rep`를 로더가 읽는다. 일반 집계기는 hold/rep를 ABSTAIN으로 돌려 세트 평균으로 잘못 통과시키지 않는다.
- 플랭크: 앵커 이후 연속 5초, 관측 범위가 허용폭 이내인 초기 구간의 중앙값을 기준으로 한다. ±0.06은 어깨-발목 선 길이 정규화 단위이며 GT 정상 클립 산포에서 얻은 탐색값이다. 같은 방향 이탈 2초에 첫 지속 이탈 시각·화면상 방향을 기록한다. 결측/750ms 초과 간격은 연속성을 끊고 미관측 시간을 정상 시간으로 세지 않는다. 초기 자세가 올바른지 인증하지 않는다.
- 푸시업/니푸쉬업: 완료 사이클 최소 거리, 힙쓰러스트: 완료 사이클 최대 골반 이탈로 범위 미달을 센다. 2회 미만 검출은 유보, 미달 2회 이상이면서 비율 34% 이상이면 beta 위반. 검출된 반복만 대상이며 마지막 반복 누락 한계는 남아 있다.
- 크런치 견갑골/Y 경추는 exclude. 바닥 경로 식별은 활성 규칙 목록에 의존하지 않는다. 횟수·머리/팔 들림 근사를 남기고 못 보는 부위를 시작 안내에 표시한다. 각 8종목의 자세 기준과 측면 촬영 안내를 라이브 화면에 추가했다.
- 신규 후보는 몸통 정렬 변화, 투영 무릎각, 발목 높이 근사로 화면 표시/원본 피처 기록만 한다. 임의의 새 정답 임계값이나 교정 음성을 만들지 않는다.
- 힙쓰러스트 고개는 std 대신 p10(64.92°), 시저크로스 다리 높이 대리 지표는 정상 p10(96.3°)을 사용한다. 변경 지표의 탐색 AUC는 `exploratory_clip_auc`에 따로 두고 `cv_auc/cv_balacc`는 비운다. 실제 출시 성능으로 해석하면 안 된다.
- 바닥 세트는 점수/깨끗 헤드라인/자세 교정 음성을 제공하지 않는다. 참고 시간·반복 결과는 완료 상세와 기록, 로그 `measurements`에 남긴다. 숫자 카운트는 검출 전체를 알린다.
- 마지막 검출 3회 이상이고 간격이 중앙값 주기의 ±50% 안이면, 마지막 극점 + 한 주기에서 최종 평가 창을 닫는다(서서 종목 포함). 그렇지 않으면 자르지 않는다. 이는 정리 동작을 완벽히 분류하는 모델이 아닌 보수적 시간 휴리스틱이다. 원본 프레임은 보존하고 `assessment_end_t_ms`를 기록한다. 창이 잘리면 원래 전체 구간 onset은 혼합하지 않는다. 실제 마지막 반복의 검출을 보완하지는 않는다.

검증: `FloorTemporalTest` 13건(초기 안정성·지속 이탈·회복·양방향 잡음·결측·반복 미달·짧은 세트·일반 집계 폴백 금지·종료 컷·beta 정직성). 전체 JVM 153건 통과, debug APK 빌드 통과. 실기기 카메라 정확도·오탐률·지속시간 라벨 검증은 아직 수행하지 않았다.

재산출: `.venv312/Scripts/python.exe research/aihub_fitness/floor_rules_v03.py`. 고정 입력 규칙은 `rules/rules_floor_v02_source.json`, 데이터는 기존 outputs parquet. 출력 자산과 연구 규칙은 동일해야 한다. 사람별 분리 검증 및 측면 바닥 높이 촬영 임계 보정은 별도 실측 작업이다.

바닥 반복 카운터는 유효 샘플 간격 1.5초 초과 시 진행 중 사이클을 버려 가림 전후를 하나의 반복으로 잇지 않는다. 기존 완료 카운트는 보존한다. 참고 관측만 있는 세트도 judged=0이면 UNJUDGED를 유지한다.


## §37 — AIHub 휴대폰 재생 평가 경로 (2026-09-11)

목적은 Python 연구 결과를 앱 정확도로 오인하지 않고 실제 단말의 같은 VIDEO 추론·Kotlin 규칙을 검증하는 것이다. `PostureAnalyzer.analyzeBitmap`은 회전 보정된 Bitmap 이후 카메라와 공유하는 진입점이다. `PostureAssessment.evaluate`는 실시간 세트 마감과 재생에서 같은 최종 집계 창/hold/rep 판정을 실행한다.

`AiHubReplayTest`는 `-PpostureReplay` 별도 applicationId에서만 실행하며 기존 앱 데이터를 건드리지 않는다. 클립·뷰마다 추론 추적 상태와 피처/코치를 초기화한다. 같은 클립의 뷰를 섞지 않고 완료 ID별 체크포인트를 저장한다. 명령·APK/manifest/소스 해시·기기·delegate·프레임 추론 시간을 보존한다.

이미지는 VIDEO로 시간순 처리하지만 실제 타임스탬프가 없어 600ms를 가정한다. 카메라/IMU와 실제 스케줄링·UI/TTS를 우회한다. 따라서 시간/반복 정확도는 제외하고 조건별 window 결과를 먼저 채점한다. 전체 클립 창과 앱 앵커 창을 별도로 보존하며 유보는 정상으로 세지 않는다.

파일 조사: 41종목/37,607클립, 앱 매핑 26종목. 원본 tar 36개 전수 헤더 검사에서 Validation 촬영일 Day07·Day19·Day32의 이미지가 없었다. Training 바닥 32클립/160뷰조합/2,560장으로 첫 진단을 완료했다. USB 연결 해제 중에도 단말 계측이 계속 실행됐고 재연결 후 중복 실행 없이 전체 결과를 회수·채점했다. Training 결과로 일반화 성능을 주장하지 않는다.

SM-N976N GPU 추론 중앙값 102ms/p95 120ms, 피처 계산 2,510/2,560장. 고정 권장 뷰의 window 조건 32사례는 앱 앵커·종료 창에서 10건 판정/22건 유보, 전체 프레임 창에서 29건 판정/3건 유보였다. 후자에서도 니푸쉬업·푸시업 고개 위반 누락과 힙쓰러스트 고개 정상 오탐을 확인했다. 플랭크 hold는 20조합 모두 유보였으며 약 9초 합성 시각의 짧은 클립으로 실제 유지 정확도를 검증할 수 없다. 관절 검출률을 자세 정확도로 말하지 않는다.

기존 테스트/평가 APK 빌드와 공유 평가 테스트 2건이 통과했다. 실행/재개 경로와 한계는 `DEVICE_REPLAY_STATUS.md`, 종목 목록은 `DEVICE_REPLAY_INVENTORY.md`, 수치와 불일치 사례는 `DEVICE_REPLAY_RESULTS.md`가 정본이다. 임계값과 규칙 등급을 변경하지 않았다.

## §38 — 정상 표본 비교와 초기 자세 변화 추적 (2026-09-11)

사용자 범위: 기존 데이터·공개 연구와 휴대폰 한 대의 관절 정보로 비교 로직과 시각·음성 안내를 만든다. 새 촬영 연구나 모델 학습을 전제하지 않는다. 정상 기준에 따른 기존 COACH 규칙은 유지하고, TRACK을 모집단 정상→위반 전환이 아닌 직접적인 개인 변화 측정으로 연결했다.

- `PostureComparison.kt`: 기존 RepSignals와 앱 피처를 사용한다. 첫 검출 전 준비 구간을 제외하고 완료된 극점 사이의 프레임에서 신호 양 끝 15% 구간의 중앙값과 이동 범위를 구한다. 직전 극점은 다음 반복에 중복 포함하지 않는다. 각 끝에서 2프레임 이상, 반복 6프레임 이상과 피처 80% 관측을 요구한다. 검출되지 않은 마지막 반복은 만들지 않는다.
- 초기 3개 관측 가능한 반복의 단계별 중앙값을 고정한다. 초기 산포가 큰 피처는 제외한다. 변화 허용폭은 기존 단위의 측정 바닥값(각도 8°, 정규화값 0.06), 초기 3 MAD, 이동 범위의 15% 중 해당 최댓값이다. 이는 정상 자세 임계값이나 임상 기준이 아니라 변화 표시를 위한 공통 탐색 설정이다. 모드별로 바꾸지 않는다.
- 연속 2개 반복에서 같은 방향 변화가 관측되면 변화, 2개 반복에서 초기 범위에 복귀하면 복귀다. 플랭크는 안정된 초기 5초(최소 8프레임), 이후 겹치지 않는 1초 창 2개로 지속 변화를 확인한다. 접지선/분절 정규화 단위를 실제 신체 cm로 환산하지 않는다.
- 가림·일시정지·1.5초 초과 간격은 진행 중 비교와 연속 판정을 끊는다. 확정 초기 기준은 유지하되 미확정 기준은 비운다. 모드 전환은 기준을 바꾸지 않는다. 카메라 전환·화면의 `기준 다시 측정`은 비교 기준을 초기화한다. 거치 이동의 자동 판별은 구현하지 않았으며 이동 시 재측정을 안내한다.
- 두 모드 모두 같은 개인 비교를 계산한다. TRACK 음성/배너는 이 비교의 지속 변화·복귀만 사용하며 기존 모집단 onset은 마감 리포트에서 제외한다. 기존 규칙 값과 로그 판정은 그대로다. 변화값은 점수나 정답 인증으로 승격하지 않는다.
- `NormalPoseReference.kt`와 `normal_pose_reference.tsv`: §37 실제 Android VIDEO 결과에서 **전 조건 정상**인 클립만 사용해 촬영 방향·단계별 표본 최솟값/최댓값 73개를 생성했다. 바닥 8종목의 데이터이며 각 범위는 2클립이다. 원본 GT 관절은 수치에 섞지 않았다. `export_normal_pose_reference.py`로 재생성하고 입력 SHA를 outputs/device_replay/normal_reference_provenance.json에 보존한다.
- 이 참고 범위는 성긴 클립 전체의 양 끝으로, 시간 정답이 있는 반복 범위가 아니다. 독립 검증 성능·출시 임계값으로 해석하지 않는다. COACH 화면에서 선택한 촬영 방향의 표본만 보여 주고, 방향 미선택·표본 없는 바로 옆 방향·측정 불가 시 숫자를 추측하지 않는다. 범위 밖만으로 교정 음성이나 점수를 만들지 않는다. 기존 COACH 교정 경로와 규칙 등급은 변경하지 않았다.
- `PostureComparisonUi.kt`: 초반/현재 수치·변화율(이동 범위에 한함)·범위 막대·부위 강조·재측정 조작. TRACK은 호박색 참고 강조를 사용한다. 화면과 음성은 동일 ComparisonSnapshot을 읽으며 음소거 중 발화 이력을 소비하지 않는다. 음성 간격은 8초이고 말하지 않은 변화의 복귀는 발화하지 않는다.
- 변화 시각과 수치는 기존 measurements로 완료 상세·기록·세트 로그에 보존한다. TRACK 기록에 모집단 기준의 `clean`/교정 지시가 붙지 않도록 변환을 정리했다. 세로 화면의 패널은 화면 높이 55% 이내에서 스크롤해 카메라 영역이 사라지지 않게 한다.

완료 화면도 TRACK에 "정확하게 끝냈다"는 제목을 붙이지 않는다. 관측 변화가 있으나 규칙 판정이 없는 경우에는 "측정 없음" 대신 "규칙 판정 없음"으로 구분한다.

검증: `PostureComparisonTest` 17건의 단계 일치, 준비 구간 제외, 초기 기준 고정, 변화/복귀 지속, 가림/재설정, 음성 정책, 방향 매칭, Android 재생 7개 반복 종목의 Python/Kotlin 수치 파리티, 실제 RepCounter 연결, 종료 컷 이후 기록 제외. 전체 JVM 172건 통과, 일반 debug APK 빌드 통과. 새 기기 촬영에 대한 정확도나 화면·TTS 실사용 효과를 이번 JVM 검사로 주장하지 않는다.

## §39 — 플랭크 규정과 실시간 판정 연결 (2026-09-11)

원인은 고개 규칙 누락, 잘못된 초기 골반 자세도 기준으로 수용하는 hold, 바닥 촬영 방향의 잘못된 UI 매핑이었다. [PLANK_ALIGNMENT_V04.md](PLANK_ALIGNMENT_V04.md)에 피처·관측 조건·범위 출처·재생 결과를 고정했다.

`PlankGeometry`/`PlankAlignmentTracker`의 `kind=alignment` 3항목이 §35 플랭크 hold를 대체한다. 관측 가능한 한쪽의 골반 부호 이탈·얼굴 방향·목 기울기를 초기 기준과 무관하게 측정하고 1초 지속/80% 복귀/가림 유보를 적용한다. 라이브와 `PostureAssessment`가 같은 엔진을 사용한다. floor_v0.4는 beta 14/exclude 2다.

COACH에는 부위 강조·어깨–발목 기준선·방향별 참고 안내, TRACK에는 초기 대비 변화만 제공한다. 처음 기준은 세 항목이 관측 범위 안에서 안정된 5초를 확보해야 저장한다. 초기 기준의 수집 자격과 임계값은 두 모드에서 같다. 정상 2명/26프레임 재사용으로 넓힌 임시 범위이며 고개 정답 라벨이 없으므로 독립 성능/정자세 인증으로 해석하지 않는다. 소수 표본 범위만으로 다른 규칙을 ship으로 올리지 않는다.

바닥 방향 선택은 C=측면으로 정정했다. `rulesFor`는 같은 조건·피처·종류·등급의 하위 규칙만 `[all]`을 대체한다. 별도 피처/beta 때문에 독립 ship이 사라지던 누락을 수정했다. 화면 상태 및 TTS 수명주기 이벤트를 `feedback-YYYYMMDD.jsonl`로 보존한다.

검증: 전체 JVM 195건, debug 및 Android 테스트 APK 빌드 통과. 실제 Android 좌표 320프레임 수치 파리티/20조합 판정 재생을 포함하며, 이 데이터는 Training 재사용 및 합성 시각 조건이다. 새 촬영 정확도·TTS 실사용 검증과 구분한다.

## §40 — 전체 운동 개인화 설계 (2026-09-11, 구현 전)

사용자 요청은 추가 데이터 수집을 기다리지 않고 현재 자료로 최선을 하는 전체 종목 설계다. [PERSONALIZED_POSTURE_DESIGN.md](PERSONALIZED_POSTURE_DESIGN.md)에 공통 엔진/기준 수집/보정/피드백/검증/구현 순서를, [PERSONALIZED_POSTURE_EXERCISES.md](PERSONALIZED_POSTURE_EXERCISES.md)에 카탈로그 57개와 초기 계획 별칭의 동작 계약을 정했다.

핵심 결정: 관측 가능한 초반 수행 기준은 정오와 무관하게 수집하고, 교정용 기준은 별도로 유지한다. §39 플랭크의 공통 범위 통과를 TRACK 기준 수집에까지 요구하던 조건은 다음 구현에서 분리한다. 모든 모드가 같은 계산을 사용하며 표시·음성 정책만 다르다. 개인 비교는 항목별·좌우별·같은 단계에서 수행한다. 기존 eligible 규칙만 같은 MP 도메인에서 확인한 교정 보정을 사용하며, 전체 사용자별 임계값 학습/체형 회귀/새 모델은 넣지 않는다.

범위: 현재 연결 27개 이름/26개 데이터 종목을 모두 개인 비교에 연결하고 미연결 30개는 관측 변화·유지/범위·가이드 계약을 명시한다. 스탠딩 사이드 크런치의 신호 미등록, 기본/바벨 스쿼트의 개인 기준 혼합, 규칙 라벨과 실제 피처의 불일치도 구현 대상이다. 타이머 묶음 운동에 임의의 정답 자세를 만들지 않는다.

확인한 자산: 기존 MP 집계 10,440행/2,094클립/41종목/110명, 원시 MP 33파일, Android 재생 2,560장. 설계 문서는 현재 코드/카탈로그/자산 목록과 대조했으며, 이번 작업에서는 앱 코드·규칙·APK를 변경하지 않았다. §40은 완료된 기능이나 새 정확도 검증 결과가 아니다.

## §41. 운동 설정·자동 진행·개인 관측 UX (2026-09-11)

구현 계약과 시간 기본값의 근거는 [SESSION_UX_IMPLEMENTATION.md](../../docs/SESSION_UX_IMPLEMENTATION.md)에 기록했다. §40 설계의 관측 기능과 이번 사용자 요청의 운동 진행/UI를 구현하며, 새 정자세 임계값 학습이나 독립 정확도 재평가는 별도다.

- `ExerciseProfiles`에 카탈로그 57개를 등록했다. 기존 정답 규칙 연결은 27개 이름/26개 데이터 종목 그대로다. 그 외에는 관측 범위/유지 비교를 제공하며 마무리 스트레칭·폼롤러 묶음은 타이머로 진행한다. 카메라 사용 가능 55개가 모두 채점 가능하다는 뜻은 아니다.
- 개인 시작 기준은 항목별로 모은다. 반복은 초반 3개, 유지는 안정 5초/8프레임 이상이며 한 항목의 가림/불안정이 다른 항목을 막지 않는다. §39의 `referenceEligible=전체 정렬 OK` 게이트를 개인 비교에서 제거했다. 절대 정렬의 beta 판정은 별도로 유지한다.
- 바닥 피처에 `_L/_R` 기하를 추가했다. 기존 규칙 피처와 임계값은 그대로 유지했다. 교대·빠른·미등록 단계는 좌우별 3초 관측 창 범위를 비교하며 같은 반복 단계로 표현하지 않는다. 기준 재설정은 번호를 늘리고 기존 변화 이력을 보존한다.
- 구형 기준선 TSV에는 도메인·변형·뷰·피처 버전이 없어 라이브에서 자동 보정하지 않는다. 저장 파일/개발용 가이드는 보존했다. 초반 기준을 정답으로 바꾸거나 공통 임계값을 사용자마다 이동시키지 않는다.
- `WorkoutTiming`이 카드/설정/실행의 시간을 계산한다. 목표 횟수×1회 소요 시간, 초/분 유지 시간, 세트 사이 휴식은 편집·저장 가능하다. 준비→세트→휴식→다음 세트로 분해하며 모든 타이머는 0에서 자동 진행한다. 마지막 세트 뒤 휴식과 0초 휴식 화면은 없다.
- `SessionProgress`는 완료/건너뜀을 분리하고 이전 token의 콜백을 거부한다. 카운터 검출 수를 자동 종료 조건으로 쓰지 않는다. 세트별 고유 키로 마감 리포트를 먼저 확정하고 기록에 합친다. 앱 비활성/일시정지/종료 확인 중에는 시간을 소비하지 않는다.
- 촬영 위치는 준비 화면과 한 번의 음성으로 안내한다. 세트 시작은 짧은 신호음, 휴식은 한 문장이다. 시작 선언/측정 준비·시작 반복 발화, REC/프레임/규칙 개수를 기본 노출에서 제거했다. 세부 측정·범위·재설정은 접어서 제공한다.

검증: JVM 213건 통과(57종목 계약, 정렬 밖 초기값·부위별 가림·좌우 관측·이력 보존·시간/세트/건너뛰기 포함). Note10+의 분리된 `.replay` 패키지에서 실제 루트 UI 전환과 저장·플랭크 자산 3검사를 수행한다. 실행 결과/설치 버전은 인수인계 최신 절을 정본으로 본다. 전체 55종목의 사람별 정자세 정확도를 새로 검증한 결과는 아니다.

## §42. 목표 기반 진행·운동별 편집·엄지 중심 UI (2026-09-11)

사용자가 §41의 반복 운동 시간 종료를 철회했다. [THUMB_FIRST_UX_IMPLEMENTATION.md](../../docs/THUMB_FIRST_UX_IMPLEMENTATION.md)에 현재 동작과 검증을 기록한다. 자세 규칙의 임계값·ship/beta/exclude 등급·개인 기준 계산은 이번 UX 변경에서 유지했다.

- `WorkoutTarget`은 반복 수/시간을 구분해 저장한다. 구형 목표 문자열도 읽는다. 준비는 사용자가 시작하고 반복은 카운트 목표에 도달해야 진행한다. 반복 속도 환산은 예상 시간일 뿐 종료 조건이 아니다. 시간 운동과 세트 사이 휴식만 시계 만료로 넘어간다. 일시정지·앱 비활성·메뉴/횟수 편집 동안 측정/시계를 멈춘다.
- `SessionProgress`가 현재 횟수, 토큰별 실제 기록, 완료·건너뜀을 구분한다. 이전 세트 이벤트를 거부하고 리포트 확정→기록→전환 순서를 유지한다. 목표 미달 반복과 중간 종료의 현재 횟수는 부분 수행으로 보존하며 전체 종목 완료로 만들지 않는다.
- 라이브의 `RepCounter(completeOnReturn=true)`는 새 `ReturnRepTracker`를 쓴다. 초기 안정 위치→최소 이동→복귀 안정으로 마지막 반복을 다음 하강 없이 확정한다. 1.5초 초과 가림/일시정지는 진행 사이클을 끊는다. 연구 재생/기존 파리티는 기본값인 옛 카운터를 유지한다. **새 카운터의 사람별 정확도는 미검증, 계속 beta**다. 사용자 요청에 따라 검출 이벤트를 진행 조건으로 연결했으며 하단 횟수 수정과 메뉴의 참고 안내를 제공한다. 자세 ROM 참고 위반도 실제 수행 횟수에서 지우지 않는다.
- `WorkoutEditorSheet`에서 운동별 종목·목표 단위/수·세트·휴식·자세 비교를 편집하고 저장한다. 변경/추가/삭제/순서 조작은 하단 시트에 있다. 미지원 프로필은 스위치를 비활성화하고 이유를 표시한다. 카메라 지원과 자동 반복 신호 지원은 다르며 후자가 없으면 직접 횟수 기록으로 진행한다.
- `WorkoutGoalDisplay`/`WorkoutSessionActions`가 시간 원형 진행률, 횟수 계기판, 하단 일시정지·수정·직접 기록·건너뛰기를 공유한다. 라이브 상단 카메라 조작과 중복 설명은 메뉴로 옮기되 관측 불가/방향 오류는 기본 표시한다.
- 운동 탭 중첩 카드를 평면 목록으로 바꾸고 첫 진입 260ms 대기와 폭/높이 애니메이션을 제거했다. 짧은 페이드/이동과 일정한 하단 조작 높이를 쓴다. 식단/내 정보/기록 조작도 아래로 옮겼다. 가이드는 큰 이미지+하단 자막 그라데이션, 로그인은 투명 여백을 제외한 비율 유지 로고다. 원본 이미지 파일은 유지한다. 밝은 테마의 시스템 표시줄 대비와 버튼 그림자도 정리했다.

검증: JVM 223건 통과. 새 검사에는 마지막 반복·단순 정지·불완전 복귀·가림/일시정지·시간/반복 목표 분리·부분 기록·늦은 이벤트가 포함된다. 실제 루트 UI 검사는 Note10+ 분리 앱에서 실행하며 최종 결과/설치 버전은 위 구현 문서가 정본이다. 카운트 정확도·사용자 자세 교정 효과·최초 탭 지연의 전후 성능 수치를 이번 UI 검사로 주장하지 않는다. 설계의 좌우 개별 목표/전환은 후속이며 기존 목표를 임의로 두 배로 만들지 않는다.


## §43. 목록에서 직접 편집·설정 복구·날짜별 기록 펼침 (2026-09-11)

사용자가 §42의 하단 전용 편집을 조정했다. 항목별 조작은 대상 옆에 두고 설정은 익숙한 목록으로 복구한다. 상세 결정과 최종 검증은 [LIST_UX_REFINEMENT.md](../../docs/LIST_UX_REFINEMENT.md)를 따른다.

- 메인 내비게이션의 더 보기 메뉴를 뒤로가기로 교체한다. 운동·식단의 기능 버튼에서 기본 탭으로 돌아가며, 설정은 항상 기본 탭을 쓴다. 운동 기록은 운동 하단과 설정에서 접근한다.
- 운동 목록은 하나의 외곽과 내부 구분선으로 묶는다. 항목 이름 옆의 비교 스위치와 오른쪽 중앙 수정 버튼을 제공한다. 사용자가 확정한 흐름은 **설정 먼저 → 종목 변경에서 추천 보기**다. 수정은 ID 하나의 초안만 만들며 다른 운동을 덮어쓰지 않는다.
- 길게 눌러 순서를 변경하고 놓을 때 저장한다. 행의 들어 올림·자리 이동·가장자리 자동 스크롤을 제공한다. 왼쪽 스와이프는 오른쪽 삭제 아이콘과 확인 팝업으로 연결한다. 확인 전에는 삭제하지 않으며 취소하면 원위치로 돌아간다. 접근성 사용자에게도 이동/삭제 동작을 제공한다.
- 대체 운동은 카탈로그의 같은 주 사용 부위 최대 3개를 먼저 제시하고, 접힌 카테고리와 검색으로 전체 종목을 탐색한다. 부위 일치는 개인 적합성/난이도의 인증이 아니다. 추가는 끝의 회복 묶음 앞, 회복 운동 자체는 마지막에 넣어 기존 순서를 보존한다.
- 설정은 프로필·기록·테마·알림·권한·도움말·버전·로그아웃 목록으로 만든다. 식단 오른쪽 위 수정에서 영양 목표 초안을 열고 저장 또는 취소한다.
- 기록은 최근 7일의 모든 날짜가 닫힌 상태로 시작하며 한 날짜씩 펼친다. 요일/날짜는 가변 폭 행에 배치하고 화살표 회전·높이/페이드 전환을 사용한다. 요약은 운동한 날/시간만, 세부 관측과 자가 라벨은 펼친 내용에 남긴다. UNJUDGED는 저장된 유보/관측 문구를 보존하고 가림으로 단정하지 않는다.

자세 규칙·카운터·목표 진행 조건은 변경하지 않았다. UI 검증으로 자세 정확도나 애니메이션 프레임 성능을 새로 주장하지 않는다.


## §44. 기록 그래프 복구·상단 종목 탭·라이브 직접 조작 (2026-09-11)

사용자의 §43 피드백에 따라 [DIRECT_UX_REFINEMENT.md](../../docs/DIRECT_UX_REFINEMENT.md)를 구현한다. 날짜 기록은 초기 접힘을 유지하면서 완료 세트 막대 그래프를 복구한다. 막대를 누르면 해당 날짜를 펼쳐 스크롤한다. 운동 행 토글/수정은 같은 중앙선에 놓고 토글에 `자세 교정` 이름을 표시한다.

종목 변경은 설정부터 시작하는 계약을 유지한다. 후보 화면은 상단 추천/카테고리 탭으로 전환하고 운동명만 표시한다. 추천의 부위 이름은 제거하며 내부 추천 정책과 새 운동 삽입 위치는 §43 그대로다.

자세 라이브의 메뉴를 없애고 종료·음성·카메라·건너뛰기를 하단에 직접 노출한다. 음성 상태를 표시하며 원래의 일시정지/횟수 조작은 유지한다. 메뉴의 모드/상세 진입은 제거하지만 저장된 모드와 엔진 정책을 임의로 바꾸지 않는다. 로그인은 이전 로그인/회원가입 탭 구성으로 복구하고 가이드/개발 도구 진입 링크를 제거한다. 홈 운동/식단 카드의 이동 콜백을 연결한다.

최종 검사·설치 정보는 위 구현 문서를 정본으로 본다. 자세 신뢰 등급·임계값·카운터 변경이나 정확도 재평가는 이 UI 변경에 포함하지 않는다.

## §45. 자세 라이브의 세트 건너뛰기 확인 (2026-09-11)

§44에서 하단에 직접 노출한 건너뛰기는 한 번의 터치로 세트를 전환했다. 사용자 요청에 따라 `WorkoutSessionActions`의 자세 라이브 경로에 확인 팝업을 추가한다. 제목은 “이 세트를 건너뛸까요?”, 본문은 “현재 세트를 건너뛰고 다음 순서로 이동해요. 완료한 세트의 기록은 유지돼요.”, 버튼은 왼쪽 취소·오른쪽 건너뛰기다. 다음 순서는 휴식·다음 세트·운동 준비·완료 중 하나이므로 항상 ‘다음 운동’이라고 안내하지 않는다.

확인창을 열면 기존 일시정지 경로로 측정과 시계를 멈춘다. 취소·창 밖 터치·시스템 뒤로가기는 열기 전 진행/일시정지 상태를 복원한다. 확정할 때만 기존 토큰 검사를 거쳐 `onSkip`을 호출하며, 현재 세트를 목표 완료로 승격하지 않는다. 일반 타이머의 메뉴와 기존 기록 정책은 유지한다.

검증: JVM 226건 통과. Note10+ 분리 앱의 운동 흐름·시간 목표 UI 테스트 2건 통과. 확인창 취소/시스템 뒤로가기, 이미 멈춘 상태 보존, 진행 중 확인 대기의 시계 정지와 취소 후 재개, 확정 후 완료 기록 보존을 검사했다. 팝업 실기기 캡처는 `outputs/skip_confirm/screenshots/skip-confirm.png`, 최종 설치/데이터 보존 결과는 `outputs/skip_confirm/verification.json`을 따른다.

같은 요청의 촬영 준비 개선은 **구현하지 않았다.** [고정된 휴대폰 앞에서 준비하는 UX 제안](../../docs/CAPTURE_PREPARATION_UX_PROPOSAL.md)에 사용자 회전 시범·준비 미리보기·명시적 준비 시작 이후 카운트다운을 설계로만 남긴다. 촬영 준비 여부를 올바른 자세 판정으로 취급하지 않으며 현재 방향 추정의 한계를 전제로 한다. 촬영 프로필·준비 화면·자세 엔진·개인 기준선 계산은 이번 변경에서 수정하지 않았다.

## §46. 고정 휴대폰 촬영 준비·미리보기·자리에서 시작 (2026-09-11)

사용자가 §45의 제안 구현을 요청했다. [촬영 준비 구현 문서](../../docs/CAPTURE_PREPARATION_IMPLEMENTATION.md)가 동작·한계·최종 검증의 정본이다. 카메라 지원 운동의 준비 단계부터 미리보기를 표시하며, 휴대폰 표식은 고정하고 인물이 움직이는 프로필별 시범과 사용자 관점 안내를 제공한다. 상단에는 운동명·목표·세트, 하단에는 고정된 준비 시작/취소·나가기와 시간 선택·음성·카메라 조작을 둔다.

`CapturePreparationController`는 명시적인 준비 시작 이후 촬영 범위의 안정성을 확인해 서서 3초/바닥 5초 카운트다운을 시작한다. `CaptureFraming`은 필요한 관절의 가시성·유한 좌표·화면 여백·몸 크기를 확인한다. 측면은 같은 쪽 관절 사슬을 요구한다. 바닥의 편한 준비 자세를 허용하며 플랭크를 버티고 있어야 준비 완료가 되는 조건은 넣지 않았다. 방향의 옳고 그름을 강제 통과 조건으로 만들지 않고 자세 판정과도 분리한다.

가림·오래된 프레임은 자동 카운트다운을 취소하고 관측 대기로 돌아간다. 앱 비활성화·카메라 전환·취소는 시작 의사 자체를 해제한다. 인식이 어려우면 10초/15초를 직접 선택해 시작할 수 있다. 숫자·원형 진행률·음성/TTS 불가 시 톤을 함께 제공하고 음소거를 따른다. 시간 운동은 카운트다운 종료에 시작하며 준비 시간은 운동 목표/수행 시간에 포함하지 않는다.

`TrexApp`의 카메라 PREPARE/WORK 경로와 `AnimatedContent.contentKey`를 같은 세트로 묶어 준비→운동에서 카메라를 유지한다. `PostureLive`는 준비 상태에서 프레임을 엔진 갱신 전에 반환하며, 추론 시작 시점에도 준비였는지 확인해 경계 프레임이 운동으로 섞이지 않게 한다. 세트 마감 콜백은 WORK에서만 등록한다. 운동 루틴의 순서, 기존 개인 기준·ship/beta/exclude·자세 임계값·반복/시간 목표 종료 규칙은 유지한다.

JVM 236건을 통과했다. UI 검사는 실기기의 준비 취소·비활성화·카메라 전환·가로 화면 버튼·준비 시간 제외·시간 목표 완료 및 기존 세트/휴식/건너뛰기 흐름을 포함한다. 최종 설치와 검사 결과는 `outputs/capture_preparation/verification.json`을 따른다. 초기 가시성/안정성 기준은 체형·촬영 환경별 자동 준비 성공률을 보장하는 보정값이 아니며 후속 실사용 검증이 필요하다.


## §47 — 자동 5초 준비·드래그·최근 기록 (2026-09-11)

정본 구현 문서: [준비·운동 목록·최근 기록 수정](../../docs/PREPARATION_RECORDS_REFINEMENT.md). §46의 명시적 준비 시작·서서 3초/바닥 5초 정책은 대체됐다.

- 준비 화면 진입 즉시 촬영 범위를 관찰하고 0.4초 안정 후 5초를 센다. 잠깐의 가림/회전에는 남은 초를 멈추고 0.3초 안정 후 재개하며 1.2초 지속 이탈은 안내로 돌아간다. 오래된 프레임은 0.75초부터 보류한다. 바닥/측면/불명 방향의 검증 한계는 그대로다.
- 카메라 OFF도 별도 원형 5초 준비·시간 추가·일시정지·직접 시작을 제공한다. 준비는 목표/기록 시간에 포함하지 않는다. 공통 하단 건너뛰기 확인창을 적용하고 반복/시간 목표 분기를 유지한다.
- 완료는 체크/배경/상단 개수로 구분한다. 드래그는 오버레이와 자리 표시를 분리하고 논리 배치 좌표·순서와 배치 일치 확인·Initial 이벤트 소비·안정 ID로 잘못된 교환/편집을 막는다. 실패 재현과 회귀는 구현 문서에 기록했다.
- 운동 기록은 최근 운동 7건 대신 달력 7일을 보존한다. 앱 시작/복귀/날짜 경계 및 운동 저장에 적용하며 기존 JSON의 만료 날짜만 제거한다. 최근 데이터 부가 필드와 미래 날짜는 보존한다.
- 식단 기록은 최근 7일 날짜 탐색·실제 영양 합계·끼니별 수량·빈 날짜를 보여 준다. 원본 자세 로그와 식단에 새 자동 삭제 정책을 추가하지 않는다.

최종 검증/설치 정본은 `outputs/preparation_records/verification.json`. 준비 성공률/실사용 반복 초기화 빈도는 미측정이다. 자세 규칙의 ship/beta/exclude와 임계값은 이 절에서 변경하지 않는다.


## §48 — 권장 촬영 방향을 먼저 안내 (2026-09-11)

`ExerciseProfile.preparationInstruction`이 권장 방향, 사용자 몸 회전 안내, 인식 후 5초 시작 순서로 문장을 만든다. 정면/오른쪽 앞/왼쪽 앞/측면/낮은 측면/앞쪽 사선을 구분하며 좌우는 사용자 본인 기준이다. 준비 화면 첫 문장과 최초 음성에 같은 내용을 사용한다.

준비 숫자가 설명을 끊지 않도록 `SpeechCoach.isSpeaking`으로 대기/발화 종료를 확인한 뒤 자동 관찰을 시작한다. 음소거/음성 사용 불가에서는 설명 대기를 생략하고, TTS 콜백 누락은 최대 12초에서 해제한다. 이미 끝까지 안내한 설명은 일시정지/재개마다 반복하지 않는다. 사용자가 직접 5초 시작을 선택하면 해당 카운트를 존중한다. 준비 시간은 운동 목표/기록에 포함하지 않는 §47 정책을 유지한다.

JVM 244건 통과: 모든 카메라 종목에서 방향 문구가 5초 안내보다 앞에 있는지와 사용자 기준 좌우를 검사했다. 원격 TSV의 빈 마지막 열이 생략된 경우도 기존 회귀가 읽도록 보완했다. APK 체험판과 설치 안내는 README 및 GitHub Release `v1.1.0-preview.1`을 따른다. 자세 평가 규칙/기준 자산은 이 변경에서 수정하지 않았다.


## §49 — 준비 건너뛰기와 기록 모드 복원 (2026-09-12)

- 자동 준비의 관절/방향 감지 또는 카운트 보류에서 막힐 수 있어 하단 오른쪽에 `준비 건너뛰기`를 항상 제공한다. 클릭은 준비를 종료하고 같은 운동을 시작하며 완료/다음 세트 콜백을 호출하지 않는다. 한 번만 전달하고 준비 음성을 중단한다. 자동/수동 카운트 중에도 동작하며 앱 비활성화 중에는 비활성이다.
- 자리로 돌아갈 시간은 기존 `5초 후 시작`으로 확보한다. 일시정지/재개는 하단 도구 행, 나가기는 왼쪽 아래다. 설명만 스크롤하고 조작은 고정하여 가로 화면에서도 탈출 경로를 유지한다.
- §44에서 제거한 모드 진입을 `기록 모드` 스위치로 복원한다. 준비/운동 하단에서 조작하며 기존 `ModeStore`의 종목별 키를 그대로 읽고 쓴다. 켜면 TRACK, 끄면 COACH다. 모드 전환 시 이전 코칭 음성/강조를 지우되 초반 개인 기준과 누적 횟수는 유지한다.
- TRACK은 기존 동일 동작 단계의 초반 대비 지속 변화/복귀와 카운트를 사용한다. 피로 원인을 진단하거나 모집단 자세와의 차이를 사용자 오류로 말하지 않는다. 횟수는 여전히 beta이며 시간 운동의 목표를 횟수로 바꾸지 않는다. 규칙 임계값·신뢰도 정책은 변경하지 않는다.
- 규칙 자산 로딩 이후 모드를 다시 읽던 코드를 없애, 준비에서 고른 모드가 비동기 로딩 완료로 덮이지 않도록 했다.

검증: JVM 244건, Note10+ UI 회귀 3건 통과. 세로/가로 준비 조작·기록 모드 화면 확인. 배포 APK의 DEX/자산 16항목이 분리 검사 APK와 일치한다. 상세 배포/제약은 `docs/ANDROID_PREVIEW_RELEASE.md`를 따른다.


## §50 — 루틴 중심 요약과 선택적 카드 정리 (2026-09-12)

사용자 요청은 참조 화면 전체 복제가 아닌 홈 정보 축소, 루틴 중심·이미지, 운동별/끼니별 카드 구분이다. 현재 배포 브랜치를 기준으로 하며 폴더블 브랜치의 별도 변경을 합치지 않는다.

- `RoutineOverview`는 세트당 운동 시간 × 세트 수를 카테고리별로 합산한다. 휴식은 중심 계산에서 제외하고 본운동이 있으면 회복 카테고리도 제외한다. 최대 비중 45% 이상·2위와 10%p 이상 차이면 특정 중심, 그 외 전신이다. 회복만 있으면 회복 중심이다. 이 수치는 제품 분류 정책이며 생리학적 검증 기준이 아니다.
- 예상 분은 모든 운동·세트 간 휴식의 초를 합친 뒤 한 번만 올림한다. 완료/순서로 중심이 변하지 않는다. 빈 목록은 추가 안내다. 시간/횟수 종료 조건은 변경하지 않는다.
- 공통 `RoutineHero`는 6종 생성 이미지를 IO 디코딩·3개 캐시로 읽으며 이미지가 텍스트나 버튼 높이를 바꾸지 않는다. 인물은 잘리지 않게 맞춤 표시한다. 장식용이므로 자세 정답/교정 기준에 사용하지 않는다.
- 홈은 기존 운동 기록의 최근 날짜, 칼로리 목표, 오늘 운동의 중심·종목 수·예상 분, 바로 식단 입력을 제공한다. 운동 목록은 단일 헤더 슬롯을 유지하고 행 사이 간격/라운드 카드만 바꿔 드래그 인덱스와 편집/교정 토글/삭제를 보존한다. 식단은 기존 끼니 내용의 외곽만 카드로 바꾼다.
- 자세 평가·기록 모드·준비 건너뛰기·세트 진행 로직은 건드리지 않는다. 상세 디자인/분류/이미지 프롬프트는 `docs/ROUTINE_OVERVIEW_UI.md`, `docs/ROUTINE_IMAGE_PROMPTS.json`에 둔다.


## §51. 홈 복구·실제 기록·폴더블 배치 (2026-09-12)

- 사용자가 §50에서 정보가 지나치게 줄었다고 요청했다. 홈 카드와 탄단지·비율·남은 kcal·실제 활동 요약을 복구했다. 운동 행 탭 편집/끼니 탭 편집/네 끼니 이미지/로그인 로고 중심을 적용했다. 이전 준비 건너뛰기와 기록 모드는 유지한다.
- 날짜별 중심 운동은 저장 당시 카테고리와 실제 수행 시간으로 계산한다. 요약은 관측한 수행만 평가하며 베타·유보·TRACK을 정상으로 승격하지 않는다.
- 샘플 운동/식단 초기값, 가짜 평소 평균, 고정 음식·정확도를 반환하던 사진 분석 UI를 제거했다. 지문 일치 날짜만 원본 백업과 함께 정리한다. 실제 사용자 원본의 알 수 없는 추가 필드는 보존한다. 사진 분석 미연결 상태에서는 직접 입력으로 이어진다.
- 준비/휴식을 제외한 세트 실제 가동 초를 보존하고 소모 kcal를 그 시간에 따른 추정값으로 표시한다. 없는 실제 시간이나 자세 평가는 만들어 넣지 않는다.
- WindowManager 1.5.0 실제 창/힌지 경계 기반 배치. 카메라와 패널의 Composition 위치는 고정하고 측정/위치만 바꾼다. 프리뷰/분석 회전, 입력 폭, 시스템/IME/메뉴 여백과 큰 글씨를 처리한다. 물리 폴더블 검증은 미실시다.
- 사용자 명시 요청에 따라 설명은 화면/TTS 출력 경계에서 ~룡 어미를 사용한다. 저장 판정 원문, 부정·유보·검증 중의 의미는 유지한다.
- 상세 구현과 검증 범위: `docs/UI_RESTORATION.md`. JVM 260건 통과. Android 결과와 설치 증빙은 `outputs/ui-restoration`에 보관한다.


## §52 문구·끼니 상징·루틴 요약·모드 선택 (2026-09-12)

- 기록 모드의 ON/OFF는 반대 상태가 무엇인지 불명확했다. 준비/운동의 같은 하단 조작 영역에 `자세 교정 / 기록 모드` 두 선택을 놓는다. 200ms 선택 배경 이동, 48dp 이상 터치 영역, Tab 선택 의미와 설명을 제공한다. 큰 글씨에서는 높이가 늘고 시스템 애니메이션 배율을 따른다
- 세로 준비 화면은 방향 시범을 위한 조작 영역을 최대 460dp/창 높이의 58%로 확보하고 운동 시작 후 영상 영역을 넓힌다. 분리 힌지와 가로 배치에서는 실제 경계를 유지한다
- 준비에서만 `운동 자세 교정을 안내해룡` 또는 `처음 자세와의 변화를 비교해룡`을 보여 준다. 운동 중에는 설명을 반복하지 않는다. 선택한 항목을 다시 눌러도 콜백/발화를 재실행하지 않는다. 종목별 저장, 준비→운동 유지, 모드 전환 시 기준·횟수 보존은 §49 그대로다
- 화면/TTS 출력 경계에서 문장 끝 마침표를 제거한다. 소수점, 버전, URL, 파일 확장자는 유지한다. 판정 JSON·로그·입력 원문은 변경하지 않는다
- 끼니는 음식 예시 사진 대신 시간대 상징(해돋이·해·티타임·달)을 사용한다. 같은 테마 벡터로 홈/식단에 적용하며 생성한 끼니 PNG는 포함하지 않는다
- 오늘 운동 아래 중복 요약을 없애고 루틴 카드에 `N개 운동 · N세트 · 약 N분`을 통합한다. 세트는 실제 각 운동 설정 합계이며 분류/소요 시간 계산은 §50 그대로다. 각 운동 완료 체크는 유지한다
- §50~§52를 배포 기반 feature/posture-coach-reliability에 커밋하고 1.1.0-preview.2(versionCode 3)로 공개한다. 검사 결과와 배포 파일 정보는 docs/ANDROID_PREVIEW_RELEASE.md가 정본이다


## §53 카메라 몰입 제어판 (2026-09-12)

- 사용자 요청: 제어판 때문에 영상이 작아 보이지 않도록 운동 중에는 카메라 위에서 제어판을 아래로 접는다. 패널만 280ms 동안 이동하고 카메라 측정 크기·View 연결·FIT_CENTER는 유지한다. 분리 힌지는 기존 카메라 영역을 유지한다
- 종목·카메라·회전별 처음 관측한 어깨–골반의 화면 길이를 UI 기준으로 잡는다. 관절 피처와 가시 관절이 필요하며 detected만으로 사람을 인정하지 않는다. 실제 거리·자세 정답 판정에 쓰지 않는다
- 3초 안정된 관측과 유지 시간 경과 후 접는다. 최초 길이의 1.65배 또는 화면 높이의 60% 이상인 몸통이 700ms 이어지면 연다. 관측/새 프레임이 1.5초 없으면 연다. 한두 프레임 이탈에는 반응하지 않는다
- 준비·일시정지·카메라 오류는 열림 우선이며 직접 열기/모드 변경 후 8초 동안 유지한다. 오른쪽 아래 수동 열기는 관절 추론과 무관하다. 사용자가 직접 접어도 화면에서 사라지면 다시 연다
- 운동명·세트·횟수/남은 시간은 영상 상단에 남긴다. 시간은 작은 원형 진행 표시를 사용한다. 제어판을 접으면 자세 안내도 상단에 남긴다. 안내 위치는 제목 높이를 따라 큰 글씨에서 겹치지 않는다
- 접근 임계값은 UX 초기값이다. 다양한 체형·바닥 운동·공간에서 자동 제어판 성공률은 아직 측정하지 않았다. 카운트·시간·평가·로그 정책은 제어판 상태와 독립이다


## §54 작은 카메라 영역·중앙 목표·조작/음성 정리 (2026-09-12)

- 사용자가 말한 PiP는 앱 안에서 제어판 위의 별도 영역에 영상 전체를 작게 표시하는 구조다. 제어판을 열면 미리보기와 제어판이 공간을 나눠 쓰며 가리지 않는다. 접는 동안 제어판의 이동 경계를 따라 미리보기를 확장한다. §53의 고정 크기 영상 위 제어판 배치는 이 정책으로 대체한다
- 세로에서는 아래로, 넓은 가로 창에서는 오른쪽으로 제어판을 이동한다. 같은 280ms 진행값으로 카메라 영역을 계산하므로 중간 프레임에도 서로 겹치지 않는다. 분리 힌지는 기존 전용 카메라 영역을 유지한다. 실제 폴더블은 미검증이다
- CameraX 연결과 PreviewView 인스턴스는 유지하며 FIT_CENTER로 원본 전체를 담는다. COMPATIBLE(TextureView)로 영역 크기·둥근 모서리 변화와 화면 합성을 처리한다. 다른 앱 위에 띄우는 Android 시스템 PiP는 사용하지 않는다
- 횟수/남은 시간을 화면 상단 중앙에 52sp(낮은 가로 창 36sp)로 표시하고 목표·원형 진행률은 그 아래에 모은다. 제어판이 열리면 숫자 영역 아래부터 영상을 배치하고 접히면 전체 영상 위에서도 숫자 위치를 유지한다
- 운동 조작은 왼쪽 보조 `화면 넓게`, 오른쪽 기본 `일시정지 / 운동 계속`으로 나눈다. 접힌 상태에도 하단의 `제어 / 일시정지`를 남긴다. 준비의 멈춤/계속은 이름과 아이콘이 함께 있는 버튼으로 바꾼다. 기록 모드·횟수 수정·세트 건너뛰기 정책은 유지한다
- SpeechCoach는 TTS에 원본 존댓말 문장을 그대로 전달한다. 준비·평가 안내의 공룡 어미 변환을 제거했고 화면 설명의 ~룡 말투는 TrexText에서만 유지한다. 판정 신뢰도와 beta 음성 제한은 바꾸지 않는다
- 식단 끼니 수정 팝업은 `이 끼니 합계` 이름만 제거하고 실제 kcal·탄단지 합계/편집은 유지한다
- 검증 정본은 `research/aihub_fitness/outputs/pip-camera/verification.json`이다. APK는 현재 작업용이며 기존 공개 preview.2를 교체하지 않는다

## §55 — 큰 미리보기와 준비 완료 즉시 몰입 (2026-09-12)

- §54의 큰 상단 HUD와 패널이 영상 높이를 동시에 줄였다. HUD를 32sp 횟수/시간과 목표·원형 진행률의 하단 표시줄로 바꾸고, 영상 좌우·상단 여백을 없앴다. 일반 세로 창의 패널은 최대 300dp/38%로 줄이고 내용은 스크롤한다. 펼쳤을 때 HUD는 영상 밖, 접었을 때 하단 제어 버튼 위에 위치하므로 얼굴 앞 상단은 비운다
- 준비의 onStart는 건너뛰기 여부를 전달한다. 자동/직접 5초 카운트 완료는 처음부터 제어판을 화면 밖에 배치하고, 준비 건너뛰기는 제어판을 연 채 시작한다. 이후의 열기/접기는 기존 애니메이션을 유지한다
- 건너뛰기 직후에는 CaptureFraming의 머리와 같은 쪽 손발 등 종목별 필수 관절·프레임 여백 조건을 사용한다. 연속 1초 충족 시 접고, 불충족/오래된 관측은 시간을 초기화한다. 어깨·골반 스케일만으로 전신을 인증하지 않는다. 이 확인은 촬영 범위이며 올바른 자세 판정이 아니다
- 직접 열기와 일시정지는 8초 조작 유예를 유지한다. 운동 중 접근/이탈로 제어판을 다시 여는 정책도 유지한다. 평가·카운터·세트 기록 조건은 변경하지 않는다
- 사용자 요청에 따라 실기기는 준비 완료/건너뛰기 흐름 한 건과 확장 중 카메라 인스턴스·HUD 위치 한 건만 검사한다. 1초 경계·관측 끊김·직접 열기 유예는 JVM에서 검사한다. 증빙은 outputs/pip-entry/verification.json이며 이전 공개 APK와는 별도 개발 빌드다

## §56 — 운동 카탈로그를 AIHub 26종목으로 축소 (2026-09-23)

- 카탈로그 57종목 중 AIHub 규칙 근거가 있는 것은 27개(앱 이름 기준)였고, 나머지 30개는 카메라가 켜져도 참조 규칙이 없는 비교 전용(28)·안내 전용(2)이었다. 비교 전용 안내가 자세 평가처럼 읽히는 것을 막으려고, 카탈로그를 AIHub 데이터셋에서 앱에 매핑된 26종목(서서 18 + 바닥 8)으로 줄인다
- 기본 스쿼트는 AIHub 에 맨몸 스쿼트가 없어 바벨 스쿼트 기준을 빌려 쓰던 종목이라 함께 뺐다. 이제 `workoutCatalog`·`ExerciseProfiles.all`·`postureExerciseMap` 이 같은 26개 이름을 갖고, AIHub 종목과 1:1 이다(`PostureExerciseMapTest`·`PersonalizationTest` 가 이 등식을 고정한다)
- 유산소·회복 카테고리는 해당 종목이 없어 사라졌다. 첫 실행 기본 루틴(`todayPlan`)은 바벨 스쿼트·플랭크·런지·니 푸쉬업으로 바꿨고 id 는 저장·기록 호환을 위해 유지했다. 대체 운동 폴백도 카탈로그 안에서만 고른다
- 예전에 저장된 루틴의 카탈로그 밖 종목(예: 마무리 스트레칭)은 지우지 않는다. 프로필이 없어 `postureSupported()` 가 false 가 되므로 카메라 없이 타이머로만 진행한다. 이름별 페이스·휴식·부위 분류(`WorkoutPacing`, `workoutRegion`)와 레거시 기록 정리(`LegacyDemoData`)는 이 호환을 위해 그대로 둔다
- 크런치·Y - Exercise 는 바닥 규칙이 exclude 1개뿐이라 매핑돼 있어도 위반을 판정하는 규칙이 없다. 26종목 유지 요청에 따라 남겼다. 화면에서 이 공백을 따로 알리는 작업은 하지 않았다
- 규칙 JSON·임계값·규칙 등급은 바꾸지 않았다
