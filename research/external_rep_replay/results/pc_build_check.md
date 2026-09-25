# PC 첫 빌드·유닛 테스트 결과 — `claude/check-exercise-count-bi05w4`

§56(운동 카탈로그 26종목 축소, `1becaab`) 이후 이 브랜치가 처음 빌드된 기록이다. 검사 대상은 `06e1ec4`(§56 이후 11커밋).

## 환경

| | |
|---|---|
| OS | Windows 11 Home 10.0.26200 |
| JDK | OpenJDK 21.0.11 (Microsoft build 21.0.11+10-LTS) |
| Gradle | 8.13 (wrapper `gradle-8.13-bin.zip`) |
| AGP / Kotlin | 8.13.2 / 2.3.21 |
| SDK | minSdk 26, targetSdk 36 |

`local.properties` 는 worktree 에 없어 원래 저장소에서 복사했다(커밋하지 않음).

## 결과

| 작업 | 결과 | 시간 |
|---|---|---:|
| `gradlew.bat :app:testDebugUnitTest` | **실패** — 293개 중 **1개 실패** | 58s (24 tasks 전부 실행) |
| `gradlew.bat :app:assembleDebug` | **성공** — `app-debug.apk` 120,965,129 B | 49s |

컴파일 오류는 없다. 경고는 테스트 소스의 불필요한 `!!` 8건뿐이고 기능과 무관하다.

## 실패 1건 — §56 때문이다

```
AdaptiveAndDataTest > noPostureDataAndTrackNeverBecomePositiveJudgements FAILED
    java.lang.AssertionError: expected:<LOWER> but was:<FULL>
    at AdaptiveAndDataTest.kt:96
```

`AdaptiveAndDataTest.kt:90` 이 만드는 기록 항목이 `WorkoutHistoryItem("기본 스쿼트", …)` 이고, 96행이 그 하루의 초점을 `RoutineFocus.LOWER` 로 기대한다.

§56 이 **"기본 스쿼트"를 카탈로그에서 뺐다.** 근거는 `Sheets.kt:860` 에 그대로 있다 — *"기본 스쿼트는 AIHub 에 맨몸 스쿼트가 없어 바벨 스쿼트 기준을 빌려 쓰던 종목이라 뺐다."* 지금 `기본 스쿼트` 는 `LegacyDemoData.kt` 의 구 기록 샘플 두 곳에만 남아 있다.

`recordWorkoutFocus`(`RecordOverview.kt:4`)는 `workoutCatalog` → `todayPlan` 순으로 종목명을 찾고 **둘 다 실패하면 `"전신"`** 으로 떨어진다. KDoc 이 그렇게 하겠다고 써 놨다(*"알 수 없는 종목은 전신으로 남긴다"*). 그래서 초점이 `LOWER` 가 아니라 `FULL` 이 된다.

**프로덕션 코드는 문서대로 동작한다. 테스트가 §56 을 따라가지 못한 것이다.** 고치지 않았다(지시대로). 고친다면 두 갈래다.

- 테스트만 고친다 — `기본 스쿼트` 를 카탈로그에 남아 있는 하체 종목(예: `바벨 스쿼트`)으로 바꾸거나 기대값을 `FULL` 로 바꾼다. 이쪽이면 **삭제된 종목의 구 기록은 앞으로 전부 "전신"으로 보인다**는 뜻이고, 그게 의도인지 확인이 필요하다.
- 코드를 고친다 — 삭제된 종목명의 카테고리를 보존하는 별칭 표를 둔다. 사용자가 이미 저장한 기록의 초점이 §56 업데이트로 바뀌는 것을 막고 싶다면 이쪽이다.

어느 쪽이 맞는지는 이 문서가 정하지 않는다. 다만 **§56 이 기존 사용자 기록의 표시를 바꾼다는 사실 자체는 테스트가 잡아낸 진짜 신호**이고, "테스트가 낡았다"로만 처리하면 그 신호가 사라진다.

## §56 과 무관한 실패

없다. 나머지 292개는 통과했다.

## 빌드 #1 (`e366723`)

`59878d3`(AdaptiveAndDataTest 수정)·`9a48fd5`(새 렙 코어·런지 신호 교체·ROM 표시·세트 로그 필드·런지류 준비 안내)·`da90111`·`e366723` 을 포함한 상태. 이 컨테이너에서 안드로이드 빌드가 안 돼 `PostureLive.kt`·`SessionScreens.kt`·`RecordOverview.kt`·`AiHubReplayTest.kt` 는 여기서 처음 실제 컴파일됐다.

### 결과 — 세 작업 모두 성공

| 작업 | 결과 | 시간 |
|---|---|---:|
| `:app:testDebugUnitTest` | **성공** — 44개 클래스 **327개 전부 통과** (실패 0 · 오류 0 · 건너뜀 0) | 27s (24 tasks: 7 실행 / 17 up-to-date) |
| `:app:assembleDebug` | **성공** | 7s |
| `:app:compileDebugAndroidTestKotlin` | **성공** (컴파일만 — 기기 없음) | 4s |

지난 절의 유일한 실패였던 `AdaptiveAndDataTest > noPostureDataAndTrackNeverBecomePositiveJudgements` 는 통과한다. `59878d3` 의 퇴역 종목 분류 표가 `기본 스쿼트` 의 초점을 다시 `LOWER` 로 만든다. 테스트 수는 293 → **327**(+34)로 늘었고 새로 늘어난 것까지 전부 통과다.

### 경고

**`app` 본 소스(`compileDebugKotlin`)는 경고 0건이다.** `9a48fd5` 가 새로 들여온 코드에서 나온 경고는 없다.

- `PostureLive.kt:493` 의 `@Suppress("DEPRECATION")` 은 **의도대로 동작한다** — 494행 `packageManager.getPackageInfo(packageName, 0)` 가 API 33+ 에서 deprecated 인데, 억제가 걸려 있어 경고가 나오지 않는다. 대체 API(`PackageManager.PackageInfoFlags`)는 API 33 이상만 있고 이 앱의 `minSdk` 는 26 이라, 지금 형태를 유지하려면 이 억제가 필요하다. 억제를 떼려면 `Build.VERSION.SDK_INT` 분기를 넣어야 한다.
- 테스트 소스 경고 5건 — 전부 불필요한 `!!`: `PostureComparisonTest.kt:188`(`RepSignal`), `PostureSetLogTest.kt:84` 2건(`Float`), `PostureSetReportTest.kt:135`(`RuleOutcome`), `RepCounterTest.kt:126`(`Float`).
- `androidTest` 경고 2건:
  - `RestorationUiTest.kt:27` — `createAndroidComposeRule` 이 deprecated. 대체는 `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` 이고, v2 는 `UnconfinedTestDispatcher` 대신 `StandardTestDispatcher` 를 쓴다. **즉시 실행에 기대는 테스트는 명시적 동기화가 필요해질 수 있다** — 옮길 때 그냥 치환하면 안 된다.
  - `AiHubReplayTest.kt:82` — 불필요한 `!!`(`Long`).

컴파일 오류는 한 건도 없다. `9a48fd5` 의 새 코어는 꺼져 있는(opt-in) 상태로 들어와 있어 유닛 테스트 결과에 기본 경로로는 영향을 주지 않는다.

## 빌드 #2 (`30e349f`)

런지류 "왼쪽 + 오른쪽 한 번씩 = 1회" 변경(`3e77c60`)이 들어간 첫 빌드. 그 위의 `79f79fe`·`48e6785`·`30e349f` 는 연구 스크립트·문서만이다. 이 컨테이너에서 통째로 컴파일된 적이 없던 `PostureLive.kt`·`SessionScreens.kt`·`TrexAppState.kt`·`LiveWorkoutHud.kt`·`WorkoutSession.kt` 가 여기서 처음 실제 컴파일됐다.

### 결과 — 세 작업 모두 성공

| 작업 | 결과 | 시간 |
|---|---|---:|
| `:app:testDebugUnitTest` | **성공** — 45개 클래스 **348개 전부 통과** (실패 0 · 오류 0 · 건너뜀 0) | 21s (24 tasks: 7 실행 / 17 up-to-date) |
| `:app:assembleDebug` | **성공** — `app\build\outputs\apk\debug\app-debug.apk` **122,086,654 B** | 5s |
| `:app:compileDebugAndroidTestKotlin` | **성공** (컴파일만 — 기기 없음) | 3s |

빌드 #1 의 327개에서 **+21**. 기대한 새 테스트가 전부 있고 통과한다:

| 클래스 | 개수 | 비고 |
|---|---:|---|
| `RepUnitTest` | **16** | 새 클래스(`3e77c60`) |
| `SetLabelStoreTest` | 6 | +1 |
| `WorkoutSessionTest` | 14 | +1 — `lungeAutoAdvanceWaitsForTheSecondSideOfTheLastPair` 확인 |
| `PostureSetLogTest` | 8 | 골든 `setlog_s58_fixture.txt` 는 주석 4줄 + **데이터 3줄**(셋째 줄이 좌우 짝 단위) — 3줄 비교 통과 |
| `RepCounterTest` | 12 | 변동 없음 |

런지 관련으로 이름 붙은 테스트 7개(`lungeAutoAdvance…`, `lungeComparisonKeepsItsRecordedUnit…`, `lungeCountsOnKneeMean…`, `lungeRepIsALeftPlusRightPair…`, `lungeSessionCountsOnlyWhenTheSecondSideIsDone`, `lungeSessionPairRomIsShort…`, `lungesStateTheLeftPlusRightRule…`)가 모두 통과다.

### 경고

**`app` 본 소스(`compileDebugKotlin`) 경고 0건** — `3e77c60` 이 새로 넣은 `RepUnit.kt` 와 고친 `PostureLive.kt`·`SessionScreens.kt`·`TrexAppState.kt`·`LiveWorkoutHud.kt`·`WorkoutSession.kt` 에서 나온 경고는 없다. `PostureLive.kt` 의 `@Suppress("DEPRECATION")` 은 빌드 #1 과 같이 억제가 유지된다.

- 테스트 소스 3건 — 전부 불필요한 `!!`: `PostureSetLogTest.kt:84` 2건(`Float`), `PostureSetReportTest.kt:135`(`RuleOutcome`). 빌드 #1 의 5건에서 `PostureComparisonTest:188`·`RepCounterTest:126` 두 건은 사라졌다.
- `androidTest` 1건 — `RestorationUiTest.kt:27` `createAndroidComposeRule` deprecated(빌드 #1 과 동일). 빌드 #1 에 있던 `AiHubReplayTest.kt:82` 의 `!!` 경고는 사라졌다.

컴파일 오류·실패 테스트 없음. 설치는 하지 않았다.

## 빌드 #3 (`696955d`, 브랜치 `claude/rep-validation-env`)

렙 검증 모드(`2dd9fb2`)·Gate A PC 파이프라인(`3712072`)·런북(`d52b619`)이 들어간 첫 빌드. 이 컨테이너에서 처음 실제 컴파일된 앱 파일: `TrexApp.kt`(검증 표시 파일 읽기·자동 진행 끄기), `PostureLive.kt`(validation 인자·음성 끄기/복원·배너), `LiveWorkoutHud.kt`(hideCount), `PostureAnalyzer.kt`(`PoseSample.world` — MediaPipe worldLandmarks 의 x()/y()/z()).

### gradle — 세 작업 모두 성공

| 작업 | 결과 | 시간 |
|---|---|---:|
| `:app:testDebugUnitTest` | **성공** — 45개 클래스 **350개 전부 통과** (실패 0 · 오류 0 · 건너뜀 0) | 27s (24 tasks: 6 실행 / 18 up-to-date) |
| `:app:assembleDebug` | **성공** — `app\build\outputs\apk\debug\app-debug.apk` **122,091,053 B** | 6s |
| `:app:compileDebugAndroidTestKotlin` | **성공** (컴파일만) | 2s |

빌드 #2 의 348개 +2 — 기대한 새 테스트 둘 다 있고 통과: `PostureSetLogTest.validationModeAddsCoordinatesAndProductLogsStayByteIdentical`, `PostureSetLogTest.validationFlagIsAFileInTheAppFolder`(클래스 8 → 10). 골든 `setlog_s58_fixture.txt` 는 주석 4줄 + **데이터 4줄**.

### 경고

**`app` 본 소스(`compileDebugKotlin`) 경고 0건** — `PostureAnalyzer.kt` 의 world 채우기 포함, 새 코드에서 나온 경고 없음.

- 테스트 소스 2건 — `PostureSetLogTest.kt:84` 불필요한 `!!` ×2(`Float`). 빌드 #2 에 있던 `PostureSetReportTest:135` 는 사라졌다.
- `androidTest` 2건 — `RestorationUiTest.kt:27` `createAndroidComposeRule` deprecated(빌드 #1·#2 와 동일), `AiHubReplayTest.kt:82` 불필요한 `!!`(`Long`; 빌드 #2 에서 사라졌다가 이 브랜치에서 다시 나타남 — 병합 기반이 다르다).

### 연구 파이프라인 드라이런 (worktree 의 `.venv-mp`, 전역 설치 없음)

| 명령 | 결과 |
|---|---|
| `pull_phone.py --self-test` | **19/20** — 아래 1건 실패 |
| `gate_a.py dry-run --out <저장소 밖 임시 폴더>` | 첫 실행 **실패** → 재생기 재빌드 뒤 **18/18 통과**(5.7s) |

**self-test 실패 1건** — `명령: -s 기기 번호가 앞에`(`pull_phone.py:345`). 테스트가 `Adb(Path("/x/adb"), "SER9").cmd("pull","a","b") == ["/x/adb", "-s", "SER9", ...]` 를 기대하는데, 윈도우에서 `str(Path("/x/adb"))` 는 `'\x\adb'` 다(확인함). `cmd()` 자체(`pull_phone.py:113-117`)는 `[str(exe), "-s", serial, *args]` 로 정상이고, **테스트 기대값이 POSIX 경로 문자열을 하드코딩한 것**이다. `3712072`(wip) 탓. 실기기 명령(`devices`·`pull`·`validation status`)은 전부 정상 동작했다.

**gate_a 첫 실패** — `replay-jvm` 설치 바이너리가 14:49(`claude/check-exercise-count-bi05w4` 빌드) 것이라 이 브랜치가 `Replay.kt` 에 넣은 `--dump-features`(75줄 변경)를 몰랐고, 그 인자를 매니페스트 경로로 읽다 `readManifest(Replay.kt:393)` 에서 죽었다. `gate_a.py:153` 은 재생기 **존재만** 확인하고 소스가 새로워도 다시 빌드하지 않는다(런북의 "필요하면 스스로 빌드한다"와 다르다 — 없을 때만 안내 문구를 낸다). `gradlew.bat -p research\external_rep_replay\replay-jvm test installDist` 로 재빌드(15s, **테스트 46개 통과** — 지난 41개 +5 `ReplayCaptureTest`) 뒤 18/18. 코드는 고치지 않았다.

### 휴대폰

- `pull_phone.py devices`: `R3CMB04LLNZ  device`(SM-N976N, adb 는 `%LOCALAPPDATA%` 의 platform-tools). 인증됨.
- 기기 앱: `com.example.trex_kotlin` **versionName 1.2.0-preview.1, versionCode 4**, 마지막 갱신 2026-09-23 12:08.
- 검증 모드 표시 파일: **off**(켜지 않았다). 기기에서 아무것도 지우지 않았다.
- 설치 전 백업 → `data\phone\backup-20260924T1530\`(`data/` 는 `.gitignore:63` 대상, 커밋 안 함). 이름·크기만:

| 파일 | 크기 |
|---|---:|
| `sets-20260912.jsonl` | 2,500,096 B |
| `sets-20260923.jsonl` | 8,550,731 B |
| `feedback-20260912.jsonl` | 71,415 B |
| `feedback-20260914.jsonl` | 776 B |
| `feedback-20260923.jsonl` | 47,878 B |
| `pull_manifest.json` | 859 B |
| `shared_prefs/trex_store.xml` | 4,996 B |
| `shared_prefs/trex_posture.xml` | 226 B |
| `shared_prefs/posture_action.xml` | 122 B |
| `shared_prefs/WebViewChromiumPrefs.xml` | 266 B |

  (shared_prefs 는 `run-as ls` 출력의 CR 때문에 첫 시도에서 1개만 저장돼, CR 을 떼고 다시 받아 4개 모두 0 바이트 아님을 확인한 **뒤에** 설치를 시도했다.)

- **설치 거부 — 멈춤.** `adb install -r app-debug.apk` → `Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Package Verification Result]`. 서명 불일치가 아니라 **versionCode 역행**이다: 이 브랜치의 `app/build.gradle.kts:17` 은 `versionCode = 3`(`3322628` 이후 변동 없음, versionName `1.1.0-preview.2`)인데 기기의 1.2.0-preview.1 은 `versionCode 4`(`8907f0f` "26종목 동작 계수와 학습 모델 시험 실행 배포" — 이 브랜치 이력에 없다). uninstall 도 `-d`(강제 다운그레이드)도 하지 않았다. 기기 앱은 그대로 versionCode 4 다.

## 빌드 #4 (`f0cb5ac`, 브랜치 `claude/rep-validation-env`) — 설치 포함

versionCode 5 / versionName `1.2.0-repval.1`(`4a50a34`) 빌드. 트리거 worktree(`../trex-repcheck`)가 아니라 **데스크톱 저장소에서 직접** 돌렸다(2026-09-25). 환경은 위 표와 같다(JDK 21.0.11).

### gradle — 전부 성공

| 작업 | 결과 | 시간 |
|---|---|---:|
| `:app:testDebugUnitTest` + `:app:assembleDebug` | **성공** — 45개 클래스 **350개 전부 통과**(실패 0 · 오류 0 · 건너뜀 0), `app-debug.apk` **120,997,897 B** | 53s (44 tasks 전부 실행) |
| `-p research\external_rep_replay\replay-jvm test installDist` | **성공** — **46개 통과**, `lib/.built` 갱신 | 16s |

빌드 #3 과 테스트 수가 같다(350). 이번은 깨끗한 빌드라 `compileDebugKotlin` 이 실제로 돌아 본 소스 경고 7건이 보였다 — `SettingsScreen.kt:67`·`WorkoutCatalogUi.kt:64`(deprecated), `TrexStore.kt:46`(플랫폼 타입), `PostureCoach.kt:324`·`PostureRules.kt:195/201`(불필요한 `!!`). 빌드 #3 의 "본 소스 경고 0건"은 그 작업이 up-to-date 였던 증분 빌드라 직접 비교되지 않는다. 검증 모드가 건드린 파일에서 나온 경고는 없다.

### 연구 파이프라인 (`../trex-repcheck/.venv-mp` 의 파이썬)

| 명령 | 결과 |
|---|---|
| `pull_phone.py --self-test` | **20/20** — 빌드 #3 의 윈도우 실패 1건(`c2160fb` 에서 고침)이 사라졌다 |
| `gate_a.py dry-run --out <저장소 밖 임시 폴더>` | **18/18** (6.9s) — 재생기 재빌드 직후라 '오래됨' 검사에 걸리지 않음 |

### 휴대폰 (SM-N976N, `R3CMB04LLNZ`)

- 설치 전: versionName 1.2.0-preview.1, **versionCode 4**, 마지막 갱신 2026-09-23 12:08.
- 설치 전 백업 → `data\phone\backup-20260925T0918\`(`data/` 는 ignore 대상, 커밋 안 함). 빌드 #3 백업과 같은 10개 파일, 세트·피드백 로그는 바이트 수까지 같고 `trex_store.xml` 만 4,996 → **4,997 B**(09:05 에 앱이 다시 저장). 4개 shared_prefs 모두 0 바이트 아님, `trex_store.xml` 이 `</map>` 으로 끝나는 것 확인 **뒤에** 설치했다.
- `adb install -r app-debug.apk` → **Success**. uninstall·`-d` 쓰지 않음.
- 설치 후: `dumpsys package` → **versionCode 5, versionName 1.2.0-repval.1**, 갱신 2026-09-25 09:20:54.
- 실행: 런처로 띄워 `MainActivity` 가 resumed, 홈 화면 정상 표시, `logcat -b crash` 비어 있음(FATAL 없음).
- 보존: 기기 `posture_logs/` 5개 파일 크기가 설치 전과 **전부 같다**, shared_prefs 4개 그대로.
- 검증 모드 표시 파일: **off** 그대로(켜지 않았다). 기기에서 아무것도 지우지 않았다.

## 빌드 #5 (미커밋 작업 트리, `f0cb5ac` + §62 — 반복 판별 게이트·발끝 방향·폰 규칙셋)

| 작업 | 결과 |
|---|---|
| `:app:testDebugUnitTest` | **성공** — **358개 전부 통과**(빌드 #4 의 350 + `RepCounterTest` 4 · `ToeOutTest` 3 · `PostureSetLogTest` 1) |
| `:app:assembleDebug` | **성공** |
| `-p research\external_rep_replay\replay-jvm test installDist` | **성공** — 50개 통과 |
| `setlog_captures.py --self-test` | **49/49** — 골든 픽스처 4줄을 파이썬 인코더로 다시 썼고 코틀린 `PostureSetLogTest` 가 같은 줄을 요구한다 |
| `gate_a.py dry-run` | **18/18** |
| `run_replay.py --configs live` (오늘 실기기 3세트) | 0/0 · 6/6 · **11 vs 로그 12** — 차이는 의도된 것(판별 게이트가 무릎 들기 사이클 t=57780 을 기각, 스윙 14.1°) |

중간에 깨졌던 것 2건과 원인: (1) 골든 픽스처 — 스쿼트 config 에 `identity` 가 붙어 첫·둘째·넷째 줄이 바뀜(의도된 형식 변경, 픽스처·파이썬 사본 갱신). (2) `comparisonSignal()` 에서 판별 게이트를 떼려고 사본을 만들자 "비교 신호 = 자기 자신" 을 `assertSame` 으로 잠근 테스트 2개가 깨짐 → 되돌림(비교 추적기는 두 인자 onFrame 이라 게이트가 어차피 동작하지 않는다).
폰에는 설치하지 않았다(기기 앱은 빌드 #4 그대로).

## 빌드 #6 (미커밋 작업 트리, `cd104ce` + §62a — 반복별 자세 검사·정확 횟수)

| 작업 | 결과 |
|---|---|
| `:app:testDebugUnitTest` | **성공** — **370개 전부 통과**(빌드 #5 의 358 + `RepFormTest` 9 · `StanceWidthTest` 2 · `PostureSetLogTest` +1) |
| `:app:assembleDebug` | **성공** |
| `-p research\external_rep_replay\replay-jvm test installDist` | **성공** — 50개 통과 (소스 목록에 `RepForm.kt`·`RuleTypes.kt` 추가) |
| `setlog_captures.py --self-test` / `gate_a.py dry-run` | **49/49** / **18/18** — 골든 4줄 불변(rep_form 블록은 평가기가 있는 세트만) |
| `run_replay.py --configs live` (오늘 4세트) | 파리티 0/0 · 6/6 · 11 vs 12(의도된 기각) · 7/7. `repForm`: 설계 §21.5 |

깨졌던 것과 원인: (1) 재생기 컴파일 — `RepForm.kt` 가 `PostureRule`·`RuleResult`(안드로이드·org.json 의존 파일)를 써서 → 규칙셋 연결을 `RepFormRules.kt` 로 떼고 `RuleStatus`·`Verdict`·`Direction` 을 `RuleTypes.kt` 로 옮김. (2) 테스트 픽스처 — 바닥 창(최소 + 진폭/3)이 110° 하강 프레임을 포함해 무릎 평균이 임계를 넘음 → 픽스처를 실제처럼(굽힘과 함께 무릎값이 변함) 고침. (3) 무릎 과도 벌림 0.25 가 정상 반복(0.34~0.35)에 걸림 → 0.40, beta 유지.

## 빌드 #7 (미커밋 작업 트리, `e594be5` + §21.7 — 서 있는 프레임 EXTREME·이월·원인 우선 문장)

| 작업 | 결과 |
|---|---|
| `:app:testDebugUnitTest` / `:app:assembleDebug` | **성공** — **373개 전부 통과**(+`RepFormTest` 3) |
| `-p research\external_rep_replay\replay-jvm test installDist` | **성공** — 50개 |
| `run_replay.py --configs live` (11:37 세트, 10회) | 파리티 10/10, 정확 8, `repForm` 표 = 설계 §21.7 |

깨졌던 것: 테스트 3건이 새 의미(요약 줄에 시작 자세 없음·방향별 수·이월 규칙)에 맞춰 갱신됐고, 음성 연속 판단이 `eventFor` 호출 이력에 기대던 결함을 `onCycle` 기록으로 옮겼다.

## 빌드 #8 (`0de7db4` + §21.8 — 발끝 위반 반복의 발 너비 유보)
`:app:testDebugUnitTest` 373 · `:app:assembleDebug` 성공 · replay-jvm 50. 재생(11:37): 발 너비 오탐 4 → 0(유보 6), 정확 8/10 그대로.

## 빌드 #9 (`2ccfcb8` + §21.9 — 이미지 2D 발 너비 `stance_2d`)
`:app:testDebugUnitTest` 374 · `:app:assembleDebug` 성공 · replay-jvm 50(소스 목록 +`Stance2d.kt`). 12:19 검증 세트 랜드마크 경로 재생: 발 너비 오탐 0/8·검출 2/2.
