package com.example.trex_kotlin.posture

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 플랫폼 경계 회귀: 실제 패키징 규칙으로 전 종목을 열고 빈 입력/중단을 검증한다. */
class IosEngineTest {
    private fun engine() = IosEngine(File("src/main/assets/posture/rules_mp_v0.json").readText(), File("src/main/assets/posture/rules_floor_v0.json").readText())
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
    @Test fun pauseResumeDoesNotCountAndStaleInputDoesNotEnterLog() {
        val engine=engine();engine.configure("{\"name\":\"덤벨 컬\"}");engine.arm(1000);engine.manualStart(1000);engine.tick(6000)
        engine.frame("{\"timeMs\":7000}");engine.frame("{\"timeMs\":6900}")
        engine.pause(true);engine.frame("{\"timeMs\":7300}");engine.pause(false)
        assertEquals(1,engine.exportFrames().lines().map(::JSONObject).count { it.optString("kind") == "ios_frame" })
        assertEquals(0,JSONObject(engine.finish(7400)).getInt("reps"))
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
        listOf(6010L,6095L,6180L,6265L,6350L,6435L,6520L,6605L).forEach { e.frame("{\"timeMs\":$it}") }
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
                        e.frame(Json.encode(mapOf("timeMs" to 6300,"width" to 720,"height" to 1280,"world" to world,"visibility" to visible,"up" to up)))
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
}
