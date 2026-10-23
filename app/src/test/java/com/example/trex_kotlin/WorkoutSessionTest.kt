package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class WorkoutSessionTest {
    @Test fun autoAdvanceWaitsForSpeechAtMostTwoSeconds() {
        // §63: 목표 도달 뒤 마지막 판정·안내 발화를 최대 2 s 기다린다 — 최소 0.3 s, 두 번 연달아 조용해야
        assertFalse(AdvanceHold.due(299, 5))
        assertTrue(AdvanceHold.due(300, 2))
        assertFalse(AdvanceHold.due(1_999, 1))
        assertTrue(AdvanceHold.due(2_000, 0))
    }

    @Test fun observedOnlyExerciseKeepsPersonalMeasurementsInHistoryWithoutAccuracy() {
        val report=com.example.trex_kotlin.posture.PostureSetReport.build("id","벽 푸쉬업","벽 푸쉬업",
            com.example.trex_kotlin.posture.CoachMode.COACH,20,false,emptyList(),emptyList(),null,null,null,
            measurements=listOf("초반보다 왼쪽 팔꿈치의 움직임 범위가 줄었어요"))
        val correction=report.toCorrection()
        assertTrue(correction.focus.contains("왼쪽 팔꿈치"))
        assertEquals("unjudged",correction.kind);assertNull(report.accuracy)
    }
    private fun workout(reps: String = "12회 × 3세트", rest: Int = 60) =
        Workout("squat","기본 스쿼트",reps,"999분",false,"하체",secondsPerRep=4,restSeconds=rest)

    @Test fun timeComesFromRepsAndOnlyTheGapsBetweenSets() {
        val timing=workout().timing()
        assertEquals(48,timing.workSeconds);assertEquals(264,timing.totalSeconds)
        assertEquals(5,workout().durationMinutes())
    }
    @Test fun lungeRepIsALeftPlusRightPairSoItsPaceIsTwoSteps() {
        // 사용자 결정(2026-09-24): 런지류 1회 = 왼쪽 + 오른쪽 한 번씩(두 걸음). 한 걸음 4초 × 2 = 8초 — 목표 10회 = 20걸음 = 80초.
        for (name in listOf("런지","바벨 런지","사이드 런지","크로스 런지")) {
            assertEquals(name,8,WorkoutPacing.secondsPerRep(name,"하체"))
            assertEquals(name,80,Workout("l",name,"10회 × 3세트","999분",false,"하체").timing().workSeconds)
        }
        // 교대 동작이어도 평균 신호로 한 사이클 = 1회인 종목과 다른 종목은 그대로
        assertEquals(3,WorkoutPacing.secondsPerRep("덤벨 컬","상체"))
        assertEquals(6,WorkoutPacing.secondsPerRep("스탠딩 니업","코어"))   // §97: 니업·사이드 크런치도 왼 + 오른 = 1회(두 번 들기)
        assertEquals(4,WorkoutPacing.secondsPerRep("기본 스쿼트","하체"))
        assertEquals(4,WorkoutPacing.secondsPerRep("불가리안 스플릿 스쿼트","하체"))   // 카탈로그 밖 이름(프로필 없음) = 사이클 단위
        // 사용자가 정한 1회 시간은 그대로 쓴다
        assertEquals(40,Workout("l","런지","10회 × 1세트","999분",false,"하체",secondsPerRep=4).timing().workSeconds)
    }
    /**
     * 사용자 결정(2026-09-24): "두 쪽 진행이 전부 완료되어야지" — 카운터 사이클(걸음) → 표시 단위(RepUnitAccumulator.onCounterFrame, PostureLive 분석
     * 루프가 부르는 그 함수) → onRepDetected 와 같은 +1 → SessionProgress.targetReached(자동 진행 조건)의 실제 경로를 잇는다.
     * [name] 은 앱 운동 이름(프로필), [aihub] 는 카운터 종목(PostureLive 의 postureExerciseMap 값).
     * @return 걸음(반복)마다 그 뒤의 목표 도달 여부.
     */
    private fun autoAdvanceAfterEachMovement(name: String, target: Int, movements: Int, pauseAfter: Int? = null, aihub: String = name): List<Boolean> {
        val steps=buildSessionSteps(listOf(Workout("w",name,"${target}회 × 1세트","999분",false,"하체")))
        val work=steps.first{it.phase==SessionPhase.WORK}
        var p=SessionProgress(work.token,0)
        val counter=com.example.trex_kotlin.posture.RepCounter.forSession(aihub,floor=false)!!
        val acc=com.example.trex_kotlin.posture.RepUnitAccumulator(
            com.example.trex_kotlin.posture.RepUnit.forSession(com.example.trex_kotlin.posture.ExerciseProfiles.forName(name),floor=false))
        val records=ArrayList<com.example.trex_kotlin.posture.RepRecord>()
        var t=0L
        fun frame(v: Float) {
            if(counter.onFrame(t,v)) {
                val tally=com.example.trex_kotlin.posture.RepUnitAccumulator.onCounterFrame(counter,t,records,acc)
                repeat(tally.completedReps){ p=p.setRepetitions(steps,work.token,p.repetitions+1) }   // TrexApp onRepDetected
            }
            t+=300L
        }
        repeat(8){frame(170f)}
        return (1..movements).map { k ->
            for(i in 0..8) frame(170f-75f*kotlin.math.sin(Math.PI*i/8).toFloat())
            repeat(5){frame(170f)}
            if(pauseAfter==k){counter.resetCycle();acc.onCounterCycleReset();repeat(6){frame(170f)}}
            p.targetReached(work)
        }
    }
    @Test fun lungeAutoAdvanceWaitsForTheSecondSideOfTheLastPair() {
        // 목표 3회 = 왼 3 + 오른 3: 5걸음째(마지막 쌍의 첫 쪽)까지는 넘어가지 않고 6걸음째에 넘어간다
        assertEquals(listOf(false,false,false,false,false,true),autoAdvanceAfterEachMovement("바벨 런지",3,6))
        assertEquals(listOf(false,false,false,false,false,true),autoAdvanceAfterEachMovement("런지",3,6,aihub="스텝 포워드 다이나믹 런지"))
        // 쪽 사이 일시정지는 끝낸 첫 쪽을 지운다거나 한 걸음을 1회로 세지 않는다
        assertEquals(listOf(false,false,false,true),autoAdvanceAfterEachMovement("바벨 런지",2,4,pauseAfter=1))
        // 사이클 단위 종목은 반복마다 1회 — 목표 2회는 둘째 반복에서
        assertEquals(listOf(false,true,true),autoAdvanceAfterEachMovement("바벨 스쿼트",2,3))
    }
    @Test fun holdDurationIsNotMultipliedByRepPace() {
        assertEquals(30,workout("30초 × 1세트").timing().totalSeconds)
        assertEquals(360,workout("전신 6분").timing().workSeconds)
        assertEquals(1,workout("전신 6분").setDraft().sets)
        assertEquals(420,workout("3분 × 2세트").timing().totalSeconds)
        assertEquals("3분",buildSessionSteps(listOf(workout("3분 × 2세트")))[1].workout.repsSpec().targetLabel)
    }
    @Test fun scheduleHasEverySetAndNoFinalRest() {
        val steps=buildSessionSteps(listOf(workout()))
        assertEquals(listOf(SessionPhase.PREPARE,SessionPhase.WORK,SessionPhase.REST,SessionPhase.WORK,SessionPhase.REST,SessionPhase.WORK),steps.map{it.phase})
        assertEquals(3,steps.filter{it.phase==SessionPhase.WORK}.map{it.workout.id}.distinct().size)
        assertEquals(listOf(2,3),steps.filter{it.phase==SessionPhase.REST}.map{it.setNumber})
    }
    @Test fun zeroRestAndSingleSetDoNotCreateEmptyTimers() {
        assertEquals(4,buildSessionSteps(listOf(workout(rest=0))).size)
        assertEquals(2,buildSessionSteps(listOf(workout("8회 × 1세트"))).size)
    }
    @Test fun elapsedClockUsesMillisecondsAndPauseDoesNotConsumeTime() {
        val p=SessionProgress(0,5000).tick(1200,false)
        assertEquals(4,p.secondsLeft);assertEquals(1200L,p.elapsedMs)
        assertEquals(p,p.tick(9999,true));assertEquals(0,p.tick(9999,false).secondsLeft)
    }
    @Test fun staleTimeoutCannotAdvanceTwiceAfterSkip() {
        val steps=buildSessionSteps(listOf(workout()))
        val p=SessionProgress(1,10).advance(steps,1,true)
        assertEquals(2,p.index);assertEquals(p,p.advance(steps,1,false))
        assertTrue(p.completed.isEmpty());assertEquals(setOf(1),p.skipped)
    }
    @Test fun preparationAndRepetitionsNeverExpire() {
        val steps = buildSessionSteps(listOf(workout()))
        var p = SessionProgress(0, 0).tick(600000, false, steps[0].timed, false)
        assertFalse(p.targetReached(steps[0])); assertEquals(0L, p.elapsedMs)
        p = p.advance(steps, 0, false)
        p = p.tick(600000, false, steps[1].timed)
        assertFalse(p.targetReached(steps[1])); assertEquals(600000L, p.elapsedMs)
        p = p.setRepetitions(steps, 1, 12)
        assertTrue(p.targetReached(steps[1]))
        p = p.advance(steps, 1, false)
        assertEquals(SessionPhase.REST, steps[p.index].phase)
        p = p.tick(60000, false, steps[p.index].timed)
        assertTrue(p.targetReached(steps[p.index]))
    }
    @Test fun countsAreEditableAndStaleEventsCannotAffectNextSet() {
        val steps = buildSessionSteps(listOf(workout()))
        var p = SessionProgress(1,0).setRepetitions(steps,1,12).setRepetitions(steps,1,8)
        assertFalse(p.targetReached(steps[1]))
        p = p.advance(steps,1,true)
        assertTrue(p.completed.isEmpty())
        assertEquals("8회 × 1세트",p.completedWorkouts(steps).single().reps)
        assertEquals(p,p.setRepetitions(steps,1,99))
        assertTrue(p.completedOriginalIds(steps).isEmpty())
    }
    @Test fun exitingKeepsPartialCountWithoutCertifyingCompletion() {
        val steps=buildSessionSteps(listOf(workout()))
        val p=SessionProgress(1,0).setRepetitions(steps,1,7).captureCount(steps[1])
        assertEquals("7회 × 1세트",p.completedWorkouts(steps).single().reps)
        assertTrue(p.completed.isEmpty());assertFalse(p.completedWorkouts(steps).single().done)
        assertTrue(p.setRepetitions(steps,1,0).captureCount(steps[1]).completedWorkouts(steps).isEmpty())
    }
    @Test fun explicitTargetKeepsTimeIndependentFromObservationAndLegacyText() {
        val time = workout().copy(target=WorkoutTarget.Duration(10))
        val steps=buildSessionSteps(listOf(time))
        assertTrue(steps[1].timed)
        assertEquals("10초", steps[1].workout.repsSpec().targetLabel)
        val p=SessionProgress(1,10000).tick(10000,false,steps[1].timed)
        assertTrue(p.targetReached(steps[1]))
        assertEquals(p,p.setRepetitions(steps,1,12))
        assertFalse(steps[0].timed)
    }
    /**
     * 플랭크 목표 = 카메라가 확인한 플랭크 시간(spec §99, 사용자 결정 Q2 2026-10-06 밤) — 남은 시간은 인정 시간의 증분으로만 줄고 세션 경과는 벽시계 그대로다.
     * 세트 가동 시간(기록의 플랭크 초·근육 부하)은 인정 시간 — 무릎을 대거나 화면 밖에 있던 시간을 플랭크로 기록하지 않는다(리뷰 2026-10-07). 증분이 없으면(null) 종전 벽시계.
     */
    @Test fun plankRemainingTimeFollowsCameraHeldTime() {
        val p=SessionProgress(0,45_000)
        val a=p.tick(1000,false,timed=true,trackWork=true,heldDeltaMs=0)
        assertEquals("확인 못 한 1 s 는 목표에서 빼지 않는다",45_000L,a.remainingMs)
        assertEquals(1000L,a.elapsedMs);assertEquals("확인 못 한 1 s 는 가동 시간도 아니다",0L,a.workMillis[0])
        val b=a.tick(1000,false,timed=true,trackWork=true,heldDeltaMs=700)
        assertEquals(44_300L,b.remainingMs);assertEquals(2000L,b.elapsedMs);assertEquals(700L,b.workMillis[0])
        assertEquals("종전 경로의 가동 시간은 그대로(목표에서 자른 벽시계)",1000L,p.tick(1000,false,timed=true,trackWork=true).workMillis[0])
        assertEquals("반복 목표는 벽시계",1000L,p.tick(1000,false,timed=false,trackWork=true).workMillis[0])
        assertEquals("음수 증분은 0",b.remainingMs,b.tick(100,false,timed=true,heldDeltaMs=-500).remainingMs)
        assertEquals("남은 시간보다 큰 증분은 자른다",0L,b.tick(100,false,timed=true,heldDeltaMs=99_000).remainingMs)
        assertEquals("일시정지는 아무것도 줄이지 않는다",b,b.tick(1000,true,timed=true,heldDeltaMs=1000))
        assertEquals("증분이 없으면 종전 벽시계",43_300L,b.tick(1000,false,timed=true).remainingMs)
        assertEquals(p.tick(1200,false),p.tick(1200,false,heldDeltaMs=null))
    }

    @Test fun holdBridgeCarriesCameraTimeAndFallsBackToTheClock() {
        val br=HoldBridge()
        assertNull("묶이지 않은 세트는 벽시계",br.take("plank::set:1",100))
        br.bind("plank::set:1")
        assertEquals(0L,br.take("plank::set:1",100))
        br.publish("plank::set:1",1500,camera=true)
        assertEquals(1500L,br.take("plank::set:1",100));assertEquals(0L,br.take("plank::set:1",100))
        assertNull("다른 세트",br.take("plank::set:2",100))
        br.publish("plank::set:2",9000,camera=true)
        assertEquals("다른 세트의 소식은 버린다",0L,br.take("plank::set:1",100))
        assertTrue(br.usesCamera)
        // 폴백(20 s 동안 한 번도 확인 못 함) — 그 세트를 처음부터 시계로 잰 것과 같다: 그동안의 벽시계를 한 번 넘기고 이후 벽시계
        val f=HoldBridge();f.bind("p")
        repeat(200){f.publish("p",0,camera=true);assertEquals(0L,f.take("p",100))}
        f.publish("p",0,camera=false)
        assertEquals(20_100L,f.take("p",100));assertEquals(100L,f.take("p",100));assertFalse(f.usesCamera)
        // 분석 소식이 20 s 넘게 끊김(카메라 정지) — 이미 확인한 시간이 있으면 소급 없이 그 뒤만 벽시계
        val s=HoldBridge();s.bind("s");s.publish("s",3000,camera=true);assertEquals(3000L,s.take("s",100))
        repeat(199){assertEquals(0L,s.take("s",100))}
        assertEquals(100L,s.take("s",100));assertEquals(100L,s.take("s",100))
        assertTrue("벽시계로 넘어간 세트 — 화면·리포트가 '카메라 미확인' 을 밝힌다",s.clockFor("s"));assertFalse(s.clockFor("x"))
        s.release("s");assertNull(s.take("s",100));assertFalse(s.clockFor("s"))
        // 한 번도 확인 못 한 채 분석 소식이 끊김 — 넘어가는 틱에 그 틱을 두 번 줄이지 않는다(리뷰 2026-10-07: 소급분에 이번 틱이 이미 들어 있었다)
        val q=HoldBridge();q.bind("q")
        var sum=0L;repeat(201){sum+=q.take("q",100)!!}
        assertEquals("넘긴 합 = 흐른 벽시계",20_100L,sum);assertTrue(q.clockFor("q"))
        // 세트 마감(종료 확인 취소 포함) — 다리를 풀면 그 세트는 벽시계(종전)로 이어진다. 묶어 두면 20 s 동안 0 을 넘겼다
        val e=HoldBridge();e.bind("e");e.publish("e",35_000,camera=true);assertEquals(35_000L,e.take("e",100))
        e.release("e");assertNull(e.take("e",100))
        assertEquals(HoldHud.CLOCK_STATUS,HoldHud(35,40,false,"대기",camera=true).bridgedToClock().status)
        assertFalse(HoldHud(35,40,true,null,camera=true).bridgedToClock().camera)
        // 세션 진행과 이으면 목표는 인정 시간이 채울 때만 닿는다
        val steps=buildSessionSteps(listOf(Workout("pl","플랭크","3초 × 1세트","1분",false,"코어")))
        val work=steps.first{it.phase==SessionPhase.WORK}
        var p=SessionProgress(work.token,work.seconds*1000L)
        val g=HoldBridge();g.bind(work.workout.id)
        var held=0L
        for(i in 1..60){ if(i>20) held+=100; g.publish(work.workout.id,held,camera=true)
            p=p.tick(100,false,timed=work.timed,trackWork=true,heldDeltaMs=g.take(work.workout.id,100)) }
        assertTrue(p.targetReached(work));assertEquals("가동 시간 = 인정 시간(목표 3 s)",3000L,p.workMillis[work.token])
    }

    @Test fun floorScopeLineIsSpentOnlyWhenSpoken() {
        // 리뷰 2026-10-07: 플랭크는 범위 문장을 '시작' 사건 뒤에 붙여 말하는데 WORK 시작에 30분 간격을 먼저 소진해, 시작 사건이 없던 세트(폴백) 뒤로 한 번도 안 들렸다
        FloorScopeVoice.forget("플랭크")
        val line=FloorScopeVoice.peek("플랭크",0)
        assertNotNull(line);assertEquals("peek 는 소진하지 않는다",line,FloorScopeVoice.peek("플랭크",1_000))
        FloorScopeVoice.mark("플랭크",2_000)
        assertNull("말한 뒤 30분은 다시 안 함",FloorScopeVoice.peek("플랭크",60_000))
        assertNotNull(FloorScopeVoice.peek("플랭크",2_000+30*60_000L+1))
        FloorScopeVoice.forget("크런치")
        assertNotNull(FloorScopeVoice.take("크런치",0));assertNull(FloorScopeVoice.take("크런치",1_000))
        assertNull("다른 종목은 범위 문장 없음",FloorScopeVoice.peek("푸시업",0))
        FloorScopeVoice.forget("플랭크");FloorScopeVoice.forget("크런치")
    }

    @Test fun skippingASetDoesNotCertifyTheWholeExerciseCompleted() {
        val steps=buildSessionSteps(listOf(workout("8회 × 2세트")))
        var p=SessionProgress(0,1)
        while(p.index>=0){val i=p.index;p=p.setRepetitions(steps,i,if(i==1)0 else 8);p=p.advance(steps,i,i==1)}
        assertEquals(1,p.completedWorkouts(steps).size);assertTrue(p.completedOriginalIds(steps).isEmpty())
    }
}
