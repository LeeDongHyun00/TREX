package com.trex.server.dto;

import java.time.Instant;
import java.util.List;

/** 링크로 받은 루틴 — 로그인 없이 본다. 보낸 사람은 커뮤니티 닉네임만. */
public record SharedRoutineResponse(String name, String senderNickname, int totalSeconds, List<SharedExerciseDto> exercises,
                                    Instant createdAt) {
}
