package com.example.trex_kotlin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ExerciseGuidesTest {
    @Test fun differentEquipmentAndEngineAliasesNeverReuseTheSample() {
        listOf("바벨 컬", "바벨 스쿼트", "바벨 런지", "행잉 레그 레이즈", "라잉 레그 레이즈").forEach {
            assertNull(it, ExerciseGuides.forName(it))
        }
    }

    @Test fun firstGuideRequiresPreparationAndIsRememberedPerExercise() {
        listOf("덤벨 컬", "크런치", "레그 레이즈", "플랭크").forEach { name ->
            assertNotNull(name, ExerciseGuides.introduction(name, true, emptySet()))
            assertNull(name, ExerciseGuides.introduction(name, false, emptySet()))
            assertNull(name, ExerciseGuides.introduction(name, true, setOf(name)))
            assertNotNull(name, ExerciseGuides.introduction(name, true, setOf("런지")))
        }
        assertNotNull(ExerciseGuides.introduction("런지", true, setOf("덤벨 컬")))
        assertNull(ExerciseGuides.introduction(null, true, emptySet()))
    }

    @Test fun everyRegisteredGuideHasAPackagedVisualAndMatchesTheCatalog() {
        val catalog = workoutCatalog.values.flatten().map { it.name }.toSet()
        assertEquals(ExerciseGuides.all.size, ExerciseGuides.all.map { it.name }.toSet().size)
        assertEquals(ExerciseGuides.all.size, ExerciseGuides.all.map { it.asset }.toSet().size)
        ExerciseGuides.all.forEach { guide ->
            assertTrue(guide.name, guide.name in catalog)
            val bytes = File("src/main/assets/${guide.asset}").readBytes()
            assertTrue(guide.asset, bytes.size > 100)
            when (guide.asset.substringAfterLast('.')) {
                "gif" -> assertTrue(guide.asset,
                    String(bytes.take(6).toByteArray(), Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
                "png" -> assertArrayEquals(guide.asset,
                    byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.take(8).toByteArray())
                else -> fail("지원하지 않는 시범 형식: ${guide.asset}")
            }
        }
        assertEquals("exercise_guides/forearm-plank.png", ExerciseGuides.forName("플랭크")!!.asset)
    }

    /** Android 시범 GIF 는 프레임이 바뀔 때만 다시 그린다(스펙 §115) — 프레임 시각을 Movie(GIFMovie)와 같은 규칙으로 읽는지 잠근다. */
    @Test fun gifFrameTimesMatchTheMovieRuleForEveryPackagedGuide() {
        val gifs = ExerciseGuides.all.filter { it.asset.endsWith(".gif") }
        assertTrue(gifs.isNotEmpty())
        gifs.forEach { guide ->
            val ends = gifFrameEnds(File("src/main/assets/${guide.asset}").readBytes())
            assertNotNull(guide.asset, ends)
            // 지금 시범은 모두 2프레임, 프레임마다 0.9 또는 1.1 초
            assertEquals(guide.asset, 2, ends!!.size)
            assertTrue(guide.asset, ends[0] in setOf(900, 1100) && ends[1] == ends[0] * 2)
        }
        assertArrayEquals(intArrayOf(1100, 2200), gifFrameEnds(File("src/main/assets/exercise_guides/squat.gif").readBytes()))
        assertArrayEquals(intArrayOf(900, 1800), gifFrameEnds(File("src/main/assets/exercise_guides/crunch.gif").readBytes()))
    }

    @Test fun nextRedrawLandsExactlyOnTheNextFrameChange() {
        // GIFMovie: time 에 그리는 프레임 = 끝 ≥ time 인 첫 프레임, 시각은 (재생 시간 % 길이)
        fun frameAt(ends: IntArray, time: Int) = ends.indexOfFirst { it >= time }
        listOf(intArrayOf(900, 1800), intArrayOf(1100, 2200), intArrayOf(100, 100, 350)).forEach { ends ->
            val duration = ends.last()
            for (time in 0 until duration) {
                val wait = gifMillisUntilNextFrame(ends, time)!!
                assertTrue(wait > 0)
                // 기다리는 동안은 같은 프레임이고(다시 그릴 필요가 없고), 기다린 뒤에는 다른 프레임이다
                for (s in 1 until wait) assertEquals(frameAt(ends, time), frameAt(ends, (time + s) % duration))
                assertNotEquals(frameAt(ends, time), frameAt(ends, (time + wait) % duration))
            }
        }
        assertEquals(901, gifMillisUntilNextFrame(intArrayOf(900, 1800), 0))
        assertEquals(1, gifMillisUntilNextFrame(intArrayOf(900, 1800), 900))
        assertEquals(899, gifMillisUntilNextFrame(intArrayOf(900, 1800), 901))
        // 한 프레임·길이 0 은 바뀔 일이 없다
        assertNull(gifMillisUntilNextFrame(intArrayOf(500), 10))
        assertNull(gifMillisUntilNextFrame(intArrayOf(0, 0), 0))
    }

    @Test fun unreadableGifFallsBackToEveryFrameRedraw() {
        val squat = File("src/main/assets/exercise_guides/squat.gif").readBytes()
        assertNull(gifFrameEnds(byteArrayOf(1, 2, 3)))
        assertNull(gifFrameEnds(squat.copyOf(1000))) // 첫 그림 압축 자료 도중에 끊김
        assertNull(gifFrameEnds("PNG_NOT_A_GIF_AT_ALL".toByteArray()))
    }
}
