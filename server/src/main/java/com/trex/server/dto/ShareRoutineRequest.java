package com.trex.server.dto;

import java.util.List;

/** 루틴 공유 요청 — 검사는 {@code RoutineShareService} 가 한다(칸별로 같은 문구를 쓰려고). {@code totalSeconds} 는 앱이 계산한 예상 시간. */
public record ShareRoutineRequest(String name, Integer totalSeconds, List<SharedExerciseDto> exercises) {
}
