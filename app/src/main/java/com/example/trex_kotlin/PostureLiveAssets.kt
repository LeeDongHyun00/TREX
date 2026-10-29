package com.example.trex_kotlin

import com.example.trex_kotlin.posture.FLOOR_RULES_ASSET
import com.example.trex_kotlin.posture.NormalPoseReference
import com.example.trex_kotlin.posture.PostureRuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 자세 화면이 쓰는 규칙·정상 자세 참조 — 앱 자산이라 앱이 떠 있는 동안 바뀌지 않는다(스펙 §115).
 * 세트 화면을 열 때마다 메인 스레드에서 규칙 JSON(약 280KB)을 다시 읽고 파싱하던 것을 백그라운드에서 한 번만 한다.
 * 규칙셋·참조는 불변 객체라 세션끼리 같이 써도 판정이 같다.
 *
 * 실패 처리는 예전 그대로다 — 참조를 못 읽으면 빈 참조, 서서 규칙을 못 읽으면 규칙 없음(저장하지 않아 다음 화면에서 다시 시도),
 * 바닥 규칙만 못 읽으면 서서 규칙만.
 */
internal object PostureLiveAssets {
    class Loaded(val normalReference: NormalPoseReference, val ruleSet: PostureRuleSet?, val floorExercises: Set<String>)

    /** 메인 스레드(화면의 LaunchedEffect)에서만 읽고 쓴다. */
    private var cached: Loaded? = null

    suspend fun load(host: PostureHost): Loaded =
        cached ?: withContext(Dispatchers.Default) { parse(host) }.also { if (it.ruleSet != null) cached = it }

    private fun parse(host: PostureHost): Loaded {
        val reference = runCatching {
            NormalPoseReference.parse(host.readAsset(NormalPoseReference.ASSET).lineSequence())
        }.getOrDefault(NormalPoseReference(emptyList()))
        // 서서 하는 종목 + 바닥 종목 규칙 병합 — PostureLabScreen 과 동일 패턴
        var floorExercises = emptySet<String>()
        val rules = runCatching {
            val standing = PostureRuleSet.loadText(host.readAsset(PostureRuleSet.ASSET_PATH))
            try {
                val floor = PostureRuleSet.loadText(host.readAsset(FLOOR_RULES_ASSET))
                floorExercises = floor.rules.map { it.exercise }.toSet()
                PostureRuleSet("${standing.version}+${floor.version}", standing.generated, standing.rules + floor.rules)
            } catch (_: Throwable) {
                standing
            }.plusRepForm()   // spec §62a 반복별 검사(발 너비·발끝·무릎 바닥) — AIHub 밖 항목
        }.getOrNull()
        return Loaded(reference, rules, floorExercises)
    }
}
