package com.trex.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "EXERCISE_ROUTINES")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExerciseRoutine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_log_id", nullable = false)
    private ExerciseLog exerciseLog;

    @Column(nullable = false)
    private String exerciseName;

    @Column(nullable = false)
    private Integer sets;

    @Column(nullable = false)
    private Integer reps;

    @Builder
    public ExerciseRoutine(String exerciseName, Integer sets, Integer reps) {
        this.exerciseName = exerciseName;
        this.sets = sets;
        this.reps = reps;
    }

    void assignLog(ExerciseLog exerciseLog) {
        this.exerciseLog = exerciseLog;
    }
}
