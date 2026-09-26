package com.trex.server.dto;

import com.trex.server.entity.ExerciseLog;
import com.trex.server.entity.ExerciseRoutine;

import java.time.LocalDate;
import java.util.List;

public record ExerciseLogResponse(
        Integer id,
        LocalDate logDate,
        Integer totalCalories,
        Integer totalTimeMin,
        Boolean isPostureChecked,
        List<Integer> setScoresJson,
        List<RoutineResponse> routines
) {

    public static ExerciseLogResponse from(ExerciseLog log) {
        return new ExerciseLogResponse(
                log.getId(),
                log.getLogDate(),
                log.getTotalCalories(),
                log.getTotalTimeMin(),
                log.getIsPostureChecked(),
                log.getSetScores(),
                log.getRoutines().stream().map(RoutineResponse::from).toList()
        );
    }

    public record RoutineResponse(Integer id, String exerciseName, Integer sets, Integer reps) {

        public static RoutineResponse from(ExerciseRoutine routine) {
            return new RoutineResponse(
                    routine.getId(),
                    routine.getExerciseName(),
                    routine.getSets(),
                    routine.getReps()
            );
        }
    }
}
