package com.trex.engine

import org.junit.Assert.*
import org.junit.Test

/** 매핑·관측 계약과 합성 궤적의 연결 검증. 실제 사람의 횟수 정확도 검증이 아니다. */
class ExerciseRepProfilesTest {
    private val expected = setOf("바벨 스쿼트", "스텝 포워드 다이나믹 런지", "바벨 런지", "사이드 런지", "크로스 런지",
        "바벨 데드리프트", "굿모닝", "힙쓰러스트", "오버 헤드 프레스", "랫풀 다운", "딥스", "덤벨 컬", "바벨 컬",
        "사이드 레터럴 레이즈", "프런트 레이즈", "업라이트로우", "푸시업", "니푸쉬업", "Y - Exercise", "플랭크",
        "스탠딩 사이드 크런치", "스탠딩 니업", "행잉 레그 레이즈", "크런치", "라잉 레그 레이즈", "시저크로스")
    private val wave = listOf(0f, 0f, 0f, .4f, .8f, 1.2f, 1.4f, 1.4f, 1f, .6f, .2f, 0f, 0f)
    private fun profile(name: String) = checkNotNull(ExerciseRepProfiles.forExercise(name))

    @Test fun allOriginal26HaveExplicitVariantsLimitationsAndPreviewEvidenceState() {
        assertEquals(26, ExerciseRepProfiles.all.size)
        assertEquals(expected, ExerciseRepProfiles.all.map { it.exercise }.toSet())
        for (p in ExerciseRepProfiles.all) {
            assertTrue(p.variant.isNotBlank())
            assertTrue(p.limitations.isNotBlank())
            assertTrue(p.countDefinition.isNotBlank())
            assertFalse(p.validated)
            for (s in listOfNotNull(p.commonSignal, p.leftSignal, p.rightSignal)) {
                assertTrue(s.minAmp.isFinite() && s.minAmp > 0)
                assertNull(s.romThreshold)
                assertNull(s.romDirection)
                assertFalse(s.romValidated)
                assertFalse(s.validated)
            }
            if (p.isometric) {
                assertEquals("플랭크", p.exercise)
                assertTrue(p.allowedPatterns.isEmpty())
                assertNull(p.createTracker())
            } else {
                assertTrue(p.defaultPattern in p.allowedPatterns)
                for (pattern in p.allowedPatterns) assertNotNull(p.createTracker(pattern))
            }
        }
    }

    @Test fun appAliasesResolveWithoutInventingProfilesForUnregisteredExercises() {
        assertEquals("오버 헤드 프레스", profile("오버헤드 프레스").exercise)
        assertEquals("힙쓰러스트", profile("힙 쓰러스트").exercise)
        assertEquals("스텝 포워드 다이나믹 런지", profile("런지").exercise)
        assertEquals("푸시업", profile("푸쉬업").exercise)
        assertEquals("니푸쉬업", profile("니 푸쉬업").exercise)
        assertEquals("Y - Exercise", profile("Y 레이즈").exercise)
        assertNull(ExerciseRepProfiles.forExercise("알 수 없는 운동"))
    }

    @Test fun everyDynamicProfileCanConnectAVisibleCompleteSyntheticCycleToItsTracker() {
        for (p in ExerciseRepProfiles.all.filterNot { it.isometric }) {
            val tracker = p.createTracker()!!
            val signals = if (p.commonSignal != null) listOfNotNull(p.commonSignal) else listOfNotNull(p.leftSignal, p.rightSignal)
            for ((i, step) in wave.withIndex()) {
                val f = p.requiredFeatures().associateWith { 1f }.toMutableMap()
                for (s in signals) {
                    val anchor = s.startMin?.let { it + if(s.minAmp < 1) .4f else 15f } ?: s.startMax?.let { it - s.minAmp } ?: 10f
                    f[s.feature] = anchor + step * s.minAmp * if(s.outboundSign == -1) -1 else 1
                    for((key,amp) in s.supportingMotion) f[key] = step*amp*2
                    s.signChangeFeature?.let { f[it]=if(step>.7f) -.2f else .2f }
                }
                val observed = p.observe(f, view = if (p.floor) null else ViewEstimator.ViewClass.C, qualityOk = true)
                assertTrue(p.exercise, observed.accepted)
                tracker.onFrame(i * 400L, observed.features)
            }
            val each = p.defaultPattern == RepMovementPattern.ALTERNATING_EACH && p.commonSignal == null
            assertEquals("합성 경로: ${p.exercise}", if (each) 2 else 1, tracker.counts.total)
        }
    }

    @Test fun oppositePhaseCurlsAreNotLostToAnUnchangingMean() {
        val p = profile("덤벨 컬")
        assertEquals("elbow_L", p.leftSignal!!.feature)
        assertEquals("elbow_R", p.rightSignal!!.feature)
        assertNull(p.commonSignal)
        val tracker = p.createTracker(RepMovementPattern.ALTERNATING_EACH)!!
        for ((i, step) in wave.withIndex()) {
            val left = 170f - step * 45f
            val right = 50f + step * 45f
            assertEquals(110f, (left + right) / 2, .001f)
            val observation = p.observe(mapOf("elbow_L" to left, "elbow_R" to right),
                view = ViewEstimator.ViewClass.C, qualityOk = true)
            tracker.onFrame(i * 400L, observation.features)
        }
        // 오른팔은 굽힌 상태에서 시작했다. 처음 관측한 중간 구간을 완전 반복으로 세지 않는다.
        assertEquals(1, tracker.counts.total)
        assertEquals(1, tracker.counts.left)
        assertEquals(0, tracker.counts.right)
    }

    @Test fun alternatingKneeUpsKeepHipSidesAndRequireKneeObservation() {
        val p = profile("스탠딩 니업")
        assertEquals("hip_L", p.leftSignal!!.feature)
        assertEquals("hip_R", p.rightSignal!!.feature)
        val missing = p.observe(mapOf("hip_L" to 100f, "hip_R" to 170f, "knee_h_R" to -.5f),
            view = ViewEstimator.ViewClass.B, qualityOk = true)
        assertTrue(missing.accepted)
        assertFalse(missing.features.containsKey("hip_L"))
        assertEquals(170f, missing.features["hip_R"]!!, 0f)
    }

    @Test fun missingContralateralLandmarksAreNeverFilledWithZeroOrTheVisibleValue() {
        val p = profile("덤벨 컬")
        val observation = p.observe(mapOf("elbow_L" to 80f, "elbow_R" to Float.NaN, "elbow_mean" to 125f),
            view = ViewEstimator.ViewClass.C, qualityOk = true)
        assertTrue(observation.accepted)
        assertEquals(80f, observation.features["elbow_L"]!!, 0f)
        assertFalse(observation.features.containsKey("elbow_R"))
    }

    @Test fun lungeKneesBendingTogetherProduceOneUnattributedCycle() {
        for (name in listOf("스텝 포워드 다이나믹 런지", "바벨 런지", "사이드 런지", "크로스 런지")) {
            val p = profile(name)
            assertFalse(p.allowedPatterns.contains(RepMovementPattern.SIMULTANEOUS))
            val tracker = p.createTracker()!!
            for ((i, step) in wave.withIndex()) {
                val angle = 170f - step * 45f
                val features = mapOf("knee_L" to angle, "knee_R" to angle, "knee_mean" to angle, "knee_minside" to angle)
                tracker.onFrame(i * 400L, p.observe(features, view = ViewEstimator.ViewClass.C, qualityOk = true).features)
            }
            assertEquals(name, 1, tracker.counts.total)
            assertEquals(name, 1, tracker.counts.unknown)
            assertEquals(name, 0, tracker.counts.left)
            assertEquals(name, 0, tracker.counts.right)
            assertEquals(RepSideAttribution.USER_DECLARED_LEAD, p.sideAttribution)
        }
    }

    @Test fun declaredLungeLeadUsesOnlyTheChosenAnatomicalKnee() {
        val p = profile("크로스 런지")
        val tracker = p.createTracker(RepMovementPattern.LEFT_ONLY)!!
        for ((i, step) in wave.withIndex()) tracker.onFrame(i * 400L,
            mapOf("knee_L" to 170f - step * 45f, "knee_R" to 170f - step * 45f))
        assertEquals(1, tracker.counts.left)
        assertEquals(0, tracker.counts.right)
        assertEquals(1, tracker.counts.total)
        assertTrue(p.limitations.contains("사용자가 선택"))
    }

    @Test fun standingSideCrunchArmOnlyAndSideBendOnlyDoNotBecomeKneeLiftRepetitions() {
        val p = profile("스탠딩 사이드 크런치")
        val tracker = p.createTracker()!!
        for ((i, step) in wave.withIndex()) {
            val observed = p.observe(mapOf("knee_h_L" to -.5f, "knee_h_R" to -.5f,
                "knee_elbow_dist_L" to 1.8f - step, "knee_elbow_dist_R" to 1.8f, "torso_roll" to step * 20f),
                view = ViewEstimator.ViewClass.C, qualityOk = true)
            tracker.onFrame(i * 400L, observed.features)
        }
        assertEquals(0, tracker.counts.total)
    }

    @Test fun standingSideCrunchKneeLiftAndReturnCanBeObservedWithoutCallingItCorrectForm() {
        val p = profile("스탠딩 사이드 크런치")
        val tracker = p.createTracker()!!
        for ((i, step) in wave.withIndex()) {
            val observed = p.observe(mapOf("knee_h_L" to -.5f + step * .25f, "knee_h_R" to -.5f,
                "knee_elbow_dist_L" to 1.8f - step * .5f, "knee_elbow_dist_R" to 1.8f, "torso_roll" to step * 10f),
                view = ViewEstimator.ViewClass.C, qualityOk = true)
            tracker.onFrame(i * 400L, observed.features)
        }
        assertEquals(1, tracker.counts.left)
        assertEquals(0, tracker.counts.right)
        assertFalse(p.validated)
        assertTrue(p.limitations.contains("정답이나 교정 성공을 인증하지 않습니다"))
    }

    @Test fun observationQualityAndViewAreRequiredAndFloorDoesNotFakeAStandingYaw() {
        val p = profile("덤벨 컬")
        val frame = mapOf("elbow_L" to 170f, "elbow_R" to 170f)
        for (view in listOf(null, ViewEstimator.ViewClass.UNKNOWN, ViewEstimator.ViewClass.SIDE_B)) {
            assertFalse(p.observe(frame, view = view, qualityOk = true).accepted)
        }
        assertFalse(p.observe(frame, view = ViewEstimator.ViewClass.C, qualityOk = false).accepted)
        assertFalse(p.observe(frame, view = ViewEstimator.ViewClass.D, qualityOk = true).accepted)
        assertTrue(p.observe(frame, RepMovementPattern.LEFT_ONLY, ViewEstimator.ViewClass.D, true).accepted)
        val floor = profile("푸시업")
        assertTrue(floor.observe(mapOf("wrist_shoulder_d" to 1f, "visible_elbow_angle" to 170f), view = null, qualityOk = true).accepted)
        assertFalse(floor.observe(emptyMap(), view = null, qualityOk = true).accepted)
        assertFalse(floor.observe(mapOf("wrist_shoulder_d" to 1f), view = null, qualityOk = false).accepted)
    }
}
