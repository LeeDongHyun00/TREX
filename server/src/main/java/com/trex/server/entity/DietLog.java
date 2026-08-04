package com.trex.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "DIET_LOGS",
        // 식단 기록은 하루 단위 목표/실제 집계이므로 사용자당 날짜별 1건으로 제한한다.
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "log_date"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DietLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;

    @Column(nullable = false)
    private Integer targetCal;

    @Column(nullable = false)
    private Double targetCarb;

    @Column(nullable = false)
    private Double targetProtein;

    @Column(nullable = false)
    private Double targetFat;

    @Column(nullable = false)
    private Integer actualCal;

    @Column(nullable = false)
    private Double actualCarb;

    @Column(nullable = false)
    private Double actualProtein;

    @Column(nullable = false)
    private Double actualFat;

    @OneToMany(mappedBy = "dietLog", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Meal> meals = new ArrayList<>();

    @Builder
    public DietLog(
            User user,
            LocalDate logDate,
            Integer targetCal,
            Double targetCarb,
            Double targetProtein,
            Double targetFat,
            Integer actualCal,
            Double actualCarb,
            Double actualProtein,
            Double actualFat
    ) {
        this.user = user;
        this.logDate = logDate;
        this.targetCal = targetCal;
        this.targetCarb = targetCarb;
        this.targetProtein = targetProtein;
        this.targetFat = targetFat;
        this.actualCal = actualCal;
        this.actualCarb = actualCarb;
        this.actualProtein = actualProtein;
        this.actualFat = actualFat;
    }

    public void addMeal(Meal meal) {
        meals.add(meal);
        meal.assignDietLog(this);
    }
}
