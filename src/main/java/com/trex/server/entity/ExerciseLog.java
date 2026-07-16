package com.trex.server.entity;

import com.trex.server.converter.IntegerListJsonConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "EXERCISE_LOGS")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExerciseLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDate logDate;

    @Column(nullable = false)
    private Integer totalCalories;

    @Column(nullable = false)
    private Integer totalTimeMin;

    @Column(nullable = false)
    private Boolean isPostureChecked;

    // 세트별 자세 점수 배열(예: [94,91,88])을 JSON 문자열로 저장한다.
    @Convert(converter = IntegerListJsonConverter.class)
    @Column(name = "set_scores_json", nullable = false, length = 500)
    private List<Integer> setScores;

    @OneToMany(mappedBy = "exerciseLog", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExerciseRoutine> routines = new ArrayList<>();

    @Builder
    public ExerciseLog(
            User user,
            LocalDate logDate,
            Integer totalCalories,
            Integer totalTimeMin,
            Boolean isPostureChecked,
            List<Integer> setScores
    ) {
        this.user = user;
        this.logDate = logDate;
        this.totalCalories = totalCalories;
        this.totalTimeMin = totalTimeMin;
        this.isPostureChecked = isPostureChecked;
        this.setScores = setScores;
    }

    public void addRoutine(ExerciseRoutine routine) {
        routines.add(routine);
        routine.assignLog(this);
    }
}
