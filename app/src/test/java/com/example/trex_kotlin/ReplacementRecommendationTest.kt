package com.example.trex_kotlin

import org.junit.Assert.*
import org.junit.Test

class ReplacementRecommendationTest {
    @Test fun groupsKeepSourceOrderRemoveCurrentAndUnknownAndDeduplicateWithinSource() {
        val groups = replacementRecommendationGroups("런지",
            listOf("런지", "푸쉬업", "푸쉬업", "unknown", "크런치"),
            listOf("크런치", "덤벨 컬", "덤벨 컬"))
        assertEquals(listOf("날씨 기반 추천 운동", "근육 피로도 기반 추천 운동"), groups.map { it.title })
        assertEquals(listOf("푸쉬업", "크런치"), groups[0].workouts.map { it.name })
        assertEquals(listOf("크런치", "덤벨 컬"), groups[1].workouts.map { it.name })
    }

    @Test fun missingSourcesDoNotInventRecommendationsOrEmptyHeadings() {
        assertTrue(replacementRecommendationGroups("런지", listOf("런지"), emptyList()).isEmpty())
        val groups = replacementRecommendationGroups(null, emptyList(), listOf("덤벨 컬"))
        assertEquals(listOf("근육 피로도 기반 추천 운동"), groups.map { it.title })
        assertEquals("덤벨 컬", groups.single().workouts.single().name)
    }
}
