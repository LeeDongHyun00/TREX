package com.trex.server.dto;

/**
 * 공유 루틴의 운동 하나 — 앱이 오늘 루틴을 저장하는 JSON 과 같은 칸 이름(기기 전용 {@code id}·{@code done} 은 없다).
 * 선택 칸은 null 이면 받는 앱이 종목 기본값을 쓴다.
 */
public record SharedExerciseDto(
        String name,
        String reps,
        String duration,
        boolean posture,
        String category,
        AltDto alt,
        Integer secondsPerRep,
        Integer restSeconds,
        String targetKind,
        Integer targetAmount
) {
    /** 대체 운동(앱의 {@code WorkoutAlt}). */
    public record AltDto(String name, String reps) {
    }
}
