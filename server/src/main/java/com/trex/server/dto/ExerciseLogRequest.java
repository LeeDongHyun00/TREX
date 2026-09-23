package com.trex.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record ExerciseLogRequest(

        @NotNull(message = "기록 날짜는 필수입니다")
        LocalDate logDate,

        @NotNull(message = "총 소모 칼로리는 필수입니다")
        @Min(value = 0, message = "총 소모 칼로리는 0 이상이어야 합니다")
        Integer totalCalories,

        @NotNull(message = "총 운동 시간은 필수입니다")
        @Min(value = 0, message = "총 운동 시간은 0분 이상이어야 합니다")
        Integer totalTimeMin,

        @NotNull(message = "자세 확인 여부는 필수입니다")
        Boolean isPostureChecked,

        // 앱 setScores(List<Int>) 매핑 필드. 자세 확인을 안 한 세션이면 빈 배열.
        // 최대 개수는 DB 컬럼 set_scores_json varchar(500) 한도 안에 들어오는 값으로 제한한다.
        @NotNull(message = "세트 점수 목록은 필수입니다")
        @Size(max = 100, message = "세트 점수는 최대 100개까지 기록할 수 있습니다")
        List<@NotNull(message = "세트 점수 값이 비어 있습니다")
             @Min(value = 0, message = "세트 점수는 0~100이어야 합니다")
             @Max(value = 100, message = "세트 점수는 0~100이어야 합니다") Integer> setScoresJson,

        @NotNull(message = "운동 루틴 목록은 필수입니다")
        @Size(min = 1, message = "운동 루틴은 1개 이상이어야 합니다")
        List<@Valid RoutineRequest> routines
) {

    public record RoutineRequest(

            @NotBlank(message = "운동 이름은 필수입니다")
            String exerciseName,

            @NotNull(message = "세트 수는 필수입니다")
            @Min(value = 1, message = "세트 수는 1 이상이어야 합니다")
            Integer sets,

            @NotNull(message = "반복 횟수는 필수입니다")
            @Min(value = 1, message = "반복 횟수는 1 이상이어야 합니다")
            Integer reps
    ) {
    }
}
