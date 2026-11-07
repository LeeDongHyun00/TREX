package com.example.trex_kotlin

import com.example.trex_kotlin.trainingload.ExerciseLoadProfile
import com.example.trex_kotlin.trainingload.LoadUnit
import com.example.trex_kotlin.trainingload.MuscleLoadCatalog

/*
 * 종목의 하중 종류(2026-11-07, 사용자 요청 "맨몸 스쿼트의 kg 은 어떻게 하나" → 근거 감사 `docs/EXERCISE_DATA_AUDIT_2026-11-07.md`).
 * Hevy 의 규칙을 빌렸다 — 맨몸 종목은 kg 이 '추가 중량'(+kg)이고, **체중 전부를 드는 종목(풀업·딥스)만** 체중을 볼륨에 넣는다.
 * 스쿼트·푸쉬업처럼 체중 일부만 드는 종목은 비율 근거가 없어(푸쉬업 64~75 % 측정은 있으나 스쿼트는 없음) 체중을 넣지 않는다 — 없는 숫자를 만들지 않는다(원칙 #1).
 */
enum class ExerciseLoadKind {
    /** 외부 중량(바벨·덤벨·머신·케이블·밴드) — kg 이 하중, 1RM 추정 가능 */
    EXTERNAL,
    /** 맨몸 — kg 은 추가 중량(조끼·벨트), 체중은 볼륨에 넣지 않는다 */
    BODYWEIGHT,
    /** 체중 전부를 드는 맨몸(풀업·딥스) — 볼륨 = (체중 + 추가 kg) × 회, 체중 기록이 있을 때만 */
    BODYWEIGHT_FULL,
    /** 버티기·운반 — 초로 기록, kg 은 운반 중량(캐리)만 */
    TIMED,
}

/** 체중 전부를 드는 종목 — Hevy 가 체중을 볼륨에 넣는 종목(풀업·친업·딥스)과 같은 기준. 행잉 레그 레이즈·캡틴체어는 다리만 들어 넣지 않는다. */
val FULL_BODYWEIGHT_EXERCISES: Set<String> = setOf("풀업", "딥스")

fun exerciseLoadKind(name: String): ExerciseLoadKind {
    val p = MuscleLoadCatalog.find(name)
    return when {
        p == null -> ExerciseLoadKind.EXTERNAL
        p.unit == LoadUnit.SECONDS -> ExerciseLoadKind.TIMED
        name in FULL_BODYWEIGHT_EXERCISES -> ExerciseLoadKind.BODYWEIGHT_FULL
        p.bodyweight -> ExerciseLoadKind.BODYWEIGHT
        else -> ExerciseLoadKind.EXTERNAL
    }
}

/** kg 칸의 뜻 — 맨몸은 '+kg'(추가 중량), 외부 중량은 'kg'. */
fun ExerciseLoadKind.kgLabel(): String = when (this) { ExerciseLoadKind.BODYWEIGHT, ExerciseLoadKind.BODYWEIGHT_FULL -> "+kg"; else -> "kg" }

/** kg 칸을 여는가 — 맨몸 버티기(플랭크 4종·싱글레그 애브도미널 프레스)는 kg 이 뜻이 없어 닫는다('—'). 운반(캐리)은 운반 중량이라 연다. */
fun exerciseShowsKg(name: String): Boolean {
    val p = MuscleLoadCatalog.find(name) ?: return true
    return !(p.unit == LoadUnit.SECONDS && p.bodyweight)
}

/** 1RM(Epley)은 외부 중량에만 뜻이 있다 — 맨몸 + 추가 중량은 체중 비율이 섞여 추정하지 않는다. */
fun ExerciseLoadKind.supportsOneRepMax(): Boolean = this == ExerciseLoadKind.EXTERNAL

/**
 * 근육 역할 가중치의 근거가 약한 프로필(감사 F2 의 a·b·c) — 출처가 없거나, evidence 가 '추론·유추·적용·추정·편집·유사·미확보' 로 적혔거나(a·b),
 * 출처가 동료심사 논문(PubMed·DOI·MDPI)이 아닌 기관 자료(NASM·ACE·Mayo)뿐인 것(c). 화면은 '추정' 칩으로 밝힌다(원칙 #2).
 * 가중치 자체는 전부 제품 초기값이라(EMG 비율 아님) 이 칩은 '같은 조건의 측정이 없어 옮겨 왔다' 는 뜻이다. 감사 당시 72종 중 39종.
 */
val ExerciseLoadProfile.evidenceWeak: Boolean
    get() = sources.isEmpty() ||
        listOf("추론", "유추", "적용", "추정", "편집", "유사", "미확보").any { it in evidence } ||
        sources.none { s -> listOf("pubmed.ncbi", "doi.org", "mdpi.com").any { it in s } }

/** 체중을 양쪽 같은 부하로 적는 좌우 버티기(§130) — 머리글 '초(한쪽)'. */
val SIDED_TIMED_EXERCISES: Set<String> = setOf("사이드 플랭크", "무릎 사이드 플랭크", "수트케이스 캐리", "싱글레그 애브도미널 프레스")

/**
 * 세트 입력 머리글의 횟수 칸 이름 — PAIR 종목(한쪽씩, 엔진은 적은 수를 **쪽당**으로 읽는다)은 '회(쪽당)', 좌우 버티기는 '초(한쪽)', 그 밖은 '회'/'초'.
 * 엔진이 `l = r = n/12` 로 읽는 뜻을 화면이 말해야 한다(감사 F3 참고 항목).
 */
fun countLabelFor(name: String, timed: Boolean): String {
    val p = MuscleLoadCatalog.find(name)
    return when {
        timed && name in SIDED_TIMED_EXERCISES -> "초(한쪽)"
        timed -> "초"
        p?.unit == LoadUnit.PAIR -> "회(쪽당)"
        else -> "회"
    }
}
