package com.example.trex_kotlin

import com.example.trex_kotlin.community.CommunityApi
import com.example.trex_kotlin.community.CommunityGate
import com.example.trex_kotlin.community.CommunityMe
import com.example.trex_kotlin.community.communityGate
import com.example.trex_kotlin.sync.ApiException
import com.example.trex_kotlin.sync.AuthAccount
import com.example.trex_kotlin.sync.AuthSession
import com.example.trex_kotlin.sync.BinaryTransport
import com.example.trex_kotlin.sync.FirstSync
import com.example.trex_kotlin.sync.NetworkException
import com.example.trex_kotlin.sync.SignInException
import com.example.trex_kotlin.sync.SyncManager
import com.example.trex_kotlin.sync.TrexApi
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

    /** "로그인 없이 사용". 나중에 설정에서 계정을 연결할 수 있다. */
    fun continueWithoutAccount() = completeLogin()

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
        viewModelScope.launch {
            authBusy = true
            authMessage = null
            try {
                val idToken = requestIdToken(platform.googleWebClientId)
                val signedIn = api.googleLogin(idToken)
                authSession.save(signedIn)
                account = signedIn
                beginSync(signedIn)
            } catch (_: SignInException.Cancelled) {
                // 사용자가 계정 선택을 닫았다 — 안내하지 않는다
            } catch (_: SignInException.NoAccount) {
                authMessage = "이 기기에 구글 계정이 없어요. 휴대폰 설정에서 계정을 추가해 주세요."
            } catch (e: SignInException.Failed) {
                // 콘솔 설정 오류(SHA-1·패키지명·테스트 사용자)는 여기로 온다 — 로그는 플랫폼 쪽(GoogleSignIn.kt)이 남긴다
                authMessage = "구글 로그인에 실패했어요. ${e.reason}"
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
        get() = communityGate(platform.googleWebClientId.isNotBlank(), account != null, communityMe)

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
        workoutHistory = store.loadHistory() ?: emptyList()
        dietByDay = store.loadDiet() ?: emptyMap()
        waterByDay = store.loadWater() ?: emptyMap()
        customFoods = store.loadCustomFoods() ?: emptyMap()
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

    // ---- 운동 안내를 본 종목 — 세션 첫 준비에서 한 번만 자동으로 띄운다(§108 에서 TrexApp 에서 옮김, 같은 저장소·키)

    private val guidePrefs = platform.preferences("exercise_guides")
    var seenGuides by mutableStateOf(guidePrefs.getStringSet("seen_v1", emptySet()).orEmpty().toSet())
        private set

    fun markGuideSeen(name: String) {
        seenGuides = seenGuides + name
        guidePrefs.edit().putStringSet("seen_v1", seenGuides).apply()
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
        val ledger=loadLedger
        viewModelScope.launch {
            val next=withContext(Dispatchers.Default) { MuscleLoadEngine.snapshot(ledger.sets,nowMillis(),ledger.doses) }
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
                        MuscleLoadEngine.snapshot(ledger.sets, nowMillis(), ledger.doses)
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
        val retained = workoutHistory.retainVisibleWorkoutHistory(today)
        if (retained != workoutHistory) { workoutHistory = retained; store.pruneExpiredHistory(today) }
        if (resetPlan && store.planDoneEpochDay != today) {
            if (workoutPlan.any { it.done }) updatePlan(workoutPlan.map { it.copy(done = false) })
            store.planDoneEpochDay = today
        }
    }

    init {
        // 어제 완료한 계획은 오늘 다시 처음부터 — done 플래그는 하루 단위다
        val today = todayEpochDay()
        if (store.planDoneEpochDay != today && workoutPlan.any { it.done }) {
            workoutPlan = workoutPlan.map { it.copy(done = false) }
            store.savePlan(workoutPlan)
        }
        store.planDoneEpochDay = today
        refreshCalendar(today)
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
        val record = createWorkoutHistoryDay(completedPlan, elapsedSeconds, sessionPostureReports.toMap(), elapsedByWorkout)
        val items = record.items.mapIndexed { i, item ->
            val w = completedPlan[i]
            item.copy(loadSet = workoutLoadSet(sessionId, w, endedAt[w.id] ?: nowMillis(),
                elapsedByWorkout[w.id] ?: 0, profile, sessionPostureReports[w.id]))
        }
        workoutHistory = workoutHistory.mergeSession(record.copy(items=items))
        store.saveHistory(workoutHistory)
        syncMuscleLoad()
    }

    val todayRecord: WorkoutHistoryDay?
        get() = workoutHistory.firstOrNull { it.epochDay == todayEpochDay() }

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

    /** 오늘 포함 최근 연속 운동일. */
    fun attendanceStreak(): Int {
        val days = workoutHistory.filter { it.items.isNotEmpty() }.map { it.epochDay }.toSet()
        var streak = 0
        var cursor = todayEpochDay()
        if (cursor !in days) cursor -= 1 // 오늘 아직 안 했으면 어제부터 센다
        while (cursor in days) {
            streak += 1
            cursor -= 1
        }
        return streak
    }

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
