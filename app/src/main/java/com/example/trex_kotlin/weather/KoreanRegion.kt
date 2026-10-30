package com.example.trex_kotlin.weather

/**
 * Dashboard regions: keep the existing 13 representative areas and allow
 * users to expand a representative area to inspect detailed cities.
 * Coordinates are representative query points, not administrative boundaries.
 */
enum class RegionGroup(val label: String) {
    CAPITAL("수도권"),
    GANGWON("강원권"),
    CHUNGCHEONG("충청권"),
    GYEONGSANG("경상권"),
    JEOLLA("전라권"),
    JEJU("제주권")
}

sealed interface KoreanRegion {
    val id: String
    val displayName: String
    val latitude: Double
    val longitude: Double
    val parentId: String?

    data class Representative(
        override val id: String,
        override val displayName: String,
        override val latitude: Double,
        override val longitude: Double,
        val group: RegionGroup
    ) : KoreanRegion {
        override val parentId: String? = null
    }

    data class Detailed(
        override val id: String,
        override val displayName: String,
        override val latitude: Double,
        override val longitude: Double,
        override val parentId: String,
        val representativeId: String
    ) : KoreanRegion
}

object KoreanRegions {
    val representatives: List<KoreanRegion.Representative> = listOf(
        KoreanRegion.Representative("SEOUL", "서울", 37.5665, 126.9780, RegionGroup.CAPITAL),
        KoreanRegion.Representative("GYEONGGI", "경기", 37.4138, 127.5183, RegionGroup.CAPITAL),
        KoreanRegion.Representative("INCHEON", "인천", 37.4563, 126.7052, RegionGroup.CAPITAL),
        KoreanRegion.Representative("BUSAN", "부산", 35.1796, 129.0756, RegionGroup.GYEONGSANG),
        KoreanRegion.Representative("DAEGU", "대구", 35.8714, 128.6014, RegionGroup.GYEONGSANG),
        KoreanRegion.Representative("DAEJEON", "대전", 36.3504, 127.3845, RegionGroup.CHUNGCHEONG),
        KoreanRegion.Representative("GWANGJU", "광주", 35.1595, 126.8526, RegionGroup.JEOLLA),
        KoreanRegion.Representative("ULSAN", "울산", 35.5384, 129.3114, RegionGroup.GYEONGSANG),
        KoreanRegion.Representative("GANGWON", "강원", 37.8228, 128.1555, RegionGroup.GANGWON),
        KoreanRegion.Representative("CHUNGCHEONG", "충청", 36.5184, 127.2000, RegionGroup.CHUNGCHEONG),
        KoreanRegion.Representative("JEOLLA", "전라", 35.0000, 127.0000, RegionGroup.JEOLLA),
        KoreanRegion.Representative("GYEONGSANG", "경상", 36.0000, 128.5000, RegionGroup.GYEONGSANG),
        KoreanRegion.Representative("JEJU", "제주", 33.4996, 126.5312, RegionGroup.JEJU)
    )

    val details: List<KoreanRegion.Detailed> = listOf(
        KoreanRegion.Detailed("SUWON", "수원", 37.2636, 127.0286, "GYEONGGI", "GYEONGGI"),
        KoreanRegion.Detailed("SEONGNAM", "성남", 37.4449, 127.1389, "GYEONGGI", "GYEONGGI"),
        KoreanRegion.Detailed("GOYANG", "고양", 37.6584, 126.8320, "GYEONGGI", "GYEONGGI"),
        KoreanRegion.Detailed("YONGIN", "용인", 37.2411, 127.1776, "GYEONGGI", "GYEONGGI"),
        KoreanRegion.Detailed("CHUNCHEON", "춘천", 37.8813, 127.7298, "GANGWON", "GANGWON"),
        KoreanRegion.Detailed("GANGNEUNG", "강릉", 37.7519, 128.8761, "GANGWON", "GANGWON"),
        KoreanRegion.Detailed("CHEONGJU", "청주", 36.6424, 127.4890, "CHUNGCHEONG", "CHUNGCHEONG"),
        KoreanRegion.Detailed("CHEONAN", "천안", 36.8151, 127.1139, "CHUNGCHEONG", "CHUNGCHEONG"),
        KoreanRegion.Detailed("CHUNGJU", "충주", 36.9910, 127.9259, "CHUNGCHEONG", "CHUNGCHEONG"),
        KoreanRegion.Detailed("POHANG", "포항", 36.0190, 129.3435, "GYEONGSANG", "GYEONGSANG"),
        KoreanRegion.Detailed("CHANGWON", "창원", 35.2281, 128.6811, "GYEONGSANG", "GYEONGSANG"),
        KoreanRegion.Detailed("GIMHAE", "김해", 35.2285, 128.8894, "GYEONGSANG", "GYEONGSANG"),
        KoreanRegion.Detailed("JEONJU", "전주", 35.8242, 127.1480, "JEOLLA", "JEOLLA"),
        KoreanRegion.Detailed("MOKPO", "목포", 34.8118, 126.3922, "JEOLLA", "JEOLLA"),
        KoreanRegion.Detailed("YEOSU", "여수", 34.7604, 127.6622, "JEOLLA", "JEOLLA"),
        KoreanRegion.Detailed("SEOGWIPO", "서귀포", 33.2541, 126.5601, "JEJU", "JEJU")
    )

    val all: List<KoreanRegion> get() = representatives + details

    fun childrenOf(parentId: String): List<KoreanRegion.Detailed> =
        details.filter { it.parentId == parentId }

    fun fromId(id: String?): KoreanRegion =
        all.firstOrNull { it.id == id }
            ?: representatives.first { it.id == "SEOUL" }

    fun fromName(name: String?): KoreanRegion =
        all.firstOrNull { it.displayName == name }
            ?: representatives.first { it.id == "SEOUL" }
}
