# 커뮤니티 서버 구현 계획 (계획 1/3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 커뮤니티 탭이 쓸 서버 API(프로필·이용규칙·글·사진·좋아요·댓글·차단·신고·관리자·탈퇴 삭제)를 Spring Boot 서버에 더하고 운영에 배포한다.

**Architecture:** 기존 계층 패키지(`controller`·`service`·`repository`·`entity`·`dto`·`exception`)를 그대로 따르고, 커뮤니티 전용 보조 코드는 새 패키지 `community`(금칙어·정리)·`storage`(사진 저장소 경계)·`config` 에 둔다. 사진은 `ImageStorage` 경계 뒤에 두어 운영은 S3, 테스트·로컬은 메모리. 경로는 전부 `/api/community/**`, 인증은 기존 JWT(`@AuthenticationPrincipal String loginId`) 그대로. 새 테이블만 더한다.

**Tech Stack:** Java 17, Spring Boot 4.0.6(webmvc·data-jpa·security·validation, Jackson 3), AWS SDK for Java v2(`s3`), H2(테스트)·PostgreSQL(운영), JUnit 5 + MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-28-community-design.md` — 계획 2(앱 기반: 바이너리 HTTP·이미지 로더·사진 인코딩)와 계획 3(앱 화면)은 이 계획이 끝난 뒤 쓴다.

작업 위치: `server/`. 테스트는 `cd server && JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew.bat test`(Windows Git Bash). 기존 테스트 20개가 계속 통과해야 한다.

## Global Constraints

- 기존 테이블·기존 API 는 바꾸지 않는다. 새 테이블과 `/api/community/**` 만 더한다(운영 `ddl-auto: update`, Flyway 없음).
- 주석·KDoc·오류 메시지는 한국어. 오류 본문은 기존 `ErrorResponse.of(message)`.
- 다른 사용자에게 보이는 식별자는 `publicId`·닉네임·프로필 사진뿐 — `loginId`(구글 sub 포함)·실명·이메일을 응답에 넣지 않는다.
- 닉네임: 앞뒤 공백 제거 후 **2~12자**(코드포인트), 제어문자·제로폭 문자(U+200B~U+200D, U+FEFF) 불가, 금칙어 불가, **대소문자 무시 중복 불가**.
- 글 본문 **≤ 2,000자**, 댓글 **≤ 500자**, 사진 **0~5장**, 장당 **≤ 2MB**, **JPEG 만**(첫 바이트 `FF D8 FF`). 글은 본문·사진 중 하나는 있어야 한다.
- 서버는 이미지를 디코딩·리사이즈하지 않는다(앱 힙 384MB) — 가로·세로는 JPEG 헤더만 읽는다.
- 속도 제한: 글 **1분 1개·24시간 30개**, 댓글 **1분 5개** → 429.
- 서로 다른 사람의 신고 **3건**이면 그 글·댓글을 자동 숨김(사유 `신고 누적`).
- 피드 **20개**, 댓글 **50개**씩, 커서는 id 내림차순 `before`.
- 사진 주소는 S3 서명 GET 주소, 수명 **1시간**.
- 이용규칙 버전 문자열 **`2026-10-28`**.
- 설정(`trex.community.*`): `s3-bucket`(env `COMMUNITY_S3_BUCKET`, 비면 메모리 저장소), `s3-region`(기본 `ap-southeast-2`), `admin-login-ids`(env `COMMUNITY_ADMIN_LOGIN_IDS`, 쉼표), `report-webhook-url`(env `COMMUNITY_REPORT_WEBHOOK_URL`, 비면 알림 안 함). 운영 프로필은 버킷이 없으면 뜨지 않는다.

## Review Focus

1. 좋아요를 빠르게 두 번(더블탭·재전송) 눌러도 둘 다 204, 좋아요 수는 1 — 유니크 제약 위반이 409 로 새면 안 된다. → Task 4 `좋아요_두_번_해도_204_수는_1`
2. S3 사진 삭제가 실패해도 글 삭제·탈퇴는 성공한다(고아 사진은 로그만). → Task 3 `글_삭제_시_사진_삭제가_실패해도_204`, Task 7 `사진_삭제가_실패해도_탈퇴는_성공한다`
3. 피드를 넘기는 사이 글이 지워지거나 숨겨져도 중복·누락이 없다. → Task 3 `피드_페이지_사이에_글이_지워져도_중복_누락_없음`
4. 닉네임 `Trex` 와 ` trex ` 는 같은 이름, 공백만·제로폭 문자는 거절. → Task 2 `닉네임_대소문자_공백만_다른_중복은_409`, `닉네임_규칙_위반은_400`
5. 정지된 사용자의 남은 토큰: 읽기는 되고 쓰기는 403, 그의 글은 남에게 안 보이고 본인에게는 보인다. → Task 6 `정지된_사용자는_쓰기_403_읽기는_된다`, `정지된_사용자의_글은_남에게_안_보이고_본인에게는_보인다`

---

## 파일 구조

`server/src/main/java/com/trex/server/` 아래:

| 파일 | 책임 |
|---|---|
| `config/CommunityProperties.java` | `trex.community.*` 설정 record |
| `config/TimeConfig.java` | `Clock` 빈(속도 제한·시각 — 테스트가 바꿔 끼운다) |
| `community/BannedWords.java` + `resources/community/banned-words.txt` | 금칙어 정규화·검사 |
| `community/CommunityAccountCleaner.java` | 탈퇴 시 커뮤니티 데이터·사진 삭제 |
| `storage/ImageStorage.java`, `InMemoryImageStorage.java`, `S3ImageStorage.java`, `ImageStorageConfig.java`, `JpegInfo.java` | 사진 저장 경계와 구현, JPEG 헤더 |
| `entity/CommunityProfile.java`, `Post.java`, `PostImage.java`, `PostLike.java`, `Comment.java`, `Block.java`, `Report.java` (+ enum `ReportTargetType`, `ReportReason`, `ReportResolution`) | 새 테이블 |
| `repository/` 각 엔티티 저장소 | 조회 |
| `service/CommunityProfileService.java`, `PostService.java`, `CommentService.java`, `ModerationService.java`, `AdminService.java`, `ReportNotifier.java`, `DiscordReportNotifier.java` | 규칙 |
| `controller/CommunityProfileController.java`, `PostController.java`, `CommentController.java`, `ModerationController.java`, `AdminController.java` | API |
| `dto/community/*.java` | 요청·응답 record |
| `exception/ForbiddenException.java`(403), `TooManyRequestsException.java`(429) | 새 예외 |

테스트: `server/src/test/java/com/trex/server/community/` — 공통 바탕 `CommunityApiTestSupport`(가짜 구글 검증기는 `SocialSyncApiTest` 와 같은 방식, `bearer(sub)`, `ready(sub, nickname)` = 프로필+이용규칙, `jpeg(w, h)` = ImageIO 로 만든 작은 JPEG 바이트, `MutableClock`).

---

### Task 1: 기반 — 설정·시계·예외·금칙어·JPEG 헤더·메모리 저장소

**Files:**
- Create: `config/CommunityProperties.java`, `config/TimeConfig.java`, `exception/ForbiddenException.java`, `exception/TooManyRequestsException.java`, `community/BannedWords.java`, `resources/community/banned-words.txt`, `storage/ImageStorage.java`, `storage/InMemoryImageStorage.java`, `storage/ImageStorageConfig.java`, `storage/JpegInfo.java`
- Modify: `exception/GlobalExceptionHandler.java`, `ServerApplication.java`(`@ConfigurationPropertiesScan`), `resources/application-prod.yml`, `resources/application-example.yml`, `src/test/resources/application.yml`
- Test: `community/BannedWordsTest.java`, `community/JpegInfoTest.java`

**Interfaces:**
- Produces:
  - `record CommunityProperties(String s3Bucket, String s3Region, List<String> adminLoginIds, String reportWebhookUrl)` — `@ConfigurationProperties("trex.community")`, `s3Region` 기본 `ap-southeast-2`
  - `Clock` 빈(`Clock.systemUTC()`)
  - `ForbiddenException(String)` → 403, `TooManyRequestsException(String)` → 429, `MaxUploadSizeExceededException` → 413 `"사진이 너무 커요(장당 2MB까지)"`
  - `BannedWords(List<String> words)`, `static BannedWords fromClasspath()`, `boolean contains(String text)` — 비교 전 NFKC·소문자·공백·문장부호·제로폭 문자 제거
  - `interface ImageStorage { void put(String key, byte[] jpeg); void delete(String key); String url(String key); }`
  - `InMemoryImageStorage implements ImageStorage` — `url` = `"memory://" + key`, 테스트용 `boolean has(String key)`
  - `record JpegInfo(int width, int height)`, `static JpegInfo read(byte[] bytes)` — JPEG 가 아니면 `InvalidRequestException("JPEG 사진만 올릴 수 있어요")`, 헤더만 읽는다(`ImageReader.getWidth(0)`, 픽셀 디코딩 없음)
  - `ImageStorageConfig` — 지금은 늘 `InMemoryImageStorage`(S3 갈래는 Task 8)
- 설정: `spring.servlet.multipart.max-file-size: 2MB`, `max-request-size: 11MB`. 테스트 설정은 `trex.community.admin-login-ids: google_admin-sub`.

- [ ] **Step 1: 실패하는 테스트 작성** — `BannedWordsTest`: `new BannedWords(List.of("바보"))` 에서 `contains("바 보")`, `contains("바.보")`, `contains("바​보")`, `contains("ＢＡＢＯ")`(단어 `"babo"` 로) 가 true, `contains("바나나")` false. `JpegInfoTest`: ImageIO 로 만든 3×2 JPEG → `JpegInfo(3, 2)`, PNG 바이트·임의 바이트 → `InvalidRequestException`.
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.community.*"` → 컴파일 실패(클래스 없음).
- [ ] **Step 3: 위 Interfaces 대로 구현** — 금칙어 시작 목록은 빌더가 고른다(예: korcen 목록의 단어, MIT). 파일은 한 줄에 한 단어, `#` 주석.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS, 전체 `./gradlew.bat test` PASS(기존 20개 포함).
- [ ] **Step 5: 커밋** — `서버 커뮤니티 기반: 설정·시계·예외·금칙어·JPEG 헤더·메모리 사진 저장소`

### Task 2: 커뮤니티 프로필·이용규칙·프로필 사진

**Files:**
- Create: `entity/CommunityProfile.java`, `repository/CommunityProfileRepository.java`, `service/CommunityProfileService.java`, `controller/CommunityProfileController.java`, `dto/community/ProfileDto.java`, `MeResponse.java`, `NicknameRequest.java`, `TermsRequest.java`
- Test: `community/CommunityApiTestSupport.java`, `community/CommunityProfileApiTest.java`

**Interfaces:**
- Consumes: Task 1 의 `BannedWords`, `ImageStorage`, `JpegInfo`, `CommunityProperties`, `Clock`.
- Produces:
  - 테이블 `COMMUNITY_PROFILES`: `id`(Long), `user_id`(unique, `User` 1:1), `public_id`(unique, 12자 무작위 영숫자), `nickname`(12), `nickname_key`(unique — 정규화한 소문자), `avatar_key`, `terms_version`, `terms_accepted_at`, `suspended_at`, `created_at`
  - `CommunityProfileService`:
    - `MeResponse me(String loginId)`
    - `ProfileDto setNickname(String loginId, String nickname)` — 없으면 만들고 있으면 바꾼다
    - `void acceptTerms(String loginId, String version)` — `"2026-10-28"` 이 아니면 400
    - `ProfileDto setAvatar(String loginId, byte[] jpeg)`, `void clearAvatar(String loginId)` — 키 `avatars/{publicId}-{uuid}.jpg`, 옛 사진은 지운다
    - `CommunityProfile requireReader(String loginId)` — 프로필 없음 403 `"닉네임을 먼저 정해 주세요"`, 이용규칙 미동의 403 `"이용규칙에 먼저 동의해 주세요"` (정지돼도 읽기는 된다)
    - `CommunityProfile requireWriter(String loginId)` — `requireReader` + 정지면 403 `"이용이 정지된 계정이에요"`
    - `boolean isAdmin(String loginId)` — `adminLoginIds` 에 있으면
  - `record ProfileDto(String publicId, String nickname, String avatarUrl)` (사진 없으면 `avatarUrl` null)
  - `record MeResponse(ProfileDto profile, boolean termsAccepted, boolean admin, boolean suspended)`
  - API: `GET /api/community/me`, `PUT /api/community/me/profile` `{nickname}` → `ProfileDto`, `POST /api/community/me/terms` `{version}` → 204, `PUT /api/community/me/avatar`(multipart `image`) → `ProfileDto`, `DELETE /api/community/me/avatar` → 204

- [ ] **Step 1: 실패하는 테스트 작성** (`CommunityProfileApiTest`)
  - `처음_me는_프로필_없음_admin_false` — `$.profile` null, `$.termsAccepted` false, `$.admin` false
  - `닉네임을_정하면_publicId가_생기고_loginId는_노출되지_않는다` — `$.publicId` 길이 12, 응답 문자열에 `"google_"` 없음
  - `닉네임_대소문자_공백만_다른_중복은_409` — A `"Trex"` 성공, B `" trex "` → 409
  - `닉네임_규칙_위반은_400` — `"a"`, `"1234567890123"`, `"   "`, `"공​룡"`, 금칙어 → 각 400
  - `이용규칙_버전이_다르면_400_맞으면_me에_반영` — `"2026-01-01"` 400, `"2026-10-28"` 204 후 `$.termsAccepted` true
  - `아바타_JPEG는_avatarUrl_PNG는_400` — JPEG → `$.avatarUrl` 이 `memory://avatars/` 로 시작, PNG → 400
  - `관리자_목록의_loginId는_admin_true` — sub `admin-sub` 로 로그인하면 `$.admin` true
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.community.CommunityProfileApiTest"` → FAIL(404·컴파일)
- [ ] **Step 3: 구현** — 위 Interfaces. 닉네임 정규화는 trim → 제어·제로폭 검사 → 길이(`codePointCount`) → 금칙어 → `nickname_key = toLowerCase(Locale.ROOT)`.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + 전체 PASS
- [ ] **Step 5: 커밋** — `서버 커뮤니티: 프로필·닉네임·이용규칙 동의·프로필 사진`

### Task 3: 글 쓰기·피드·상세·삭제·사용자 프로필

**Files:**
- Create: `entity/Post.java`, `entity/PostImage.java`, `repository/PostRepository.java`, `repository/PostImageRepository.java`, `service/PostService.java`, `controller/PostController.java`, `dto/community/PostDto.java`, `ImageDto.java`, `PostPage.java`, `UserProfileDto.java`
- Test: `community/PostApiTest.java`

**Interfaces:**
- Consumes: Task 2 `requireReader`/`requireWriter`/`isAdmin`, `ProfileDto`; Task 1 `ImageStorage`, `JpegInfo`, `BannedWords`, `Clock`.
- Produces:
  - 테이블 `POSTS`(`id`, `author_id`→프로필, `body` 2000 null 허용, `created_at`, `hidden_at`, `hidden_reason` 40), `POST_IMAGES`(`id`, `post_id`, `position`, `storage_key` 200, `width`, `height`)
  - `PostService`:
    - `PostDto create(String loginId, String body, List<MultipartFile> images)` — 본문 금칙어 400 `"쓸 수 없는 표현이 있어요"`, 속도 제한 429 `"잠시 뒤에 다시 올려 주세요"`, 키 `posts/{postId}/{position}-{uuid}.jpg`
    - `PostPage feed(String loginId, Long before, int limit)`
    - `PostDto get(String loginId, long postId)` — 숨김 글·정지된 작성자의 글은 작성자 본인·관리자만, 그 밖은 404
    - `void delete(String loginId, long postId)` — 작성자·관리자만(아니면 403), 사진은 **커밋 뒤** 지우고 실패는 로그만
    - `UserProfileDto user(String loginId, String publicId)`, `PostPage userPosts(String loginId, String publicId, Long before)`
  - `record ImageDto(String url, int width, int height)`
  - `record PostDto(long id, ProfileDto author, String body, List<ImageDto> images, long likeCount, boolean likedByMe, long commentCount, long createdAt, boolean hidden, String hiddenReason, boolean mine)` — `createdAt` epoch ms, `hiddenReason` 은 숨김 글을 작성자·관리자가 볼 때만(스펙 "숨김 처리됨 · 사유"). 좋아요·댓글 수는 Task 4 전까지 0
  - `record PostPage(List<PostDto> items, Long nextBefore)` — 다음 페이지가 없으면 `nextBefore` null
  - `record UserProfileDto(ProfileDto profile, long postCount, boolean blockedByMe)` — `blockedByMe` 는 Task 5 전까지 false
  - API: `GET /api/community/posts?before=&limit=`(limit 기본·최대 20), `POST /api/community/posts`(multipart: 글 `body` 선택, 파일 `images` 0~5) → 201 `PostDto`, `GET /api/community/posts/{id}`, `DELETE /api/community/posts/{id}` → 204, `GET /api/community/users/{publicId}`, `GET /api/community/users/{publicId}/posts?before=`

- [ ] **Step 1: 실패하는 테스트 작성** (`PostApiTest`)
  - `프로필_없거나_이용규칙_미동의면_글쓰기_403`
  - `글만_사진만_올리기는_201_둘_다_없으면_400`
  - `사진_6장은_400_PNG는_400_2MB_넘으면_413`
  - `피드는_최신순_20개와_nextBefore` — 글 22개(`MutableClock` 을 2분씩 넘기며) → 1쪽 20개·`nextBefore` = 20번째 id, 2쪽 2개·`nextBefore` null
  - `피드_페이지_사이에_글이_지워져도_중복_누락_없음` — 1쪽을 받은 뒤 1쪽의 글 하나와 2쪽 글 하나를 지우고 2쪽을 받으면, 남은 글 id 가 정확히 한 번씩
  - `1분_안에_두_번째_글은_429_24시간_31번째도_429`
  - `남의_글_삭제는_403_내_글은_204_그_뒤_404`
  - `글_삭제_시_사진_삭제가_실패해도_204` — `delete` 가 늘 예외인 저장소를 `@Primary` 로 끼운 중첩 테스트 설정
  - `사진_주소와_크기가_응답에_담긴다` — `$.images[0].url` 이 `memory://posts/` 로 시작, `width`·`height` 가 올린 JPEG 크기
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.community.PostApiTest"` → FAIL
- [ ] **Step 3: 구현** — 피드 조건: `id < before`(있으면), 숨김 아님 또는 내 글, 작성자 정지 아님 또는 내 글, `id` 내림차순. 사진 올리기는 글 저장 뒤, 중간 실패면 예외로 롤백하고 이미 올린 키는 지운다. 커밋 뒤 삭제는 `TransactionSynchronizationManager.registerSynchronization`(afterCommit).
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + 전체 PASS
- [ ] **Step 5: 커밋** — `서버 커뮤니티: 글 쓰기·사진·피드·상세·삭제·사용자 프로필`

### Task 4: 좋아요·댓글

**Files:**
- Create: `entity/PostLike.java`, `entity/Comment.java`, `repository/PostLikeRepository.java`, `repository/CommentRepository.java`, `service/CommentService.java`, `controller/CommentController.java`, `dto/community/CommentDto.java`, `CommentPage.java`, `CommentRequest.java`
- Modify: `service/PostService.java`(좋아요·댓글 수 채우기 — 피드는 글 id 묶음으로 한 번에 센다), `controller/PostController.java`(좋아요 경로)
- Test: `community/LikeCommentApiTest.java`

**Interfaces:**
- Consumes: Task 3 `PostService.get` 의 보이기 규칙, Task 2 `requireReader`/`requireWriter`/`isAdmin`.
- Produces:
  - 테이블 `POST_LIKES`(`post_id`, `profile_id`, `created_at`, unique(`post_id`,`profile_id`)), `COMMENTS`(`id`, `post_id`, `author_id`, `body` 500, `created_at`, `hidden_at`, `hidden_reason`)
  - `PostService.like(String loginId, long postId)`, `unlike(...)` — 둘 다 멱등(이미 있음·이미 없음도 성공, 유니크 위반은 성공으로 삼킨다)
  - `CommentService`: `CommentPage list(String loginId, long postId, Long before)`, `CommentDto add(String loginId, long postId, String body)`(빈 본문·500자 초과·금칙어 400, 1분 5개 429), `void delete(String loginId, long commentId)` — 댓글 작성자·**글 작성자**·관리자만
  - `record CommentDto(long id, ProfileDto author, String body, long createdAt, boolean hidden, String hiddenReason, boolean mine)`, `record CommentPage(List<CommentDto> items, Long nextBefore)` — 댓글은 오래된 순으로 보여 주되 커서는 id 기준
  - API: `PUT|DELETE /api/community/posts/{id}/like` → 204, `GET /api/community/posts/{id}/comments?before=`, `POST /api/community/posts/{id}/comments` `{body}` → 201, `DELETE /api/community/comments/{id}` → 204

- [ ] **Step 1: 실패하는 테스트 작성** (`LikeCommentApiTest`)
  - `좋아요_두_번_해도_204_수는_1_취소_두_번도_204`
  - `피드에_좋아요_수_내_좋아요_댓글_수가_담긴다`
  - `빈_댓글과_501자_댓글은_400`
  - `숨겨진_글에는_댓글을_못_쓴다_404`
  - `글쓴이는_내_글의_남_댓글을_지울_수_있고_제3자는_403`
  - `댓글_1분에_6번째는_429`
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.community.LikeCommentApiTest"` → FAIL
- [ ] **Step 3: 구현** — 위 Interfaces
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + 전체 PASS
- [ ] **Step 5: 커밋** — `서버 커뮤니티: 좋아요·댓글`

### Task 5: 차단·신고·자동 숨김·디스코드 알림

**Files:**
- Create: `entity/Block.java`, `entity/Report.java`, enum `entity/ReportTargetType.java`(`POST`,`COMMENT`,`USER`), `entity/ReportReason.java`(`SPAM`,`ABUSE`,`SEXUAL_VIOLENT`,`PRIVACY`,`RIGHTS`,`OTHER`), `entity/ReportResolution.java`(`KEPT`,`REMOVED`), `repository/BlockRepository.java`, `repository/ReportRepository.java`, `service/ModerationService.java`, `service/ReportNotifier.java`, `service/DiscordReportNotifier.java`, `controller/ModerationController.java`, `dto/community/ReportRequest.java`, `MyReportDto.java`
- Modify: `PostRepository`·`CommentRepository` 조회(내가 차단한 작성자 빼기), `PostService.user`(`blockedByMe`, 차단한 사람의 글 목록은 빈 목록)
- Test: `community/ModerationApiTest.java`

**Interfaces:**
- Consumes: Task 3·4 의 글·댓글, Task 2 프로필.
- Produces:
  - 테이블 `BLOCKS`(`blocker_id`, `blocked_id`, `created_at`, unique 쌍), `REPORTS`(`id`, `reporter_id`, `target_type`, `target_id` 문자열 — 글·댓글 id 또는 publicId, `reason`, `created_at`, `resolved_at`, `resolution`, unique(`reporter_id`,`target_type`,`target_id`))
  - `ModerationService`: `void block(String loginId, String publicId)`(자기 자신 400, 멱등), `void unblock(...)`, `List<ProfileDto> blocks(String loginId)`, `void report(String loginId, ReportRequest req)`(대상 없음 404, 내 글·댓글·나 자신 400, 중복 409, 글·댓글은 서로 다른 신고자 3명이면 `hidden_at` + `hidden_reason="신고 누적"`, 저장 뒤 알림), `List<MyReportDto> myReports(String loginId)`
  - `interface ReportNotifier { void reportFiled(ReportTargetType type, String targetId, ReportReason reason, long reportCount, boolean autoHidden); }` — `DiscordReportNotifier` 는 웹훅이 비면 아무것도 안 하고, 있으면 `RestClient` 로 `{"content": "..."}` 를 요청 스레드 밖에서 보내며 실패는 로그만. 메시지에 `loginId`·실명·이메일·본문을 넣지 않는다(예: `[T-REX 신고] 글 #12 · 욕설·혐오 · 누적 3건 · 자동 숨김`)
  - `record ReportRequest(ReportTargetType targetType, String targetId, ReportReason reason)`, `record MyReportDto(long id, ReportTargetType targetType, String targetId, ReportReason reason, long createdAt, String status, ReportResolution resolution)` — `status` 는 `"PENDING"` 또는 `"RESOLVED"`
  - API: `PUT|DELETE /api/community/blocks/{publicId}` → 204, `GET /api/community/me/blocks`, `POST /api/community/reports` → 201, `GET /api/community/me/reports`

- [ ] **Step 1: 실패하는 테스트 작성** (`ModerationApiTest`, 기록만 하는 `ReportNotifier` 를 `@Primary` 로)
  - `차단하면_피드_댓글_프로필_글에서_사라지고_풀면_돌아온다`
  - `자기_자신_차단과_내_글_신고는_400`
  - `같은_대상을_두_번_신고하면_409`
  - `서로_다른_세_명이_신고하면_자동_숨김_작성자에게만_hidden_true와_사유가_보인다` — `$.hiddenReason` `"신고 누적"`
  - `신고하면_알림이_가고_메시지에_loginId와_이름이_없다`
  - `내_신고_목록은_PENDING으로_보인다`
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.community.ModerationApiTest"` → FAIL
- [ ] **Step 3: 구현** — 위 Interfaces
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + 전체 PASS
- [ ] **Step 5: 커밋** — `서버 커뮤니티: 차단·신고·자동 숨김·디스코드 알림`

### Task 6: 관리자

**Files:**
- Create: `service/AdminService.java`, `controller/AdminController.java`, `dto/community/AdminReportDto.java`, `ResolveRequest.java`
- Test: `community/AdminApiTest.java`

**Interfaces:**
- Consumes: Task 2 `isAdmin`, Task 3·4·5 엔티티.
- Produces:
  - 모든 경로 `/api/community/admin/**`, 관리자가 아니면 403
  - `GET /admin/reports?pendingOnly=true` → `List<AdminReportDto>`, `record AdminReportDto(ReportTargetType targetType, String targetId, String preview, long reportCount, List<ReportReason> reasons, boolean hidden, long firstReportedAt)` — 대상별로 묶는다, `preview` = 글·댓글 본문 앞 80자 또는 닉네임
  - `POST /admin/reports/resolve` `{targetType, targetId, resolution}` → 그 대상의 미처리 신고를 모두 처리(`REMOVED` 면 글·댓글을 지운다, `KEPT` 면 숨김을 푼다)
  - `POST /admin/posts/{id}/hide|restore`, `POST /admin/comments/{id}/hide|restore` → 204 (`hidden_reason="관리자"`)
  - `GET /admin/hidden` → 숨겨진 글 `List<PostDto>`
  - `POST /admin/users/{publicId}/suspend|unsuspend` → 204, `GET /admin/suspended` → `List<ProfileDto>`
  - 관리자는 기존 `DELETE /posts/{id}`·`DELETE /comments/{id}` 도 쓴다(Task 3·4 규칙)

- [ ] **Step 1: 실패하는 테스트 작성** (`AdminApiTest`)
  - `관리자가_아니면_admin_API는_모두_403`
  - `자동_숨김된_글을_KEPT로_처리하면_다시_피드에_보인다`
  - `REMOVED로_처리하면_글이_지워진다`
  - `정지된_사용자는_쓰기_403_읽기는_된다` — 글·댓글·좋아요·신고 403, 피드·상세 200
  - `정지된_사용자의_글은_남에게_안_보이고_본인에게는_보인다`
  - `정지를_풀면_다시_쓸_수_있다`
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.community.AdminApiTest"` → FAIL
- [ ] **Step 3: 구현** — 위 Interfaces
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + 전체 PASS
- [ ] **Step 5: 커밋** — `서버 커뮤니티: 관리자 신고 처리·숨김·정지`

### Task 7: 회원 탈퇴 연동

**Files:**
- Create: `community/CommunityAccountCleaner.java`
- Modify: `service/AuthService.java`(`deleteAccount` 가 `userRepository.delete(user)` 직전에 `communityAccountCleaner.deleteAllFor(user)` 를 부른다)
- Test: `community/CommunityDeletionTest.java`

**Interfaces:**
- Consumes: Task 2~5 저장소, Task 1 `ImageStorage`.
- Produces: `void deleteAllFor(User user)` — 그 사람의 프로필이 없으면 아무것도 안 한다. 있으면: 그 사람이 누른 좋아요, 그 사람 글에 달린 좋아요·댓글, 그 사람이 쓴 댓글, 그 사람이 한 신고와 그 사람의 글·댓글·계정을 대상으로 한 신고, 그 사람이 들어간 차단(양쪽), 글·사진 행, 프로필을 지운다. 사진·프로필 사진 키는 **커밋 뒤** 저장소에서 지우고 실패는 로그만.

- [ ] **Step 1: 실패하는 테스트 작성** (`CommunityDeletionTest`)
  - `탈퇴하면_글_사진_댓글_좋아요_차단_신고가_모두_지워진다` — A 가 사진 글을 쓰고, B 의 글에 댓글·좋아요, C 를 차단, D 의 글을 신고. B 가 A 의 글에 댓글·좋아요. A 탈퇴(`DELETE /api/auth/me` 기존 경로) 뒤: A 의 글 404, A 의 사진 키 `has` false, B 의 글 `commentCount`·`likeCount` 줄어듦, D 의 글 신고 수 0
  - `커뮤니티를_안_쓴_사용자의_탈퇴는_그대로_된다`
  - `사진_삭제가_실패해도_탈퇴는_성공한다`
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.community.CommunityDeletionTest"` → FAIL(외래 키 위반 또는 남은 행)
- [ ] **Step 3: 구현** — 위 Interfaces. 탈퇴 경로는 기존 `SocialSyncApiTest` 의 탈퇴 테스트에서 확인한다.
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + 전체 PASS(`SocialSyncApiTest` 포함)
- [ ] **Step 5: 커밋** — `서버 커뮤니티: 회원 탈퇴 때 커뮤니티 데이터·사진 삭제`

### Task 8: S3 저장소·운영 설정·배포

**Files:**
- Modify: `server/build.gradle`(AWS SDK v2 BOM + `software.amazon.awssdk:s3` — BOM 버전은 Maven Central 에서 확인한 2.29 이상), `storage/ImageStorageConfig.java`(버킷이 있으면 S3), `resources/application-prod.yml`, `deploy/docker-compose.yml`, `deploy/.env.example`, `docs/SERVER_SYNC_HANDOFF.md`(커뮤니티 운영 절)
- Create: `storage/S3ImageStorage.java`
- Test: `storage/S3ImageStorageTest.java`

**Interfaces:**
- Consumes: Task 1 `ImageStorage`, `CommunityProperties`.
- Produces: `S3ImageStorage(S3Client client, S3Presigner presigner, String bucket)` — `put` = `putObject`(Content-Type `image/jpeg`), `delete` = `deleteObject`, `url` = 서명 GET 주소(수명 1시간). 자격 증명은 기본 공급자(EC2 인스턴스 역할) — 키를 `.env` 에 넣지 않는다. `application-prod.yml`: `trex.community.s3-bucket: ${COMMUNITY_S3_BUCKET}`(기본값 없음), `admin-login-ids: ${COMMUNITY_ADMIN_LOGIN_IDS:}`, `report-webhook-url: ${COMMUNITY_REPORT_WEBHOOK_URL:}`. compose 의 `app.environment` 에 세 변수와 `AWS_REGION: ap-southeast-2`.

- [ ] **Step 1: 실패하는 테스트 작성** — `S3ImageStorageTest`: 고정 가짜 자격 증명(`StaticCredentialsProvider`)으로 만든 `S3Presigner` 로 `url("posts/1/0-a.jpg")` 가 버킷 이름·키·`X-Amz-Expires=3600` 을 담는다(네트워크 없이 서명만). 버킷이 빈 테스트 설정에서는 `ImageStorage` 빈이 `InMemoryImageStorage`.
- [ ] **Step 2: 실패 확인** — `./gradlew.bat test --tests "com.trex.server.storage.*"` → FAIL
- [ ] **Step 3: 구현** — 위 Interfaces
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS + 전체 PASS, `./gradlew.bat bootJar` 성공
- [ ] **Step 5: 커밋** — `서버 커뮤니티: S3 사진 저장소·운영 설정`
- [ ] **Step 6: AWS 준비(사용자, 콘솔 — 시드니)** — 런북에 같은 내용을 적는다
  1. S3 버킷 `trex-community-<임의 6자>`: 퍼블릭 액세스 **모두 차단 유지**, 기본 암호화 SSE-S3
  2. IAM 역할(EC2용) `trex-app-s3`: 그 버킷 `/*` 에 `s3:PutObject`·`s3:GetObject`·`s3:DeleteObject` 만 허용
  3. EC2 `trex-server` → 작업 → 보안 → IAM 역할 수정 → `trex-app-s3`
  4. EC2 → 작업 → 인스턴스 설정 → 인스턴스 메타데이터 옵션 수정 → **홉 제한 2**(도커 컨테이너가 역할 자격 증명을 받으려면 필요)
  5. 디스코드 채널 → 연동 → 웹후크 만들기 → 주소 전달(비밀값 — 서버 `.env` 에만)
  6. 사용자의 `loginId`(`google_<sub>`)를 관리자 목록에 — `GET /api/auth/me` 응답에서 확인
- [ ] **Step 7: 운영 배포** — 런북 8절 순서: `bootJar` → EC2 `~/trex/app.jar` 를 `app.jar.prev` 로 보관 → 새 jar·compose 올리기 → `.env` 에 `COMMUNITY_S3_BUCKET`·`COMMUNITY_ADMIN_LOGIN_IDS`·`COMMUNITY_REPORT_WEBHOOK_URL` 추가 → `docker compose up -d` → 확인: 기동 로그 `Started ServerApplication`, `/api/sync` 무인증 401 그대로, 로그인 토큰으로 `GET /api/community/me` 200, 작은 JPEG 로 글 쓰기 → 받은 `images[0].url` 이 200, 글 삭제 → 그 주소 403/404. 실패하면 `app.jar.prev` 로 되돌린다.
- [ ] **Step 8: 커밋** — 런북 갱신이 남았으면 `서버 커뮤니티: 운영 배포 런북`
