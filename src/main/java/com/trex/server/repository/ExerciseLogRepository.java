package com.trex.server.repository;

import com.trex.server.entity.ExerciseLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExerciseLogRepository extends JpaRepository<ExerciseLog, Integer> {

    List<ExerciseLog> findByUserIdOrderByLogDateDescIdDesc(Integer userId);

    // 파생 삭제 메서드는 엔티티를 로드한 뒤 삭제하므로 cascade로 하위 루틴도 함께 지워진다.
    long deleteByLogDateBefore(LocalDate cutoff);
}
