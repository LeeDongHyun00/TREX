package com.example.trex_kotlin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ExerciseGuidesTest {
    @Test fun differentEquipmentAndLungeVariantsNeverReuseTheSample() {
        listOf("바벨 컬", "바벨 스쿼트", "바벨 런지", "플랭크").forEach {
            assertNull(it, ExerciseGuides.forName(it))
        }
    }

    @Test fun firstGuideRequiresPreparationAndIsRememberedPerExercise() {
        assertNotNull(ExerciseGuides.introduction("덤벨 컬", true, emptySet()))
        assertNull(ExerciseGuides.introduction("덤벨 컬", false, emptySet()))
        assertNull(ExerciseGuides.introduction("덤벨 컬", true, setOf("덤벨 컬")))
        assertNotNull(ExerciseGuides.introduction("런지", true, setOf("덤벨 컬")))
        assertNull(ExerciseGuides.introduction(null, true, emptySet()))
    }

    @Test fun everyRegisteredGuideHasAPackagedGifAndMatchesTheCatalog() {
        val catalog = workoutCatalog.values.flatten().map { it.name }.toSet()
        ExerciseGuides.all.forEach { guide ->
            assertTrue(guide.name, guide.name in catalog)
            val bytes = File("src/main/assets/${guide.asset}").readBytes()
            assertTrue(guide.asset, bytes.size > 100)
            assertTrue(guide.asset, String(bytes.take(6).toByteArray(), Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
        }
    }
}
