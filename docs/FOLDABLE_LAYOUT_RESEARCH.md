# TREX 폴더블 레이아웃 대응 조사

조사일: 2026-09-12. 대상: `feature/posture-coach-reliability`, HEAD `e2cd1d1`와 조사 시작 당시 미커밋 작업을 포함한 소스.

**권고: 현재 창의 폭·높이와 접힘 영역으로 배치를 결정하고, 자세 세션 상태는 배치와 독립적으로 유지한다.** 일반 화면은 내용 폭·스크롤·시스템 여백을 먼저 정리하고, 자세 화면은 카메라의 유효 표시 영역과 조작 버튼을 함께 보장한다.

아래 조사 본문은 구현 전 상태를 기록한 것이다. **2026-09-12 구현 완료:** Jetpack WindowManager 1.5.0 기반 접힘 회피, 창 크기에 따른 카메라/패널 재배치, 스크롤·인셋·큰 글씨 대응을 적용했다. JVM 176건과 Android 에뮬레이터 UI 테스트 9건이 통과했다. 접힘은 합성 좌표로 검증했으며 실제 폴더블 센서·접기/펴기·카메라 좌표 정확도 검증은 남아 있다. 구현 세부와 함께 수정한 로그인·문구·데이터 정책은 [정본 스펙 §37](../research/aihub_fitness/KOTLIN_PORTING_SPEC.md)에 기록했다.

## 1. 현재 코드에서 확인한 것

현재 `compileSdk`와 `targetSdk`는 36, `minSdk`는 26이다. Compose BOM은 `2026.04.01`, CameraX는 `1.4.2`이다. 버전 카탈로그와 앱 의존성에는 Material 3 Adaptive 및 WindowManager 직접 의존성이 없고, 화면 코드에도 창 크기 등급·힌지 관측이 없다.

| 대상·근거 | 코드로 확인한 현재 동작 | 예상 영향과 대응 |
|---|---|---|
| [자세 화면 분기](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/PostureLive.kt:407), [실제 배치](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/PostureLive.kt:1131) | `orientation`으로만 가로·세로 분기. 가로는 우측 패널 `320.dp`, 세로는 패널 높이를 먼저 쓰고 카메라에 남은 높이 할당 | 좁은 가로 분할 창에서는 카메라 폭이 작아지고, 낮은 세로 창·큰 글씨·상세 펼침에서는 카메라 높이가 줄어들 수 있음. 가용 영역과 내용 최소 공간에 따라 재배치 |
| [자세 조작 행](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/PostureLive.kt:1072) | 음성·음성 확인·일시정지·완료를 한 행에 배치. 음성 확인은 바닥 종목에 추가 | 좁은 폭과 글씨 확대 조합에서 필요한 폭 증가. 일시정지·완료를 우선 확보하고 보조 조작을 다음 행으로 배치 |
| [타이머 화면](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/SessionScreens.kt:106) | 상단 `50.dp`, 링 `224.dp`, 하단 `26.dp`; 화면 전체 스크롤·가용 높이 분기 없음 | 낮은 창에서 중앙 정보와 버튼이 압축될 가능성. 링 크기와 배치를 가용 높이로 결정하고 필수 조작 영역 확보 |
| [탭 공통 여백](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/MainScreens.kt:94), [하단 메뉴](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/TrexApp.kt:421) | 탭 목록은 위 `50.dp`, 아래 `110.dp`; 메뉴는 화면 위에 겹쳐 배치하고 자체 내비게이션 바 여백 사용 | 태스크바·회전·메뉴 실제 높이를 고정값이 정확히 반영하지 못함. 측정된 메뉴 공간과 시스템 인셋을 콘텐츠에 전달 |
| [공통 시트](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/TrexDesign.kt:515) | 자체 `Box` 시트가 전체 폭을 채우고 하단 내비게이션 여백만 적용 | 큰 내부 화면에서 입력·선택 UI가 과하게 넓어짐. 키보드·상단 잘림·힌지 회피도 호스트 차원의 계약이 없음 |
| [공통 버튼](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/TrexDesign.kt:183) | 기본 높이 `54.dp`, 한 줄 문구, 말줄임 | 큰 글씨에서 핵심 행동 문구가 잘릴 수 있음. 최소 높이와 내용 기반 높이를 사용하고 핵심 버튼은 줄바꿈 허용 여부 검토 |
| [카메라 회전](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/PostureLive.kt:395) | `LocalConfiguration` 변화 때 `defaultDisplay.rotation`을 읽어 `ImageAnalysis.targetRotation` 갱신 | 180도 회전처럼 configuration 변화만으로 충분하지 않은 경우를 별도로 확인해야 함. 실제 연결된 디스플레이의 변경 이벤트를 관측 |
| [골격 표시](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/PostureLive.kt:1214) | 회전된 분석 이미지 크기로 FIT 배율·중앙 오프셋·전면 미러를 직접 계산 | 현재 구현 자체가 잘못됐다고 단정할 근거는 없음. 장치별 분석·프리뷰의 실제 crop/시야각·회전이 달라지는 경우를 검증하고 좌표 변환 보강 |
| [세트 상태](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/PostureLive.kt:290), [세트 마감](C:/Users/hp276/Desktop/trex/app/src/main/java/com/example/trex_kotlin/PostureLive.kt:540) | 집계기·렙·앵커·바닥 추적기 등이 화면 `remember`에 있음. `onDispose`에서 세트 마감 | 적응형 UI를 별도 화면 인스턴스로 교체하면 의도치 않은 마감·리셋 위험. 세션 소유자를 배치 분기 위에 유지 |

이미 있는 대응도 활용한다. 자세 화면은 카메라와 패널을 분리했고 `FIT_CENTER`로 전체 영상을 표시한다. 완료 화면은 스크롤과 상·하 시스템 여백을 처리한다. 로그인·온보딩에는 `imePadding`과 일부 스크롤이 있다. 탭·세션 진행 값은 `rememberSaveable`, 앱 데이터는 `AppViewModel`을 사용한다. 따라서 전체 화면을 새로 만드는 작업으로 시작할 필요는 없다.

## 2. 기준은 기종이 아니라 현재 앱 창

Android는 앱에 실제로 주어진 창의 폭과 높이를 각각 분류하도록 안내한다. 접힌 Fold, 펼친 Fold, Flip, 분할 화면을 기종명이나 `isTablet`로 판별하면 같은 장치 안에서도 배치가 어긋난다. 폭뿐 아니라 낮은 높이도 별도로 다룬다. 공식 기준은 폭 600/840/1200/1600dp, 높이 480/900dp 경계이다. [공식 창 크기 가이드](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)

TREX에서는 다음을 시작안으로 삼는다. **아래 화면 선택은 TREX용 제안이며 모든 폴더블의 치수를 뜻하지 않는다.**

| 현재 사용 가능 공간 | 일반 화면 제안 | 자세 화면 제안 |
|---|---|---|
| 폭 600dp 미만 | 한 열, 하단 메뉴, 필수 조작 외 콘텐츠 스크롤 | 전신 프리뷰 + 간결한 조작부. 상세는 제한된 높이 안에서 스크롤 |
| 폭 600~839dp | 본문 최대 폭 제한·중앙 정렬부터 적용. 충분한 높이에서 메뉴 레일 검토 | 폭·높이를 함께 측정해 한 열 또는 카메라/패널 좌우 배치 선택 |
| 폭 840dp 이상 | 운동 목록/선택 운동 상세, 기록 목록/상세 등 두 영역 활용 가능 | 카메라/패널 좌우 배치. 패널은 콘텐츠에 필요한 폭, 카메라는 나머지 공간 |
| 높이 480dp 미만 | 큰 장식·헤더 축소, 필수 버튼 확보, 내용 스크롤 | 일반 상세 패널 대신 작은 조작부. 단순히 “가로니까 320dp 패널”을 적용하지 않음 |
| 반쯤 접어 가로 힌지가 생김 | 실제 힌지 경계로 상·하 영역 분리 | 위 프리뷰·아래 조작부를 우선 검토. 두 영역의 크기가 부족하면 간결한 배치로 전환 |
| 화면을 나누는 세로 힌지 | 힌지 양쪽에 목록/상세 등 배치 | 카메라와 주요 버튼을 힌지에 걸치지 않게 배치 |

앱 최상위에서는 창 정보로 큰 배치와 내비게이션을 정하고, 각 패널에서는 부모가 실제로 준 제약을 사용한다. 내비게이션 레일·힌지·여백을 제외한 뒤에도 자식이 전체 화면 폭을 기준으로 두 열을 만들면 다시 좁아진다. 이때 `BoxWithConstraints` 또는 맞춤 `Layout`을 사용한다.

로그인·입력 폼의 최대 폭은 예를 들어 560~600dp부터, 읽기 중심 단일 본문은 720~840dp부터 검토할 수 있다. 이는 디자인 시안용 값이다. 좁은 화면에 최소 폭을 강제하지 않으며, 폰트를 화면 비율로 일괄 축소하지 않는다. 긴 한국어 운동명·큰 글씨·버튼 문구를 넣고 폭을 확정한다.

## 3. 공통 레이아웃부터 정리

### 시스템 UI와 키보드

상태 표시줄·내비게이션 바·태스크바·카메라 구멍의 공간은 `WindowInsets`로 처리한다. 일반 콘텐츠의 `safeDrawing`과 입력 화면의 `imePadding`을 일관되게 적용하고, 상위 컨테이너가 처리한 인셋을 하위에서 다시 더하지 않는다. 키보드가 열린 입력 필드와 저장 버튼에 스크롤로 도달할 수 있어야 한다. [공식 인셋 가이드](https://developer.android.com/develop/ui/compose/system/insets)

`MainScreens.kt`의 위 50/아래 110dp를 시스템 UI 대용으로 사용하는 구조를 공통 화면 컨테이너로 바꾼다. 메뉴가 콘텐츠 위에 떠 있어야 한다면 그 실제 높이와 확장 상태까지 반영한 콘텐츠 여백을 전달한다. 카메라 배경은 화면 가장자리까지 표시할 수 있어도 종료·완료·일시정지 버튼은 안전 영역에 둔다. 힌지는 인셋만으로 처리되지 않으므로 별도 회피 영역이 필요하다.

### 메뉴와 시트

작은 창은 현재 `MorphNav`를 유지하는 방향이 적절하다. 큰 창의 좌측 메뉴는 `NavigationSuiteScaffold` 또는 같은 판단을 쓰는 사용자 정의 레일로 구현할 수 있다. 표준 Scaffold는 창 정보로 메뉴를 전환하며, 기본 정책은 높이와 tabletop 상태도 고려한다. TREX의 운동 시작·식단 추가는 탭 목적지와 다른 실행 버튼이므로 큰 화면에서도 별도 행동으로 보존한다. [공식 적응형 내비게이션](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)

`SheetHost`에는 최대 폭, 사용 가능 높이, 키보드 회피, 콘텐츠 스크롤, 힌지 회피 기준을 둔다. 기존 호스트를 정리하거나 Material 시트를 사용하는 선택이 가능하다. 컴포넌트 교체만으로 내용 스크롤과 폴더블 처리가 모두 끝난다고 보지는 않는다.

## 4. 자세 평가 화면의 핵심 설계

### 프리뷰와 필수 조작을 함께 보장

현재의 `FIT_CENTER`는 유지한다. 이 저장소에서는 화면을 채우는 crop 때문에 사용자가 실제 분석 범위를 제대로 볼 수 없었던 전력이 코드에 기록돼 있다. 펼친 화면에서도 영상의 가로·세로 배율은 동일하게 유지하고, 남는 여백을 허용한다.

패널을 “항상 보이는 조작부”와 “읽는 상세”로 나눈다. 일시정지·완료·촬영 불가 상태는 항상 접근 가능하게 두고, 규칙 설명·스코프·상세 참고 내용은 남은 높이 안에서 스크롤한다. 세로 패널에 스크롤만 추가하고 무제한 측정을 허용하는 식으로 해결하지 않는다. 카메라와 버튼에 필요한 공간을 먼저 확보한다.

카메라의 최소 표시 영역은 임의의 고정 높이를 출시 기준으로 선언하지 말고, 전체 영상이 보이는지와 사용자가 관절·촬영 상태를 식별할 수 있는지 실기기로 정한다. 매우 작은 분할 창에서는 측정 일시정지와 창 확대 안내를 제안한다. 타이머 전환을 제공한다면 기존 “같은 운동을 타이머로 계속” 경로를 사용하며 자동 완료로 처리하지 않는다.

### 힌지 감지와 좌표

`FoldingFeature`의 `bounds`, `orientation`, `state`, `isSeparating`, `occlusionType`을 사용한다. 가려지는 힌지 또는 화면을 분리하는 접힘을 기준으로 콘텐츠 영역을 계산한다. 펼쳐진 단일 화면의 주름이 존재한다는 이유만으로 모든 경우에 두 영역을 강제할 필요는 없다. `HALF_OPENED`도 정밀한 각도 측정값은 아니다. [공식 폴더블 가이드](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)

힌지는 항상 화면 정중앙에 있다고 가정하지 않는다. Window 기준 힌지 픽셀 좌표를 카메라/패널 컨테이너의 로컬 좌표로 변환한 뒤 분할하고, 인셋·레일·스크롤 오프셋을 반영한다. 임의로 화면을 50:50으로 나누는 방식은 피한다.

### 회전·카메라·골격 정렬

1. `defaultDisplay` 대신 프리뷰가 연결된 디스플레이를 기준으로 삼는다. configuration 이벤트 외에 `DisplayListener`로 180도 회전과 디스플레이 변경을 확인하고 분석의 target rotation 및 중력축 변환이 같은 값을 사용하도록 한다. 실제 카메라 바인딩이 필요한 변경과 단순 UI 리사이즈를 구분한다. [CameraX 회전 처리](https://developer.android.com/media/camera/camerax/orientation-rotation)
2. 현재 분석기는 이미지를 `imageInfo.rotationDegrees`로 회전한 다음 MediaPipe에 넣는다. 새 오버레이 행렬이 raw `ImageProxy` 좌표를 입력으로 받는다면 이 전처리를 역변환하거나 전체 변환에 명시적으로 포함해야 한다. 회전을 두 번 적용하면 안 된다.
3. 프리뷰와 분석에 모두 4:3 선호를 주고 있지만 해상도 선택에는 fallback이 있다. 실제 crop·회전·미러를 포함해 분석 좌표를 프리뷰 좌표로 매핑하고, `PreviewView` 변환 정보 또는 CameraX 좌표 변환 API로 보강하는 방향을 검토한다. 변환 정보를 얻기 전에는 이전 좌표의 골격을 새 화면 위에 그대로 표시하지 않는다. [CameraX 출력 변환](https://developer.android.com/media/camera/camerax/transform-output)
4. 프리뷰를 분기 간 옮길 때 `AndroidView`·Surface 연결이 안정적으로 유지되는지 확인한다. 현재 `remember`로 만든 `PreviewView`를 반환하는 구조이므로, 새 배치 두 개를 동시에 애니메이션하며 동일 View를 두 부모에 넣지 않는다. 카메라 분석기는 하나만 유지한다.

`UseCaseGroup`/`ViewPort` 도입은 별도 판단이다. 분석·프리뷰의 영역을 맞출 수 있지만, 새 UI 패널 비율에 맞춰 분석 crop까지 바꾸면 관측 가능한 전신 범위가 달라질 수 있다. 표시상의 문제를 고치기 위해 분석 입력을 조용히 변경하지 않는다.

### 배치 전환과 운동 상태의 수명

현재 manifest는 `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden`을 직접 처리한다. 이를 바로 제거하면 화면에 저장된 세트 상태가 재생성으로 끊길 수 있다. 반대로 이 선언만으로 밀도·글꼴·프로세스 종료 등 모든 상태 보존이 해결되지는 않는다.

첫 구현에서는 `PostureLiveSessionScreen`의 세션 소유자를 배치 분기 위에 유지하고 UI만 옮긴다. `key(layoutMode)`로 세션 전체를 다시 만들거나, 세 가지 배치마다 엔진을 새로 생성하지 않는다. 카메라 View와 Activity를 장기 보관하는 대신, 다음 단계에서 세션 데이터·집계 상태를 ViewModel 또는 독립 상태 소유자로 옮기고 카메라 자원은 활성 lifecycle에 연결한다.

작은 UI 값·선택·스크롤은 `rememberSaveable`, 구성 변경을 넘어 살아야 하는 세션 데이터는 ViewModel, 프로세스 재시작 복구용 최소 ID·상태는 `SavedStateHandle` 또는 저장소에 둔다. 프레임 배열·비트맵을 Bundle에 저장하지 않는다. [Compose 상태 저장](https://developer.android.com/develop/ui/compose/state-saving)

접고 펼치는 UI 변경이 자동 세트 마감의 원인이 되지 않아야 한다. 실제 카메라 중단·이동으로 관측이 끊긴 경우에는 해당 구간을 정상으로 세지 않는다. 바닥 시간 측정에 중단 시간이 들어가거나, 재개 직후 준비 동작이 기존 극값 집계를 오염시키지 않도록 세션 단위 검증이 필요하다. 촬영 위치가 바뀐 경우의 재관측·앵커 정책은 별도 정의한다. COACH/TRACK·ship/beta/exclude·기존 바닥 피드백 정책이나 임계값을 폴더블 대응 과정에서 바꾸지 않는다.

## 5. 라이브러리와 OS 선택

최소 도입 대상은 `androidx.compose.material3.adaptive:adaptive`이다. 공식 안정판은 조사 시점 **1.3.0**이며, 이 버전의 `currentWindowAdaptiveInfoV2()`가 창 정보·기기 접힘 상태의 진입점이다. `collectFoldingFeaturesAsState()` 또는 `WindowInfoTracker.windowLayoutInfo()`로 필요한 힌지 정보를 받는다. 두 영역 Scaffold가 필요해질 때 `adaptive-layout`을 추가 검토한다. [공식 릴리스](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive), [V2 API](https://developer.android.com/reference/kotlin/androidx/compose/material3/adaptive/currentWindowAdaptiveInfoV2.composable)

일부 가이드에는 이전 `currentWindowAdaptiveInfo()` 예제가 남아 있다. 도입하는 버전의 API와 실제 Gradle 해석 결과를 기준으로 맞춘다. 현재 BOM `2026.04.01`과 새 Adaptive의 조합은 이번 조사에서 빌드 검증하지 않았다. 필요한 버전을 카탈로그에 명시하고, 전이 의존성으로 Compose가 예상 밖에 올라가는지 확인한 뒤 확정한다. 폴더블 대응만을 이유로 CameraX·MediaPipe·내비게이션 전체를 한 번에 교체할 필요는 없다.

Android 16에서 API 36을 타깃하는 일반 앱은 `smallestWidth >= 600dp` 디스플레이에서 방향·화면비·리사이즈 제한이 무시되는 정책 대상이다. 이 `smallestWidth` 조건과 UI용 현재 창 폭 등급은 같은 값으로 취급하지 않는다. Android 16에서는 제한적인 opt-out이 있으나 장기 해법으로 삼지 않는다. [Android 16 변경사항](https://developer.android.com/about/versions/16/behavior-changes-16)

Android 17의 API 37 타깃에서는 해당 큰 화면 제한의 개발자 opt-out도 제거된다. TREX의 현재 타깃은 36이므로 이 부분은 다음 타깃 상향 시 고려 사항이다. [Android 17 변경사항](https://developer.android.com/about/versions/17/behavior-changes-17)

## 6. 구현 순서와 완료 조건

| 단계 | 작업 | 완료 판단 |
|---|---|---|
| 1. 작은·낮은 창에서 기능 확보 | 공통 인셋·콘텐츠 여백·시트 높이, 타이머, 핵심 버튼 줄바꿈/재배치 | 320dp급 좁은 폭·낮은 창·키보드·큰 글씨에서 주요 행동에 도달 |
| 2. 자세 화면 적응 | 세션 소유자를 유지하며 폭/높이 기반 카메라·패널 배치, 상세 높이 제한 | 전체 프리뷰와 필수 조작이 함께 보이고 레이아웃 전환으로 세트가 끝나지 않음 |
| 3. 접힘·카메라 전환 검증 | 실제 힌지 경계, tabletop/book, 디스플레이 회전, 골격 좌표 | 힌지 위 핵심 요소 없음. 카메라 중복 바인딩·정렬 오류·상태 손실 없음 |
| 4. 넓은 화면 활용 | 최대 본문 폭, 메뉴 레일, 운동·기록의 목록/상세 배치 | 넓은 화면에서도 읽기 쉬운 밀도이며 선택·스크롤·입력 유지 |

화면 기능을 실제 추가하는 단계에서는 저장소 관례대로 `KOTLIN_PORTING_SPEC.md`에 구현 근거와 검증 결과를 추가한다. 현재 조사 제안을 구현 완료 상태로 스펙에 기록하지 않는다.

## 7. 검증 계획

아래는 실행할 검증 계획이며, 통과 결과가 아니다.

### 정적 배치

| 축 | 대표 조건 |
|---|---|
| 폭 경계 | 320, 360, 599/600/601, 839/840/841dp. 넓은 창은 1200dp도 확인 |
| 높이 | 360, 479/480/481, 720, 900dp. 폭과 높이를 조합해 좁고 낮은 창 포함 |
| 접힘 | FLAT, 가로 HALF_OPENED, 세로 HALF_OPENED, 가리는 힌지, 중앙에서 벗어난 힌지, 힌지 없는 기기 |
| 접근성 | 글꼴 배율 1.0/1.3/2.0, 디스플레이 크기 확대, 긴 운동명·긴 수치·참고 문장 |
| 시스템 UI | 제스처/3버튼 내비게이션, 태스크바, 디스플레이 cutout, 키보드 표시 |
| 화면 | 로그인/가입/온보딩, 4개 탭, 운동·식단 시트, 타이머, 자세 권한 거부, 자세 측정, 완료 상세, 기록 |

단위는 물리 해상도 px가 아닌 앱 창의 dp이다. Fold 외부→내부 화면과 Flip tabletop을 각각 검증하며, 특정 제조사가 커버 화면에서 일반 앱을 실행할 수 있는지는 별도 플랫폼 제약으로 취급한다.

Compose Preview로 빠르게 배치를 확인하고 `DeviceConfigurationOverride`로 크기·글꼴 조건을 조합한다. 힌지 정보 주입은 WindowManager 테스트 도구, 실제 접힘·회전 전환은 폴더블 AVD와 필요 시 Espresso Device API를 사용한다. [화면 테스트 도구](https://developer.android.com/training/testing/different-screens/tools), [Compose 테스트 패턴](https://developer.android.com/develop/ui/compose/testing/common-patterns)

### 동적 전환과 상태

1. 입력 중 접기/펼치기·분할 폭 조절 → 입력값과 포커스 유지, 저장 버튼 접근.
2. 운동 목록을 스크롤하고 선택 후 전환 → 선택 운동·목록 위치 유지.
3. 자세 세트에서 전환 → workout ID·set ID·세트 진행·기존 렙·앵커 유지, 배치 변경만으로 로그 마감/중복 리포트 없음.
4. 플랭크 측정 중 실제 카메라 중단·복귀 → 중단 시간을 유지 시간에 포함하지 않음, 관측 불가를 회복·정상으로 표시하지 않음.
5. 전면/후면 각각 0/90/180/270도 회전 → 골격의 좌우·방향·관절 위치가 실제 영상에 맞음. 화면 중앙뿐 아니라 네 모서리 근처도 확인.
6. 앱 재생성 및 시스템에 의한 프로세스 종료 후 복귀 → 복구 가능한 값과 다시 준비해야 하는 측정 상태를 구분. 강제 종료를 정상 복구와 동일시하지 않음.

`StateRestorationTester`는 작은 Compose 저장 상태 검증에 사용하고, 실제 Activity 재생성·카메라·전체 세션은 별도로 검사한다. 크기 강제 테스트만으로 실제 카메라 센서와 힌지 동작이 검증됐다고 보지 않는다.

구현 후 JVM 회귀 검사와 APK 빌드는 Windows에서 다음 명령을 사용한다.

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

UI 테스트를 추가할 때는 현재 앱의 테스트 의존성에 필요한 Compose 테스트 도구를 명시한다. 엔진 테스트 통과는 레이아웃·실카메라 통과를 대신하지 않는다.

**현재 결론:** 가장 먼저 고칠 대상은 자세 화면의 방향만 보는 분기와 패널 공간 배분, 타이머의 고정 크기, 탭/시트의 공통 여백이다. 폴더블 전용 두 영역 화면은 이 기반과 세션 유지 조건 위에 추가한다.
