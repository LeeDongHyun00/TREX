# 커뮤니티 앱 화면 구현 계획 (계획 3/3) — 초안

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 하단 바에 커뮤니티 탭을 더하고, 인스타형 피드·글 상세·글쓰기·프로필·신고/차단·관리자 신고함·오운완 올리기 화면을 Android·iPhone 공유 Compose 로 만든다.

**Architecture:** 화면 상태는 `AppViewModel` 의 커뮤니티 절(계획 2 의 `CommunityApi` 를 감싼다)에 두고, 계산·상태 변화는 순수 함수(`community/` 의 `CommunityGate`·`FeedState`·`TimeAgo`)로 떼어 JVM 테스트로 잠근다. 화면 파일은 `community/` 아래에 두고 전부 `ios-ui/android-sources.txt` 에 올린다. 사진은 계획 2 의 `RemoteImage`(안정 키), 올리기는 `FoodPhoto.jpeg()`.

**Tech Stack:** Kotlin, Compose / Compose Multiplatform, 계획 2 의 `CommunityApi`·`RemoteImageLoader`·`BinaryTransport`.

**Spec:** `docs/superpowers/specs/2026-10-28-community-design.md`(§2 목표, §3 판단 표, §5 동작, §7 문구). 서버 계약: Draft PR #28. 계획 2: `docs/superpowers/plans/2026-10-29-community-app-foundation.md`(브랜치 `feature/community-app-foundation` — 이 계획은 그 위에서 시작한다).

## Global Constraints

- 공유 화면 규칙(CLAUDE.md §108): `android.*`·`java.*` 금지, 플랫폼 기능은 짝, 테마는 `Trex.c`·기존 `AdaptiveLayout`(최대 폭 840dp, 폴더블 힌지), 글자 1.3배에서도 깨지지 않게.
- 문구: 한국어 존댓말 평서체. 글쓰기 화면에 **"서버에 저장되고 모든 사용자에게 공개돼요"**, 동의 화면에 **만 14세 이상 확인** 과 이용규칙 전문 링크(`CommunityApi.TERMS_URL`, `LocalUriHandler`).
- 사진 캐시 키: 글 `post:<글 id>:<순서>`, 프로필 `avatar:<publicId>:<avatarUrl 경로 부분>`(사진을 바꾸면 키가 바뀐다).
- 서버 오류는 `ApiException.message` 를 그대로 보인다. 글쓰기 실패 때 입력(본문·사진)을 지우지 않는다.
- 오운완 카드: 날짜·운동 이름·완료 세트·총 시간·예상 kcal **숫자만**(자세 판정·점수·헤드라인 없음 — 설계 §3).
- 화면 순서·탭: 홈 · 운동 · 식단 · **커뮤니티** · 내 정보. 커뮤니티 탭은 다시 눌러도 리모컨으로 바뀌지 않는다(홈처럼).

## Review Focus

1. 로그인 없이 쓰는 사람·iPhone 에서 웹 클라이언트 ID 가 빈 빌드 → 커뮤니티 탭이 안내만 보이고 다른 탭은 그대로. → Task 1 `게이트는_계정과_설정으로_정해진다`
2. 좋아요를 빠르게 여러 번 → 화면 수가 튀지 않고 마지막 상태로 맞는다(낙관적 갱신 + 서버 실패 시 되돌림). → Task 3 `좋아요_실패하면_되돌린다`
3. 피드를 넘기다 글이 지워져도 중복 카드가 생기지 않는다(같은 id 는 한 번). → Task 3 `다음_쪽을_붙여도_같은_글은_한_번`
4. 글쓰기 중 네트워크 실패·429 → 본문·사진이 그대로 남고 서버 문구가 보인다. → Task 5 실기기 확인 + `ComposeState` 테스트
5. 정지된 사용자 → 읽기만 되고 쓰기 버튼은 "이용이 정지된 계정이에요". → Task 1 게이트 `Suspended`

---

### Task 1: 진입 게이트와 AppViewModel 커뮤니티 절
**Files:** Create `community/CommunityGate.kt`(공유) · Modify `TrexAppState.kt`(AppViewModel: `val community: CommunityApi`, `var communityMe`, `suspend fun refreshCommunityMe()`) · Test `community/CommunityGateTest.kt`
**Interfaces:** `sealed interface CommunityGate { NoLogin, NotSignedIn, NeedNickname, NeedTerms, Ready(admin: Boolean), Suspended(admin: Boolean) }`, `fun communityGate(loginAvailable: Boolean, signedIn: Boolean, me: CommunityMe?): CommunityGate` — `loginAvailable` = `platform.googleWebClientId.isNotBlank()`, `signedIn` = `account != null`, `me == null` 이면 아직 모름(로딩).
- [ ] 테스트 `게이트는_계정과_설정으로_정해진다`(6가지 조합) → 실패 확인 → 구현 → 통과 → 커밋

### Task 2: 커뮤니티 탭
**Files:** Modify `TrexData.kt`(`TrexTab.Community("커뮤니티")` 를 Diet 와 Profile 사이), `TrexApp.kt`(아이콘 `Icons.Rounded.Forum`, `MainTabs` 분기, 다시 눌러도 펼치지 않음) · Create `community/CommunityTabScreen.kt`(게이트별 화면: 로그인 안내(내 정보의 계정 연결로)·"이 기기에서는 아직 커뮤니티를 쓸 수 없어요"·닉네임·동의·피드) · Test `TrexTabTest.탭_순서`
- [ ] 테스트 → 구현 → 통과 → 실기기(폰 360dp·폴드 펼침·글자 1.3배) → 커밋

### Task 3: 피드 상태와 카드
**Files:** Create `community/FeedState.kt`(순수: 쪽 붙이기·중복 제거·좋아요 낙관적 갱신/되돌림·글 지우기), `community/TimeAgo.kt`("방금"·"n분 전"·"n시간 전"·"n일 전"·"M월 d일"), `community/PostCard.kt`(작성자 줄·사진 좌우 넘김 + 점·두 번 탭 하트 애니메이션·하트/댓글 버튼·수·본문 접기·"댓글 n개 보기"·시각·숨김 배지·⋯ 메뉴), `community/FeedScreen.kt`(당겨서 새로고침·끝에서 다음 쪽·빈 피드 안내 "첫 글을 올려 보세요")
**Tests:** `FeedStateTest`: `다음_쪽을_붙여도_같은_글은_한_번`, `좋아요는_바로_바뀌고_실패하면_되돌린다`, `지운_글은_빠진다`; `TimeAgoTest`
- [ ] 테스트 → 구현 → 통과 → 실기기 → 커밋

### Task 4: 글 상세와 댓글
**Files:** Create `community/PostDetailScreen.kt`(글 + 댓글 목록(오래된 순, "이전 댓글 보기")·입력창(500자)·내 댓글/내 글의 댓글 지우기·댓글 ⋯ 신고)
- [ ] 순수 부분(댓글 쪽 붙이기) 테스트 → 구현 → 실기기 → 커밋

### Task 5: 글쓰기
**Files:** Create `community/ComposeState.kt`(순수: 사진 0~5·본문 2,000자·올리기 가능 여부·실패 시 입력 유지), `community/ComposeScreen.kt`(+ 버튼에서 연다, 갤러리 `rememberFoodGallery(5)`·카메라 `rememberFoodCamera`, 사진 미리보기 지우기, 공개 안내 문구, 올리는 중 막기)
**Tests:** `ComposeStateTest`: `사진도_글도_없으면_못_올린다`, `여섯_번째_사진은_더하지_않는다`, `실패해도_입력은_남는다`
- [ ] 테스트 → 구현 → 실기기(세로 사진·큰 사진·EXIF 회전 사진이 바로 서고 1080 으로 줄어드는지) → 커밋

### Task 6: 프로필
**Files:** Create `community/ProfileScreen.kt`(사진·닉네임·글 수·3열 격자(첫 사진, 글만이면 글 타일)·내 프로필: 닉네임·사진 바꾸기, 차단 목록, 내 신고(처리 상태)·남의 프로필: 차단/신고)
- [ ] 실기기 → 커밋

### Task 7: 신고·차단·관리자 신고함
**Files:** Create `community/ReportSheet.kt`(사유 6개, 보내면 "신고했어요. 운영자가 24시간 안에 확인해요"), 차단 확인 대화상자, `community/AdminInboxScreen.kt`(내 정보 탭, 관리자에게만: 신고 대상별 줄 — 미리보기·누적 수·사유 → 그대로 둠/지움, 숨긴 글 복구, 정지 목록 해제, 프로필 사진 지우기) · Modify `SettingsScreen.kt`(관리자 진입, 내 신고)
- [ ] 실기기(관리자 계정·일반 계정 둘 다) → 커밋

### Task 8: 오운완 올리기
**Files:** Create `community/WorkoutCard.kt`(카드 컴포저블 — 공룡 아이콘·날짜·운동·세트·시간·kcal), 화면을 그림으로 뜨는 짝(`rememberGraphicsLayer` → `ImageBitmap` → JPEG: Android `asAndroidBitmap().compress`, iPhone Skia) · Modify `SessionScreens.kt`(완료 화면에 "오운완 올리기" 버튼 → 글쓰기 화면에 카드가 첫 사진으로)
- [ ] 카드 값 계산 순수 함수 테스트(세트·시간·kcal 합) → 구현 → 실기기 → 커밋

### Task 9: 문구·문서
- [ ] `AuthScreens.kt` "계정 없이도 모든 기능" → "커뮤니티를 뺀 모든 기능", README·AGENTS.md 예정, `KOTLIN_PORTING_SPEC.md` 새 절(커뮤니티 화면·근거), 처리방침·이용규칙 PR #26 머지 시점 메모
- [ ] PR(대상 main, 계획 2 위) — CI `shared`(iPhone 컴파일)·수동 `ios` 작업, Android·iPhone 실기기 확인 목록

## 늦어지면 (설계 §9)
관리자 신고함 화면 → 오운완 카드 → 프로필 격자 순으로 뺀다. 신고·차단·이용규칙 동의·글 삭제는 빼지 않는다.
