// tools/sync_ios_core.py 생성본 — Android 정본에서 수정한 뒤 동기화하세요.
package com.example.trex_kotlin.posture

/**
 * 생성 파일 — `research/external_rep_replay/rep_priors.py --write` 가 쓴다. 손으로 고치지 않는다(spec §90).
 * 반복 검사의 모집단 사전값: 앱과 같은 검사기로 재생한 정상 반복에서 사람(세트)마다 수렴한 본인 기준의 분포(뷰별).
 * 데이터: MM-Fit(스쿼트·런지·덤벨 컬) + REHAB24-6('올바름', 스쿼트·런지). 생성 2026-09-29.
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
            "B" to RepFormPrior(0.2209f, 0.0526f, 0.1423f, 0.3463f, 0.1469f, 0.4555f, 31),
            "D" to RepFormPrior(0.2011f, 0.0552f, -0.0111f, 0.3476f, -0.3337f, 0.4555f, 37),
        ),
        "repform|덤벨 컬|팔꿈치 앞 이탈" to mapOf(
            "B" to RepFormPrior(-0.0819f, 0.0048f, -0.0947f, -0.0648f, -0.1038f, -0.0546f, 31),
            "D" to RepFormPrior(-0.0804f, 0.0072f, -0.0959f, -0.0480f, -0.1038f, 0.1384f, 38),
        ),
        "repform|덤벨 컬|팔꿈치 옆 벌림" to mapOf(
            "C" to RepFormPrior(0.1628f, 0.0312f, 0.0360f, 0.2056f, 0.0424f, 0.5210f, 14),
        ),
        "repform|바벨 런지|상체 숙임" to mapOf(
            "B" to RepFormPrior(7.7671f, 2.2650f, 2.8195f, 17.9597f, 2.4953f, 27.9928f, 30),
            "D" to RepFormPrior(7.7314f, 2.2765f, -0.2485f, 17.9758f, 1.0934f, 32.1102f, 37),
            "SIDE_B" to RepFormPrior(1.0858f, 4.0338f, -5.8570f, 15.1623f, -4.1560f, 19.1343f, 18),
            "SIDE_D" to RepFormPrior(2.2861f, 4.1755f, -5.9278f, 15.2331f, -4.1560f, 19.1343f, 21),
        ),
        "repform|바벨 런지|어깨 기울기" to mapOf(
            "B" to RepFormPrior(-0.0753f, 0.0233f, -0.1533f, 0.0293f, -0.1324f, 0.0687f, 25),
        ),
        "repform|바벨 스쿼트|몸통 좌우 기울기" to mapOf(
            "C" to RepFormPrior(0.0687f, 1.0667f, -4.7313f, 2.4440f, -23.2101f, 8.5709f, 38),
        ),
        "repform|바벨 스쿼트|발 간격" to mapOf(
            "C" to RepFormPrior(0.0851f, 0.0329f, 0.0162f, 0.1629f, 0.0328f, 0.2008f, 38),
        ),
        "repform|바벨 스쿼트|발끝 방향" to mapOf(
            "C" to RepFormPrior(17.1384f, 8.0972f, 0.2364f, 40.8098f, 4.4253f, 65.0069f, 37),
        ),
        "repform|바벨 스쿼트|상체 숙임" to mapOf(
            "C" to RepFormPrior(6.1614f, 2.7394f, -0.4747f, 18.4886f, 5.5683f, 63.2761f, 38),
        ),
        "repform|바벨 스쿼트|좁음" to mapOf(
            "C" to RepFormPrior(0.0851f, 0.0329f, 0.0162f, 0.1629f, 0.0328f, 0.2008f, 38),
        ),
        "repform|바벨 컬|상체 숙임" to mapOf(
            "C" to RepFormPrior(10.0135f, 6.8530f, -2.2898f, 40.8520f, 3.5863f, 109.9288f, 15),
        ),
        "repform|바벨 컬|팔꿈치 높이 상승" to mapOf(
            "C" to RepFormPrior(-0.4966f, 0.0086f, -0.5120f, -0.4599f, -0.5137f, -0.2532f, 10),
        ),
        "repform|바벨 컬|팔꿈치 몸에서 떨어짐" to mapOf(
            "B" to RepFormPrior(0.2209f, 0.0526f, 0.1423f, 0.3463f, 0.1469f, 0.4555f, 31),
            "D" to RepFormPrior(0.2011f, 0.0552f, -0.0111f, 0.3476f, -0.3337f, 0.4555f, 37),
        ),
        "repform|바벨 컬|팔꿈치 앞 이탈" to mapOf(
            "B" to RepFormPrior(-0.0819f, 0.0048f, -0.0947f, -0.0648f, -0.1038f, -0.0546f, 31),
            "D" to RepFormPrior(-0.0804f, 0.0072f, -0.0959f, -0.0480f, -0.1038f, 0.1384f, 38),
        ),
        "repform|바벨 컬|팔꿈치 옆 벌림" to mapOf(
            "C" to RepFormPrior(0.1628f, 0.0312f, 0.0360f, 0.2056f, 0.0424f, 0.5210f, 14),
        ),
        "repform|스텝 포워드 다이나믹 런지|상체 숙임" to mapOf(
            "B" to RepFormPrior(7.7671f, 2.2650f, 2.8195f, 17.9597f, 2.4953f, 27.9928f, 30),
            "D" to RepFormPrior(7.7314f, 2.2765f, -0.2485f, 17.9758f, 1.0934f, 32.1102f, 37),
            "SIDE_B" to RepFormPrior(1.0858f, 4.0338f, -5.8570f, 15.1623f, -4.1560f, 19.1343f, 18),
            "SIDE_D" to RepFormPrior(2.2861f, 4.1755f, -5.9278f, 15.2331f, -4.1560f, 19.1343f, 21),
        ),
        "repform|스텝 포워드 다이나믹 런지|어깨 기울기" to mapOf(
            "B" to RepFormPrior(-0.0753f, 0.0233f, -0.1533f, 0.0293f, -0.1324f, 0.0687f, 25),
        ),
    )
}
