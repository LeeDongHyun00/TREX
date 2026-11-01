package com.example.trex_kotlin.posture

import org.junit.Assert.*
import org.junit.Test

/** §133: 사용자 맨몸 굿모닝 영상 18~33초의 실제 추론 피처 재생. 영상/모델 해시는 별도 검증 문서에 기록한다. */
class GoodMorningVideoTest {
    @Test fun normalReturnsCountAndSquatExamplesDoNotBecomeReps() {
        // 시각, 관측한 몸통 신호, 보이는 오른 무릎. 26~31초의 XXX 스쿼트 시범은 굿모닝 왕복이 아니다.
        val frames=arrayOf(
            floatArrayOf(18031f,176.72398f,174.35715f),
            floatArrayOf(18328f,167.02885f,167.10204f),
            floatArrayOf(18631f,147.72458f,155.54347f),
            floatArrayOf(18934f,131.57095f,152.83842f),
            floatArrayOf(19273f,121.60317f,153.6439f),
            floatArrayOf(19564f,119.364746f,151.60236f),
            floatArrayOf(19861f,136.52263f,153.81142f),
            floatArrayOf(20164f,163.47923f,170.49287f),
            floatArrayOf(20455f,177.3073f,179.00475f),
            floatArrayOf(20770f,176.50763f,179.74055f),
            floatArrayOf(21061f,157.59113f,166.53606f),
            floatArrayOf(21364f,139.07779f,154.34871f),
            floatArrayOf(21661f,123.49072f,152.90508f),
            floatArrayOf(21970f,120.62056f,151.75111f),
            floatArrayOf(22261f,120.84265f,150.60649f),
            floatArrayOf(22595f,143.46362f,154.20508f),
            floatArrayOf(22898f,173.07626f,175.65791f),
            floatArrayOf(23195f,179.49559f,176.96184f),
            floatArrayOf(23431f,176.84106f,179.24748f),
            floatArrayOf(23728f,155.1082f,160.66386f),
            floatArrayOf(24037f,134.79787f,153.04082f),
            floatArrayOf(24328f,123.29024f,151.63539f),
            floatArrayOf(24631f,122.145065f,151.25386f),
            floatArrayOf(24928f,125.76018f,151.48608f),
            floatArrayOf(25231f,147.77557f,155.01143f),
            floatArrayOf(25546f,175.22575f,177.2644f),
            floatArrayOf(25831f,177.61636f,177.38304f),
            floatArrayOf(26128f,173.3921f,177.21701f),
            floatArrayOf(26431f,157.69281f,142.06313f),
            floatArrayOf(26728f,148.65866f,113.49489f),
            floatArrayOf(27025f,148.02728f,102.80748f),
            floatArrayOf(27328f,149.72429f,110.75941f),
            floatArrayOf(27631f,163.33463f,143.56679f),
            floatArrayOf(27928f,174.52666f,177.41223f),
            floatArrayOf(28231f,175.58601f,177.57336f),
            floatArrayOf(28528f,167.56454f,160.34479f),
            floatArrayOf(28831f,154.79611f,131.9411f),
            floatArrayOf(29165f,148.07196f,111.85311f),
            floatArrayOf(29462f,148.10715f,103.34482f),
            floatArrayOf(29765f,150.65193f,114.42494f),
            floatArrayOf(30062f,162.94516f,144.53671f),
            floatArrayOf(30365f,174.34053f,173.18187f),
            floatArrayOf(30662f,176.83298f,177.13393f),
            floatArrayOf(30965f,175.54645f,179.02461f),
            floatArrayOf(31231f,161.75354f,165.64606f),
            floatArrayOf(31522f,141.19286f,153.90335f),
            floatArrayOf(31898f,127.10661f,150.54613f),
            floatArrayOf(32110f,122.91464f,151.24411f),
            floatArrayOf(32498f,128.85306f,151.93643f),
            floatArrayOf(32795f,152.12871f,156.34637f)
        )
        val counter=RepCounter.forSession("굿모닝",floor=false)!!
        val evaluator=RepFormSpecs.evaluatorFor("굿모닝",counter)!!
        val reps=mutableListOf<RepFormRep>()
        for (row in frames) {
            val t=row[0].toLong()
            val features=mapOf(FourExerciseForm.GOOD_MORNING_SIGNAL to row[1], StandingFormGeometry.knee("R") to row[2])
            evaluator.onFrame(t,features)
            if(counter.onFrameFeatures(t,features)) reps+=evaluator.onCycle(t,counter.lastCycleMin,counter.lastCycleMax)
        }
        assertEquals(3,counter.reps)
        assertTrue(reps.all {it.correct})
        assertTrue(counter.repTimesMs.all {it<26431L})
        // 가려진 왼쪽은 정상으로 꾸미지 않는다.
        assertTrue(reps.all {FourExerciseForm.unobservedNote(it)!=null})
    }
}
