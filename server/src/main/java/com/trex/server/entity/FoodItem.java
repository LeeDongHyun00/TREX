package com.trex.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "FOOD_ITEMS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FoodItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meal_id", nullable = false)
    private Meal meal;

    @Column(nullable = false)
    private String foodName;

    @Column(nullable = false)
    private Integer calories;

    @Column(nullable = false)
    private Double carb;

    @Column(nullable = false)
    private Double protein;

    @Column(nullable = false)
    private Double fat;

    @Builder
    public FoodItem(String foodName, Integer calories, Double carb, Double protein, Double fat) {
        this.foodName = foodName;
        this.calories = calories;
        this.carb = carb;
        this.protein = protein;
        this.fat = fat;
    }

    void assignMeal(Meal meal) {
        this.meal = meal;
    }
}
