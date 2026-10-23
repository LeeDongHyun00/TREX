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
}
