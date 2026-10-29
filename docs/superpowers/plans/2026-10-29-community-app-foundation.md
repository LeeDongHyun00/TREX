# 커뮤니티 앱 기반 구현 계획 (계획 2/3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 커뮤니티 화면(계획 3)이 쓸 앱 기반 — 바이너리 HTTP(사진 올리기·받기), 커뮤니티 API 클라이언트, 사진 JPEG 재인코딩, 원격 이미지 표시 — 를 Android·iPhone 공유 코드로 만든다.

**Architecture:** 기존 경계를 그대로 넓힌다. `AppPlatform` 에 `binary: BinaryTransport` 를 더하고(Android HttpURLConnection, iPhone NSURLSession), multipart 본문은 공유 Kotlin 이 바이트로 만든다. 서버 호출은 새 공유 `community/CommunityApi.kt`(TrexApi 와 같은 방식, org.json). 사진은 `FoodPhoto.jpeg(...)` 로 긴 변 1080px JPEG 를 다시 만들어 EXIF 를 떨군다. 원격 이미지는 화면 짝 `BytesImageDecoder` + 공유 LRU + `RemoteImage` 컴포저블.

**Tech Stack:** Kotlin, Jetpack Compose / Compose Multiplatform(ios-ui), org.json(Android) ↔ posture-core 공통 OrgJson(iPhone), HttpURLConnection / NSURLSession, Android Bitmap / Skia(iPhone).

**Spec:** `docs/superpowers/specs/2026-10-28-community-design.md`(§4 코드 제약, §5 동작, §6 서버 초안). 서버 API 의 정본은 계획 1 의 구현(`feature/community-server`, `server/src/main/java/com/trex/server/controller/*`·`dto/community/*`).

작업 위치: 앱(`app/`) — 공유 파일은 `ios-ui/android-sources.txt` 에 올린다. 테스트: `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew.bat :app:testDebugUnitTest`(지금 787개 통과). iPhone 컴파일은 PR CI `shared` 작업(`:ios-ui:compileKotlinIosSimulatorArm64`)이 확인한다 — 이 PC(Windows)에서는 못 돌린다.

## Global Constraints

- 공유 파일(`android-sources.txt` 에 오른 파일)에는 `android.*`·`java.*`·`BuildConfig`·`@Synchronized` 금지. 잠금은 atomicfu `synchronized(lock)`/`locked(lock)`, 시각은 `TrexTime.kt`, 네트워크 실패는 `NetworkException`, HTTP 오류는 `ApiException(status, 서버 message)`.
- 화면 짝(`PlatformApi.kt` 타입 ↔ Android `PlatformUi.kt` ↔ iPhone `ios-ui/.../PlatformUi.kt`)은 같은 이름·같은 시그니처.
- 올리는 사진: **긴 변 1080px**, JPEG **품질 85**, 원본 픽셀에서 다시 만든다(EXIF·위치 정보가 따라가지 않는다). 서버 한도는 장당 2MB·글당 5장.
- 원격 사진 주소는 S3 서명 주소(수명 1시간)라 **주소가 아니라 안정 키**(`post:<글 id>:<순서>`, `avatar:<publicId>`)로 캐시한다. 인증 헤더를 붙이지 않는다(서명 주소에 이미 권한이 있다).
- 주석·사용자 문구는 한국어. 서버 오류 문구(`message`)는 그대로 화면에 쓴다.

## Review Focus

1. 서명 주소가 만료(1시간)된 뒤 같은 사진을 다시 그릴 때 — 캐시에 있으면 네트워크 없이, 없으면 새 주소(피드 새로고침으로 받은)로 받아야 한다. 만료 주소로 받은 403 은 오류 그림이지 앱 죽음이 아니다. → Task 5 `만료된_주소의_403은_실패로_끝난다`
2. 큰 사진(가로 4000px)·세로 사진·EXIF 회전 사진을 올릴 때 긴 변 1080 으로 줄고 방향이 맞는다. → Task 4 `긴_변_1080으로_맞춘다`(크기 계산), 회전은 기존 로더가 이미 반영(실기기 확인)
3. 네트워크가 끊긴 채 글쓰기 → `NetworkException` 으로 끝나고 입력이 사라지지 않는다(화면은 계획 3). → Task 3 `연결_실패는_NetworkException`
4. 서버가 413·429·403(정지·이용규칙 전)을 주면 서버 문구가 그대로 올라온다. → Task 3 `서버_오류_문구를_그대로_올린다`
5. 같은 사진을 여러 카드가 동시에 그릴 때 한 번만 받는다. → Task 5 `같은_키는_한_번만_받는다`

---

### Task 1: 단위 테스트에서 진짜 org.json 쓰기

**Files:** Modify `app/build.gradle.kts`(+ `gradle/libs.versions.toml` 에 `org-json` 항목)

**Interfaces:** Produces: 단위 테스트 classpath 의 `org.json.JSONObject` 가 Android 스텁이 아니라 실제 구현(예: `org.json:json:20240303` — Maven Central 에서 확인).

- [ ] **Step 1: 실패하는 테스트** — `app/src/test/java/com/example/trex_kotlin/community/OrgJsonInTestsTest.kt`: `JSONObject("{\"a\":1}").getInt("a") == 1`
- [ ] **Step 2: 실패 확인** — `./gradlew.bat :app:testDebugUnitTest --tests "*OrgJsonInTestsTest"` → FAIL(스텁: "not mocked")
- [ ] **Step 3: `testImplementation(libs.org.json)` 추가**
- [ ] **Step 4: 통과 + 전체 787 통과 확인**(스텁에 기대던 테스트가 있으면 여기서 드러난다)
- [ ] **Step 5: 커밋** — `테스트: 단위 테스트에 실제 org.json(커뮤니티 API 파싱 검증용)` — CLAUDE.md 의 "org.json 은 스텁" 문장도 고친다

### Task 2: 바이너리 전송과 multipart

**Files:**
- Create: `app/.../sync/BinaryTransport.kt`(공유), `app/.../sync/Multipart.kt`(공유)
- Modify: `AppPlatform.kt`(`val binary: BinaryTransport`), `AndroidPlatform.kt`(`HttpUrlConnectionBinaryTransport`), `ios-ui/.../ios/UrlSessionTransport.kt`(`UrlSessionBinaryTransport`), `ios-ui/.../ios/IosPlatform.kt`, `ios-ui/android-sources.txt`
- Test: `app/src/test/.../sync/MultipartTest.kt`

**Interfaces:**
- Produces:
  - `class BinaryResponse(val status: Int, val body: ByteArray)`
  - `fun interface BinaryTransport { suspend fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): BinaryResponse }` — 연결·읽기 실패는 `NetworkException`, 4xx·5xx 는 응답. 시간 제한: 연결 10 s, 읽기 30 s(사진).
  - `object Multipart { fun boundary(): String; fun body(boundary: String, parts: List<Part>): ByteArray; fun contentType(boundary: String): String }`, `sealed interface Part { data class Text(name: String, value: String); data class File(name: String, fileName: String, contentType: String, bytes: ByteArray) }` — CRLF, `Content-Disposition: form-data; name="…"(; filename="…")`, 텍스트는 UTF-8.

- [ ] **Step 1: 실패하는 테스트** — `MultipartTest`: 텍스트 `body=오늘 오운완` 과 파일 두 개를 넣은 본문을 문자열(ISO-8859-1)로 풀면 경계·헤더·순서·끝 경계(`--b--\r\n`)가 정확하다, 파일 바이트는 손대지 않고 그대로 들어간다, 한글 값은 UTF-8 바이트.
- [ ] **Step 2: 실패 확인** — `--tests "*MultipartTest"` → 컴파일 실패
- [ ] **Step 3: 구현** — 위 Interfaces. Android·iPhone 전송은 기존 `HttpUrlConnectionTransport`·`UrlSessionTransport` 를 본뜬다(바이트 그대로).
- [ ] **Step 4: 통과 + 전체 통과**
- [ ] **Step 5: 커밋** — `앱 기반: 바이너리 전송(두 플랫폼)·multipart 본문`

### Task 3: 커뮤니티 API 클라이언트

**Files:** Create `app/.../community/CommunityApi.kt`, `app/.../community/CommunityModels.kt`(공유, android-sources 에) · Test `app/src/test/.../community/CommunityApiTest.kt`

**Interfaces:**
- Consumes: Task 2 `BinaryTransport`·`Multipart`, 기존 `HttpTransport`·`ApiException`·`NetworkException`.
- Produces (서버 DTO 와 같은 이름·의미, `createdAt` 은 epoch ms):
  - `data class CommunityProfile(publicId: String, nickname: String, avatarUrl: String?)`, `data class CommunityMe(profile: CommunityProfile?, termsAccepted: Boolean, admin: Boolean, suspended: Boolean)`
  - `data class PostImage(url: String, width: Int, height: Int)`, `data class Post(id: Long, author: CommunityProfile, body: String?, images: List<PostImage>, likeCount: Long, likedByMe: Boolean, commentCount: Long, createdAt: Long, hidden: Boolean, hiddenReason: String?, mine: Boolean)`, `data class PostPage(items: List<Post>, nextBefore: Long?)`
  - `data class Comment(id, author, body: String, createdAt, hidden, hiddenReason: String?, mine)`, `data class CommentPage(items, nextBefore: Long?)`, `data class UserProfile(profile, postCount: Long, blockedByMe: Boolean)`
  - `enum class ReportTarget { POST, COMMENT, USER }`, `enum class ReportReason(label: String) { SPAM("스팸·광고"), ABUSE("욕설·혐오"), SEXUAL_VIOLENT("음란·폭력"), PRIVACY("개인정보 노출"), RIGHTS("권리 침해"), OTHER("기타") }`, `data class MyReport(id, target: ReportTarget, targetId: String, reason: ReportReason, createdAt: Long, resolved: Boolean, resolution: String?)`
  - 관리자: `data class AdminReport(target, targetId, preview, reportCount: Long, reasons: List<ReportReason>, hidden: Boolean, firstReportedAt: Long)`
  - `class CommunityApi(baseUrl: String, http: HttpTransport, binary: BinaryTransport, token: () -> String?)` 메서드(전부 `suspend`): `me()`, `setNickname(nickname)`, `acceptTerms(version = TERMS_VERSION)`, `setAvatar(jpeg: ByteArray)`, `clearAvatar()`, `feed(before: Long?)`, `post(id)`, `createPost(body: String?, images: List<ByteArray>)`, `deletePost(id)`, `like(id)`, `unlike(id)`, `comments(postId, before: Long?)`, `addComment(postId, body)`, `deleteComment(id)`, `user(publicId)`, `userPosts(publicId, before)`, `block(publicId)`, `unblock(publicId)`, `blocks()`, `report(target, targetId, reason)`, `myReports()`, 관리자 `adminReports()`, `resolve(target, targetId, kept: Boolean)`, `hidePost(id)`, `restorePost(id)`, `suspend(publicId)`, `unsuspend(publicId)`, `suspended()`, `hiddenPosts()`; `companion { const val TERMS_VERSION = "2026-10-28"; const val TERMS_URL = "https://leedonghyun00.github.io/TREX/terms.html" }`
  - 사진·글쓰기·프로필 사진은 `binary`(multipart, 파일 이름 `photo<i>.jpg`, `image/jpeg`), 나머지는 `http`(JSON). 오류 처리는 `TrexApi.call` 과 같다.

- [ ] **Step 1: 실패하는 테스트** (`CommunityApiTest`, 요청을 기록하고 정해 둔 JSON 을 돌려주는 가짜 전송 두 개)
  - `피드는_before를_쿼리로_보내고_글을_읽는다` — `GET …/api/community/posts?before=12`, 응답의 `nextBefore` null·`hiddenReason` null 을 Kotlin null 로
  - `글쓰기는_multipart로_본문과_사진을_보낸다` — `POST …/posts`, `Content-Type: multipart/form-data; boundary=…`, `Authorization: Bearer t`, 본문에 `name="body"` 와 `name="images"; filename="photo0.jpg"` 두 장
  - `서버_오류_문구를_그대로_올린다` — 429 `{"message":"잠시 뒤에 다시 올려 주세요"}` → `ApiException(429, 그 문구)`
  - `연결_실패는_NetworkException`
  - `신고는_대상과_사유를_JSON으로` — `{"targetType":"POST","targetId":"5","reason":"ABUSE"}`
- [ ] **Step 2: 실패 확인** · **Step 3: 구현** · **Step 4: 통과 + 전체** · **Step 5: 커밋** — `앱 기반: 커뮤니티 API 클라이언트`

### Task 4: 올릴 사진 JPEG 재인코딩

**Files:** Modify `PhotoFoodApi.kt`(`FoodPhoto.jpeg`), `PhotoFoodAndroid.kt`, `ios-ui/.../PhotoFoodIos.kt`, 테스트의 가짜 `FoodPhoto`(`FoodPipelineTest.FakePhoto` 등) · Create `app/.../community/UploadSizing.kt`(공유) · Test `app/src/test/.../community/UploadSizingTest.kt`

**Interfaces:**
- Produces: `FoodPhoto.jpeg(maxDimension: Int = 1080, quality: Int = 85): ByteArray`; `object UploadSizing { fun fit(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> }` — 긴 변이 넘으면 비율 유지로 줄이고(반올림, 최소 1), 작으면 그대로.
- Android: `UploadSizing.fit` 으로 `Bitmap.createScaledBitmap` → `compress(JPEG, quality)`. iPhone: 사진의 RGBA 로 Skia 래스터 이미지를 만들어 크기를 맞춘 뒤 `encodeToData(EncodedImageFormat.JPEG, quality)`. 둘 다 픽셀에서 새로 만드니 EXIF 가 없다.
- 이름은 `FoodPhoto` 그대로 둔다(사진 고르기·찍기 경계를 커뮤니티가 같이 쓴다 — 이름 바꾸기는 계획 밖).

- [ ] **Step 1: 실패하는 테스트** — `UploadSizingTest.긴_변_1080으로_맞춘다`: (4000,3000)→(1080,810), (3000,4000)→(810,1080), (800,600)→(800,600), (1081,1)→(1080,1)
- [ ] **Step 2~4** — 실패 확인·구현·통과(가짜 사진은 고정 바이트를 돌려준다)
- [ ] **Step 5: 커밋** — `앱 기반: 올릴 사진 긴 변 1080 JPEG 재인코딩(두 플랫폼)` — 실기기 확인(세로 사진·큰 사진)은 계획 3 의 글쓰기 화면에서

### Task 5: 원격 이미지 — 디코더 짝, LRU, 내려받기, `RemoteImage`

**Files:**
- Modify: `PlatformApi.kt`(`fun interface BytesImageDecoder { fun decode(bytes: ByteArray, maxDimension: Int): ImageBitmap? }`, 기존 `AssetImageCache` 를 제네릭 `LruCache<K, V>` 로 일반화), Android `PlatformUi.kt`·iPhone `PlatformUi.kt`(`@Composable fun rememberBytesImageDecoder(): BytesImageDecoder`)
- Create: `app/.../community/RemoteImages.kt`(공유 — `class RemoteImageLoader(binary: BinaryTransport, decoder: BytesImageDecoder, maxEntries: Int = 40)` + `@Composable fun RemoteImage(key: String, url: String, contentDescription: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop)`)
- Test: `app/src/test/.../community/RemoteImageLoaderTest.kt`

**Interfaces:**
- Produces: `suspend fun RemoteImageLoader.load(key: String, url: String, maxDimension: Int): ImageBitmap?` — 캐시에 있으면 그대로, 없으면 `GET`(인증 없음) → 200 이면 디코딩해 캐시, 그 밖(403 만료 등)·디코딩 실패는 null. 같은 키를 동시에 부르면 한 번만 받는다(진행 중 요청 공유). `RemoteImage` 는 받는 동안 회색 자리, 실패하면 "사진을 불러오지 못했어요" 자리.
- Android 디코더는 `BitmapFactory`(inSampleSize), iPhone 은 Skia `Image.makeFromEncoded`.

- [ ] **Step 1: 실패하는 테스트** (`RemoteImageLoaderTest`, 가짜 전송·디코더 — 디코더는 크기만 담은 가짜 그림 대신 호출 횟수를 센다)
  - `캐시에_있으면_다시_받지_않는다`
  - `같은_키는_한_번만_받는다` — 동시에 두 번 `load` → 전송 1회
  - `만료된_주소의_403은_실패로_끝난다` — null, 캐시에 남지 않음, 다음 `load`(새 주소)는 다시 받는다
  - `LruCache_넘치면_가장_오래_안_쓴_것부터` — 기존 `AssetImageCache` 동작과 같다
- [ ] **Step 2~4** · **Step 5: 커밋** — `앱 기반: 원격 이미지(디코더 짝·LRU·RemoteImage)`

### 마무리
- [ ] PR(대상 `main`) — CI `shared` 로 iPhone 컴파일 확인. 화면이 없으니 실기기 확인은 계획 3 에서 한다.
