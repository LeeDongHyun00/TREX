package com.example.trex_kotlin

import com.example.trex_kotlin.community.CommunityApi
import com.example.trex_kotlin.community.CommunityGate
import com.example.trex_kotlin.community.CommunityMe
import com.example.trex_kotlin.community.FeedController
import com.example.trex_kotlin.community.WorkoutCardData
import com.example.trex_kotlin.community.communityGate
import com.example.trex_kotlin.routines.AppLink
import com.example.trex_kotlin.routines.SavedRoutine
import com.example.trex_kotlin.routines.SharedRoutine
import com.example.trex_kotlin.routines.toTodayPlan
import com.example.trex_kotlin.routines.withAdded
import com.example.trex_kotlin.routines.without
import com.example.trex_kotlin.routines.toWorkouts
import com.example.trex_kotlin.trainingload.Muscle
import com.example.trex_kotlin.trainingload.MuscleLoadCatalog
import kotlinx.datetime.LocalDate
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.AppleCredential
import com.example.trex_kotlin.sync.AuthAccount
import com.example.trex_kotlin.sync.AuthSession
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.FirstSync
import com.example.trex_kotlin.sync.NetworkException
import com.example.trex_kotlin.sync.SignInException
import com.example.trex_kotlin.sync.SignupForm
import com.example.trex_kotlin.sync.SyncManager
import com.example.trex_kotlin.sync.TrexApi
import com.example.trex_kotlin.sync.signupProblem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import com.example.trex_kotlin.posture.FormLabel
import com.example.trex_kotlin.posture.PostureSetReport
import com.example.trex_kotlin.posture.SetSelfLabel
import androidx.lifecycle.viewModelScope
import com.example.trex_kotlin.weather.WeatherLocationPrefs
import com.example.trex_kotlin.weather.WeatherRepository
import com.example.trex_kotlin.weather.WeatherServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.trex_kotlin.trainingload.*

/**
 * 앱 서비스 상태의 단일 소유자.
 *
 * 화면(컴포저블)은 상태를 소유하지 않고 여기서 읽고 액션 메서드로만 바꾼다.
 * 모든 변경은 [TrexStore] 로 즉시 영속화된다 — 탭 전환/회전/재시작에도 기록이 유지된다.
 * 플랫폼 기능(저장소·HTTP·근육 장부·라벨 파일)은 [AppPlatform] 으로 받는다(§108) — 같은 소스가 iPhone 에서도 돈다.
 */
class AppViewModel(private val platform: AppPlatform) : ViewModel() {

    private val store = TrexStore(platform.preferences("trex_store"))

    // ---- 진행 플래그

    var guideDone by mutableStateOf(store.guideDone)
        private set
    var loggedIn by mutableStateOf(store.loggedIn)
        private set
    var onboarded by mutableStateOf(store.onboarded)
        private set

    fun completeGuide() {
        guideDone = true
        store.guideDone = true
    }

    fun completeLogin() {
        loggedIn = true
        store.loggedIn = true
    }

    fun completeOnboarding(profile: UserProfile) {
        this.profile = profile
        store.saveProfile(profile)
        onboarded = true
        store.onboarded = true
    }

    /** 앱에서 나간다. 서버 계정도 이 기기에서 로그아웃한다. 이 기기의 기록은 그대로 둔다. */
    fun logout() {
        loggedIn = false
        store.loggedIn = false
        authSession.clear()
        account = null
    }

    // ---- 계정·동기화 (docs/SERVER_SYNC_HANDOFF.md 2단계)

    private val authSession = AuthSession(platform.preferences("trex_auth"))
    private val api = TrexApi(platform.apiBaseUrl, platform.http) { authSession.token }

    /** 서버 계정. null 이면 "로그인 없이 사용" — 기록은 이 기기에만 있다. */
    var account by mutableStateOf(authSession.current())
        private set
    var authBusy by mutableStateOf(false)
        private set
    /**
     * 계정 선택을 마치고 서버 로그인·첫 동기화를 기다리는 중 — 앱 전체에 로그인 로딩 화면([SignInLoadingScreen])을 덮는다.
     * 계정 선택 창(구글·Apple)이 떠 있는 동안은 거짓이다 — 창을 닫았을 때 로딩 화면이 깜빡이지 않게. 탈퇴처럼 [authBusy] 만 켜는 일에는 덮지 않는다.
     */
    var authVerifying by mutableStateOf(false)
        private set
    var authMessage by mutableStateOf<String?>(null)
        private set
    /** 처음 연결한 계정의 서버에도 이 기기에도 기록이 있다 — 어느 쪽을 남길지 묻는 중. */
    var askFirstSync by mutableStateOf(false)
        private set
    /** 이 기기에 다른 계정으로 쓰던 기록이 있다 — 새 계정으로 옮길지 묻는 중(묻지 않고 올리면 남의 기록이 섞인다). */
    var askSwitchAccount by mutableStateOf(false)
        private set
    private var refreshJob: kotlinx.coroutines.Job? = null

    val sync = SyncManager(
        platform.preferences("trex_sync"), store, authSession, api, viewModelScope,
        onRemoteApplied = { reloadFromStore() },
        onSessionExpired = {
            account = null
            authMessage = "로그인이 만료됐어요. 다시 로그인하면 동기화가 이어져요."
        },
    )

    fun clearAuthMessage() { authMessage = null }

    /**
     * "로그인 없이 사용". 나중에 설정에서 계정을 연결할 수 있다. 시작 화면의 로그인 안내("다른 계정 연결을 취소했어요" 등)는
     * 여기서 지운다 — 남겨 두면 설정·커뮤니티의 계정 칸에 그대로 보였다(10-29 실기기).
     */
    fun continueWithoutAccount() {
        authMessage = null
        completeLogin()
    }

    /**
     * "Google로 계속하기" — 로그인 화면과 설정의 계정 연결이 같이 쓴다. [requestIdToken] 은 플랫폼의 계정 선택 화면이다
     * (웹 클라이언트 ID → 구글 ID 토큰, 실패는 [SignInException]). Android 는 Credential Manager(GoogleSignIn.kt).
     */
    fun continueWithGoogle(requestIdToken: suspend (webClientId: String) -> String) {
        if (authBusy) return
        if (platform.googleWebClientId.isBlank() || !api.configured) {
            authMessage = "구글 로그인이 아직 준비되지 않았어요. ‘로그인 없이 사용’으로 시작해 주세요."
            return
        }
        signIn("구글", credential = { requestIdToken(platform.googleWebClientId) }) { api.googleLogin(it) }
    }

    /** Apple 로그인을 쓸 수 있는가 — 버튼을 그릴지(iPhone 은 기본 로그인 창, Android 는 웹 로그인이 설정된 빌드). */
    val appleSignInAvailable: Boolean get() = platform.appleSignInAvailable && api.configured

    /**
     * "Apple로 계속하기"(iPhone·Android, 설계 docs/superpowers/specs/2026-10-29-apple-sign-in-design.md) — 구글과 같은 길로 서버 로그인·
     * 첫 동기화를 한다. [requestCredential] 은 플랫폼의 Apple 로그인 창이다(iPhone 기본 창, Android 웹 로그인 — 취소는 [SignInException.Cancelled]).
     */
    fun continueWithApple(requestCredential: suspend () -> AppleCredential) {
        if (authBusy) return
        if (!appleSignInAvailable) {
            authMessage = "Apple 로그인이 아직 준비되지 않았어요."
            return
        }
        signIn("Apple", credential = requestCredential) { api.appleLogin(it) }
    }

    /**
     * 아이디·비밀번호 로그인(docs/SERVER_SYNC_HANDOFF.md D13) — 소셜 로그인과 같은 길로 계정을 저장하고 첫 동기화를 한다.
     * 플랫폼 창이 없어 바로 서버 확인으로 들어가고, 그동안 로그인 로딩 화면이 덮인다([authVerifying]).
     * 틀린 비밀번호·5번 연속 실패 잠금은 서버 문구를 그대로 보인다.
     */
    fun loginWithPassword(loginId: String, password: String) {
        if (authBusy) return
        if (!api.configured) {
            authMessage = "서버가 아직 준비되지 않았어요. ‘로그인 없이 사용’으로 시작해 주세요."
            return
        }
        signIn("아이디", credential = {}) { api.passwordLogin(loginId, password) }
    }

    /** 아이디·비밀번호 가입(D13) — 가입하면 바로 로그인된다. 칸 확인은 화면이 [signupProblem] 으로 먼저 하고, 서버도 같은 규칙으로 본다. */
    fun signUpWithPassword(form: SignupForm) {
        if (authBusy) return
        if (!api.configured) {
            authMessage = "서버가 아직 준비되지 않았어요. ‘로그인 없이 사용’으로 시작해 주세요."
            return
        }
        signupProblem(form)?.let { authMessage = it; return }
        signIn("아이디", credential = {}) { api.signup(form) }
    }

    /**
     * 로그인 공통 — [credential](플랫폼의 계정 선택 창 — 아이디·비밀번호는 창이 없다)로 받은 증명을 [exchange] 로 서버 계정으로 바꿔 저장하고
     * 첫 동기화를 시작한다. 증명을 받은 뒤부터 끝날 때까지 [authVerifying]. [label] 은 실패 문구의 이름("구글"·"Apple"·"아이디").
     */
    private fun <T> signIn(label: String, credential: suspend () -> T, exchange: suspend (T) -> AuthAccount) {
        viewModelScope.launch {
            authBusy = true
            authMessage = null
            try {
                val proof = credential()
                authVerifying = true
                val signedIn = exchange(proof)
                authSession.save(signedIn)
                account = signedIn
                beginSync(signedIn)
            } catch (_: SignInException.Cancelled) {
                // 사용자가 계정 선택을 닫았다 — 안내하지 않는다
            } catch (_: SignInException.NoAccount) {
                authMessage = "이 기기에 구글 계정이 없어요. 휴대폰 설정에서 계정을 추가해 주세요."
            } catch (e: SignInException.Failed) {
                // 콘솔 설정 오류(SHA-1·패키지명·테스트 사용자, Apple 기능 미설정)는 여기로 온다 — 로그는 플랫폼 쪽이 남긴다
                authMessage = "$label 로그인에 실패했어요. ${e.reason}"
            } catch (e: ApiException) {
                authMessage = e.message
            } catch (_: NetworkException) {
                authMessage = "서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                authMessage = e.message ?: "로그인하지 못했어요."
            } finally {
                authBusy = false
                authVerifying = false
            }
        }
    }

    /** 로그인(또는 앱 시작) 직후. 이 계정과 처음이면 올릴지·받을지 정하고, 양쪽에 다 있으면 묻는다. */
    private suspend fun beginSync(signedIn: AuthAccount) {
        when (sync.beginAccount(signedIn.loginId, localHasData = store.loadProfile() != null)) {
            FirstSync.ASK -> askFirstSync = true
            FirstSync.SWITCH_ACCOUNT -> askSwitchAccount = true
            else -> completeLogin()
        }
    }

    /** 첫 연결 질문(또는 계정 바꿈 질문)의 답. [keepLocal] 이면 이 기기 기록으로 서버를 덮는다. */
    fun resolveFirstSync(keepLocal: Boolean) {
        viewModelScope.launch {
            askFirstSync = false
            askSwitchAccount = false
            sync.resolveFirst(keepLocal)
            completeLogin()
        }
    }

    /** 다른 계정의 기록을 새 계정으로 옮기지 않기로 했다 — 새 계정 연결을 취소한다. 이 기기 기록은 그대로다. */
    fun cancelAccountSwitch() {
        askSwitchAccount = false
        sync.cancelFirst()
        authSession.clear()
        account = null
        authMessage = "다른 계정 연결을 취소했어요. 이 기기 기록은 그대로예요."
    }

    /**
     * 계정이 있으면 서버와 맞춘다. 첫 연결을 못 마친 계정(로그인 직후 받기가 실패 등)이면 첫 연결부터 다시 한다 —
     * 예전에는 화면에 "연결됨"으로 보이면서 동기화가 조용히 아무것도 안 했다(코드 리뷰 지적).
     */
    private fun refreshAccountSync() {
        val signedIn = account ?: return
        if (askFirstSync || askSwitchAccount || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                if (sync.isOwner(signedIn.loginId)) sync.syncNow() else beginSync(signedIn)
            } catch (e: ApiException) {
                if (e.status == 401) {
                    authSession.clear()
                    account = null
                    authMessage = "로그인이 만료됐어요. 다시 로그인하면 동기화가 이어져요."
                } else {
                    authMessage = e.message
                }
            } catch (_: NetworkException) {
                authMessage = "서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요."
            } catch (_: org.json.JSONException) {
                authMessage = "서버 응답을 읽지 못했어요."
            }
        }
    }

    fun syncNow() {
        authMessage = null
        refreshAccountSync()
    }

    /**
     * 앱이 다시 앞으로 왔다 — 다른 기기에서 바뀐 기록을 편집하기 전에 받아 둔다. 오래 켜 둔 화면에서 고친 항목이
     * 다른 기기의 변경을 통째로 덮는 일을 줄인다(항목 단위 "나중 수정 우선"의 한계, 코드 리뷰 지적).
     * 운동 세션 중에는 받지 않는다 — 계획이 바뀌면 진행 중인 세션의 단계가 어긋난다.
     */
    fun onAppForeground(sessionActive: Boolean) {
        if (sessionActive) return
        if (nowMillis() - sync.lastPullAt < 15_000) return
        refreshAccountSync()
    }

    // ---- 커뮤니티 (docs/superpowers/specs/2026-10-28-community-design.md — 서버 계정이 있을 때만)

    /** 커뮤니티 서버 API — 계정 토큰을 같이 쓴다. 글·댓글 목록은 화면 상태라 여기 두지 않고 화면이 바로 부른다. */
    val community = CommunityApi(platform.apiBaseUrl, platform.http, platform.binary) { authSession.token }

    /** 커뮤니티 사진을 받는 전송 — 앱 루트의 원격 사진 로더가 쓴다(서명 주소라 인증 헤더 없음). */
    val imageTransport: BinaryTransport get() = platform.binary

    /** 서버의 내 커뮤니티 상태와 그것을 받은 계정 — 로그아웃·계정 전환 뒤 남의 상태로 들어가지 않게 계정을 같이 둔다. */
    private var communityMeState by mutableStateOf<Pair<String, CommunityMe>?>(null)
    var communityMessage by mutableStateOf<String?>(null)
        private set

    val communityMe: CommunityMe?
        get() = communityMeState?.takeIf { it.first == account?.loginId }?.second

    val communityGate: CommunityGate
        // 서버만 있으면 아이디로 가입할 수 있다(D13) — 구글 설정이 빠진 iPhone 빌드에서도 Apple·아이디 회원이 들어온다
        get() = communityGate(api.configured, account != null, communityMe)

    /** 커뮤니티 탭에 들어올 때·닉네임 정하기·동의 뒤에 부른다. 실패하면 [communityMessage] 에 문구를 둔다. */
    suspend fun refreshCommunityMe() {
        val signedIn = account ?: return
        try {
            communityMeState = signedIn.loginId to community.me()
            communityMessage = null
        } catch (e: ApiException) {
            if (e.status == 401) {
                authSession.clear()
                account = null
                authMessage = "로그인이 만료됐어요. 다시 로그인하면 동기화가 이어져요."
            } else {
                communityMessage = e.message
            }
        } catch (_: NetworkException) {
            communityMessage = "네트워크에 연결하지 못했어요"
        }
    }

    fun clearCommunityMessage() { communityMessage = null }

    /** 전체 피드 — 탭을 오가도 남는다. 계정이 바뀌면 비운다(좋아요 표시가 앞 사람 것이 되지 않게). */
    val communityFeed = FeedController(
        load = { before -> community.feed(before) },
        setLike = { id, liked -> if (liked) community.like(id) else community.unlike(id) },
        scope = viewModelScope,
        now = ::nowMillis,
    )
    private var communityFeedOwner: String? = null

    /** 운동 완료 화면에서 "오운완 올리기"를 눌렀다 — 커뮤니티 탭이 이 카드를 넣은 글쓰기를 열고 비운다. */
    var pendingWorkoutCard by mutableStateOf<WorkoutCardData?>(null)

    // ---- 루틴 공유·내 루틴 보관함(docs/superpowers/specs/2026-10-29-routine-share-design.md)

    /** 링크로 들어온 곳 — 앱 화면(RootRoute.Main)에 닿으면 [TrexApp] 이 받은 루틴 창이나 게시글로 연다(시작 화면·온보딩·운동 중엔 기다린다). */
    var pendingLink by mutableStateOf<AppLink?>(null)

    /** 링크로 받은 게시글 — 커뮤니티 탭이 이용 준비를 마친 뒤 상세를 열고 비운다. */
    var pendingPostId by mutableStateOf<Long?>(null)

    var savedRoutines by mutableStateOf(store.loadRoutines())
        private set

    /** 보관함에 넣는다 — 성공이면 null, 가득 찼으면 그 문구. [from] 은 받은 루틴의 보낸 사람 닉네임. */
    fun saveRoutine(name: String, workouts: List<Workout>, from: String?): String? {
        val now = nowMillis()
        val next = savedRoutines.withAdded(SavedRoutine("routine-$now", name.trim(), workouts, from, now))
            ?: return "보관함이 가득 찼어요. 안 쓰는 루틴을 지워 주세요"
        savedRoutines = next
        store.saveRoutines(next)
        return null
    }

    fun deleteRoutine(id: String) {
        savedRoutines = savedRoutines.without(id)
        store.saveRoutines(savedRoutines)
    }

    /** 오늘 루틴을 [workouts] 로 바꾼다(확인은 화면이 먼저 받는다) — 새 id, 완료 표시 없이. */
    fun applyRoutine(name: String, workouts: List<Workout>) {
        updatePlan(SavedRoutine("apply", name, workouts, null, 0L).toTodayPlan(nowMillis()))
    }

    /** 서버에 올리고 메신저에 붙일 링크를 받는다. 예상 시간은 [routineOverview] 로 계산해 같이 보낸다. */
    suspend fun shareRoutine(name: String, workouts: List<Workout>): String =
        api.shareRoutine(name, routineOverview(workouts).totalSeconds, workouts).also { noteRoutineShare() }

    suspend fun loadSharedRoutine(code: String): SharedRoutine = api.sharedRoutine(code)

    fun postShareUrl(postId: Long): String = api.postShareUrl(postId)

    /** 피드를 보이기 전 — 계정이 바뀌었으면 비우고, 받은 적이 없거나 사진 주소가 곧 만료되면 새로 받는다. */
    suspend fun refreshFeedIfNeeded() {
        val loginId = account?.loginId ?: return
        if (communityFeedOwner != loginId) {
            communityFeed.reset()
            communityFeedOwner = loginId
        }
        if (communityFeed.state.isStale(nowMillis())) communityFeed.refresh()
    }

    /** 회원 탈퇴 — 서버의 계정과 백업을 지운다. 이 기기의 기록은 남는다. */
    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            authBusy = true
            try {
                api.deleteAccount()
                authSession.clear()
                sync.forgetAccount()
                account = null
                onDone()
            } catch (e: ApiException) {
                authMessage = e.message
            } catch (_: NetworkException) {
                authMessage = "서버에 연결하지 못해 탈퇴하지 못했어요."
            } finally {
                authBusy = false
            }
        }
    }

    /** 서버에서 받은 항목으로 저장소가 바뀌었다 — 화면 상태를 다시 읽는다. */
    private fun reloadFromStore() {
        store.loadProfile()?.let { loaded ->
            profile = loaded
            if (!onboarded) { onboarded = true; store.onboarded = true }
        }
        goalOverride = store.loadGoalOverride()
        workoutPlan = store.loadPlan() ?: todayPlan
        savedRoutines = store.loadRoutines()
        workoutHistory = store.loadHistory() ?: emptyList()
        dietByDay = store.loadDiet() ?: emptyMap()
        waterByDay = store.loadWater() ?: emptyMap()
        customFoods = store.loadCustomFoods() ?: emptyMap()
        bodyWeights = store.loadBodyWeights()
        xpLedger = store.loadXp()
        customExercises = store.loadCustomExercises()
        registerCustomProfiles()
        weeklyRoutine = store.loadWeeklyRoutine()
        retentionDays = store.loadRetentionDays()
        streakPauses = store.loadStreakPauses()
        refreshCalendar(resetPlan = false)
        syncMuscleLoad()
    }

    // ---- 화면 모드 (라이트/다크/시스템)

    var themeMode by mutableStateOf(store.themeMode)
        private set

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        store.themeMode = mode
    }

    /** 설정 화면의 '버전' — 설치된 앱 그대로(§119). 예전에는 "1.0" 이 박혀 있어 스토어 버전과 달랐다. */
    val versionLabel: String get() = appVersionLabel(platform.appVersion, platform.appBuild)

    // ---- 운동 안내를 본 종목 — 세션 첫 준비에서 한 번만 자동으로 띄운다(§108 에서 TrexApp 에서 옮김, 같은 저장소·키)

    private val guidePrefs = platform.preferences("exercise_guides")
    var seenGuides by mutableStateOf(guidePrefs.getStringSet("seen_v1", emptySet()).orEmpty().toSet())
        private set

    fun markGuideSeen(name: String) {
        seenGuides = seenGuides + name
        guidePrefs.edit().putStringSet("seen_v1", seenGuides).apply()
        // 가이드 열람 XP(§131) — 종목마다 한 번
        if (ExerciseGuides.forName(name) != null) awardXp("guide:$name", "가이드 열람 · $name", Rewards.XP_GUIDE)
    }

    // ---- 사용자 프로필 → 권장 영양 목표

    var profile by mutableStateOf(store.loadProfile() ?: UserProfile())
        private set

    val recommendedGoal: Nutrition get() = recommendedNutritionGoal(profile)

    /** 사용자가 직접 수정한 목표. null 이면 프로필 기반 추천값을 쓴다. */
    var goalOverride by mutableStateOf(store.loadGoalOverride())
        private set

    val targetGoal: Nutrition get() = goalOverride ?: recommendedGoal

    fun setTargetGoal(goal: Nutrition) {
        goalOverride = goal.normalizedGoal()
        store.saveGoalOverride(goalOverride)
    }

    fun resetTargetGoalToRecommended() {
        goalOverride = null
        store.saveGoalOverride(null)
    }

    // ---- §131 상태 — init 의 refreshCalendar(보관 기간·주간 루틴·연속 멈춤)보다 먼저 초기화돼야 한다(선언 순서 = 초기화 순서; 뒤에 두면 null 상태로 NPE, 11-07 실기기)
    var retentionDays by mutableStateOf(store.loadRetentionDays())
        private set
    var bodyWeights by mutableStateOf(store.loadBodyWeights())
        private set
    var xpLedger by mutableStateOf(store.loadXp())
        private set
    var lastSessionReward by mutableStateOf<SessionReward?>(null)
    var streakPauses by mutableStateOf(store.loadStreakPauses())
        private set
    var customExercises by mutableStateOf(store.loadCustomExercises())
        private set
    var weeklyRoutine by mutableStateOf(store.loadWeeklyRoutine())
        private set
    val sessionSets: SnapshotStateMap<String, SetEntry> = mutableStateMapOf()
    var notifyPrefs by mutableStateOf(store.loadNotifyPrefs())
        private set

    // ---- 운동 계획 / 기록

    var workoutPlan by mutableStateOf(store.loadPlan() ?: todayPlan)
        private set

    var workoutHistory by mutableStateOf(store.loadHistory() ?: emptyList())
        private set

    private val muscleStore = platform.muscleLoadDatabase()
    private val muscleLock = Mutex()
    private val initialLegacyHistory = workoutHistory.toList()
    var muscleLoad by mutableStateOf(LoadSnapshot())
        private set
    var muscleLoadLoading by mutableStateOf(true)
        private set
    var muscleLoadError by mutableStateOf<String?>(null)
        private set
    private var loadLedger = LoadLedger(emptyList(),emptyMap())
    private val loadCorrections = mutableMapOf<String, Double?>()

    fun refreshMuscleLoad() {
        val now = nowMillis()
        if (loadLedger.sets.any { it.endedAt < workoutRetentionStart(now) }) {
            syncMuscleLoad()
        }
        val ledger=loadLedger
        viewModelScope.launch {
            val next=withContext(Dispatchers.Default) { MuscleLoadEngine.snapshot(ledger.sets,now,ledger.doses,workoutRetentionStart(now)) }
            if(ledger === loadLedger) muscleLoad=next
        }
    }

    fun syncMuscleLoad() {
        val current = workoutHistory.flatMap { it.items }.mapNotNull { it.loadSet }
        val legacy = legacyLoadSets(initialLegacyHistory, profile, nowMillis())
        val corrections = workoutHistory.flatMap { it.items }.mapNotNull { item ->
            item.postureCorrection?.let { pc -> pc.setId?.let { it to pc.actualReps?.toDouble() } }
        }.toMap() + loadCorrections
        viewModelScope.launch {
            muscleLock.withLock {
                muscleLoadLoading = true
                try {
                    loadLedger = withContext(Dispatchers.IO) { muscleStore.sync(current, legacy, nowMillis(), corrections) }
                    // 저장과 계산이 모두 끝나야 완료 화면의 로딩을 해제한다.
                    val ledger = loadLedger
                    muscleLoad = withContext(Dispatchers.Default) {
                        val now = nowMillis()
                        MuscleLoadEngine.snapshot(ledger.sets, now, ledger.doses, workoutRetentionStart(now))
                    }
                    muscleLoadError = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    muscleLoadError = "근육 기록을 불러오지 못했습니다. 다시 시도해 주세요."
                } finally { muscleLoadLoading = false }
            }
        }
    }

    /** 홈 날씨 카드의 저장소·위치 설정(§108e) — 플랫폼 전송·키-값 저장소 위. 저장소 이름은 weather 브랜치와 같다. */
    val weather: WeatherServices by lazy {
        WeatherServices(
            WeatherRepository(platform.http, platform.preferences("trex_weather_cache_prefs")),
            WeatherLocationPrefs(platform.preferences("trex_weather_location_prefs")),
        )
    }

    /** 카탈로그·내 종목을 오늘 루틴에 넣는다(§131 카탈로그·종목 상세). 자세 교정은 지원 종목만 켠다. */
    fun addToPlan(name: String) {
        val t = catalogTemplates().firstOrNull { it.name == name } ?: return
        updatePlan(workoutPlan + Workout(newUuid(), t.name, t.reps, t.duration, t.posture && postureSupported(t.name), t.category))
    }

    fun addMuscleRecommendation(name: String) {
        val t = workoutCatalog.values.flatten().firstOrNull { it.name == name } ?: return
        val w = Workout(newUuid(), t.name, t.reps, t.duration, t.posture, t.category)
        updatePlan(workoutPlan + w)
    }

    var calendarDay by mutableStateOf(todayEpochDay())
        private set

    /** 앱 복귀와 날짜 경계에서도 화면의 조회 기간과 저장 기간을 맞춘다. */
    fun refreshCalendar(today: Long = todayEpochDay(), resetPlan: Boolean = true) {
        calendarDay = today
        val retained = workoutHistory.retainVisibleWorkoutHistory(today, retentionDays)
        if (retained != workoutHistory) { workoutHistory = retained; store.pruneExpiredHistory(today, retentionDays) }
        if (resetPlan && store.planDoneEpochDay != today) {
            if (workoutPlan.any { it.done }) updatePlan(workoutPlan.map { it.copy(done = false) })
            store.planDoneEpochDay = today
            // 날이 바뀌면 그 요일의 주간 루틴을 오늘 루틴으로(§131 P11) — 세션 중에는 오지 않는다(resetPlan 거짓)
            applyWeeklyRoutineForToday()
        }
    }

    init {
        // 어제 완료한 계획은 오늘 다시 처음부터(done 플래그는 하루 단위) + 그 요일의 주간 루틴 — 날짜 경계 처리는 refreshCalendar 한 곳.
        // 예전엔 여기서 planDoneEpochDay 를 먼저 오늘로 두어 콜드 스타트에서는 주간 루틴이 한 번도 적용되지 않았다(PR 65 리뷰).
        refreshCalendar(todayEpochDay())
        syncMuscleLoad()
    }

    fun updatePlan(plan: List<Workout>) {
        // 시간 전용 종목(플랭크)은 횟수 목표를 갖지 않는다(spec §108g)
        val normalized = plan.map { it.timeOnlyNormalized() }
        workoutPlan = normalized
        store.savePlan(normalized)
    }

    fun markWorkoutDone(id: String) {
        updatePlan(workoutPlan.map { if (it.id == id) it.copy(done = true) else it })
    }

    fun togglePosture(id: String) {
        updatePlan(
            workoutPlan.map {
                // 규칙 엔진이 지원하는 종목만 자세 교정을 켤 수 있다 (postureExerciseMap)
                if (it.id == id && it.postureSupported()) it.copy(posture = !it.posture) else it
            },
        )
    }

    fun recordCompletedSession(elapsedSeconds: Int, completedPlan: List<Workout> = workoutPlan.filter { it.done }, elapsedByWorkout: Map<String, Int> = emptyMap(),
        sessionId: String = newUuid(), endedAt: Map<String, Long> = emptyMap()) {
        if (completedPlan.isEmpty()) return
        // 리포트 맵은 여기서 비우지 않는다 — 완료 화면이 같은 맵을 읽고, 다음 startSession 이 비운다
        val before = workoutHistory
        // 새 배지 판정은 기록을 합치기 전 상태와 비교한다 — 합친 뒤 재면 '첫 운동' 처럼 이번 세션으로 얻은 배지가 새것이 아니게 된다(11-07 실기기)
        val earnedBefore = badges().filter { it.earned }.map { it.id }.toSet()
        val record = createWorkoutHistoryDay(completedPlan, elapsedSeconds, sessionPostureReports.toMap(), elapsedByWorkout)
        var pr: Pair<String, SetEntry>? = null
        var previousBest: Double? = null
        val items = record.items.mapIndexed { i, item ->
            val w = completedPlan[i]
            val report = sessionPostureReports[w.id]
            val entry = sessionSetEntry(w, report, elapsedByWorkout[w.id] ?: 0)
            if (pr == null && isPersonalRecord(before, w.name, entry)) { pr = w.name to entry; previousBest = before.bestKg(w.name)?.set?.kg }
            item.copy(loadSet = workoutLoadSet(sessionId, w, endedAt[w.id] ?: nowMillis(), elapsedByWorkout[w.id] ?: 0, profile, report),
                sets = listOf(entry), source = "session")
        }
        workoutHistory = workoutHistory.mergeSession(record.copy(items=items), retentionDays)
        store.saveHistory(workoutHistory)
        syncMuscleLoad()
        // 보상(§131 P5) — '마치고 기록한 사실' 에만. 같은 세션을 다시 저장하면(이어하기) 다시 주지 않는다
        val doneSets = completedPlan.count { it.done }
        val xp = Rewards.sessionXp(doneSets, pr != null)
        val awarded = awardXp("session:$sessionId", if (pr != null) "세션 완료 · ${doneSets}세트 · PR" else "세션 완료 · ${doneSets}세트", xp)
        val newBadges = badges().filter { it.earned && it.id !in earnedBefore }
        lastSessionReward = SessionReward(if (awarded) xp else 0, pr, previousBest, newBadges, attendanceStreak(), Rewards.level(totalXp), Rewards.toNextLevel(totalXp),
            volumeKg = items.sumOf { it.sets.sumOf { s -> s.volumeKg ?: 0.0 } })
        sessionSets.clear()
    }

    /**
     * 세션의 한 세트 기록(P3) — 휴식 화면에서 적은 kg·회·RPE·메모([sessionSets]) + 카메라 횟수. 사용자가 적은 횟수가 기록 기준이고,
     * 없으면 카메라 값은 '추정' 으로 남는다. 타이머 세션의 직접 기록 횟수([Workout.reps] 의 수)는 사용자 값이다.
     */
    private fun sessionSetEntry(w: Workout, report: com.example.trex_kotlin.posture.PostureSetReport?, seconds: Int): SetEntry {
        val index = w.id.substringAfterLast("::set:", "1").toIntOrNull() ?: 1
        val typed = sessionSets[w.id]
        val camera = report?.let { r -> r.repsValid?.let { it + (r.repsPartial ?: 0) } }
        val timed = w.resolvedTarget() is WorkoutTarget.Duration
        return SetEntry(index = index, kg = typed?.kg,
            reps = typed?.reps ?: if (camera == null && !timed) w.repsSpec().count else null,
            // 시간 종목은 사용자가 고친 초가 기준, 없으면 잰 시간(PR 65 리뷰 — 고친 45→30초가 기록엔 45로 남았다)
            seconds = if (timed) typed?.seconds ?: seconds else null, cameraReps = camera, rpe = typed?.rpe, memo = typed?.memo, done = w.done)
    }

    val todayRecord: WorkoutHistoryDay?
        get() = workoutHistory.firstOrNull { it.epochDay == todayEpochDay() }

    /**
     * 완료 화면에서 세트 한 줄을 고친다(PR 65 리뷰 — 마지막 세트는 휴식 화면이 없어 kg·RPE·메모를 적을 곳이 없었다).
     * 오늘 기록에서 [workoutName] 의 [SetEntry.index] 가 같은 세트를 바꾸고, 보상 띠의 볼륨·PR 을 다시 잰다(XP 는 그대로 — 세트 수가 바뀌지 않는다).
     */
    fun updateTodaySet(workoutName: String, entry: SetEntry) {
        val today = todayEpochDay()
        val day = workoutHistory.firstOrNull { it.epochDay == today } ?: return
        var changed = false
        val items = day.items.map { item ->
            if (item.workoutName != workoutName || item.sets.none { it.index == entry.index }) item
            else { changed = true; item.copy(sets = item.sets.map { s -> if (s.index == entry.index) entry.copy(cameraReps = s.cameraReps) else s }) }
        }
        if (!changed) return
        workoutHistory = workoutHistory.map { if (it.epochDay == today) it.copy(items = items) else it }
        store.saveHistory(workoutHistory)
        lastSessionReward?.let { r ->
            val before = workoutHistory.filter { it.epochDay != today }
            val pr = r.personalRecord ?: if (isPersonalRecord(before, workoutName, entry)) workoutName to entry else null
            lastSessionReward = r.copy(personalRecord = pr, previousBestKg = r.previousBestKg ?: if (pr != null) before.bestKg(workoutName)?.set?.kg else null,
                volumeKg = items.sumOf { it.sets.sumOf { s -> s.volumeKg ?: 0.0 } })
        }
    }

    // ---- 이번 세션의 자세 리포트 (spec §30) — workoutId → 세트 리포트

    /** 라이브 코칭이 세트를 닫으며 넘긴 리포트. 세션 시작에 비우고, 세션 완료 시 기록으로 접혀 들어간다. */
    val sessionPostureReports: SnapshotStateMap<String, PostureSetReport> = mutableStateMapOf()

    /** [keep] 에 든 운동(이어하기로 이미 마친 것)의 리포트는 남기고 나머지를 비운다. */
    fun clearSessionReports(keep: Collection<String> = emptyList()) {
        val keepSet = keep.toSet()
        sessionPostureReports.keys.filter { it !in keepSet }.forEach { sessionPostureReports.remove(it) }
    }

    /**
     * 리포트는 맵에만 둔다 — 기록 병합은 recordCompletedSession 한 곳에서. ✓ 가 finalize 를 먼저 하므로 마지막 운동도
     * 병합 전에 도착하고, ✕ 중도 이탈 리포트는 여기 남았다가 다음 세션 시작에 지워진다(결정 3: 중도 이탈은 기록하지 않는다).
     */
    fun addPostureReport(workoutId: String, report: PostureSetReport) {
        sessionPostureReports[workoutId] = report
    }

    /**
     * 완료 화면의 자가 라벨 — 기록에서 그 세트(setId) 항목의 actualReps/formLabel 을 갱신하고,
     * 연구용 라벨 파일(labels/set_labels.jsonl / rep_truth.csv)에도 남긴다. exercise 는 리포트의 AIHub 종목명이 정본이고
     * 리포트가 이미 비워졌으면 기록 항목의 운동 이름으로 대신한다. [repsSource] 는 "edited"|"confirmed"|null.
     */
    fun labelPostureSet(setId: String, actualReps: Int?, repsSource: String?, form: FormLabel?) {
        loadCorrections[setId] = actualReps?.toDouble()
        // setId 는 전역 유일 — 자정을 넘겨 저장해도 기록이 어제 날짜에 있을 수 있으니 날짜로 거르지 않는다
        workoutHistory = workoutHistory.map { day ->
            day.copy(
                items = day.items.map { item ->
                    val pc = item.postureCorrection
                    if (pc != null && pc.setId == setId) {
                        item.copy(postureCorrection = pc.copy(actualReps = actualReps, formLabel = form?.key),
                            loadSet=item.loadSet?.copy(actualReps=actualReps?.toDouble()))
                    } else {
                        item
                    }
                },
            )
        }
        store.saveHistory(workoutHistory)

        syncMuscleLoad()

        val report = sessionPostureReports.values.firstOrNull { it.setId == setId }
        val exercise = report?.exercise
            ?: workoutHistory.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.postureCorrection?.setId == setId }?.workoutName
            ?: ""
        // 라벨의 단위 = 그 세트 화면의 횟수 단위(런지류는 좌우 한 쌍, spec §59). 리포트가 이미 비워졌으면 모름(null — 세트 로그의 reps.unit 이 정본)
        val label = SetSelfLabel(setId = setId, exercise = exercise, actualReps = actualReps, repsSource = repsSource, form = form,
            createdAtIso = isoUtcSeconds(), repUnit = report?.repUnit?.key)
        // 파일 IO 는 플랫폼이 메인 스레드 밖에서 한다 — SetLogStore 저장과 같은 이유
        platform.appendSetLabel(label)
    }

    /** 오늘 포함 최근 연속 운동일 — 목표 요일 기준(§131 P6): 계획된 휴식일과 멈춘 날은 건너뛴다. 요일 설정이 없으면 매일이 목표다. */
    fun attendanceStreak(): Int = targetDayStreak(workoutDaySet(), todayEpochDay(), ::isTargetDay, streakPauses)

    fun longestStreak(): Int = longestTargetDayStreak(workoutDaySet(), todayEpochDay(), ::isTargetDay, streakPauses)

    private fun workoutDaySet(): Set<Long> = workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet()

    /** 목표 요일 — 주간 루틴 슬롯이 있으면 그 요일, 없으면 온보딩의 운동 요일([UserProfile.dayMask], 0비트 = 월). */
    fun isTargetDay(day: kotlinx.datetime.DayOfWeek): Boolean =
        if (weeklyRoutine.slots.isNotEmpty()) weeklyRoutine.isTarget(day) else (profile.dayMask shr (day.isoDayNumber - 1)) and 1 == 1

    // ---- §131 기록 확장 — 보관 기간·체중·XP·배지·직접 추가 종목·주간 루틴·세트 입력·수동 기록·내보내기·알림 설정


    /** 기간을 줄이면 그 밖의 기록이 이 기기와 백업에서 지워진다(P14 경고) — 화면이 먼저 내보내기를 권한다. */
    fun chooseRetentionDays(days: Int) {
        retentionDays = days
        store.saveRetentionDays(days)
        refreshCalendar(resetPlan = false)
    }


    val latestBodyWeight: BodyWeightEntry? get() = bodyWeights.maxWithOrNull(compareBy({ it.epochDay }, { it.recordedAt }))

    /** 같은 날·같은 시간대는 덮어쓴다. 최신 기록이면 프로필 체중도 따라가 영양 목표·근육 부하 체격 계수가 최신 값을 쓴다(P7). */
    fun recordBodyWeight(kg: Double, bodyFatPercent: Double?, slot: String, fasting: Boolean, epochDay: Long = todayEpochDay()) {
        val entry = BodyWeightEntry(epochDay, slot, kg, bodyFatPercent, fasting, nowMillis())
        bodyWeights = (bodyWeights.filterNot { it.epochDay == epochDay && it.slot == slot } + entry).sortedWith(compareBy({ it.epochDay }, { it.recordedAt }))
        store.saveBodyWeights(bodyWeights)
        if (latestBodyWeight == entry && profile.weightKg != kg) completeOnboarding(profile.copy(weightKg = kg))
    }

    fun deleteBodyWeight(entry: BodyWeightEntry) {
        bodyWeights = bodyWeights - entry
        store.saveBodyWeights(bodyWeights)
    }

    val totalXp: Int get() = xpLedger.sumOf { it.amount }

    /** 같은 [key] 로는 한 번만 준다. 줬으면 true. */
    private fun awardXp(key: String, reason: String, amount: Int): Boolean {
        if (amount <= 0 || xpLedger.any { it.key == key }) return false
        xpLedger = xpLedger + XpEntry(nowMillis(), key, reason, amount)
        store.saveXp(xpLedger)
        return true
    }



    /** 지금 진행 중인(오늘 이후까지 덮는) 멈춤 — 화면 표시·해제 버튼용. 지난 멈춤은 [streakPauses] 에 남아 그 날들의 면제가 유지된다(PR 65 리뷰). */
    val streakPause: StreakPause? get() = streakPauses.firstOrNull { it.untilEpochDay >= todayEpochDay() }

    /** 연속 잠시 멈추기 — 오늘부터 [days]일(최대 14). 멈춘 동안은 연속이 줄지 않는다. 진행 중이던 멈춤은 어제까지로 접고 새 구간을 더한다. */
    fun pauseStreak(days: Int) {
        val today = todayEpochDay()
        streakPauses = streakPauses.endedBefore(today) + StreakPause(today, days.coerceIn(1, StreakPause.MAX_DAYS))
        store.saveStreakPauses(streakPauses)
    }

    /** 멈춤 해제 — 지난 날의 면제는 그대로, 오늘부터의 구간만 접는다. */
    fun resumeStreak() {
        streakPauses = streakPauses.endedBefore(todayEpochDay())
        store.saveStreakPauses(streakPauses)
    }

    fun rewardFacts(): RewardFacts {
        val days = workoutHistory.filter { it.items.isNotEmpty() }
        val items = days.flatMap { it.items }
        val sets = workoutHistory.datedSets()
        // 개인 기록 수 — 종목별 최고 kg 가 시간순으로 올라간 횟수(처음 kg 기록도 PR)
        var prs = 0
        val best = mutableMapOf<String, Double>()
        sets.sortedBy { it.epochDay }.forEach { d ->
            val kg = d.set.kg ?: return@forEach
            if (d.set.reps == null) return@forEach
            if (kg > (best[d.workoutName] ?: -1.0)) { prs += 1; best[d.workoutName] = kg }
        }
        val squatReps = items.filter { it.workoutName == "기본 스쿼트" }.sumOf { item ->
            if (item.sets.isEmpty()) item.reps.let { parseReps(it).let { s -> s.count * s.sets } } else item.sets.sumOf { it.countedReps ?: 0 }
        }
        return RewardFacts(
            workoutDays = days.size, streak = attendanceStreak(), longestStreak = longestStreak(),
            coachSets = items.count { it.postureCorrection?.mode == "coach" },
            guidesSeen = seenGuides.count { ExerciseGuides.forName(it) != null }, guidesTotal = ExerciseGuides.all.size,
            personalRecords = prs, squatReps = squatReps,
            plankSeconds = items.filter { it.workoutName == "플랭크" }.sumOf { it.durationSeconds ?: it.durationMinutes * 60 },
            dietDays = dietByDay.count { (_, slots) -> slots.values.any { it.isNotEmpty() } },
            manualRecords = items.count { it.source == "manual" },
            routineShares = xpLedger.count { it.key.startsWith("share:") },
        )
    }

    fun badges(): List<BadgeStatus> = badges(rewardFacts())


    private fun registerCustomProfiles() {
        MuscleLoadCatalog.custom = customExercises.map { ce ->
            MuscleLoadCatalog.customProfile(ce.id, ce.name, ce.muscles.mapNotNull { m -> Muscle.entries.firstOrNull { it.name == m } }, ce.timed)
        }
    }

    /** 카탈로그 + 직접 추가한 종목 — 목록·검색·종목 고르기가 쓴다. */
    internal fun catalogTemplates(): List<WorkoutTemplate> = workoutCatalog.values.flatten() + customExercises.map { it.template() }

    /** 추가 — 성공이면 null, 이름이 비었거나 카탈로그·내 종목과 겹치면 그 문구. */
    fun addCustomExercise(name: String, category: String, equipment: String, muscles: List<String>, timed: Boolean): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 30) return "이름은 1~30자예요"
        if (catalogTemplates().any { it.name == trimmed }) return "같은 이름의 종목이 이미 있어요"
        customExercises = customExercises + CustomExercise("ce-${newUuid()}", trimmed, category, equipment, muscles, timed, nowMillis())
        store.saveCustomExercises(customExercises)
        registerCustomProfiles()
        return null
    }

    fun deleteCustomExercise(id: String) {
        customExercises = customExercises.filterNot { it.id == id }
        store.saveCustomExercises(customExercises)
        registerCustomProfiles()
    }


    /** 요일에 루틴을 넣거나(ref) 뺀다(null = 계획된 휴식). 오늘 요일이면 오늘 루틴에도 바로 얹는다. */
    fun assignWeeklyRoutine(day: kotlinx.datetime.DayOfWeek, ref: String?) {
        weeklyRoutine = weeklyRoutine.assigned(day, ref)
        store.saveWeeklyRoutine(weeklyRoutine)
        if (day == localToday().dayOfWeek) applyWeeklyRoutineForToday(force = true)
    }

    /** 루틴 참조("preset:<id>" | "saved:<id>") → (이름, 운동 목록). 지워진 보관함 루틴이면 null. */
    fun routineWorkouts(ref: String): Pair<String, List<Workout>>? = when {
        ref.startsWith("preset:") -> com.example.trex_kotlin.routines.presetById(ref.removePrefix("preset:"))?.let { it.displayName to it.toWorkouts(nowMillis()) }
        ref.startsWith("saved:") -> savedRoutines.firstOrNull { it.id == ref.removePrefix("saved:") }?.let { it.name to it.toTodayPlan(nowMillis()) }
        else -> null
    }

    fun routineName(ref: String): String? = when {
        ref.startsWith("preset:") -> com.example.trex_kotlin.routines.presetById(ref.removePrefix("preset:"))?.displayName
        ref.startsWith("saved:") -> savedRoutines.firstOrNull { it.id == ref.removePrefix("saved:") }?.name
        else -> null
    }

    /** 오늘 요일의 주간 루틴을 오늘 루틴으로 — 하루 한 번(날이 바뀔 때). [force] 는 사용자가 요일에 넣은 직후. */
    fun applyWeeklyRoutineForToday(force: Boolean = false): Boolean {
        val today = todayEpochDay()
        if (!force && store.weeklyAppliedDay == today) return false
        store.weeklyAppliedDay = today
        val ref = weeklyRoutine.ref(LocalDate.fromEpochDays(today).dayOfWeek) ?: return false
        val (name, workouts) = routineWorkouts(ref) ?: return false
        applyRoutine(name, workouts)
        return true
    }

    /** 세션 중 휴식 화면에서 적는 세트별 kg·회·RPE·메모 — 키는 세트의 운동 id(`…::set:N`). 기록 저장 때 접혀 들어간다. */

    /** 이 종목의 가장 최근 세트 기록(지난번) — 휴식 화면의 "그대로 채우기" 와 종목 상세. */
    fun lastSets(name: String): Pair<Long, List<SetEntry>>? = workoutHistory.sortedByDescending { it.epochDay }.firstNotNullOfOrNull { day ->
        val sets = day.items.filter { it.workoutName == name }.flatMap { it.sets }.filter { it.done }
        if (sets.isEmpty()) null else day.epochDay to sets.sortedBy { it.index }
    }

    /**
     * 카메라·타이머 없이 적는 기록(P2) — 자세 판정은 남지 않는다. 한 항목에 여러 세트. 그날 첫 수동 기록은 +20 XP.
     * 돌려주는 값은 개인 기록이면 그 세트(저장 뒤 알려 준다), 아니면 null; 기록할 세트가 없으면 저장하지 않는다.
     */
    fun recordManualWorkout(epochDay: Long, name: String, sets: List<SetEntry>): Result<SetEntry?> {
        val done = sets.filter { it.done && (it.reps != null || it.seconds != null) }.mapIndexed { i, s -> s.copy(index = i + 1) }
        if (done.isEmpty()) return Result.failure(IllegalArgumentException("완료한 세트가 없어요"))
        val template = catalogTemplates().firstOrNull { it.name == name }
        val timed = done.all { it.seconds != null }
        val label = if (timed) "${done.first().seconds}초 × ${done.size}세트" else "${done.first().countedReps}회 × ${done.size}세트"
        val before = workoutHistory
        val pr = done.firstOrNull { isPersonalRecord(before, name, it) }
        val seconds = done.sumOf { it.seconds ?: 0 }
        val id = "manual-${newUuid()}"
        val w = Workout(id, name, label, template?.duration ?: "8분", false, template?.category ?: "전신", done = true)
        val endedAt = if (epochDay == todayEpochDay()) nowMillis() else startOfDayMillis(epochDay) + 12 * 3_600_000L
        val load = workoutLoadSet("manual:$epochDay", w.copy(reps = "${done.sumOf { it.countedReps ?: 0 }}회 × 1세트"), endedAt, seconds, profile, null)?.copy(source = "manual")
        val date = LocalDate.fromEpochDays(epochDay)
        val item = WorkoutHistoryItem(name, label, seconds / 60, w.estimatedCalories(seconds.coerceAtLeast(done.size * 30)), null, null, w.category, seconds, load, done, "manual")
        val day = WorkoutHistoryDay(epochDay, koreanDayOfWeek(date.dayOfWeek), monthDayLabel(date), listOf(item), 0, 0)
        workoutHistory = workoutHistory.mergeSession(day, retentionDays)
        store.saveHistory(workoutHistory)
        syncMuscleLoad()
        awardXp("manual:$epochDay", "수동 기록", Rewards.XP_MANUAL_FIRST_OF_DAY)
        return Result.success(pr)
    }

    /** 운동 기록 전체 삭제(P13) — 이 기기와 백업의 기록. 근육 부하 장부는 7일 창이 지나며 비워진다. */
    fun deleteAllHistory() {
        workoutHistory = emptyList()
        store.saveHistory(workoutHistory)
        syncMuscleLoad()
    }

    /** 내보내기 텍스트(P15) — CSV/JSON, [fromEpochDay] null 이면 전체. 자세 원본·음식 사진은 넣지 않는다. */
    fun exportRecords(json: Boolean, fromEpochDay: Long?, includeBody: Boolean, includeDiet: Boolean): String {
        fun keep(day: Long) = fromEpochDay == null || day >= fromEpochDay
        val history = workoutHistory.filter { keep(it.epochDay) }
        val body = if (includeBody) bodyWeights.filter { keep(it.epochDay) } else emptyList()
        val diet = if (includeDiet) dietByDay.filterKeys(::keep) else null
        return if (json) buildRecordJson(history, body, diet) else buildRecordCsv(history, body, diet)
    }


    fun updateNotifyPrefs(p: NotifyPrefs) {
        notifyPrefs = p
        store.saveNotifyPrefs(p)
    }

    /** 루틴 공유 배지용 — 공유 링크를 만든 사실을 적는다(XP 0, 장부에만). */
    private fun noteRoutineShare() {
        xpLedger = xpLedger + XpEntry(nowMillis(), "share:${nowMillis()}", "루틴 공유", 0)
        store.saveXp(xpLedger)
    }

    init { registerCustomProfiles() }

    // ---- 식단 (epochDay 기준 저장, 화면에는 오늘 기준 offset 으로 노출)

    var dietByDay by mutableStateOf(
        store.loadDiet() ?: emptyMap(),
    )
        private set

    var waterByDay by mutableStateOf(store.loadWater() ?: emptyMap())
        private set

    private fun epochDayFor(offset: Int): Long = todayEpochDay() + offset

    fun dietFor(offset: Int): Map<String, List<FoodEntry>> =
        dietByDay[calendarDay + offset] ?: emptyDietSlots()

    fun waterFor(offset: Int): Int = waterByDay[calendarDay + offset] ?: 0

    /** 물 한 잔 빼기(§131 P17) — 0 아래로는 가지 않는다. */
    fun removeWater(offset: Int) {
        val key = epochDayFor(offset)
        val n = (waterByDay[key] ?: 0) - 1
        waterByDay = if (n <= 0) waterByDay - key else waterByDay + (key to n)
        store.saveWater(waterByDay)
    }

    fun addWater(offset: Int) {
        val key = epochDayFor(offset)
        waterByDay = waterByDay + (key to (waterByDay[key] ?: 0) + 1)
        store.saveWater(waterByDay)
    }

    /**
     * 사용자가 직접 등록한 음식. 모델 클래스와 기본 DB(350종)에 없는 집밥·간편식·브랜드 제품을 담는다.
     * 기기에 저장하고, 계정을 연결하면 서버에도 백업한다(`sync/SyncRules.KEYS`).
     */
    var customFoods by mutableStateOf(store.loadCustomFoods() ?: emptyMap())
        private set

    /** 이름이 같으면 사용자가 등록한 값이 기본 DB 를 덮는다 — 본인이 고친 값을 존중한다. */
    fun findFood(name: String): Nutrition? =
        customFoods[name] ?: foodDatabase[name] ?: foodNameAliases[name]?.let { foodDatabase[it] }

    /** 검색 결과. 사용자 등록분을 앞에 둔다(두 번째 값이 true 면 사용자 등록). */
    fun searchFoods(query: String): List<Triple<String, Nutrition, Boolean>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // 사용자가 쓰는 다른 이름으로 쳐도 찾히게 한다(흰쌀밥 → 쌀밥).
        val viaAlias = foodNameAliases.filterKeys { it.contains(q) }.values.toSet()
        val mine = customFoods.filterKeys { it.contains(q) }.map { (n, v) -> Triple(n, v, true) }
        val base = foodDatabase
            .filterKeys { (it.contains(q) || it in viaAlias) && it !in customFoods }
            .map { (n, v) -> Triple(n, v, false) }
        return mine + base
    }

    fun addCustomFood(name: String, nutrition: Nutrition) {
        val key = name.trim()
        if (key.isEmpty()) return
        customFoods = customFoods + (key to nutrition)
        store.saveCustomFoods(customFoods)
    }

    fun removeCustomFood(name: String) {
        customFoods = customFoods - name
        store.saveCustomFoods(customFoods)
    }

    /**
     * 지금까지 기록한 식단에서 자주 담은 음식 순. 검색어가 비어 있는 화면의 첫 목록으로 쓴다.
     *
     * 따로 저장하지 않고 기존 기록에서 센다 — 빈도표를 새로 저장하면 지우기·이전·기록과의
     * 불일치가 따라붙는데, 기록 자체가 이미 정답이라 그럴 이유가 없다. 세는 규칙은
     * [frequentFoodsOf] 에 있다(테스트가 같은 함수를 부른다).
     */
    fun frequentFoods(limit: Int = 8): List<Pair<String, Nutrition>> =
        frequentFoodsOf(dietByDay, limit, ::findFood)
    /** 슬롯에 음식 추가 (사진/수동 기록 플로우의 결과). 같은 이름은 수량을 올린다. */
    fun appendFoods(offset: Int, slot: String, foods: List<FoodEntry>) {
        mutateSlots(offset) { slots ->
            var list = slots[slot].orEmpty()
            foods.forEach { food ->
                val at = list.indexOfFirst { it.name == food.name }
                list = if (at == -1) {
                    list + food
                } else {
                    list.toMutableList().also { it[at] = it[at].copy(qty = FoodPortion.add(it[at].qty, food.qty)) }
                }
            }
            slots + (slot to list)
        }
    }

    /** 슬롯 내 음식 인분 한 칸(0.5) 올리기·내리기 — 0 이 되면 제거 (직접 기록 시트 스테퍼, [FoodPortion.step]). */
    fun stepFoodQty(offset: Int, slot: String, index: Int, up: Boolean) {
        mutateSlots(offset) { slots ->
            val list = slots[slot].orEmpty().toMutableList()
            val cur = list.getOrNull(index) ?: return@mutateSlots slots
            val q = FoodPortion.step(cur.qty, up)
            if (q == null) list.removeAt(index) else list[index] = cur.copy(qty = q)
            slots + (slot to list)
        }
    }

    /** 숫자를 눌러 직접 넣은 인분. [qty] 는 [FoodPortion.parseTyped] 를 거친 값(0.1~20) — 지우는 길은 스테퍼뿐이다. */
    fun setFoodQty(offset: Int, slot: String, index: Int, qty: Double) {
        mutateSlots(offset) { slots ->
            val list = slots[slot].orEmpty().toMutableList()
            val cur = list.getOrNull(index) ?: return@mutateSlots slots
            list[index] = cur.copy(qty = FoodPortion.normalize(qty).coerceIn(FoodPortion.MIN_TYPED, FoodPortion.MAX))
            slots + (slot to list)
        }
    }

    /** 슬롯 전체 교체 (끼니 수정 시트의 결과). */
    fun replaceSlot(offset: Int, slot: String, foods: List<FoodEntry>) {
        mutateSlots(offset) { slots -> slots + (slot to foods) }
    }

    fun clearSlot(offset: Int, slot: String) = replaceSlot(offset, slot, emptyList())

    /** 마지막에 추가한 n개 음식 되돌리기 (기록 직후 실행 취소 토스트). */
    fun undoAppend(offset: Int, slot: String, count: Int) {
        mutateSlots(offset) { slots ->
            val current = slots[slot].orEmpty()
            slots + (slot to current.dropLast(count))
        }
    }

    private fun mutateSlots(
        offset: Int,
        transform: (Map<String, List<FoodEntry>>) -> Map<String, List<FoodEntry>>,
    ) {
        val key = epochDayFor(offset)
        val current = dietByDay[key] ?: emptyDietSlots()
        val keepFrom = todayEpochDay() + DIET_MIN_OFFSET
        dietByDay = (dietByDay + (key to transform(current)))
            .filterKeys { it >= keepFrom }
        store.saveDiet(dietByDay)
    }

    /** 현재 끼니 슬롯의 가장 최근 기록 — "최근 기록 다시 기록하기" 소스. */
    fun recentFoodsForCurrentMeal(): List<FoodEntry> {
        val mealId = currentMealId()
        return dietByDay.entries
            .sortedByDescending { it.key }
            .firstNotNullOfOrNull { (_, slots) -> slots[mealId]?.takeIf { it.isNotEmpty() } }
            .orEmpty()
    }

    // 모든 상태 필드가 만들어진 뒤에 동기화를 켠다(서버 값이 오면 위 필드들을 다시 읽는다).
    init {
        sync.start()
        // 첫 연결 질문 중에 앱이 꺼졌으면 여기서 다시 묻는다. 오프라인이면 앱 복귀·"지금 동기화" 때 다시 한다.
        refreshAccountSync()
    }

    companion object {
        const val DIET_MIN_OFFSET = -7
        const val DIET_MAX_OFFSET = 0
    }
}
