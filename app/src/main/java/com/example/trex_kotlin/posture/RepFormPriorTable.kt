package com.example.trex_kotlin.posture

/**
 * 생성 파일 — `research/external_rep_replay/rep_priors.py --write` 가 쓴다. 손으로 고치지 않는다(spec §90).
 * 반복 검사의 모집단 사전값: 앱과 같은 검사기로 재생한 정상 반복에서 사람(세트)마다 수렴한 본인 기준의 분포(뷰별).
 * 데이터: MM-Fit(스쿼트·런지·덤벨 컬) + REHAB24-6('올바름', 스쿼트·런지). 생성 2026-10-09.
 */
internal object RepFormPriorTable {
    val table: Map<String, Map<String, RepFormPrior>> = mapOf(
        "repform|덤벨 컬|상체 숙임" to mapOf(
            "C" to RepFormPrior(10.0135f, 6.8530f, -2.2898f, 40.8520f, 3.5863f, 109.9288f, 15),
        ),
        "repform|덤벨 컬|팔꿈치 높이 상승" to mapOf(
            "C" to RepFormPrior(-0.4966f, 0.0086f, -0.5120f, -0.4599f, -0.5137f, -0.2532f, 10),
        ),
        "repform|덤벨 컬|팔꿈치 몸에서 떨어짐" to mapOf(
            "B" to RepFormPrior(0.2209f, 0.0544f, 0.1452f, 0.3472f, 0.1497f, 0.4555f, 31),
            "D" to RepFormPrior(0.1980f, 0.0703f, -0.0187f, 0.3552f, -0.2600f, 0.4555f, 38),
        ),
        "repform|덤벨 컬|팔꿈치 앞 이탈" to mapOf(
            "B" to RepFormPrior(-0.0819f, 0.0054f, -0.0950f, -0.0630f, -0.1038f, -0.0560f, 31),
            "D" to RepFormPrior(-0.0804f, 0.0075f, -0.0961f, -0.0465f, -0.1038f, 0.1384f, 38),
        ),
        "repform|덤벨 컬|팔꿈치 옆 벌림" to mapOf(
            "C" to RepFormPrior(0.1628f, 0.0312f, 0.0360f, 0.2056f, 0.0424f, 0.5210f, 14),
        ),
        "repform|바벨 런지|상체 숙임" to mapOf(
            "B" to RepFormPrior(7.7960f, 2.5994f, 2.6523f, 14.5952f, 2.6328f, 33.3810f, 29),
            "D" to RepFormPrior(7.5584f, 2.2203f, -0.2204f, 14.4056f, 1.0098f, 49.2024f, 36),
            "SIDE_B" to RepFormPrior(0.3155f, 4.6642f, -6.1722f, 15.4775f, -4.1560f, 19.1343f, 16),
            "SIDE_D" to RepFormPrior(1.2001f, 5.4071f, -6.5437f, 15.8490f, -4.1560f, 19.1343f, 19),
        ),
        "repform|바벨 스쿼트|몸통 좌우 기울기" to mapOf(
            "C" to RepFormPrior(0.0687f, 1.0667f, -4.7313f, 2.4440f, -23.2101f, 8.5709f, 38),
        ),
        "repform|바벨 스쿼트|무릎 바깥 벌림" to mapOf(
            "C" to RepFormPrior(0.1058f, 0.1112f, -0.1921f, 0.3556f, -0.1259f, 0.5146f, 32),
        ),
        "repform|바벨 스쿼트|상체 숙임" to mapOf(
            "C" to RepFormPrior(6.1614f, 2.7394f, -0.4747f, 18.4886f, 5.5683f, 63.2761f, 38),
        ),
        "repform|바벨 컬|상체 숙임" to mapOf(
            "C" to RepFormPrior(10.0135f, 6.8530f, -2.2898f, 40.8520f, 3.5863f, 109.9288f, 15),
        ),
        "repform|바벨 컬|팔꿈치 높이 상승" to mapOf(
            "C" to RepFormPrior(-0.4966f, 0.0086f, -0.5120f, -0.4599f, -0.5137f, -0.2532f, 10),
        ),
        "repform|바벨 컬|팔꿈치 몸에서 떨어짐" to mapOf(
            "B" to RepFormPrior(0.2209f, 0.0544f, 0.1452f, 0.3472f, 0.1497f, 0.4555f, 31),
            "D" to RepFormPrior(0.1980f, 0.0703f, -0.0187f, 0.3552f, -0.2600f, 0.4555f, 38),
        ),
        "repform|바벨 컬|팔꿈치 앞 이탈" to mapOf(
            "B" to RepFormPrior(-0.0819f, 0.0054f, -0.0950f, -0.0630f, -0.1038f, -0.0560f, 31),
            "D" to RepFormPrior(-0.0804f, 0.0075f, -0.0961f, -0.0465f, -0.1038f, 0.1384f, 38),
        ),
        "repform|바벨 컬|팔꿈치 옆 벌림" to mapOf(
            "C" to RepFormPrior(0.1628f, 0.0312f, 0.0360f, 0.2056f, 0.0424f, 0.5210f, 14),
        ),
        "repform|스텝 포워드 다이나믹 런지|상체 숙임" to mapOf(
            "B" to RepFormPrior(7.7960f, 2.5994f, 2.6523f, 14.5952f, 2.6328f, 33.3810f, 29),
            "D" to RepFormPrior(7.5584f, 2.2203f, -0.2204f, 14.4056f, 1.0098f, 49.2024f, 36),
            "SIDE_B" to RepFormPrior(0.3155f, 4.6642f, -6.1722f, 15.4775f, -4.1560f, 19.1343f, 16),
            "SIDE_D" to RepFormPrior(1.2001f, 5.4071f, -6.5437f, 15.8490f, -4.1560f, 19.1343f, 19),
        ),
    )
}
