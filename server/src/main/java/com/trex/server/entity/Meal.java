package com.trex.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "MEALS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diet_log_id", nullable = false)
    private DietLog dietLog;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MealType mealType;

    @OneToMany(mappedBy = "meal", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FoodItem> foodItems = new ArrayList<>();

    @Builder
    public Meal(MealType mealType) {
        this.mealType = mealType;
    }

    void assignDietLog(DietLog dietLog) {
        this.dietLog = dietLog;
    }

    public void addFoodItem(FoodItem foodItem) {
        foodItems.add(foodItem);
        foodItem.assignMeal(this);
    }
}
