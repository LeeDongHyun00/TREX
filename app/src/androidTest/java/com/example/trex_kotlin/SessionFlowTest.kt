package com.example.trex_kotlin

import com.example.trex_kotlin.posture.toDinoCopy
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 루트 화면·설정 저장·타이머 전환을 검사한다. 사용자 앱에는 테스트 계획을 쓰지 않는다. */
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (!node.refresh()) return
            add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String) = nodes().firstOrNull { it.text?.toString() in setOf(text,text.toDinoCopy()) || it.contentDescription?.toString()==text }
    private fun await(text: String, timeout: Long = 12000) {
        val until=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<until) { if(find(text)!=null)return; Thread.sleep(100) }
        capture("failed-screen")
        error("화면에 '$text' 없음: " + nodes().map { "${it.text ?: ""} [${it.contentDescription ?: ""}]" }.joinToString(" | "))
    }
    private fun click(text: String) {
        await(text)
        // 시트가 닫히는 동안 같은 이름의 제목은 남고 버튼은 아직 접근할 수 없다.
        val until = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < until) {
            val candidates = nodes().filter { it.isVisibleToUser && !it.isEditable && (it.text?.toString() in setOf(text,text.toDinoCopy()) || it.contentDescription?.toString()==text) }
            for (candidate in candidates) {
                var node: AccessibilityNodeInfo? = candidate
                while (node != null) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        instrumentation.waitForIdleSync()
                        Thread.sleep(400) // 창과 접근성 노드의 전환이 끝난 뒤 다음 입력을 보낸다.
                        return
                    }
                    node = node.parent
                }
            }
            Thread.sleep(100)
        }
        capture("failed-click")
        error("클릭 불가: $text · " + nodes().joinToString(" | ") { "${it.text} [${it.contentDescription}] visible=${it.isVisibleToUser} enabled=${it.isEnabled} clickable=${it.isClickable}" })
    }
    private fun scrollTo(text: String) {
        repeat(12) {
            if (find(text)?.isVisibleToUser == true) return
            nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            Thread.sleep(150)
        }
        await(text)
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(500) // 접근성 트리가 갱신된 뒤 실제 화면 그리기까지 기다린다.
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(context.getExternalFilesDir(null),"$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }

    private fun isolated(block: (TrexStore) -> Unit) {
        check(context.packageName.endsWith(".replay"))
        val prefs=context.getSharedPreferences("trex_store",Context.MODE_PRIVATE)
        val before=prefs.all.toMap()
        try { block(TrexStore(context)) } finally {
            val edit=prefs.edit().clear()
            before.forEach { (key,value) -> when(value) {
                is String -> edit.putString(key,value); is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value); is Long -> edit.putLong(key,value); is Float -> edit.putFloat(key,value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>) }
            } };edit.commit()
        }
    }

    @Test fun perExerciseEditorPersistsIndependentTargetsAndPosture() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true
        store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("edit-a","기본 스쿼트","2회 × 2세트","99분",false,"하체",restSeconds=4),
            Workout("edit-b","플랭크","30초 × 1세트","99분",false,"코어",restSeconds=60)))
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동");capture("workout-flat")
            click("기본 스쿼트 수정")
            await("운동 수정")
            assertNull(find("플랭크"))
            scrollTo("목표 횟수 증가");click("목표 횟수 증가")
            scrollTo("세트 증가");click("세트 증가")
            scrollTo("세트 간 휴식 증가");click("세트 간 휴식 증가")
            scrollTo("기본 스쿼트 자세 비교");click("기본 스쿼트 자세 비교")
            capture("workout-editor");click("저장")
            val saved=TrexStore(context).loadPlan()!!
            assertEquals(WorkoutTarget.Repetitions(3),saved[0].target)
            assertEquals(3,saved[0].repsSpec().sets);assertEquals(9,saved[0].restSeconds)
            assertTrue(saved[0].posture);assertFalse(saved[1].posture)
            assertEquals(WorkoutTarget.Duration(30),saved[1].target)
            click("기본 스쿼트 수정");scrollTo("종목 변경");click("종목 변경")
            await("운동 검색");await("추천 대체 운동");assertNull(find("허벅지·엉덩이"));capture("replacement-categories")
            val search=nodes().first { it.isEditable }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"월 싯")
            }))
            click("월 싯 선택");click("저장")
            val changed=TrexStore(context).loadPlan()!!.first()
            assertEquals("edit-a",changed.id);assertEquals("월 싯",changed.name)
            assertTrue(changed.resolvedTarget() is WorkoutTarget.Duration)
        }
    }

    @Test fun guideAndLoginKeepArtworkAndActionsVisible() = isolated { store ->
        store.guideDone=false;store.loggedIn=false
        ActivityScenario.launch(MainActivity::class.java).use {
            await("다음");Thread.sleep(1200);capture("guide-full-image")
            repeat(3){click("다음");Thread.sleep(250)}
            click("시작하기");await("아이디/비밀번호 찾기");Thread.sleep(1500);capture("login-restored")
            assertNotNull(find("TREX"))
            assertNull(find("자세 교정 실험실"))
            assertNull(find("자세 기준선 설정"));assertNull(find("가이드북"));assertNull(find("계정 도움"))
            click("회원가입");scrollTo("가입 완료");await("가입 완료")
        }
    }

    @Test fun durationGoalAutoCompletesWithoutCameraOrRepCounts() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true
        store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("hold-test","플랭크","3초 × 1세트","99분",false,"코어")))
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동");click("운동 시작");await("운동 준비");capture("timer-preparation")
            await("시간 측정");capture("session-circle")
            await("완료 세트")
            await("오늘 운동을 기록했어요");capture("session-complete")
            assertTrue(TrexStore(context).loadPlan()!!.single().done)
        }
    }

    @Test fun bottomNavigationReachesDietSettingsAndRecordDates() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        val date=java.time.LocalDate.now()
        store.saveHistory(listOf(WorkoutHistoryDay(date.toEpochDay(),"오늘","오늘",
            listOf(WorkoutHistoryItem("기록 확인 스쿼트","12회 × 1세트",2,4)),2,4)))
        ActivityScenario.launch(MainActivity::class.java).use {
            click("식단");await("사진 기록");capture("diet-list")
            assertNull(find("메뉴"))
            val kcal=TrexStore(context).loadGoalOverride()?.kcal
            click("영양 목표 수정");click("하루 칼로리 증가");click("취소")
            assertEquals(kcal,TrexStore(context).loadGoalOverride()?.kcal)
            click("영양 목표 수정");click("하루 칼로리 증가");capture("nutrition-editor");click("목표 저장")
            assertNotNull(TrexStore(context).loadGoalOverride())
            click("뒤로가기");capture("navigation-tabs");click("내 정보");await("프로필 편집");capture("settings-list")
            click("화면 모드");click("다크");click("완료");capture("settings-dark")
            click("운동 기록");await("최근 7일");assertNull(find("기록 확인 스쿼트"));capture("record-collapsed")
            val label="${date.monthValue}월 ${date.dayOfMonth}일 기록"
            await("완료 세트");click("${date.monthValue}월 ${date.dayOfMonth}일 그래프 · 1세트")
            await("기록 확인 스쿼트");capture("record-expanded")
            click(label);Thread.sleep(400);assertNull(find("기록 확인 스쿼트"))
            click("기록 뒤로가기");await("프로필 편집")
        }
    }

    @Test fun homeCardsNavigateWithoutStartingWorkout() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true
        ActivityScenario.launch(MainActivity::class.java).use {
            scrollTo("운동하기");click("운동하기");await("오늘 운동");assertNull(find("운동 준비"))
            click("뒤로가기");click("홈");click("식단 보기");await("오늘 식단")
            capture("home-diet-link")
        }
    }

    @Test fun overviewUsesRealPlanAndHomeOnlyShowsRequestedSections() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("hero-a","기본 스쿼트","12회 × 3세트","99분",false,"하체"),
            Workout("hero-b","마무리 스트레칭","3분 × 1세트","99분",false,"회복")))
        for (focus in RoutineFocus.entries.filter { it != RoutineFocus.EMPTY }) {
            val bitmap=context.assets.open("routine/${focus.image}.png").use { android.graphics.BitmapFactory.decodeStream(it) }
            assertNotNull(bitmap);bitmap!!.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("운동한 날");await("오늘의 섭취");await("하체 중심")
            assertNull(find("오늘 소모"));assertNull(find("연속 출석"));assertNull(find("이번 주 목표"))
            capture("overview-home")
            scrollTo("기록하기");click("기록하기");await("직접 기록")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            click("운동");await("하체 중심");capture("overview-workout")
            scrollTo("기본 스쿼트 수정");click("기본 스쿼트 수정");await("운동 수정");click("취소")
            click("뒤로가기");click("식단");await("영양 목표 수정");capture("overview-diet")
            scrollTo("저녁");capture("overview-meals")
            click("뒤로가기");click("홈")
            scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java].setTheme(ThemeMode.Dark) }
            Thread.sleep(600);await("오늘의 섭취");capture("overview-dark")
            scenario.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(900);scrollTo("기록하기");capture("overview-landscape")
        }
    }

    @Test fun mealTapEditsAndExpandedDayShowsRealSummary() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        val day=java.time.LocalDate.now()
        store.planDoneEpochDay=day.toEpochDay()
        store.savePlan(todayPlan)
        store.saveHistory(listOf(createWorkoutHistoryDay(todayPlan.take(1),60,elapsedByWorkout=mapOf("squat" to 60))))
        store.saveDiet(mapOf(day.toEpochDay() to mapOf("breakfast" to listOf(FoodEntry("바나나",Nutrition(89,23.0,1.1,.3))))))
        ActivityScenario.launch(MainActivity::class.java).use {
            await("오늘의 섭취");capture("restored-home-top")
            scrollTo("연속 운동");capture("restored-home-bottom")
            click("식단");await("오늘 식단");capture("restored-diet")
            click("사진 기록");await("직접 기록으로 이어가룡");assertNull(find("분석 완료"));click("음식 직접 선택");await("직접 기록")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            scrollTo("아침 식단 수정");click("아침 식단 수정");await("아침 기록");assertNull(find("이 끼니 합계"));capture("meal-edit")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            click("뒤로가기");click("운동");click("운동 기록")
            val dateLabel="${day.monthValue}월 ${day.dayOfMonth}일 기록"
            scrollTo(dateLabel);click(dateLabel);await("하체 중심")
            await("1개 종목을 기록했어룡. 자세를 평가할 기록은 충분하지 않아룡.")
            capture("record-focus-summary")
        }
    }

    private fun bounds(text: String): android.graphics.Rect {
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        await(text)
        val node = find(text)!!
        node.refresh()
        return android.graphics.Rect().also { node.getBoundsInScreen(it) }
    }
    /** 실제 터치 이벤트로 길게 누르기와 스와이프의 경합·삭제 확인을 검사한다. */
    private fun gesture(x: Float, y: Float, endX: Float, endY: Float, hold: Long = 0) {
        val start=android.os.SystemClock.uptimeMillis()
        fun emit(action: Int, px: Float, py: Float) {
            val e=android.view.MotionEvent.obtain(start,android.os.SystemClock.uptimeMillis(),action,px,py,0)
            e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(instrumentation.uiAutomation.injectInputEvent(e,true));e.recycle()
        }
        emit(android.view.MotionEvent.ACTION_DOWN,x,y)
        if(hold>0)Thread.sleep(hold)
        repeat(20) { i -> val f=(i+1)/20f;emit(android.view.MotionEvent.ACTION_MOVE,x+(endX-x)*f,y+(endY-y)*f);if(hold>0 && i==10)capture("workout-drag-in-progress");Thread.sleep(22) }
        emit(android.view.MotionEvent.ACTION_UP,endX,endY);Thread.sleep(600)
    }

    @Test fun longPressReordersAndSwipeRequiresDeleteConfirmation() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        store.savePlan(listOf(Workout("drag-a","기본 스쿼트","12회 × 3세트","8분",false,"하체"),
            Workout("drag-b","플랭크","30초 × 1세트","1분",false,"코어"),
            Workout("drag-c","마무리 스트레칭","3분 × 1세트","3분",false,"회복")))
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동");capture("workout-grouped");scrollTo("플랭크 수정")
            val toggle=bounds("기본 스쿼트 자세 교정 사용");val edit=bounds("기본 스쿼트 수정")
            assertTrue("토글이 운동 행 안에 배치", edit.contains(toggle))
            assertNotNull(find("자세 교정"))
            click("기본 스쿼트 자세 교정 사용");assertTrue(TrexStore(context).loadPlan()!!.first().posture)
            val a=bounds("기본 스쿼트");val b=bounds("플랭크")
            gesture(a.left+24f,a.exactCenterY(),a.left+24f,b.exactCenterY(),700)
            assertEquals(listOf("drag-b","drag-a","drag-c"),TrexStore(context).loadPlan()!!.map { it.id })
            capture("workout-reordered")
            fun swipe() {
                val row=bounds("플랭크");val screen=android.graphics.Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
                gesture(screen.width()*.52f,row.exactCenterY(),screen.width()*.08f,row.exactCenterY())
            }
            swipe();await("운동을 삭제할까요?");await("오늘 운동에서 플랭크 항목을 삭제해요.")
            assertEquals(3,TrexStore(context).loadPlan()!!.size);capture("workout-delete-confirm")
            click("취소");Thread.sleep(400);assertEquals(3,TrexStore(context).loadPlan()!!.size)
            swipe();click("삭제");Thread.sleep(400)
            assertEquals(listOf("drag-a","drag-c"),TrexStore(context).loadPlan()!!.map { it.id })
        }
    }

    @Test fun addingFromCategoryPlacesWorkBeforeCooldown() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        store.savePlan(listOf(Workout("add-work","플랭크","30초 × 1세트","1분",false,"코어"),
            Workout("add-recovery","마무리 스트레칭","3분 × 1세트","3분",false,"회복")))
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동");click("운동 추가");await("하체 카테고리")
            click("상체 카테고리");assertNull(find("기본 스쿼트 선택"));click("하체 카테고리");await("기본 스쿼트 선택");capture("add-category")
            click("기본 스쿼트 선택");click("추가");Thread.sleep(400)
            val saved=TrexStore(context).loadPlan()!!
            assertEquals(listOf("플랭크","기본 스쿼트","마무리 스트레칭"),saved.map { it.name })
            assertEquals("add-work",saved.first().id);assertEquals("add-recovery",saved.last().id)
        }
    }

    @Test fun dragFromEditButtonDoesNotOpenAnotherExercise() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        val names=listOf("바벨 데드리프트","플랭크","사이드 레터럴 레이즈","크런치","덤벨 컬","런지","푸쉬업")
        store.savePlan(names.mapIndexed { i,name -> Workout("drag-stress-$i",name,"12회 × 3세트","8분",false,"하체") })
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동")
            val screen=android.graphics.Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            gesture(screen.width()*.25f,screen.height()*.72f,screen.width()*.25f,screen.height()*.42f)
            val start=bounds("바벨 데드리프트 수정");val end=bounds("사이드 레터럴 레이즈 수정")
            gesture(start.exactCenterX(),start.exactCenterY(),end.exactCenterX(),end.exactCenterY(),800)
            Thread.sleep(500);capture("drag-stress-after")
            assertNull("드래그 종료가 수정 클릭으로 전달되면 안 됨",find("운동 수정"))
            assertEquals(listOf("플랭크","사이드 레터럴 레이즈","바벨 데드리프트"),TrexStore(context).loadPlan()!!.take(3).map{it.name})
            click("바벨 데드리프트 수정");await("운동 수정");assertNotNull(find("바벨 데드리프트"))
            assertNull(find("플랭크"));click("취소")
            val moved=bounds("바벨 데드리프트 수정");val first=bounds("플랭크 수정")
            gesture(moved.exactCenterX(),moved.exactCenterY(),first.exactCenterX(),first.exactCenterY(),800)
            assertEquals(names,TrexStore(context).loadPlan()!!.map{it.name})
            assertNull(find("운동 수정"));capture("drag-returned")
        }
    }

    @Test fun briefPreparationEntryShowsPanelOnlyForSkip() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("brief-entry","플랭크","90초 × 1세트","2분",true,"코어")))
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동");click("운동 시작");await("준비 건너뛰기",20000)
            click("일시정지");click("준비 건너뛰기")
            await("제어판 접기");capture("larger-preview")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동");click("운동 시작");await("5초 후 시작",20000)
            if (find("음성 안내 끄기") != null) click("음성 안내 끄기")
            click("5초 후 시작");await("제어판 열기",8000)
            assertNull(find("운동 준비"))
            assertTrue(find("제어판 접기")?.isVisibleToUser != true)
            capture("countdown-immersive")
        }
    }

    @Test fun cameraPreparationCancelsOnBackgroundAndStartsAtCountdownEnd() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("prepare-camera","플랭크","3초 × 1세트","99분",true,"코어")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click("운동");click("운동 시작");await("5초 후 시작",20000)
            await(com.example.trex_kotlin.posture.ExerciseProfiles.forName("플랭크")!!.preparationInstruction)
            assertNull(find("준비 시작"));capture("capture-preparation")
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1500);await("5초 후 시작");capture("capture-landscape")
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(1500);await("5초 후 시작")
            assertNull(find("이 세트 건너뛰기"))
            click("일시정지");await("재개");Thread.sleep(1000);assertNull(find("시작까지"));click("재개")
            click("5초 후 시작");await("시작까지")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            Thread.sleep(1500);scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            await("5초 후 시작");assertNull(find("시작까지"))
            click("5초 후 시작");click("카메라 전환");assertNull(find("시작까지"))
            click("음성 안내 끄기");await("음성 안내 켜기")
            click("5초 후 시작");capture("capture-countdown")
            assertNotNull(find("운동 준비"));assertNull(find("완료 세트"))
            await("제어판 열기",12000)
            assertNull(find("운동 준비"))
            await("완료 세트");assertTrue(TrexStore(context).loadPlan()!!.single().done)
        }
    }

    @Test fun preparationSkipStartsSameExerciseAndRecordModePersists() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("skip-preparation","플랭크","90초 × 2세트","99분",true,"코어")))
        val modes=com.example.trex_kotlin.posture.ModeStore(context)
        val before=modes.get("플랭크")
        try {
            modes.set("플랭크",com.example.trex_kotlin.posture.CoachMode.COACH)
            ActivityScenario.launch(MainActivity::class.java).use {
                click("운동");click("운동 시작");await("준비 건너뛰기",20000)
                click("기록 모드");await("처음 자세와의 변화를 비교해룡")
                assertEquals(com.example.trex_kotlin.posture.CoachMode.TRACK,modes.get("플랭크"))
                click("일시정지");capture("record-mode-preparation")
                click("준비 건너뛰기");await("이 세트 건너뛰기")
                assertNull(find("운동 준비"));assertNull(find("완료 세트"))
                assertNotNull(find("플랭크 · 1 / 2 세트"));assertFalse(TrexStore(context).loadPlan()!!.single().done)
                await("기록 모드");assertNull(find("처음 자세와의 변화를 비교해룡"));capture("record-mode-active")
                click("제어판 접기");await("제어판 열기")
                assertNotNull(find("목표 01:30"))
                click("제어판 열기");await("기록 모드")
                click("일시정지")
                click("자세 교정");await("자세 교정")
                assertEquals(com.example.trex_kotlin.posture.CoachMode.COACH,modes.get("플랭크"))
                click("기록 모드")
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                click("운동");click("운동 시작");await("준비 건너뛰기",20000)
                await("처음 자세와의 변화를 비교해룡")
                click("일시정지")
                click("준비 건너뛰기");await("이 세트 건너뛰기")
                assertNull(find("운동 준비"));assertFalse(TrexStore(context).loadPlan()!!.single().done)
            }
        } finally { modes.set("플랭크",before) }
    }

    @Test fun calendarRetentionPersistsAndDietWeekUsesActualDates() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        val today=java.time.LocalDate.now().toEpochDay()
        fun record(day:Long)=WorkoutHistoryDay(day,"","",listOf(WorkoutHistoryItem("보존 운동","1회 × 1세트",1,2)),1,2)
        store.saveHistory(listOf(today-30,today-7,today-6,today-1,today).map(::record))
        store.saveDiet(mapOf(today to mapOf("breakfast" to listOf(FoodEntry("오늘 식사",Nutrition(123,10.0,20.0,3.0)))),
            today-6 to mapOf("dinner" to listOf(FoodEntry("지난 식사",Nutrition(222,20.0,30.0,5.0),2)))))
        val prefs=context.getSharedPreferences("trex_store",Context.MODE_PRIVATE)
        val raw=org.json.JSONArray(prefs.getString("history",null))
        raw.getJSONObject(raw.length()-1).put("futureMetadata","보존")
        prefs.edit().putString("history",raw.toString()).commit()
        val foods=store.loadDiet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click("식단");click("최근 7일 식단 기록")
            await("오늘 식사");await("123 kcal")
            assertEquals(listOf(today-6,today-1,today),store.loadHistory()!!.map{it.epochDay})
            val previous=java.time.LocalDate.ofEpochDay(today-6)
            click("${previous.monthValue}월 ${previous.dayOfMonth}일 식단, 기록 있음")
            await("지난 식사 ×2");await("444 kcal");assertNull(find("오늘 식사"));capture("diet-week-record")
            val empty=java.time.LocalDate.ofEpochDay(today-5)
            click("${empty.monthValue}월 ${empty.dayOfMonth}일 식단, 기록 없음")
            await("기록된 식사가 없어요.");assertNull(find("지난 식사 ×2"));capture("diet-week-empty")
            click("식단으로");await("오늘 식단")
            scenario.onActivity { activity ->
                val vm=androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java]
                vm.refreshCalendar(today+1,resetPlan=false)
                assertTrue(vm.dietFor(0).values.all { it.isEmpty() })
            }
            assertEquals(listOf(today-1,today),store.loadHistory()!!.map{it.epochDay})
            assertEquals(foods,store.loadDiet())
            val retained=org.json.JSONArray(prefs.getString("history",null))
            assertEquals("보존",retained.getJSONObject(retained.length()-1).getString("futureMetadata"))
        }
    }

    @Test fun completedWorkoutHasVisibleCheckAndSummary() = isolated { store ->
        store.guideDone=true;store.loggedIn=true;store.onboarded=true;store.themeMode=ThemeMode.Light
        store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
        store.savePlan(listOf(Workout("done-a","기본 스쿼트","12회 × 3세트","8분",false,"하체",done=true),
            Workout("done-b","플랭크","30초 × 1세트","1분",false,"코어")))
        ActivityScenario.launch(MainActivity::class.java).use {
            click("운동");await("기본 스쿼트 완료");await(routineOverview(TrexStore(context).loadPlan()!!).detail);assertNull(find("2종목 중 1종목 완료"));capture("workout-completed")
            click("플랭크 수정");await("운동 수정");assertNull(find("기본 스쿼트"));click("취소")
        }
    }

    @Test fun configuredSetsRestPauseSkipAndAutoAdvanceUseRealApp() {
        check(context.packageName.endsWith(".replay")) { "분리된 재생 앱에서만 실행" }
        val prefs=context.getSharedPreferences("trex_store",Context.MODE_PRIVATE)
        val before=prefs.all.toMap()
        try {
            val store=TrexStore(context)
            store.guideDone=true;store.loggedIn=true;store.onboarded=true
            store.planDoneEpochDay=java.time.LocalDate.now().toEpochDay()
            val plan=listOf(Workout("flow-a","기본 스쿼트","2회 × 2세트","99분",false,"하체",secondsPerRep=3,restSeconds=4),
                Workout("flow-b","플랭크","60초 × 1세트","99분",true,"코어"))
            store.savePlan(plan)
            assertEquals(3,TrexStore(context).loadPlan()!!.first().secondsPerRep)
            assertEquals(4,TrexStore(context).loadPlan()!!.first().restSeconds)
            ActivityScenario.launch(MainActivity::class.java).use {
                click("운동");click("운동 시작")
                await("운동 준비")
                click("일시정지");Thread.sleep(5500)
                assertNotNull(find("운동 준비")) // 준비 일시정지는 자동 시작을 막는다.
                capture("session-prepare")
                click("재개")
                await("횟수 기록")
                Thread.sleep(7000) // 구형 2회×3초를 넘어도 끝나지 않는다.
                assertNotNull(find("횟수 기록"))
                capture("session-count")
                click("횟수 기록");click("기록 완료")
                await("세트 사이 휴식")
                click("일시정지")
                capture("session-rest")
                val clock=nodes().mapNotNull { it.text?.toString() }.first { Regex("\\d+:\\d{2}").matches(it) }
                Thread.sleep(1200);assertNotNull(find(clock));click("재개")
                await("횟수 기록") // 휴식만 0에서 자동으로 이어진다.
                click("이 세트 건너뛰기");await("이 세트를 건너뛸까요?");click("건너뛰기")
                await("몸 옆 · 낮게")
                capture("capture-floor-prepare")
                click("5초 후 시작")
                await("제어판 열기",15000);click("제어판 열기")
                await("이 세트 건너뛰기",15000)
                await("카메라 전환",20000)
                assertNull(find("운동 메뉴"));assertNull(find("측정 상세 · 촬영 안내"))
                click("일시정지")
                click("음성 안내 끄기");await("음성 안내 켜기")
                click("음성 안내 켜기");click("카메라 전환")
                capture("session-direct-controls")
                click("운동 종료");await("계속하기");click("계속하기")
                assertFalse(nodes().any { it.text?.contains("REC ")==true })
                // 이미 일시정지한 세트는 취소 후에도 멈춰 있어야 한다.
                val holdClock=nodes().mapNotNull { it.text?.toString() }.first { Regex("\\d+:\\d{2}").matches(it) }
                click("이 세트 건너뛰기")
                await("이 세트를 건너뛸까요?")
                capture("skip-confirm")
                Thread.sleep(1200);click("취소");await("재개");assertNotNull(find(holdClock))
                click("이 세트 건너뛰기");await("이 세트를 건너뛸까요?")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await("재개");assertNotNull(find(holdClock))
                // 진행 중에 확인창을 열면 타이머가 멈추고 취소하면 다시 진행한다.
                click("재개")
                val beforeAsk=nodes().mapNotNull { it.text?.toString() }.first { Regex("\\d+:\\d{2}").matches(it) }
                click("이 세트 건너뛰기");await("이 세트를 건너뛸까요?")
                Thread.sleep(3500);click("취소");await("일시정지")
                val resumedClock=nodes().mapNotNull { it.text?.toString() }.first { Regex("\\d+:\\d{2}").matches(it) }
                fun seconds(value: String) = value.split(":").let { it[0].toInt()*60+it[1].toInt() }
                assertTrue("확인창 대기 중 타이머 정지", seconds(beforeAsk)-seconds(resumedClock) in 0..2)
                Thread.sleep(1500);assertNull(find(resumedClock))
                click("이 세트 건너뛰기");await("이 세트를 건너뛸까요?");click("건너뛰기")
                await("완료 세트")
                assertEquals(1,TrexStore(context).loadHistory()!!.last().items.size)
                assertTrue(TrexStore(context).loadPlan()!!.none { it.done })
            }
        } finally {
            val edit=prefs.edit().clear()
            before.forEach { (key,value) -> when(value) {
                is String -> edit.putString(key,value); is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value); is Long -> edit.putLong(key,value); is Float -> edit.putFloat(key,value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>) }
            } }
            edit.commit()
        }
    }
}
