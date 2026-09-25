package com.example.trex_kotlin.posture

/**
 * 규칙 상태·판정·방향 — `PostureRules.kt` 에서 떼어낸 공유 타입. 이 파일은 안드로이드·org.json 의존이 없어 재생기(replay-jvm, spec §62a)가
 * `RepForm.kt` 와 함께 그대로 컴파일한다. 의미는 종전과 같다.
 */
enum class RuleStatus { SHIP, BETA, EXCLUDE;
    companion object {
        fun from(s: String): RuleStatus = when (s) {
            "ship" -> SHIP
            "beta" -> BETA
            else -> EXCLUDE
        }
    }
}

enum class Verdict { OK, VIOLATION, ABSTAIN }

/** 위반 방향 — PRIMARY 는 라벨로 검증된 방향(예: 스쿼트 무릎 '안쪽'), OPPOSITE 는 반대측 가드(예: '바깥'). */
enum class Direction { PRIMARY, OPPOSITE }
