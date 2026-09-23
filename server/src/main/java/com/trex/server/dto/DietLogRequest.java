package com.trex.server.dto;

import com.trex.server.entity.MealType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record DietLogRequest(

        @NotNull(message = "기록 날짜는 필수입니다")
        LocalDate logDate,

        @NotNull(message = "목표 칼로리는 필수입니다")
        @Min(value = 0, message = "목표 칼로리는 0 이상이어야 합니다")
        Integer targetCal,

        @NotNull(message = "목표 탄수화물은 필수입니다")
        @PositiveOrZero(message = "목표 탄수화물은 0 이상이어야 합니다")
        Double targetCarb,

        @NotNull(message = "목표 단백질은 필수입니다")
        @PositiveOrZero(message = "목표 단백질은 0 이상이어야 합니다")
        Double targetProtein,

        @NotNull(message = "목표 지방은 필수입니다")
        @PositiveOrZero(message = "목표 지방은 0 이상이어야 합니다")
        Double targetFat,

        @NotNull(message = "실제 칼로리는 필수입니다")
        @Min(value = 0, message = "실제 칼로리는 0 이상이어야 합니다")
        Integer actualCal,

        @NotNull(message = "실제 탄수화물은 필수입니다")
        @PositiveOrZero(message = "실제 탄수화물은 0 이상이어야 합니다")
        Double actualCarb,

        @NotNull(message = "실제 단백질은 필수입니다")
        @PositiveOrZero(message = "실제 단백질은 0 이상이어야 합니다")
        Double actualProtein,

        @NotNull(message = "실제 지방은 필수입니다")
        @PositiveOrZero(message = "실제 지방은 0 이상이어야 합니다")
        Double actualFat,

        // 아무것도 안 먹은 날의 목표 기록 동기화를 허용하기 위해 빈 배열을 받는다.
        @NotNull(message = "식사 목록은 필수입니다")
        List<@Valid MealRequest> meals
) {

    public record MealRequest(

            @NotNull(message = "식사 구분은 필수입니다")
            MealType mealType,

            @NotNull(message = "음식 목록은 필수입니다")
            @Size(min = 1, message = "음식은 1개 이상이어야 합니다")
            List<@Valid FoodItemRequest> foodItems
    ) {
    }

    public record FoodItemRequest(

            @NotBlank(message = "음식 이름은 필수입니다")
            String foodName,

            @NotNull(message = "칼로리는 필수입니다")
            @Min(value = 0, message = "칼로리는 0 이상이어야 합니다")
            Integer calories,

            @NotNull(message = "탄수화물은 필수입니다")
            @PositiveOrZero(message = "탄수화물은 0 이상이어야 합니다")
            Double carb,

            @NotNull(message = "단백질은 필수입니다")
            @PositiveOrZero(message = "단백질은 0 이상이어야 합니다")
            Double protein,

            @NotNull(message = "지방은 필수입니다")
            @PositiveOrZero(message = "지방은 0 이상이어야 합니다")
            Double fat
    ) {
    }
}
