package com.example.trex_kotlin.posture

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 플랫폼 경계 회귀: 실제 패키징 규칙으로 전 종목을 열고 빈 입력/중단·발화 대기열·얼굴 피처·플랭크 시계·관절 게이트를 검증한다. */
class IosEngineTest {
    private fun engine() = IosEngine(File("src/main/assets/posture/rules_mp_v0.json").readText(), File("src/main/assets/posture/rules_floor_v0.json").readText())
    private fun frames(status: String) = JSONObject(status)
    private fun speechOf(status: String): List<String> = frames(status).getJSONArray("speech").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("text") } }

    @Test fun all26ExercisesConfigureAndMissingBodyNeverReceivesScore() {
        ExerciseProfiles.all.forEach { p ->
            val engine=engine(); engine.configure(Json.encode(mapOf("name" to p.name,"mode" to "coach","target" to 10)))
            engine.arm(1000); engine.manualStart(1000)
            engine.tick(6000)
            for(i in 0..50) engine.frame(Json.encode(mapOf("timeMs" to 6300+i*300,"width" to 720,"height" to 1280)))
            val report=JSONObject(engine.finish(22000))
            assertTrue(p.name,report.isNull("score"));assertEquals(0,report.getInt("shipJudged"));assertEquals(0,report.getInt("reps"))
            assertFalse(report.getString("summary").contains("깨끗"))
        }
    }
    @Test fun trackAlsoKeepsZeroJudgementsUnscored() {
        val engine=engine();engine.configure("{\"name\":\"기본 스쿼트\",\"mode\":\"track\"}")
        val result=JSONObject(engine.finish(1000)); assertTrue(result.isNull("score"));assertEquals(0,result.getInt("duration"))
    }
    @Test fun malformedInputIsRecoverableException() {
        val e=engine();assertThrows(Exception::class.java) { e.configure("{\"name\":\"없는 운동\"}") }
        assertThrows(Exception::class.java) { e.frame("[]") }
    }
    /** 검출된 프레임(33관절) — 사람이 없는 프레임은 Android 처럼 세트 로그에 들어가지 않는다. */
    private fun body(t: Long) = Json.encode(mapOf("timeMs" to t,"width" to 720,"height" to 1280,"xy" to List(66){0.5},"world" to List(99){0.0},"visibility" to List(33){1f}))
    @Test fun pauseResumeDoesNotCountAndStaleInputDoesNotEnterLog() {
        val engine=engine();engine.configure("{\"name\":\"덤벨 컬\"}");engine.arm(1000);engine.manualStart(1000);engine.tick(6000)
        engine.frame(body(7000));engine.frame(body(6900))
        engine.pause(true, 7100);engine.frame(body(7300));engine.pause(false, 7350)
        assertEquals(1,engine.exportFrames().lines().map(::JSONObject).count { it.optString("kind") == "ios_frame" })
        assertEquals(0,JSONObject(engine.finish(7400)).getInt("reps"))
    }
    @Test fun undetectedFramesAreNotLoggedAndDoNotCount() {
        val engine=engine();engine.configure("{\"name\":\"덤벨 컬\"}");engine.manualStart(1000);engine.tick(6000)
        engine.frame("{\"timeMs\":7000}")
        assertEquals(0,engine.exportFrames().lines().map(::JSONObject).count { it.optString("kind") == "ios_frame" })
    }
    @Test fun jsonRoundTripPreservesKoreanEscapesAndNull() {
        val text=Json.encode(mapOf("운동" to "말\"\\\n", "유보" to null,"배열" to listOf(1,2,3)))
        val decoded=JSONObject(text);assertEquals("말\"\\\n",decoded.getString("운동"));assertTrue(decoded.isNull("유보"));assertEquals(3,decoded.getJSONArray("배열").length())
    }
    @Test fun insufficientBaselineIsNotFabricated() {
        val e=engine();e.configure("{\"name\":\"기본 스쿼트\"}")
        assertEquals(emptyMap<String,Any?>(),JSONObject(e.finish(1000)).optJSONObject("baselineValues")!!.values())
    }
    @Test fun eightyFiveMsInputOnlyJudgesFirstSampleOfEachThreeHundredMsBin() {
        val e=engine();e.configure("{\"name\":\"덤벨 컬\"}");e.manualStart(1000);e.tick(6000)
        listOf(6010L,6095L,6180L,6265L,6350L,6435L,6520L,6605L).forEach { e.frame(body(it)) }
        val frames=e.exportFrames().lines().map(::JSONObject).filter { it.optString("kind") == "ios_frame" }
        assertEquals(listOf(10L,350L,605L),frames.map { it.getLong("t_ms") })
    }
    @Test fun baselineRequiresThreeSetsButKeepsFeaturesPresentInTwoOfThem() {
        val builder=IosBaseline()
        assertEquals(emptyMap<String,Any?>(),JSONObject(builder.build("[{\"x\":10},{\"x\":20}]",3)).values())
        val ready=JSONObject(builder.build("[{\"x\":10,\"y\":1},{\"x\":20},{\"z\":3}]",3))
        assertEquals(15.0,ready.getDouble("x"),0.0);assertFalse(ready.has("y"));assertFalse(ready.has("z"))
    }
    @Test fun swiftJsonBoundaryPreservesResearchWorldCoordinatesAndUpSanity() {
        val lines=javaClass.classLoader!!.getResourceAsStream("posture_port_fixture.txt")!!.bufferedReader().readLines()
        var joints=mutableMapOf<String,Vec3>();var expected=mutableMapOf<String,Float>();var cases=0
        for(line in lines) {
            val p=line.trim().split(" ")
            when(p[0]) {
                "CASE" -> { joints=mutableMapOf();expected=mutableMapOf() }
                "J" -> joints[p[1]]=Vec3(p[2].toFloat(),p[3].toFloat(),p[4].toFloat())
                "F" -> expected[p[1]]=p[2].toFloat()
                "END" -> {
                    val world=MutableList<Float?>(99) { null };val visible=MutableList(33) { 0f }
                    fun put(i:Int,v:Vec3) { if(world[i*3]==null) { world[i*3]=v.x/100;world[i*3+1]=-v.y/100;world[i*3+2]=-v.z/100;visible[i]=1f } }
                    Joints.SINGLE.forEach { (name,i) -> joints[name]?.let { put(i,it) } }
                    Joints.PAIR.forEach { (name,pair) -> joints[name]?.let { put(pair.first,it);put(pair.second,it) } }
                    for(up in listOf(listOf(0,1,0),listOf(0,-1,0))) {
                        // 접힌/누운 표본은 중력 부호를 몸 위치로 검증할 수 없다. 정본이 반전을 확인한 표본만 대조한다.
                        if(up[1]<0 && !checkUpSanity(joints,Vec3(0f,-1f,0f)).flipped) continue
                        val e=engine();e.configure("{\"name\":\"기본 스쿼트\"}");e.manualStart(1000);e.tick(6000)
                        val xy = List(66) { 0.5 }
                        e.frame(Json.encode(mapOf("timeMs" to 6300,"width" to 720,"height" to 1280,"xy" to xy,"world" to world,"visibility" to visible,"up" to up)))
                        val actual=JSONObject(e.exportFrames().lines().last()).optJSONObject("features")!!
                        for((key,value) in expected) {
                            assertTrue("사례 $cases / $key",actual.has(key))
                            assertEquals("사례 $cases / $key / up=$up",value.toDouble(),actual.getDouble(key),maxOf(.05,kotlin.math.abs(value)*.001))
                        }
                    }
                    cases++
                }
            }
        }
        assertTrue(cases>=10)
    }
    @Test fun allCatalogNamesCanCalculateLoadAndRecommendationsRespectEquipment() {
        val load=IosLoad()
        for(p in ExerciseProfiles.all) {
            val input=Json.encode(listOf(mapOf("id" to p.name,"name" to p.name,"endedAt" to 1000,"reps" to 5,"duration" to 30)))
            val result=JSONObject(load.snapshot(input,1000,"[]"))
            assertTrue(p.name,result.has("values"))
            val recommendations=result.getJSONArray("recommendations")
            for(i in 0 until recommendations.length()) assertEquals(com.example.trex_kotlin.trainingload.Equipment.NONE,
                com.example.trex_kotlin.trainingload.MuscleLoadCatalog.find(recommendations.getString(i))!!.equipment)
        }
    }

    // ---- §107f: 발화 대기열·준비 안내·얼굴·플랭크·관절 게이트 ----
    @Test fun preparationIntroIsPlannedByEssentialLineFirstAndOneSubmitAtATime() {
        PreparationIntro.forget("기본 스쿼트")
        val e=engine();e.configure("{\"name\":\"기본 스쿼트\",\"voiceReady\":true}");e.arm(1000)
        val first=speechOf(e.tick(1100))
        assertEquals(1,first.size); assertEquals(ExerciseProfiles.forName("기본 스쿼트")!!.preparationLines().first().text,first[0])
        // 재생 중에는 다음 문장을 내지 않는다 — 종결이 와야 다음 문장
        assertTrue(speechOf(e.tick(1300)).isEmpty())
        e.speechStarted(1300); val next=JSONObject("{\"a\":"+e.speechFinished(2500)+"}").getJSONArray("a")
        val after=speechOf(e.tick(2600))
        assertTrue("둘째 문장은 종결 뒤 한 번만", next.length() + after.size <= 1)
    }
    @Test fun coachingSentenceGoesThroughQueueAndRecoveryRequiresHeardComplaint() {
        val e=engine();e.configure("{\"name\":\"기본 스쿼트\",\"muted\":false}")
        e.speech.speakLatest("무릎을 벌려 주세요.", 1000, heardKey = "rf:x")
        assertEquals(1, e.speech.drain().size)
        assertFalse("시작도 못 한 지적은 교정을 내지 않는다", e.speech.recoverable("rf:x"))
        e.speech.onStart(1050); e.speech.onDone(1090)
        e.speech.speakLatest("무릎을 벌려 주세요.", 1100, heardKey = "rf:y"); assertEquals(1, e.speech.drain().size); e.speech.onStart(1200)
        assertTrue("재생을 시작한 지적만 교정 문장을 낸다", e.speech.recoverable("rf:y"))
        // 재생 중 들어온 둘째 코칭은 보류 하나 — 끊지도 줄 세우지도 않는다
        e.speech.speakLatest("허리를 펴 주세요.", 1300); assertTrue(e.speech.drain().isEmpty()); e.speech.onDone(1400); assertEquals("허리를 펴 주세요.", e.speech.drain().single().item.text)
        e.speech.stop(); assertFalse(e.speech.busy(1500))
    }
    @Test fun mutedEngineEmitsNoSpeechAndSetEndAdvancesWithinTwoSeconds() {
        val e=engine();e.configure("{\"name\":\"덤벨 컬\",\"muted\":true}");e.arm(1000);e.manualStart(1000)
        assertTrue(speechOf(e.tick(1200)).isEmpty())
        e.beginSetEnd(5000); assertFalse(e.advanceDue(5100)); assertTrue(e.advanceDue(5400))
        val f=engine();f.configure("{\"name\":\"덤벨 컬\"}");f.speech.speakLatest("x",1000);f.speech.drain();f.speech.onStart(1000);f.beginSetEnd(1000)
        assertFalse(f.advanceDue(1500)); assertTrue("상한 2 s", f.advanceDue(3100))
    }
    @Test fun faceMatrixFromSwiftBecomesGazeFeaturesOnlyForFloorExercises() {
        val e=engine();e.configure("{\"name\":\"크런치\"}");e.manualStart(1000);e.tick(6000)
        // 행 우선 4×4, 앞 방향 z = +1 → 요 +90(카메라를 봄)
        val m=listOf(1,0,0,5, 0,1,0,5, 0,0,1,30, 0,0,0,1)
        e.frame(Json.encode(mapOf("timeMs" to 6300,"width" to 720,"height" to 1280,"xy" to List(66){0.5},"world" to List(99){0.0},"visibility" to List(33){1f},"face" to mapOf("found" to true,"matrix" to m,"inferMs" to 12))))
        val f=JSONObject(e.exportFrames().lines().last()).optJSONObject("features")!!
        assertEquals(1.0,f.getDouble("face_found"),0.0); assertEquals(90.0,f.getDouble("face_yaw"),0.5)
        val s=engine();s.configure("{\"name\":\"기본 스쿼트\"}");s.manualStart(1000);s.tick(6000)
        s.frame(Json.encode(mapOf("timeMs" to 6300,"width" to 720,"height" to 1280,"xy" to List(66){0.5},"world" to List(99){0.0},"visibility" to List(33){1f},"face" to mapOf("found" to true,"matrix" to m))))
        assertFalse("서서 하는 종목에는 얼굴 피처를 얹지 않는다(Android faceEnabled = floor)", JSONObject(s.exportFrames().lines().last()).optJSONObject("features")!!.has("face_yaw"))
    }
    @Test fun faceCropSpecFollowsNeckDirectionAndNeedsHeadAndShoulder() {
        val xy=MutableList(66){Double.NaN}; val vis=MutableList(33){0f}
        fun put(i:Int,x:Double,y:Double){xy[i*2]=x;xy[i*2+1]=y;vis[i]=1f}
        put(0,0.5,0.2);put(7,0.45,0.22);put(8,0.55,0.22);put(11,0.4,0.4);put(12,0.6,0.4)
        val spec=JSONObject(IosFace.cropSpec(Json.encode(mapOf("xy" to xy,"visibility" to vis,"width" to 1000,"height" to 1000))))
        assertEquals(0.0,spec.getDouble("rotateDeg"),1e-3); assertTrue(spec.getDouble("side")>120.0); assertEquals(256,spec.getInt("outPx"))
        assertEquals("null", IosFace.cropSpec(Json.encode(mapOf("xy" to List(66){Double.NaN},"visibility" to List(33){0f},"width" to 10,"height" to 10))))
    }
    @Test fun plankSessionExposesHoldClockAndNeverScoresWithoutBody() {
        val e=engine();val cfg=JSONObject(e.configure("{\"name\":\"플랭크\",\"target\":30,\"timed\":true}"))
        assertTrue(cfg.optBoolean("hold")); e.manualStart(1000); e.tick(6000)
        val st=frames(e.frame("{\"timeMs\":6300,\"width\":720,\"height\":1280}"))
        val hold=st.optJSONObject("hold")!!; assertEquals(0,hold.getInt("heldSec")); assertTrue(hold.optBoolean("stopped"))
        assertFalse("시간 목표는 엔진이 done 을 내지 않는다", st.optBoolean("done"))
        val r=JSONObject(e.finish(40000)); assertTrue(r.isNull("score")); assertEquals(0,r.getInt("heldSec"))
    }
    @Test fun jointGateOutputIsPerFrameAndHidesUnreliableLegs() {
        val e=engine();e.configure("{\"name\":\"런지\"}");e.manualStart(1000);e.tick(6000)
        val vis=MutableList(33){1f}; vis[25]=0.6f
        val st=frames(e.frame(Json.encode(mapOf("timeMs" to 6300,"width" to 720,"height" to 1280,"xy" to List(66){0.5},"world" to List(99){0.0},"visibility" to vis))))
        val shown=st.getJSONArray("shown"); assertEquals(33,shown.length())
        assertFalse("가시성 0.6 의 무릎은 그리지 않는다", (shown.values()[25] as Boolean))
        assertTrue(shown.values()[11] as Boolean)
    }
}
