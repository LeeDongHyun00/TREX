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
