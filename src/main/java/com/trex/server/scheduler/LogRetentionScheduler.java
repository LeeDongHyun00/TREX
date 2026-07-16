package com.trex.server.scheduler;

import com.trex.server.repository.DietLogRepository;
import com.trex.server.repository.ExerciseLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Component
public class LogRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionScheduler.class);

    // 7일 보존 정책 대상은 EXERCISE_LOGS, DIET_LOGS뿐이다. USERS, USER_PROFILES는 영구 보존.
    private static final int RETENTION_DAYS = 7;

    private final ExerciseLogRepository exerciseLogRepository;
    private final DietLogRepository dietLogRepository;

    public LogRetentionScheduler(
            ExerciseLogRepository exerciseLogRepository,
            DietLogRepository dietLogRepository
    ) {
        this.exerciseLogRepository = exerciseLogRepository;
        this.dietLogRepository = dietLogRepository;
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void purgeExpiredLogs() {
        LocalDate cutoff = LocalDate.now().minusDays(RETENTION_DAYS);
        long exerciseDeleted = exerciseLogRepository.deleteByLogDateBefore(cutoff);
        long dietDeleted = dietLogRepository.deleteByLogDateBefore(cutoff);
        log.info("7일 보존 정책 실행: {} 이전 운동 기록 {}건, 식단 기록 {}건 Hard Delete", cutoff, exerciseDeleted, dietDeleted);
    }
}
