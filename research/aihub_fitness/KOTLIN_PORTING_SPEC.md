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

> 검증 이력: 적대 검증(3에이전트)이 v2 의 결함 3건(MP 불가 피처 채택 shoulder_neck_gap · 바닥 off-by-one 프레임 유실 · 컬형 파형 붕괴)을 발견 — v3 로 수정 후 수치 갱신. 합의 자기포함 순환성은 LOO 재계산으로 무시 가능 확인(Δ<0.003), 단 근중복 피처 패밀리의 블록 투표는 남은 한계.

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
