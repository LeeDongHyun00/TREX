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
- **후속 (2026-09-24, PC 첫 빌드가 잡음)**: 분류(`category`)를 저장하지 않던 때의 운동 기록은 `recordWorkoutFocus` 가 종목명을 카탈로그 → `todayPlan` 에서 찾아 초점을 되찾는데, 뺀 종목이 전부 "전신" 으로 떨어져 **업데이트만으로 지난 기록의 표시가 바뀌었다**(`AdaptiveAndDataTest` 의 기본 스쿼트 → 하체 기대가 실패 — `research/external_rep_replay/results/pc_build_check.md`). 뺀 31종목과 옛 `todayPlan` 의 "푸쉬업 입문" 의 원래 분류를 `retiredWorkoutCategories`(`RecordOverview.kt`)에 두고 세 번째 순서로 찾는다. 지난 기록 표시에만 쓰고, 현재 카탈로그·`todayPlan` 과 이름이 겹치지 않는다는 것을 테스트가 고정한다(겹치면 분류의 정본이 둘이 된다). 대체 운동 이름(예: 의자 스쿼트)은 §56 전에도 찾지 못해 전신이었으므로 그대로 둔다

## §57 — 휴대폰 횟수 엔진 설계 v2 (2026-09-24 — 새 코어는 구현했으나 꺼 둠, 런지 신호만 적용)

`docs/REP_ENGINE_DESIGN.md`(v2)가 정본이다. 외부 코퍼스 재생(`research/external_rep_replay/README.md` §4·§7)으로 현재 라이브 카운터
(`ReturnRepTracker`)의 놓침 원인이 촬영이 아니라 네 가지 설계 선택(런지 신호 `knee_out_mean`, 안정 준비 자세 요구, 상단 체류 요구,
3점 중앙값 평활)임을 확인한 뒤, 엔진을 새로 만들지 않고 그 네 곳을 바꾸는 설계다. MM-Fit 3D 포즈는 **개발 데이터**다(봉인 피험자 없음) — 성능 주장은 휴대폰 Gate B 에서만.

- 적용: 런지(스텝 포워드 다이나믹 런지) 신호 `knee_out_mean` → `knee_mean` 35°(기존 코어 위, MM-Fit 재현율 0.10 → 0.84). 같은 변경에서 옛 ROM(−0.0076)을 지우고,
  ROM 이 없는 반복은 '유효' 가 아니라 '범위 미판정'. TRACK 비교 지표는 `comparisonFeature` 로 `knee_out_mean` 0.10 을 유지한다. 미검증 ROM 은 '참고', TRACK 음성은 '파셜' 을 말하지 않는다.
  **이 카운터는 §1 수용 기준을 하나도 넘지 못한다**(MM-Fit 3D: 세트 정확 일치 0.48 · ±1 0.68, 세트 앞 15 s·뒤 10 s 를 이어 재생하면 앞·뒤 헛카운트 0.11·0.34/세트, 앞뒤 포함 과다 7/62세트) — beta.
  교체의 값은 거의 못 세던 신호(앞뒤 창 재생에서 62세트 중 43세트 0회)를 대부분 세게 된 것이다. 대가: 런지에서 목표 도달 자동 진행(§42)이 실제로 걸리기 시작하고,
  MM-Fit 앞 15 s 재생에서 4/62세트는 세트 전 움직임이 더해져 마지막 실제 반복 전에 목표에 닿는다(교체 전 0 — 목표에 닿는 일 자체가 없었다). 반복 정의는 §59 에서 사용자 결정으로 런지류 '좌우 한 번씩 = 1회' 가 됐다 — 목표 P회는 2P 걸음에서 닿으므로 이 조기 도달 수는 그대로다.
  런지 자동 진행을 Gate A 까지 끌지는 사용자 결정(설계 §15 #23).
- 새 코어(구현·테스트, 앱에서 꺼짐): 복귀 히스테리시스(휴식 → 하강 → 반전 → 75% 복귀에서 확정, 재하강 폴백, 불응기 0.8 s, 평활 없음).
  `RepSignal.polarity` 가 있는 신호만 쓰는 opt-in 이고(극성을 틀리면 모든 세트가 마지막 반복을 잃는다), 세 종목 모두 Gate A 전까지 `null` = 기존 경로.
- 세트 시작 확정: 첫 쌍 8 s 창 + 이후 반복은 진폭 비 0.5~2 만. 결과 전에 적은 선택 규칙(설계 §12)을 MM-Fit 스트레스 배터리(`stress_battery.py`, 설계 §13)에 적용해 골랐다 —
  옛 4 s/4 s 는 반복당 4.5 s 이상에서 0회, 마지막 반복 전 5 s 멈춤에서 전 종목 0.00 이었다. 세트 끝 보류 후보는 세지 않고 로그(1회 세트 = 0회). 반복당 8 s 초과 템포는 0회가 되는 절벽이 남았다.
- 컬 신호는 앱에서 `elbow_mean` 유지. 새 코어 후보는 **앱 피처 그대로의** `elbow_minside`(한쪽이 안 보이면 보이는 쪽) — 합성 가림은 이 폴백이 과다 카운트를 낸다고 예측했지만
  실제 MM-Fit 영상에서는 재현되지 않았고 "양팔 보일 때만" 이 오히려 나빴다(0.77 vs 0.86, 설계 §16.4). 새 코어로도 0.85 라 항상 10(0.92) 아래 — 세 종목 중 가장 준비가 덜 됐다.
- **MM-Fit 영상 → MediaPipe(앱 모델) 결과**(설계 §16, 개발 데이터): 세트 정확 일치 새 코어 스쿼트 1.00 · 런지 0.97 · 컬 `elbow_minside` 0.85, 지금 앱 0.33 · 0.18 · 0.00
  (항상 10: 0.95 · 0.95 · 0.92). 같은 샘플 시각의 3D 와 비교하면 MediaPipe 는 새 코어의 스쿼트·런지 카운트를 세트당 ±0.04회만 바꾼다.
  배포된 MM-Fit 영상은 w16~w20 에서 라벨보다 최대 5.8 s 앞서 있어 정답 창을 세트별로 옮겨 채점했다(`mmfit_align.py`). 컬 게이트 35° 유지·런지 새 코어 신호 `knee_mean` 을 결정했다(설계 §15 #10·#11).
- **REHAB24-6 사전 등록**(설계 §17): 설계에 쓰지 않은 첫 자료라 판정 기준(P1 정자세 재현율 ≥ 0.95, P2 정밀도 ≥ 0.95, P3 경계 F1 ≥ 0.90, P4 ≥ 지금 앱, 두 카메라 모두)과 채점기
  (`score_rehab_prereg.py`)를 실행 전에 커밋했다. CC BY-NC — 측정 전용, 파생 캡처는 공개 저장소에 올리지 않는다.
- 200 ms 부스트 제거(300 ms 고정). 신뢰 등급은 로그만 — 발열 저신뢰가 자동 진행을 조용히 끄지 않게(등급 로그 필드는 아직 없다 — Gate A 전에).
- 가장 큰 남은 위험은 세트 경계: 2세트부터는 준비 카운트다운이 없고, 세트 앞 10 s 헛카운트가 새 코어 런지 0.19·컬 0.19~0.31/세트(기준 0.05). 지금 앱의 레거시 카운터도 같은 노출이 있다
  (앞 15 s: 런지 0.11·컬 0.24/세트, 컬 재현율 0.855 → 0.467). 확정 규칙으로는 안 풀려 제품 해법은 Gate A 수치 뒤 사용자 결정.
- 상수의 출처: 게이트·f·불응기·maxGap 은 설계값(데이터로 조정하지 않음). 평활 없음과 첫 쌍 8 s 는 MM-Fit(개발 데이터)에서 골랐다 — 8 s 는 결과 전에 적은 규칙으로.
  배터리 결과의 §12 해시는 결과와 규칙 본문을 묶을 뿐 작성 순서의 증거가 아니다. §12 는 결과와 같은 커밋(`da90111`)에 들어갔고 순서는 세션 기록으로만 뒷받침된다 —
  그래서 다음 외부 검증(REHAB24-6, 설계 §17)은 판정 기준을 **실행 전에 커밋**했다.
- 검증: 외부 자료(MM-Fit 영상 → REHAB24-6 → RepCount·AIHub 이미지 폰 재생)는 걸러내기, Gate A = 휴대폰 개발 데이터(튜닝·귀속), Gate B = 고정 빌드·새 사람 ≥ 5명·Clopper-Pearson 단측 95%
  (정확 일치 하한 ≥ 0.90 → 틀린 세트 0개면 종목당 29세트). 통과 전까지 beta.

## §58 — 런지 신호 교체의 앱 표면·렙 ROM 표시 정직성·세트 로그 단계 0 (2026-09-24, 폰 미연결 — PC 빌드 검증 대기)

§57 의 결정 중 "지금 적용" 분(런지 `knee_mean` + ROM 제거)이 앱 화면·음성·로그·TRACK 비교에 닿는 곳을 정리했다. 새 코어는 여전히 꺼져 있다(모든 `RepSignal.polarity = null`).

- **세션 카운터 구성 한 곳**: `PostureLive` 의 세 갈래 생성(규칙 rep 설정 / 바닥 ROM 제거 / 등록부 그대로, 전부 maxGap 1.5 s·복귀 완료)을 `RepCounter.forSession(ex, dir, thr, floor)` 호출로 바꿨다. 동작은 같다 — 왜: 재생기(`replay-jvm` 'live')가 같은 함수를 불러 "앱과 같은 구성" 을 구조로 보장한다. 복사한 구성은 조용히 어긋난다.
- **TRACK 비교는 카운트 신호와 분리**: `ComparisonMetrics.primary/forExercise`, `PhaseSignature.compute`, `PostureComparisonTracker` 기본 신호가 `RepSignal.comparisonSignal()`(멱등)을 쓴다. 런지 비교는 `knee_out_mean` · 게이트 0.10 · '정규화 비율'(잡음 바닥 0.06) 그대로다 — 왜: 비교 지표는 사용자가 이미 쌓은 기록의 단위라, 카운트 개선이 기록의 단위를 바꾸면 안 된다.
  앱 '런지' 프로필은 교대 종목이라 `ObservationKind.WINDOW`(3 s 관측 창)이므로 카운터가 더 자주 발화해도 비교 구간은 그대로다. 달라지는 것은 앵커(§31): 이제 첫 `knee_mean` 반복에서 잡혀 10 s 폴백보다 일찍 잡힐 수 있고, 비교 시작(anchored 조건)·세트 집계 창·`AssessmentWindow.end` 도 그에 따른다 — 신호 교체의 의도된 결과다.
- **렙 ROM 표시 정직성** (`RepRomTier`, `PostureSetReport.kt`) — 원칙 #1·#2. 신호의 ROM 설정으로 세트마다 한 단계를 정하고, 말은 그 단계가 허락하는 만큼만 한다:

  | 단계 | 조건 | COACH 펼침 | TRACK 요약·펼침 | TRACK 음성 |
  |---|---|---|---|---|
  | VALIDATED | 임계값 + `romValidated` | 렙 유효 N−m · 무효 m (기존) | N렙 · 파셜 m (기존) | "N렙 파셜 m" (기존) |
  | REFERENCE | 임계값, 미검증 | 참고 · 검출 N회 · 범위 미달 m회 | N렙 · 참고 · 범위 미달 m회 | "N렙" 만 |
  | NONE | 임계값 없음(구성 모름 null 도 여기) | 참고 · 검출 N회 · 범위 미판정 | N렙 · 범위 미판정 | "N렙" 만 |

  N = 검출 전체, m = ROM 미달로 판정된 수. COACH 요약·음성은 원래 렙을 말하지 않는다. NONE 의 COACH 줄도 '참고' 로 연다 — 카운트 자체가 beta 이고(HUD '자동 횟수 · 참고'),
  예전 바닥 종목 표기('참고 · 검출 …')가 NONE 이 되면서 '참고' 를 잃지 않게.
  **'TRACK 음성' 열은 `PostureSetReport.voiceLine` 의 문구다 — 이 필드는 지금 앱에서 부르는 곳이 없다**(커밋 e0e4c2f 가 세트 끝 `speech.speak(r.voiceLine)` 을 뺐다). 규칙은 다시 연결될 때를 위해 필드에 걸어 둔다.

  TRACK 은 미달 0 을 표기하지 않는다(VALIDATED·REFERENCE — 예전 '파셜 0' 생략과 같다). COACH 펼침은 0 도 적는다('무효 0'·'범위 미달 0회' — 예전 형식). 바닥 종목은 검증 기준이 붙어 와도 REFERENCE 로 낮춘다(규칙이 전부 beta — 세션 구성에서는 생기지 않는 조합). 세션에서 실제 분포: VALIDATED = 바벨 런지·딥스, NONE = 런지와 rep 규칙 설정이 없는 바닥 종목(크런치·라잉 레그 레이즈·시저크로스·Y - Exercise), 나머지 카운터 종목 = REFERENCE.
  왜: 예전 표시는 ROM 기준이 없는 렙을 "유효" 로 합산했다 — 신호를 교체한 런지는 "렙 유효 10 · 무효 0", rep 설정이 없는 바닥 종목은 "범위 미달 0회" 로, 판정하지 않은 것을 판정한 것처럼 보였을 것이다. 미검증 기준(대부분의 서서 종목)의 미달은 코치 "무효"·기록 "파셜" 로 표시됐다(TRACK 세트 끝 음성 문구 `voiceLine` 에도 '파셜' 이 들어 있었지만 e0e4c2f 이후 말하지는 않았다).
  **예외로 남긴 것**: 바닥 종목의 실시간 참고 안내(`FloorFeedbackController`, 코드 주석의 '§36' 정책 — 이 스펙에 §36 절은 없다)는 beta 바닥 rep 규칙의 범위 미달을 COACH 에서 "참고 안내예요. …" 로 말한다.
  이 변경 전부터의 동작이라 건드리지 않았다 — 원칙 #2(beta 는 음성으로 말하지 않는다)·§35("바닥 세트는 자세 교정 음성을 제공하지 않는다")와 부딪치므로 유지 여부는 사용자 결정(설계 §15 #22). 수는 바꾸지 않는다 — `repCount + repInvalid`(검출 전체)가 그대로 진행·자동 넘김(§42)으로 가고(§59 부터 표시 단위 — 런지류는 좌우 쌍의 수), ROM 은 수를 줄이지 않는다. `repsValid` 필드 이름은 저장 호환 때문에 두되 뜻은 "ROM 미달로 판정되지 않은 렙" 이다.
- **세트 로그 단계 0** (`PostureSetLog.kt`, 스키마 호환 추가 — null 이면 키 부재 = 이전 로그): `app_version`(versionName), `thermal{start, changes[{t_ms,status}]}`(첫 프레임 시점 열 상태와 세트 중 변화, API 29 미만이면 부재), `reps.engine`("return_v1" 지금 / "hysteresis_v1" 새 코어), `reps.config{feature,min_amp,refractory_ms,max_gap_ms,complete_on_return,polarity?,return_fraction?,first_pair_window_ms?,later_window_ms?,min_ratio?,max_ratio?,rom_direction?,rom_threshold?,rom_tier}`(값은 `RepEngineLog.of(counter)` 가 카운터에서 읽은 **실제** 구성 — `RepCounter.effective*`, 코어·시작 확정의 값), `reps.resets[{t_ms,after_t_ms,reason}]`(일시정지 "pause"·카메라 전환 "camera_switch" 의 `resetCycle`. `t_ms` = 누른 시각, `after_t_ms` = 리셋 직전에 카운터가 처리한 마지막 프레임, null = 아직 없음. 카운터가 돈 세트는 빈 목록이라도 남긴다), `reps.pending{unconfirmed,in_progress,dropped[]}`(새 코어만 — 레거시는 부재). 시각은 전부 세트 상대(프레임 `t_ms` 기준).
  왜: Gate A(폰 개발 데이터)에서 "그 세트를 어떤 빌드·카운터·구성이 셌는가, 언제 사이클을 버렸는가, 발열로 샘플 간격이 바뀌었는가" 를 로그만으로 갈라야 새 코어 전후와 세트 경계 헛카운트(§57)를 귀속할 수 있다. 화면 회전은 카운터를 리셋하지 않는다(코드에 그런 호출이 없다) — 리셋 사유는 둘뿐이다. 구성값은 복사한 상수가 아니라 카운터에서 읽는다(`RepCounter` 가 `refractoryMs`·`maxGapMs`·`completeOnReturn`·`effective*` 를 공개) — 복사한 상수는 `forSession` 이 바뀌어도 로그가 옛 값을 적는다.
  `after_t_ms` 가 필요한 이유: 프레임의 `t_ms` 는 추론 **전** 시각이고 리셋과 `onFrame` 은 같은 락 안에서 순서가 정해진다. 추론 중(~60 ms)에 누른 전환은 누른 시각보다 이른 프레임보다도 먼저 적용되므로, 누른 시각으로 자르면 재생이 리셋을 한 프레임 늦게 놓는다(합성 자가 검증 H/H2 가 그 차이로 카운트가 갈리는 것을 확인).
- 새 코어 대비(휴면): `PostureLive` 는 완료 프레임마다 `newlyPublished` 의 사이클 수만큼 센다(시작 확정이 첫 두 사이클을 한 프레임에 발표하므로 +2 가능). 레거시 경로는 이 목록이 늘 비어 있어 지금처럼 한 프레임 한 회다.
  새 코어의 `resetCycle()`(일시정지·카메라 전환)은 진행 중 사이클과 **보류(확정 대기) 사이클**을 함께 버린다 — 남기면 리셋 앞의 한 번이 리셋 뒤 첫 사이클과 짝지어 +2 로 발표된다(원칙 #1). 끊김(maxGap 초과)은 진행 중 사이클만 버린다(프로토타입·배터리와 같다).
- **런지류 반복 정의를 준비 안내에** — **§59 가 대체했다.** 이 절의 빌드는 `ExerciseProfile.statesSideCount` 로 런지류 준비 안내에 걸음 단위 정의("번갈아 하는 동작은 한쪽 1회를 1회로 셉니다.")를 넣었다.
  사용자가 그 정의를 뒤집어(2026-09-24, 설계 §15 #18) 지금은 `ExerciseProfile.repUnit` 과 "왼쪽과 오른쪽을 한 번씩 해야 1회로 셉니다." 이고, 표시 수·진행이 좌우 쌍 단위다(§59).
  남은 판단은 그대로다: 덤벨 컬·스탠딩 니업은 교대 동작(`alternating`)이지만 짝 규칙을 안내하지 않는다 — 카운트 신호가 두 팔·두 엉덩이 평균이라 쪽별 사이클을 약속할 수 없다(MM-Fit 교대 컬 영상에서 지금 카운터 재현율 0.09, 원칙 #1).
  왜 시작 전에 밝히는가: 신호 교체로 런지의 목표 도달 자동 진행이 실제로 걸리고(자동 진행 유지는 사용자 결정, 설계 §15 #23), 사용자가 "10회" 의 단위를 모르면 기대와 다른 때에 세트가 끝난다(설계 §4.4·§4.8).
- **재생·로그 형식 잠금**: `app/src/test/resources/setlog_s58_fixture.txt`(골든 줄 2개 — 레거시·새 코어)를 `PostureSetLogTest` 가 `SetLogJson.encode` 와 바이트 비교하고, `setlog_captures.py --self-test` 가 같은 파일을 읽어 변환·파이썬 인코더 사본과 비교한다 — 키 이름·순서·숫자 형식이 한쪽만 바뀌면 둘 중 하나가 깨진다(`.jsonl` 은 .gitignore 대상이라 `.txt`).
  재생기(`Replay.kt`)의 hysteresis 구성은 극성을 로그(`reps.config.polarity`) → 등록부 → 연구 표(세 대상 종목 + 기기 픽스처가 있는 푸시업류) 순서로 정하고, 모르면 재생하지 않는다(틀린 극성은 모든 세트의 마지막 반복을 잃는다).
  `AiHubReplayTest`(폰 재생)도 `RepCounter.forSession` 을 부른다 — 예전의 손으로 만든 반전형 구성은 앱과 다른 카운터·앵커 시점을 쟀다.
- 검증 상태: 이 컨테이너는 안드로이드 빌드가 불가(Google Maven 차단). 순수 posture 파일(편집본)은 안드로이드 타입 스텁을 둔 JVM 스크래치 빌드에서 컴파일하고 `app/src/test/.../posture` 30개 클래스 **244개** 테스트를 돌려 전부 통과했다(리뷰 반영 뒤 — RepHysteresisTest 22·PostureSetLogTest 7·PostureSetReportTest 20·CapturePreparationTest 포함). `PostureLive.kt`·`SessionScreens.kt`(Compose)와 `AiHubReplayTest`(androidTest)는 바뀐 줄을 원문 그대로 떼어 실제 posture API 에 대고 타입 검사만 했다. **PC 에서 `./gradlew :app:testDebugUnitTest` 와 `./gradlew :app:assembleDebug` 가 남아 있다.** 폰 동작(열 이벤트 중복, 리셋 시각, 화면 문구, 준비 안내 음성 길이)은 Gate A 에서 확인한다.

## §59 — 런지류 좌우 한 쌍 = 1회 (2026-09-24, 사용자 결정 — PC 빌드 검증 대기)

**사용자 결정**(원문): "런지 자동진행 유지하고 한쪽 1회하고 다른 한쪽 안했으면 다른쪽도 진행한다음 두 쪽 진행이 전부 완료되어야지 1세트로 해".
앱은 이것을 **왼쪽 한 번 + 오른쪽 한 번 = 1회**로 구현했다 — 목표 10회 = 왼 10 + 오른 10(20걸음). 한쪽만 했을 때는 표시 수가 오르지 않고 화면에 '반대쪽 차례' 가 뜨며,
두 쪽을 다 해야 1회가 오른다. 목표 도달 자동 진행(§42)은 유지하므로 세트는 두 쪽이 모두 목표에 닿은 뒤 넘어간다. §58 의 걸음 단위 정의("한쪽 1회를 1회로")는 폐기(설계 §15 #18·#23).

- **대상 — 런지·바벨 런지·사이드 런지·크로스 런지만** (`ExerciseProfile.repUnit = RepUnit.SIDE_PAIR`, `statesSideCount` 는 없앴다). 카운터(`RepCounter`·`RepHysteresis`·`ReturnRepTracker`)는 바꾸지 않았다 —
  무릎 신호(런지 `knee_mean` · 크로스 런지 `knee_mean` · 바벨 런지 `knee_minside` · 사이드 런지 `knee_minside`)가 걸음마다 한 번 내려가 **사이클 하나 = 한 걸음**이고,
  새 순수 파일 `posture/RepUnit.kt` 의 `RepUnitAccumulator` 가 연속한 두 사이클을 1회로 묶는다.
  덤벨 컬·스탠딩 니업은 교대 동작(`alternating` 은 메타데이터로 남음)이지만 `RepUnit.CYCLE` 이다 — 두 팔·두 다리 **평균** 신호(`elbow_mean`·`hip_mean`)라 양쪽을 함께 하는 반복은 이미 한 사이클 = 양쪽 = 1회이고,
  한쪽씩 번갈아 하는 반복은 쪽별 사이클도 쪽 귀속도 없다(MM-Fit 교대 컬 영상 재현율 0.09). 지킬 수 없는 짝 규칙을 약속하지 않는다(원칙 #1). 나머지 종목·바닥 종목도 `CYCLE`.
- **화면·음성** (`PostureLive.kt`, `LiveWorkoutHud.kt`, `ExerciseProfiles.kt`):
  - 준비 안내(화면·음성): 런지류에만 "왼쪽과 오른쪽을 한 번씩 해야 1회로 셉니다." (`ALTERNATING_COUNT_RULE`, "5초" 문장 앞).
  - HUD '목표 N회' 옆에 "좌우 한 번씩 = 1회", 첫 쪽을 마친 동안 "반대쪽 차례"(강조). 접힌 제어판 라벨 "자동 횟수 · 참고 · 좌우 한 번씩 = 1회 [· 반대쪽 차례]".
    **화면 전용** — 어느 쪽인지 모르고(번갈아 한다는 가정) 한쪽을 몰아서 하는 사용자에게는 틀리므로 말하지 않는다(원칙 #6). 숫자 음성(`speakRep`)은 바뀌지 않았고 표시 수만 말한다.
    HUD 에도 둔 이유: 운동 중에는 제어판이 접혀(`LivePanelController.beginSession`) 라벨만으로는 보이지 않는다.
  - `repCount`/`repInvalid`·`onRepDetected`(진행·자동 넘김)·숫자 음성이 1회 완료에 한 번씩 — 표시 단위다. 세트마다 카운터를 만들 때 그 세트 종목의 단위(`RepUnit.forSession(profile, floor)`)로 누적기를 함께 만든다(이 화면은 종목이 바뀌어도 재구성되지 않을 수 있다 — CLAUDE.md 함정).
    카운터 완료 프레임의 처리(발표된 사이클 → 렙 기록 + 표시 단위의 완료 회·반쪽·템포)는 화면 코드가 아니라 순수 함수 `RepUnitAccumulator.onCounterFrame` 이다 —
    사용자가 요구한 동작("두 쪽을 다 해야 1회", 자동 진행)이 테스트 없는 Compose 코드에만 있으면 걸음마다 세도록 되돌려도 아무 테스트도 깨지지 않는다.
    `RepUnitTest`(세션 카운터 `RepCounter.forSession` 에 합성 걸음 신호 → 표시 [0,1,1,2,2,3], 짝 ROM, 쪽 사이 일시정지, 새 코어의 첫 두 걸음 동시 발표)와
    `WorkoutSessionTest.lungeAutoAdvanceWaitsForTheSecondSideOfTheLastPair`(→ `SessionProgress.targetReached`: 목표 3회는 5걸음째까지 거짓, 6걸음째 참)가 잠근다.
  - 짝의 ROM(`RepUnitAccumulator.combine`): 어느 쪽이라도 미달 → 미달, 아니고 어느 쪽이라도 미판정 → 미판정, 둘 다 충족 → 충족. 판정하지 않은 쪽을 충족으로 올리지 않는다(원칙 #1).
  - 완료 화면 자가 라벨: 런지류는 "실제 몇 회 하셨어요?" 아래에 "좌우 한 번씩 = 1회" 를 보인다(`SessionScreens.SelfLabelSlot`, 화면 전용) — 스테퍼가 쌍의 수로
    시작하므로 단위를 밝히지 않으면 걸음을 세는 사용자가 두 배를 적는다. `labels/set_labels.jsonl` 에 그 세트 화면의 단위 `rep_unit` 을 함께 적는다(알 때만 — 없으면
    키 부재, `rep_truth.csv` 는 열이 고정이라 넣지 않는다).
  - 일시정지·카메라 전환은 이미 센 첫 쪽을 **지킨다**(`onCounterCycleReset` 은 아무것도 바꾸지 않는다) — 카운터가 이미 발표한 실제 걸음이고 사용자는 쪽 사이에 쉴 수 있다.
    카운터 리셋이 버리는 끝나지 않은 사이클·확정 대기 후보(잡음일 수 있는 것)와 다르다.
  - 첫 반복 앵커·비교 시작·빠른 렙 자가진단은 여전히 카운터 **사이클**에서 걸린다(움직임의 성질).
- **세트 리포트** (`PostureSetReport.kt`): `repsValid`·`repsPartial`·`tempoMs` 가 표시 단위(템포 = 1회 완료 간격 = 두 걸음). 새 필드 `repUnit`·`repHalfPending`.
  좌우 쌍 세트의 렙 줄(`repDetailLine`)에 " · 좌우 한 번씩 = 1회", 세트 끝에 짝 없는 한쪽이 남았으면 " · 반대쪽 없이 끝난 한쪽은 세지 않음". `RepRomTier` 문구·`voiceLine`·`summaryLine` 은 그대로(수는 이미 쌍).
  **짝의 ROM 단계는 걸음 단위 검증에서 물려받은 것이다**: 바벨 런지의 VALIDATED(knee_minside ≤ 112.0852°)는 사이클(한 걸음) 하나에서 검증된 기준이고, 짝은 두 걸음 판정을
  합친다(한쪽이라도 미달이면 미달). 짝 단위의 오판정률은 재지 않았다 — 걸음마다 오판정률 p 면 짝은 약 2p 일 수 있다. 화면의 '렙 유효 · 무효' 는 "두 걸음 중 하나라도
  검증 기준에 못 미쳤다" 는 뜻이다(`RepRomTier` KDoc). `voiceLine`('N렙 파셜 m')은 지금 부르는 곳이 없어(§58) 짝 판정이 음성으로 나가지는 않는다 — 다시 연결할 때
  걸음 단위 표기로 할지 정한다.
- **세트 로그** (`PostureSetLog.kt`, 스키마 호환 추가 — `valid` 뒤·`engine` 앞, null 이면 키 부재 = 이전 로그): `reps.unit`("cycle"|"side_pair"), `reps.cycles_per_rep`(1|2),
  `reps.completed`(화면에 보인 수), `reps.half_pending`(true 일 때만). **`reps.count`·`t_ms`·`min`/`max`/`valid`·`invalid` 는 카운터 사이클 그대로다** — 재생 파리티가 사이클을 센다.
  골든 픽스처(`setlog_s58_fixture.txt`)에 셋째 줄(바벨 런지, 사이클 [true,false,true] → completed 1, half_pending, 일시정지 리셋)을 더했다. 앞 두 줄은 바이트 그대로. 파이썬 인코더 사본(`setlog_captures.py`)도 같이 바꿨다.
- **예상 시간** (`WorkoutSession.kt`): `WorkoutPacing.secondsPerRep` = 한 동작 시간 × `ExerciseProfiles.forName(name)?.repUnit?.cyclesPerRep ?: 1` — 런지류 1회 = 4 s × 2 = **8 s**.
  왜: 목표 10회가 20걸음이 됐으므로 한 걸음 시간으로 두면 세트 시간을 절반으로 잡는다. 단위를 프로필에서 읽어 자동 횟수와 예상 시간이 같은 정의를 쓴다.
  효과: 런지 10회 × 3세트의 세트당 운동 시간 추정 40 s → 80 s, 루틴 예상 분·(경과 시간 없이 만든) 칼로리 추정이 런지 몫만큼 대략 두 배(= 20걸음과 맞다).
  사용자가 이미 저장한 페이스(`secondsPerRep`, 편집한 런지는 옛 기본 4 가 저장돼 있다)는 그대로 우선한다 — 이 값은 이제 과소 추정이고 이관하지 않았다. 상한 15 s/회는 그대로(런지는 걸음당 7.5 s).
- **연구** (`research/external_rep_replay/side_attribution.py`, 설계 §18, MM-Fit 개발 데이터 — 성능 주장 아님):
  쌍 단위 floor(사이클/2) 세트 정확 일치 새 코어 0.98 · 지금 앱 0.19(걸음 단위 0.97 · 0.18). 자동 진행 시점은 걸음 단위 목표 2P 와 모든 세트에서 같다.
  MediaPipe 걸음 좌우 판별은 정면에서 3D 기준과 0.983 이지만 확신 있는 이름 바뀜이라 판별로 센 min(L, R) 은 0.84 — **카운트·다음 쪽 이름에 쓰지 않는다**(설계 §15 #24).
  3D 기준을 믿을 수 있는 47세트는 전부 완전 교대(floor = min). 사이드·크로스 런지는 재지 않았다.
- **알려진 한계**: 쌍은 교대 가정이다. 한쪽을 몰아서 하면 총수는 맞지만 도중 '반대쪽 차례' 가 틀린다. 카운터(beta)가 한 걸음을 놓치거나 더하면 짝이 한 걸음 밀린다 —
  세트 앞 헛사이클이 홀수면 세트 내내, 마지막 걸음을 놓치면 반쪽이 남아 목표에 닿지 않는다(✓ 또는 한 걸음 더). 걸음 단위 목표에도 같은 한 걸음 노출이 있었다.
  **세트 앞 헛사이클이 홀수인 세트에서는 사용자 규칙 자체가 깨진다**: 준비 동작 한 번이 첫 '쪽' 이 되어 실제로는 한쪽만 하고도 1회가 오르고(HUD 는 실제 걸음 전에
  '반대쪽 차례'), 그 뒤 1회는 매번 사용자의 첫 쪽에서 오르며, 자동 진행은 마지막 쌍의 둘째 쪽 전에 걸린다(MM-Fit 새 코어 16/62세트 — 개발 데이터, 앱의 세트 시작과 다른
  세트 사이 휴식 창). 막는 장치는 없다 — 후보(오래된 반쪽 버리기, 첫 쌍 전 '반대쪽 차례' 숨기기)는 쪽 사이에 쉬는 사용자의 첫 쪽을 버리거나 맞는 안내를 숨기는
  대가가 있어 Gate A 자료로 정한다(설계 §4.4·§15 #26).
- **연구 도구에 주는 영향** (`research/external_rep_replay`, README §9): 재생·파리티는 **카운터 사이클**을 센다(`reps.count` 와 재생 사이클). `reps.unit` 이 없는 로그는
  이 절 이전 빌드라 표시도 걸음이었다(사이클 단위로 읽는다).
  - `setlog_captures.py`: 런지류 자가 라벨(완료 화면 스테퍼·`rep_truth.csv`)은 화면 단위(쌍)다 — `truthDisplayedReps`(라벨 그대로)와 사이클 범위 `truthCyclesMin`~`truthCyclesMax`
    (2P ~ 2P+1, 짝 없는 한쪽)로 적고, `truthReps`(run_replay·parity_core 가 정확한 정답으로 쓰는 사이클 수)는 범위가 한 값일 때만 채운다 — 쌍 라벨이면 None 이라,
    짝 없는 한쪽으로 끝난 정직한 세트가 run_replay 에서 과다로 채점되지 않는다. confirmed 도 같다. `set_labels.jsonl` 의 `rep_unit` → `labelUnit`·`labelUnitMatchesLog`.
  - `score_phone_reps.py`: 런지는 **두 단위**로 채점한다 — 걸음(정답 = 집계 왼 + 오른, 예측 = `reps.count`·재생 사이클; 쌍 자가 라벨은 걸음 정답에 넣지 않는다)과
    쌍(정답 = min(왼, 오른) 또는 같은 단위 자가 라벨, 예측 = 재생 사이클 // 2·그 빌드가 쌍으로 보였을 때의 `reps.completed`). 쌍 표에 위상 밀림(세트 앞 헛사이클 홀수)과
    반사실 조기 자동 진행을 싣고, 런지 Gate B 는 두 단위 모두 통과해야 통과다. 팔별 집계가 있는 컬 세트(`altcurl`)는 쌍 표에 참고로 싣는다(예측 = 사이클 그대로).
  - 수집 프로토콜(`REP_VALIDATION.md`, `rep_validation_plan.py`): 런지 세트는 10걸음 = 앱 표시 5회(쌍), 집계는 `tally_left`·`tally_right`(+ 영상이면 `tally_order`),
    완료 화면에는 min(왼, 오른). Gate A 에 런지 한쪽 몰아 하기 `sideblock`·번갈아 하는 컬 `altcurl` 각 1세트(둘 다 판정 밖). 덤벨 컬의 판정 세트는 양팔 동시다 —
    컬 Gate B 판정은 **양팔 동시 컬에 대한 판정**이다(번갈아 하는 컬은 앱이 쪽을 가리지 못한다, 설계 §7·§15 #25).
  - 남은 것: `run_replay.py` 에는 쌍 단위 출력이 없다(쌍 라벨 세트는 사이클 정답이 없어 unlabeled 로 빠진다 — 쌍 단위 채점은 score_phone_reps).
- **검증 상태**: 이 컨테이너는 안드로이드 빌드 불가. 공용 JVM 스크래치 하네스(순수 posture 파일 + 테스트, `RepUnit.kt` 심볼릭 링크 추가) **265/265**(32 클래스, 하네스 자체의 골든 덤프 1 포함 — 저장소 posture 테스트 264. `RepUnitTest` 16
  (세션 경로 7 포함) · `SetLabelStoreTest` 6 · `PostureSetLogTest` 8 · `PostureSetReportTest` 21 포함), replay-jvm 41/41, `setlog_captures.py --self-test` 46/46(3줄 골든 바이트 비교,
  쌍 라벨의 사이클 정답 없음·run_replay 채점·라벨 단위), `score_phone_reps.py --self-test` 42/42(런지 두 단위·번갈아 하는 컬), `side_attribution.py --self-test` 32/32.
  `WorkoutSessionTest` 14/14(`lungeRepIsALeftPlusRightPairSoItsPaceIsTwoSteps`·`lungeAutoAdvanceWaitsForTheSecondSideOfTheLastPair`)·`RoutineOverviewTest` 6/6 은 앱 파일 사본을 둔 별도 JVM 하네스에서.
  `PostureLive.kt`(세트 마감·카운터 생성·분석 루프·일시정지·카메라 전환·패널 라벨·HUD 표기)·`TrexAppState.kt`(라벨)·`SessionScreens.kt`(스테퍼 단위 조건)는 바뀐 줄을
  원문 그대로 떼어 실제 posture API 에 대고 컴파일했다 — 전체 파일(`LiveWorkoutHud.kt` 포함)은 컴파일하지 않았다.
  바뀐 기대값(사용자 결정 때문): `CapturePreparationTest` 의 런지 안내 문장·`repUnit`(그리고 "한쪽 1회" 금지를 모든 프로필로 넓힘), `PostureSetLogTest`·파이썬 자체 검사의 골든 줄 수 2 → 3. 지운 단정은 없다.
  **PC 에서 `./gradlew :app:testDebugUnitTest` 와 `./gradlew :app:assembleDebug` 가 남아 있다**(직전 빌드 #1, `e366723` 기준 327/327 통과 — `research/external_rep_replay/results/pc_build_check.md`).
  폰에서 볼 것: 반쪽 동안의 HUD 표기, 쪽 사이 일시정지, 사이드·크로스 런지가 실제로 걸음마다 한 사이클인지(과제의 전제이지 기기에서 확인한 것이 아니다).

## §60 — REHAB24-6 사전 등록 판정과 원인 (2026-09-24, 설계 §17·§19)

- §17 의 기준·채점기·코드(`9a48fd5`)를 그대로 두고 사용자 PC 에서 한 번 돌렸다. **두 종목 모두 "옮겨 가지 않음"** — 정자세 반복 재현율 스쿼트 가로 0.910 · 세로 0.500, 런지 0.731 · 0.769(기준 0.95).
  정밀도는 네 칸 모두 0.97~0.99, 새 코어는 네 칸 모두 지금 앱보다 많이 센다. 옛 런지 신호 `knee_out_mean` 은 재현율 0.00~0.03 — 신호 교체는 설계에 쓰지 않은 자료에서도 맞았다.
- 원인(`rehab_diagnose.py`, 집계만): 세로 스쿼트 놓침 94건 중 88건은 반복 구간에 `knee_mean` 이 없다 — 세로 녹화에서 하체 관절이 화면 밖이거나 가시성 < 0.5 인 샘플이 녹화 중앙값 45%(가로 0%).
  원본은 처음부터 1080×1920 이고 회전 태그 문제가 없어(사용자 PC 확인) 추출 오류가 아니라 촬영 구도다. 가로·런지의 놓침은 대부분 스윙 < 35° 의 얕은 반복(스쿼트 14/195, 런지 21~25/174), 카운터 논리(확정·시각·기타)의 몫은 종목·카메라마다 2~5건.
- 이 결과로 상수를 바꾸지 않았다(CC BY-NC, 원칙 #4). 다음 후보는 구조 변경 — 프레이밍 게이트, 세트별 적응 게이트, 시작 자세 게이트(설계 §15 #27·#28, §19.3). REHAB 은 원인까지 본 뒤라 봉인 자료가 아니며, 서비스 판정은 Gate B 에서만 한다.

## §61 — 렙 검증 모드와 Gate A PC 파이프라인 (2026-09-24, 브랜치 `claude/rep-validation-env`)

- **켜는 법**: 앱 전용 외부 폴더의 표시 파일 `rep_validation.on`(`RepValidation.kt`). 화면 메뉴가 아닌 이유 — 검증은 어차피 USB 로 하고, 일반 사용자가 실수로 켤 길이 없어야 한다.
  운동 단계가 바뀔 때마다 다시 읽으므로 세션 사이에 켜고 끌 수 있다. 켜져 있으면 배너가 늘 보인다(조용히 켜져 있으면 안 된다).
- **켜졌을 때만 달라지는 것과 왜**:
  - 반복 세트가 목표에 닿아도 넘어가지 않는다 — 세트 뒤 헛카운트와 마지막 반복(상단 확정에 다음 하강이 필요해 구조적으로 놓치는 몫)까지 재려면 세트 끝을 사람이 정해야 한다.
  - 음성을 끈다 — 숫자·코칭 발화가 동작과 템포를 바꾼다(원칙 #6). 끄기 전 상태를 기억했다가 되돌린다(SpeechCoach 는 세션 사이에 공유된다).
  - 화면의 자동 횟수를 "검증 중" 으로 가린다 — 집계자가 앱 숫자에 끌려가면 정답이 앱을 닮는다. 세는 것·로그·완료 화면 자가 라벨은 그대로다.
  - 세트 로그에 `validation`·`image{w,h}`, 검출 프레임마다 `xy`(정규화 좌표 33×2)·`w`(MediaPipe 월드 원값 33×3, m, MediaPipe 부호)·`up` 을 더 쓴다(소수 4자리, 미검출 프레임은 키 없음).
    REHAB24-6(§60)에서 세로 촬영이 하체 샘플의 45% 를 잘랐다 — 폰에서 그 빈도를 먼저 재야 프레이밍 게이트를 설계할 수 있다. 좌표가 있으면 후보 카운터·게이트를 새 세션 없이 다시 돌릴 수 있다.
- **꺼져 있으면 바이트 그대로**: 골든 `setlog_s58_fixture.txt` 앞 세 줄이 바뀌지 않았고, 넷째 줄이 검증 모드 인코딩을 고정한다(파이썬 인코더 사본과 바이트 일치).
- **PC 쪽**(`research/external_rep_replay/`, README §12, 절차 `GATE_A_RUNBOOK.md`): `pull_phone.py`(adb 찾기·회수·표시 파일 — 기기에서 지우는 명령은 표시 파일 끄기뿐),
  `gate_a.py run`(캡처 → Gate A 채점 → 잡음 → 프레이밍 → 피처 무결성 → `report.md`), `gate_a.py dry-run`(폰·데이터셋 없이 합성 골격 7세트로 전 과정 18/18),
  `Replay --dump-features`(로그 좌표에서 앱과 같은 후처리로 피처를 다시 계산 — 로그 좌표가 앱이 실제로 쓴 좌표인지 검사).
- **판정 아님**: 드라이런은 도구 검사다. Gate A 도 개발 데이터라 성능 주장이 아니고, 서비스 판정은 Gate B(고정 빌드·새 사람 ≥ 5명)에서만 한다.

## §62 — 근거 출처를 AIHub 로 한정하지 않는다 · 스쿼트 4층 구조 · 반복 판별 게이트 · 발끝 방향 (2026-09-25, 사용자 결정)

### 결정 (사용자, 2026-09-25)
"AIHub 에서 못 잡는 건 우리가 새로 발견하고 보완한다. AIHub 에 없다는 이유만으로 개선에 보수적이었던 것을 폐지한다. 스쿼트 발 간격은 어깨 너비가 공식이다."

폐지되는 것은 **"근거의 출처는 AIHub 뿐"** 이라는 전제다. 원칙 #2(검증된 것과 검증 중인 것을 같은 확신으로 말하지 않는다)는 그대로다 — 바뀌는 건
근거를 **우리 폰 라벨 세트**로 만든다는 것. AIHub 조건에 없는 항목은 우리가 정의하고, 폰 데이터로 오탐률·검출률을 재서 beta → ship 으로 올린다.
"AIHub 에 없어서 못 한다"는 답은 하지 않는다. 원칙 #4(모집단 파라미터)도 그대로다 — 모집단이 AIHub 연기자에서 실사용자로 바뀔 뿐이다.

### 근거 — 2026-09-25 실기기 세트 (SM-N976N, `1.2.0-repval.1`, 바벨 스쿼트 3세트, 정면)
사용자가 **의도한 오류를 순서대로 섞어** 한 세트(정답을 아는 세트)다. 3세트 카운트 12 = 정상 3 + 무릎 벌림 2 + 발목(발끝) 벌림 2 + 허리 굽힘 2 + 무릎 들기 1 + 정상 2.

| 단계 | 앱이 한 것 | 로그가 말하는 것 |
|---|---|---|
| 무릎 일부러 벌림 2회 | 카운트 + "좋아요, 무릎 자세가 교정됐어요" | 규칙이 한 방향(`knee_out_mean__mean < 0.024` = 안쪽 모임만). 벌리면 0.30(정상 중앙값 0.067 의 4배)으로 OK 영역 → RECOVERED |
| 발끝만 바깥으로 2회 | 카운트, 아무 판정 없음 | `foot_pitch` −35~−50 / `stance_w` 1.7~2.1 로 정상 회와 구분 안 됨. **발끝 방향을 재는 피처가 없었다** — AIHub 조건에 없어 규칙도 exclude 도 없고 `PostureScope` 의 '못 봄' 에도 없었다(원칙 #5 위반) |
| 허리 굽힘 2회 | 카운트 + "등이 점점 말리고" + 세트 위반 | 시스템이 잡았다. 세는 것이 맞다 — "12회 중 허리 2회" 가 "10회" 보다 정보가 많다 |
| 한쪽 무릎 들기 7번 | **1번 카운트**(t=57.8 s) | `knee_mean` 은 양 무릎 평균이라 한쪽 30~60°·다른 쪽 156~163° → 평균 95~120°, 스윙 40~65° 가 35° 게이트를 넘는다. 7번 중 1번만 센 건 불응기·최대 간격에 걸려서지 막아서가 아니다 |
| 세트 판정 | 척추 위반 · 무릎 위반 · 고개 위반(beta) · 발바닥 유보 | **무릎 규칙이 서 있는 자세를 보고 있었다**: 서 있는 프레임 118개(`knee_out` 중앙값 −0.018 < 임계) vs 바닥 ~20개(0.08~0.22, 정상 중앙값 위). 세트 평균은 서 있는 자세가 정한다. 1세트(스쿼트 0회, 20초)도 무릎 위반 |

**"자세 여러 항목을 확인한 뒤에만 센다" 는 기각했다.** 그 규칙이면 오늘 3세트는 0회다(정상 5회 포함). 오탐 하나가 "코칭 한 번 틀림" 이 아니라 "한 회 증발" 이 되고(원칙 #6 의 확장), 관측 시스템은 스타일과 오류를 못 가린다(원칙 #3·§29). 횟수("몇 번") 와 자세("어떻게") 는 다른 질문이다.
**"양쪽 무릎이 동시에 굽어야 센다" 는 채택했다** — 자세 검사가 아니라 **동작 판별**로. 더 편 쪽 무릎(`knee_maxside`)의 사이클 스윙: 스쿼트 12회 전부 ≥ 65°, 무릎 들기 7번 전부 < 8°. 35° 는 카운트 신호와 같은 모집단 상수라 새 상수가 없다.

### 스쿼트 4층 구조 (설계 §20 — 이번에 2층·3층 일부 구현, 1층은 다음)
1. **시작 자세 검사**(세트 전, 서 있을 때): 발 간격 `발목 간격 ÷ 어깨 간격`(정면 2D, 같은 높이의 수평 거리라 원근 상쇄 — 지금 `stance_w` 는 바닥에서 두 배로 뛴다), 발끝 방향, 프레이밍(엉덩이·무릎·발목·발끝 가시성). 안내는 **시작 전에** — 서 있을 때가 가장 정확하고 오탐의 비용이 0 이다.
2. **반복 판별**(카운트 게이트 — 자세 품질은 안 본다): 양 무릎 굽힘(구현), 바닥 좌우 무릎 차, 엉덩이 하강, 발 제자리, 복귀 완료.
3. **반복별 자세 판정**(반복 창 안 통계 — 세트 평균·범위가 아니라): 무릎 방향(바닥 1/3, 양방향), 상체(반복 최대 − 그 세트 서 있는 기준), 깊이, 뒤꿈치, 고개, 발 간격·발끝(상단마다). 결과는 반복 단위 "12회 · 허리 2회 · 무릎 0회".
4. **발화 정책**: 1층은 자유롭게, 반복 중 음성은 폰 데이터로 검증된 규칙만, "교정됐어요" 는 정상 띠 안에 들어왔을 때만, 반복 0회 세트엔 자세 판정 없음.

### 구현 (이번 커밋)
- **반복 판별 게이트** — `RepSignal.identityFeature/identityMinAmp`, `RepCounter.onFrame(t, value, identity)`(셋째 인자 기본 null → 두 인자 호출은 종전과 같다). 사이클 창 = (직전 판별 사이클 끝, 이번 사이클 끝]. 스윙 < 게이트 → 세지 않고 `rejectedReps`, 센 사이클은 `identitySwings`. 창에 판별 샘플 2개 미만 → 판정하지 않고 센다(원칙 #1). 세 경로(반전·복귀·새 코어) 모두 같은 규칙, 새 코어는 함께 발표된 사이클마다. 등록부: **바벨 스쿼트만** `knee_maxside` 35°. 런지류는 한쪽 무릎 종목이라 붙이지 않는다.
- **세트 로그** — `reps.config.identity{feature,min_amp}` · `reps.rejected[{t_ms,min,max,swing}]` · `reps.identity_swing[]`(판별 신호가 있는 종목만, 없으면 키 부재). 골든 `setlog_s58_fixture.txt` 첫·둘째·넷째 줄 갱신, 파이썬 인코더 사본(`setlog_captures.py`) 동기. 재생기(`Replay.kt`)도 판별 값을 준다 — **파리티가 이 인자에 기댄다.**
- **재생 확인**: 오늘 3세트를 `run_replay.py --configs live` 로 다시 셌다 — 1세트 0/0, 2세트 6/6(스윙 75~100°), **3세트 11 (로그 12)** — 기각된 사이클은 정확히 t=57780(무릎 들기, 스윙 14.1°), 남은 11회 스윙 65~102°.
- **발끝 방향 피처** `toe_out_L/R/mean/maxside/asym`(`PostureCore`): 뒤꿈치(29/30)→발끝(31/32)을 수평면에 눕혀 몸 앞 방향과 이루는 각, 바깥 +(knee_out 부호 규약, 미러 불변 — `ToeOutTest`). 수평 발 길이 < 3 cm 면 없음.
- **폰 규칙셋** `assets/posture/rules_phone_v0.json`(`phone_v0.1`) — AIHub 밖 규칙의 자리. `PostureRuleSet.plusPhone(context)` 로 mp+floor 뒤에 붙는다(4곳: PostureLive·PostureScopeCache·BaselineGuide·PostureLab). 첫 규칙 `phone|바벨 스쿼트|발끝 방향`: `toe_out_maxside__p90 > 40` **beta·잠정**(스쿼트 관용 발끝 각 5~30° 의 바깥 여유) — 폰 라벨 세트로 확정 전까지 화면 '참고' 만, 음성·점수 없음. `rules_version` 은 `mp_v0.1+floor_v0.4+phone_v0.1`. 코치 문구·강조 부위(발·발목)·비교 지표 이름 추가.
- 손으로 관리하는 파일이다(`rules_mp_v0.json` 처럼 파이프라인이 생성하지 않는다). `counts` 는 `rules` 배열과 맞춘다.

### 근거를 만드는 절차 — "지정 오류 세트"
오늘 세션이 원형이다. 한 세트 안에 정상 N회 + 지정 오류 유형 M회를 **순서와 수를 미리 적고** 하고, 로그의 반복별 값과 대조한다. 규칙마다 "정상 회 위반률 / 오류 회 검출률" 이 나오고, 그것이 `confidence` 에 **폰 데이터 출처**로 들어간다. ship 기준은 그대로, 출처만 바뀐다. Gate A 계획표에 이 세트를 넣는다(`REP_VALIDATION.md` 에 절 추가 — 다음).

### 다음
2층 나머지(좌우 무릎 차·엉덩이 하강·발 제자리 — 오늘 로그로 계산 가능, 임계는 사람 2~3명 뒤), 3층(반복 창 통계 — 무릎 규칙의 세트 평균 오탐이 직접 원인), 1층(시작 자세 검사 — 발 간격 비율 피처부터), RECOVERED 문구, 반복 0회 세트의 자세 판정 억제.

## §62a — 반복별 자세 검사·정확 횟수 구현 (2026-09-25 후속, 설계 §21)

- **정본이 바뀌었다**: §62 의 `rules_phone_v0.json` 은 삭제하고 반복별 검사 등록부 **`RepFormSpecs`(코드)** 로 대체했다. 이유 — 재생기(replay-jvm)가 org.json 없이 앱과 같은 검사를 돌려야 Gate A 가 검사의 오탐·검출을 잰다(§61 의 카운터 파리티와 같은 구조). 상태·띠·문장이 한 곳에 있다. `RepFormSpecs.asRules()` 가 규칙셋에 `kind: "rep_form"` 규칙으로 붙어 범위 문장·리포트 행·상태 표시를 맡고, 판정은 `RepFormSummary.ruleResult` 가 채운다(`PostureAssessment`). `rules_version` 은 `mp_v0.1+floor_v0.4+repform_v0.1`.
- **`PostureRuleSet.plusRepForm()`**(4곳: PostureLive·PostureScopeCache·BaselineGuide·PostureLab): 반복 검사가 대체하는 창 규칙(`RepFormSpecs.supersedes` — 스쿼트 `발과 무릎의 방향 일치`)을 **beta 로 낮춘다**. 그 규칙의 세트 평균은 서 있는 프레임이 결정해 바닥이 정상인 세트에 "무릎 안쪽" 4번을 냈다(10:52 세트). 음성·점수·헤드라인에서 빠지고 리포트 '참고' 로 남는다. 무릎은 반복 검사 ship 이 맡아 범위 문장에서 계속 '봄' 이다.
- **평가기** `RepFormEvaluator`(`RepForm.kt`): 카운터보다 먼저 프레임을 받고(`onFrame`), 센 사이클마다 창을 닫아 판정(`onCycle`), 기각 사이클은 창을 버린다(`onRejected`). 위상 자르기 — 상단 = 사이클 최소값 앞에서 마지막으로 서 있던 프레임(신호 ≥ 최대 − 0.22h)부터 거꾸로 ≤ 5개, 바닥 = 최소 + 진폭/3 아래, 사이클 = 상단 끝 다음부터. 시작 자세 = 첫 상단 창의 중앙값(피처별), 그때 START 검사 1회. 판별 샘플 2개 미만이면 유보(위반 아님).
- **정확 횟수**: `PostureLive` 가 ship 검사 위반 회를 `repIncorrect` 로 세고 목표 진행(`onRepDetected`)에서 뺀다 — 반복 수(`repCount+repInvalid`)는 그대로, HUD `countNote` "반복 N · 정확 M". 숫자 발화도 정확 수를 따른다(진행 수). ship 사건은 2회 연속 + 쿨다운일 때 음성 + 화면(`formNote`), beta 사건은 `provisionalNote`(참고).
- **피처**: `stance_sh`(발목 간격 ÷ 어깨 너비, 수평·월드 — `stance_w` 골반 정규화는 서 있을 때 ×0.78 헛경보·바닥 두 배). `toe_out_*` 는 **발목→발끝**으로 바꿨다(§62 는 뒤꿈치→발끝) — 정면·바닥 폰에서 오른 뒤꿈치가 91% 프레임에서 안 보였다(발목 96%). 사람별 상수 편향은 시작 대비 검사가 상쇄.
- **로그** `rep_form{version, baseline_t_ms, baseline{feature:value}, start[], reps[{t_ms, correct, checks[{id,v,value,raw,ref,dir}]}], rejected, no_top}` — 평가기가 있는 종목만. 인코더는 `RepFormLog.toJson` 하나(세트 로그·재생기 공용, 숫자 형식은 `SetLogJson.num` 과 같다). 골든 4줄 불변. 재생기 출력 `repForm`·`repFormCorrect`·`repFormFlags`·`repFormLines`.
- **공유 타입 이동**: `RuleStatus`·`Verdict`·`Direction` → `RuleTypes.kt`(안드로이드 의존 없음, 재생기 소스 목록에 추가). 규칙셋 연결(`asRules`·`ruleResult`)은 `RepFormRules.kt`(앱 전용).
- **검증**: 유닛 370/370(+`RepFormTest` 9·`StanceWidthTest` 2·`ToeOutTest` 개정·`PostureSetLogTest` +1), replay-jvm 50/50, 드라이런 18/18, `setlog_captures --self-test` 49/49. 재생 확인은 설계 §21.5 — 09:52 세트의 허리 굽힘 2회만 '상체 숙임' 으로 잡히고, 발 너비·발끝 반복 검사는 옛 로그에 피처가 없어 유보(새 세트 필요).
- **임계 관측**(잠정값의 근거이자 한계): 무릎 과도 벌림 바닥 평균은 정상 반복(0.34~0.35)과 일부러 벌린 반복이 겹쳐 0.40 으로 넓혔다 — 검출 근거 없음, beta. 09:52 허리 굽힘 반복 하나가 무릎 안쪽 모임(ship, 0.02) 에도 걸렸다 — 굽히며 무릎이 모였는지 다음 세트에서 본다.
- **§62a 후속(11:37 세트 재생, 설계 §21.7)**: 발 너비·발끝 반복 검사는 `RepPhase.STANDING`(하강 직전 상단 + 복귀 뒤 서 있음, 이월 포함) + `RepFormStat.EXTREME`(시작 기준에서 가장 먼 값)으로 바꿨다 — 바닥에서 발 너비가 부풀고(정상 ×1.31~1.5) 하강하며 옮긴 발은 복귀 뒤에 드러난다. 상단 창 이월(`carry`, 이 창의 서 있는 프레임이 2개 이상이면 버림). ship 무릎 검사에 원인 목록(`causes` = 발끝·발 간격) — 같은 반복에 발 위반이 있으면 문장이 발을 먼저 말한다. 방향별 위반 수, `RepFormRep.consecutiveShip`. 그 세트 결과: 정확 8/10, 7·8회(발 너비 ×1.8·발끝 −24°)가 ship 무릎(−0.01·0.02)으로 부정확 — 앱 음성은 8회에서 한 번, 숫자 6 → 7·8(9·10회) 로 진행이 정확 수를 따랐다.
- **§62a 후속 2(사용자 정답, 설계 §21.8)**: 발목 간격 발 너비는 발끝 회전에 오염(발끝 안쪽만 한 7·8회 ×1.8). `RepFormCheck.invalidatedBy` — 같은 반복에 발끝 위반이 있으면 발 너비 유보("발끝 위반으로 측정 무효"). 회전 불변 측정점은 검증 모드 좌표로 찾는다. 이 세트 1명 성적: 발끝 검출 6/6·오탐 1/4, 발 너비 검출 4/4·오탐 4/6(전부 발끝 회전 반복).
- **§62a 후속 3(검증 모드 좌표, 설계 §21.9)**: 발 너비 피처를 `stance_2d`(이미지 2D 발목 x 간격 ÷ 어깨 x 간격, `Stance2d.kt` — 앱 `PostureAnalyzer`·재생기 `frameFeatures` 공용, 재생기 소스 목록 추가)로 바꿨다. 월드 3D 발목 간격(`stance_sh`, 계속 로그)은 발끝 회전에 −18 %/+80 % 흔들렸고 2D 는 ±5 %. 12:19 세트 랜드마크 재생: 발 너비 검출 2/2·오탐 0/8. 띠 반복 0.7~1.4·시작 0.7~1.6. `invalidatedBy` 는 필드만 남기고 발 너비에서 뗐다.
- **§62a 후속 4(사용자 확인)**: 넓게 선 반복에서 발끝 그대로여도 +28° → 발 너비 위반 반복에서 발끝 유보(`invalidatedBy` = 발 간격). 12:19 세트 최종: 발 너비 2/2·오탐 0, 발끝 4/4·오탐 0(2회 유보), 정확 8/10.
- **§62a 후속 5(모집단 지도·좌표 상시, 설계 §21.10, `docs/SQUAT_FAULT_CATALOG.md`)**: REHAB24-6(9명)·MM-Fit(21 워크아웃) 재생으로 띠를 확정 — 무릎 안쪽 ship 임계 0.02388 → **0.0**(정상 오탐 8 → 4 %), 발 간격 반복 0.6~1.5(6 → 1~3 %), 발끝 반복 **서 있는 프레임 중앙값 ±15°**(±8° 가장 벗어난 값은 오탐 31~35 %), 시작 절대 띠 0.5~1.8·−5~45°(카메라 높이 의존). 좌표(`xy`·`w`·`up`·`image`)는 세션 로그에 **항상**(`coordinates = true`, 검증 모드와 분리; 인코더는 `image` 를 `validation` 밖으로). 실기기 12:19 최종: 발 너비 2/2, 발끝 2/4, 무릎 2/2, 정확 8/10.
- **§62a 후속 6(설계 §21.11)**: 서 있음 판정을 시작 자세 무릎각 기준으로(`STANDING_BAND_REF` 0.3h) — 실기기 발끝 검출 3/4. 카탈로그 #9·#13 구현: `RepPhase.ASCENT`·`RepFormStat.RISE`, 검사 '엉덩이 먼저 상승'(> 20°)·'좌우 무릎 비대칭'(바닥 ±30°)·'몸통 좌우 기울기'(±20°) — 전부 beta, 띠는 REHAB·MM-Fit 정상 반복 p95(오탐 ≤ 2 %). 스쿼트 반복 검사 10개(ship 1).

## §62b — 발 너비·발끝 검사 교체와 COACH 횟수 게이트 (2026-09-25 저녁, 사용자 결정 "임의로 정하고 구현", 연구 `docs/SQUAT_FOOT_RULES_RESEARCH.md`)

**왜.** 16:13~16:16 실기기 3세트에서 옛 발 너비 검사(`stance_2d` 서 있는 극값 ÷ 시작, 프레임별 어깨 정규화, 직전 창 이월)가 정상 반복을 ×3.21(어깨 한 프레임 26 px)·×1.90(넓게 한 직전 반복의 되돌리는 프레임 이월)으로 오탐했고, 그 오탐이 실제로 발끝을 벌린 회의 발끝 판정을 유보시켰다. 월드 3D 발끝 각은 실제 회전을 2D 의 6할로만 반영해(z 가 GHUM 추정치, A7a·A7c) +11~+12° 로 임계 바로 아래에서 놓쳤다. 사용자 결정: **COACH 는 자세 위반 회를 횟수에서 뺀다, TRACK 은 전부 센다** — 오탐 하나가 한 회를 지우므로 모집단 오탐률로 검사를 골랐다(A7b).

**피처(`Stance2d.kt`, 앱·재생기 공용, `aspect` = 폭÷높이)**: `ankle_sep_2d`(2D 발목 x 간격), `shoulder_sep_2d`, `toe2d_L/R/maxside`(2D 발목→발끝 각, 바깥 +, 골반 x 순서로 사람 좌우). `stance_2d` 는 로그·시작 검사에 남는다.

**검사(`RepFormSpecs`, `repform_v0.2`)**
| 검사 | 상태 | 창·통계 | 기준·띠 | 근거 |
|---|---|---|---|---|
| 발 간격(반복) | **ship** | BOTTOM 중앙값 `ankle_sep_2d` | ÷ 시작 ≥ ×1.4 **AND** ÷ 시작 `shoulder_sep_2d` ≥ 1.5 (`absRefFeature/absMin`) | 정면 정상 반복 오탐 0/243(REHAB 51·MM-Fit 192, 진짜 300 ms), 폰 넓힘 7/7(×1.53~2.35), 정상·발끝 회전·무릎 모음 오탐 0/27, 유보 0~2 %. 절대 조건은 시작 기준이 어긋난 세트(16:13)를 막는다 |
| 발 간격 좁아짐(반복) | beta | 같은 창 | ÷ 시작 < 0.7 | 참고만 — 검출 근거 없음 |
| 발끝 방향(반복) | **ship** | TOP 마지막 ≤3프레임(`windowFrames`) 중앙값 `toe2d_maxside`, 2개 이상 | 시작 대비 ±15° | 정면 오탐 REHAB 0 %·MM-Fit 4 %, 폰 검출 5/6. '한쪽만 넘어도' 는 검출 불변·오탐 2배라 채택 안 함. 발 너비 위반 반복은 유보(`invalidatedBy`) — 넓게 서면 모든 판독이 +14~+30°(MediaPipe 편향) |
| 발끝 방향(시작) | beta | START | −5~50° | 카메라 높이에 따라 영점 이동 — 안내용 |

**시작 기준 안정 규칙(`START_STABLE_RATIO` 1.10)**: 첫 상단 창에서 발목 간격 최대÷최소 > 1.1 이면 발을 옮기던 중 — 발 너비 기준을 그 반복의 바닥 중앙값으로(`baselineFromFirstBottom`, 요약 줄 "첫 반복으로 잡았어요"). 16:13 세트(60 → 97 px) 재현 테스트.

**횟수 게이트(`PostureLive`)**: `gate = COACH && !floor`. 게이트일 때만 `repIncorrect` 가 늘고(=`onRepDetected`·HUD 큰 숫자·목표 진행이 정확 수), `eventFor(gate = true)` 는 ship 첫 위반부터 말한다(쿨다운 12 s 유지) — "… 이 회는 세지 않았어요." HUD 보조 문구 "감지 N회 중 정확 M회만 셌어요". TRACK 은 검사를 돌리되 빼지 않는다(로그·리포트만). 원칙 #7 은 COACH 에 한해 이 결정으로 바뀐다(CLAUDE.md).

**임의로 정한 것(사용자 미확인)**: 절대 띠 1.5·시작 발끝 띠 −5~50°·안정 비 1.10. 16:16 7회 정답 미확인(2D +19° — 정상이었다면 2D 오탐 1건). 16:16 3회 누락은 랜드마크가 움직이지 않은 건이라 규칙으로 못 고친다 — 테이프 실험 대기.

**검증**: 유닛 380건(RepFormTest 19: 어깨 한 프레임 오탐·이월 오탐·불안정 시작·AND 조건·게이트 발화). 오늘 4세트를 좌표 캡처(`setlog_captures.py` → `.cap`)로 재생(`run_replay.py --configs live`, 재생기 재빌드) — 사이클 수는 앱과 같고(10·21·7·7) 반복별 판정은 사용자 정답과 일치: 12:19 정확 4/10(3·4 발끝 안 −46°+무릎 따라감, 5·6 발끝 밖 +18~19°, 7·8 넓게 ×2.16 → 발끝 유보), 16:14 정확 4/7(5~7 넓게 ×1.52~2.08), 16:16 정확 3/7(옛 오탐 2회·6회 해소, 4·5 넓게, 6 발끝 +21°, 7 발끝 +19°(정답 미확인), 3 은 여전히 정상으로 읽힘). 16:13(중력 up 오류 세트)은 2D 검사는 정상(발 너비 위반 1회, 첫 반복 바닥 기준 적용)이지만 월드 기반 ship 무릎 검사가 21회 전부 위반 → COACH 게이트에서 0회 — **up 오류 방어(별도 작업 `task_76c45018`)가 없으면 같은 오류에 세트가 통째로 지워진다.**

**§62b 후속(실기기 시험 뒤 사용자 결정, 2026-09-25 20:10)**: (1) **상체 숙임(반복) ship 승격** — 허리를 굽힌 회도 COACH 횟수에서 뺀다. 띠 그대로(시작 대비 +45°, 정상 반복 오탐 0/0/1 %, 실기기 검출 2/2). 월드 기울기 기반이라 up 오류 세트에서는 무의미해질 수 있다(up 방어 작업 대기). ship 은 넷(상체·무릎·발 너비·발끝). (2) **앱 표시 이름 "바벨 스쿼트" → "기본 스쿼트"** — `ExerciseProfiles`·`workoutCatalog`·`todayPlan`·`postureExerciseMap` 키·페이싱 표. AIHub 참조 이름(규칙 JSON·`RepSignals`·`RepFormSpecs` id·세트 로그 `exercise`)은 "바벨 스쿼트" 그대로. 저장된 루틴·기록의 옛 이름은 `WorkoutNames.canonical`(TrexData.kt)이 로드 시 바꾼다. "기본 스쿼트" 는 §56 에서 퇴역했던 맨몸 스쿼트 이름이기도 해서 `retiredWorkoutCategories` 에서 뺐다(옛 맨몸 스쿼트 기록은 이제 하체 카탈로그 분류로 같이 보인다). 유닛 380건 통과, 20:16 설치.

## §62c — 덤벨 컬: 팔별 카운터 · 본인 기준 비율 ROM · 컬 아님 기각 · 팔꿈치 앞 이탈 2단 검사 (2026-09-26, 사용자 결정 "구현해", 연구 `docs/CURL_RULES_RESEARCH.md`)

**왜.** 실기기 지적 두 가지 — (1) 반동·팔꿈치 이탈을 못 잡는다 (2) 얼마나 올렸다 내려야 1회인지 정의가 없어 반쯤 들어도 1회. 연구(B1 AIHub 1,439클립·B2 MM-Fit 598반복·B3 문헌)의 결론: 팔꿈치 이탈은 사선 2D **앞 성분**이 가른다(AUC 0.96, 월드 세트 규칙은 오탐 16 %), 팔꿈치 너비 벌어짐은 라벨이 반대 방향이라 beta, MediaPipe 월드 팔꿈치각의 절대값은 정답과 상관 0.04(진폭은 0.56~0.73)라 절대 ROM(81.3°)은 폐기하고 본인 진폭 비율로, 교대 컬은 두 팔 평균 신호로 못 센다(재현율 0.09).

**카운터(`RepCounter` 팔별 경로, `RepSignal.pairedFeatures`)**: 자식 카운터 둘(`elbow_L`·`elbow_R`, 새 코어 DOWN — 레거시 복귀형을 팔별로 쓰면 MM-Fit 세트 정확 0.05), 완료 = min(nL + 가상, nR + 가상) 증가 — 동시 컬은 사이클마다 1회, 교대 컬은 **왼 + 오른 = 1회**(런지 결정과 같은 단위, 사용자 미확인·임의). 한 팔이 2.5 s 넘게 안 보이면 다른 팔 사이클을 그 회로 센다(가상). 새 코어는 첫 두 사이클을 함께 발표하므로 한 프레임에 여러 회가 완료될 수 있다(`newlyPublished`·`newlyPublishedValid`). 입구는 `onFrameFeatures(t, features)` — 앱·재생기 공용(파리티).
- **ROM(본인 기준 비율)**: 그 팔 첫 2사이클 진폭 중앙값 A0(≥ 50°)에 대해 진폭 ≥ max(0.7·A0, 45°) 이면 유효, 아니면 '부분'. 기준 전·미확보(A0 < 50°)는 판정 안 함. `romExcludesShort` — 부분은 화면 횟수·목표에서 뺀다(HUD "감지 N회 중 M회만 셌어요 · 부분 K회"). B2: 정답 반복 유지 0.94(절대 각 0.69).
- **기각(컬 아님, `rejectFeatures`)**: 그 팔 사이클 구간에서 `torso_tilt2d` 범위 > 0.30(≈ 24°, 사선 뷰에서만 정의 — 정면이면 판정 안 함) 또는 `upperarm_vert_{side}` 범위 > 60° → `rejectedReps`(feature 포함). 월드 `torso_incl` 25° 는 정면 MM-Fit 정상 반복에서 45~110° 로 튀어 16회를 기각했고(월드는 못 씀, B1), 두 팔 구간을 합친 창은 교대 정상 반복을 걸었다(0.66) → 팔 사이클 단위·2D.
- **재생 검증(MM-Fit 교대 59세트, 정답 쌍 = floor(정답/2))**: 세트 정확 **0.76**, ±1 0.90, 과다 5, 부분 0, 기각 7(2D 몸통 3·상완 4). 기각 없는 상한 0.81(설계 §18.3 의 0.90 과 같은 자리 — 남은 놓침은 한 팔 미검출 w04·w17 과 여분 팔 사이클). 현행 `elbow_mean` 카운터는 0.00.

**피처(`Arm2d.kt`, 앱·재생기 공용)**: `elbow_fwd2d_L/R/mean`(골반→어깨 선에서 팔꿈치의 앞쪽 거리 ÷ 몸통, 앞 = +), `torso_tilt2d`(앞 숙임 +). 부호는 요로 — yaw > 0(B, 오른어깨 가까움)이면 앞 = 화면 +x, D 면 −x; 사선 띠(|yaw| 16.4~46.2°) 밖은 키 없음. `PostureCore` 에 `elbow_gap_sh`(월드 팔꿈치 간격 ÷ 어깨폭).

**반복 검사(`RepFormSpecs` 덤벨 컬, 창 분할 신호 `elbow_minside`)**: `팔꿈치 앞 이탈` ship — 수축 구간 `elbow_fwd2d_mean` 중앙값, 시작 대비 +0.07 이고 원값 ≥ 0.09(코칭, 오탐 ≈ 5 %·검출 ≈ 0.8) / **차단은 +0.12 이고 ≥ 0.12**(`gateHi`·`gateAbsMin`, 오탐 ≤ 2 % 목표) — `RepFormOutcome.gate`·`RepFormEvent.gated` 가 2단을 나른다. `몸통 반동`(2D 기울기 ±0.10)·`팔꿈치 벌어짐`(`elbow_gap_sh` ≥ 1.8) beta. 검사마다 뷰(`RepFormCheck.views` — 앞 이탈·몸통 B/D, 벌어짐 B/C/D)가 있고 `ruleResult(viewLetter)` 가 검사별로 유보한다. 월드 세트 규칙 `팔꿈치 위치 고정` 은 그대로 두었다(대체 표시는 다음).

**임의로 정한 것(사용자 미확인)**: 교대 컬 단위(왼+오른 = 1회), 2단 임계(스튜디오 2D 값 — 폰 좌표로 재보정 전), 상완 60°·2D 몸통 0.30, ROM 비율 0.7·하한 45°·기준 하한 50°. 폰 라벨 세트(정상·팔꿈치 앞·몸통 젖힘·벌림·반만·다 안 내림, D·C 뷰)로 확정한다.

**검증**: 유닛 390건(RepPairedTest 6: 동시·교대·가림·기각·기준 미확보·로그, RepFormCurlTest 4: 2단·beta·검사별 뷰). 폰 9/12 컬 2세트(피처만, 정답 없음) 재생은 아래 후속에.

**§62c 후속 — 폰 9/12 컬 2세트 재생(피처만·정답 없음, 동시 컬)**: 1세트 팔별 사이클 왼 18·오른 14(오른팔 8 s 미검출 구간은 가상 계수) → **16회**(당시 앱 8회), 부분 후보 왼 1·오른 2(진폭 43~48 < 0.7·A0); 2세트 왼 17·오른 16 → **15회**(당시 앱 11회), 기각 1(49~51 s 왼팔 상완 스윙 62° — B2 가 지목한 비컬 구간). 3세트(20프레임)는 0. 재생기 출력의 `invalid` 는 아직 팔별 ROM 판정을 반영하지 않는다(`Replay.kt` 가 `isValidRep` 로 계산) — Gate A 파리티 전에 `newlyPublishedValid` 로 맞출 것. 2026-09-26 00:26 설치.

**§62c 후속 2 — 정면 검사·2D 손목 가동 범위·팔 사이클 짝 규칙 (2026-09-26 밤, 사용자 리뷰 "신뢰성도 없고 교정도 안 돼서 위험한 상태")**

*계기.* 13회 정면(C) 세트(정상 4 · 팔꿈치 옆 벌림 4 · 반동 3 · 가동 범위 축소 2)에서 검사가 하나도 안 걸리고 13회가 다 세어졌다 — 앞 절의 검사가 전부 사선(B/D) 전용이라 정면에서는 유보였고, 월드 팔꿈치각 비율 ROM 은 짧은 회를 0.87~1.01 로 통과시켰다. 사용자 제안 셋: ① 팔꿈치가 어깨보다 일정 범위 바깥이면 벌림 ② 반동은 문헌으로 관절 위치 특징을 찾아 구현 ③ 가동 범위를 신체 비율로 정의하고 위·아래 끝에 닿아야 1회. 판정(B4 `research/camera_observability/B4_REPORT.md`): ① 지지 — 단 '어깨 대비 절대' 가 아니라 **시작 자세 대비 + 절대 하한**(정상 수축도 어깨보다 0.29 바깥까지 간다) ② 지지 — 정면에서는 반동·으쓱·앞 내밀기가 모두 '손목이 어깨 위로 넘음' 으로 나타나 한 검사로 포괄 ③ 절반 지지 — 아래 끝(손목 ≤ 어깨 아래 0.75 몸통)은 절대 신체 비율로 되지만 **위 끝은 안 된다**(정상 수축 손목 높이가 사람마다 −0.30~+0.10) → 본인 첫 회 대비 비율.

*정면 검사(`RepFormSpecs` 덤벨 컬, `Arm2d` 뷰 무관 피처)*: `팔꿈치 뜸` **ship** — 사이클 창 `wrist_h2d_max` ≥ 0.15(정상 초과 MM-Fit 1.5 %·AIHub 1.8 %, 폰 반동 3/3 0.20~0.24); `팔꿈치 옆 벌림` **ship 2단** — 수축 구간 `elbow_lat2d_max` 중앙값, 코칭 시작 대비 +0.15 이고 ≥ 0.30(오탐 0.7 %/2.9 %), 차단 +0.20 이고 ≥ 0.35(0.0 %/0.6 %), 폰 벌림 4/4(Δ0.33~0.35); `팔꿈치 높이 상승` beta(`elbow_rise2d_max` +0.20 이고 ≥ −0.30). 창 규칙 `팔꿈치 위치 고정`·`척추의 중립[all/flexion]`(고개 각 대리 — 폰을 내려다보면 걸린다)은 `supersedes` 로 beta 강등.

*가동 범위(`RepSignal.romAuxFeature` = `wrist_h2d_{side}`, 비율 0.8, 아래 끝 −0.75)*: 팔 사이클 창의 손목 높이 진폭 ≥ 0.8 × 본인 기준 이고 창 최소 ≤ −0.75. 월드 진폭 비율(0.7)과 **둘 다** 충족해야 유효, 한쪽이 미판정이면 다른 쪽을 따른다. 창은 팔 사이클 [startMs, tMs] 그대로 — 새 코어 사이클이 복귀 75 % 에서 끝나 그 회의 완전 신전은 창 뒤에 남지만, 창 앞 끝(하강 직전)이 직전 매달린 자세라 아래 끝 판정은 "매달린 자세에서 시작했는가" 가 된다. 앞 여유 1 s 를 뒀더니 첫 회 창에 덤벨 드는 동작이 들어가 기준이 부풀었다(MM-Fit 손목 기준 1.1~2.6 vs 이후 0.7~1.0, 부분 오탐 33건) — 폐기. **기준은 첫 3사이클 중앙값**(`romRefCycles` 2 → 3, 월드·손목 공통): 첫 사이클이 부풀기 일쑤라 2회 중앙값이면 그 뒤 정상 회가 부분이 된다 — MM-Fit 부분 오탐 41 → **21 / 629 팔 사이클(3.3 %)**, 회 단위 7 / 288(그중 4는 세트 끝 여분 사이클이라 맞게 뺀 것). 셋째 회까지 미판정. 아래 끝 −0.75 초과는 MM-Fit 5/629.

*팔 사이클 짝 규칙(`RepCounter` 팔별 경로)*: 순서로만 짝지으면(왼 i ↔ 오른 i) 한 팔의 조각 사이클 하나가 그 뒤 모든 회를 한 사이클씩 어긋나게 한다 — 폰 15:34 세트 9회에서 왼팔이 이중 굴곡(45.9° 조각 + 진짜)을 내자 마지막 부분 2회가 0회로 둔갑했다. 세트 단위 규약(첫 쌍의 겹침으로 동시/교대 확정)도 안 된다 — MM-Fit 교대 13세트의 첫 쌍이 100 % 겹치고(첫 사이클 창이 세트 시작부터) 폰 15:33 세트는 교대에서 동시로 바꿨다(세트 정확 0.76 → 0.59). 확정: **쌍마다** — 두 머리 사이클 창이 ≥ 0.5(짧은 창 대비) 겹치면 한 회; 안 겹치면 먼저 끝난 팔의 다음 사이클을 보아 그것이 반대 팔과 겹치면 앞선 것은 조각(`ArmCycle.orphan`, `armOrphans`, 회 아님), 다음 사이클이 반대 팔 사이클 전반부에 시작해 아직 돌아오는 중이면 기다리고, 어느 쪽도 아니면 교대(순서). 가림 대체(가상) 사이클은 발표된 사이클마다 그 사이클의 복사본(마지막 것만 복사하면 함께 발표된 첫 두 사이클의 앞 것이 조각이 된다). MM-Fit: 조각 5(59세트), 세트 정확 0.76·±1 **0.92**, 부분 뺀 뒤도 0.76/0.92.

*횟수 합성(`PostureLive`)*: 부분(ROM 미달)으로 이미 뺀 회를 자세 위반으로 다시 빼지 않는다 — 두 번 빼면 화면 수가 실제보다 준다(폰 15:34 세트 6·8회는 부분이면서 벌림). 재생기 파리티: 팔별 경로의 회 유효는 `newlyPublishedValid`(앱 `RepUnitAccumulator.onCounterFrame` 과 같은 출처), `arms` 출력에 start_ms·손목 창 진폭·최소·orphan.

*폰 15:34 13회 정답 세트 재생(정면, 좌표 로그)*: 팔 사이클 왼 15·오른 14, 조각 1(왼 9회), 기각 1(30 s 오른 상완 스윙 66° — 반동 조각), **회 13**; 옆 벌림 차단 4/4(5~8회, Δ0.33~0.35), 팔꿈치 뜸 3/3(9~11회, 0.20~0.24), 부분 12·13회(손목 진폭 0.57~0.62 = 기준 0.80 의 0.71~0.78; 월드 비율은 0.87~1.0 으로 못 봄) + 아래 끝 경계 2회(6·8회 −0.73/−0.74, 이미 벌림으로 빠짐) → **화면 정확 4회 = 사용자 정답 4**. 정상 1~4회 오탐 0. 15:33 세트(교대→동시, 정답 없음): 13회, 조각 2, 부분 4, 뜸 1·상승 1(beta)·간격 5(beta).

*남은 것*: (1) 마지막 회의 아래 끝은 판정하지 못한다(창이 그 회의 신전 전에 닫힌다 — 다음 사이클 시작 또는 1 s 지연 판정으로 바꾸려면 발표 뒤 수정 사건이 필요) (2) 완료 화면 `정확 M / N`(`RepFormSummary`)은 자세만 세고 부분을 안 뺀다 — HUD 와 다르다 (3) 임계는 스튜디오 + 폰 1명 — 지정 오류 세트 3명 (4) MM-Fit 상완 스윙 기각 3/288(1 %) 은 오탐 후보 (5) 교대 컬에서 조각이 나면 여전히 어긋난다(다음 사이클이 겹치지 않으므로 순서로 짝짓는다). 유닛 396건. 2026-09-26 01:45 설치.

**§62c 후속 3 — 부분 회의 음성 사유 (2026-09-26 오전, 사용자 "덤벨 컬 자세 교정 대사가 없어 넣어")**: 09:59 정면 18회 세트에서 부분 5회(4·5·6·10·11회, 왼팔 손목 진폭 0.58~0.68 = 기준의 0.7~0.8)가 **말없이** 횟수에서 빠졌다 — ROM 사유 발화(`invalidCue`)가 4cda910c 리팩터에서 떨어져 나간 뒤 아무도 부르지 않았고, 자세 위반은 beta(상승·간격)뿐이라 말하지 않았다(원칙 #2). 숫자가 안 올라가는데 침묵하면 카운트가 죽은 줄 안다(§62b 와 같은 이유). 구현: 팔 사이클마다 미달 사유 `RomShort`(손목 창 최소 > −0.75 → BOTTOM '덜 폄', 아래 끝은 닿고 손목 진폭 부족 → TOP '덜 올림', 월드 진폭만 부족 → RANGE 불명), 회는 두 팔 중 BOTTOM > TOP > RANGE, `RepCounter.newlyPublishedShort`. `PostureLive` 는 COACH·서서·`romExcludesShort` 에서 부분 회가 빠진 프레임에 `RepSignal.shortCue(사유)` + "이 회는 세지 않았어요." 를 말한다(세트당 처음 `MAX_INVALID_CUES`=2번, 그 뒤는 "덜 올려서 세지 않았어요." 처럼 짧게 — 매 회 전체 문장은 잔소리). 같은 프레임의 자세 사건은 말하지 않는다(한 회에 한 사유). 문구: 덜 올림 "끝까지 올리지 않았어요. 덤벨을 어깨 앞까지 올려 주세요", 덜 폄 "팔을 끝까지 펴지 않았어요. 내릴 때 팔꿈치를 다 펴 주세요". 함께 고침: 부분 제외를 **COACH 만** — TRACK 은 세지 않는 기능 없이 전부 센다(사용자 결정 2026-09-25, 그동안 TRACK 에서도 빠지고 있었다). 유닛 397건.

**§62c 후속 4 — 덤벨 컬 '허리 굽힘' · 틀린 부위 붉게 · 반복 검사의 실시간 뷰 게이팅 (2026-09-26 오전, 사용자 "허리 굽히는 것도 잡아야 하고 틀린 부분 빨간색으로 시각화도 해")**

*허리 굽힘(ship, 2단)*: `torso_incl`(목−골반 vs 중력 up) 사이클 최대 − **그 반복의** 하강 직전 자세 중앙값(새 기준 `RepFormRef.REP_DELTA`). 코칭 +25°, 횟수 차단 +30°(COACH). 뷰 C·D. 근거: AIHub 덤벨 컬 MediaPipe 월드 '척추의 중립' 정상 677클립 — 정면 C +25° 초과 2.4 %·+30° 1.0 %(전 조건 정상 85클립 0 %), D 1.6 %·0.6 %, B 4.6 %·3.7 %(B 제외, 입장 조건 ≤ 2 % 미달); 재생 — 폰 정상 63회 최대 +10.9°, MM-Fit 111회 p95 +6.6°·+25° 초과 0. 위반 검출은 AIHub 라벨 기준 6~8 % 뿐이다(라벨의 대부분은 정면에서 안 보이는 미세 말림) — 큰 굽힘·젖힘만 잡는다. 정면에서는 앞 숙임과 뒤 젖힘을 못 가르므로 문구는 "허리가 굽어 상체가 기울었어요. 허리를 펴고 상체를 세운 채 팔만 움직이세요." 창 규칙 `척추의 중립[all/flexion]`(고개 각 대리)의 대체 표시를 이 검사로 옮겼다(`supersedes`).
*왜 세트 시작 기준이 아닌가*: 첫 상단 창에 덤벨을 집으려 숙인 프레임이 들어가 시작 기울기가 30~45° 가 됐다(폰 15:33·09:59 세트) — START_DELTA 로는 그 세트 전체가 −20~−47° 로 읽혀 영영 못 걸린다. AIHub 수치도 클립 안 이완 프레임 대비(max − e)라 반복 기준이 같은 정의다. 대가: 세트 내내 서서히 숙어지는 자세는 그 변화가 한 반복 안에서 25° 를 넘지 않으면 못 잡는다.

*틀린 부위 붉게*: 반복이 끝나면 그 회의 위반 검사 피처 → 관절(`RuleHighlight.forRepForm`)을 스켈레톤에 2.5 s 칠한다 — ship 위반은 붉게(창 규칙 강조와 같은 채널), beta 는 '참고' 색(원칙 #2), 유보는 칠하지 않는다. COACH·서서만(TRACK 은 모집단 기준 '틀림' 을 칠하지 않는다, §29). 다음 회가 깨끗하면 바로 지운다. 부위: 허리 → 어깨·골반, 팔꿈치 뜸 → 팔꿈치·손목, 옆 벌림·상승 → 어깨·팔꿈치, 앞 이탈 → 팔꿈치·몸통, 스쿼트 발끝 → 발·발목(2D 피처 매핑 추가).

*실시간 뷰 게이팅*: 반복 검사의 `views` 는 세트 결과(`ruleResult(viewLetter)`)에서만 쓰였고 실시간 평가기는 뷰를 몰랐다 — 사선에서 찍으면 정면 전용 '옆 벌림' 이 회를 빼고 붉게 칠할 수 있었다. `RepFormEvaluator.viewLetter`(PostureLive 가 프레임마다 `ViewEstimator` 글자를 넣는다, UNKNOWN 이면 null) 밖의 검사는 반복마다 유보("촬영 방향 · B"). 재생기는 설정하지 않는다(종전과 같다). 스쿼트 검사도 뷰 C 라 사선 스쿼트는 이제 실시간에서도 유보된다 — 세트 결과와 같은 규약. 유닛 400건. 2026-09-26 설치.

**§62c 후속 5 — 컬 몸통 검사를 스쿼트 '상체 숙임' 과 같은 정책으로 (2026-09-26, 사용자 "스쿼트와 똑같이 허리 숙임 피드백도 넣어")**: 후속 4 의 '허리 굽힘'(2단: +25° 코칭·+30° 차단)을 `repform|덤벨 컬|상체 숙임` 1단으로 바꿨다 — 위반(+25°, 그 반복 시작 대비) = 음성 + COACH 횟수 제외 + 붉은 강조, 문구 "상체가 많이 숙여졌어요. 가슴을 들고 몸통을 세운 채 팔만 움직이세요." 스쿼트와 다른 것은 임계(45° vs 25°)와 기준(세트 시작 vs 그 반복 시작, 덤벨 집기 오염)뿐. 대가: 정면 C 오탐 2.4 %(D 1.6 %, C·D 합 2.0 %)가 차단 입장 조건 ≤ 2 % 의 경계다 — 지정 오류 세트에서 정상 오탐이 나오면 30°(1.0 %) 로 올린다.

**§62c 후속 6 — 컬 '상체 숙임' 이 숙임을 못 잡던 원인과 수정: 세트 최소 기준 · 유지 자세 사건 · 반복 창 뷰 게이팅 (2026-09-26 낮, 사용자 "숙이는 거 인식 못 해 — 스쿼트와 비교해 원인부터")**

*원인(11:14 세트, 10:48 빌드, 2D 좌표로 동작 복원: 정면 숙인 채 컬 2~5회 · 사선 6~7회 · 정면 정상 8~11회 · 옆으로 돌아 숙인 채 컬 12~14회 · 옆으로 돌아 숙인 채 16초 유지 · 정면 정상 15~17회).* 측정은 됐다 — 몸통 기울기 정상 10~19°, 숙임 32~45°. 판정이 지웠다:
1. **기준** — 후속 4 가 기준을 '그 반복의 시작' 으로 바꿨다(세트 시작이 덤벨 집기에 오염되는 세트 때문). 숙인 채 컬을 하면 그 반복 시작부터 숙어 있어 차이가 0: 3·5·12·13·14회의 창 최대 36~45° 가 −1~+3° 로 읽혀 전부 통과(세트 시작 9.2° 대비였다면 +27~+36). '반복 중 몸을 흔드는 반동' 만 보는 검사가 됐었다. 스쿼트는 세트 시작 대비라 이 실패가 없다.
2. **반복 밖** — 숙인 채 멈춰 있으면(58~73 s) 반복이 끝나지 않아 반복 판정이 없다. 스쿼트에는 창 규칙 '척추의 중립'(`torso_incl__range` > 30.85°, ship, 전 뷰)이 반복과 무관하게 말하지만, 컬의 '척추의 중립' 은 고개 각(head_pitch) 대리라 beta 로 내려 두었다(후속 2).
3. **실시간 뷰 게이팅(후속 4)** — 세트 누적 뷰를 썼다. 옆으로 돌아선 구간(요 55~76°)에 누적이 19.8°(B)로 끌려가 그 뒤 정면 15~17회의 허리·팔꿈치 검사가 전부 "촬영 방향" 유보.
4. 옆(요 55~63°, 어깨폭/몸통 0.27~0.47 vs 정면 0.71)은 원래 판정하지 않는 뷰 — 12~14회·유지 구간은 규약상 유보가 맞다. 그때 먼 쪽 팔도 안 보였다.
(`tilt_deg` 57.6° 는 세트 마지막 프레임 = 폰을 집어 든 순간의 값. 세트 중 up 은 정상.)

*수정.* (1) 새 기준 `RepFormRef.SET_MIN_DELTA` = 세트에서 지금까지 가장 곧았던(작은) 반복 상단 중앙값(이 반복 포함) 대비 — 첫 상단이 덤벨 집기에 오염돼도 다음 반복의 곧은 상단이 기준을 끌어내리고, 숙인 채 반복해도 세트 기준과 견준다. 띠 20°(1단, 스쿼트와 같은 정책). (2) 유지 자세 사건 `RepFormEvaluator.liveEvent` — `RepFormCheck.liveHoldFrames`(컬 14프레임 ≈ 4 s): 마지막 사이클 뒤 최근 N 프레임이 모두 쉬는 자세(팔을 내림)이고 중앙값 − 기준이 20° 초과 60° 이하이면 "상체가 숙여져 있어요. …" 를 말하고 어깨·골반을 붉게 칠한다(COACH·서서, 이 프레임에 회가 끝나지 않았을 때만, 첫 반복 전에는 기준이 없어 말하지 않음, 쿨다운 15 s·반복 사건 직후 5 s). 60° 초과는 덤벨을 집거나 내려놓는 동작·측정 붕괴라 말하지 않는다. 로그 `rep_form.live`. 재생기도 같은 자리에서 부른다(파리티). (3) 반복 창 뷰 게이팅 — 평가기가 반복 창(상단 + 사이클)의 방향 피처 원형 평균으로 그 반복의 뷰를 정하고(`REP_VIEW_MIN_FRAMES` 4, 결과 벡터 0.7 미만·프레임 부족이면 모름 = 거르지 않음) 검사 뷰 밖이면 유보. `RepFormRep.view`, 로그 `reps[].view`. 세트 결과(`ruleResult`)는 반복 뷰가 하나라도 있으면 세트 뷰로 다시 거르지 않는다(방향이 섞인 세트를 통째로 유보하지 않게) — 반복 뷰가 없으면(방향 피처 없는 픽스처) 종전 규약. 실시간 `viewLetter`(후속 4)는 없앴다. 재생기도 같은 게이팅을 한다.

*검증(연속 세트 재생).* 폰 정상 75회: 최대 +23.3°, 20° 초과 1회(10:41 6회 — 2D 몸통이 짧아지지 않아 숙임 여부 불명), 유지 사건 0. MM-Fit 123회(C/D 로 판정된 반복): 20° 초과 1회(0.8 %, w13 7회 D +42.7°), 유지 사건 1건(w20 세트 끝 정리 동작 +33°; 3 s·상한 없음이면 5건 — 82~109° 측정 붕괴 3건 포함). 11:14 숙임 반복: 정면 3·4·5회 +30·+23·+27, 사선 6회 +23 검출, 2회(+18, 숙이기 시작) 통과, 옆 12~14회는 유보. 그 세트에서 옆으로 돌아선 반복의 '팔꿈치 옆 벌림' 헛위반(6·12~14회)도 반복 뷰 게이팅으로 사라졌다. 15:34 13회 정답 세트 판정 불변(5~8 벌림·9~11 뜸·12·13 부분 → 화면 정확 4). AIHub 세션(32클립·수십 분)을 한 세트로 본 상한은 정면 +20° 초과 13.9 %(GT 3D 4.0 %) — 긴 시간의 자세 이동이 섞인 과대 추정으로 보고 연속 세트 수치를 택했다. 지정 오류 세트에서 정상 오탐이 나오면 25° 로 올린다(그러면 정면 4회 +23 을 놓친다). 유닛 403건. 2026-09-26 11:50 설치.

**§62c 후속 7 — 사선에서 카메라 쪽 팔 하나로: 앞 이탈·몸에서 떨어짐 · 옆 뷰 앞뒤(beta) · 정면 벌림 기준 교체 (2026-09-26 오후, 사용자 "왼쪽 어깨로 하니 어깨너비 이상 벌려도 못 잡는다 … 앞이나 뒤에 가 있는 교정도")**

*원인(11:54 D 세트 — 왼어깨가 카메라 쪽, 반복 요 −21~−40°).* 정면 '옆 벌림'(ship)은 뷰 C 전용이라 사선 반복에서 유보됐다 — 값은 쟀다(정상 −0.05~+0.08, 벌린 10회 +0.22~+0.61). 월드 '팔꿈치 벌어짐'(beta)은 두 팔꿈치 간격이라 먼 팔꿈치(가시성 0.55~0.85)의 추정이 몸 쪽에 머물러 둔감(1.27~1.61, 띠 1.8) — 사용자 가설 그대로. '앞 이탈' 은 양팔 평균(`n == 2`)이라 먼 팔꿈치가 가려지면 유보.

*제1원리(연구 `docs/CURL_OBLIQUE_ELBOW_RESEARCH.md`, `research/camera_observability/B5_oblique_near_arm.py`).* 사선에서 카메라 쪽 팔꿈치의 화면 가로 = cos 요 × 옆 벌림 − sin 요 × 앞 이동 — 숫자 하나에 미지수 둘이라 '앞으로' 와 '몸에서 떨어짐(옆 또는 뒤)' 까지만 가를 수 있다. AIHub(뷰 D/B): 외전 1° 가 2D 앞 성분에 −0.5~−0.63° 로 새고, 월드 외전 변화는 GT 와 ρ 0.38~0.49(앞 기울기 변화는 0.84~0.86) — 옆/뒤는 월드로도 못 가른다. 가까운 팔 앞 성분 하나의 앞 내밀기 판별 AUC 0.937·0.928(양팔 평균 0.958).

*구현.*
- `Arm2d`: `elbow_fwd2d_near`(사선·옆, |요| 16.4~81.7°) — **가까운 쪽 몸통 선**(가까운 골반→가까운 어깨) 기준 앞쪽 거리 ÷ 몸통. 가운데 선 기준이면 가까운 어깨가 반 어깨폭만큼 비켜 있는 상수(cos 요 × 반 어깨폭)가 들어가 세트 안에서 각도가 바뀌면(11:54: 21° → 40°) 정상 반복이 +0.05~+0.10 앞으로 읽혔다. `elbow_lat2d_near`(사선) = 가까운 팔의 `elbow_lat2d`. 가까운 팔 = 요 부호(`nearSign`, B = 오른팔, D = 왼팔).
- 새 기준(`RepFormRef`): `FIRST_REPS_DELTA` — 그 뷰의 첫 3회 같은 창 통계 중앙값(기준 반복은 유보 — 원칙 #1), 뷰별로 따로(가까운 팔이 뷰마다 다른 팔). `SET_LOW_DELTA` — 그 뷰에서 지금까지(이 반복 포함) **두 번째로 작은** 값, 첫 2회는 기준만. 첫 3회 중앙값은 초반부터 벌리면 기준이 벌린 자세가 됐고(11:54: 벌림 10회 중 4회), 그냥 최솟값은 세트 첫 반복의 튄 값(MM-Fit w19·w17 첫 회 −0.21·−0.26)에 묶여 그 뒤 정상 반복이 떨어짐으로 읽혔다(사선 85회 중 4회). `SET_MIN_DELTA` 는 검사 id 별, 앞쪽 반구(C·B·D) 반복의 상단으로만 갱신한다(팔을 늘어뜨린 상단 가로 비는 정면·사선이 거의 같다 — 어깨 가로폭이 cos 요를 지운다).
- 검사: '팔꿈치 앞 이탈'(ship, B/D) → `elbow_fwd2d_near`·`FIRST_REPS_DELTA`, 코칭 +0.12(AIHub 본인 수축 대비 오탐 5.4·3.3 %·검출 88·80 %) / 차단 +0.20(2.1·1.5 %·62·49 %), 절대 하한 제거(가까운 팔은 뷰마다 상수가 달라 절대값이 옮겨지지 않는다). 새 ship '팔꿈치 몸에서 떨어짐'(B/D) — `elbow_lat2d_near`·`SET_LOW_DELTA`, 코칭 +0.25 / 차단 +0.40(AIHub 오탐 +0.25 7.4·4.6 %, +0.40 1.7·1.2 %, 단일 프레임 상한), 문구 "팔꿈치가 몸에서 떨어졌어요. 팔꿈치를 옆구리에 붙이세요." — 옆인지 뒤인지 단정하지 않는다(고치는 동작이 같다). 새 beta '팔꿈치 앞뒤(옆)'(SIDE_B/SIDE_D) — `elbow_fwd2d_near`·`FIRST_REPS_DELTA`, 앞 +0.12 "앞으로 나갔어요" / 뒤 −0.15 "뒤로 빠졌어요": 옆에서는 앞 성분이 거의 그대로(sin 요 0.7~1.0) 나오고 벌림이 거의 안 새 앞·뒤를 따로 말할 수 있다. 저장소 규약상 옆은 미검증 뷰라 beta(화면 '참고' — 음성·횟수 영향 없음), AIHub 에 옆 카메라가 없어 폰 지정 오류 세트로만 확정할 수 있다. 정면 '옆 벌림' 기준 `START_DELTA` → `SET_MIN_DELTA`(첫 상단이 덤벨 집기에 오염 — 09:59 0.50·15:33 0.46).
- `RepFormEvaluator.eventFor` 는 ship 사유를 먼저 고른다 — 검사 순서상 앞선 beta('팔꿈치 높이 상승' 등)가 회를 빼는 ship 사유를 가리면 빠진 회가 침묵으로 남는다.
- `RepFormRules`: 옆 전용 검사의 뷰 문구 "옆"(`viewDescOf`).

*재생(폰 8세트·MM-Fit 59세트).* 11:54 D 세트: '몸에서 떨어짐' 코칭 8/10(13·14·15·16·17·19·20·21회), 차단 4(14·15·16·19회), 초반 4·5회(+0.14·+0.18)는 기준이 서는 중이라 놓침; 정상 10~12·18회 −0.02~0.00; '앞 이탈' 오탐 0(벌림이 앞 축에서는 −로 읽힌다); 정면으로 돌아선 6·7회 '옆 벌림' 검출(앞쪽 반구 기준). MM-Fit 사선 반복: '몸에서 떨어짐' 85회·'앞 이탈' 50회 위반 0. 정면 '옆 벌림': 09:59 5·6·10회, 15:33 5·12·13회, 9/25 11:06 7·8회가 새로 걸린다(원값 0.33~0.60 — 같은 세트 다른 반복 0.21~0.26, 모집단 정상 p95 0.29) — 첫 상단 오염이 사라진 결과. MM-Fit 정면 판정 88회 코칭 5·차단 2(차단 2는 종전과 같고, 코칭 2건은 종전에 기준이 없어 판정 못 하던 반복). 15:34 13회 정답 세트 판정 불변. 유닛 411건.

*남은 것.* 폰 지정 오류 세트 — D·B 각각 정상·벌림·팔꿈치 앞·팔꿈치 뒤 10회(회 번호 기록), 옆 뷰 앞뒤 beta 확정. AIHub 수치를 앱 창 통계(수축 구간 중앙값·같은 기준)로 재측정. 세트 내내 벌리고 하면 '몸에서 떨어짐' 기준이 벌린 자세다(본인 기준의 한계).

**§62c 후속 8 — 사선 촬영 안내에 각도 · '0회 → 2회' 원인 · '처음부터 벌림' 방지 설계 (2026-09-26 12:49, 사용자 요청)**

*촬영 안내(구현).* `CapturePosition.LEFT_FRONT`/`RIGHT_FRONT` 문구에 각도를 넣었다 — "왼어깨가 휴대폰에 가까워지도록 45도쯤 비스듬히 서 주세요."(덤벨 컬·바벨 컬·사이드 레터럴 레이즈·랫풀 다운·바벨 런지; 오른쪽은 런지·사이드 런지·딥스·프런트 레이즈·스탠딩 니업). 준비 확인(`CaptureDirectionWindow`)은 MediaPipe 요 16.4~46.2°(D/B)에서 통과 — 45° 는 위쪽 끝이지만 MediaPipe 가 사선 각을 실제보다 조금 작게 읽어(AIHub D 카메라 39° → 33°) 대개 안에 든다. 안내가 계속 나오면 조금 덜 돌면 된다.

*'0회 → 2회'(원인만, 미수정).* 컬 팔별 카운터의 자식(새 코어)에 **시작 확정**(`RepStartConfirmation`, 설계 §4.3)이 붙어 있다 — 팔마다 첫 사이클을 두 번째 사이클이 확인할 때까지 보류했다가 둘을 한 프레임에 발표한다(준비 동작 한 번을 1회로 세지 않으려는 장치). 회 = 두 팔 짝이라 화면 수가 0 → 2 로 뛴다. 12:36~12:41 새 4세트 재생: 3세트는 첫 두 회가 같은 프레임(8.9·32.4·10.2 s), 1세트는 1 → 3. 사선에서는 **세트 중에도** 생긴다 — 가려졌던 먼 팔이 다시 보이면 그 팔의 '첫' 사이클이 다시 확정을 기다린다(12:38 세트: 오른팔이 16 s 에 나타나 4회가 3회보다 8.8 s 늦게 발표). 수정 후보: (a) 컬 팔 자식은 시작 확정 없이 바로 발표(컬 준비 동작 — 덤벨 집기 — 는 팔을 편 채라 팔꿈치각 사이클이 거의 없다; MM-Fit 세트 정확 재측정 필요) (b) 확정은 두되 보류 중인 첫 회를 화면에 먼저 보이고 버려지면 되돌림.

*'처음부터 벌림' 방지(설계만).* 본인 기준은 처음부터 틀리면 그 틀림이 '정상' 이 된다. AIHub '팔꿈치 고정' 충족 클립의 절대 분포: 정면 수축 가로 p95 0.33·p99 0.40(≥ 0.45 0.3 %), **이완(팔 늘어뜨림) 가로 p99 0.19**(≥ 0.30 0 %); 사선 가까운 팔 수축 p95 0.46·0.41, p99 0.62·0.59(D·B), 이완 p99 0.43·0.30. 정면 '옆 벌림' 은 기준이 이완 자세라 이완 때 팔이 옆구리에 있으면 첫 회부터 잡힌다. 제안: 정면 — 수축 원값 ≥ 0.45 면 기준과 무관하게 위반(OR, 오탐 ≈ 0.3 %), 이완 원값 ≥ 0.30 이면 시작 전에 "팔꿈치를 옆구리에 붙이고 시작하세요"(모집단에 없는 자세). 사선 — 본인 기준을 모집단 정상 띠 안으로 자른다(기준 = min(본인, 모집단 p90 ≈ 0.31~0.33)) → 처음부터 벌린 사람은 실효 임계가 코칭 0.58·차단 0.73 이 된다(정상 사람은 그대로); 기준 반복이 끝났을 때 본인 기준이 띠 밖이면 한 번 "처음부터 팔꿈치가 몸에서 떨어져 있어요" 를 말한다; 준비 안내에 "처음 두세 번은 팔꿈치를 옆구리에 붙이고 정확히" 를 넣는다. 사선의 한계: 모집단 폭이 넓어(사람·각도마다 앞 성분이 섞인다) 중간 크기의 '처음부터 벌림' 은 스타일과 못 가른다 — 정면에서 찍어야 잡힌다.

**§62c 후속 9 — '0회 → 2회' 수정 · '처음부터 벌림' 방지 · 사선 본인 기준 오염 (2026-09-26 오후, 사용자 "구현해")**

*0회 → 2회(후속 8 의 수정안 (a)).* 컬 팔 자식 카운터는 시작 확정 없이 사이클을 바로 낸다(`RepCounter(startConfirmation = false)`) — 팔마다 첫 사이클을 보류했다가 한 프레임에 둘을 내던 것이 원인이었다. 그대로 끄면 준비 동작 한 번이 1회가 돼 MM-Fit 세트 정확이 0.76 → 0.66 으로 떨어져, 확정을 **회 단위**로 옮겼다: 첫 회는 바로 보이고 말하되 `FIRST_REP_CONFIRM_MS`(8 s) 안에 둘째 회가 없으면 되돌린다(`newlyRetracted`, 로그 `reps.retracted` = 되돌린 시각). `PostureLive` 는 되돌림에서 회 기록·반복 평가기·표시 수를 비우고, 음성은 이미 말한 수를 다시 말하지 않는다(`deliveredReps` = 최대값). 가려진 팔의 가상 짝(`ARM_ABSENT_MS` 2.5 s)도 고쳤다 — 가상 짝은 **반대 팔을 마지막으로 본 뒤에 끝난** 사이클에만(또는 반대 팔이 세트에서 한 번도 사이클을 안 냈을 때) 붙인다. 기다리는 사이클 전부에 붙이면 11:54 세트가 21 → 25 로 넘쳤고, '한 번도 안 낸 팔' 만이면 12:36 세트의 101° 컬 하나를 놓쳤다. 재생: MM-Fit 59세트 세트 정확 0.76 → **0.80**, ±1 0.92 → **0.98**, 같은 프레임 두 회 발표 MM-Fit·폰 12세트 모두 **0**. 폰: 11:14 17 → 18, 11:54 21 → 22, 12:41 13 → 14(보류·고아로 사라지던 실제 컬), 12:38 13 그대로(첫 컬이 더는 고아가 아니다), 15:33 은 떨어져 있던 이른 사이클 하나를 되돌리고 횟수 불변.

*'처음부터 벌림' 방지(후속 8 설계 구현).* `RepFormCheck` 에 셋: `absHi`(원값이 이 이상이면 기준과 무관하게 위반·차단 — 기준을 모으는 반복에도), `refCap`(본인 기준을 모집단 정상 띠 끝에서 자른다 — 띠 안의 사람은 그대로), `refNotice`·`noticeText`(본인 기준이 모집단에 거의 없는 값이면 세트에서 한 번 알림, `RepFormEvaluator.takeNotice`, 로그 `rep_form.live` 에 "검사 id#기준"). 정면 '옆 벌림': 0.45 / 0.19 / 0.30(AIHub '팔꿈치 고정' 충족 677클립: 수축 ≥ 0.45 0.3 %, 이완 p99 0.19·≥ 0.30 0 %) "처음부터 팔꿈치가 옆으로 벌어져 있어요. 팔을 내렸을 때 팔꿈치를 옆구리에 붙이고 해 주세요." 사선 '몸에서 떨어짐': 0.72 / 0.32 / 0.60(가까운 팔 수축 p90 0.33·0.31, p99 0.62·0.59) "처음부터 팔꿈치가 몸에서 떨어져 있어요. 옆구리에 붙이고 해 주세요." 알림은 셋째 반복부터(`NOTICE_MIN_REP` — 09:59 세트는 첫 상단이 덤벨 집기에 오염돼 0.50 이라 첫 회 기준으로 헛알림이 났다), 세트에서 한 번(12:41 세트에서 정면·사선 두 문장이 같은 벌림을 거듭 말했다). 준비 안내(`ExerciseProfile.referenceHint`)에 "처음 두세 번은 팔꿈치를 옆구리에 붙이고 정확하게 해 주세요. 그 자세를 기준으로 봐요." — 본인 기준의 전제를 시작 전에 밝힌다. 재생: 12:41 세트(가까운 팔 원값 0.63~0.80) 1·2·10회 차단·11회 코칭·알림 1; 12:36 정면 7회 벌림 코칭 → 차단; MM-Fit 정면 +2(w08 6회 코칭, w12 1회 차단), 알림 0.

*사선 본인 기준 오염(재생 중 발견 — 사용자가 시험한 12:31 빌드에도 있었다).* 기기 로그: 12:36 세트 정확 **15/30**('몸에서 떨어짐' 13회, 기준 −0.27), 12:39 세트 **6/16**(8회 차단, 기준 −1.02) — 정상 반복(원값 0.12~0.29)이 줄줄이 '떨어짐' 으로 빠졌다. 원인: 사선 가까운 팔의 화면 가로 = cos 요 × 바깥 − sin 요 × 앞이라 앞 성분(`elbow_fwd2d_near`)과 가로(`elbow_lat2d_near`)는 **한 숫자를 둘로 읽은 것**이고, 한 축이 틀린 반복은 다른 축에 반대 부호로 샌다. 앞으로 나간 반복은 가로가 음수: 12:36 10~12회(−0.20·−0.27·−0.32, 같은 반복 앞 이탈 +0.30·+0.34·+0.36), 12:39 5·6회(−1.02·−1.13, 앞 이탈 +0.59·+0.64) — 낮은 값이 **두 번** 나오면 '두 번째로 작은 수축'(`SET_LOW_DELTA`)도 끌려간다(폰 사선 반복의 음수 원값은 전부 같은 반복의 앞 이탈 위반이었다). 거꾸로 벌린 반복은 앞 성분이 뒤로 읽힌다: 12:41 벌린 첫 두 회(가로 0.80·0.77, 앞 −0.44·−0.45)가 '앞 이탈' 첫 3회 중앙값 기준을 −0.44 로 끌어(본인 정상 −0.15) 벌린 11회가 "팔꿈치가 앞으로 나갔어요" 로 읽혔다(검사 순서상 앞 이탈 문장이 먼저) — 정상 반복을 이어 했다면 +0.29 로 차단될 자리였다. 수정: 본인 기준 모음(FIRST_REPS·SET_LOW)은 반복을 **다 판정한 뒤** 넣고(`commitReference`), 넣지 않는 두 조건을 둔다 — `refExcludedBy`(같은 반복에서 다른 축 위반: 앞 이탈 ↔ 몸에서 떨어짐) · `refFloor`(떨어짐 −0.15 = AIHub 사선 '팔꿈치 고정' 정상 수축 p5~p10: D −0.25/−0.19, B −0.17/−0.12 — 앞 이탈이 기준을 모으는 첫 3회의 누설과 세트 첫 회의 튐을 막는다). 그 반복의 판정은 그대로 한다(한 축이 틀린 반복의 다른 축은 반대쪽으로 읽혀 위반이 안 난다; SET_LOW 는 이 반복을 넣어 본 모음으로 판정해도 결과가 같다 — 두 번째로 작은 값 이하면 자기 판정이 0 이하). 재생: 12:36 정확 15 → **24/30**(떨어짐 22·24회 차단 — 원값 1.05·0.62, 25·26회 코칭 0.54·0.55; 앞 이탈 10~12회 차단은 그대로), 12:39 6 → **14/16**(16회 코칭 0.49), 12:41 11회 '앞으로' 오판 → '떨어짐'(앞 이탈은 깨끗한 기준 반복이 모자라 세트 내내 유보 — 정직한 결과); MM-Fit 사선 떨어짐 위반 2 → **0**(w17 set25 3·4회 — 첫 두 회 −0.16·−0.33 이 기준이었다; MM-Fit 사선 원값 p5 0.05·중앙값 0.21, −0.15 아래 4건은 전부 세트 첫 1~2회), 앞 이탈 위반 0; 11:54 검출·12:41 '처음부터 벌림'·다른 세트 판정·횟수 불변. 유닛 416건.

*남은 것.* 첫 회 되돌림은 화면 수가 1 → 0 으로 돌아가는 드문 경우를 만든다(15:33 1회). 가상 짝 규칙은 재생 12+59세트로 고른 경험 규칙 — 먼 팔이 오래 가려지는 B/D 지정 세트로 확인한다. 절대 상한(0.72) 밑으로 벌린 처음 몇 회는 '앞 이탈' 기준에 여전히 들어간다(떨어짐은 첫 두 회를 기준만 모으고, 셋째는 벌린 기준과 견준다) — 그러면 정상 반복이 '앞으로' 로 읽힐 수 있다. '처음부터 벌림' 의 중간 크기(사선 원값 0.33~0.60)는 스타일과 못 가른다 — 정면에서 찍어야 잡힌다.

**§62c 후속 10 — "될 때도 안 될 때도 있다": 판정이 귀에 닿는 경로 넷을 고침 (2026-09-26 오후, 사용자 "규칙성 없이 적용되고 신뢰성이 없어 … 임의로 설정해서 구현")**

*진단(오늘 컬 10세트 178회, 기기 로그 + 음성 로그).* 자세 오류로 횟수에서 뺀 57회 중 사유가 온전히 들린 것은 약 14회(25 %). (1) 19회는 가동 범위 문장("덜 폈어요")이 대신 나갔다 — 팔꿈치를 앞으로 내면 손목이 덜 내려가 ROM 도 걸리는데 한 회에 사유 하나 규칙에서 ROM 이 먼저였다. (2) 16회는 같은 검사 12 s 쿨다운 안이라 **침묵**(12:36 세트 14~19회 연속 위반 중 소리는 둘). (3) 요청된 코칭 문장 72개 중 26개(36 %)가 **다음 코칭 문장에 잘렸다** — 문장 5~7 s, 반복 2~3 s, 코칭 문장이 QUEUE_FLUSH. (4) 반복마다 뷰를 다시 정해 검사 집합이 회마다 바뀌었다 — 요(월드 z)가 한 세트 안에서 −19~−45°, 한 회 안에서 −74~−23° 까지 흔들려 178회 중 SIDE_D 11회(전부 유보)·C 30회(정면 검사, 임계 +0.15)·D 89회(사선 검사, +0.25). 45° 안내가 D 띠 끝(46.2°) 위다. (5) '앞 이탈' 은 59/178회가 "값 부족" 유보 — 수축 창 1~3프레임 중 가까운 팔 피처가 있는 프레임이 하나뿐이면 중앙값(값 2개 필요)을 못 냈다. (6) '등 말림' 검사는 없다 — '상체 숙임'(목–골반 선 대 중력, 세트 최소 상단 대비 +20°)은 골반 접힘 검출기라 등만 말면 +15~+19° 로 경계에 걸린다(13:49 세트 14회 +21 위반, 15회 +19 통과; 12:36 세트 20·21회 +16·+17 통과). 00:32 의 "처음부터 등이 말려 있어요" 는 옛 창 규칙 '척추의 중립' = **고개 각도(head_pitch) < −19.9°** 였고(오늘 세트는 내내 −23~−36° 라 전부 위반), 오전에 beta 로 내려 침묵 중이다.

*구현(전부 임의값 — 등 말림·사선 벌림 폰 지정 세트로 확정 전).*
- **음성 경로**: `SpeechCoach.speakLatest` — 코칭 문장은 끊지도(QUEUE_FLUSH) 줄 세우지도(QUEUE_ADD) 않는다. 말하는 중이면 보류분 **하나**만 쥐고(나중 것이 덮음) 끝나면 말한다, `HELD_TTL_MS` 4 s 넘게 묵으면 버린다. flush 발화(촬영 안내·세트 경계)는 보류분도 지운다. 반복 사건·ROM 사유·유지 자세·처음부터 알림이 전부 이 경로. 숫자는 종전대로 QUEUE_ADD.
- **우선순위**: 한 회에 사유 하나는 그대로, 순서를 **자세(ship·COACH) > ROM** 으로.
- **쿨다운 안의 위반은 짧은 단서**(`RepFormEvent.brief`, `RepFormCheck.cue`): 문장은 검사당 12 s 에 한 번, 그 사이 위반 회는 "팔꿈치 떨어짐, 이 회 제외." / 코칭 단계는 "상체 숙임." 게이트(COACH)에서만 — TRACK 은 종전대로 없음. 단서 기본값 "부위 꼬리표"("발 너비 넓음").
- **세트 뷰 잠금**(`RepFormEvaluator.lockView`): 최근 `VIEW_WINDOW_REPS` 5회 반복 요의 **중앙값**으로 뷰를 정하고(3회부터), 반복은 자기 추정이 그것과 `VIEW_LOCK_TOLERANCE_DEG` 35° 넘게 다를 때만 자기 뷰(반대쪽 사선·옆으로 진짜 돌아섬). 처음 3회 고정은 9/25 11:06 세트(사선 → 정면)의 정면 벌림 2회를 잃었고, 원형 평균은 −30·+30 이 섞이면 0(정면)이 된다. `RepFormRep.view` = 거른 뷰, `viewRaw` = 자기 추정(로그 `view_raw`, 다를 때만). 옆(SIDE)으로 걸러진 회는 `turnedTooFar` — 15 s 에 한 번 "옆으로 너무 돌아서 이 회는 자세를 못 봤어요. 조금 덜 돌아 주세요."(원칙 #5).
- **가까운 팔 피처 띠**(`Arm2d.NEAR_MIN_DEG` 10°·`NEAR_LAT_MAX_DEG` 60°): 사선으로 잠긴 세트에서 살짝 정면(10~16°)·옆 초입(46~60°)으로 흔들린 프레임도 잰다. **값 하나로 판정**(`need = 1`).
- **컬 '상체 숙임' 2단**: 코칭 `LEAN_COACH_HI` +15°(말만), 차단 `LEAN_HI` +20°(종전). 문구 "등이 말리거나 상체가 숙여졌어요. 가슴을 들고 등을 편 채 팔만 움직이세요." — 이 선 하나로는 척추 말림과 골반 접힘을 못 가른다. 후보 신호: 13:49 세트 15회(등 말림 추정)는 `sh_over_hip_fwd` +0.26 vs 나머지 +0.02~+0.10 — 폰 지정 세트 뒤 별도 검사로.
- 다른 세션이 `PostureLive` 에 넣어 둔 미완성 참조(`ObliqueStanceCue`·`stanceLine`, 존재하지 않는 클래스 — 컴파일 불가)는 걷어 냈다. 위 '옆으로 너무 돌아섬' 안내가 그 자리다.

*재생.* MM-Fit 59세트: 세트 정확 0.80·±1 0.98 불변, 사선 앞 이탈·떨어짐 위반 0 유지, 정면 벌림 차단 +1(w13 set23 7회 원값 0.49 — 절대 상한 위), 상체 숙임 코칭 단계 1/117(0.9 %). 9/25 폰 3세트: 정면 벌림 9 → 9(11:06 세트 잠금이 사선 → 정면을 따라감), 정확 33 → 34. 오늘 10세트 재생(기기 기록 → 재생): 12:36 정확 15 → 25/30, 12:39 6 → 14/16(둘은 후속 9 기준 오염 수정 몫), 13:49 16회 전부 D 로 일관(10·11회 "값 부족" 유보 사라짐), 15회 +19° 코칭 단계. '앞 이탈' 값 부족 유보 59 → 27. 유닛 419건.

*남은 것.* 폰 지정 오류 세트(D·C 각각 정상·벌림·앞·등 말림 10회씩) 없이는 위 임계가 전부 임의값이다. 등 말림 전용 검사(`sh_over_hip_fwd`·`torso_pitch`)는 REHAB·MM-Fit 정상 반복 오탐률을 잰 뒤 beta 로. 사선 '몸에서 떨어짐' 은 정면 '옆 벌림' 보다 둔하다(+0.25 vs +0.15) — 중간 크기 벌림은 정면에서 찍어야 잡힌다. 음성이 밀리면 숫자가 늦게 나온다(QUEUE_ADD).

*14:46 지정 세트(정상 5 → 옆 벌림 4 → 앞으로 3 → 등 말림 3 → 정상 3, 요 −40°) 로 드러난 셋 — 같은 날 오후 고침.* 기기(14:40 빌드) 결과: 1~4회 SIDE_D 유보(요 −52·−47 로 시작, 45° 안내가 세트 규칙 D 띠 끝 46.2° 위), 벌림 첫 회·앞으로 첫 회는 "기준 반복" 유보(1~4회가 SIDE_D 모음에 들어가 D 모음이 비어 있었다) 뒤 ROM 문장("팔을 끝까지 펴지 않았어요" — 팔꿈치를 벌리거나 앞으로 내면 손목 최저점이 −0.75 위로 올라와 보조 ROM 이 같이 걸린다), 벌림 7~9회는 기준이 벌린 값에 오염돼 코칭 단계(+0.29~+0.33)만, 등 말림 3회는 차단(+23.7~+28.9), **정상 16회가 +23.6° '숙임' 으로 차단** — 등 말림 뒤 짝 없이 버려진 팔 조각(더 깊은 65°)이 다음 회 창에 접혀 들어가 창의 최소가 조각에 잡혔다(창 = 직전 회 끝 이후 전부). 고침: (1) 반복 창의 앞 경계 = **사이클 시작 1.5 s 전**(`onCycle(startMs)`, `RepRecord.cycleStartMs`, 짝의 시작 = 먼저 시작한 팔 — 레거시 경로는 종전대로), 잘랐으면 이월 프레임도 버림; (2) 반복 검사의 사선 띠를 **55°** 까지(`REP_OBLIQUE_MAX_DEG`, `classFor`); (3) 가로 검사('옆 벌림'·'몸에서 떨어짐')는 **차단 단계 숙임** 회에서 측정 무효(`invalidatedBy`) — 어깨가 말리면 어깨 가로폭 투영이 줄어 가까운 팔 가로가 0.39~0.49 로 부풀었다(월드 바깥은 정상). 무효화는 차단 단계 위반만(코칭 단계 +15~20° 는 아님). 재생(최종): 14:46 세트 17회가 **라벨대로** — 정상 7회 정확, 벌림 4회 차단(+0.51~+0.59, 기준 0.06), 앞으로 3회 차단(+0.34~+0.37), 등 말림 3회 차단(가로는 무효); 세지 않은 정상 1회(조각 — 오른팔 사이클이 안 잡힘)는 카운터 몫으로 남는다. MM-Fit: 횟수 불변, 사선 위반 0 유지, 정면 벌림 차단 3, 상체 숙임 차단 2/86 판정(w18 set21 5회 raw 26.4 vs 6.1 — 창 정리로 오염된 기준 84.9 가 걷힌 결과, 진짜 숙임인지 잡음인지 모름; MM-Fit 는 중력 up 이 없는 프레임이 있어 '기준 없음' 유보가 36 → 62), 코칭 1. 9/25 폰: 정면 벌림 +1(15:33 10회 원값 0.45). 유닛 422건.

**§63 — 런지 재설계(연구 `docs/LUNGE_RESEARCH.md`) 0단계: 분석기 종료 크래시 · 세트 로그 기록 (2026-09-26 저녁)**

*원인(확정).* 폰 `logcat -b crash`: 2026-09-26 13:57:10.170 · 09-25 20:06:31 SIGSEGV(null, fault 0x198) — 분석 스레드(pool-N-thread)의 `PacketCreator.nativeCreateProto`. 자동 진행으로 화면이 치워질 때 `onDispose` 의 `analyzer.close()`(메인 스레드)가 분석 실행기에서 도는 `detectForVideo` 의 네이티브 그래프를 해제했다(`close` 와 `detect` 사이 동기화 없음, `executor.shutdown()` 은 기다리지 않는다). 프로세스가 죽으면서 기다리지 않는 `Thread { append }` 의 세트 로그도 사라졌다 — 사용자가 설명한 런지 지정 세트(13:56)가 이렇게 없어졌다.

*수정.* `PostureAnalyzer`: 추론(`analyze`·`analyzeBitmap`)과 `close` 가 같은 락(this)을 잡고, 닫힌 뒤에는 모델을 다시 만들지 않고(`closed`) 빈 샘플을 돌려준다 — close 는 진행 중인 추론(≈ 100 ms)을 기다린다. 세트 로그는 앱 수명의 단일 기록 스레드(`SetLogStore.writer`)가 순서대로 쓰고, 실패는 feedback 로그 `set_log_failed` 로 남긴다. 새 세트가 시작되면 `finalized` 를 푼다. 남은 것(설계 §4-1): 세트 중간 저널(`inprogress/<set_id>.jsonl`)·재시작 복구, ✕ → 취소 뒤 `finalized`.

**§63 후속 1 — 런지 쪽별 카운트 · 걸음 검사 (2026-09-26 저녁, 사용자 결정 "좌우 기준 횟수는 계속 키고 차감식으로 하지 말고 왼쪽 오른쪽 따로 카운트한 다음 한쪽 카운트가 다 사라지면 안내해서 다른 쪽 하게 유도 · 방향은 45도 유지 · 무릎 발끝은 무릎 쏠림으로 · 80도 부근까지 굽혀지지 않으면 교정 멘트와 시각 표시, 횟수 취소 · 5번(스플릿 스쿼트 구분)은 하지 말고")**

*쪽별 카운트(`LungeSides.kt` — 앱·재생기 공용).* 런지(앱 "런지" = AIHub 스텝 포워드)만 `RepUnit.SIDE_EACH`. 걸음(카운터 사이클)마다 앞다리 쪽을 정해(`Lunge2d.stepSide` — 바닥 프레임 3D 발목 전후 `lunge_fwd_d` 중앙값 |≥ 0.25| 의 부호, 2D 무릎 높이가 반대로 0.10 이상이거나 얼굴 방향 점검 `lunge_names_ok` 가 0 이면 모름, 정면은 모름) `SideStepCounter` 가 쪽마다 목표(세트 목표 회수)까지 센다. 화면 큰 글자는 쪽마다 **남은 수**("왼 7 · 오 ✓"), 음성은 걸음마다 "왼쪽 7개 남음"/"왼쪽 끝"(쪽 모름은 짧은 톤), 한쪽을 다 채운 순간 "이제 오른쪽 다리를 앞으로 내디뎌 주세요."(쪽마다 한 번), 다 채운 쪽으로 더 디디면 세지 않고 "왼쪽은 다 했어요. 오른쪽 다리를 앞으로 해 주세요."(8 s 에 한 번). 목표 진행(쌍) = min(왼, 오른) — 차감이 아니라 걸음마다 +0/+1, 두 쪽이 다 목표에 닿아야 세트가 끝난다. 쪽을 모르는 걸음은 걸음 흐름에 채우고(후속 2) '좌우 미확인 n' 으로 밝힌다. 풀이 둘: TRACK(판별 통과 전부)·COACH(ship 차단 위반 제외) — 세트 중 모드를 바꿔도 맞는다. 종전 `repIncorrect` 차감(쌍 − 사이클)은 런지에서 쓰지 않는다. 로그 `reps.unit = side_each`, `reps.sides{target,track{L,R,U,extra,blocked,pairs},coach{…}}`, `reps.completed` = TRACK 쌍, `rep_form.reps[].side/stand_yaw/not_step`. 바벨·사이드·크로스 런지는 종전 SIDE_PAIR(앞다리 기하가 설계되지 않았다).

*걸음 기하(`Lunge2d.kt`, `ViewEstimator` 어깨 요).* `lunge_fwd_d`(분모 = 엉덩이→무릎 + 무릎→발목 — 현은 앞무릎이 굽을수록 준다), `lunge_front_knee`, `lunge_front_shin`(앞 정강이가 중력 up 에서 앞으로 기운 각, 시상면), `lunge_back_knee_h`, `lunge_foot_lift`, `lunge_kh2d`, `lunge_names_ok`, `sh_level2d`. 반복 뷰·방향 안내는 **어깨선만의 요**(`view_cos_sh/view_sin_sh`) — 골반선은 앞다리에 따라 ±15~35° 흔들린다.

*걸음 검사(`RepFormSpecs.lunge()`, repform_v0.3).*
- **앞무릎 깊이**(ship, 차단): 걸음 바닥 두 무릎 중 더 굽은 쪽(`knee_minside`) 최솟값 > **90°** — 사용자 "80도 부근" + MediaPipe 3D 무릎각 오차(MAE ~10°). 그대로 80 이면 정상 걸음 71 %·사용자 본인의 깊은 걸음(83.6°)까지 빠진다. 문구 "덜 내려갔어요. 앞무릎이 80도 가까이 굽도록 뒷무릎을 바닥 가까이 내려 주세요." + 앞다리 강조(`highlight = knee_ang_{front}`). 정면(C)은 판정하지 않는다(MM-Fit 정면 25 % 초과, 3D 오차 ~14°). **원칙 #7 입장 조건의 사용자 결정 예외**: MM-Fit 정상 걸음 5.5 %, REHAB '올바름'(치료용 얕은 런지) 29 % — TRACK 은 빼지 않는다.
- **상체 숙임**(ship, 코칭 +20°·차단 +25°): 바닥 `torso_pitch` 중앙값 − 두 번째로 곧았던 걸음(한 모음 — `refByView = false`, 뷰가 A→SIDE_B 로 바뀐 순간 숙인 걸음이 새 모음의 기준이 됐었다). 허리 말림 자체는 못 본다(척추 점 없음). 정면 제외.
- **무릎 쏠림**(ship, **코칭 전용** `gates = false` — 음성은 2걸음 연속): `lunge_front_shin` 최댓값 > 45°. 무릎이 발끝을 넘는 것 자체는 오류가 아니라(Fry 2003) '쏠림' 으로 말하고 횟수는 빼지 않는다.
- **어깨 기울기**(**beta** — 후속 2, 화면 '참고'만): `sh_level2d` 중앙값 − 같은 앞다리의 처음 2걸음(`refBySide`, `refN = 2`), |Δ| > 0.12. B/D 만(옆에서는 먼 어깨가 가리고, 정면은 앞다리 쪽을 몰라 늘 유보). 숙임 차단 걸음은 무효·기준 제외.
- 창 규칙 '상체의 과조한 숙임/젖힘 여부'(뒤로 젖힘 검출기, MP 오탐 16.2 %)·'척추의 중립[lateral]'(흔들림 std, 15.8 %)은 `supersedes` 로 beta(사유는 `supersedeNotes`).
- **걸음 판별**(`RepFormRep.notStep`): 바닥에서 발이 모여 있고(|fwd_d| < 0.25) 몸통을 45° 넘게 숙인 사이클은 걸음이 아니다 — 판정·쪽별 카운트에서 뺀다. MM-Fit 세트 앞뒤 몸 굽히기 25사이클이 깊이 오탐의 37 %·D 뷰 숙임 오탐의 전부였다.

*놓친 얕은 걸음(`RepFormEvaluator.missedDipEvent`, `RepCounter.midCycle`).* 레거시 카운터는 무릎 평균이 준비 자세에서 35° 넘게 내려가야 걸음으로 잡아 얕은 걸음은 사이클이 되지 않았다(저장 세트 얕은 7걸음 중 5걸음). 마지막 사이클 뒤 프레임에서 '선 자세 → 15° 이상 굽힘(앞뒤로 벌림 프레임 ≥ 2, 발 들림 ≤ 0.6) → 선 자세 복귀' 를 찾아 깊이 띠를 넘으면 COACH 에서 "덜 내려갔어요… 이 걸음은 세지 않았어요." + 앞다리 강조. 카운터가 걸음 도중(레거시 `moving`)이면 기다린다. 모드와 무관하게 부른다(창을 바꾸므로). 재생기도 같은 자리.

*그 밖.* 세트 중 방향 안내 — 선 자세 어깨 요 55~120° 인 걸음이 3번 이어지면 세트에서 한 번 "옆으로 많이 돌아섰어요. 휴대폰 쪽으로 조금 돌아 45도쯤 비스듬히 서 주세요."(두 모드, `turnReminderSteps`). 컬의 '이 회는 자세를 못 봤어요' 는 런지에서 쓰지 않는다(옆에서도 숙임·깊이는 본다). `OnsetKind.CURRENT` — 초반 창을 판정하지 못한 위반은 '처음부터' 없이 말한다(전 종목). 목표 도달 자동 진행은 마지막 발화를 최대 2 s 기다린다(`AdvanceHold`). 일시정지·카메라 전환 때 걸음 창을 버린다(`discardWindow`).

*재생.* 폰 저장 세트(첫 시도, 13걸음 중 카운터 10): 쪽 RRRR LLLLL R(정답과 같음), 숙인 4걸음 차단 +28~+37°·나머지 ≤ +5.5°, 깊이 차단 = 센 얕은 2걸음(121.7·95.6°)·깊은 8걸음 0, 놓친 얕은 걸음 4개 검출(102~133°, 오른발 앞), 무릎 쏠림 0(최대 34.5°), 어깨 기울기 유보(옆), 방향 안내 3걸음째. MM-Fit 런지 62세트 정상 485걸음: 상체 숙임 0.3 %, 무릎 쏠림 2.6 %(2걸음 연속 음성 0/62 세트), 어깨 기울기 0 %, 깊이 5.5 %(사용자 결정 예외). REHAB 확신 걸음 쪽 일치 96.7 %(153/207, 정면 카메라는 모름). 유닛 436건.

*남은 것.* 레거시 카운터 걸음 재현율(MM-Fit 433/624) — 쪽별 수에 그대로 보인다(새 코어 전환은 Gate A 뒤). 옆(SIDE)·후방 사선(A/E) 임계는 폰 지정 오류 세트로 확정. 좌우 이름은 얼굴 방향 점검으로만 지킨다 — 안내형 세트로 확인.

**§63 후속 2 — 런지 구현 검토 반영 (2026-09-26 밤, 다중 에이전트 검토 확인 40건 · 중복 제외 약 25건)**

*쪽별 카운트.*
- **목표 진행을 절대값으로 맞춘다.** 쪽마다 목표에서 멈추니 쌍이 목표를 넘지 않는다. 증가분(`onRepDetected`)으로 보내면 일시정지 중 버려진 +1 이 다음 걸음으로 돌아오지 않았다. 그래서 두 쪽이 다 ✓ 인데 세트가 넘어가지 않았다. 이제 `onRepetitions(쌍)` 로 보낸다. 일시정지 중에 오른 쌍은 풀린 뒤에 보내고, 직접 고친 수가 더 크면 두다.
- **목표를 채운 쪽 판정을 자세 차단보다 먼저 한다.** 다 채운 쪽의 얕은 걸음을 "더 깊이 … 이 걸음은 세지 않았어요" 로 말하면 끝난 다리를 고치라는 말이 된다. 그런 걸음은 '더 디딘 걸음'(extra)이고, '자세로 뺀 걸음' 에 넣지 않는다. 그 걸음의 자세 사유는 말하지 않는다.
- **더 디딘 걸음은 `switchTo` 를 내지 않는다.** 이전에는 안내 분기가 "이제 오른쪽…" 을 걸음마다 대기열에 쌓았고, 8 s 제한이 있는 "…은 다 했어요" 분기에는 도달할 수 없었다.
- **쪽을 모르는 걸음은 걸음 흐름에 채운다.** 최근 확신 두 걸음의 쪽에 따라 정한다.

  | 최근 확신 두 걸음 | 모르는 걸음을 채우는 쪽 |
  |---|---|
  | 같은 쪽(몰아서 하는 중 — 안내한 방식) | 직전에 채운 쪽 |
  | 번갈음 | 직전의 반대쪽 |
  | 확신 걸음 없음 | 적은 쪽 |
  | 채울 쪽이 이미 목표 | 반대쪽 |

  '적은 쪽' 규칙만 쓰면 왼쪽을 몰아서 하는 동안 모르는 걸음이 전부 오른쪽에 들어갔다. 그러면 오른쪽 블록이 한 걸음 일찍 끝났다.
- **모르는 걸음에서 나온 반대쪽 안내는 추정이라 화면에만 둔다**(원칙 #6).
- **HUD 는 첫 걸음 전부터 '왼 10 · 오 10' / '좌우 각 10회' 를 보인다.** 이전에는 '0회 / 목표 10회' 로 보여 합계 10걸음으로 읽혔다.
- **'반쪽 대기' 강조색을 끈다.** 누적기의 두 걸음 짝 기준이라 홀수 걸음마다 켜졌다.

*발화·화면.*
- **한 걸음에서 할 말을 한 문장으로 이어 `speakLatest` 한 번에 보낸다.** 순서는 사유 → 쪽 안내 → 방향 안내 → 출발 알림이다. 이전에는 대기열에 붙인 쪽 안내가 보류된 사유보다 먼저 나왔다. 그래서 사유가 반대쪽 다리 얘기처럼 들렸고, 뒤의 `speakLatest` 가 앞의 보류분을 밀어냈다. 이것은 전 종목에 적용된다(컬 사유 + 출발 알림도 같은 문장이 된다).
- **TRACK 의 비교 문장은 런지에서 끊지 않는다**(`speakLatest`). flush 가 같은 프레임의 수·쪽 안내를 잘랐다.
- **쪽·방향 안내는 TRACK 화면에도 보인다**(`guideNote`, 6 s). TRACK 의 화면 문구는 비교 문장이라 formNote 가 안 보였다.
- **검증 모드(§61)는 앱이 센 수를 숨긴다.** HUD 부제의 'N회 완료', 차례·미확인·자세로 뺀 걸음 표기, 쪽 안내를 모두 숨기고 단위만 보인다.
- **준비 안내를 "따로 셉니다" 로 바꾼다.** "따로 세요" 는 명령으로 들렸다.
- **정면(C) 방향 안내를 추가한다.** 선 자세 어깨 요 ≤ 16.4° 인 걸음이 3번 이어지면 세트에서 한 번 "정면으로 서면 깊이와 좌우를 볼 수 없어요. 휴대폰에서 45도쯤 비스듬히 서 주세요." 라고 한다(`TurnReminder.FRONT`). 정면에서는 걸음 검사가 전부 유보되고 쪽도 모른다. 그런데 이전에는 삐 소리만 나서 "봤는데 괜찮았다" 로 읽혔다(원칙 #5).

*걸음 검사.*
- **어깨 기울기는 beta 로 내리고 뷰를 B/D 로 좁힌다.**
  - 모집단 정상 초과는 0 % 다(MM-Fit B·D 0/122, REHAB 0/14).
  - 그러나 폰 검출률이 없다. 사용자 세트는 전부 옆이라 유보됐다.
  - 설계(docs/LUNGE_RESEARCH.md §6)도 "데이터가 분명해질 때까지 beta" 였다.
  - 지금은 걸음마다 화면에 '참고' 로 보인다.
  - 45° 사선에서 기울인 걸음을 담은 지정 세트로 검출률을 재면 코칭 전용으로 올린다.
  - 런지 ship 은 3개(깊이·숙임·무릎 쏠림)다. 등록부 ship 은 12개다.
- **옆(SIDE)·후방 사선(A)은 ship 을 유지한다.** 근거는 원칙 #2(근거는 AIHub 에 한정하지 않는다)다. 뷰별 모집단 정상 걸음(MM-Fit, 어깨선 뷰)과 폰 검출은 다음과 같다.

  | 검사 | B | SIDE_B | SIDE_D | A | D |
  |---|---|---|---|---|---|
  | 숙임 +25 초과 | 0/170 | 0/97 | 0/11 | 0/17 | 0/15 |
  | 깊이 초과 | 8.7 % | 2.3 % | 0 % | 0 % | 0 % |
  | 무릎 쏠림 초과 | 1.7 % | 3.9 % | — | — | — |

  - 무릎 쏠림의 2걸음 연속 음성은 0/62 세트다.
  - 폰 검출(SIDE_B 세트): 숙임 4/4, 센 얕은 걸음 2/2, 놓친 얕은 걸음 4/4.
  - A/E·SIDE_D 는 표본이 작다. 폰 근거는 1인 1세트라 임계는 여전히 잠정이다.
- **공유 숙임 기준을 막는다.** 숙임은 기준 모음이 하나(`refByView = false`)다. 거기에 검사의 뷰 밖에서 유보된 걸음의 원값을 넣지 않는다(`commitReference` 가 뷰를 본다). 기준 하한은 `refFloor = −10°` 다. 정상 걸음 바닥 최소는 −4.2° 이고, 좌우 이름이 뒤바뀌면 숙임이 음수로 읽힌다.
- **걸음 판별은 |pitch| 로 본다.**
- **요약 줄을 걸음 단위로 바꾼다.** "정확 N / 판정 M걸음" 이다. 여기서 걸음 아닌 사이클과 전부 유보된 걸음은 '정확' 에 넣지 않는다(`RepFormRep.judged`, 원칙 #1). 판정 0건이면 '정확' 줄을 쓰지 않는다. 판정 못 한 걸음과 걸음 아닌 동작은 따로 밝힌다.
- **시작 자세(발 너비·발끝) 문장은 START 검사가 있는 종목(스쿼트)에서만 쓴다.** 이것은 컬도 고친다.
- **`stand_yaw` 는 걸음 종목에서만 잰다.** 스쿼트·컬 로그는 HEAD 와 같다.

*놓친 얕은 걸음.*
- **방향은 그 구간(앞뒤 선 자세 포함) 자기 프레임으로 잰다.** 없으면 잠금 뷰로 대신한다. 방향을 모르거나 정면이면 판정하지 않는다. 이전에는 잠금(3걸음) 전이면 정면에서도 판정했다.
- **구간 안에서 앞다리가 바뀌면 걷기로 보고 버린다.** 가장 굽은 프레임도 벌림이어야 한다.
- **쿨다운은 말할 때만 쓴다**(`speak`). 이전에는 TRACK 에서 조용히 쓴 쿨다운 때문에 COACH 로 바꾼 뒤 첫 교정이 짧은 단서로 나갔다.
- **발을 모으고 돌아오기는 요구하지 않는다.**
  - 사용자 세트 045533 은 선 자세에서도 앞뒤 벌림 −0.8 인 제자리 스플릿이었다. 스플릿도 같은 걸음으로 센다(설계 §6 #5).
  - 이 조건을 넣었더니 폰 놓친 얕은 걸음이 4 → 0 이었다.
  - 재생 결과: 폰 4/4, MM-Fit 정상 62세트 1 → 0, REHAB 10 → 8.

*그 밖.*
- **`advanceHoldFrom`(§63)을 세션 시작·종료·넘김, 목표에서 내려갈 때 지운다.** token 이 세션마다 0 부터 다시 쓰여서, 두 번째 세션부터 기다림 없이 넘어가 마지막 말을 잘랐다.
- **분석기 닫기를 분석 스레드로 옮긴다.** 메인은 `markClosed()` 만 부른다. 이전에는 메인의 close 가 첫 프레임 모델 준비(GPU, 수 초)를 기다렸다.
- **`discardWindow`(일시정지·카메라 전환)는 걸음 종목에서만 쓴다**(`RepFormEvaluator.stepSides`). 스쿼트·컬 재생 파리티를 유지하기 위해서다.
- **연구 도구.**
  - `setlog_captures.current_unit` 이 스텝 포워드에 `side_each` 를 돌려준다.
  - `UNIT_CYCLES["side_each"] = 2` 다.
  - side_each 의 `loggedUnitConsistent` 는 `reps.sides.track.pairs` 와 견준다.
  - `score_phone_reps.same_display` 는 side_pair·side_each 를 같은 화면 단위(쌍)로 본다.
  - 재생기는 `reps.sides.target` 으로 쪽별 상한을 두고 `paritySides` 를 낸다.

유닛 445건. 알려진 미해결: ✕ 나가기에서 취소를 누르면 그 세트가 이미 마감돼 이후 걸음이 세지지 않는다. 기존 결함이고 런지와 무관하다.

## §64 — 기존 레이아웃을 유지한 운동 시범 (2026-09-27)

사용자 요청에 따라 로컬 main에서 목록·준비·운동의 기존 배치를 유지하고 `운동 방법` 진입점과 공통 하단 시트를 추가했다. 초보자가 동작을 보는 사이 준비 카운트다운이 끝나지 않도록, 첫 안내가 구성되는 시점부터 루트의 pausedState로 시계·자동 전환·자세 측정을 함께 보류한다. 카메라 자체와 준비/운동 라우트는 교체하지 않는다.

- 처음 수행하는 종목은 첫 준비에서 안내한다. 확인 상태는 종목별 전용 설정에 저장한다. 시범 확인은 숙련도나 정상 자세 인증이 아니다.
- 첫 안내의 명시적 준비 버튼으로만 기존 준비를 진행한다. 수동 재열기·뒤로가기·시트 닫기는 정지 상태를 유지하고 사용자가 재개한다. 카메라 준비의 재개 버튼은 외부 세션 일시정지도 해제한다.
- 목록의 편집·스위치·정렬·삭제, 홈·내비게이션·계기판·카메라 영역은 유지한다. 촬영 방향 안내와 운동 동작 시범을 구분한다.
- 등록 자산은 기본 스쿼트·런지·덤벨 컬 3개로 한정한다. 로컬 ImageGen 2프레임 시안이며 Gym Visual 자산을 사용하지 않았다. 미등록 종목·다른 장비/변형에는 대체 시범을 표시하지 않는다. 전문가 검수를 거친 연속 동작 자료로 교체하는 작업은 별도다.
- 상세: `docs/EXERCISE_GUIDE_UI.md`. 이 변경은 자세 규칙·임계값·카운터·점수 정책을 바꾸지 않는다.

## §65 — 준비 안내 축소·목표 휠·운동 설명 구조화 (2026-09-27)

사용자가 준비 패널의 긴 대사와 작은 보조 문구를 빼고 촬영 방향 그림·배치 안내만 남겨 스크롤을 없애도록 요청했다.
표시 문구와 스크롤만 줄이며 준비 감지·카운트다운·TTS·촬영 게이트·판정 엔진은 변경하지 않는다.

운동 수정의 +/- 행을 세트 / 횟수 또는 시간 / 세트 간 휴식의 세 열로 교체했다. LazyColumn의 역방향 숫자 휠로
아래 드래그가 증가, 위 드래그가 감소하고 중앙 값에 스냅한다. 세트·횟수·초는 1 단위이며 기존 범위·저장/취소 정책을 유지한다.
이미 선택된 횟수/시간 탭을 눌러도 목표를 기본값으로 덮어쓰지 않는다.

운동 방법에는 코치의 코멘트 / 운동 가이드 / 주의사항을 추가하고 시작 자세·동작·호흡을 분리했다.
첨부 사진은 구조만 참고하며, 현재 등록 3종목의 설명은 ACE·Mayo Clinic 원문에 근거한다.
종목별 출처와 일반 호흡 원칙을 적용한 범위는 `docs/EXERCISE_GUIDE_UI.md`에 기록했다.
사용자 요청에 따라 빌드·유닛 검사만 수행하며 실휴대폰 검증은 사용자가 담당한다.

## §66 — 계열 단위 온보딩: 채점표 도구 · CLAUDE.md 분리 · 파일럿(바벨 컬·바벨 런지) (2026-09-27)

사용자 결정(2026-09-27): 한 종목씩 폰 피드백 → 재설계로 3종목(스쿼트·덤벨 컬·런지)에 사흘과 많은 토큰이 들었다. 남은 23종목은 효율적으로 한다. 순서는 ① 도구·등급표·CLAUDE.md 정리 ② 파일럿(바벨 런지 + 바벨 컬) ③ 팔 들기 4종이다.

*등급표.* `docs/EXERCISE_TIERS.md` 에 23종목을 계열로 묶었다.
- 등급은 A(ship 반복 검사), B(beta 참고), C(횟수·못 보는 것 문장)이고, 완료 기준을 먼저 정한다.
- 계열 온보딩 절차는 새 세션 → 검사 틀 재사용 → 오프라인 표 → 폰은 계열당 한 번 → 튀는 것만 수정 → 계열 끝 검토 1회다.
- 폰 지정 오류 세트 프로토콜 CSV 형식과 토큰 규칙도 이 문서에 있다.

*채점표 도구(`research/external_rep_replay/`).*
- **`aihub_captures.py`**
  - AIHub 클립의 MediaPipe 결과(`research/aihub_fitness/outputs/mp`)를 재생 캡처로 옮긴다. 결과는 종목마다 카메라 5대 × 약 60클립, 서서 18종목 전부에 있다.
  - 클립은 250 ms 간격 16프레임이고, 클립 사이에 5 s 틈을 둔다.
  - 재생기는 세로 0.75 비율을 가정하므로, 가로 1920×1080 영상은 x 를 `x_px ÷ (0.75 × h)` 로 적어 2D 피처가 등방이 되게 한다.
- **재생기 `--clip-eval`**
  - 클립마다 **새** `RepFormEvaluator` 로 클립 하나를 한 반복으로 판정한다. 카운터는 쓰지 않고, 극값은 클립의 신호 최소·최대다.
  - 사람 사이의 기준이 섞이지 않는다. 대신 본인 기준 검사(FIRST_REPS·SET_LOW)는 판정이 유보되고, 원값 AUC 만 본다.
- **재생기 `--checks <종목>`**: 검사 명세(방향·임계·상태·뷰)를 코드에서 읽는다. 정본은 RepFormSpecs 다.
- **`family_scorecard.py`**: 근거 셋을 표 하나(`scorecard.md`)로 낸다. 덤벨 컬 전체가 약 4초 걸린다.
  1. AIHub 조건 라벨: 충족 클립 위반(참고 오탐), 위반 클립 위반(검출), 원값 AUC
  2. 모집단 정상 반복(MM-Fit·REHAB): 오탐, 차단 오탐, 연속 위반 세트 — 입장 조건 ≤ 2 % 의 근거
  3. 폰 지정 오류 세트(프로토콜 CSV): 블록별 검출, 정상 블록 오탐, 세트 횟수 정렬
- **대리 모집단**: 같은 종목 영상이 없으면 **대리 종목의 검증된 검사기**로 재생하고, 같은 이름의 검사끼리 견준다.
  - 처음에는 대상 종목의 카운터로 재생했다. 그러자 바벨 컬 카운터(두 팔 평균 `elbow_mean`)가 MM-Fit 교대 컬을 거의 못 세고 헛사이클을 셌다. 판정 수가 86 → 28 로 줄었고 '상체 숙임 차단 오탐 14 %' 가 나왔다.
  - 이것은 검사 오탐이 아니라 카운트 오류다. 카운트는 Gate A 의 질문이다.
- **요약 열의 '제안'** 은 참고다. 폰으로 확정한 검사(§62a~§63)를 자동으로 내리지 않는다.
  - 기존 결정 중 입장 조건(차단 오탐 ≤ 2 %)을 넘는 것이 있다.
    - 런지 깊이: REHAB 29 %. 사용자 결정 예외다.
    - 덤벨 컬 상체 숙임: MM-Fit 2.3 %.
    - 덤벨 컬 뜸: 7.2 %. 한 사람의 두 세트다.
  - 표가 이것을 드러낸다. 재검토는 해당 계열에서 한다.

*CLAUDE.md 분리.*
- 종목별 함정 단락(스쿼트 2 · 컬 4 · 런지 2)을 글자 그대로 `docs/exercises/{squat,curl,lunge}.md` 로 옮겼다. 규칙셋 분포의 종목별 목록은 `docs/exercises/README.md` 로 옮겼다.
- `CLAUDE.md` 는 23.4 KB → 14.0 KB 다. 모든 세션과 하위 에이전트에 매번 들어가는 파일이다.
- '옆은 어떤 규칙도 허용하지 않는다' 의 예외 목록에 런지 걸음 검사를 더했다(§63 후속 2).

*파일럿 — 바벨 컬·바벨 런지(재사용만).*
- `RepFormSpecs.curl(ex)`·`lunge(ex)` 에 종목 인자를 두었다.
  - 바벨 컬은 덤벨 컬 검사 9개(ship 5), 바벨 런지는 런지 걸음 검사 4개(ship 3)를 같은 임계로 쓴다.
  - 바벨 런지는 `STEP_LUNGES` 로 걸음 검사기(어깨선 뷰·걸음 쪽·방향 안내·놓친 얕은 걸음)를 쓰고, 앱은 `SIDE_EACH_LUNGES` 로 쪽별 카운트를 한다.
- 겹치는 창 규칙은 같은 방식으로 beta 로 내린다(`supersedes`).
  - 바벨 컬: '팔꿈치 위치 고정' 과 '척추의 중립[all·flexion]'. 뒤의 것은 어깨가 골반보다 앞에 있는 정도의 세트 평균이다.
  - 바벨 런지: '척추의 중립[lateral]'.
- 바벨 컬 시작 안내(`REFERENCE_HINTS`)는 덤벨 컬과 같다.
- `repform_v0.4` 이고, 등록부 ship 은 12 → 20 이다.
- 연구 도구의 단위: 바벨 런지 = `side_each`.

*파일럿 오프라인 결과(`family_scorecard.py`).*

바벨 컬의 AIHub 바벨 클립 결과다. 바벨 특유의 이상은 없다.

| 검사 | 원값 AUC | 조건 충족 클립 위반 | 비교(덤벨 컬) |
|---|---|---|---|
| 팔꿈치 앞 이탈 | 0.90 | — | — |
| 상체 숙임 ('척추의 중립' 대응) | 0.80 | 8.7 % | 덤벨 컬 0.63. 바벨 컬의 '척추의 중립' 은 실제로 몸통 앞 쏠림이다 |
| 팔꿈치 뜸 | 0.75 | 3.0 % | — |
| 팔꿈치 옆 벌림 | 0.60 | 7.1 % | 덤벨 컬 6.7 %. 넓은 그립이 가로 비를 부풀리지 않았다 |

- 모집단 수치는 덤벨 컬과 같다(같은 검사).

바벨 런지의 AIHub 결과:

| 검사 | 원값 AUC | 결과 |
|---|---|---|
| 앞무릎 깊이 | 0.96 | 위반 클립 41/41 검출, 충족 클립 43 % 위반 |
| 상체 숙임 | 0.27 (반대 방향) | 비교 대상 아님 |
| 어깨 기울기 | 0.73 | — |

- 깊이 검사의 충족 클립 위반이 높은 것은 키프레임 16장이 가장 깊은 순간을 자주 놓쳐서다. 런지와 같은 비관이다(§63 후속 1 의 AIHub-MP 52 %).
- AIHub 바벨 런지의 '상체의 과조한 숙임/젖힘' 위반은 주로 **뒤로 젖힘**이라 앞 숙임 검사의 근거가 아니다(그 창 규칙도 `torso_pitch__max < 19.5` 로 exclude). 원값 AUC 가 0.27 로 반대 방향인 이유다.
- 모집단 수치는 런지와 같다: 숙임 0 %, 무릎 쏠림 2.6 %(코칭 전용), 깊이 MM-Fit 5.5 %·REHAB 29 %(사용자 결정 예외).

*폰 프로토콜.* `research/external_rep_replay/protocols/pilot_barbell.csv` 의 세 세트다.
- 바벨 컬 ① 정상 5 · 팔꿈치앞 3 · 정상 2
- 바벨 컬 ② 정상 4 · 팔꿈치벌림 3 · 숙임 3
- 바벨 런지(쪽마다 6걸음) 정상 3 · 얕게 3 → 오른쪽 정상 3 · 숙임 3

바가 없으면 빈 봉·빗자루로 해도 된다. MediaPipe 는 몸만 본다.

*빌드.* `main` 에 PR #6 이 버전 올림(9cb29f6, 1.3.0-preview.1 / 코드 6) 이전 커밋으로 병합돼, main 은 코드 5 였다. 릴리스 APK(코드 6) 위에 설치하려고 작업 트리를 1.3.0-preview.2 / 코드 7 로 올렸다.

*남은 것.*
- 폰 파일럿 세트 → `family_scorecard.py --phone … --protocol …` 표 → 종목당 실제 비용 측정 → 팔 들기 4종.
- 팔 들기의 모집단 영상(MM-Fit 숄더 프레스·레터럴 레이즈)은 아직 MediaPipe 로 추출하지 않았다(`extract_mediapipe.py`).

## §66 — 안내 패널 고정·휠 저장 경로 단일화 (2026-09-27)

운동 안내는 스와이프 이동 없이 본문만 스크롤한다. 운동 가이드/주의사항만 남기고 GIF는 화면이 열린 동안 자동 재생한다.
재생 조작·코멘트·출처 UI·숫자 조작 설명을 제거했다. 출처 기록은 구현 문서에 남긴다.

사용자는 런지의 휴식 수정값 저장 실패를 보고했다. 저장소의 restSeconds 직렬화와 세트 사이 휴식 생성은 유지하고,
휠 선택값을 비동기 콜백으로 초안에 복제하던 구조를 없앴다. 표시와 저장이 동일한 휠 상태를 직접 참조하며
저장 순간 세트/목표/휴식 3개를 함께 확정한다. 수정 중 드래그로 시트가 닫히지 않게 했다.
이는 코드에서 확인한 값 복제·닫기 경로를 보완한 것이며 실기기 발생 원인은 재현하지 않았다.
런지 휴식 설정·실행 단계 연결 회귀 2건을 포함한 JVM 450건 통과. 사용자 지침에 따라 실기기 검증은 수행하지 않는다.

## §67 — 목록 마지막 운동 추가 행 (2026-09-27)

운동 목록의 마지막 항목 아래에 `운동 추가하기` 행을 배치했다. 헤더의 기존 추가 버튼과 동일한
`onAddWorkout` 콜백으로 종목 선택 및 설정 화면을 연다. 빈 목록에도 보인다. 운동 데이터가
아니므로 정렬·삭제·자세 비교의 대상에 넣지 않는다. 빌드로 오류를 확인하며 실기기 화면 검증은 사용자가 한다.

## §68 — 별도 근육 모드·스쿼트 자산 시안 (2026-09-27)

`outputs/muscle-mode/`에 기존 인체 모델과 분리된 Blender 장면·인터랙티브 검토 페이지를 추가했다.
기존 남녀 모델 원본 4개는 SHA-256 일치로 보존을 확인한다. 근육 모드는 Z-Anatomy/BodyParts3D의
남성 해부학 모델을 부위별 실제 메시로 나누고 근육 ID·좌우·출처를 보존한다. 여성 근육 모델을
의학적으로 검증했다는 의미가 아니다. CC BY-SA 4.0 출처와 변경 내역은 폴더 README에 기록한다.

선택 근육의 대비·단독 확대·깊은 근육 선택과 피로도 색 농도 시뮬레이션을 제공한다. 색은 실제
피로도나 근육 사용률이 아니며 애니메이션과 독립적이다. Isear 1997·Kubo 2019는 스쿼트 부위
분류의 근거로만 사용하고 EMG·근비대를 개인 사용량 %로 변환하지 않는다.

스쿼트 1회 6초의 관절 키프레임을 Blender와 미리보기에서 공유한다. 발 고정·변환 일치·PC
브라우저 조작을 확인한다. 해부학·동작의 의학 검수와 회복도 추정 엔진은 미완료이며 앱 기능으로
출시하거나 연결하지 않는다. 자세 판정 엔진·점수·촬영 정책은 이 자산 시안의 범위에 포함되지 않는다.

## §69 — 근육 연결·표면·개별 변형 정밀 수정 (2026-09-27)

`outputs/muscle-mode-refined/`에 별도 개선본을 추가했다. 일부 전완 근육이 이름 분류에서 빠져
다리 가중치를 받던 문제를 수정했다. 손의 총지신근 이름을 부분 문자열로 매칭하면 발의
짧은발가락폄근까지 팔로 분류하는 문제도 발견해 구분했다. 몸통·어깨·상완·전완·목의 연결은
위치 기반 연속 가중치를 사용한다. 이 가중치는 개별 힘줄 경로·견갑대 생체역학의 검증을 뜻하지 않는다.

복직근은 좌우 연속 근육으로 유지하면서 표면을 재구성하고 힘줄 교차를 표현했다. 가리는 중앙
건막 일부는 절개 표시로 생략했다. 얼굴은 완성된 MakeHuman 피부 표면과 눈을 정합했으며
표정근을 분리한 모델이 아니다. 원본 근육 메시의 해상도·재질·섬유 결을 보완했다.

148개 좌우/근두 메시에 독립 Shape Key를 추가하고 미리보기에서 개별 선택·변형을 제공한다.
기하학적 근복 팽창이며 실제 수축·활성도·힘을 계산하지 않는다. 피로도 0%는 기본색 `#b56b60`,
증가 시 `#581b2b` 방향으로 어두워지며 선택 외곽선은 별도다. 색상은 시뮬레이션이다.

49개 스쿼트 자세 표본에서 유한 좌표·문제 전완의 다리 가중치 0·문제 발 근육의 고정 위치를
검사했다. GLB의 6초 애니메이션·148개 변형 대상, 메시 재검사, PC 재생·단독 변형·0% 색상·
320px 화면 조작을 확인했다. 기존 남녀 원본 4개 SHA-256은 동일하다. 검증 JSON과 문헌 대조는
같은 폴더에 보존한다. 문헌·기술 검수이며 의료인 서명 검수는 미완료다. 여성 해부학 모델,
실제 피로 추정과 Android 연결은 구현하지 않았고 실휴대폰은 조작하지 않았다.

## §70 — 참고 이미지의 회백색 근육·붉은 강조 (2026-09-27)

사용자가 첨부한 이미지에 맞춰 `outputs/muscle-mode-ivory/`에 새 표현을 추가했다. 기존 정밀
메시와 가중치를 재사용하고 기본 근육을 회백색, 피로도 색상 시뮬레이션의 강조를 산호색·붉은색으로
변경했다. 새 표현의 0%는 `#deded9`, 100%는 `#d53b35`다. 첫 화면의 부위별 색상은 예시이며
회복도와 피로도 수치를 혼용하지 않는다. 앞뒤를 동시에 렌더링하며 시점·개별 근육·스쿼트 조작을 유지한다.

Blender 재질과 별도 GLB를 저장했다. GLB는 절차적 섬유 범프를 자동으로 보존하지 않으므로
브라우저에서는 별도 섬유 셰이더를 적용한다. PC에서 0% 색상 일치·독립 변형·재생·좁은 화면을 확인했다.
이 절은 시각 표현 수정이며 추가 의학 검증·여성 해부학 모델·실제 피로 추정·앱 연결은 포함하지 않는다.

## §71 — 목 중복 피부·근육 사이 빈 표면 수정 (2026-09-27)

사용자가 목·어깨의 피부 중첩과 복부 등의 빈틈을 지적했다. 얼굴 표면이 목 아래까지 포함된
것과 개별 근육만 나열해 연결 조직 영역을 비워 둔 것이 원인이었다. `outputs/muscle-mode-continuous/`에
별도 개선본을 만들었다. 턱선을 보존하는 경사면으로 피부를 절단하고 목·어깨 근육을 노출한다.

몸통·팔·다리의 실제 메시 단면을 샘플링해 안쪽으로 들어간 연속 연결층을 추가했다. 이는
근육 사이로 배경이 비치는 것을 막는 표시용 기하이며 실제 근막 두께·개인 해부학을 측정한
데이터가 아니다. 선택·피로색·개별 근육 변형에서는 제외한다. 턱선과 연결층은 연속 메시로
융합하고 겹친 뒷목 표면을 제거했다. 복직근은 좌우 두 근육을 유지하며 볼륨·중앙 간격을 보완하고,
외복사근 절개 경계의 톱니 모양을 정리했다. 팔·다리 사이 등 정상적인 공간은 유지한다.

6초 스쿼트·148개 좌우/근두 변형·0% 회백색을 유지한다. 49개 자세 표본의 유한 좌표·전완
가중치·발 근육 고정, GLB 애니메이션 및 변형 대상을 확인했다. 복부 중앙 1.00~1.28m 확대 영역은
투명 픽셀이 0이며 모든 시점·모든 부위의 무결성을 보증하는 검사로 확대 해석하지 않는다.
목·복부·등 확대와 PC 조작 결과를 같은 폴더에 저장한다. 기존 원본 파일의 해시는 동일하다.
Android 연결·실휴대폰 조작·추가 의학 검증은 하지 않았다.

## §72 — 손 피부 및 지정된 체간·하체 조형 (2026-09-27)

사용자가 손목 아래 피부와 가슴 아래·갈비뼈 주변·복근·골반·엉덩이·허벅지·종아리만 수정하도록
범위를 지정했다. `outputs/muscle-mode-sculpted/`에 별도 개선본을 저장한다. MakeHuman CC0의
피부 손을 현재 손목 좌표계에 맞추고 전완 가중치 1로 연결했다. 원본 선택에 포함될 수 있는
분리 조각은 공간 범위와 연결 성분으로 제외한다. 손뼈 54개는 원본에 보관하고 표시·내보내기에서
제외한다. 피부 밖으로 보이는 힘줄 및 연결층은 손목 아래만 정리하며 그 위 정점은 보존한다.

복직근의 전면 돌출·면과 힘줄 홈, 하부 전거근/외복사근의 연결, 골반 앞뒤 연결 곡면, 둔근의
볼륨과 곡률, 허벅지·종아리의 근복과 끝부분을 보완한다. 이는 참고 이미지에 맞춘 시각 조형이며
개인의 해부학·실제 수축량을 측정한 모델이 아니다. 손 피부와 연결층은 근육·피로도 계산 대상에서
제외한다. 요청 밖 메시 좌표 해시, 손목 위 정점 집합, 보호할 미리보기 부위의 데이터 불변을 검사한다.

0% 회백색·148개 좌우/근두 변형·6초 스쿼트를 유지한다. 49개 자세 표본과 GLB, PC 확대·조작
검사를 같은 폴더에 기록한다. 좌표/인덱스 차분과 바이트 재배치는 미리보기 용량을 위한 표현이며
형태 변경으로 사용하지 않는다. Android 연결·실휴대폰 조작·의학적 추가 검증은 하지 않았다.

## §73 — 상부 목 돌출·복벽·골반 연결면 재구성 (2026-09-27)

§72 확대 렌더에서 복직근의 평평한 기둥 형태, 늑골 주변 표현 부족, 골반의 사각 덧판과
천골 중앙 빈틈, 머리 뒤의 근육 끝 노출을 확인했다. 사용자가 제공한 `plnafit_analysis_02.jpg`와
목·복부·둔부를 나란히 비교하며 `outputs/muscle-mode-anatomy-finish/`에 후속 모델을 만들었다.

상부 흉쇄유돌근·승모근의 끝은 머리 내부로 수렴시키며 1.575m 아래 정점은 유지한다.
복직근은 좌우 각각 하나의 연속 메시에서 세 쌍의 둥근 근복과 길어진 하부 건막으로 재구성한다.
힘줄 교차는 표면 재질에 포함해 개별 변형 때 같이 움직이며 별도 근육으로 세지 않는다.
전거근 하부 갈래와 외복사근의 표면을 보완하고, 흉곽에 뜬 대흉근 복부 기시부를 붙였다.
골반 앞뒤 사각 덧판은 제거하고 몸통과 골반 연결층을 하나의 닫힌 체적으로 융합한다.
대둔근의 하연과 근복을 둥글게 만들고 중둔근·소둔근의 경계를 다듬는다.

기존 손·얼굴·허벅지·종아리 등을 포함한 341개 보호 메시 좌표는 동일하다. 개별 제어 148개,
0% `#deded9`, 6초 스쿼트는 유지한다. 49개 자세 표본의 좌표/전완/발 회귀, GLB 애니메이션,
목 위 정점의 기본·변형 상태 8건, 복직근·외복사근·대둔근·융합 연결층 7개 표면의 열린 경계와
비다양체 경계 0을 확인한다. 전체 모델의 모든 접촉·충돌이나 해부학적 완벽성을 입증하는 검사는 아니다.
PC UI 조작·좁은 화면·부위별 확대를 확인하고 비교 이미지와 JSON으로 남긴다.

참고는 여성형 2D 일러스트이고 현재 근육 모델은 기존 남성형 3D다. 비율·조명·섬유 질감까지
동일하게 복제한 결과나 추가 의료인 검수로 표현하지 않는다. Android 변경·실휴대폰 검증은 없다.

## §74 — 옆구리 연속 근육 면·가슴 노출·둔근 축소·발 피부 (2026-09-27)

사용자는 §73의 막대형 전거근이 갈비뼈처럼 보이며 가슴을 가리고, 둔근이 지나치게 둥글고
돌출됐다고 지적했다. `outputs/muscle-mode-natural/`에서 지정 부위를 수정한다. 추가 막대를
제거하고 늑골 메시를 최종 표시/GLB에서 제외한다. 옆구리는 외복사근/전거근 선택 구분을
유지하는 연속 근복으로 표현하고 가슴 앞에 있던 연결층은 대흉근 표면 뒤에 둔다.
이 옆구리 외형은 시인성을 위한 조형이며 원본 내부 해부학의 정밀 복제로 해석하지 않는다.

대둔근은 Z-Anatomy 원본 형태에서 표면과 부착부를 정리한다. 뒤쪽 최대 좌표가
0.14251m에서 0.10932m로 약 23% 줄었으며 부피/근육량 감소율은 아니다. 몸통·다리의
내부 연결층이 축소한 둔근 위로 드러나지 않게 정합한다. 발은 MakeHuman CC0 피부를
발목 좌표와 정강이/발 가중치에 연결한다. 발 피부 밖 힘줄은 0.137m 아래에서 정리하며
그 위 정점은 보존한다. 발 피부는 피로 색상과 근육 변형에서 제외한다.

요청 밖 293개 메시 좌표, 원본 자산 4개, 148개 근육 변형 대상·6초 스쿼트·0% 회백색을
유지한다. 8개 광선 표본의 최전면 가림, 49개 자세의 좌표/전완/발 회귀, GLB 구조,
PC UI·320/360px 표시를 확인한다. 표본 검사를 모든 시점의 무결성이나 의료인 검수로
확대 해석하지 않는다. 참고/수정 전후 비교와 검사 JSON은 산출물 폴더에 저장한다.
Android 앱과 실휴대폰은 변경·조작하지 않았다.

## §75 — 복부 구획·옆구리 방향·하복부 연결 개선 (2026-09-28)

§74의 복부는 긴 판과 기둥처럼 보였다. 사용자 요청에 따라 실제 오브젝트와 표면부터
검사했다. 복직근·전거근·외복사근 좌우 및 몸통 연결층 7개 표면은 열린 경계가 0이었다.
어두운 곳은 구멍으로 단정하지 않고 광선의 표면 순서로 깊이·가림을 확인했다.
골반 앞의 치골근·내전근·봉공근 등을 식별하고 기존 근육을 삭제하지 않았다.

`outputs/muscle-mode-abdomen/build_abdomen.py`는 기존 복직근 높이/폭과 몸통 표면을
기준으로 구획별 볼륨, 얕은 백선/힘줄 교차, 작은 배꼽 함몰, 둥근 하복부 끝을 구성한다.
전거근/외복사근은 동일 몸통 곡면과 맞물림 경계를 사용하며 독립 오브젝트를 유지한다.
몸통 앞쪽 연결층만 국소 세분·변형하여 넓고 얇은 건막과 서혜부 경계를 이어준다.
가슴을 가리는 층과 사선에서 들뜨는 전거근 위끝/옆구리 뒤끝을 기존 표면에 정합한다.
부위별 폭·돌출·경계 깊이·끝 좁아짐은 `P`에서 조절한다. 매번 보존 원본을 새로 연다.

정본 수정본은 `trex-muscle-abdomen.blend`다. 요청 밖 355개 메시의 정점/면/Shape Key,
몸통 보호 정점 11,287개를 보존했다. 오브젝트 367개·개별 변형 148개를 유지하고,
수정한 표면의 열린 경계/비다양체 모서리 0, 정면 표본 693개 배경 관통 0,
49개 자세의 좌표 유한성과 가중치 합을 확인했다. 같은 회색 재질·조명·카메라의
정면/45도/측면 렌더를 직접 비교했다. 전체 경량 미리보기의 얇은 경계 축약 때문에
최종 표시에는 수정 부위에 해상도를 배정한 `focus-preview.html`을 사용한다.
검사 범위는 JSON과 README에 명시하며 전신 모든 충돌이나 의료인 승인을 뜻하지 않는다.
Android와 실휴대폰은 변경·조작하지 않았다.

## §76 — 복부 수정본을 기존 전신 원본에 반영 (2026-09-28)

사용자가 §75 복부 수정본을 기존 전신 모델에 반영하도록 요청했다. 교체 전
`muscle-mode-natural`의 Blender/GLB/미리보기 데이터와 소스를 `pre-abdomen-backup/`에
보존하고 전신 `.blend`를 승인된 수정본으로 교체했다. 두 파일의 SHA-256 일치를
`integration-verification.json`에 기록한다. 같은 경로의 GLB와 전신 미리보기도 갱신했다.
§75 생성·비교·검사 코드는 수정 전 백업을 참조하도록 고정했다.

근육 148개 제어, 6초 스쿼트, 남녀 기존 모델 보기, 0% 회백색 및 원래 선택 UI를 유지한다.
인라인 전신에는 복부 정점 예산을 늘리고 약 0.333mm 좌표 압축을 사용한다. 원본 Blender와
GLB에는 이 압축을 적용하지 않는다. 49개 자세의 유한 좌표·전완/발 회귀·GLB 구조와
PC 조작/320·360px 표시를 확인했다. Android·실휴대폰은 변경하거나 조작하지 않았다.

## §77 — 문헌 기반 흉복벽 층과 근위 햄스트링 조형 (2026-09-28)

사용자가 늑간근·전거근·외복사근의 문헌 조사와 형태 개선, 둔부에 이어지는 햄스트링의
자연스러운 확대를 요청했다. `outputs/muscle-mode-researched/ANATOMY_RESEARCH.md`에
Webb·Brown·Ng·Subit·Wilson·Miller 연구 및 해부 참고 자료의 실제 열람 범위와 근거를 남겼다.
소수/고령 기증자의 평균 치수와 특정 아바타의 조형 계수를 구분한다. 전문 의료인 검수는 아니다.

§76 원본을 `source-backup/`에 보존하고 기존 해부학 원자료의 전거근 갈래와 외복사근 면을
현재 몸통에 정합했다. 전거근은 몸통, 외복사근은 몸통/골반에 결합해 팔 가중치로 벌어지지
않도록 했다. 외·내·최내늑간근은 각 좌우 독립 깊은 층 6개로 복원하고 미리보기에서
층별 선택/단독 보기를 제공한다. 기본 전신에서 갈비뼈를 밖으로 돌출시키지 않는다.

햄스트링 장두·반건양근·반막양근은 기존 단면 중심 기준으로 위쪽 근복 반경을 최대 18%
확대하고 기시 끝·원위부는 고정했다. 표면 최대 이동 약 8.35mm는 조형 결과이며 생리학적
비대율이 아니다. 대퇴이두근 단두·둔근·복직근 등 기존 352개 메시를 보존했다.
작은 고립 조각·다중 접합을 국소 정리하여 수정 10개+추가 6개 메시의 열린 경계/비다양체
모서리 0을 확인했다. 전체 병합/리메시는 하지 않는다.

같은 회색 단일 재질의 정면·45도·측면과 햄스트링 후면/사선을 비교했다. 49개 자세의
유한 좌표, 가중치 합, 154개 독립 변형, 6초 GLB, PC 조작·320/360px 표시를 검사했다.
현재 `muscle-mode-natural`의 .blend/GLB/전체 검토 화면에 반영하고 해시 일치를 기록한다.
전체 검토와 인라인 전신은 서로 다른 화면용 LOD이며 Blender/GLB 정밀도는 보존한다.
늑골별 호흡 변형·전수 자기 관통·실제 운동 활성량/피로 추정은 구현 또는 검증하지 않았다.
Android 앱과 실휴대폰은 변경·조작하지 않았다.

## §78 — 문헌 기반 스쿼트·덤벨 컬·전방 런지 시연 (2026-09-28)

사용자가 세 운동의 사용 근육과 머리부터 발까지의 단계별 수행 지침을 신뢰할 만한
자료에서 조사하고 시작→수행→원위치 복귀까지 구현하도록 요청했다. ACE와 Mayo의
교육 자료, Robertson(2008) 스쿼트 연구, Marcolin(2018) 컬 EMG, Muyor(2020) 런지 EMG,
Leonello(2007) 상완근 해부 연구를 대조했다. 초록/교육 본문의 실제 열람 범위와
정량 일반화의 한계는 `outputs/muscle-mode-exercises/EXERCISE_RESEARCH.md`에 기록한다.
머리·목·어깨·팔꿈치·손목·손가락·흉곽·허리·골반·무릎·발목·발가락별로 명세를 나눴다.

§77 전신을 백업한 뒤 기존 368개 메시 정점/면과 154개 독립 근육 변형을 보존했다.
기존 13개 본에 MakeHuman 손가락 관절 30개, 현재 발 비율에 맞춘 앞꿈치 2개,
덤벨 장비 2개 제어를 추가했다. `motion.py`는 기존 리그 길이에 비례한 2절 IK,
발 접지, 뒤꿈치 착지·들림, 손가락 그립과 손목/팔꿈치 고정을 구현한다. 매번 보존
원본에서 생성하므로 중복되지 않는다. 흉복벽·햄스트링 조형을 다시 만들지 않는다.

맨몸 스쿼트와 양팔 덤벨 컬은 각각 6초, 좌우 전방 런지는 12초다. Blender에는
3개 개별 Action과 1–579프레임의 연속 검토 Action을 저장하고 장비 표시도 구간에
맞춘다. 개별 GLB 세 개는 47개 본과 154개 변형 대상, 6/6/12초를 확인했다.
검토 화면은 운동 선택·재생·스크럽과 정성적 사용 근육 강조를 추가한다. 강조 색을
켰을 때 피로도 슬라이더를 비활성화하며, 강조를 끄면 0% 회백색 계약을 유지한다.
기존 피부형 남녀 비교는 기존 스쿼트에 한정한다. 새 세 운동은 근육 모드에 있다.

운동별 193개 계산 자세에서 시작/종료 행렬 차이와 접지 행렬 변화 0, 컬 팔꿈치
이동 0, 다리 길이 최대 오차 약 6.3e-8m, 발 표면 바닥 관통 없음을 확인했다.
Blender 회색 렌더 및 PC 정면·사선·측면 주요 단계를 검토했다. 인라인 경량 모델도
운동 3종·0% 기본색·깊은 층·독립 변형·320px 표시 검사를 통과했다. JSON과 README에
범위를 남기고 `muscle-mode-natural`의 현재 Blender/검토 경로에 반영한다.

이 검사는 생체 역동역학, 전수 근육 충돌, 견갑/경추/요추의 개별 분절, 전완의
요척골 회전, 호흡에 따른 흉곽 용적 변화를 검증한 것이 아니다. 시연 각도와 색을
자세 판정 기준/실측 활성량으로 승격하지 않는다. Android 자세 엔진 및 실휴대폰은
변경하거나 조작하지 않았다.

## §79. 기본 차렷 자세와 근피로 추정 타당성 조사 (2026-09-28)

`outputs/muscle-mode-neutral/`에서 §78 전신 원본을 보존하고 팔을 몸 옆으로 내린
기본 Action을 추가했다. 전완 회전으로 양손 손바닥을 안쪽으로 향하게 하고 손가락
벌어짐을 줄였다. 기존 운동 시범은 종목에 맞는 팔·손 자세를 유지한다. 메시 조형과
47개 본·154개 근육 변형은 유지하며, 기본 보기와 세 운동 전환을 PC에서 확인했다.
정적 GLB는 `export_rest_position_armature=False`로 내보내야 현재 자세가 유지된다.
이를 재수입해 정면/측면 렌더와 구조를 확인하고 현재 `muscle-mode-natural`의
Blender·GLB·미리보기에 반영했다. 원래 기본 GLB의 스쿼트는 `trex-squat.glb`에
보존한다. 이전 파일과 생성 코드·검사 JSON은 새 폴더에 있다.

사용자의 피로 기능 요청은 **조사만** 수행했다. 근거와 코드 감사는
`docs/MUSCLE_FATIGUE_FEASIBILITY.md`에 기록한다. 현재 횟수·세트·자세 점수·키·체중은
일반적인 사용 근육과 운동 노출을 설명할 수 있지만 실제 근육별 피로/회복 %를
식별하지 못한다. 문헌은 상대 부하·실패 근접도·휴식 조건의 중요성을 보인다.
자세 정확도는 판정된 ship 규칙의 통과 비율이며 근육 자극 효율로 환산하지 않는다.
ABSTAIN·TRACK null을 정상/0부하로 취급하지 않는다. 중량·RIR·실제 시각·개인 능력과
누적 세션 원장이 후보 입력이다. 현재 같은 날 기록 교체·7일 보관 정책도 별도 설계가
필요하다. 근육 모델의 시각적 색과 생리학적 측정을 구분한다.

앱 UI·기록 구조·피로 수식·자세 엔진은 변경하지 않았다. 실제 휴대폰 검증이나
전문 의료인 검수, 개인 근피로 측정 실험은 수행하지 않았다.

## §80. 손 방향 정정과 세션 기반 피로 추정 설계 (2026-09-28)

사용자가 §79의 손바닥 방향 오류를 지적했다. 기존 검사에서 임의의 -Y 축을
실제 손바닥 법선이라고 가정한 것이 잘못이었다. `build_neutral.py`의 전완 회전
부호를 바꾸어 이전 결과 대비 양쪽 각각 180도 반전했고, 잘못된 법선 검사는
삭제했다. 실제 GLB 재수입 정면/측면에서 손등이 바깥으로 향하는 결과를 확인했다.
수정 전 파일은 `outputs/muscle-mode-neutral/pre-palm-correction/`에 보존한다.
기본 자세만 변경하고 운동 3종 Action은 유지한다. PC 기본 보기·운동 전환·320px
검사 후 기존 `muscle-mode-natural` 전신 및 인라인 미리보기에 반영했다.

사용자는 생리학적 실측의 한계를 인정하면서 세션 기반 추정 기능의 개발 설계를
요청했다. 정본 설계는 `docs/MUSCLE_SESSION_FATIGUE_DESIGN.md`다. 반복·세트와 작은
키/체중 보정으로 근육군별 부하를 배정하고, 빠른/느린 감소 항을 합쳐 잔여량을
0–100점으로 표시한다. 중량 미입력 컬도 간편 추정으로 제공하되 체중으로 덤벨
중량을 만들어내지 않는다. 근육 가중치·반감기·체격 지수·점수 계수는 문헌에서
검증된 숫자가 아닌 조정 가능한 초기 가정이다. 가상 계산 예시와 민감도 시나리오,
좌우 단위, 세트 원장, 멱등 저장, 날짜/삭제/이관, 기존 7일 UI와 보관 분리,
3D 근육군 ID·회백색→붉은색 연결과 단계별 검증 항목을 정의했다.

이 요청에서는 피로 계산기·DB·앱 화면은 구현하지 않았다. 자세 점수를 부하에
곱하거나 ABSTAIN을 0부하로 처리하는 설계는 채택하지 않는다. 실휴대폰 검증은
사용자가 하므로 조작하지 않았다.

## §81 기록 기반 근육 피로도·26종목 근거·로컬 원장 (2026-09-28)

사용자 요청으로 §80 설계를 실제 앱에 연결한다. 현재 카탈로그는 26종목 전체이며
앞서 조사한 3종을 제외한 추가 조사는 23종이다. 정본 조사/DB/추정식/검증 범위는
`docs/MUSCLE_LOAD_IMPLEMENTATION.md`, 원연구 링크와 적용 한계/계수의 코드 정본은
`trainingload/MuscleLoad.kt`다. 이름이 다른 동작이나 다른 지지/중량 조건을 해당
운동의 직접 검증으로 표시하지 않는다. 근육별 EMG를 개인 피로 %로 변환하지 않는다.

홈 운동·식단 동작 카드 아래에 근육 피로도 진입칸을 추가한다. 기존 중립 근육
모델에서 별도로 추출한 경량 정적 자산을 오프라인 WebView로 보여주며 앞·뒤/정면/
후면, 근육군 선택, 좌우 값, 관련 최근 세트, 다음 운동 후보를 제공한다. 0은 기본
근육 회색이고 부하가 커질수록 적색이다. 미기록 부위는 '기록 없음'이며 실제 회복
완료와 구분한다. 남성 기준 공통 형상이고 개인별/여성 해부 모델로 오인시키지 않는다.
3D에서 없는 심부 고관절 굽힘근을 모두 표현했다고 하지 않는다.

저장 이유: 기존 날짜별 7일 기록은 같은 날 교체되고 만료되므로 연속적인 부하의
정본으로 쓸 수 없다. SQLite `load_sets`(원본), `muscle_doses`(근육군/좌우 부하),
`load_meta`(이관 마커)를 트랜잭션/FK로 묶고 시각/자세 세트 ID 인덱스를 둔다.
세션 진입 UUID+단계 Workout ID를 멱등 키로 사용한다. 기존 기록에 원본도 저장하여
실패 뒤 재전송한다. 같은 날 다른 세션은 누적하고 동일 세트만 갱신한다. 90일
원장과 7일 화면은 별도이며 현재 색을 매분 DB에 쓰지 않는다. IO/계산은 각각
Dispatchers.IO/Default에서 수행한다. 이미 지워진 과거 기록은 복구할 수 없다.

플랭크는 실제 workMillis, 런지는 TRACK 좌우 걸음, 나머지는 기록 횟수를 사용한다.
한쪽 수행 때문에 화면이 0쌍이어도 부분 기록을 반영한다. LungeSides의 unknown은
이미 left/right에 포함됐으므로 재가산 금지. extra는 좌우별 정보가 없어 균등
배분하고 추정 출처를 남긴다. 니업/사이드 크런치/시저의 미귀속 사이클은 교대
가정으로 배분하며 엔진 단위를 바꾸지 않는다. actualReps가 있으면 우선하고
해제 시 원본으로 복귀한다. ship 정확도·유보·TRACK은 부하 배율에 사용하지 않는다.

추천은 기록 부하가 덜 겹치는 순서·보유 기구·주동근 초기 경계값을 적용한 후보며
안전/완전 회복 판정이 아니다. 없는 근육 기록은 문구로 밝힌다. 사용자가 누르면
현재 운동 목록에 추가하며 자동 시작하지 않는다. DB 실패는 오류/재시도 상태다.

검증: JVM 468건(신규 18) 모두 통과, APK 빌드, 코드에서 추출한 DDL의 PC SQLite
제약/rollback/삭제 연쇄, 실제 앱 3D 자산의 PC Chromium 앞뒤·색·320px 검사.
검증 자료는 `outputs/muscle-load/verification.json`. Android SQLite 생명주기·
WebView 실제 화면·사용자별 생리 타당성·실휴대폰 UI/운동은 검증하지 않았다.
실휴대폰에서는 기존 일반 앱에 업데이트 설치와 설치 APK/저장 설정 보존 확인만
진행한다. 결과와 APK 해시는 같은 폴더 `deployment.json`을 따른다.


## §82. 완료 시 세션 부하 변화 · 부위별 단일 상세 카드 (2026-09-28)

§81의 DB·부하 계수·자세 판정 정책을 유지한다. 이번 UUID의 기록만 제외/포함한
두 스냅샷을 같은 평가 시각에 계산하여 완료 화면에 반영 전후를 표시한다.
실제 운동 전 측정이나 생리 회복 변화가 아니다. 기존 기록 없음은 0점 회복으로
바꾸지 않는다. 중복 ID/수동 횟수 수정·한쪽 런지·부분 시간·만료·미래 기록을
테스트한다. 계산은 백그라운드, 저장 실패/로딩은 별도 상태로 표시한다.
부분 수행을 저장하고 종료할 때도 완료 화면으로 연결한다.

피로도 페이지는 상체/하체/코어 선택 + 한 부위 상세 카드로 18개 세로 목록을
대체한다. 최근 관련 기록은 3개까지만 표시하며 추천은 별도 탭으로 분리한다.
요청한 회색 안내/최근 90일 헤더/계산 방법/연구 근거 UI는 제거한다.
연구 문서와 모델 저작자·라이선스는 유지한다. 루틴 카드는 사진을 보존하며
다크 테마 배경·글자·사진 색조를 적용한다.

JVM 473건(신규 5)·APK 빌드 통과, 실제 3D 자산 PC 오류/320px 넘침 없음.
Compose 화면·실휴대폰 UI는 검증하지 않았다. 업데이트 설치만 수행했고
설치 APK 일치·기존 설정 6파일 보존을 확인했다. 증빙:
`outputs/muscle-load-refinement/verification.json`, `deployment.json`.
상세: `docs/MUSCLE_LOAD_IMPLEMENTATION.md` §7.


## §83. 완료 화면의 사용 근육 강조 · 드래그 회전 (2026-09-28)

§82 완료 카드의 제목/설명/점수/더 보기/피로도 진입을 제거하고 근육도만 둔다.
현재 세션의 실제 수행 기록에서 양의 부하가 있는 근육·좌우를 추출해 최대 농도로
강조한다. 이 표시 농도 100은 생리 피로/근활성 점수가 아니다. 실제 피로 DB와
색 계산은 변경하지 않는다. 같은 세트 ID의 최신 수정·0회·한쪽·부분 시간을 처리한다.

완료 모드는 단일 모델 + 수평 360도/수직 제한 회전이며 시점 버튼을 숨긴다.
부모 스크롤 가로채기는 근육도 안의 터치에만 제한한다. 방향키/Home도 지원한다.
기존 피로도 화면은 앞뒤 버튼과 누적 점수 표시를 유지한다. 정상 화면 설명은 없다.

JVM 476건(신규 3)·APK 빌드, PC 실제 자산의 강조/해제/독립 입력·문구/버튼 숨김·
마우스/터치 회전·정면 복귀·작은 화면 및 기존 피로도 뷰 회귀 검사를 통과했다.
Android 제스처/실휴대폰 UI는 사용자 검증 범위다. 업데이트 설치만 수행한다.
증빙: `outputs/session-muscle-map/verification.json`, `deployment.json`.
상세: `docs/MUSCLE_LOAD_IMPLEMENTATION.md` §8.


## §84. 완료 근육도 빈 화면 · 참고 문구 수정 (2026-09-28)

§83 완료 뷰어가 Android 스크롤 컨테이너 안에서 stage 높이 0(100vh)을 가졌다.
PC 브라우저 검증만으로 발견하지 못했고, 실제 Compose 완료 화면을 에뮬레이터에
띄운 검사에서 재현했다. 고정 폴백 + innerHeight 픽셀 지정, 0 크기 투영 방지,
재표시/resize 동기화로 수정한다. 후면 복제 생략·Float32 직접 변환·픽셀 비율 제한·
프레임당 렌더 합치기로 완료 뷰어의 불필요한 작업을 줄인다.

완료 화면과 그 헤드라인 음성에서 ‘참고만 하세요’를 제거한다. REFERENCE는
중립적 기록 요약, 펼친 행은 ‘검증 중’ 표시를 사용한다. 판정 상태/점수는 유지한다.

요청 부분만: JVM 완료 문구 2 + 사용 근육 3건, Android 에뮬레이터 완료 화면
인체/강조 픽셀·높이400·터치 회전/부모 스크롤 불변 1건 통과. 일반 APK 빌드.
실휴대폰 UI/운동은 검증하지 않았으며 기존 앱 업데이트만 설치한다.
정본 `outputs/session-muscle-fix/verification.json`, 설치 `deployment.json`.


## §85. 피로도 드래그 보기 · 완료 근육도 제목 (2026-09-28)

홈에서 들어가는 피로도 페이지도 단일 인체 드래그 회전을 사용하고 앞/뒤/정면
버튼을 제거했다. 조작 여부와 색 입력 모드를 분리하여 피로도는 누적 점수,
완료 화면은 현재 세션 사용 부위 강조를 유지한다. 두 화면 모두 근육도 내 터치를
회전에 사용하고, 높이 픽셀 동기화와 최대 픽셀 비율/프레임 렌더 합치기를 공유한다.
완료 3D 위에는 ‘오늘 운동 사용 근육’ 제목만 추가했다. 설명이나 음성은 추가하지 않는다.

일반 APK 빌드 및 PC 실제 자산의 두 모드 색 반영/다른 모드 입력 무시/드래그/
정면 복귀/강조 해제/버튼 제거/작은 화면 검사를 통과했다. 이번에는 전체 테스트나
실휴대폰 화면 조작을 수행하지 않았다. 설치 정본 `outputs/fatigue-drag/deployment.json`,
PC 증빙은 같은 폴더의 `fatigue/viewer-verification.json`, `session/viewer-verification.json`.


## §86. 완료 카드 내부 제목 · 조건부 스크롤 · 양옆 입력 여백 (2026-09-28)

완료 3D 제목은 ‘사용 근육’으로 변경해 어두운 카드 내부 상단에 배치하고,
밝은 회백색(#F1F4EA)을 사용한다. 카드 양옆에는 최소 28dp의 WebView 밖 여백을
남기며 폭 상한은 340dp다. 이 여백은 부모 세로 스크롤 영역 안에 있어 3D 회전이
입력을 가로채지 않는다. 제목 영역 역시 WebView 밖이다.

완료 페이지의 실제 ScrollState.maxValue가 양수일 때만 세로 스크롤을 활성화한다.
내용이 모두 들어가면 비활성화하고 오버스크롤 효과도 끈다. 방향/기기 이름을
고정 조건으로 사용하지 않아 가로 모드·폴더블·큰 글씨·자세 상세 펼침 등 실제
높이가 부족한 경우를 함께 처리한다. 홈 버튼은 기존처럼 하단에 고정한다.

PC Android 에뮬레이터의 완료 화면 레이아웃 검사 1건과 실제 근육 렌더/회전 검사
1건을 통과했다. 합성 창 390×1100, 700×360, 800×600dp에서 들어가는 내용의
스크롤 0, 짧은 창의 양옆 드래그·끝 도달·큰 창 복귀 시 위치 0을 검사했다.
첫 390×900dp 합성 창은 시스템 인셋 포함 내용이 68px 넘쳐 정상적으로 스크롤이
열렸으므로 ‘모두 들어가는 경우’ 검증 크기를 1100dp로 조정했다. 실제 인체·색·
회전은 별도의 기본 밀도 창으로 확인했다. 일반 APK 빌드 통과. 물리 폴더블이나
실휴대폰 화면 검증은 수행하지 않고 기존 앱 업데이트 설치만 한다.

증빙: `outputs/session-layout/verification.json`, `layout-result.txt`, `android-result.txt`,
`portrait.png`. 설치 정본은 같은 폴더의 `deployment.json`이다.

## §87. 표시 메시 국소 보정 · TREX 범례 · 완료 제목 외부 배치 (2026-09-28)

사용자 요청에 따라 §86의 ‘사용 근육’ 제목만 카드 밖으로 되돌린다. 테마 글자색을
사용하고 12dp 간격을 둔다. 최대 340dp 모델 폭/양옆 28dp 입력 여백/높이 부족 시에만
스크롤하는 정책은 유지한다. 설명은 추가하지 않는다.

피로도 페이지의 타 서비스와 유사한 긴 회색→빨강 범례를 낮음·중간·높음의 작은
세 칩으로 바꾼다. 0/50/100 색 기준은 #DEDED9/#C7E26B/#759848이며, 실제 재질은
선형 색 공간에서 구간 보간한다. 0점 기본 회색, 완료 사용 부위는 같은 그린 최대
강조를 사용한다. 선택/세션과 누적 피로 분리/계산식은 변경하지 않는다.

실제 앱 정적 atlas를 Blender로 재구성해 돌출 위치를 확인했다. 둔부 사이의 둥근
하단은 둔근이 아니라 몸통 연결층이었다. 새 조각을 덧대는 대신 기존 연결층의
하단을 매끄럽게 올리고 후면을 안쪽으로 정리했다. 약지는 손 피부 메시 자체의
원위부가 옆으로 접혀 있었다. 같은 토폴로지의 정상 중지를 근위 약지에 정합한 후
원위부에만 점진 전사하여 손끝을 복원했다. 양손 대칭, 손바닥 방향은 유지한다.

`research/muscle_load/polish_surfaces.py`를 정적 베이크 직후 적용한다. 매번 수정 전
소스로부터 재생성하므로 보정이 누적되지 않는다. 손 토폴로지가 달라지면 assert로
중단한다. 메시 이름/면/근육 매핑과 나머지 167개 부품은 바이트 기준 동일하다.
수정 대상은 손 176정점씩, 연결층 266정점이다. 수정 세 표면의 열린 모서리와
영면적 삼각형은 0이며, 원래 손에 있던 비다양체 모서리 3개씩은 그대로다.

원본 앱 atlas는 `outputs/muscle-surface-polish/source-backup/atlas.js`, 수정된 정적
편집 파일은 `after/trex-app-muscles.blend`에 저장한다. 원본 리그와 3종 운동 클립은
수정하지 않았다. Blender 동일 회색/조명/배율의 골반 정면·후면·사선 및 손 3시점,
PC Chromium 실제 자산 두 모드 색 반영/해제/회전 검사를 통과했다. 일반 APK 빌드
통과. Android 계측 테스트의 색 판정은 그린으로 갱신했지만 이번에는 재실행하지
않았다. 실휴대폰은 실행/화면 검증 없이 백업 후 업데이트 설치만 한다.

검사 정본: `outputs/muscle-surface-polish/geometry-verification.json`,
`viewer/{session,fatigue}/viewer-verification.json`, `deployment.json`.
전후 비교는 `pelvis-comparison.jpg`, `hand-comparison.jpg`. 이는 표면 형태와 국소
위상 검사이며 전문 해부 검수나 전체 모델의 전수 자기교차 검사는 아니다.

## §88. 피로도 범례를 10dp 스펙트럼 바로 변경 (2026-09-28)

사용자 후속 요청으로 §87의 세 단계 칩을 연속 스펙트럼 바로 교체한다. 두께는
기존 색상 네모와 같은 10dp, 모서리는 5dp다. 회색→라임→그린 색은 유지하고
글자는 바 아래 8dp 간격으로 왼쪽 ‘낮음’, 오른쪽 ‘높음’만 표시한다. ‘중간’은
제거한다. 3D 색상과 피로 계산, 완료 화면, 입력 처리는 변경하지 않는다.
일반 APK 빌드로 오류를 확인하고 실휴대폰은 화면 조작 없이 업데이트 설치만 한다.
설치 증빙: `outputs/muscle-spectrum/deployment.json`.


## §89. 운동 시작 전 촬영 범위 확인 완화 (2026-09-29)

사용자 보고: 운동 시작 전 신체 관절이 모두 보여야 시작하는 판정이 너무 보수적이어서 시작이 오래 걸린다.

*실측(폰 로그 38세트 첫 40프레임을 옛 규칙으로 재현).* 통과율이 정면 75 %, 45° 사선 **3 %**, 옆 0 % 였다.
- 발끝(31·32)의 가시도 중앙값이 사선에서 0.03~0.04 였다.
- 컬 세트는 다리가 화면 아래 바깥(무릎 y 1.2~2.5)이었다.
- 스쿼트는 발목이 아래 끝(y 0.99~1.01)에 걸렸다.

옛 흐름은 다음과 같았다. 이미 서 있는 사용자도 약 17초가 걸렸다.
1. 안내 음성이 끝날 때까지(최대 12 s) 프레임을 보지 않는다.
2. 머리와 양쪽 14관절(발끝 포함)이 한 프레임에 가시도 0.55 이상으로 보이고, 모두 4 % 안쪽에 있어야 한다.
3. 몸 전체 테두리가 0.4 s 안정해야 한다.
4. 5초 카운트다운을 세고, 1.2 s 끊기면 처음으로 돌아간다.

*바꾼 것.* `CapturePreparation.kt`·`CapturePreparationUi.kt` 두 파일이다.
1. **운동이 쓰는 관절만 요구한다.**
   - 서서 하는 팔 운동(`ExerciseProfile.framingRegion` = 무릎·골반 피처가 없는 서서 종목)은 상체(어깨·팔꿈치·손목·골반)만, 그 밖은 전신(무릎·발목 추가)이다.
   - 발끝은 요구하지 않는다(스쿼트 발끝 검사는 발끝이 없으면 유보된다).
   - 사선은 가까운 쪽 사슬 전부 + 먼 쪽 어깨·골반(전신이면 발목)을, 옆은 한쪽 사슬을 본다.
   - 가시도 기준은 0.5 다.
2. **안내 음성과 판정을 겹친다.**
   - 음성이 나오는 동안에도 범위·안정을 재고 카운트다운만 보류한다(`holdStart`). 끝났을 때 이미 준비돼 있으면 바로 3초를 센다.
   - 같은 운동의 긴 안내는 30분에 한 번만 한다(`PreparationIntro`, 준비 패널이 세트마다 새로 만들어지므로 프로세스 범위에 둔다).
   - 대기 안내 발화는 안내 음성이 끝난 뒤에만 한다.
   - 자동 카운트다운은 3초다. 직접 시작(5초 후 시작)은 그대로다.
3. **가장자리를 부위별로 본다.** 머리·손은 4 %, 그 밖은 2 % 안쪽이고, 발목은 화면 아래 끝까지 허용한다. 상체 범위의 최소 높이는 0.2 다.
4. **안정·움직임은 몸통(어깨·골반) 테두리로만 본다.** 카운트다운 중 끊김 허용은 2.5 s 이고, 그 사이는 멈췄다가 이어서 센다.
5. **방향 확인은 사선을 55° 까지 본다**(반복 검사 `REP_OBLIQUE_MAX_DEG` 와 같다). 방향이 2 s 넘게 이어서 틀릴 때만 막고, 한 프레임 가림으로 방향 창을 비우지 않는다.
6. **못 보면 무엇이 잘렸는지 말한다.**
   - "발이 화면 아래로 잘렸어요. 휴대폰을 조금 뒤로 두거나 낮춰 주세요."
   - "왼손이 안 보여요 …"
   - "머리가 화면 위로 잘렸어요 …"
   - "몸이 화면 가장자리에 걸려요 …"
   - 좌우는 사용자 기준이다.

*재현(같은 로그, Python 근사).* 덤벨 컬 33 % → 74 %, 옆 0 → 45 %, 스쿼트 74 → 79 %.

*지킨 선.* 판정에 쓰는 관절이 빠진 채 시작하면 그 검사는 유보되고 유보는 정상으로 세지 않는다(원칙 #1). 이 절은 시작 문턱만 낮췄다. 판정·임계·카운터는 바꾸지 않았다.

**§89 후속 1 — 멈춘 이유 말하기 · 카운트다운 "1" 에서 처음으로 돌아가던 문제 (2026-09-29)**

사용자 보고는 두 가지다.
1. 타이머가 멈추거나 처음으로 돌아갈 때 어디가 안 보였는지 말하지 않는다.
2. "1" 까지 세고 운동 화면으로 넘어가지 않는다.

*원인 1.* 멈춤 사유가 음성으로 나오지 않았고, 화면에는 배치 안내만 떠 있었다. 처음으로 돌아가면 "안내한 방향으로 다시 자리 잡아 주세요." 만 말했다.

*원인 2.* 폰 음성 추적(`feedback-20260929.jsonl`)에서 네 번 모두 같은 순서였다.
1. "3·2·1" 이 나온다.
2. 1초 안에 멈춘다.
3. 약 5 s 뒤 배치 안내("왼어깨가 휴대폰에 가까워지도록…")가 나온다. 이것이 방향 확인의 문구다.

- 세트 로그 요는 −18~−23° 였다. 왼쪽 앞(D)이지만 정면 경계 16.4° 근처다.
- 8프레임 평균이 경계를 넘나들어 불일치 2 s 가 카운트다운 3 s 의 끝에 걸렸다.

*고친 것.*
- **멈춘 이유를 알린다.** 멈추면 이유(안 보인 부위·"몸이 많이 움직였어요"·"몸의 방향이 바뀌었어요"·"몸이 화면에서 벗어났어요")를 0.6 s 이어질 때 말하고, 화면에 경고색으로 띄운다.
- **처음으로 돌아가면 그 이유를 바로 말한다.** 문구는 "{이유} 다시 자리 잡으면 3초를 셉니다." 다.
- **방향 확인은 분명히 틀린 방향만 막는다.**
  - 사선 요청: 반대쪽 사선이거나 55° 넘게 돌아섰을 때
  - 정면 요청: 30° 넘게 돌아섰을 때
- **방향 확인은 카운트다운 전에만 한다.** 카운트 중 몸을 돌리면 움직임 판단(방향 변화)이 멈춘다.

**§89 후속 2 — 첫 회를 버리지 않게: 준비 단계에서 서 있는 기준 심기 (2026-09-29)**

사용자 보고는 "처음 1회 할 때 계산이 오래 걸린다" 였다.

*확인한 것.* 계산이 느린 것이 아니라 첫 회가 버려진다. 폰 로그에서 바닥 → 카운트 지연은 첫 회와 나머지가 비슷했다(스쿼트 1.3 대 1.7 s, 런지 1.4 대 1.7 s).
- 복귀형(레거시) 카운터는 신호가 3프레임(≥ 0.3 s) ±0.22 × 진폭 안에 가만히 있어야 서 있는 기준을 잡는다.
- 1회는 기준에서 35° 넘게 벗어났다가 기준 ±7.7° 로 돌아와야 한다.
- 그래서 카운트다운 직후 바로 내려간 첫 회는 기준 전이라 버려진다. 1.5 s 넘게 화면을 벗어났다 돌아와도 기준이 지워진다.
- 09-25 런지 세트: 첫 걸음(16~20 s)과 화면 밖에서 돌아온 첫 걸음(58 s)이 빠져 첫 숫자가 63 s 였다.

*바꾼 것.*
- `PostureLive` 가 준비 단계의 최근 프레임(서서 하는 종목, 최대 12개)을 남긴다.
- 운동 첫 프레임에서 `RepCounter.standingSeedFrom` 이 기준값을 정한다. 조건은 첫 프레임 앞 1.5 s 안의 카운트 신호가 3개 이상이고 모두 0.22 × 진폭 안에 모여 있는 것이고, 값은 그 중앙값이다.
- `seedStanding` 이 그 값을 복귀형 추적기의 기준으로 심는다. 움직이고 있었으면 심지 않는다(종전과 같다).
- 팔별 경로(덤벨 컬)·새 코어는 건드리지 않는다.
- 로그는 `reps.config.seed` 다. 재생기가 첫 프레임에 같은 값을 심는다(`setlog_captures` → 메타 `loggedSeed`).
- 화면 밖에서 돌아온 뒤의 기준 재설정은 그대로다(남은 것).

## §90. 첫 반복의 모집단 사전값 · 대사 점검 (2026-09-29)

사용자 결정(2026-09-29): 첫 1회는 틀려도 1회로 쳐 준다.
- 여러 자료(AIHub·MM-Fit·REHAB)로 평균 기준값을 만든다.
- 첫 회가 그 기준에 부합하면 기준값에 넣어 조정하고, 이렇게 3회까지 간다.
- 평가에 따른 수정안(평균이 아니라 범위, 여유는 처음 넓게, 기준이 선 뒤 평균은 뺌, 갇힘 방지)으로 스쿼트·컬·런지에 적용한다.
- 서비스가 보는 모든 틀린 자세 경우의 대사를 점검한다.

*왜 평균 하나로는 안 되나.* 사람마다 수렴한 본인 기준을 세트별로 쟀다(MM-Fit·REHAB 정상 반복).

| 검사 | 사람 간 퍼짐 | 오류 임계 | 판단 |
|---|---|---|---|
| 컬 앞 이탈 | 0.006~0.024 | 0.12 | 평균으로도 된다 |
| 런지 숙임 (B) | 5.7° (7~28.5°) | 20° | 평균으로는 틀린다 |
| 컬 정면 숙임 | 5.4° | 15° | 평균으로는 틀린다 |
| 컬 정면 옆 벌림 | 0.069 | 0.15 | 평균으로는 틀린다 |

- 같은 사람 안의 흔들림은 사람 간 퍼짐의 1/2~1/5 다. 개인 기준을 쓰는 이유다.
- 사용자 폰 런지의 정상 숙임은 −3° 로, MM-Fit 중앙값 17° 와 20° 차이가 났다(촬영 기하). 평균 기준이면 숙인 걸음(+28~37°)을 +10~17° 로 읽어 놓친다.
- AIHub 는 스튜디오·키프레임·가로 영상이라 사전값 생성에 쓰지 않는다(검출 확인용으로만 쓴다).

*사전값.*
- `research/external_rep_replay/rep_priors.py` 가 생성 파일 `posture/RepFormPriorTable.kt` 를 쓴다(손으로 고치지 않는다).
- 원천: 앱과 같은 검사기로 재생한 MM-Fit·REHAB('올바름') 정상 반복.
- 검사 × 뷰마다 담는 값:
  - 수렴한 본인 기준의 중앙값
  - 사람 간 퍼짐(1.4826 × MAD)
  - 기준 범위(최소~최대, 중앙값 ± 4 퍼짐으로 자른 뒤 ± 0.5 퍼짐)
  - 정상 원값 범위(0.5~99.5 %)
- 세트가 8 미만인 뷰는 두지 않는다. 좌우 대칭 피처(가까운 팔·몸통각)만 거울 뷰(B↔D 등)를 합친다. 어깨 높이차는 원근 부호가 뒤집혀 합치지 않는다.
- 바벨 컬·바벨 런지는 대리 종목의 값을 쓴다.

*엔진(`RepFormEvaluator`).*
1. **잠정 판정.** 처음 N회·세트 하한 기준(FIRST_REPS·SET_LOW)은 기준이 서기 전 반복을 유보(= 통과)했다. 이제 다음과 같이 판정한다(`warmup`, 로그 `warm`).
   - 잠정 기준 = (사전 기준 + 받아들인 본인 원값 합) ÷ (1 + n)
   - 여유 = 2 × 퍼짐 ÷ √(1 + n) — 처음엔 넓게, 쌓일수록 좁게.
   - 시작 자세·세트 최소 기준(START·SET_MIN)은 종전처럼 기준이 없으면 유보한다. 사전값으로 판정하면 정면 컬 첫 회(덤벨 집기, 월드 몸통각 72~110°)가 차단 오탐 4건이 됐다.
2. **기준 입장.** 위반 쪽 원값 범위 밖이거나 잠정 판정 위반인 반복은 본인 기준 모음에 넣지 않는다.
   - 반대쪽 밖은 받아들인다. 폰 D 사선에서 사용자 정상 '앞 이탈' −0.2~−0.3 이 MM-Fit 범위 밖이라 막혔고 헛알림이 났다.
   - 기준을 모으는 중에 같은 쪽으로 3번 이어지면 그 사람의 자세로 받아들이고 세트에서 한 번 알린다(갇힘 방지). 기준이 선 뒤의 위반 반복은 넣지 않을 뿐이다.
3. **자르기.** 본인 기준은 위반을 숨기는 쪽만 사전 기준 범위 끝에서 자른다. 높을수록 위반인 검사는 위에서, 낮을수록 위반인 검사는 아래에서 자른다. 종전 `refCap` 의 일반화다.
4. **처음부터 알림.** 전용 띠가 없는 ship 검사도 본인 기준이 사전 범위 밖이면 세트에서 한 번 알린다.

*재생 결과.*

모집단 정상 반복(표본 안):

| 검사 | 판정한 반복 | 차단 오탐 |
|---|---|---|
| 스쿼트 전체 | 변화 없음 | 변화 없음 |
| 컬 앞 이탈 | 67 → 184 | 0.5 % |
| 컬 몸에서 떨어짐 | 101 → 183 | 0 |
| 런지 숙임 | 310 → 414 | 0.2 % |
| 런지 어깨(beta) | 122 → 229 | 0 |

폰 09-26 컬 12세트:
- 12:41 세트(처음부터 벌림): 1·2회가 위반·회 제외로 잡히고, 알림이 한 번 나온다. 종전에는 기준 반복이라 유보됐고 '앞 이탈' 기준을 오염시켰다.
- 12:36 세트: 원값 0.42~0.47 로 '떨어짐' 알림.
- 나머지 10세트: 헛알림 0.

*대사 점검.* `docs/exercises/FAULT_LINES.md` 는 `fault_lines.py` 가 코드에서 뽑은 생성 문서다.
- 대상: 스쿼트 11·컬 9·런지 4 반복 검사, 창 규칙 5, 그 밖 9 상황.
- 빠진 대사를 `RepFormSpecs.withLines`(계열별 채우기 표)로 채웠다.
  - 짧은 단서: 스쿼트 11 전부, 컬 beta 4
  - 처음부터 알림: 본인 기준 ship 검사 전부(스쿼트 상체·발 간격·발끝, 컬 상체·앞 이탈, 런지 숙임)
- `RepFormLinesTest` 가 모든 검사에 방향별 문장·고치는 말·단서, 본인 기준 ship 검사에 처음부터 알림이 있는지 지킨다.
- beta 검사(스쿼트 7·컬 4·런지 1)와 beta 창 규칙은 문장은 있지만 음성으로 말하지 않는다(원칙 #2). 폰 지정 오류 세트로 검출률을 재면 ship 으로 올린다.

## §91. 세트 로그의 촬영 배치 지문 (2026-10-05)

사용자 결정(2026-10-05): 높이·거리·화각 보정의 첫 단계로 **배치 기록**부터 한다(`docs/SQUAT_CAMERA_RESEARCH.md` §9 P1).

*왜.* 같은 동작도 폰 높이·거리·기울기가 다르면 측정값이 움직인다(A1 모의: 바닥 폰 발끝 +21~24°·깊이 −6~−8°, 1 m 폰 깊이 +13°). 피처만 있는 로그로는 "자세가 달랐다" 와 "배치가 달랐다" 를 못 가른다. 세션 간 비교는 배치 지문이 같을 때만 하고(§6-1), 배치별 기준값(AIHub 3D 정답을 그 배치로 가상 촬영)도 이 값이 있어야 만든다. 3일 실기기 테스트(`docs/DEVICE_TEST_SCHEDULE_3DAYS.md`) 로그를 뒤에 보정 연구에 그대로 쓰기 위해 먼저 넣는다.

*무엇을 남기나.* 세트 로그 헤더의 `placement` 블록(부재 = 이전 로그). 정의는 `posture/CameraPlacement.kt`.

| 필드 | 값 | 근거 |
|---|---|---|
| `frames` | 피처 있고 up 이 IMU 에서 온 프레임 수 | 0 이면 각도는 null |
| `pitch_deg`·`roll_deg`·`tilt_deg` | 프레임 up 의 **중앙값**. 피치 = asin(−up_z)(+ = 카메라가 위를 봄), 롤 = atan2(up_x, up_y) | A4_3_geometry.py 규약. 헤더 `tilt_deg` 는 마지막 프레임 값이라 폰을 집어 든 각이 찍혔다(A4 §8 #5) — 그 필드는 호환을 위해 그대로 두고 비교에는 이 블록을 쓴다 |
| `tilt_max_deg` | 세트 중 기울기 최대 | 중앙값과 크게 다르면 세트 중 폰이 움직였다 |
| `fps`·`infer_ms_med` | 실효 추론 주기(Hz)·추론 시간 중앙값 | A4 "실효 3.0 Hz" 를 세트마다 |
| `lens{focal_mm,sensor_w_mm,sensor_h_mm,active_w,active_h,zoom}` | Camera2 특성(`CameraLens.kt`, 바인딩 직후) | 없으면 null |
| `f_px` | focal_mm / 센서 긴 변 mm × 이미지 긴 변 px × zoom | 4:3 분석 스트림이 센서 긴 변을 크롭 없이 쓴다고 가정. 16:9 센서를 자르는 기기는 과소 |
| `distance_m` | f · 월드 어깨 폭(m) · `scale` / 이미지 어깨 폭(px, 두 점 거리라 롤과 무관) 의 중앙값(양 어깨 vis ≥ 0.5) | A4 교차 확인 (c). MediaPipe 월드 척도는 평균 체형 가정 — 실제 키가 x % 크면 거리도 x % 크다 → 프로필 키로 맞춘다 |
| `height_m` | 0.08 + distance · tan(atan((발목 행 − c_y)/f) − 피치). 발목 행은 그 프레임의 up 으로 **롤을 되돌린** 좌표에서 읽는다 | A4 (b). 서서 하는 종목만(바닥 종목은 null). 사용자 결정 2026-10-05 "기울기도 반영" |
| `subject_height_cm` · `scale` | 프로필 키와 월드 척도 배율 = (0.818 × 키 − 0.08) / 서 있는 프레임(상위 10 %)의 월드 발목→어깨 up 성분 | 사용자 결정 2026-10-05 "사용자 정보의 키를 기준으로". 0.818 은 견봉 높이/키의 인체 측정 평균 비. 키가 없거나 바닥 종목이거나 발목·어깨가 보이는 프레임이 5개 미만이면 null(배율 1) |

*기록하지 않는 것.* 노출·센서 실제 fps — CameraX 가 캡처 결과를 노출하지 않는다. 키는 `TrexStore.loadProfile().heightCm`(온보딩 입력, 기본 170) 을 세션 시작 때 `LiveSessionRefs` 에 담아 쓴다 — 거대 Composable 의 지역 변수를 늘리지 않는다.

*검증.* `CameraPlacementTest`(핀홀 합성: 거리 2.0 m·높이 0.7 m·피치 0°/5° 되돌림, 롤 15° 되돌림, 월드 척도 1.2 배를 키 170 cm 로 1/1.2 배율 복원, 바닥 종목 높이·배율 생략, 렌즈·IMU 없을 때 부분 기록, 세트 끝 집어 듦이 `tilt_max_deg` 에 드러남) + `PostureSetLogTest.placementBlockCarriesImuMediansLensAndEstimates`. `:app:testDebugUnitTest` 533개 통과·`assembleDebug` 성공(2026-10-05). 실기기 값(거리·높이)은 줄자로 잰 값과 대조한 적이 없고, 0.818 비·발목 8 cm 는 평균값이라 체형에 따라 수 % 오차가 남는다.

*읽기.* `pull_logs.py` 요약이 세트마다 피치·롤·기울기 최대·거리·높이·Hz 를 한 줄로 찍는다.

## §92. 크로스·사이드 런지·니업·사이드 크런치 개발 설계 (2026-10-06, 미구현)

사용자 요청: 네 종목의 필요한 관절·횟수 판정·오류 범위부터 개발과 실검증까지 검토하고 `C:/Users/hp276/Desktop/trex/data`를 먼저 조사한다. 상세 설계 정본은 [FOUR_EXERCISE_POSTURE_PLAN.md](../../docs/FOUR_EXERCISE_POSTURE_PLAN.md)다.

*근거.* ZIP 내부까지 대조한 로컬 Training 라벨은 크로스 350/42명, 사이드 1,404/42명, 니업 1,172/56명, 사이드 크런치 1,611/49명(총 4,537클립, 수행자는 종목 간 중복). 각각 2D/3D 원본과 MP 프레임 캐시가 있다. MP 집계 60클립/종목은 기존 규칙을 선택한 개발 자료이고, 클립 상태 라벨은 반복 시각·쪽별 폼 정답이 아니다. 해당 `data/phone`에는 네 종목 세트 로그가 없다. 현재 ‘무릎 높이’의 무릎각 range·‘상체 균형’의 머리각 max·‘척추 중립’의 한쪽 어깨 높이 등의 프록시를 새 반복 검사의 직접 근거로 쓰지 않는다. 사이드 런지 라벨의 반대 무릎 90도와 정상 펴기의 충돌도 재주석 대상이다.

*설계.* 관측 품질 → 좌우 개별 사이클 후보 → 동작/쪽 식별 → 해당 반복 창 자세 → 표시/목표 정책을 분리한다. 양 어깨/골반/무릎/발목이 공통 필수이고, 크런치에는 같은 쪽 팔꿈치, 발 방향/들림에는 heel/foot index, 손/머리에는 손목/얼굴 점이 추가된다. MP에 없는 Back/Waist·근 긴장·접지 하중·실제 척추 분절 상태는 exclude 범위다. 준비에 기준을 모으고 실제 복귀에서 마지막 반복을 확정하며 종료 자체로 미완료 후보를 세지 않는다. 정상 다리 교차·측굴을 오류로 만들지 않는다.

*사용자 확정.* 니업·스탠딩 사이드 크런치는 한쪽 올림·복귀 = 1회. 크로스·사이드 런지는 좌우 한 번씩 = 1회 유지. 런지의 확정 좌/우 원장에서 `min(N_L,N_R)`로 쌍을 표시하고, 쪽 미확인을 순서 추정으로 확정 원장에 배분하지 않는다. 단위/템포/음성/기록/자가 라벨은 같은 사건을 사용한다. COACH/TRACK의 임계값은 동일하고 정책만 다르다.

*개발/검증 계획.* 니업 → 사이드 → 크로스 → 사이드 크런치. 오류 43항목과 유보/제외를 START/진행/EXTREME/RETURN에 연결한다. 신규 규칙은 beta로 시작하며 기존 `ship` 성능 수치를 승계하지 않는다. 수행자 분리 오프라인 분석 → 전문 독립 반복/오류 라벨 → 사용자가 촬영하는 폰 Gate A → 피처/뷰/임계/빌드를 고정한 새 사람 Gate B. 정확 횟수뿐 아니라 과다·마지막·좌우·조기 종료·정상 오탐·음성·유보율을 함께 채점한다. 지원 밖 뷰/배치/관절 누락은 정상으로 판정하지 않는다.

*이번에 한 것.* `four_exercise_audit.py`로 라벨·ZIP·MP 캐시·현재 규칙·data/phone 로그를 감사해 `outputs/four-exercise-design/local-audit.json`에 저장했다. 설계와 재현 스크립트만 추가했다. 앱 코드/규칙 수치·빌드·실휴대폰은 변경하거나 실행하지 않았다. 다음 작업은 프로필별 정상 변형 확인·분할/라벨/지표 사전 등록(P0)과 관절 피처 파리티(P1)다.

## §93. 네 종목의 관측·좌우 복귀·반복별 beta 검사 구현 (2026-10-06)

§92 이후 사용자의 구현 요청에 따라 `redesign`의 `99f7a39d` 기반 작업 폴더에 개발판을 구현했다. 작업 폴더는 해당 커밋의 detached HEAD이며 다른 worktree가 점유한 로컬 redesign을 이동시키지 않았다. 상세 정본은 [설계 문서 §9](../../docs/FOUR_EXERCISE_POSTURE_PLAN.md#9-개발판-구현-결과-2026-10-06-93)다.

*카운트.* `FourExerciseGeometry`는 양 어깨/골반/무릎/발목을 모두 요구한다. 무릎각·중력 기준 허벅지각, 준비 축에 투영하는 발목 상대 위치, 준비 이미지 위치 대비 발 이동을 사용한다. 크로스의 working side는 앞 지지 다리, moving side는 그 반대다. 전방 교차를 뒤 교차로 부르지 않도록 실제 움직인 발을 이미지에서 반증한다. 사이드는 스텝 후 복귀형만 지원한다. 니업/크런치는 독립 다리 후보를 쓰며 평균각과 컬의 가림 대체 정책을 사용하지 않는다. 다음 하강 없이 같은 다리/발의 복귀에서 발표한다. 런지 표시/템포는 확인된 좌우를 짝짓고 미확인은 쌍·완료·전환 음성에 넣지 않는다. 두 들기 종목은 한쪽 올림·복귀가 1회다.

*관측/기준.* 안정 준비 3프레임·400 ms, 준비 무릎/허벅지 145° 이상; 이탈 25°, 복귀 띠 10°·120 ms, 최대 후보 15 s, 공백 750 ms. 이들은 검출용 잠정 상수이지 정상 폼 허용치/정확도 보장이 아니다. 준비 countdown의 최근 안정 피처를 받아 `reps.config.four_seed`에 저장하고 replay가 같은 기준을 심는다. 발 위치 분모는 준비 다리 길이/이미지 몸통 길이다. 관절 누락·비유한값·마디 길이 급변·어깨 이름/방향 급반전·준비 축에서 45° 넘는 회전·일시정지는 진행 후보를 버린다. 완료 원장은 보존한다. 미완료 종료는 카운트하지 않는다. 모든 카메라 이동·MP 이름 오류를 완전 검출한다는 뜻은 아니다.

*자세/정직성.* 반복별 후보는 크로스 4, 사이드 5, 니업 5, 크런치 8 = **22 beta**다. 실제 극점 창에서 각도·몸통 전후/옆 기울기·어깨/골반 상대 회전·같은 쪽 팔꿈치 거리·각 손 위치를 본다. 크런치의 정상 측굴에 오류 상한을 적용하지 않는다. 뷰 C/B/D만 검사별 허용, UNKNOWN/SIDE/쪽 모름/2프레임 미만/관측률 60% 미만은 ABSTAIN한다. 새 임계는 초기 개발값이며 기존 AIHub AUC/FPR을 승계하지 않는다. 기존 네 종목 창 규칙은 `plusRepForm()`에서 EXCLUDE로 보존한다(원본 JSON 변경 없음). 종목별 국소 발/무릎 정렬, 접지·미끄러짐, 척추/목 분절·힘/근 활성은 추가 scope EXCLUDE다. beta는 음성·점수·횟수 차감에 쓰지 않는다. 새 폼의 정확 수는 0이고 로그는 `correct:false,form_state:UNJUDGED`다. raw 반복을 잘못된 자세로 삭제하지 않는다.

*통합.* `RepSignals`/`RepCounter.forSession`에서 앱·재생기가 같은 경로를 만든다. 앱 진입 map은 이미 네 종목을 포함한다(`PostureSupport.kt`). 프로필의 두 런지 단위는 `SIDE_EACH`, 두 들기는 `CYCLE`; 사이드 크런치는 `REPS`로 등록했다. 기존 전방 런지의 80도 안내가 네 종목에 넘어가지 않도록 준비 문구를 분리했다. 원시 사이클에는 side, 폼 로그에는 moving_side/side_state/form_state를 기록한다. 두 들기 HUD에도 좌우/미확인 수를 보여 준다. 일반 런지의 과거 추정 배분은 변경하지 않고, 두 새 런지에만 `strictUnknown=true`를 적용한다.

*검증.* Android JVM **557/557**, 독립 replay JVM **71/71**, Python 채점기 **3/3**, debug APK 빌드 통과. 신규 앱 테스트 24건은 상태/기하/단위/유보/로그 계약이다. 캐시 MP **18,813프레임**을 Kotlin 공용 피처로 다시 계산: core 관측 4,595/4,800(크로스), 4,679/4,768(사이드), 4,371/4,667(니업), 4,228/4,578(크런치). 모든 라벨 정상인 MP 클립은 각각 9/1/5/0이므로 정상 오탐률 검증으로 삼지 않는다. 자료/명령은 `four_exercise_probe.py`와 `four_exercise_evidence.json`이다. 실휴대폰/UI/운동 정확도 검증과 설치는 하지 않았다.

*실검증 도구.* `four_exercise_validation.py plan`이 3명×4종목×11상황=132세트의 빈 촬영 계획과 독립 정답 CSV를 만든다. `score`는 replay JSONL의 발표 사건을 정답 복귀 시각에 일대일 대응해 과다/누락/좌우 커버리지·정확도/표시 횟수 오차를 계산한다. 정답 없음·예측 누락·런지 좌우 정답 없음은 성공/0회로 채점하지 않는다. 이 도구는 자세 22검사의 참/거짓 정답이나 ship 승격을 자동으로 만들지 않는다. §92의 전문가 오류 주석·새 사람 Gate B는 남아 있다.

## §94. 실시간 운동 추론 간격 85 ms (2026-10-06)

사용자가 움직임 반영 지연을 줄이기 위해 실시간 간격을 300 ms에서 85 ms로 변경하도록 요청했다. `PostureLive.SESSION_SAMPLE_INTERVAL_MS`를 85로 바꾸어 준비·운동 추론과 세트 로그의 `sample_interval_ms`에 함께 적용한다. 명목상 최대 약 11.8 Hz이며 실제 속도는 추론 시간·카메라 프레임 도착·OS 발열 감속에 따라 낮아진다. 촬영 배치 기록의 `placement.fps`로 실효 주기를 확인한다.

일시정지의 400 ms, 랩·개인 기준선의 300 ms와 기존 발열 배수는 유지한다. 화면 보간은 원래 실제 샘플 도착 간격(80~400 ms)을 따르므로 새 주기에 자동 적응한다. 규칙 임계값·횟수 정책은 바꾸지 않는다. 프레임 수로 정한 창의 실제 시간 폭은 짧아질 수 있으므로, 기존 300 ms 데이터의 판정 정확도가 85 ms에서도 검증됐다고 간주하지 않는다. 실기기 반응 속도·발열·판정 정확도는 사용자의 운동 로그로 확인할 항목이다.

## §95. 별도 작업 폴더의 네 종목 운동 가이드 복구 (2026-10-06)

사용자가 추가한 가이드가 설치 후 없어졌다고 알려 확인했다. `codex/redesign-exercise-guides` 작업 폴더에는 네 종목 가이드가 미커밋으로 남아 있었고, §93·§94 APK는 이를 포함하지 않은 현재 작업 폴더에서 빌드되어 기기의 기존 가이드를 대체했다. 원본 `ExerciseGuides.kt`, 기존 가이드 테스트, 네 전용 GIF, 자산 README와 `EXERCISE_GUIDE_UI.md`를 그대로 복사하고 8개 파일의 SHA-256 일치를 확인했다. 원본 폴더는 수정하지 않았다.

크로스 런지·사이드 런지·스탠딩 니업·스탠딩 사이드 크런치의 가이드/주의사항·시작 자세·운동 동작·호흡·GIF를 기존 등록 경로로 복원한다. 원본 문서의 §91은 해당 작업 폴더의 번호이며, 현재 정본의 촬영 배치 §91과 구분한다. 가이드 설명/자산 출처와 한계는 `docs/EXERCISE_GUIDE_UI.md`의 해당 절을 따른다. 자세 엔진과 85 ms 추론 설정은 보존한다. 향후 폰 업데이트 전에 동일 기기에 설치한 다른 작업 폴더의 미커밋 기능을 확인해야 한다.

## §96. 한 발 떠남 계열 — 발 이탈 추적기와 반복 검사 (2026-10-06, 브랜치 `claude/exercise-form-correction-5fe80e`)

§93 개발판을 설계 `docs/SINGLE_LEG_FAMILY_DESIGN.md` 대로 바꿨다. 종목 함정은 `docs/exercises/single_leg.md`.

*왜.* 네 종목(크로스 런지·사이드 런지·스탠딩 니업·스탠딩 사이드 크런치)은 전부 "한 발이 준비 위치를 떠났다가 같은 자리로 돌아온다" 는 한 사건이다. §93 은 종목별 신호(두 무릎 최소값·허벅지각)와 별도 상태 기계·별도 평가기를 두어
무릎 최소값이 다리 사이에서 뒤집히는 가짜 사이클, 관절 한 프레임 누락에 기준선이 지워지는 문제, 뷰 잠금·사전값·2단 차단이 빠진 평가가 생겼다(2026-10-06 코드 리뷰).

*카운트(`posture/FootExcursion.kt`).* `ExcursionGeometry.features` 가 프레임마다 중력 기준 허벅지각 `thigh_L/R`(180° = 내림)·어깨선−골반선 요 `rel_yaw`·같은 쪽 2D `hand_ear_*`·`elbow_knee_*`·롤 보정(IMU up, `CameraPlacement` 와 같은 식) 2D 발목/무릎/골반 원값(`ex_*`)을 낸다.
`FootExcursionTracker` 는 두 발이 0.4 s 이상 가만히 있을 때(준비 카운트다운 `prepare`, 또는 세트 중 정지) 발목·무릎·골반의 중앙값을 **앵커**로 잡고, 발마다 `foot_move = |발목 − 앵커| ÷ 앵커 몸통` 이 0.25 넘게 떠났다가 0.12 안으로 돌아와 150 ms 머물면
사이클을 발표한다(`RepCycle.side` = **움직인 발**). 복귀마다 두 발을 착지점으로 재앵커한다. 관절 누락 프레임은 건너뛰고 750 ms 공백은 진행 후보만 버린다(앵커 유지). 두 발이 함께 떠나면(`both_moved`·`support_moved`) 둘 다 버리고 앵커를 지운다. 10 s 안 돌아오면 `timeout`.
판별(원칙 #7)은 극점 벡터(그 발의 바깥쪽·위쪽, 몸통 단위; 바깥쪽 부호는 앵커 때 어깨 x 차 `ex_face_dx` 로)와 무릎·허벅지 굽힘으로 — 사이드: 바깥 ≥ 0.40·높이 ≤ 0.15·(무릎 ≥ 20° 또는 골반 하강 ≥ 0.08), 크로스: 반대 발목을 ≥ 0.05 넘어 교차·높이 −0.10~0.25·하강, 니업: 위 ≥ 0.35·옆 ≤ 0.6×위·무릎 ≥ 40°·허벅지 ≤ 140°, 크런치: 위 ≥ 0.20·무릎 ≥ 40°. 기각 사유는 `reps.rejected[].feature`, HUD "판별 못 한 동작 n".
전부 잠정값이다. `RepSignal.excursion`·`RepCounter.excursionTracker` 가 §93 의 훅 자리를 그대로 쓴다(`effectiveMaxGapMs` 750, 불응기 0, 복귀 완료). 단위: 런지 둘 `SIDE_EACH`(`SideStepCounter.strictUnknown` — 쪽 미확인은 짝에 넣지 않음, `RepUnitAccumulator.offerSide`), 들기 둘 `CYCLE`. 쪽 이름 반증은 `Lunge2d.NAMES_OK`.

*자세.* 별도 평가기를 없앴다. `RepFormSpecs.legExcursion(profile)` 의 검사가 `{moving}`/`{support}` 틀을 갖고 `RepFormEvaluator.onCycle(…, cycleSide)` 가 사이클마다 푼다(`RepFormCheck.resolved`, 창 신호도 `knee_{support}`·`knee_{moving}`·`thigh_{moving}`) — 뷰 잠금·시작 기준·§90 사전값·2단·연속 위반 음성·부위 강조가 그대로 돈다.
창은 같은 발의 직전 사이클 끝 이후(`lastEndBySide`), 반대 발 진행분은 보존(10 s). `hip_drop`·`knee_lat_*` 는 앵커 대비 값이라 추적기 `annotate()` 를 `rf.onFrame` 앞에서 부른다(앱·재생기 같은 순서). 쪽 미확인 사이클은 전부 "좌우 미확인" 유보.
검사(전부 **beta**, 17개): 사이드 — 반대 다리 굽힘(지지 무릎 < 140°, 차단 후보)·디딘 무릎 안쪽 모임(`knee_out_{moving}` < 0, C)·깊이(범위)·상체 숙임(B/D); 크로스 — 앞 무릎 안쪽 모임(`knee_out_{support}`)·하강(범위, `hip_drop` < 0.12)·몸통 비틀림(`rel_yaw` 시작 대비 ±25°, 참고)·상체 숙임;
니업 — 상체 젖힘·숙임(`torso_pitch` 시작 대비 ±20°, B/D, 차단 후보)·허벅지 높이(범위, `thigh` > 120°)·지지 무릎 굽힘(참고)·골반 들림(`torso_roll` ±10°, 참고); 크런치 — 왼손·오른손 머리 위치(`hand_ear` > 0.45, 차단 후보)·무릎 옆 경로(`knee_lat_{moving}` < 0.12, C, 차단 후보)·팔꿈치 무릎 접근(범위)·앞 숙임(B/D).
'(범위)' 는 스타일·체력(원칙 #3)이라 승격해도 차단하지 않는다(`gates = false`). AIHub ship 창 규칙 8건 중 7건은 `RepFormSpecs.supersedes` 로 beta 강등(리포트 '참고')하고 '스탠딩 사이드 크런치|시선 정면 유지' 는 ship 유지. 못 보는 것은 `excursionScopeRules()`. `rules_version` 의 repform 은 `v0.6`.

*85 ms 와 판정 격자.* §94 의 85 ms 추론은 **화면**(뼈대·프레이밍·무대)에만 쓴다. `PostureLive` 는 `SESSION_INFER_INTERVAL_MS`(85) 로 추론하고, 준비 프레임·피처·규칙·카운터·반복 검사·세트 로그는 `SESSION_SAMPLE_INTERVAL_MS`(300) 칸(`now / 300`)마다 첫 프레임만 받는다(`refs.judgeBinRef`).
모집단 캡처(`mmfit_mp_captures`·`rehab_mp_captures` `cadenceMs` 300)·§32 오탐률·§21.10 띠·프레임 수 상수(`liveHoldFrames` 14·`windowFrames` 3·`TOP_FRAMES` 5)가 전부 300 ms 전제라, 격자를 낮추려면 그 상수를 ms 로 바꾸고 캡처를 재추출해 재생 표를 다시 낸 뒤에 한다. 로그 `sample_interval_ms` 는 300.

*로그·재생.* `reps.config.anchors`(세트 첫 앵커 — 재생기 `restoreAnchors`, `setlog_captures.py` → `loggedAnchors`), `reps.engine = excursion_v1_beta`, 원시 사이클 `side`, `rep_form` 의 `form_state`(UNJUDGED/PASS_IN_SCOPE/FAIL)·`moving_side`(= side). 재생기 `Replay.kt` 는 앱과 같은 순서(`annotate` → `rf.onFrame` → `onFrameFeatures`). engineFiles 에 `FootExcursion.kt`(§93 두 파일 삭제).

*화면·음성.* beta 는 '참고' 만. 쪽 미확인 걸음은 §93 의 무음 대신 짧은 톤(`TONE_PROP_ACK`). 준비 문구 "왼발을 뒤로 교차하면 왼쪽 1회예요"(`LEG_PAIR_COUNT_RULE`)·"한쪽 발을 올렸다가 내려놓으면 1회"(`LEG_LIFT_COUNT_RULE`). 권장 뷰 C·C·B·C. 시작 문장 "한 발이 떠났다 돌아오면 1회로 셉니다. 횟수와 자세는 검증 중…".

*검증.* 앱 유닛 554/554 · 재생기(replay-jvm) 69/69 · `setlog_captures.py --self-test` 50/50 · `assembleDebug` 통과(`FootExcursionTest` 엔진 계약 18건 — 재생기도 컴파일, `LegExcursionIntegrationTest` 앱 연결 3건). 자가 검증은 `postureExerciseMap` 을 옛 위치 `PostureLive.kt` 에서 읽어 전부터 틀려 있었다(2026-10-02 에 `PostureSupport.kt` 로 옮김) — 경로와 런지 단위 표(사이드·크로스 = side_each)를 고쳤다. 합성 계약이다: 같은 발 반복·오른발·네 프로필 상호 기각·전방 런지 네 프로필 기각·스쿼트 0·동시 이탈·공백 뒤 앵커 유지·관절 누락 건너뜀·착지 재앵커·이름 반증·쌍·준비 앵커 재생·annotate·평가기 쪽 치환·beta 비차단·기하(허벅지각·요·롤 보정)·규칙 강등·로그.
**폰 설치**: SM-N976N 에 2026-10-06 16:27 `install -r`(versionCode 10 → 10, 서명 거부 없음). 설치 전 백업 `data\phone\backup-20261006T1625\`(세트 로그 9·피드백 10·shared_prefs 6, 60 MB — `pull_phone.py --adb … pull --out`, `run-as … cat shared_prefs/*`). 설치 뒤 posture_logs 19개 보존, `cmd package compile -m verify -f` 거부 클래스 없음, 앱 실행·크래시 없음(MainActivity 포그라운드). **실기기 운동 검증·AIHub 재생 표는 하지 않았다.** 다음: ① `family_scorecard.py` 에 네 종목 행(설계 §6.1 조건별 true 클립 띠, §6.2 MM-Fit·REHAB 판별 음성) ② 계열 폰 1세션(§6.3 CSV, 크로스 런지는 C·B 둘 다) ③ 규칙·뷰 단위 승격.

## §97. 한 다리 계열 v2 — 다리 기하 사이클 (2026-10-06 저녁, 브랜치 `claude/exercise-form-correction-5fe80e`)

§96(발 이탈 추적)을 폰 1차 세트(SM-N976N, 16:27 설치 뒤 네 종목 각 1세트)가 반증했다 — 사용자 보고: 사이드·크로스 런지 0회, 니업·크런치는 한쪽만 해도 1회·얕아도 1회·다리만 올려도 1회, 교정 없음. 설계 `docs/SINGLE_LEG_FAMILY_DESIGN.md` 를 v2 로 다시 썼다(§1 이 진단). 종목 함정 `docs/exercises/single_leg.md`.

*진단(`data/phone/20261006-session2/sets-20261006.jsonl`, Codex 개발판 4세트 + v1 4세트).* 사이드 런지: 디딘 발 0.3 몸통, **반대 발 0.5**(두 발 다 옮김), 바닥 스탠스 1.3~2.5, 굽힌 무릎 72~112 / 반대 142~171, 골반이 굽힌 발 위(발목 사이 위치 0.69~0.86) → v1 `support_moved`·`both_moved` 로 전부 기각. 크로스 런지: 두 발이 모두 0.6~0.9 움직여 발목이 교차(`leg_cross` −0.4~−0.7), 골반 0.4~0.6 하강, 돌아온 자리는 매번 다르고 좁다(0.2) → 같은 기각. 니업: 전체 들기 허벅지 45~99°, 얕은 들기 118~149°; 착지가 앵커에서 0.13~0.2 벗어나 복귀 띠(0.12) 안에 못 들어와 사이클이 안 닫히고 다음 다리에서 `support_moved` 로 기준 삭제. 크런치: 수축 반복은 같은 쪽 팔꿈치–무릎 0.52~0.95, 다리만 올린 반복 1.33~1.59 — 12회 중 8회가 다리만(Codex 개발판도 같은 12회를 셌다). 라벨·롤·3D 각은 멀쩡했다. 틀린 것은 "발이 제자리로 돌아온다" 는 전제다.

*결정.* ① 사이클 = 다리 기하(무릎각·허벅지각·발목 교차). 발 위치 앵커는 쓰지 않는다. ② **네 종목 모두 왼 1 + 오른 1 = 1회**(사용자 결정 — §92 의 "한쪽 올림·복귀 = 1회" 를 뒤집음; `ExerciseProfiles.SIDE_EACH_EXERCISES`, `WorkoutPacing` 두 배). ③ **얕은 회·다리만 올린 회·교차 없는 회는 세지 않고 이유를 말한다**(두 모드, 판별 = 횟수의 입장 조건; 원칙 #3 보다 사용자의 종목 정의가 우선). ④ 교정 항목은 사용자 목록의 카메라 대리로, 전부 beta. 힘의 출처(아랫배·옆구리·반동)는 범위 문장.

*엔진(`posture/LegCycle.kt`).* `LegGeometry.features`: `thigh_L/R`·`rel_yaw`(3D), 롤 보정 2D 로 `leg_cross`(발목 교차, 사용자 좌우는 어깨 x 차 부호), `leg_hipshift`(골반 x 의 두 발목 사이 위치), `leg_lift_L/R`(반대 발 대비 높이), `hip_hike_L/R`, `knee_out2d_L/R`, `head_lean_L/R`(귀선 − 어깨선), `hand_ear_L/R`, `elbow_knee_L/R`, `leg_hip_y`, `leg_torso2d`. `LegCycleTracker`: 다리마다(크로스는 교차 하나) 기준(준비 `prepare` 또는 정지 0.4 s·3프레임·퍼짐 ≤ 12°, **복귀마다 재기준**) → 출발(무릎 −30 / 허벅지 −25 / 교차 ≤ −0.15) → 극점 스냅샷 → 복귀(기준 −15 안, 교차는 ≥ 0.05 + 두 무릎 ≥ 145; 150 ms·2프레임) → 판별. 판별 띠(실측, 잠정): 사이드 — 굽힌 무릎 ≤ 115·반대 − 굽힌 ≥ 30·골반 위치 ≥ 0.58·발 들림 ≤ 0.45; 크로스 — 교차 ≤ −0.2·(무릎 ≤ 135 또는 하강 ≥ 0.25)·뒷발 = 0.15 위인 발(없으면 쪽 미확인); 니업 — 허벅지 ≤ 110·들림 ≥ 0.6·반대 허벅지 ≥ 150; 크런치 — 들림 ≥ 0.5·**팔꿈치–무릎 최소 ≤ 1.0**(높이보다 먼저 묻는다)·허벅지 ≤ 115·반대 ≥ 150. 기각 사유는 `reps.rejected[].feature`, 음성 `LegCycleTracker.cueFor`(`PostureLive`, 틱 + 6 s 간격), HUD "세지 않은 동작 n". `RepSignal.legProfile`·`RepCounter.legTracker`(§96 훅), `reps.engine = legcycle_v2_beta`, `reps.config.standing`(재생기 `restoreStanding`, `setlog_captures.py` `loggedStanding`), 관절 누락 프레임 건너뜀·750 ms 공백은 후보만 폐기.

*자세(`RepFormSpecs.legCycle`, 전부 beta, 기존 평가기 + `{moving}`/`{support}` 치환).* 니업 — 가슴 펴기(`torso_pitch` 시작 대비 ±15°, C/B/D), 골반 균형(`hip_hike_{moving}` > 0.12, C), 지지 무릎 잠김(> 176°)·굽힘(< 150°), 반동(올리는 동안 `torso_pitch` 상단 대비 −12°); 크런치 — 왼손·오른손 머리 위치(`hand_ear` > 0.45), 골반 고정(`hip_hike_{moving}` > 0.25), 가슴 정면(`rel_yaw` ±20°), 머리 당김(`head_lean_{moving}` > 15°), 앞 숙임(> 20°, B/D); 사이드 — 반대 다리 굽힘(< 140°), 굽힌 무릎 안쪽 모임(`knee_out_{moving}` < 0, C), 상체 숙임(> 45°, B/D — 힙 힌지 13~45° 는 정상); 크로스 — 앞 무릎 안쪽 모임(`knee_out_{support}`), 몸통 비틀림(`rel_yaw` ±25°), 상체 숙임(> 35°, B/D). 범위 카드 `legScopeRules`(척추 분절·허리 말림·아랫배와 옆구리의 힘 / 발 접지 / 무릎 축 회전·발끝 / 목 분절·당기는 힘·시선). `rules_version` repform `v0.7`. AIHub ship 7건 `supersedes` 강등은 §96 과 같다(대응 검사만 바뀜).

*재생(오늘 8세트, 랜드마크 캡처 `.cap` — 로그 피처에는 v2 기하가 없다).*

| 세트(빌드 세션) | 로그가 센 수 | v2 | 쪽(왼/오/미확인) | 기각 |
|---|---:|---:|---|---|
| 사이드 런지(Codex) | 0 | 10 | 4 / 1 / 5(뒤쪽 절반은 옆으로 돌아 이름 반증) | 얕게 1 · 발 들림 1 · 스쿼트형 3 |
| 스탠딩 니업(Codex) | 12 | 9 | 4 / 5 / 0 | 얕게 4 |
| 사이드 크런치(Codex) | 12 | 3 | 1 / 2 / 0 | 옆구리 안 접음 5 · 발 안 뜸 4 |
| 크로스 런지(Codex) | 0 | 3 | 0 / 3 / 0 | 하강 없음 2 |
| 사이드 런지(v1) | 0 | 7 | 5 / 2 / 0 — 수동 판독 L·R·L·L·L·R·L 과 같다 | 스쿼트형 2(두 무릎 73/96 회) · 얕게 2 |
| 스탠딩 니업(v1) | 12 | 30 | 15 / 15 / 0 | 얕게 5 |
| 사이드 크런치(v1) | 12 | 4 | 1 / 3 / 0 — 팔꿈치–무릎 ≤ 1.0 인 4회와 같다 | 옆구리 안 접음 10 · 발 안 뜸 1 |
| 크로스 런지(v1) | 0 | 5 | 2 / 3 / 0 — 수동 판독 R·L·R·L·R 과 같다 | — |

정답은 사용자의 기억뿐이라 동작 모양의 확인이다. 니업 v1 세션의 30회는 사용자가 그 세트에서 한 36번의 들기 중 얕은 5번을 뺀 수로 읽힌다(설계 §3 의 극점 표). 크런치 4·3회는 팔꿈치–무릎 ≤ 1.0 인 수축 반복과 정확히 같다. 재생에는 준비 프레임이 없어 세트 첫 회가 기준 전에 시작되면 놓친다(앱은 카운트다운에서 기준을 심는다).

*검증.* 앱 유닛 548/548 · 재생기(replay-jvm) 63/63 · `setlog_captures.py --self-test` 50/50. **폰 설치**: SM-N976N 에 2026-10-06 17:16 `install -r`(versionCode 10, 로그 19개 보존, ART verify 거부 없음, 실행·크래시 없음). 재생의 반복 검사도 실제 값을 낸다(예: 니업 첫 회 지지 무릎 잠김 176.7° 위반 — 이 사용자의 선 무릎이 175~177° 라 176° 띠는 좁을 수 있다, beta). **폰 2차 세트는 하지 않았다.** 다음: ① 설치 → 폰 2차 세트(설계 §7 CSV, 왼발부터 번갈아) ② 쪽·횟수·기각 사유 확인 → 띠 확정 ③ 자세 beta 의 폰 검출률.

## §98. 한 다리 계열 v3 — 사용자가 "세지 말라" 한 조건을 판별에 (2026-10-06 밤, 브랜치 `claude/exercise-form-correction-5fe80e`)

§97(v2)을 17:16 에 설치하고 사용자가 네 종목 각 1세트를 일부러 오류 회를 섞어 했다(`data/phone/20261006-session3`, 로그 17:27). 보고: 사이드 런지 — 무릎 발끝 넘김 교정 없음·얕게 해도 1회·허리 굽힌 다음 진행해도 1회. 니업 — 발을 앞으로 뻗기만 해도 1회·허리 굽혀도 1회. 크런치 — 다리만·앞으로 뻗어도·편 다리를 옆으로 뻗어도·허리 숙여도 1회, 교정 없음. 크로스 런지 — 허리 숙여도·얕게 해도 1회, 교정 없음. 설계 `docs/SINGLE_LEG_FAMILY_DESIGN.md` §1a(진단)·§3(띠)·§7(재생).

*진단(회별 극점, `research/external_rep_replay/single_leg_diag.py`).* 사이드 런지(24회 셈): 무릎 98~100° 2회가 띠 115 를 지났고, 몸통 전후 기울기(`torso_pitch`) 최대 40~58° 8회가 세졌다 — 그중 2회는 출발에 이미 32°("굽힌 다음 진행"). 허리는 어느 종목에서도 횟수에 영향이 없었다 — beta 창 검사(B/D 뷰)뿐이라 정면에서 전부 유보. 니업(25회): 몸통 21~36° 6회 세짐. 편 다리 킥(무릎 115~128)은 '얕게' 로 기각됐지만 "골반 높이까지" 라고 말해 이유가 틀렸다. 크런치(24회): 앞으로 올린 무릎(`knee_lat` −0.08~0.22) 10회·편 다리(무릎 132~170) 10회·옆으로 올리기만(팔꿈치–무릎 0.77) 1회가 세짐 — 팔꿈치–무릎 1.0 은 다리를 높이 들면 다 지나간다. 제대로 접은 회는 8회(팔꿈치–무릎 0.06~0.23·측굴 44~56°). 크로스 런지(6회): 오른발 뒤 3회가 무릎 107~117·골반 높이(`hip_height_rel`) 0.80~0.82 로 왼발 뒤(89~100·0.72~0.77)보다 얕다. 몸통(28~36, 기준 9)은 회마다 비슷해 숙인 회를 데이터로 못 가른다.

*결정(사용자 결정 2026-10-06 밤).* 허리 숙임·편 다리·앞으로 올린 무릎·얕은 깊이(강화)도 **세지 않고 이유를 말한다**(두 모드) — 판별의 입장 조건이지 자세 검사가 아니다(원칙 #7 의 사용자 정의). 무릎 발끝 넘김은 beta 검사(화면 참고): 발끝 관절의 z 가 발 길이를 반으로 읽어(0.17~0.30 정강이) 라벨 없이 띠를 못 정한다.

*엔진(`posture/LegCycle.kt`, `legcycle_v3_beta`).* 기하 추가 `lat_flex_L/R`(측굴 = 어깨선 − 골반선 기울기, 2D), `knee_fwd_foot_L/R`(무릎이 발 방향으로 나간 거리 ÷ 정강이, 3D). 기준에 선택 키 `torso_pitch`·`lat_flex_L/R`(`OPTIONAL`, 정지 판정에는 안 듦). 사이클 중 수집: 몸통의 출발값·최대, 골반 높이 최소, 크로스의 더 굽은 무릎 최소(교차 극점 프레임의 무릎은 바닥보다 펴져 있다), 크런치의 측굴 최대. 판별 띠(설계 §3): 사이드 — 무릎 ≤ 95·골반 높이 ≤ 0.82·몸통 기준 대비 출발 ≤ 22·최대 ≤ 32(절대 45); 크로스 — 무릎 ≤ 110·골반 높이 ≤ 0.80·몸통(런지 띠); 니업 — 극점 무릎 ≤ 110·몸통 ≤ 10(절대 25); 크런치 — 극점 무릎 ≤ 110·`knee_lat` ≥ 0.35(없으면 `knee_out2d` ≥ 0.45)·팔꿈치–무릎 ≤ 0.45·측굴 ≥ 25·몸통 ≤ 10. 새 사유 `torso_bent`·`knee_straight`·`no_abduct`, 음성(`cueFor`) "허리를 숙이면 세지 않아요 …", "다리를 뻗으면 세지 않아요 …", "앞으로 올리면 세지 않아요 …", `no_lift` 도 말한다. **사유 순서 = 우선순위**(첫 사유만 말한다): 니업·크런치는 편 다리를 높이보다 먼저, 사이드 런지는 얕음을 비대칭보다 먼저. 재료가 없는 조건은 통과. `RepFormSpecs` 에 '무릎 발끝 넘김'(`knee_fwd_foot_{moving}` > 0.6, beta) — `rules_version` repform `v0.8`. 준비 문구·범위 문장이 새 조건을 말한다.

*재생(2차 세트 4개, `.cap`).* 사이드 런지 24 → **14**(왼 8·오 6 = 6짝; 허리 8·얕게 4), 니업 25 → **19**(10·9 = 9짝; 허리 6·얕게 3·편 다리 4), 크런치 24 → **8**(4·4 = 4짝; 앞으로 10·편 다리 10·옆구리 안 접음 1 — 남은 8회 = 팔꿈치–무릎 ≤ 0.23 인 회), 크로스 런지 6 → **3**(왼 3·오 0 = **0짝**; 얕게 3 = 오른발 뒤 전부·서성임 3). 라벨이 없어 군집으로 띠를 정했다. 여백이 좁은 곳: 사이드 런지 몸통 34.6 vs 39.5, 크로스 깊이 100.5/0.77 vs 107.5/0.80(좌우 비대칭인지 가려진 뒷다리의 3D 편향인지 모른다), 크런치 앞 숙임 13.0 vs 15.0. 크로스 런지의 허리는 이 세트로 못 가른다.

*검증.* 앱 유닛 전부 통과 · 재생기(replay-jvm) 통과 · `setlog_captures.py --self-test` 50/50 · APK 빌드. 3차 세트(설계 §7 CSV, 블록 이름 = 기각 사유) → 띠 확정, 크로스 런지 오른쪽부터.

*§98a 크런치 닿음(사용자 지적 2026-10-06 밤 2).* "팔꿈치랑 무릎이 닿아야 1회 — 순간적으로 닿을 수 있어 프레임에는 안 잡혔지만 닿을 수도 있다." 닿음 띠 `CRUNCH_TOUCH_MAX` = 0.30 몸통(관절 중심끼리라 닿아도 0 이 아니다: 팔꿈치·무릎 반지름 합 6~10 cm ≈ 0.12~0.2 몸통 + 흔들림·놓침 여유; 2차 세트 닿은 회 0.06~0.23, 아닌 회 ≥ 0.49). 순간 접촉은 **판정 격자(300 ms)의 유일한 예외**로 푼다: `posture/ContactMinimum.kt`(앱 전용) 가 85 ms 추론 프레임마다 같은 쪽 팔꿈치–무릎을 받아 판정 프레임 사이의 최솟값을 모으고, 연속 세 표본의 가운데가 국소 최소이면 대칭 V 로 표본 사이의 꼭짓점을 추정한다(`m = y1 − |y0 − y2| / 2`, 0 아래 금지; 250 ms 넘는 끊김·값 없는 프레임은 세 표본의 연속을 끊어 가려짐을 닿음으로 읽지 않는다). `PostureLive` 가 판정 프레임 피처에 `elbow_knee_min_L/R` 로 얹어 평가기·카운터·세트 로그가 같은 맵을 보고, `LegCycleTracker` 는 그 값이 있으면 `elbow_knee` 대신 쓴다. 측굴(`lat_flex`)은 판별에서 빼고 beta 검사 '옆구리 접힘'(< 25°)으로 — 닿았는데 측굴이 작다고 세지 않으면 정의와 어긋난다. `rules_version` repform `v0.9`. 재생기 `.fcap` 경로는 로그 피처로 파리티, `.cap` 경로는 300 ms 값으로 후퇴(오늘 2차 세트의 닿은 8회는 300 ms 값으로도 ≤ 0.23 이라 같다). 테스트 `ContactMinimumTest`(최솟값·꼭짓점·끊김·가려짐·비우기), `LegCycleTest`(판정 프레임 0.5 + 85 ms 최솟값 0.25 → 셈, 0.4 → 안 셈).

*§98b 사이드 런지 무릎 발끝 넘김(사용자 결정 2026-10-06 밤 3).* "무릎이 발끝 넘는 것도 횟수에서 빼라." beta 검사였던 '무릎 발끝 넘김' 과 같은 띠로 판별 사유 `knee_over_toe` 를 더했다: 굽힌 다리의 `knee_fwd_foot`(발 방향으로 나간 무릎 ÷ 정강이, 3D) 사이클 최대 > 0.6(발끝 ≈ 발목에서 0.6 정강이 앞). 순서는 `no_shift` 뒤·`torso_bent` 앞. 음성 "무릎이 발끝을 넘으면 세지 않아요. 엉덩이를 뒤로 보내 무릎을 발끝 뒤에 두세요." 발끝 관절 자체의 z 는 발 길이를 반으로 읽어(0.17~0.30 정강이) 쓰지 않는다. 라벨이 없어 띠는 물리값이다. 측정은 사이클 최대가 아니라 **바닥 프레임(무릎 최소 + 15°) 중앙값** — 한 프레임 z 흔들림에 안 휘둘리게(2차 세트 회당 2~4프레임, 값은 매끄럽게 커졌다 작아져 흔들림이 아니라 진행이다). 2차 세트 재생: 세진 14회 중 **10회가 빠져 4회(왼 2·오 2 = 2짝)** 남는다 — 바닥 중앙값 0.46~0.70, 넘은 회 0.61~0.70. 이 사용자가 정말 넘기는지(깊을수록 더 나간다: 무릎 60~78° 에서 0.6+), MediaPipe z 의 편향인지는 3차 세트 '무릎발끝넘김' 블록(일부러 넘기기·뒤에 두기)으로 확정한다. 사용자에게 이 수를 알렸다.

## §99. 바닥 계열 — 플랭크 · 크런치 · 라잉 레그 레이즈 (2026-10-06 밤 설계 · 2026-10-07 구현, 브랜치 `claude/exercise-form-correction-5fe80e`, 미커밋)

설계 문서 `docs/FLOOR_FAMILY_DESIGN.md`, 측정 스크립트 `research/floor_family/`(실행본·데이터 `data/floor_family/`). 워크플로 하나로 만들었다: 조사 6(현재 바닥 코드 경로·연구 문서·최근 노하우·AIHub 바닥 데이터·외부/폰 데이터·운동역학) → 관점이 다른 설계안 3(신뢰성·사용자 정의·데이터 검증) → 반박 검증 3(코드 현실·수치·사용자/원칙) → 통합.

*진단.* 바닥 세 종목은 임계보다 **관측**이 먼저 막힌다 — 중점 피처가 양쪽 관절을 요구해 측면 폰에서 카운트 신호가 55~69 % 프레임만 계산된다(한쪽 피처 100 %). 플랭크는 실기기에서 OK/VIOLATION 을 낸 세트가 0(폰 10세트), 시간은 벽시계라 무릎을 대거나 화면 밖이어도 간다. 크런치는 활성 규칙 0·ROM 미사용·머리 높이 신호가 '고개만 까딱' 에도 채워진다. 레그 레이즈는 3점 고관절각이 무릎을 접어도 채워지고 연속 영상 데이터가 0. 바닥 판별을 만들어도 기각 음성 배선이 `rf != null`(RepFormSpecs) 안에 있어 무음이 된다(`PostureLive.kt:1036`). 바닥은 beta 를 "참고 안내예요" 로 말한다(REP_ENGINE_DESIGN #22).

*설계 요지.* ① 보이는 쪽 한 사슬 + 중력 롤 보정 피처(`FloorChain`, `fc_*`) ② 크런치 = 어깨–골반 현, 레그 레이즈 = 허벅지 사이클(`FloorCycleTracker`, LegCycle 틀 복제, 진폭 60 % 되돌아옴 복귀, 정지 없는 누운 기준) ③ 플랭크 = 카메라가 확인한 시간(`PlankHoldClock`, 1 s 확정 뒤 단조 적립, 멈춤 사유 out_of_view·not_prone·knees_down·hips_low·pike, 20 s 미확인 시 시계 폴백) ④ 판별은 두 모드에서 세지 않고/멈추고 이유를 말한다 — 입장선은 AIHub MP 측면 **회 단위** 정상 기각 ≤ 2 %, 넘는 판별은 기본 끔 ⑤ 자세 검사는 전부 beta·침묵(#22 를 닫는 안, 사용자 결정 Q1). 근거 수치(AIHub 바닥 C·E 33,248장 앱 모델 재추출, MM-Fit 윗몸일으키기 15세트, 폰 플랭크 10세트)와 '분할 의존' 한계는 설계 §3. 구현 순서는 설계 §10 — 재현성(1) → 순수 기하(2) → 사이클(3) → 시계(4) → 앱 배선(5) → 문서(6) → 폰(7) → 자세 beta(8).

*§99 구현 기록(2026-10-07) — 지금 상태의 정본.* 설계 §10 의 2~6단계(순수 기하·사이클·시계·앱 배선·문서)를 마쳤고, 그 사이 로컬 엔진 재생과 배선 리뷰(결함 29건)를 거쳤다. 아래 세 문단(엔진 재생·앱 배선·리뷰 결함 수정)은 단계별 기록이다. 남은 것은 폰 세션(7단계)과 자세 beta(8단계). 함정은 `docs/exercises/floor.md`.
- **사용자 결정**(2026-10-06 밤). **Q1 = 바닥 beta 음성 끄기**(화면 '참고' 만) — 실기기 오탐 3건이 전부 beta·미보정 규칙에서 나왔고(§28) 음성은 사용자가 즉시 몸을 바꾸는 채널이라(원칙 #2·#6) REP_ENGINE_DESIGN #22 를 '침묵' 으로 닫았다. 대신 세트 시작에 범위 문장으로 아직 교정하지 않는 것을 한 번 말한다(들리던 참고 음성이 사라진 것을 퇴행으로 받아들이지 않게, 설계 §6). **Q2 = 플랭크 목표 진행 = 카메라가 확인한 플랭크 시간** — 벽시계는 무릎을 꿇어도, 화면 밖이어도 줄었다(원칙 #1). WORK 20 s 동안 한 번도 확인 못 하면 시계로 넘기고 한 번 말하고 리포트에 '카메라 미확인'(09-12 같은 촬영 실패가 기록 0 이 되지 않게). **Q12 = 크런치·레그 레이즈를 바닥 반복 계열 한 엔진**으로(토큰 효율 메모 2026-09-27, 스탠딩 사이드 크런치 선례). Q3~Q11 은 설계 §9.2 권고를 기본값으로 — 무릎 플랭크 멈춤·팔 편 플랭크 셈(Q3), 골반 참고 띠 [−0.08, +0.16](Q4), 축소 관측 안 함(Q5), 크런치 `neck_only` 4.5° 켬(Q6), 크런치 `shallow` 끔(Q7), `sit_up` 80°(Q8), 손 위치 안내는 폰 뒤(Q9), 레그 레이즈 `knee_bent` 끔(Q10 — 결정 대기), 얕게 30°·머리는 바닥에 둠·발 닿음은 셈 = `feet_touch` 끔(Q11).
- **엔진**(`posture/`, 재생기 `engineFiles` 에 넣어 앱과 같은 소스로 재생): `FloorChain.kt`(`Floor2d` + 관측 층 `FloorChain` — 보이는 쪽 한 사슬, 쪽 잠금 1.5 s 중앙값·반대쪽 +0.2 가 2 s, 중력 up 의 화면 성분으로 위·수평, `gravityUp` 이 앱의 up 뒤집힘을 되돌림), `PlankGeometry.kt`(`PlankAlignment.kt` 에서 뗀 순수 기하), `FloorCycle.kt`(`FloorProfile{CRUNCH, LEG_RAISE}` + `FloorCycleTracker`, `floorcycle_v1_beta`), `PlankHold.kt`(`PlankHoldClock`, `plankhold_v1_beta`), `PostureFloor.kt`(`computeForExercise(종목, xy, vis, W, H, up, tMs)` 가 세 종목에 `fc_*`). 공통 `IdentityCueSource{cueFor(reason)}` 를 `LegCycleTracker`(위임, 동작 그대로)·`FloorCycleTracker`·`PlankHoldClock` 이 구현한다. 앱 쪽은 `posture/FloorLive.kt`(사유 문구·기각 큐 게이트·`RejectionCues.handle`·`HoldVoice`·`HoldAnnounce`, 안드로이드 의존 없음), `PostureFloorLive.kt`(`FloorLiveState` = `refs.floorLive`), `WorkoutSession.kt`(`HoldBridge`·`SessionProgress.tick(heldDeltaMs)`). `PostureLiveSessionScreen` 에는 지역 변수 0개, 화면 파라미터는 `holdBridge` 하나(dexdump 레지스터 212 < 256).
- **카운트 신호**: 크런치 `RepSignal("fc_trunk_lift", 4.5, floorProfile = CRUNCH)`, 라잉 레그 레이즈 `RepSignal("fc_thigh", 20, floorProfile = LEG_RAISE)`. TRACK 비교 지표는 `head_ground`·`hip_ang` 그대로(조용한 단위 변경 금지). floorProfile 신호에는 ROM 을 붙이지 않는다 — 크런치 ROM 은 머리 대리라 MP C 위반의 63.5 % 가 통과했다.
- **판별**(두 모드 — 세지 않고/멈추고 첫 사유를 말함, 사유 목록의 순서 = 음성 우선순위): 크런치 `sit_up`(> 80°)·`neck_only`(귀 탐침, 4.5°) 켬, `shallow` 끔 / 레그 레이즈 `trunk_up`(> 30°)·`shallow`(< 30°) 켬, `knee_bent`·`one_leg`·`feet_touch` 끔 / 플랭크 멈춤 사유 `out_of_view`·`not_prone`·`knees_down`·`hips_low`·`pike`. 반복 판별은 정점 `fc_yaw` ≤ 0.15(측면)일 때만, 아니면 유보하고 센다(`identity_abstain` — 띠를 측면 C 에서만 쟀다).
- **로그 키**: `reps.engine` = `floorcycle_v1_beta`, `reps.config.lying`(누운 기준 + `prep`·`min`), `reps.rejected[]`(`reason, trunk_peak, ear_peak, amp, knee_top, head_med, side_ok`), `reps.floor_reps[]`(`t_ms, start_t_ms, peak_t_ms, min, peak, top_ms, descent_ms, abstain`), `reps.discarded[[t_ms, timeout|exit]]`, `reps.identity_abstain[]`, `floor_chain{switches}`, `hold{engine, source, held_ms, wall_ms, first_hold_t_ms, first_stop, stop_ms, wait_ms, start_t_ms, end_t_ms, segments}`, 프레임 `fc_*`·`floor_up`(관측 층에 넘긴 중력 — 재생기 U 줄의 정본). `fc_torso_tilt` 는 카운터 `annotate` 뒤에만 있어 프레임 로그에 없다.
- **재생 수치**(실제 엔진, 리뷰 수정 뒤 — 정본 표는 설계 §7.1 결과, 원자료 `data/floor_family/replay/results/`):

| 항목 | 값 | 판단 |
|---|---|---|
| 크런치 판별, AIHub C 정상 회 기각(측면 판정 회) | `sit_up` 1/484 = 0.2 % · `neck_only` 13/286 = 4.5 %(CI 2.4~7.6) · `shallow` 30/273 = 11.0 % | `sit_up` 켬 · `neck_only` 켬(회를 지우지 않음) · `shallow` 끔 |
| 레그 레이즈 판별, AIHub C | `trunk_up` 0/306 · `shallow` 3/306 = 1.0 % · `knee_bent` 3/198 = 1.5 %(진입·이탈 제외 2/76 = 2.6 %) · `one_leg` 0/306(위반 표본 없음) · `feet_touch` 1/111 = 0.9 %(검출 1.9 %) | `trunk_up`·`shallow` 켬, `knee_bent` 는 Q10 대기 |
| 뷰 E 측면 게이트 누출 | 크런치에서 게이트를 통과한 42회 중 `sit_up` 7(투영 붕괴) | **고침(2026-10-07)**: 측면 = `fc_yaw` ≤ 0.15 ∧ `fc_ratio` ≥ 8(있을 때, `FloorCycleTracker.SIDE_RATIO_MIN`). 재생 E 측면 판정 42 → 10회, 오기각 7 → 1. C 정상 기각 0.2 % 그대로. 비스듬한 MM-Fit 완전 윗몸의 `sit_up` 기각 18/28 → 4/28(측면 아님 = 유보) |
| MM-Fit 윗몸일으키기 15세트 | 판별 끔 ±1 12/15(중력 = 화면 위)·13/15(중력 모름), 정확 6/15, **w06·w14 0회 세트 없음**, w20 10·10·9. 기본 판별 정확 2/15(완전 윗몸을 `sit_up` 으로 — 의도, w19 18/28), 세트 밖 헛사이클 7·12(기준선 15) | ±1 15/15 목표 미달 — 대리 데이터라 상수는 안 바꿈 |
| 플랭크 | 파리티 77프레임 ≤ 1e-3, 시간 게이트(pike 제외) C 74.1 %(설계 0.743), 정상 클립 거짓 멈춤 1/103, pike STOP 클립 위반 3/104 vs 충족 0/103 | 통과 |
| 회전 불변(합성) | 롤 0/90/180/37/−90°·up 뒤집힘·좌우 반전 ≤ 0.1°·1e-3 몸통 | 통과 |

- **검증**: 앱 유닛 629/629(작업 전 557 + 신규 72 — `FloorChainTest`·`FloorCycleTest`·`PlankHoldTest`·`FloorWiringTest`·`WorkoutSessionTest` 추가분), 재생기 126/126(`FloorReplayTest`, 앱 바닥 테스트를 안드로이드 없이 함께 컴파일), `setlog_captures.py --self-test` 54/54, `:app:assembleDebug` 통과. 정책이 바뀌어 고친 기존 테스트: `RepCounterTest`(max 방향 ROM 예시 크런치 → 바이시클 크런치, 설계 §2 #13), `PlankAlignmentTest`(정렬 음성 null — Q1), 리뷰의 정책 변경분(`neck_only` 켬·폴백 조건·lying `prep`·v0.5 집계·`workMillis` = 인정 시간·결측 문장). 기기 `compile -m verify` 는 하지 않았다(폰 단계).
- **설계와 다르게 한 것(근거)**:
  - `LYING_MIN_FRAMES` 3 → 2 — 3 이면 쉬지 않는 MM-Fit w06·w14 6세트가 0회였다(설계 §2 #3 '쉬지 않는 사용자를 0회로 만들지 않는다').
  - 레그 레이즈에도 출발 여유 15°(설계는 크런치에만 '최소점이 기준 + 8° 안') — 상단 펄스를 회로 세지 않게, 잠정.
  - `fc_torso_tilt` 는 누운 기준을 가진 추적기 `annotate()` 가 더한다. `FloorChain` 은 수평 대비 `fc_torso_elev` 만 낸다.
  - up 을 믿을 수 없으면(null·화면 성분 < 0.3) `fc_axis_h` 만 유보하고 부호 피처는 화면 위 기준 — 사이클은 up 없이도 돌아야 한다(설계 §4.2 'up 미검증이면 게이트 없이'). 플랭크는 그때 `fc_flat_screen` < 0.7 로 엎드림을 묻는다(리뷰 #5).
  - `fc_chain`·`fc_up_ok`·`fc_flat_screen` 추가, 종목(Kind)별 피처만, `computeForExercise` 에 `tMs`(쪽 잠금 이력), 몸통이 관측된 프레임에만 `fc_*`(§31a '검출 = 측정 가능' 함정, 리뷰 #11).
  - `PostureFloor.kt` 를 순수 기하로 쪼개는 대신 `PoseSample.withFeatures` 를 `PostureAnalyzer.kt` 로 옮겨 통째로 재생기에 넣었다 — 재생기가 레거시 피처까지 앱과 같은 함수로 낸다.
  - `feet_touch` 는 '회 사이 바닥'(출발 전 최저 `fc_leg`) — 사이클이 60 % 복귀에서 닫혀 하단 체류가 닫힌 뒤에 온다. `neck_only` 탐침 출발은 '귀 최소점 + 6°'.
  - `timeout`·`exit` 은 기각 목록이 아니라 `discarded` — 설계 '말하지 않음, 틱도 없음' 을 앱의 '기각 = 틱' 규약과 맞추려고.
  - 판별 기각 음성 간격은 바닥만 사유별 6 s, 한 다리 계열은 종전대로 하나의 간격(서서 하는 종목 동작 유지).
  - 플랭크 폴백을 '20 s 미확인 ∧ 대기 시간의 과반이 화면 밖 ∧ HOLD 후보 확인 중 아님' 으로 좁혔다(리뷰 #17 — 무릎 플랭크가 시계로 플랭크 시간이 됐다, Q3·원칙 #1). 대신 대기 사유가 5 s 이어지면 사유마다 한 번 말한다(HINT). 그래서 무릎 플랭크로 세트를 끝까지 하면 목표에 닿지 않는다(✓ 로 끝냄). 폴백하면 그때까지의 벽시계를 한 번 더해 '처음부터 시계로 잰 세트' 와 같게 한다. 세트 가동 시간(기록의 플랭크 초·근육 부하)도 인정 시간이다(리뷰 #13, 설계는 벽시계).
  - `PlankHoldClock.pause/resume` 추가 — 일시정지는 시계 밖(재개 첫 칸이 `out_of_view` 멈춤과 그 음성이 되지 않게).
  - 첫 회 잠정은 세트에 한 번·종목별 확인 창(크런치 8 s·레그 레이즈 12 s)·기각과 일시정지도 확정(리뷰 #1·#2). 대가: 세트 앞 헛사이클 뒤에 기각 동작만 이어지면 그 헛사이클이 1회로 남는다(MM-Fit w18 +1 × 2세트). 출구가 판별보다 먼저 걸린 후보는 5 s 보류 뒤 그 사유로 기각·발화(리뷰 #20).
  - 리포트 정렬 줄은 '(참고) 정렬 이탈 k건'(추적기가 시간이 아니라 사건을 센다), HUD 사유는 마지막 기각 사유, 플랭크 범위 문장에 '고개와 골반 정렬은 화면에 참고로만 보여요' 추가. 설계에 없던 로그 `floor_up`·`hold.wait_ms/start_t_ms/end_t_ms`(재생 파리티).
  - rules_floor v0.5 를 8단계 전에 넣었다(골반 띠·목 exclude·레그 레이즈 고개 exclude) — Q1(침묵)·Q4 를 지금 반영하려면 필요했다. 레그 레이즈는 대체 검사(`fc_head_lift` > 40°) 전까지 화면 '참고' 자세 항목이 0개다.
- **남은 일**: ① **폰 세션**(설계 §7.2 CSV — 블록 이름 = 기각 사유, 각 종목 1번 세트는 TRACK 으로 한 번 더) → `setlog_captures.py` → 재생기 바닥 경로 → `floor_diag.py` 표 → 띠 확정·판별 켜고 끄기(설계 §7.3). 함께: 설치 뒤 `compile -m verify`, 배치별 `fc_yaw`·`fc_ratio` 통과율, up 뒤집힘 비율, 쪽 잠금 교체 수, 잠정 상수(`LYING_MIN_FRAMES` 2·레그 레이즈 출발 20°·여유 15°·최대 10 s·`UP_MIN_INPLANE` 0.3·누운 영역 30°·출구 50°/45°·플랭크 `not_prone` 30°·`hips_low` 0.10·`pike` 0.25). ② 사용자 결정: Q10(`knee_bent`), 폰 '목만' 블록 뒤 Q6 재결정, 뷰 E `sit_up` 측면 게이트 보강(후보: up 이 유효하면 정점 `fc_axis_h` ≥ 45° 또는 `fc_ratio` ≥ 8). ③ **자세 beta(8단계)는 폰 뒤** — `RepFormSpecs.floor`·`restHigh`·레그 레이즈 머리 들림 > 40°(ship 1순위), `rep_priors.py`·`fault_lines.py` 재생성. ④ `floor_scorecard.py`(설계 §7.1 a), `family_scorecard.py` 바닥 줄, `extract_mediapipe.py` MM-Fit situps·pushups. ⑤ 죽은 배관(`HoldTracker`·`FloorCoverage`·`floorMeasurement`) 정리. ⑥ 덤벨 컬 팔별 경로의 첫 회 거둠이 `RepUnitAccumulator` 원장에 남는 같은 결함(서서 하는 종목이라 이번엔 그대로). ⑦ `CLAUDE.md` — 바닥 계열 함정 한 줄, `rules_floor_v0.json` 16규칙 = beta 12 + exclude 4, `rules_version` `mp_v0.1+floor_v0.5+repform_v0.9`(지침 파일이라 사용자 확인 뒤 반영).

*§99 엔진 재생(2026-10-07, 설계 §7.1 b·c·d).* 재생기 바닥 경로: `.cap` 메타 `floor=1` 이면 앱 바닥 분기와 **같은 함수** `FloorFeatureExtractor.computeForExercise(종목, xy, vis, imageW/H, 중력 up, t)` — 이를 위해 `PoseSample.withFeatures` 를 `PostureAnalyzer.kt` 로 옮겨 `PostureFloor.kt` 를 재생기 `engineFiles` 에 넣었다. U 줄 = 중력 up, 없으면 null(게이트 없는 경로). 플랭크는 `PlankHoldClock`, 크런치·레그 레이즈는 `RepCounter.forSession(floor = true)`, 결과 JSON 에 기각 상세·센 회 상세·버린 후보·판별 유보·누운 기준. `--floor-clips`(클립마다 새 엔진), 매니페스트 11열 = 켠 판별 사유(진단, `RepCounter.floorEnabled`). 캡처 `research/external_rep_replay/aihub_floor_captures.py`(AIHub 바닥 C·E 600 ms 가정·MM-Fit 윗몸 300 ms 격자, 출력 `data/floor_family/replay/`), 표 `floor_replay_tables.py`, 회별 표 `floor_diag.py`. 결과(측면 C, 사유를 하나씩 켠 구성, 측면 판정 회 기준): 크런치 `sit_up` 0.2 %(1/484)·**`neck_only` 4.5 %(13/286, CI 2.4~7.6) → 이 단계에서 기본 끔**(분할 의존 1.7 % 로 켰던 것 — 아래 '리뷰 결함 수정' 에서 Q6 대로 다시 켰다: 회를 지우지 않는 사유라 2 % 입장선 대상이 아니다)·`shallow` 11.0 %; 레그 레이즈 `trunk_up` 0/306·`shallow` 1.0 %(3/306)·`knee_bent` 1.5 %(3/198, 진입·이탈 제외 2.6 %, CI 상한 4.4 %) → 끔 유지(Q10)·`feet_touch` 0.9 %(위반 검출 1.9 %). 플랭크 C 시간 게이트(pike 제외 = 설계 §3.4 조건) 74.1 %, pike 포함 65.0 %, 정렬 충족 클립 거짓 멈춤 1/103. MM-Fit 윗몸 15세트(판별 끔): 중력 = 화면 위 ±1 12/15·정확 6/15·세트 밖 7, 중력 모름 ±1 13/15 — w06·w14 0회 세트 없음, 미달은 발쪽 카메라가 축각을 키운 것(w14 s1)과 완전 윗몸의 느린 상단·세트 끝 앉음이 크런치 출구(현 > 50° 1.5 s)에 걸린 것(w19 s0)이라 상수는 바꾸지 않았다. w19 sit_up 기각 18/28. 열린 문제: E(머리 쪽) 뷰에서 정점 `fc_yaw` ≤ 0.15 를 통과한 회의 sit_up 오기각 7/42(현 들림 107~155° = 투영 붕괴, `fc_ratio` 3.6~6.9) — 측면 게이트 보강 후보.

*§99 앱 배선(2026-10-07, 설계 §8·§10 5단계).* 사용자 결정 Q1(바닥 beta 음성 끔)·Q2(플랭크 목표 = 카메라가 확인한 시간 + 20 s 미확인 시 시계)·Q12 와 §9.2 권고 기본값(무릎 플랭크 멈춤·팔 편 플랭크 셈·골반 참고 띠 [−0.08, +0.16]·축소 관측 안 함·레그 레이즈 머리 바닥)으로 배선했다.
- **기각 음성 경로**(설계 §4.4): `PostureLive` 기각 블록을 `posture/FloorLive.kt` `RejectionCues.handle`(평가기 `rf` 는 있을 때만 창 소비) + `RejectCueGate`(6 s — 바닥은 사유별, 한 다리 계열은 종전대로 사유 무관 하나의 간격)로 뺐다. 원천은 `RepCounter.cueSource`. 바닥 종목은 `RepFormSpecs` 가 0개라 전에는 틱·이유가 안 나갔다 — `FloorWiringTest` 가 잠근다.
- **플랭크 시계**: `PlankHoldClock` 을 판정 칸(300 ms)마다 돌린다(사람이 없으면 `onFrame(t, null)`, 일시정지는 새 `pause/resume` — 시계 밖, 구간에 `pause`). 사건 음성은 `speakLatest`(시작 + 범위 문장 한 번·멈춤 사유별 6 s·5 s 뒤 not_prone 알림·폴백), 재개는 짧은 톤, 10 s 마다 인정 시간·남은 5 s 카운트는 숫자 읽기 설정(speak 대기열). 남은 시간은 `HoldBridge`(WorkoutSession.kt, 화면 파라미터 하나) → TrexApp tick 루프 → `SessionProgress.tick(heldDeltaMs)` — camera 면 인정 시간 증분, 폴백(한 번도 확인 못 함)이면 그때까지의 벽시계를 한 번 더해 '처음부터 시계로 잰 세트' 와 같게, 분석 소식이 20 s 끊기면 그 뒤 벽시계(이미 확인한 시간이 있으면 소급 없음). 경과·가동 시간은 벽시계. HUD 큰 숫자 = 인정 시간/목표(멈춘 동안 회색)·상태 줄·작은 경과.
- **정렬은 HOLD 칸만**: `PlankAlignmentTracker.add(gate)` — 시계가 HOLD 이고 그 칸에 멈춤 사유가 없을 때만, `fc_yaw` ≤ 0.15(키가 없으면 종전), 고개 |값| > 90° 는 관측 실패로 버림(worst 미기록). 세트 마감 `PostureAssessment.evaluate(holdSegments)` 가 같은 칸(차이는 HOLD 확정 전 1 s 소급분뿐).
- **준비 시드·종목**: 바닥 피처의 종목을 비교 추적기 대신 세트 시작 값(`FloorLiveState`)으로. 준비 단계에서 세 종목은 별도 추출기로 관측 층 피처를 남겨 운동 첫 프레임에 `standingSeedFrom → FloorCycleTracker.prepare`(앉아 있으면 심지 않음). 카운터에 `floorTracker.annotate`(재생기와 같은 순서). 크런치·레그 레이즈 `guideCue` 를 판정 칸마다(신호 3 s 결측 15 s 간격·누운 기준 없음 한 번, 재개 뒤 3 s 쉼).
- **Q1**: `FloorFeedback` 의 확인 필요·범위 복귀·플랭크 정렬·배치 음성 제거(화면 문장만), 바닥 강조는 빨강 대신 provisional 채널, 확인 필요 문장은 '참고' 칩. 바닥 발화 셋(L970·L1275·L1283)은 `speakLatest`. 관측 문장 음성은 다른 바닥 다섯 종목만.
- **문장**: 준비 = `FLOOR_PREPARATION`(세 종목만, 공유 `FLOOR_SIDE.voice` 그대로), 범위 = `PostureScope.FLOOR_LINES`(준비 화면 표시·세트 시작 음성 30분에 한 번·리포트 '범위 ·'). 리포트 = `PostureSetReport.floorLines`(플랭크 '버틴 시간 n초(카메라 확인) · 경과 · 멈춤: 사유별 · 처음 멈춤 · 정렬 확인 못 함|(참고) 정렬 이탈 k건', 폴백 '카메라 미확인 · 시계 기록', 반복 '센 회 n · 세지 않은 동작 m(사유별) · 판별 유보').
- **세트 로그**: `reps.config.lying`, `reps.rejected[]` 의 사유 상세, `reps.floor_reps[]`(상단 체류·하강), `reps.discarded`, `reps.identity_abstain`, `floor_chain{switches}`, `hold{…segments}`, 프레임 `floor_up`(관측 층에 넘긴 중력 — setlog_captures.py 가 U 줄의 정본으로 쓴다: 앱이 up 을 뒤집은 프레임도 재생 파리티, 중력이 아니면 U 줄 없음).
- **rules_floor_v0.json → floor_v0.5**: 플랭크 골반 띠 [−0.08, +0.16], `plank_neck_pitch` exclude. (리뷰 수정 뒤) 레그 레이즈 고개 window 규칙 exclude — rules 배열 집계 16 = beta 12 + exclude 4.
- 확인: 앱 유닛 611/611, 재생기 112/112, setlog_captures 자가 검증 51/51, `:app:assembleDebug` 통과, dexdump `PostureLiveSessionScreen` 레지스터 212(256 미만 — 기기 `compile -m verify` 는 폰 단계에서).

*§99 리뷰 결함 수정(2026-10-07).* 배선 리뷰 28건(엔진·안드로이드 배선·사용자 원칙 세 렌즈) 중 실제 결함을 고치고 테스트로 잠갔다. 근거(왜)와 바뀐 계약:
- **첫 회 잠정은 세트에 한 번**(`FloorCycleTracker`): 거둔 뒤 다음 회가 다시 '첫 회' 가 되어 9 s 간격 레그 레이즈 세트가 회마다 앞 회를 거뒀다(끝 수 0~1). 확인 창 = `FloorProfile.firstRepConfirmMs` = max(8 s, 최대 사이클 + 2 s)(크런치 8 s·레그 레이즈 12 s), 기각(세지 않은 동작)도 세트 시작의 증거라 확정, `resetCycle`(일시정지·카메라 전환)도 확정 — `RepCounter.resetCycle` 의 '쉬는 동안 거두지 않는다' 와 같은 결정(전에는 재개 첫 프레임에서 거뒀다).
- **거둠의 표시 원장**: `RepUnitAccumulator.retractFirst`·`onCounterRetracted` — 바닥 계열은 원장에서도 첫 회를 빼(리포트 '센 회'·로그 `reps.completed` = 화면 수) 같은 칸의 새 기각을 처리한 것으로 넘기지 않는다(틱·이유가 빠지지 않게). 덤벨 컬 팔별 경로는 같은 결함이 있으나 서서 하는 종목이라 이번에 바꾸지 않았다.
- **출구가 판별보다 먼저 걸린 회**: 느린 상단의 윗몸일으키기(현 > 50° 1.5 s)는 sit_up 판별 전에 출구가 걸려 무음이었다(MM-Fit w19 s0). 판별 사유가 있는 진행 후보는 보류했다가 5 s(`EXIT_RETURN_MS`) 안에 60 % 되돌아오면 그 사유로 기각·발화, 아니면 세트 끝(소리 없이). 출구로 기준을 잃고 5 s 못 잡으면 누운 기준 안내를 한 번 더.
- **크런치 `neck_only` 기본 켬**(Q6 = 4.5° 로 시작): 2 % 입장선은 회를 지우는 판별의 장치인데 이 사유는 주 신호가 출발하지 않은 동작에만 나 어떤 회도 지우지 않는다(엔진 재생 4.5 % = 세지 않은 동작에 이유를 말한 비율). 끄면 그 동작이 틱도 이유도 없이 사라진다.
- **신호 결측 안내는 빠진 관절로**: 크런치 현 들림은 발목, 레그 레이즈 허벅지는 무릎이 있어야 계산된다 — `signalLostCue(profile, missing)`(몸통/발목/무릎/모름).
- **up 이 없을 때**: `fc_torso_elev` 를 화면 위 기준으로 낸다(추적기는 누운 기준 대비로만 쓴다 — 레그 레이즈 출구·상체 들기가 산다), 플랭크는 `fc_flat_screen`(|발목x − 어깨x| ÷ 길이, 종전 `PlankGeometry` ≥ 0.7)으로 엎드림을 묻는다. `fc_axis_h` 만 유보. 관측 층 피처는 보이는 쪽 몸통이 관측된 프레임에만 붙인다(`computeForExercise` — 관절 11개짜리 프레임도 피처 맵이 비지 않아 '검출 = 측정 가능' 함정(§31a)으로 돌아갔다).
- **누운 기준 로그의 출처**: `reps.config.lying` 에 `prep`(1 = 준비 프레임, 0 = 세트 중)·`min`(준비 신호 최솟값). `restoreLying` 은 prep 일 때만 심고 establish 와 같은 상태(출발 최소점 = 준비 최솟값)를 만든다 — 재생 첫 회 출발이 한 칸 어긋나던 것·앉아서 시작한 세트의 출구 기록이 앱과 달랐던 것.
- **플랭크 폴백은 촬영 실패에만**: 20 s 동안 한 번도 확인 못 했고 처음 버티기 전 시간의 절반 넘게 화면 밖일 때, HOLD 후보 확인 중이 아닐 때. 몸을 보면서 무릎·골반·솟음·서 있음으로 기다린 세트는 폴백하지 않는다(무릎 플랭크가 시계로 플랭크 시간이 됐다 — Q3·원칙 #1). 대신 WAIT 사유가 5 s 이어지면 사유마다 한 번 말한다(`PlankHoldEvent.Kind.HINT`, `waitCue`), HUD 대기 줄도 사유별, WAIT 시간은 `waitMs`(로그 `hold.wait_ms`, 리포트 '확인 전: 무릎 n초').
- **폴백 뒤 음성**(`HoldVoice.voiced`): 폴백 안내만 — 남은 시간이 벽시계로 줄어드는데 '시간을 재기 시작해요'·'…멈췄어요' 를 말했다. `not_prone` 멈춤은 틱도 없다(`HoldVoice.stopCue`). 범위 문장의 30분 간격은 실제로 말할 때 시작(`FloorScopeVoice.peek/mark`).
- **남은 시간 다리**: 세트 마감(`FloorLiveState.finishSet` — 종료 확인 취소 포함)에 다리를 풀고 HUD 를 종전 남은 시간으로(묶어 두면 분석이 멈춘 세트가 20 s 동안 0 을 넘겼다). 분석 소식 20 s 끊김으로 다리가 벽시계로 넘어가면 HUD(`hudNow`)·리포트 출처가 clock. 넘어가는 틱의 이중 차감 제거. 세트 가동 시간(기록의 플랭크 초·근육 부하)은 인정 시간(`SessionProgress.tick` — 무릎·화면 밖 시간을 플랭크로 기록하지 않는다).
- **화면**: ATTENTION·RECOVERED 동안 큰 줄은 중립 측정 문장(`FloorReasons.liveMessage` — 전에는 '옆모습을 화면에 담아 주세요.' 로 떨어졌다). 플랭크 정렬 피드백은 시계 확인(held)을 먼저 묻고, 재는 중 정렬 준비가 안 되면 '이 각도에서는 정렬을 참고로도 보지 않아요 · 시간은 계속 재요'. 리포트 '처음 멈춤' 은 시계 시작(WORK) 기준(`HoldSummary.of` 가 스냅샷 `startAt` 을 쓴다). 사람이 거의 안 잡힌 플랭크 세트(검출 < 8)도 시계가 돌았으면 최소 리포트·로그.
- **재생기 파리티**(`Replay.runHold`·`holdTimeline`): 로그 `hold.start_t_ms`·`end_t_ms`·`segments` 의 `pause` → setlog_captures.py `hold_meta` → 메타 `loggedHoldStartMs/EndMs/Pauses/HeldMs/Source`. 재생기는 WORK 시작부터 300 ms '사람 없음' 칸을 다시 만들고 일시정지에서 시계를 멈춘다(결과 `syntheticNullFrames`·`parityHeld`·`paritySource`).
- 재생 영향(`floor_replay_tables.py` 를 고친 엔진으로 다시, 임시 폴더 — `data/floor_family/replay/results` 는 그대로): AIHub 크런치·레그 레이즈 C 클립별 센 회 **변화 0**(크런치는 기본 구성에 `neck_only` 이유가 50클립에 더해졌을 뿐), 레그 레이즈 `trunk_up` 중력 모름 변형 0/306 그대로(화면 위 기준 상체 기울기가 정상 회를 거르지 않는다). 플랭크 중력 모름 변형은 화면 수평도 엎드림이 프레임 1.3 %(C)·1.7 %(E)를 멈춤으로 — 시간 게이트 74.2 → 74.1 %(C). MM-Fit 15세트 정확·±1 그대로(기본 판별 2/15·7/15), **세트 밖 헛사이클 5 → 7(중력 모름 10 → 12)** — 전부 w18(완전 윗몸일으키기만 한 세트): 세트 앞 헛사이클이 예전엔 거둬졌는데 뒤이은 sit_up 기각이 '세트 시작' 증거로 확정했다. 기각이 첫 회를 확정하는 규칙의 대가이고, 정상 1회 + 기각 회가 정상 회를 지우던 반대 오류와 맞바꾼 것이다(폰 세션에서 다시 본다).
- 확인: 앱 유닛 629/629, 재생기 126/126, setlog_captures 자가 검증 54/54, `:app:assembleDebug` 통과, dexdump `PostureLiveSessionScreen` 레지스터 212(이번 수정으로 늘지 않음 — 그 Composable 에 지역 변수 0 추가).

*§99 후속 2 — 플랭크 멈춤 문턱 0.15 · 재개 이력 · 다수결 확정(2026-10-08, `docs/PHONE_REPORT_2026-10-07_DESIGN.md` §3.5 의 첫 구현).* 10-07 폰 플랭크 세트에서 골반을 바닥에 댄 값이 0.086~0.107 이라 `hips_low` 문턱 0.10 한가운데였고, '1 s 연속 위반' 확정은 한 프레임만 문턱 위로 튀어도 리셋돼 골반을 댄 7.5 s 가 유지로 적립됐다(버틴 시간 15.0 s 중 실제 ≈ 6 s). 바꾼 것(`PlankHoldClock`):
- `HIP_FLOOR_MIN` 0.10 → **0.15**. 근거: AIHub C 정상 프레임(무릎 ≥ 145°, `data/floor_family/designer/near_frames.parquet` 의 엔진 정의 `hip_floor`) p1 0.125 · p2 0.146 · p5 0.188 · 중앙 0.383 — 0.15 미만 2.3 %, 0.20 이면 6.3 %(수행자 최대 42 %), 0.25 면 15 %. 설계의 0.20~0.25 는 그래서 쓰지 않았다. 폰 버팀 0.38~0.53(AIHub 중앙과 같다)과 골반 내림 0.107 사이에 문턱 0.15 와 재개선 0.20 이 든다.
- 골반으로 멈춘 뒤 재개는 문턱 + `HIP_FLOOR_HYSTERESIS`(0.05) 위에서만 — 문턱 안팎의 흔들림이 재개·멈춤을 되풀이하지 않게.
- 멈춤 확정 = 최근 `STOP_CONFIRM_MS`(1 s) 판정 칸의 위반 시간 비율 ≥ `STOP_MAJORITY`(0.7) 이고 지금 칸도 위반. 회복 = 지금 칸이 정상이고 비율이 0.7 미만(보류분 적립). 그 사이는 보류가 이어진다. 끊김 안의 정상 칸은 멈춤으로 끝나면 확정 사유의 멈춤 시간(벽시계 = 버팀 + 멈춤 + 대기 유지). 대가: 0.9 s 끊김 뒤 회복 적립이 한 칸(300 ms) 늦는다(`PlankHoldTest` 갱신).
- 10-07 세트 재생(재생기 바닥 경로, `setlog_captures.py` → `.cap`): 첫 멈춤 13.9 s → 6.05 s, 버틴 시간 15.0 → 5.5 s, 골반 멈춤 7.75 → 15.8 s(세트 끝 1.2 s 는 재개선 위였으나 1 s 확정 전에 로그가 끝났다).
- AIHub 재생 표(`floor_replay_tables.py`, 전후): 플랭크 C 프레임 `hips_low` 0.4 → 1.4 %(trim 0.1 → 0.8 %), 확정 멈춤 `hips_low` 1 → 6/416클립, 정상 클립 거짓 멈춤 1/103 → 1/103, 정상 클립 인정 시간 688 → 673 s/927 s. 크런치·레그 레이즈 표는 변화 없음. AIHub 카메라는 서 있는 높이라 이 값의 폰 분포와 다를 수 있다 — 확정은 폰 2명 이상의 골반 내림 블록(`docs/exercises/floor_trial.md`).
- 검증: 앱 유닛 635/635(새 `PlankHoldTest.flickerAroundTheHipThresholdStopsAndIsNotCredited`), 재생기 테스트, `setlog_captures.py --self-test` 54/54. 폰 미검증, 시험 단계 유지.

## §99a. 레그 레이즈 데이터(FMS) · 바닥 준비 기준 · 양다리 판별 · 복귀형 v2 롤백 (2026-10-07, 브랜치 `claude/legraise-video-data-alternative-cac7cf`)

*번호 주의: 이 절은 `exercise-form-correction-5fe80e` 의 §99(바닥 계열)와 같은 날 다른 작업 폴더에서 §99 로 쓰였다. 두 폴더를 합치며(2026-10-08) 이 절을 §99a 로 옮겼다 — `docs/exercises/leg_raise.md`·`PHONE_REPORT_2026-10-07_DESIGN.md`·코드 주석의 "§99 ①②③" 은 이 절이다. ③ 양다리 판별(`hip_ang_maxside`)은 합치면서 §99 엔진의 `one_leg`(`fc_knee_gap`)로 대체했다 — 레거시 `hip_ang` 카운터 등록은 뺐고 피처는 로그용으로 남는다.*

레그 레이즈는 연속 영상이 없었다(AIHub 클립은 16장 키프레임). 공개 데이터를 조사해 FMS 데이터셋(Xing 2022, CC0 — 누워 한 다리 들기 ASLR, 15명 × 양쪽 × 3회)을 받아 앱 바닥 경로로 재생했다(`research/external_rep_replay/results/legraise_datasets_survey.md`, `results/fms_aslr/README.md`). 거기서 나온 세 문제를 사용자가 모두 진행하라고 했다(2026-10-07).

*FMS 재생이 보인 것.* 옆모습(후면 96 cm 카메라)에서 누운 몸 검출 100 %. 드는 쪽 고관절각 스윙은 중앙 71°로 전부 25° 이상이다. 반면 측면 낮은 카메라는 ASLR 에서 몸을 머리·발 끝에서 본다. 이 구도에서는 무릎이 가려져 거의 세지 못한다 — 폰을 발·머리 쪽에 두면 안 된다는 근거다. 다음 회의 준비 자세가 이어지는 회 60개 중 진단 신호(드는 쪽)가 놓친 3회는 카메라가 아니었다. 2회(s03)는 복귀 띠(아래 ①)였다. 1회(s10)는 진단 신호의 가시성(관절 ≥ 0.5)이 준비 3.3 s 동안 없어 기준이 올린 자세에 박힌 것으로, 앱 신호(중점, 무릎 ≥ 0.35)에는 없는 누락이다. ②는 이 누락이 아니라 "카운트다운 직후 바로 움직이는" 경우를 위한 것이다(흉내로 잰 효과는 아래).

*① 복귀형 추적기 v2 (`ReturnRepTracker`, 모든 복귀형 종목 공통) — **구현했다가 같은 날 되돌렸다.*** 사용자 결정(2026-10-07 오전, 폰 보고 뒤 "복귀 판정 범위 재검토가 문제로 예상되니 롤백"). 앱·로그 엔진은 v1(`return_v1`) 그대로이고, v2 의 코드·테스트·픽스처 변경(7개 파일)은 `research/external_rep_replay/results/return_v2.patch` 에 남겼다. 아래 v2 설명과 A/B 수치는 기록이다. 참고로 그 폰 보고의 세트(`sets-20261007.jsonl`, 00:05~00:11 UTC)는 로그 엔진이 `return_v1`·`floorcycle_v1_beta`·`plankhold_v1_beta` 라 **v2 가 들어 있지 않은 빌드**(다른 작업 폴더 `exercise-form-correction-5fe80e` 의 APK, 02:53 KST)였다 — 롤백은 보고된 현상을 바꾸지 않는다. 보고된 문제의 원인과 설계는 `docs/PHONE_REPORT_2026-10-07_DESIGN.md`.

v1 은 처음 3샘플 가만히 있던 자세를 세트 내내 기준으로 고정하고, 복귀를 기준 ±0.22 × 진폭 안으로만 본다. 그래서 쉬는 자세가 띠 밖이면 세트 끝까지 0회다.

- 지나친 복귀 — FMS s03 시작 171°·내린 다리 177°. MM-Fit 스쿼트 w18 은 세트 전 느슨한 자세 154° 가 기준, 세트 중 선 자세 165°.
- 덜 돌아온 복귀 — MM-Fit 런지가 매번 끝까지 펴지 않는다.

v2 의 복귀는 방향으로 본다. 이번 회가 벗어난 쪽으로의 이탈 d 가 다음 중 하나면 복귀다.

- d ≤ max(0.22 × 진폭, 깊이/3) — 깊이의 2/3 이상 되돌아옴
- 기준을 진폭 미만으로 지나침

진폭 이상 지나치면 반대쪽의 새 움직임이다. 로그 엔진 표기는 `return_v2` 다(`RepEngineLog.ENGINE_RETURN`, 골든 픽스처 갱신).

재생 A/B 는 같은 캡처를 v1(HEAD 5213e3c4 재생기 사본)·v2 로 돌렸다(`tracker_ab.py`, 앱 소스를 그 자리에서 컴파일하는 재생기).

| 코퍼스 | 정확 일치 v1 → v2 | 과다 세트 | 0회 세트 | 세트 뒤 헛카운트 |
|---|---|---|---|---|
| MM-Fit 스쿼트 64(앞 15 s · 뒤 10 s 휴식 포함) | 0.36 → **0.78**, MAE 2.62 → 0.44 | 0 → 0 | 6 → 0 | 27 → 13 |
| MM-Fit 런지 62 | 0.18 → **0.95**, MAE 3.08 → 0.11 | 0 → 0 | 3 → 0 | 35 → 15 (앞 17 → 19) |
| REHAB24-6 스쿼트·런지 36 | 0.06 → 0.22 · 0.06 → 0.11, MAE 10.11 → 6.56 · 7.83 → 4.94 | 0 → 0 | — | — |
| MM-Fit · 폰 덤벨 컬(팔별 경로) | 변화 없음 | | | |

REHAB 의 남은 손실은 신호 자체가 없는 구간이다(예: 세로 카메라에서 앞 113 s 동안 사람이 잘림).

폰 90세트 중 바뀐 6세트는 정답 라벨이 없어 신호와 대조해 판정했다.

- 런지 09-26 17:27: 12 → 13. 하강이 13번이다.
- 런지 09-26 19:48: 13 → 16. 약 17번이고, 15 s 이후 걸음 사이 선 자세가 150° 로 바뀌었다.
- 스쿼트 09-23 14:38(204 s): 9 → 26. 하강마다 1:1 이다.
- 스쿼트 09-25 10:52: 7 → 9. 문서의 "7회" 는 앱이 센 수이지 정답이 아니다(설계 §21.1 — 발 너비·발끝을 바꾼 스쿼트와 무릎 들기를 섞었다). 더한 2회는 양 무릎 판별을 통과한 최저 113°·100° 사이클이다.
- 오버헤드 프레스 09-12: 2 → 6. 두 버전 모두 틀린다 — 기준이 '팔 내린 자세' 에 고정돼 어깨 → 머리 위 프레스 4회를 못 세고, v2 는 내렸다 올리기 4회를 센다. 팔 들기 계열 온보딩 때 다룬다.

띠는 복귀형 경로의 파이썬 사본(재생기 v1 과 세트 수 일치 — 스쿼트 판별 게이트를 뺀 2세트만 다름)으로 넓이를 훑은 뒤 재생기로 확인했다. 띠를 넓힐수록 MM-Fit 이 좋아지지만 1/2 은 반만 올라온 반동을 1회로 만든다. 잡음뿐인 구도(FMS 끝-방향)의 한 회 두 번 세기도 늘어난다(60회 중 — 파이썬 사본 시뮬레이션 1/4: 4, 1/2: 9 · 재생기 1/3: 6, v1: 1). 실기기 푸시업 기록(`rep_fixture_baseline1.txt`, 정답 3~4)은 1/4 에서 1회, 1/3 에서 4회였다 — 1/3 을 고른 이유. 멈춤 재기준(이동 중 8 s 넘게 머물면 기준을 옮김)과 회마다 기준 갱신은 시뮬레이션에서 이득이 없어 넣지 않았다.

*② 바닥 준비 기준 심기 (`PostureLive`·`RepCounter.standingSeedFrom`).* §89 후속 2(서서 하는 종목)를 바닥 종목에도 쓴다.

- 준비 프레임의 바닥 피처는 준비 전용 추출기(`LiveSessionRefs.prepFloorRef`)로 만든다. 세트 추출기의 접지선을 오염시키지 않기 위해서다.
- 접지선 신호(크런치 `head_ground`)는 두 추출기의 접지선이 달라 심지 않는다.

재생기는 메타 `prepUntilMs` 로 준비 구간을 흉내 낸다(`fms_prep_variants.py`). FMS 30열에서 첫 들어 올림 300 ms 전까지를 준비로 보면, 첫 회를 센 열이 다음처럼 바뀐다.

| 신호 | 심지 않음 | 심음 |
|---|---:|---:|
| 드는 쪽 신호 | 0/30 | 20/30 |
| 앱 신호(중점) | 6/30 | 15/30 |

심지 못한 열은 준비 1.5 s 동안 다리가 5.5° 넘게 흔들렸거나 신호가 없었다.

*③ 레그 레이즈 양다리 판별 (`RepSignals` '라잉 레그 레이즈', `PostureFloor` `hip_ang_maxside`).* 한 다리만 들어도 양측 중점 `hip_ang` 이 25° 넘게 움직인다(FMS 66 %). 판별 신호는 몸통축과 각 무릎의 고관절각 중 더 편 쪽이고, 문턱은 20° 다(창 안 원값 최대−최소). 모집단 근거는 두 갈래다.

- AIHub '라잉 레그 레이즈' 416클립 × 5뷰(33,280장)를 앱 모델로 새로 추론했다(`aihub_floor_mp.py`, `legraise_identity.py`).
- FMS 후면 90회.

| 문턱 | 양다리 기각 — AIHub A·B·D·E | 정면 뷰 C | 한 다리 기각 — FMS |
|---:|---|---:|---:|
| 15° | 0 % | 3.4 % | 74 % |
| **20°** | **0~0.2 % (95 % 상한 ≤ 1.1 %)** | 4.1 % | **96 %** |
| 25° | 0~1.2 % (A·B 상한 2.2~2.5 %) | 5.8 % | 100 % |

원칙 #7 의 입장 조건(정상 반복 기각 ≤ 2 %)을 측면·사선 뷰 모두 상한까지 지키는 것이 20° 다. 정면 뷰 C 는 몸을 머리·발 쪽에서 보는 앱 안내 밖 구도다.

재생에서 FMS 한 다리 회는 앱 구성으로 30/60 → 0/60 이 됐다(전부 기각). 판별 표본이 2개 미만이면(먼 무릎 가시성 < 0.35) 판정하지 않고 센다. 기각은 낮은 틱과 "두 다리를 함께 들어 주세요" 를 두 모드에서 6 s 에 한 번 낸다(`RepSignal.identityCue` — 반복 검사기가 없는 종목만, 스쿼트의 무릎 들기는 지금처럼 침묵).

*검증.*

- 앱 유닛 전부 통과 — 새 테스트 `LegRaiseCounterTest` 5건 · `FloorFeaturesTest` 1건(v2 롤백 뒤 563건: `ReturnRepTrackerTest` 4건과 `RepHysteresisTest` baseline1 live 4 기록은 패치와 함께 빠졌고, `LegRaiseCounterTest` 의 '먼 무릎 미관측' 회는 쉬는 프레임도 미관측으로 바꿨다 — v1 은 복귀 뒤 둘째 쉬는 프레임에서 회를 닫는다).
- 재생기 통과, `setlog_captures.py --self-test` 50/50, APK 빌드. `PostureLiveSessionScreen` dex 레지스터 205(256 미만, 본문에 지역 변수를 더하지 않았다).
- `score_phone_reps.py --self-test` 41/42 는 변경 전 빌드에서도 같은 1건(번갈아 하는 컬 합성의 새 코어 기대값)이라 이번 변경과 무관하다.
- **폰 미검증** — 레그 레이즈 폰 세트가 아직 없다(C 등급 완료 기준 = 폰 한 세트로 횟수 확인).

*후속 1 — 크런치·레그 레이즈·플랭크 '자세 교정 시험 단계' 표시(사용자 결정 2026-10-08).* 2026-10-07 폰 보고에서 세 종목이 틀린 동작을 세고 교정을 말하지 않았다(원인·설계 `docs/PHONE_REPORT_2026-10-07_DESIGN.md`). 구현·폰 검증 전까지 앱이 먼저 밝힌다(원칙 #2): `posture/PostureTrial.kt`(앱 이름·규칙 이름 두 집합, 문구) → 운동 목록 스위치 아래 "시험 단계"(`WorkoutListScreen`, `Workout.postureTrial()` in `PostureSupport.kt`), 편집 화면 "자세 교정 시험 단계 · 촬영 방향"(`WorkoutEditor`), 세트 시작 안내·소개 첫 문장과 카드 부제(`PostureScope.startLine`·`cardLine`). 카메라는 켤 수 있다 — 승격 조건인 폰 블록 세트가 그 로그에서 나온다. 해제 조건과 프로토콜은 `docs/exercises/floor_trial.md`. 테스트 `PostureTrialTest` 3건.
