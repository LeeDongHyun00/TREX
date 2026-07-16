package com.trex.server.service;

import com.trex.server.dto.ExerciseLogRequest;
import com.trex.server.dto.ExerciseLogResponse;
import com.trex.server.entity.ExerciseLog;
import com.trex.server.entity.ExerciseRoutine;
import com.trex.server.entity.User;
import com.trex.server.exception.InvalidCredentialsException;
import com.trex.server.repository.ExerciseLogRepository;
import com.trex.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ExerciseLogService {

    private final UserRepository userRepository;
    private final ExerciseLogRepository exerciseLogRepository;

    public ExerciseLogService(UserRepository userRepository, ExerciseLogRepository exerciseLogRepository) {
        this.userRepository = userRepository;
        this.exerciseLogRepository = exerciseLogRepository;
    }

    @Transactional
    public ExerciseLogResponse create(String loginId, ExerciseLogRequest request) {
        User user = findUser(loginId);

        ExerciseLog log = ExerciseLog.builder()
                .user(user)
                .logDate(request.logDate())
                .totalCalories(request.totalCalories())
                .totalTimeMin(request.totalTimeMin())
                .isPostureChecked(request.isPostureChecked())
                .setScores(request.setScoresJson())
                .build();

        request.routines().forEach(routine -> log.addRoutine(
                ExerciseRoutine.builder()
                        .exerciseName(routine.exerciseName())
                        .sets(routine.sets())
                        .reps(routine.reps())
                        .build()
        ));

        return ExerciseLogResponse.from(exerciseLogRepository.save(log));
    }

    public List<ExerciseLogResponse> getMine(String loginId) {
        User user = findUser(loginId);
        return exerciseLogRepository.findByUserIdOrderByLogDateDescIdDesc(user.getId()).stream()
                .map(ExerciseLogResponse::from)
                .toList();
    }

    private User findUser(String loginId) {
        return userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new InvalidCredentialsException("존재하지 않는 사용자입니다"));
    }
}
