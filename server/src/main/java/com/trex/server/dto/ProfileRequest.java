package com.trex.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProfileRequest(

        @NotBlank(message = "운동 목표는 필수입니다")
        String fitnessGoal,

        @NotNull(message = "운동 요일은 필수입니다")
        @Size(min = 1, max = 7, message = "운동 요일은 1~7개여야 합니다")
        List<@NotNull(message = "요일 값이 비어 있습니다")
             @Min(value = 0, message = "요일 인덱스는 0~6이어야 합니다")
             @Max(value = 6, message = "요일 인덱스는 0~6이어야 합니다") Integer> workoutDays,

        @NotBlank(message = "운동 장소는 필수입니다")
        String place,

        @NotBlank(message = "성별은 필수입니다")
        String gender,

        // 맨몸 운동이면 빈 배열을 보낸다. null은 허용하지 않는다.
        @NotNull(message = "보유 장비 목록은 필수입니다")
        List<@NotBlank(message = "장비 이름은 비어 있을 수 없습니다") String> availableEquip,

        @NotNull(message = "키는 필수입니다")
        @Min(value = 100, message = "키는 100~250cm 범위여야 합니다")
        @Max(value = 250, message = "키는 100~250cm 범위여야 합니다")
        Integer height,

        @NotNull(message = "몸무게는 필수입니다")
        @Min(value = 30, message = "몸무게는 30~200kg 범위여야 합니다")
        @Max(value = 200, message = "몸무게는 30~200kg 범위여야 합니다")
        Integer weight,

        @NotNull(message = "나이는 필수입니다")
        @Min(value = 1, message = "나이는 1~120 범위여야 합니다")
        @Max(value = 120, message = "나이는 1~120 범위여야 합니다")
        Integer age
) {
}
