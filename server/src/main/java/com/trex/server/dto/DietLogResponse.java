package com.trex.server.dto;

import com.trex.server.entity.DietLog;
import com.trex.server.entity.FoodItem;
import com.trex.server.entity.Meal;
import com.trex.server.entity.MealType;

import java.time.LocalDate;
import java.util.List;

public record DietLogResponse(
        Integer id,
        LocalDate logDate,
        Integer targetCal,
        Double targetCarb,
        Double targetProtein,
        Double targetFat,
        Integer actualCal,
        Double actualCarb,
        Double actualProtein,
        Double actualFat,
        List<MealResponse> meals
) {

    public static DietLogResponse from(DietLog log) {
        return new DietLogResponse(
                log.getId(),
                log.getLogDate(),
                log.getTargetCal(),
                log.getTargetCarb(),
                log.getTargetProtein(),
                log.getTargetFat(),
                log.getActualCal(),
                log.getActualCarb(),
                log.getActualProtein(),
                log.getActualFat(),
                log.getMeals().stream().map(MealResponse::from).toList()
        );
    }

    public record MealResponse(Integer id, MealType mealType, List<FoodItemResponse> foodItems) {

        public static MealResponse from(Meal meal) {
            return new MealResponse(
                    meal.getId(),
                    meal.getMealType(),
                    meal.getFoodItems().stream().map(FoodItemResponse::from).toList()
            );
        }
    }

    public record FoodItemResponse(
            Integer id,
            String foodName,
            Integer calories,
            Double carb,
            Double protein,
            Double fat
    ) {

        public static FoodItemResponse from(FoodItem item) {
            return new FoodItemResponse(
                    item.getId(),
                    item.getFoodName(),
                    item.getCalories(),
                    item.getCarb(),
                    item.getProtein(),
                    item.getFat()
            );
        }
    }
}
