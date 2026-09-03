package com.example.trex_kotlin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 로컬 영속 저장소 (백엔드 연동 전 단계).
 *
 * 기존에는 모든 서비스 데이터가 컴포저블 `remember` 에만 있어서 탭 전환/화면 회전/앱 재시작에
 * 기록이 통째로 사라졌다. SharedPreferences + JSON 으로 단순하게 저장하고, 서버가 붙으면
 * 이 클래스만 원격 동기화 구현으로 교체한다.
 */
class TrexStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("trex_store", Context.MODE_PRIVATE)

    // ---- 진행 플래그

    var guideDone: Boolean
        get() = prefs.getBoolean(KEY_GUIDE_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_GUIDE_DONE, value).apply()

    var loggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGGED_IN, value).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "System") }.getOrDefault(ThemeMode.System)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** 계획의 done 플래그가 어느 날짜 기준인지 — 날짜가 바뀌면 리셋한다. */
    var planDoneEpochDay: Long
        get() = prefs.getLong(KEY_PLAN_DONE_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_PLAN_DONE_DAY, value).apply()

    // ---- 사용자 프로필

    fun loadProfile(): UserProfile? = prefs.getString(KEY_PROFILE, null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            UserProfile(
                goal = o.optString("goal", "general"),
                dayMask = o.optInt("dayMask", 0),
                place = o.optString("place").takeIf { it.isNotEmpty() },
                bodyweightOnly = o.optBoolean("bodyweightOnly", false),
                equipmentMask = o.optInt("equipmentMask", 0),
                gender = o.optString("gender", "none"),
                heightCm = o.optDouble("heightCm", 170.0),
                weightKg = o.optDouble("weightKg", 65.0),
                age = o.optInt("age", 30),
                activityFactor = o.optDouble("activityFactor", 1.35),
            )
        }.getOrNull()
    }

    fun saveProfile(profile: UserProfile) {
        val o = JSONObject()
            .put("goal", profile.goal)
            .put("dayMask", profile.dayMask)
            .put("place", profile.place ?: "")
            .put("bodyweightOnly", profile.bodyweightOnly)
            .put("equipmentMask", profile.equipmentMask)
            .put("gender", profile.gender)
            .put("heightCm", profile.heightCm)
            .put("weightKg", profile.weightKg)
            .put("age", profile.age)
            .put("activityFactor", profile.activityFactor)
        prefs.edit().putString(KEY_PROFILE, o.toString()).apply()
    }

    // ---- 운동 계획

    fun loadPlan(): List<Workout>? = prefs.getString(KEY_PLAN, null)?.let { raw ->
        runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Workout(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    reps = o.getString("reps"),
                    duration = o.getString("duration"),
                    posture = o.getBoolean("posture"),
                    category = o.getString("category"),
                    alt = o.optJSONObject("alt")?.let { a ->
                        WorkoutAlt(a.getString("name"), a.getString("reps"))
                    },
                    done = o.optBoolean("done", false),
                )
            }
        }.getOrNull()
    }

    fun savePlan(plan: List<Workout>) {
        val arr = JSONArray()
        plan.forEach { w ->
            val o = JSONObject()
                .put("id", w.id)
                .put("name", w.name)
                .put("reps", w.reps)
                .put("duration", w.duration)
                .put("posture", w.posture)
                .put("category", w.category)
                .put("done", w.done)
            w.alt?.let { o.put("alt", JSONObject().put("name", it.name).put("reps", it.reps)) }
            arr.put(o)
        }
        prefs.edit().putString(KEY_PLAN, arr.toString()).apply()
    }

    // ---- 운동 기록

    fun loadHistory(): List<WorkoutHistoryDay>? = prefs.getString(KEY_HISTORY, null)?.let { raw ->
        runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val itemsArr = o.getJSONArray("items")
                WorkoutHistoryDay(
                    epochDay = o.getLong("epochDay"),
                    dayLabel = o.getString("dayLabel"),
                    dateLabel = o.getString("dateLabel"),
                    averageMinutes = o.getInt("averageMinutes"),
                    averageCalories = o.getInt("averageCalories"),
                    items = List(itemsArr.length()) { j ->
                        val it = itemsArr.getJSONObject(j)
                        // §30 이전 기록(postureKind 없음)의 자세 칸·정확도는 전부 시드 목업/하드코딩이었다 — 실데이터와 섞이지 않게 버린다
                        val legacy = it.optString("postureKind").isEmpty()
                        WorkoutHistoryItem(
                            workoutName = it.getString("workoutName"),
                            reps = it.getString("reps"),
                            durationMinutes = it.getInt("durationMinutes"),
                            calories = it.getInt("calories"),
                            postureCorrection = if (legacy) null else readPostureCorrection(it),
                            accuracy = if (legacy) null else it.optInt("accuracy", -1).takeIf { a -> a >= 0 },
                        )
                    },
                )
            }
        }.getOrNull()
    }

    fun saveHistory(history: List<WorkoutHistoryDay>) {
        val arr = JSONArray()
        history.forEach { day ->
            val items = JSONArray()
            day.items.forEach { item ->
                items.put(
                    JSONObject()
                        .put("workoutName", item.workoutName)
                        .put("reps", item.reps)
                        .put("durationMinutes", item.durationMinutes)
                        .put("calories", item.calories)
                        .put("accuracy", item.accuracy ?: -1)
                        .also { o -> writePostureCorrection(o, item.postureCorrection) },
                )
            }
            arr.put(
                JSONObject()
                    .put("epochDay", day.epochDay)
                    .put("dayLabel", day.dayLabel)
                    .put("dateLabel", day.dateLabel)
                    .put("averageMinutes", day.averageMinutes)
                    .put("averageCalories", day.averageCalories)
                    .put("items", items),
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /**
     * 자세 요약 필드 — "postureFocus" 가 비어 있으면 자세 칸 없음(구버전과 같은 관례).
     * 정수 null 은 accuracy 와 같은 -1, 문자열 null 은 키 생략(optString 은 "" 로 읽힌다). JSON null 을 넣지 않는 이유:
     * org.json 의 optString 은 JSON null 을 문자열 "null" 로 돌려준다.
     */
    private fun readPostureCorrection(o: JSONObject): PostureCorrection? {
        val focus = o.optString("postureFocus").takeIf { it.isNotEmpty() } ?: return null
        return PostureCorrection(
            focus = focus,
            kind = o.optString("postureKind").takeIf { it.isNotEmpty() },
            bodyPart = o.optString("postureBodyPart").takeIf { it.isNotEmpty() },
            fix = o.optString("postureFix").takeIf { it.isNotEmpty() },
            note = o.optString("postureNote").takeIf { it.isNotEmpty() },
            beta = o.optBoolean("postureBeta", false),
            setId = o.optString("postureSetId").takeIf { it.isNotEmpty() },
            mode = o.optString("postureMode").takeIf { it.isNotEmpty() },
            judged = o.optInt("postureJudged", -1).takeIf { it >= 0 },
            abstained = o.optInt("postureAbstained", -1).takeIf { it >= 0 },
            repsValid = o.optInt("postureRepsValid", -1).takeIf { it >= 0 },
            repsPartial = o.optInt("postureRepsPartial", -1).takeIf { it >= 0 },
            tempoMs = o.optLong("postureTempoMs", -1L).takeIf { it >= 0L },
            actualReps = o.optInt("postureActualReps", -1).takeIf { it >= 0 },
            formLabel = o.optString("postureFormLabel").takeIf { it.isNotEmpty() },
        )
    }

    private fun writePostureCorrection(o: JSONObject, pc: PostureCorrection?) {
        o.put("postureFocus", pc?.focus ?: "")
        if (pc == null) return
        pc.kind?.let { o.put("postureKind", it) }
        pc.bodyPart?.let { o.put("postureBodyPart", it) }
        pc.fix?.let { o.put("postureFix", it) }
        pc.note?.let { o.put("postureNote", it) }
        o.put("postureBeta", pc.beta)
        pc.setId?.let { o.put("postureSetId", it) }
        pc.mode?.let { o.put("postureMode", it) }
        o.put("postureJudged", pc.judged ?: -1)
        o.put("postureAbstained", pc.abstained ?: -1)
        o.put("postureRepsValid", pc.repsValid ?: -1)
        o.put("postureRepsPartial", pc.repsPartial ?: -1)
        o.put("postureTempoMs", pc.tempoMs ?: -1L)
        o.put("postureActualReps", pc.actualReps ?: -1)
        pc.formLabel?.let { o.put("postureFormLabel", it) }
    }

    // ---- 식단 (epochDay → 슬롯 → 음식들)

    fun loadDiet(): Map<Long, Map<String, List<FoodEntry>>>? = prefs.getString(KEY_DIET, null)?.let { raw ->
        runCatching {
            val root = JSONObject(raw)
            buildMap {
                root.keys().forEach { dayKey ->
                    val slots = root.getJSONObject(dayKey)
                    put(
                        dayKey.toLong(),
                        buildMap {
                            slots.keys().forEach { slotId ->
                                val foods = slots.getJSONArray(slotId)
                                put(
                                    slotId,
                                    List(foods.length()) { i ->
                                        val f = foods.getJSONObject(i)
                                        FoodEntry(
                                            name = f.getString("name"),
                                            nutrition = Nutrition(
                                                kcal = f.getInt("kcal"),
                                                carb = f.getDouble("carb"),
                                                protein = f.getDouble("protein"),
                                                fat = f.getDouble("fat"),
                                            ),
                                            qty = f.optInt("qty", 1),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }.getOrNull()
    }

    fun saveDiet(diet: Map<Long, Map<String, List<FoodEntry>>>) {
        val root = JSONObject()
        diet.forEach { (epochDay, slots) ->
            val slotsObj = JSONObject()
            slots.forEach { (slotId, foods) ->
                val arr = JSONArray()
                foods.forEach { food ->
                    arr.put(
                        JSONObject()
                            .put("name", food.name)
                            .put("kcal", food.nutrition.kcal)
                            .put("carb", food.nutrition.carb)
                            .put("protein", food.nutrition.protein)
                            .put("fat", food.nutrition.fat)
                            .put("qty", food.qty),
                    )
                }
                slotsObj.put(slotId, arr)
            }
            root.put(epochDay.toString(), slotsObj)
        }
        prefs.edit().putString(KEY_DIET, root.toString()).apply()
    }

    // ---- 물 섭취 (epochDay → 잔 수)

    fun loadWater(): Map<Long, Int>? = prefs.getString(KEY_WATER, null)?.let { raw ->
        runCatching {
            val root = JSONObject(raw)
            buildMap { root.keys().forEach { key -> put(key.toLong(), root.getInt(key)) } }
        }.getOrNull()
    }

    fun saveWater(water: Map<Long, Int>) {
        val root = JSONObject()
        water.forEach { (epochDay, cups) -> root.put(epochDay.toString(), cups) }
        prefs.edit().putString(KEY_WATER, root.toString()).apply()
    }

    // ---- 영양 목표 (null 이면 프로필 기반 추천값 사용)

    fun loadGoalOverride(): Nutrition? = prefs.getString(KEY_GOAL, null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            Nutrition(
                kcal = o.getInt("kcal"),
                carb = o.getDouble("carb"),
                protein = o.getDouble("protein"),
                fat = o.getDouble("fat"),
            )
        }.getOrNull()
    }

    fun saveGoalOverride(goal: Nutrition?) {
        if (goal == null) {
            prefs.edit().remove(KEY_GOAL).apply()
        } else {
            val o = JSONObject()
                .put("kcal", goal.kcal)
                .put("carb", goal.carb)
                .put("protein", goal.protein)
                .put("fat", goal.fat)
            prefs.edit().putString(KEY_GOAL, o.toString()).apply()
        }
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_PLAN_DONE_DAY = "plan_done_day"
        const val KEY_GUIDE_DONE = "guide_done"
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_PROFILE = "profile"
        const val KEY_PLAN = "plan"
        const val KEY_HISTORY = "history"
        const val KEY_DIET = "diet"
        const val KEY_WATER = "water"
        const val KEY_GOAL = "goal_override"
    }
}
