# 2026-10-08 폰 보고 12건: 교차 통합 설계

## 0. 결론

1. **설계 12개가 같은 줄과 같은 함수를 서로 다르게 고친다.**
   - `PostureLive.kt:1203`(`rejectGate.takeRecovery()`)은 9개 설계가 각자 고친다. 9개 모두 시그니처가 다르다.
   - `IdentityCueSource`(`RepCounter.kt:1057`)는 5개 설계가 각자 메서드를 하나씩 더한다(`recovers`·`recoveredBy`·`recoveryConfirmed`·`silent`·`motionFor`).
   - 숫자 발화(`PostureLive.kt:1148`)는 3개 설계가 서로 다른 정책을 낸다(TTL 2 s / 쪽별 칸 / 같은 키 최신만).
   - 따로 머지하면 충돌하거나 의미가 섞인다. 그래서 **공용 기반 3개(F1 판별 사유 레지스트리, F2 음성 대기열·경계, F5 측정 파이프라인)를 먼저 깔고** 종목 설계를 그 위에 올린다.
2. **원칙·테스트 위반이 설계 단계에서 이미 4건 있다.**
   - `FormMotionTest.kt:15`에 걸리는 것: kneeup·curl_swing 의 beta 검사에 motion 이 있다.
   - `FormMotionTest.kt:16-19`에 걸리는 것: lunge 의 SHIP 검사에 motion 이 없다.
   - gaze '시선 변화'는 §90 사전값 자르기를 받는다(`rep_priors.py:32` REL ∋ FIRST_REPS_DELTA, `RepForm.kt:926-933`). squat RC3 와 같은 계열의 측정 기하 불일치 위험이다.
   - endcut 은 countdown 이 지우는 `holdStart` 에 의존한다(`CapturePreparation.kt:182·231` ↔ endcut 의 `CapturePreparationUi` 변경).
3. **VerifyError 여유를 실측했다**(현재 APK, dexdump, `work/integration/dump_11.txt`).

   | 메서드 | 레지스터 | 비고 |
   |---|---|---|
   | `PostureLiveSessionScreen` | 212 | 한계 약 256 |
   | 분석 람다 `PostureLiveKt$PostureLiveSessionScreen$32$1.invokeSuspend$lambda$1` | 119 | 1091~1240 줄이 여기 속한다 |
   | `CapturePreparationPanel` | 179 | |
   | `TrexApp$lambda$0$76` | 90 | |

   대부분의 설계는 분석 람다 안을 고치므로 안전하다. 위험한 것은 Composable 본문의 `SkeletonStage(...)` 호출(1431-1441, `Box` 인라인 내용)에 인자를 더하는 sidelunge(f)와 arrows(i)뿐이다.
4. **누락된 문제가 있다.**
   - **크로스 런지 세트(줄 11)는 0회/기각 7회**인데 12건 중 어디에도 없다. 기각 직전 3 s 창의 무릎 최소가 71.7~105°였고, 발이 이미지 밖인 프레임이 49/148이었다(추론, 미검증).
   - **스탠딩 사이드 크런치 창 규칙 '시선 정면 유지' 음성**은 sidecrunch 와 gaze 가 서로에게 넘겨서 담당 설계가 없다.
5. **폰 작업을 줄이는 핵심**: 세트 로그에 좌표(xy·w·up·vis)가 남는다. 그래서 문턱을 정하는 라벨 블록은 **지금 설치된 빌드로 먼저 찍고**(세션 A) 재생으로 판정할 수 있다. 새 빌드에는 UX 확인(세션 B)만 남는다. 각 설계가 따로 제안한 약 60세트를 2회 세션으로 줄일 수 있다.

---

## 1. 설계 간 충돌·중복과 해소안

### 1.1 같은 함수·같은 줄을 여러 설계가 다르게 바꾸는 곳

| 위치 | 바꾸는 설계(제안 내용) | 충돌 | 해소안 |
|---|---|---|---|
| `PostureLive.kt:1203` `takeRecovery()` (+ `FloorLive.kt:214`) | curl_swing `takeRecovery(countedClean, alreadyPraised)`+헬퍼 · kneeup `takeRecovery(source)`+`(!gate \|\| lastRep?.correct != false)` · sidecrunch `pendingSource.recoveryConfirmed` · sidelunge `silent` 사유는 대기 안 심음 · crunch_arms `takeRecovery(context, judgedPass)`→`repSpeech` · gaze gazeRep 합치기 순서 · endcut `takeRecovery(heard)` · arrows `takeRecovery(counted sides)`+화살표 해제 · curl_onearm `next()` 에서 `recovers` | 9개 시그니처. 순서에 따라 서로의 조건을 덮어쓴다 | **F1 `RecoveryContext` 하나**로 합친다(§2 F1). 1203 줄은 `refs.floorLive.repSpeech(now, rc, ctx)` 한 줄로 바꾼다(분석 람다 안이라 레지스터 영향 없음) |
| `RepCounter.kt:70` `cueSource` | curl_swing `CurlRejectCues` · curl_onearm `PairedArmCues` | 같은 팔별 경로에 객체가 둘이다. onearm 은 `torso_tilt2d·upperarm_vert`→null(틱), swing 은 문장 | **객체 하나**(`PairedArmCues`)로 합친다. 사유는 `upperarm_vert_*`·`torso_tilt2d`(swing 문장)와 `arm_unseen_*`(onearm 문장) |
| `RepCounter.kt:1057` `IdentityCueSource` | 위 5개 메서드 + arrows `motionFor` | 메서드가 계속 붙는다 | 사유 메타 하나 `ReasonSpec(cue, class, motion, recovery)`를 돌려주는 `spec(reason)`로 합친다(F1) |
| `PostureLive.kt:1148-1152` 쪽별 숫자 | lunge `speakCount`(2 s TTL 버림) · kneeup 쪽별 최신 칸·'끝' 보존 · endcut `speak(key=side)` 같은 키 최신만 | 정책 셋 | F2 `SpeechQueue` COUNT(key) 하나로 합친다. TTL 여부는 사용자 결정 U4 |
| `PostureLive.kt:1625` `speakRep` · 플랭크 `HoldAnnounce` | endcut key="n" · gaze tail 가드 8.6 s · countdown(바닥 범위 문장 제거로 지연 해소) | 같은 숫자 경로 | F2 로 합친다 |
| `TrexApp.kt:266` `nextSession` 의 `speech.stop()` | countdown: PREPARE→WORK 에서 건너뜀 · endcut: 자동 진행(WORK→다음)에서 `handOff()` | 같은 줄, 다른 분기 | `when { PREPARE·정상시작 → stop 안 함(현재 문장 끝까지); 자동 진행 → handOff(); 그 밖(수동·나가기) → stop() }` |
| `CapturePreparationUi.kt:76-97` | countdown: holdStart·12 s 마감 삭제, 문장 계획 루프, 톤 카운트 · endcut: `cancel()`→`stopKeepingTail`, `deadline += tailRemainingMs`, **꼬리 동안 `holdStart(true)`** | **endcut 이 countdown 이 지우는 장치에 기대고 있다** | `holdStart` 는 지운다(countdown). 대신 카운트 진입 시 `speech.tailActive` 를 `introPlaying` 으로 취급해 그 카운트를 **톤**으로 낸다(countdown `CountdownVoice.mode`). endcut 의 deadline 변경은 12 s 마감이 사라지므로 불필요하다 |
| `CapturePreparation.kt` `inspect`·`CaptureFrame` | countdown `directionSettled`·바닥 이동 문구 · crunch_arms `TRUNK_LEGS`(:48) · sidelunge `lateralReach` · curl_onearm D4(먼 손목 요구 안 함) | 같은 함수 확장. 의미 충돌은 없다 | 한 커밋에서 머지하고 `CapturePreparationTest` 를 한 번에 갱신한다 |
| `ExerciseProfiles.kt` 준비 문장 | countdown `preparationLines`(ESS ≤24음절) · kneeup `REFERENCE_HINTS` 니업 · curl_onearm `ARM_PAIR_COUNT_RULE` · crunch_arms 크런치 세는 조건 · gaze 시선 목표(WORK 시작) | **한 문장 예산을 여러 설계가 다툰다**(크런치 3후보, 니업 2, 컬 2, 스쿼트 2) | §1.2 의 ESS 표를 사용자 결정 U6 으로 승인받는다 |
| `PostureLive.kt:1305-1316` 창 규칙·커버리지 음성 | endcut flush→speakLatest, `boundaryUntil` 삭제(:1684, 읽기 :1011·1305·1334·1343) · sidelunge `framingRef.takeSpeech`→speakLatest | 같은 블록 | 한 번에 머지한다 |
| `PostureLive.kt:1169` 통과 회 화살표 지우기 · 1179·1184 | arrows `origin==REPFORM` 만 지움, `MotionCue.of(side, view)` · squat `firstExclusion` 문구 · endcut heardKey `rf:<id>` | 같은 4줄 | 한 번에 머지한다 |
| `SkeletonStage(...)` 호출 1431-1441(**Composable 본문**) | sidelunge `fullWidth`·`edgeGhost` · arrows cue 조건에서 `!isFloorExercise` 삭제 | 레지스터 212 에 인자 임시값이 더해진다 | 두 인자를 묶은 `StageOptions` 객체 하나(홀더 필드)로 넘긴다. 통합 뒤 dexdump ≤ 230 을 합격 조건에 넣는다 |
| `RepForm.kt` `VERSION`(:1186 `repform_v0.9`) | curl_swing·lunge 가 각각 v0.10. squat·kneeup·gaze·sidelunge 는 언급 없음 | 번호가 겹치고 누락된다 | **릴리스당 한 번** 올린다. CLAUDE.md 의 `rules_version` 도 같은 커밋에서 바꾼다 |
| `LegCycle.kt:449` `legcycle_v3_beta` | sidelunge v4 · kneeup·sidecrunch 는 identity 를 바꾸는데 버전 언급 없음 | 로그·재생 파리티의 엔진 키 | 세 종목 변경을 합쳐 v4 한 번 |
| `FloorCycle.kt:548` `floorcycle_v1_beta` | crunch_arms 가 identity·골·깊이를 바꾸는데 버전 언급 없음 | 같음 | v2 로 올린다 |
| `RepFormPriorTable.kt`(생성) | squat 키 버그 수정과 `prior=false`(발 간격·좁음) · lunge B 어깨 사전값 소멸 · gaze 새 FIRST_REPS_DELTA 검사 → 사전값 생김 · curl_swing·kneeup 은 무변경 기대 | 재생성을 따로 돌리면 diff 해석이 섞인다 | 한 단계에서 **한 번** 재생성하고, diff 를 검사별로 설명한다. 키 수정은 `prior=false` 와 **반드시 같은 커밋**이다. 키만 고치면 '좁음'에 지금 없는 사전값이 붙어 동작이 바뀐다(`rep_priors.py:68`, 키 = `c["name"]` = id 마지막 토막) |
| `family_scorecard.py:57-61` `PHONE_BLOCKS["바벨 스쿼트"]` | squat `{정상,발끝밖,발끝안,넓게,좁게}` · gaze `{정상,천장,발끝,위,아래}` | **같은 키를 두 번 정의하고, "발끝"(시선)과 "발끝밖"(발 방향)이 헷갈린다** | 한 딕셔너리로 합치고 시선 블록은 `시선천장·시선발끝·시선위·시선아래` 로 쓴다. 또 `outcome_by_name`(`family_scorecard.py:76-78`)은 id 마지막 토막으로 찾는데 '시작'이 두 검사에 있다(`RepForm.kt:1572·1588`). id 기반 조회로 바꾼다 |
| `PostureScope`·`RepFormRules`·`PostureSetReport` 문장 | squat·curl_swing·lunge·gaze·crunch_arms·sidecrunch·kneeup·arrows·sidelunge(HUD `audibleRejected`) | 범위·요약 문장이 9개 설계에서 따로 바뀐다 | 화면 문구는 한 단계(P4)에서 일괄 수정한다. `PostureScopeTest`·리포트 테스트도 한 번에 갱신 |

### 1.2 음성 경로 충돌: 세트 시작 한 문장 예산(ESS)

countdown 설계에 따르면 이미 준비된 사용자가 시작(최선 5.42 s) 전에 들을 수 있는 말은 약 24음절이다(실측 410+194 ms/음절). 여기에 후보가 몰린다.

| 종목 | countdown ESS 초안 | 경쟁 후보 | 통합 추천(U6) |
|---|---|---|---|
| 덤벨 컬 | "처음 두세 번은 팔꿈치를 옆구리에 붙여 정확히 해 주세요."(23) | curl_onearm "왼팔과 오른팔이 한 번씩 올라가야 1회예요…"(3문장) | ESS = "양팔을 한 번씩 올려야 1회예요."(약 13). 한 팔 회를 이제 세지 않으므로 안 들으면 버그로 느낀다. 기준 문장은 DET·화면 |
| 스탠딩 니업 | "무릎을 접은 채 골반 높이까지 올려야 세요."(17) | kneeup "처음부터 높이 올려 주세요" + COACH 조건 | '무릎 높이' ship 전에는 countdown 안. ship 뒤 COACH 는 "처음부터 무릎을 높이 올려 주세요. 그 높이로 셉니다." |
| 크런치 | "누워서 무릎을 세우면 세기 시작해요."(15) | crunch_arms "처음 세 번은 끝까지 말아 올려 주세요. 그 높이로 셉니다."(22) · gaze "올라올 때 턱을 살짝 당기고 무릎 쪽을 보세요."(WORK 시작마다) | COACH ESS = crunch_arms 문장(이력 있으면 "지난번 높이만큼…"). 눕기 안내는 PLACE, 시선은 DET·화면 |
| 플랭크 | "다리를 펴고 골반을 들면 시간을 재기 시작해요."(19) | gaze "시선은 양손 사이 바닥에 두세요."(세트마다 WORK 시작) | ESS 는 유지. 시선은 준비 단계 DET(시간 될 때만) + 화면 + 리포트 |
| 기본 스쿼트 | "스쿼트 설계가 정함" | gaze "시선은 정면의 한 점에 두세요." · squat(문장 없음) | COACH ESS = "발 너비와 발끝은 첫 동작 자세를 유지해 주세요." 시선은 DET |

gaze 의 'WORK 시작마다 2.7~3.6 s 시선 목표 문장'(`PostureFloorLive.kt:144-153` startWork 변경)에는 문제가 둘 있다.
- countdown 의 원칙 '말이 제때의 신호를 막지 않게'와 정면으로 부딪친다.
- endcut 대기열에서는 첫 숫자·첫 교정 앞에 줄을 선다. 크런치는 범위 문장 하나 때문에 숫자가 이미 10.78 s 밀렸다.

그래서 **세트마다 말하지 않고, 준비 계획의 DET 와 리포트 줄('시선 · 판정 n회 · 이탈 0')로 '켜져 있음'을 보이는 쪽**을 추천한다. gaze (C) 리포트 변경이 이 역할을 한다.

### 1.3 게이트·판별 정책의 불일치

1. **'못 봤을 때 세나'가 설계마다 다르다.**
   - 세지 않음: curl_onearm `arm_unseen` → 세지 않고 이유를 말함. sidelunge `edge·blip` → 세지 않고 무음.
   - 셈: sidecrunch 정면 밖 → 유보(셈). kneeup 옆 뷰 → 유보(셈).
   - 실제로는 일관된 규칙 하나로 정리된다: **"반복의 존재 자체(그 팔·그 다리의 사이클)를 관측하지 못했으면 세지 않는다. 존재는 관측했고 질(방향·높이)만 못 봤으면 센다(유보=통과)."**
   - 이 규칙은 '유보는 통과'(원칙 #7)의 예외가 아니라 그 적용 범위를 정하는 것이다. U1 로 한 번에 결정받는다.
   - 표시 정책도 하나로 맞춘다. sidelunge 는 HUD '세지 않은 동작'에서 빼고, onearm 은 기각 수에 넣는다. 통합안은 사유 등급 OBSERVATION(촬영 안내 1문장, 틱 없음, 교정됨 없음, HUD '못 본 동작 n' 별도 줄)과 PHANTOM(무음)이다.
2. **'이 회는 세지 않았어요'를 다시 쓸지가 다르다.**
   - 이 문구를 쓰는 곳: squat 2-(b), crunch 부분 판정(세트 처음 2회), 기존 컬 ROM(`MAX_INVALID_CUES = 2`, `PostureLive.kt:216`).
   - 반대로 `PostureLive.kt:1128` 주석은 §7 소리 간소화로 "틱이 이 문장을 대신한다"고 결정했다.
   - curl_swing·lunge·kneeup 의 새 차단에는 언급이 없다. U2 로 한 번에 정한다. 추천: 검사(사유)별 세트 첫 1회.
3. **교정 대기 수명이 다르다.** kneeup 15 s, endcut 교정 줄 TTL 8 s, arrows 화살표 12 s(`MOTION_CUE_MS`), crunch_arms 무기한(judgedPass null 동안). 추천은 12 s 하나로 맞추는 것이다(eventFor 쿨다운과 같은 수).
4. **가까운/먼 팔을 정하는 방법이 셋이다.**
   - curl_swing: 프레임별 `Arm2d.nearSign(yawOf(frame))`.
   - curl_onearm: 15프레임 요 중앙값.
   - arrows: 반복 잠금 뷰 `rep.view`(최근 5회 중앙값, `lockView`).
   - 경계 요(16.4°·46.2°)에서 판정한 팔과 화살표를 붙인 팔이 갈릴 수 있다. **반복당 한 번 정한 near 를 `RepFormRep` 에 싣고 셋이 공유**하게 한다. 사선 판정을 쓰는 sidecrunch(선 자세 뷰)와 countdown(준비 8프레임)도 같은 `ViewEstimator` 함수로 창 길이만 달리하게 한다.

### 1.4 원칙·함정 위반(설계 단계에서 발견)

| # | 설계 | 위반 | 근거 | 해소 |
|---|---|---|---|---|
| a | kneeup '무릎 높이', curl_swing '팔 반동' | BETA 인데 `motion` 지정 | `FormMotionTest.kt:15` "beta 는 화살표 없음" → 테스트 실패. 원칙 #2 | motion 은 **ship 으로 올리는 커밋에서** 넣는다 |
| b | lunge '어깨 기울기' | SHIP 인데 motion 없음("화살표 설계와 같이 정한다") | `FormMotionTest.kt:16-19` → 실패. arrows D1(말한 문장 = 단서)에도 어긋남 | arrows 의 `MARK` 어휘가 먼저 들어가야 한다(P4 전 ship 불가). 또는 임시 `SHOULDERS UP`. 추천: MARK. 순서를 강제한다 |
| c | gaze '시선 변화'(FIRST_REPS_DELTA) | §90 사전값이 자동 생성돼 기준을 자른다 | `rep_priors.py:32` REL ∋ FIRST_REPS_DELTA, `RepForm.kt:926-933`(양방향 띠면 refLo·refHi 모두로 자름). gaze 는 "AIHub·MM-Fit 은 수평 카메라 가정, 폰과 절대값이 다를 수 있다"고 스스로 적었다 | squat 가 도입하는 `prior=false` 를 이 검사에도 준다. 또는 이 사용자의 −15.6°가 사전값 [refLo, refHi] 안인지 재고 결정한다. squat RC3(발 간격 사전값 단위 불일치)와 같은 계열 |
| d | endcut ↔ countdown | 지워지는 `holdStart` 에 의존 | `CapturePreparation.kt:182·231`, `CapturePreparationUi.kt:85·91` | §1.1 표대로 톤 카운트로 대체 |
| e | arrows D2(바닥 화살표) | 바닥은 '빨강=ship 전용' 관례라 강조를 비운다 | `PostureLive.kt:1433` "바닥은 전부 beta — 빨강(ship 전용) 대신 '참고'", 1434 highlight emptySet | 판별 기각은 beta 규칙이 아니므로 원칙 위반은 아니다. 다만 화면 관례와 충돌하므로 **바닥 화살표 색을 참고색(호박)으로 할지** U7 에 넣는다 |
| f | crunch_arms 부분 판정 음성 | 바닥 Q1(beta 무음)의 경계 | 판별의 비율 조건을 COACH 전용 '부분'으로 옮기므로 beta 규칙 음성은 아니다. 다만 #7 입장 측정이 불가능한 COACH 게이트다(설계가 인정) | U15 에서 시험 단계 예외로 명시해 결정받는다 |
| g | VerifyError | Composable 본문 변경 | 위 표: 212/256. 분석 람다 119/256 이라 1091~1240 줄의 지역 변수 추가(endcut SpeechDraft, curl_onearm `.any{}`)는 안전하다. curl_swing 의 "인라인 람다도 금지"는 과잉 경계다 | Composable 본문(`SkeletonStage` 인자, `var … by remember`, LaunchedEffect 키)만 막는다. **분석 람다·CapturePreparationPanel(179)도 dexdump 로 측정 대상에 넣는다** |
| h | 300 ms 격자 | 위반 없음 | kneeup 85 ms 허벅지 최소 보류, sidecrunch ContactMinimum 관측 키만 추가, sidelunge 85 ms 는 표시 전용 | 유지 |

---

## 2. 공용 기반으로 묶을 것

### F1. 판별 사유 레지스트리와 교정 판단 하나

- **`ReasonSpec`**: 사유마다 다음 칸을 한 표에 둔다.
  - `cue`(문장)
  - `class` ∈ {POSTURE(틱+이유+교정 대기), OBSERVATION(촬영 안내, 틱 없음, 교정 없음), PHANTOM(무음, HUD 제외)}
  - `motion`(화살표·점)
  - `recovery`(같은 쪽 요구, 여유 함수, 맥락 키)
  - `label`(HUD·리포트)
- 표 위치는 `LegCycle`·`FloorCycle`·`PlankHoldClock`·`PairedArmCues` 각 문장 표 옆이다.
- 등급별로 들어갈 사유:
  - POSTURE: kneeup `shallow_straight`, crunch `arms_only`·부분, curl `upperarm_vert`·`torso_tilt2d`.
  - OBSERVATION: curl_onearm `arm_unseen_*`.
  - PHANTOM: sidelunge `blip`·`edge`.
- **`RecoveryContext`**: {사유 또는 검사 id, `heard`(재생 시작 장부, F2), `side`, `margin`, `countedClean`(그 회가 COACH 에서 셌고 다른 차단·부분 없음), `judgedPass`(유보 아님), `context`(크런치 팔 자세)}.
- 칭찬 조건 = 모두 참일 때. 못 들은 지적은 거둔다(endcut). 대기 수명은 12 s 다.
- 이 하나로 해결되는 것:
  - kneeup 경계 칭찬 2건과 반대 다리 칭찬.
  - arrows 의 반대 다리 해제 6/10.
  - sidecrunch 의 경계 칭찬.
  - curl 11회 반동 회 칭찬.
  - crunch 자세 전환 칭찬 2건.
  - endcut 의 못 들은 지적 칭찬 4/25.
- 테스트: `CueMotionCoverageTest`(arrows)와 `RepFormLinesTest` 를 '레지스트리 모든 사유에 칸이 다 찼다'로 확장한다.

### F2. 음성 대기열·세트 경계(`SpeechQueue`, endcut 안 기반)

- 숫자는 COUNT(key) 최신만 남긴다. 코칭은 보류 하나를 두고 숫자와 번갈아 낸다. 세트 끝은 꼬리 넘김과 봉인 300 ms, 세트 범위 줄을 정리한다. 들림 장부를 둔다.
- 이 위에 다음이 올라간다.
  - countdown: 준비 문장 계획(ESS/PLACE/DET), 설명 중 카운트는 톤. `tailActive` 도 같은 판정에 넣는다.
  - gaze: 플랭크 시선은 채널이 빈 때만, 크런치는 한 회 한 문장으로 합친다.
  - kneeup·lunge: 숫자 지연.
  - sidelunge: 촬영 안내는 speakLatest.
- `isSpeaking` 에 보류분을 넣는다(`PostureCoach.kt:527`은 지금 `pending ∪ speaking`, 보류 `held` 는 빠져 있다).
- 주의: 보류분은 `speaking` 이 완전히 비어야만 풀린다(`PostureCoach.kt:603-612`). 숫자를 QUEUE_ADD 로 쌓는 한 코칭이 굶는다. 이게 endcut·kneeup 의 공통 원인이고 이 구조가 바로잡는다.

### F3. 관측 가능성 공통 장치

- **존재/질 규칙(U1)** 과 가장자리 판정. sidelunge `leg_edge_*`(이미지 x 0.05)와 arrows '앵커는 이미지 안'은 같은 판정이다. `LegGeometry.edge` 하나로 둔다.
- **극점 한 프레임 → 바닥 띠 중앙값 도우미**. sidelunge(SIDE 판별), sidecrunch(kl_st), kneeup R3, sidelunge beta '반대 다리 굽힘' MIN→MEDIAN 이 같은 처방이다. `LegCycleTracker` 에 `bottom` 기록과 `bandMedian()` 하나를 두고, CROSS·KNEE_UP 에도 쓸 수 있게 한다. 크로스 런지 0/7(§3.1)의 후보 원인이기도 하다.
- **시작 기준 확인 패턴**. squat '첫 반복 직후 선 자세로 확인'(8° 허용)과 같은 문제가 여럿 있다.
  - 크런치 누운 기준 −5.24 는 팔을 든 준비 프레임에서 잡혔다(crunch_arms).
  - 크로스 런지 선 자세 무릎 기준이 154.5/159.5°다(줄 11 `reps.config.standing`). 편 다리치고 낮다.
  - countdown 이 시작을 최대 9.5 s 당기면 '아직 자리 잡는 중'인 프레임이 기준에 들어갈 위험이 커진다.
  - 그래서 상대 기준을 쓰는 엔진(RepForm START_*, LegCycle standing, FloorCycle lying)에 "첫 확인된 반복으로 기준 확인" 규칙을 공통으로 넣을지 검토한다. countdown 과 같은 릴리스로 묶는 것을 추천한다.
- **뷰 추정 하나**(§1.3-4).

### F4. 화살표 앵커 체계(arrows 안 기반)

- `FormMotion` 어휘: MARK·TOWARD_TARGET·WRISTS·HEAD{0}·pick(MOVING/SUPPORT/NEAR/CHAIN)·frame(GRAVITY).
- 사유별 motion 은 F1 레지스트리가 갖는다.
- near 팔은 F3 의 반복당 near 를 쓴다.
- 이미지 밖 앵커는 제외한다(sidelunge 유령 다리와 같은 판정).
- 해제 시각은 F1 칭찬 시각과 같다.
- 각 종목 설계가 새로 만드는 사유에도 motion 칸을 채워야 한다.
  - kneeup `shallow_straight`, crunch `arms_only`·부분 → 어깨 UP(GRAVITY).
  - curl `arm_unseen` → null(OBSERVATION).
  - lunge 어깨 기울기 → MARK.
  - curl 반동 → ELBOWS TOWARD_TORSO(ship 때).

### F5. 측정 파이프라인 하나

- **재생기 `--identity-dump`**: 한 다리·바닥·팔별 사이클마다 판별 입력(극점, 띠 중앙값, 쪽, edge, kl_st, 손목 sweep)을 낸다.
  - 지금 kneeup(sim_*.py), sidelunge(d2·d5), crunch(ft.py), sidecrunch(stance_bottom.py)가 각자 파이썬 포트로 쟀다. 구현 뒤 포트와 JVM 이 같다는 확인이 설계마다 따로 필요하다.
  - 하나의 JVM 덤프로 바꾸면 모집단(MM-Fit·REHAB·AIHub MP 캡처)과 폰 블록을 같은 코드로 채점한다.
- **`family_scorecard` 확장**: 판별 사유 표(정상 기각률 ≤ 2 % 입장선, 지정 오류 검출률). `PHONE_BLOCKS` 를 종목당 하나로 합치고 id 기반으로 조회한다.
- **`rep_priors.py`**: 키를 검사 id 로 바꾸고 `prior` 플래그를 읽는다(squat). 단계당 한 번 재생성한다.
- **`speech_policy_replay.py`**(endcut): 모든 설계가 새로 더하는 문장을 합친 **음성 부하 재생**이다. 어느 설계도 하지 않았다(§3.2).
- **세트 로그 관측 필드 한 번에 추가**: `display` 블록(arrows), `floor_gaze`(gaze), `reps.arms[].src`(curl_onearm), `rep_form.start_rebased`(squat), feedback `set_end·speech_handoff·prep_*`(endcut·countdown). 골든 픽스처는 한 번만 갱신한다.

---

## 3. 놓친 것

### 3.1 누락된 문제

1. **크로스 런지(줄 11): 37 s 동안 0회, 기각 7.** 사유는 no_descent ×3, shallow ×4이고 전부 side=None 이다. 12건 어디에도 없다.
   - 기각 시각 직전 3 s 창의 무릎 최소를 다시 셌다(`work/integration` 대신 인라인 계산, 추론).

     | 기각 시각(ms) | 사유 | 무릎 최소(L/R) |
     |---|---|---|
     | 4252 | no_descent | 98.6 / **71.7** |
     | 16838 | shallow | **87.0** / 119.8 |
     | 31518 | no_descent | 155.2 / 94.9 |

   - no_descent 는 두 무릎이 모두 > 135°여야 한다(`LegCycle.kt:359·524`). 그런데 창 안에는 72~95°의 굽힘이 있었다.
   - 발 이미지 밖 프레임이 49/148이고, 선 자세 무릎 기준이 154.5/159.5°다.
   - sidelunge 와 같은 계열(극점 한 프레임, 가장자리 가설 뒤집힘)일 가능성이 있다. 다만 sidelunge 설계는 "CROSS 는 바꾸지 않는다"고 했다.
   - 창이 이전 걸음을 포함했을 수 있어 확정은 아니다. **사용자가 일부러 틀린 세트였는지 먼저 묻고, 아니면 sidelunge 관측 게이트와 같은 단계에서 조사**한다(U18).
2. **라잉 레그 레이즈(줄 13)**: 5회, 기각 6(one_leg 4·knee_bent 2). 보고는 없다. 10-08 오전 커밋(§99 후속 3)이 켠 사유라 정상 회 오기각인지 라벨이 필요하다. 세션 A 에 블록 하나를 넣는다.
3. **컬 '오른팔이 가끔 인식 안 됨'의 화면 부분.** curl_onearm 은 세는 문제만 고친다. 화면에서 먼 팔꿈치가 사라지는 것(가시성 0.5 컷)은 sidelunge 의 가시성 이력(0.4/0.6)으로 깜빡임만 준다. 먼 팔을 '못 봄' 점선으로 그릴지는 어느 설계에도 없다.
4. **창 규칙 '시선 정면 유지'(스탠딩 사이드 크런치) 음성 2회(13:49:50·13:50:24)와 옛 LiveCoach "좋아요, 시선 자세가 교정됐어요"(`PostureCoach.kt:32`).** sidecrunch 는 "#8 에서 다룬다", gaze D8 은 "sidecrunch 와 조율"이라 담당이 없다. endcut 의 LiveCoach 들림 조건이 칭찬 일부만 막는다. **gaze 설계에 귀속**시키고 U12 에서 beta 강등을 결정받는다.

### 3.2 검증되지 않은 주장·안 돌린 측정

| 항목 | 상태 | 필요 측정 |
|---|---|---|
| 설계를 **합쳤을 때**의 결과 | 전부 단독 재생 | 컬: swing 은 "HUD 12→8"을 말하지만 onearm 은 회 구성 자체를 바꾼다(17→18 사이클, 손목 출처 회). 두 기대값은 그대로 함께 성립할 수 없다. LegCycle 3건, 스쿼트(발+시선), 크런치(arms_only+자세별 골+시선+화살표)도 같다. **통합 재생표를 각 단계 합격 조건에 넣는다** |
| 파이썬 포트 결과(kneeup Δ, sidelunge 6건, crunch 6/1/5/6, sidecrunch kl_st) | 포트만 | F5 JVM 덤프로 재현해야 한다(차이 0) |
| sidecrunch MP C 정상 0/31 | 95 % 상한 약 10 % | 설계의 '오프라인 4'(AIHub C ≥150프레임·≥30명)는 **구현 전 필수**다. 통과 전에는 띠 0.70 을 잠정으로 둔다 |
| lunge 모집단 0/739 | 걸음 분할 근사, 모집단 IMU 없음 | 재생기 실제 바닥 창으로 다시 잰다(설계도 관문이라 적음) |
| curl_swing AIHub B 2.1 % | 입장선 경계 | 정상 회 1건이 나오면 0.25 로 올린다(설계 그대로) |
| kneeup·crunch 깊이 #7 입장 | 모집단이 없어 측정 불가 | 사용자 정의 게이트 예외 결정(U11·U15) |
| gaze '시선 변화' 사전값 영향 | 미측정 | §1.4-c |
| countdown 준비 시각 | 로그 없음(준비 프레임 12개만 메모리) | `prep_*` 추적 뒤 세션 B |
| **새 문장 전부를 합친 음성 부하** | **어떤 설계도 안 함** | 새로 생기는 문장: squat firstExclusion, curl 기각 2종·arm_unseen, kneeup 높이, lunge 어깨, crunch 팔만·부분, sidelunge 촬영 안내, gaze 시선, 각 ESS. `speech_policy_replay.py` 로 10-08 요청 흐름에 '새 문장 주입' 반사실을 돌려 보류 굶음·숫자 지연이 다시 늘지 않는지 확인한다 |
| VerifyError | 분석 람다·CapturePreparationPanel 은 측정한 적 없음 | 이번에 실측(119·179). 단계마다 다시 잰다 |
| sidelunge_skeleton 반박 검증 | `res/` 에 verify json 이 없고 `work/sidelunge_skeleton_verify/` 에만 있다 | 산출물 위치만 통일 |

### 3.3 안 읽은 코드·상호작용

- **countdown 이 시작을 당기는 효과**가 시작 스냅샷 기준(스쿼트 START, LegCycle standing, Floor lying, §89 준비 프레임 시드 `PostureLive.kt:985-992`)에 주는 영향을 아무도 재지 않았다(F3).
- **목표 도달 시각**(`WorkoutSession.targetReached`): COACH 차단이 늘면 목표 도달이 늦어지고 세트가 길어진다. endcut 의 t0 정의는 그대로 맞지만, 사용자가 체감하는 세트 길이 변화는 어느 설계도 적지 않았다.
- **리포트·HUD 수 일관성**(`countNote` '감지 N회 중 M회'): 쪽별(SideStepCounter), 바닥 부분(RepUnit:170), 컬 짝, 무음 기각(sidelunge)을 한 화면에서 같은 규칙으로 보이는지 확인되지 않았다.
- **검증 모드**(§61): 기각 음성을 끄고(`PostureLive.kt:1092` silent 에 validationRef) 자동 진행을 막는다. 세션 A 라벨 수집에 쓰면 앱 음성이 동작을 바꾸지 않아 라벨이 깨끗하다. 어느 설계도 고려하지 않았다(§5).

### 3.4 사용자 문장 해석의 빈틈

| 보고 | 빈틈 |
|---|---|
| 스쿼트 "중간에 다리를 벌려도" | 재현되지 않았다. 후보 3개(squat U9) |
| 크런치 "팔만 올려도 셈" | 센 회는 모두 실제 상체 들림이었다. '숫자가 늦게 들린' 경로(10.78 s)가 직접 원인일 수 있다. 설계를 고쳐도 사용자가 다시 같은 동작을 하면 '세지 않는다'로 바뀌는 회는 팔만 기각(이미 10° 미만)뿐이다. **"그때 어떤 동작을 했는지"** 확인 필요 |
| 니업 "허술" | 높이인지 템포인지. 설계는 높이만 다룬다 |
| 런지 "재발?" | 어깨 흐트러짐은 **한 번도 해결된 적이 없다.** 사용자의 '해결됐다'는 기억(크게 숙인 걸음 차단)과 다르다는 사실을 명시적으로 알려야 한다 |
| 시선 "전혀 동작 안 함" | 세 종목 모두 판정 범위 안에서 오류가 0이었다. 사용자가 일부러 시험했는지, 어느 방향이었는지 묻는다. 유일하게 들린 시선 음성은 요청 밖 종목(사이드 크런치)이었다 |
| 화살표 "일부에만" | 바닥까지 기대하는지(U7) |
| 컬 "인식 안 되고" | 셈만이 아니라 화면 뼈대일 수 있다(§3.1-3) |

---

## 4. 구현 순서와 합격 기준

원칙: 공용 기반(동작 불변) → "틀린 회가 세지는" 수정 중 오프라인 근거가 충분한 것 → 음성 기반 → 새 검사 → 화면 → 승격. 세션 A(현재 빌드 라벨 수집)는 P0 과 병렬로 진행한다.

### P0. 공용 기반, 동작 불변 (M)

- F1 레지스트리와 `RecoveryContext`. 기본 정책은 현행과 같게 둔다. `PostureLive.kt:1203` 은 헬퍼 한 줄로 바꾼다.
- `RepRejected.side` 와 F5 관측 필드·추적.
- 재생기 `--identity-dump`·`--arrows`, scorecard 통합, `speech_policy_replay.py`.

**합격**

- 10-06·10-07·10-08 폰 전 세트와 MM-Fit·REHAB 재생에서 count·rejected·rep_form 이 바이트 단위로 같다.
- 앱 유닛·재생기 테스트 전부 통과.
- dexdump: `PostureLiveSessionScreen` 212, 분석 람다 ≤ 125, `CapturePreparationPanel` ≤ 185.

### 세션 A. 현재 빌드 라벨 블록 (P0 과 병렬, §5)

### P1. 판별·게이트 수정, 근거 충분분 (L)

- **squat**: 발끝 시작 확인 T=8, 발 간격·좁음 `prior=false` 와 생성기 키 수정(같은 커밋), 발 기준은 첫 정면 반복.
- **sidecrunch**: kl_st 선 자세축, 정면에서만 판정. AIHub MP 확장 측정을 먼저 통과해야 한다.
- **sidelunge**: blip·edge(PHANTOM), 바닥 띠 중앙값. 도우미는 F3 공용.
- **curl_onearm**: 가상 짝 폐기, 먼 손목 폴백, 큐 전체 조각 탐색, `PairedArmCues`(swing 문장 포함, 하나).
- **crunch**: `arms_only`(판별, 두 모드), 자세별 골.
- **kneeup**: `shallow_straight`, 교정 여유는 F1 에서.
- 버전은 LegCycle v4, FloorCycle v2 로 한 번씩 올린다.

**합격(통합 재생 기준)**

- **squat 13:46**: 발끝 위반 = {2,3,4,10,11}, OK = {1,5~9,12,14}, 정확 8/14.
  - '발 간격#기준' 0건, '기준확인' 1건(−10.9±0.5).
  - 모집단: 발끝 MM-Fit 0/134·REHAB 0/52, 발 간격 0/194·0/54.
  - 라벨 폰 발끝 오류 10/11 검출, 정상 0/17.
- **sidecrunch**: 10-08 줄 8 은 20→18(54020R·55532L 만 no_abduct). 10-06 s3:11 은 8 그대로. 다른 한 다리 세트는 차이 0. AIHub GT 정상 기각 ≤ 2 %, 앞 들기 검출 ≥ 85 %. MP C(확장) ≤ 2 %, 상한 ≤ 5 %.
- **sidelunge**: 줄 10 센 5회는 그대로, 말한 기각 24→18, 무음 정확히 6, 진짜 기각 15건 중 무음 0. 10-06 s3:9 무음 0.
- **curl_onearm**:
  - 파리티 플래그 시 17회·시각 동일.
  - cf_RstillHidE 6→0.
  - MM-Fit 세트 정확 ≥ 0.777, ±1 ≥ 0.98, Σ|오차| ≤ 15. hideEW 무음 손실 0. 정면 C 세트 손실 0.
- **crunch**:
  - `arms_only`: AIHub C 0/213, MM-Fit 0/78, 폰 팔만 5/5, 실제 회 0/49.
  - 48357 은 기각 8.9°.
  - AIHub 전 조건 정상 489클립에서 새 기각 0.
- **kneeup**: 줄 7 기각 11 그대로(사유 이름만 바뀜). 칭찬은 22565 R 한 번.
- **원칙 #7**: 새 판별 사유의 모집단 정상 기각 ≤ 2 %. 단 sidelunge edge·onearm 못 본 짝은 U1 결정에 따른다.

### P2. 음성 기반 (L)

- F2 `SpeechQueue`: 꼬리 넘김, 봉인, 들림 장부, 숫자 키.
- countdown: `holdStart` 삭제, 방향 근거, ESS 계획, 톤 카운트, 바닥 이동 문구.
- 바닥 범위 음성을 화면으로 옮긴다(U6).
- gaze: 플랭크 busy 게이트·tail 가드, 크런치 한 문장 합치기, 문장 단축.
- 세트 시작·끝 `TrexApp.kt:266` 분기 통합.

**합격**

- `SpeechQueueTest`(10-08 고정본):
  - 자동 진행 세트에서 t0 전 회 범위 줄이 끊김·버림 0.
  - 마지막 말 완주 ≤ t0+6 s.
  - 세트 중 숫자 지연 p90 ≤ 3.0 s, 최대 ≤ 6.0 s.
  - 못 들은 지적의 교정 0.
  - 꼬리가 없을 때 flush 경로는 현행과 같다.
- countdown 시뮬:
  - ESS 가 시작 전에 끝남 100 %.
  - 시작 ≤ max(R,M)+3.8 s.
  - 이미 준비된 경우 15.0 s → ≤ 5.7 s(첫 세트 ≤ 7.5 s).
  - 준비 경로가 자른 문장 0.
- speech_policy_replay(09-26~10-08): 현재 형식 경계의 꼬리 상한 도달 0. 크런치 첫 숫자 지연 10.78 s → ≤ 2 s. 플랭크 시작 안내가 HOLD+7 s 안.
- **새 문장 주입 반사실**(§3.2): 보류 코칭 버림 건수가 현행 이하.
- dexdump `CapturePreparationPanel` ≤ 200, TrexApp 람다 ≤ 100.

### P3. 새 반복 검사 (M)

세션 A 결과로 문턱을 정하고 ship 여부를 결정한다(U8). 생성물은 한 번 재생성한다.

- **curl '팔 반동'**: 1단, near 위상, 상승 AND.
- **kneeup '무릎 높이'**: RECENT_PASS_DELTA, refCap 95, Δ 는 세션 A 로 정한다.
- **lunge '어깨 기울기'**: 3D 중력 기준. 카운트 신호 폴백은 **반드시 어깨 검사와 같거나 뒤**에 넣는다.
- **gaze '시선 변화'**: `prior=false`.
- **crunch 깊이 부분**: COACH 전용, 이력 k.
- **sidelunge** 반대 다리 MEDIAN.
- **squat** firstExclusion(U2).
- RepForm VERSION v0.10 을 한 번 올리고, `rep_priors --write` 와 `fault_lines.py` 를 한 번 돌린다.

**합격**

- family_scorecard 검사별:
  - 팔 반동: MM-Fit ≤ 2 %(기대 0/168), AIHub 정상 ≤ 1.5 %·고정 충족 ≤ 2.5 %.
  - 어깨: 뷰별(n ≥ 30) 차단 ≤ 2 %, 연속 2걸음 세트 0/62.
  - 시선 변화: MM-Fit·REHAB 각각 ≤ 2 %, 연속 0.
  - 기존 검사 수치는 그대로.
- 세션 A 블록: 지정 오류 검출 ≥ 80 %(사용자 기준 '틀린 회가 세지면 실패'에 맞춰 목표 100 %), 정상 회 차단 ≤ 1.
- tracker_ab(런지 신호): MM-Fit·REHAB 재현율 하락 0. 10-08 줄 9 는 9 → ≥ 20.
- `FormMotionTest`: beta motion = null, ship motion ≠ null(§1.4-a·b). `RepFormLinesTest` 통과. 사전값 diff 는 설명 가능한 줄만.

### P4. 화면 (L)

- arrows: 어휘, 사유표 완성(P1·P3 사유 포함), 중력 프레임, NEAR, HEAD{0}, 영상 위.
- sidelunge 무대: 배율 1, 유령 다리, 발광 막대, 가시성 이력. `StandingFraming` 촬영 안내.
- 범위·리포트 문장 일괄(§1.1 마지막 줄).

**합격**

- `CueMotionCoverageTest` 누락 0.
- 세션 A 로그 `arrow_audit`: 말한 문장↔단서 차 0, 사유별 방향 일치 ≥ 90 %(n ≥ 4), 반대 다리 해제 0, 런지 깊이 앵커 = 뒷무릎 100 %, 컬 = near 100 %.
- StandingFraming 발화: MM-Fit 0/185, REHAB ≤ 1/36, 사이드 런지가 아닌 폰 세트 ≤ 1/세트.
- **dexdump `PostureLiveSessionScreen` ≤ 230**, 기기 `compile -m verify` 에 failed to verify 0.

### 세션 B. 통합 빌드 UX 확인 (§5) → P5. 남은 승격

- 크런치는 시험 단계 해제 조건: 정상 회 누적 185(2명·2일 이상)에서 부분 판정 0.
- sidelunge v4 띠 확정: 정상 회 150 이상에서 제거 ≤ 3.
- kneeup·crunch 의 #7 예외는 사용자 결정대로.

빌드 횟수: **2회 추천**(P0~P2 → 빌드 1, P3~P4 → 빌드 2). 한 번에 합치면 회귀 원인을 가르기 어렵다. 다만 로그 추적이 충분하면 1회도 가능하다(U8).

---

## 5. 폰 세션 통합안

### 세션 A: 지금 설치된 빌드, 재생으로 판정

조건: TRACK 또는 검증 모드. 앱 음성이 동작을 바꾸지 않게 하기 위해서다. 블록 사이 3 s 정지, 라벨은 블록 순서로 붙인다.

필수 (약 50분)

- **스쿼트(C)**
  - ① `정상:2 발끝밖:3 정상:3 발끝안:2 정상:2`
  - ② `정상:3 넓게:2 정상:2 조금넓게:2 정상:2 좁게:2 정상:2`
  - ③ `정상:3 시선천장:2 정상:2 시선발끝:2 정상:2 시선위:3 정상:2 시선아래:3 정상:2`
- **덤벨 컬**
  - D 동시: `정상:4 반동:3 정상:2 왼팔만:3 정상:2 오른팔만:3 정상:2 큰휘두르기:2 정상:2`
  - D 교대: `교대:4 반동교대:3 교대:2`
  - B: `동시:3 오른팔만:3 동시:2`
- **니업(B)**
  - `정상:5 골반높이:3 정상:3 빠르게대충:4 정상:3`
  - `정상:10 골반높이:2 다리뻗기:2 숙임:2 정상:2`
- **사이드 크런치(C)**
  - `정상:6 앞팔꿈치:4 정상:4 앞으로:4 정상:2`
  - `정상:4 대각:4 다리만올림:3 정상:2`
- **런지(45°)**
  - 왼앞·오른앞 각각 `정상:4 어깨떨굼크게:3 정상:2 어깨떨굼살짝:3 정상:2`
  - `정상:4 등만말기:4 정상:2 상체숙임:4 정상:2`
- **사이드 런지(C)**: `가운데정상:20 가장자리정상:6 가장자리정지:1 얕음:3 두무릎:3 허리숙임:3`
- **크로스 런지**: `정상:6 교차안함:2 얕게:3 정상:2` (§3.1)
- **크런치**
  - `정상:5 어깨만:3 정상:2 팔만:3 정상:2 머리뒤:3 정상:2 턱박기:3 정상:2`
  - `손머리:4 팔뻗기:4 팔뻗기어깨만:2`
- **레그 레이즈**: `정상:3 한다리:3 무릎굽힘:2 정상:2`
- **플랭크 60 s**
  - `버팀:12s 고개들기:6s 버팀:8s 고개떨굼:6s 버팀:10s`
  - `버팀:10s 무릎대기:5s 버팀:10s 골반처짐:5s 버팀:10s 엉덩이솟음:5s 버팀:10s`

선택

- 컬 D 60° 과회전 `동시:5`
- 런지 옆
- 사이드 크런치 B
- 크런치 다른 날 반복
- 가능하면 다른 사람 1명이 니업·크런치 정상 세트

판정 방법: `setlog_captures.py` → 새 엔진 재생(.cap 경로) → F5 표. 85 ms 접촉 관측(sidecrunch)과 준비 시각(countdown)은 세션 A 로 잴 수 없다.

### 세션 B: 통합 빌드, COACH

- 모든 세트를 `…정상:k 오류:1 정상:1`로 끝낸다. 그러면 endcut 의 (a)~(g)를 매 세트 판정할 수 있다.
- 각 종목 첫 세트는 앱을 다시 켜고 시작한다. 그래야 `prep_*` 로 countdown 1~7 을 판정한다.
- 화살표 A1~A8 은 `display` 블록으로 판정한다.
- 추가 블록
  - TRACK 1세트(사이드 크런치 `정상:2 앞으로올림:3`)
  - 영상 토글 1세트
  - 사이드 런지 `준비가까이:1 가장자리정상:6`
  - 컬 D 과회전(못 본 팔 음성)
- 합격 기준은 각 설계의 phone_protocol 을 그대로 쓰되, 공통 항목은 하나로 센다.
  - '교정됐어요'가 짝 지적의 tts_start 뒤에만 나옴(위반 0).
  - 지정 오류 회가 COACH 숫자에 들어간 수 0(사용자 기준).
  - 정상 회 차단 ≤ 1/세트.
  - VerifyError 0.

---

## 6. 사용자 결정 목록 (중복 제거, 추천 포함)

### 공통 (먼저 정하면 여러 설계가 풀림)

| # | 결정 | 추천 |
|---|---|---|
| U1 | 못 본 동작: "반복 존재를 못 봤으면 세지 않음(이유는 촬영 안내), 존재는 봤고 질만 못 봤으면 셈(유보=통과, 범위 문장으로 밝힘)". 적용: sidelunge edge·blip, curl 못 본 짝, sidecrunch 정면 밖, kneeup 옆, crunch 손목 없음 | 채택. 대가: 과거 사선 컬 회 5.9 %(17/289)를 세지 않음, 사이드 런지 가장자리 정상 회 일부 |
| U2 | 자세·부분으로 뺀 회 음성: 검사(사유)별 세트 첫 1회만 "이 회는 세지 않았어요"를 덧붙이고, 이후는 틱과 짧은 단서. §7 일부 되돌림. 스쿼트·컬 반동·런지 어깨·니업 높이·크런치 부분 공통 | 채택 |
| U3 | "좋아요, 교정됐어요" 통합 조건: 들린 지적 ∧ 판정 통과 ∧ 깨끗하게 센 회 ∧ 같은 쪽 ∧ 여유(니업 15°·사이드 크런치 0.80) ∧ 같은 팔 자세. 못 들은 지적은 거둠. 대기 12 s | 채택. 대가: 한 다리 해제 중앙 3.8→8.1 s |
| U4 | 밀린 숫자: 같은 키 최신만 + 코칭과 번갈아. 추가로 3 s 넘게 묵은 중간 숫자를 버릴지('끝'은 유지) | 최신만 + 번갈아 + 3 s TTL(런지 오인 사례 근거) |
| U5 | 세트 끝: 화면은 지금처럼 ≤ 2 s 에 넘기고 마지막 말은 끝까지(꼬리 상한 10 s). 수동 조작은 즉시 정지 | 채택 |
| U6 | 세트 시작: 카운트다운을 말과 분리(설명 중 카운트는 톤), 바닥 긴 범위 음성을 화면·리포트로 옮김, 종목별 ESS 한 문장 표(§1.2) 승인, 시선 목표는 세트마다 말하지 않고 DET·리포트로 | §1.2 표 그대로 |
| U7 | 화살표: 말한 교정 문장마다 화살표 또는 점(COACH 만), 바닥 포함 여부와 **바닥 색(빨강 vs 참고색)**, 영상 위에도 그림, 방향 없는 기호는 보류, 창 규칙 5개 보류, 사이드 런지 squat_like 문장을 바꾸고 골반→굽힌 발 화살표 | 포함, 바닥은 참고색, 나머지 설계 추천대로 |
| U8 | 폰 작업 방식: 세션 A(현재 빌드 라벨) → 오프라인 문턱·ship 결정 → 빌드 → 세션 B. 빌드 1회 vs 2회 | 세션 A 먼저, 빌드 2회 |

### 종목별

| # | 결정 | 추천 |
|---|---|---|
| U9 스쿼트 | 보고 2 가 무엇이었나: (가) 13회 틱만 (나) 거짓 알림 뒤 좁힘 (다) 무릎 벌림 · 발끝 사전값 유지 · T=8° · 발 기준을 첫 정면 반복으로 · 한 발 회전은 범위 문장 · 시선: 극단 + 처음 대비 변화(beta), 횟수 게이트 아님 · **'시선 변화'에 §90 사전값을 쓰지 않음**(이번 발견) | 각 설계 추천 + 사전값 미사용 |
| U10 덤벨 컬 | 반동 1단 · 승격은 세션 A 근거로 · 먼 팔 반동은 못 봄으로 밝힘 · 대스윙 이유는 두 모드에서 말함 · 앞 이탈 교정 문구를 축 단위로 · 저가시성 원시 증거는 보류 · '한 팔만'은 화면 먼저 · 준비에 먼 손목 요구 안 함 · 블록 방식(왼10→오른10)도 10회 · 먼 팔 화면 표시(§3.1-3) | 각 A안 |
| U11 니업 | 정의 = 본인 최근 정성 회 대비 · COACH 만 · **#7 입장 예외**(세션 A 정상 ≤ 1/46 ∧ 대충 ≥ 17/18 로 대신) · Δ 는 세션 A 로 결정 · 판별 110° 유지 · 템포는 범위 문장 · 85 ms 보류 | 각 추천안 |
| U12 사이드 크런치 | 띠 0.70(AIHub MP 확장 측정 통과 조건) · 정면만 판정 · 45° 대각은 셈 · '팔꿈치 앞 내림' beta 참고 · 접촉 V 외삽은 관측 키만 · 사유 순서 유지 · **창 규칙 '시선 정면 유지' beta 강등**(담당 없던 항목) | 각 추천 + 강등 |
| U13 런지 | 어깨 3D 2단(15° 코칭/20° 차단) · ship 은 세션 A 뒤 · 옆 뷰는 검출 ≥ 6/8 일 때만 · 등 말림은 못 봄으로 밝힘 · 카운트 신호 폴백(어깨 뒤) · "어깨는 이전에도 해결된 적 없음"을 사용자에게 알림 | 각 추천 |
| U14 사이드 런지 | 무대 배율 1 · 가장자리 유령 다리 · 준비 공간 검사(시작 막음) · 촬영 안내 두 모드 · 같은 관측 게이트를 크로스 런지·니업·사이드 크런치로 넓힐지 | 각 추천. 확장은 크로스 런지부터 재생표로 |
| U15 크런치 | 깊이(부분)는 COACH 만 + 시험 단계 예외 명시 · 이력 k=1.0 · 비율 0.6 유지 · 팔만 문장 "팔은 고정하고…" · 준비 단계에서 손목 제외(레그 레이즈는 별도) · '기준 다시 잡기' 버튼 보류 | 각 추천 |
| U16 시선(바닥) | 멈춘 동안 판정 안 함 · 턱 박기 판정 안 함 · 플랭크 15 s 목표에서는 시선 교정 기회가 사실상 1회(알림) | 각 추천 |
| U17 카운트다운 | 진입에 방향 근거 요구(+0.28 s) · 바닥 '많이 움직였어요'는 문구만 바꿈 | 예 / 문구만 |
| U18 미조사 | 크로스 런지 0/7 이 일부러 틀린 세트였는지, 레그 레이즈 '한 다리' 4회가 의도였는지 | 확인 후, 아니면 P1 에서 조사 |

산출물: dexdump 측정 `C:/Users/hp276/AppData/Local/Temp/claude/C--Users-hp276-Desktop-trex--claude-worktrees-exercise-posture-feedback-gaze-d02974/439872ae-9430-46b3-a4a9-850716a63fda/scratchpad/phone1008/work/integration/dump_11.txt`(현재 APK 의 `mergeProjectDexDebug/11/classes.dex`). 저장소 파일은 고치지 않았다.