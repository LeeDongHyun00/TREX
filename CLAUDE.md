# TREX — 작업 지침

Android Kotlin/Jetpack Compose 운동·식단 앱. 핵심 기능은 **카메라 자세 평가**이고, 이 저장소의 어려운 결정은 거의 전부 자세 평가에서 나온다.

이어받는 세션은 **`docs/POSTURE_HANDOFF.md` 를 먼저 읽는다** — 최근 작업(§30·§31·§31a)의 설계 근거와 미해결 목록이 거기에 있다.

## 이 프로젝트의 판단 원칙 (가장 중요)

코드를 고치기 전에 이것부터 읽어야 한다. 이 원칙을 모르면 "개선"이 정확히 반대 방향으로 간다.

1. **판정하지 않은 것을 판정한 것처럼 말하지 않는다.** 유보(ABSTAIN)를 정상으로 세지 않고, 판정이 0건이면 점수도 "깨끗"도 금지한다. 화면에 덜 잡힌 세트일수록 점수가 오르는 신호를 만들면 안 된다.
2. **검증된 것과 검증 중인 것을 같은 확신으로 말하지 않는다.** 규칙에는 `ship`/`beta`/`exclude` 상태가 있다. 실기기 오탐 3건(§28)이 **전부 beta·미보정 규칙**에서 나왔다. beta 는 음성으로 말하지 않고, 헤드라인이 되지 않고, 점수 분모에 들어가지 않는다. 화면에는 '참고'로 남긴다.
3. **관측 시스템은 스타일과 오류를 구분할 수 없다.** 사용자의 깊은 스쿼트가 AIHub 표준(더 얕음)에 위반 판정된 실측이 근거다(§29). 그래서 모드가 둘이다 — COACH(모집단이 기준, 앱이 가르침) / TRACK(본인이 기준, 앱이 기록함). 같은 엔진, 반대 정책. 임계값은 두 모드에서 동일하게 계산된다.
4. **모든 사용자 원칙.** 특정 사용자군을 위해 모집단 파라미터를 바꾸지 않는다. 모드는 발화·표시 정책만 가른다.
5. **못 보는 것을 밝힌다.** 종목마다 `exclude` 규칙이 있다. 예: **바벨** 데드리프트의 '척추의 중립'은 전부 exclude 라, 허리를 말아도 ship 규칙만 통과하면 "깨끗"이 나온다. `PostureScope` 가 이 범위를 문장으로 만든다.
6. **오탐이 사용자를 잘못 교정시킨다.** 음성 코칭은 사용자가 즉시 몸을 바꾸게 만드는 채널이라, 화면 표시보다 훨씬 보수적으로 다룬다.

## 구조

| 위치 | 내용 |
|---|---|
| `app/src/main/java/com/example/trex_kotlin/posture/` | 자세 엔진 — 피처·규칙·코칭·렙·리포트·기준선 |
| `app/src/main/java/com/example/trex_kotlin/` | 앱 화면 (`PostureLive.kt` = 실시간 세션, `TrexApp.kt` = 라우팅) |
| `app/src/main/assets/posture/` | 규칙 JSON + MediaPipe 모델 |
| `research/aihub_fitness/` | 연구 코드·문서. **`KOTLIN_PORTING_SPEC.md` 가 정본 스펙**(§1~§31a) |

기능을 추가하면 `KOTLIN_PORTING_SPEC.md` 에 절을 추가하는 것이 저장소 관례다. 근거(왜)를 반드시 남긴다.

**규칙셋의 실제 분포** — `rules_mp_v0.json` 141규칙 = ship **55** · beta **16** · exclude **70**(절반이 못 보는 규칙). `rules_floor_v0.json` 14규칙/8종목은 **전부 beta**. ⚠️ JSON 헤더의 `counts` 필드(`ship 59/beta 12`)는 **낡았다** — sanity check 로 쓰지 말고 `rules` 배열을 직접 집계하라.

**앱이 실제로 자세 평가를 도는 종목은 18개**(서서 18 + 바닥 8 매핑)이고, 게이트는 규칙 JSON 이 아니라 `PostureLive.kt` 의 `postureExerciseMap` + `Workout.postureSupported()` 다. 종목을 늘리거나 진입 경로를 손대는 작업은 반드시 이 map 을 지난다.

## 빌드·테스트

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

- ⚠️ **새로 클론했다면 먼저** `chmod +x gradlew` — git 에 등록된 모드가 100644 라 `./gradlew` 가 Permission denied 로 실패한다(실행비트 변경이 아직 미커밋).
- macOS 에 `timeout` 명령이 없다. gradle 은 수 분 걸릴 수 있다.
- 유닛 테스트에서 **`org.json` 은 스텁**이다 — 직렬화 코드는 직접 문자열로 만든다(`SetLogJson`, `SetLabelStore` 가 그렇게 돼 있다).
- 주석·KDoc·사용자 문구는 **한국어**. 판정·코칭 문장은 존댓말 평서체, 공룡 말투("~해룡")는 완료 화면 제목류에만.
- `posture` 패키지는 앱 최상위 패키지에 의존하지 않는다 — 단 **엔진 파일에 한한다**. 개발용 화면 `BaselineGuideScreen`·`PostureLabScreen` 은 테마 상수 때문에 이미 예외다.

## 실기기

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

세션마다 세트 로그(원본 프레임 피처)가 `/sdcard/Android/data/com.example.trex_kotlin/files/posture_logs/sets-YYYYMMDD.jsonl` 에 쌓인다. 자가 라벨은 `labels/set_labels.jsonl` 과 `rep_truth.csv`. 회수는 `research/aihub_fitness/pull_logs.py`(단 adb 경로가 Windows 로 하드코딩돼 있다).

**설치 함정**: 기기에 다른 키로 서명된 빌드가 있으면 설치가 거부된다. 삭제 후 재설치해야 하는데 그러면 운동 기록·기준선이 지워진다. 지우기 전에 반드시 백업한다 — 로그는 `adb pull`, 내부 저장소는 디버그 빌드라 `adb shell "run-as com.example.trex_kotlin cat shared_prefs/…"` 로 꺼낼 수 있다(복원은 앱 전용 외부 폴더를 경유해야 한다. 앱 UID 는 `/sdcard` 루트를 못 읽는다).

## 알아야 할 함정

- **`PoseSample.detected` 는 가시 관절 수와 무관하게 항상 true** 다(`PostureAnalyzer.kt`). 관절 11개짜리 프레임도 detected 다. "사람이 화면에 있다"의 실질 판단은 **피처가 계산됐는지**(`features.isNotEmpty()`)로 해야 한다. 이걸 혼동해 앵커 폴백이 10초 일찍 걸린 실기기 오탐이 §31a 다.
- **극값 통계(range/min/max)는 준비 동작 하나에 뒤집힌다.** §31a 실측: 준비 구간이 포함되면 `torso_incl__range` 68.6°, 실제 운동 구간만 보면 27.4°(임계 30.85°). 집계 창은 반드시 앵커 이후여야 한다.
- **렙 카운터는 beta.** 실기기 라벨 세트에서 12개 중 9개 검출. KDoc 이 "±1 오차를 약속에 포함하지 않는다"고 못박았다. 게다가 **세트의 마지막 렙을 구조적으로 놓친다** — 상단 확정에 다음 하강이 필요하기 때문이다(§31a 로그에서 6사이클 중 5개만 카운트). 카운트를 의사결정 근거로 승격시키는 기능은 이 오차를 사용자에게 약속하게 된다.
- **임계값은 스튜디오(AIHub) 기준이고 §9 재보정 전이다.** 그래서 점수는 분수로 표기하고, beta 는 참고이며, TRACK 은 점수가 없다. ship 규칙은 종목당 **0~3개**뿐이라(4개인 종목 없음) 한 건 위반이 점수의 3분의 1~전부를 깎는다.
- **개인 기준선은 종목마다 성격이 다르다.** 서서 종목 eligible 규칙의 gain 은 +0.02~+0.12(중앙값 ~+0.04). 바닥 종목 14규칙의 **기준선**은 폼 교정이 아니라 폰 위치 재배치(`mode: reanchor`)가 목적이다. `required = true` 는 저장소 통틀어 1건(바벨 스쿼트 '발바닥 지면 고정')이고 **그 규칙은 beta** 다. 다른 날 찍은 기준선은 이득이 사라질 수 있다(§19).
- **`PostureRuleSet.rules` 는 EXCLUDE 규칙까지 담고 있다**(로더가 필터하지 않는다). `PostureScope` 가 이 전제 위에 서 있다 — 깨지면 "못 봄" 목록이 조용히 비어 거짓 안심으로 돌아간다.
- **`PostureLive` 는 종목이 바뀌어도 재컴포지션되지 않는다**(같은 `AnimatedContent` 라우트). 그래서 세트 마감 람다는 종목·뷰를 **세트 시작 시점 값으로 인자 전달**받는다. 지금 값을 쓰면 종목이 어긋난다.

## 현재 상태

`docs/POSTURE_HANDOFF.md` 의 §2(브랜치·미커밋)와 §6(다음에 할 일)을 볼 것.
