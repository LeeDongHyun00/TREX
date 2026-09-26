package com.trex.server.repository;

import com.trex.server.entity.DietLog;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DietLogRepository extends JpaRepository<DietLog, Integer> {

    // meals만 fetch join한다. foodItems까지 한 번에 당기면 중첩 bag fetch가 되므로 meal 단위 lazy 로딩으로 둔다.
    @EntityGraph(attributePaths = "meals")
    List<DietLog> findByUserIdOrderByLogDateDesc(Integer userId);

    boolean existsByUserIdAndLogDate(Integer userId, LocalDate logDate);

    // 파생 삭제 메서드는 엔티티를 로드한 뒤 삭제하므로 cascade로 하위 식사/음식도 함께 지워진다.
    long deleteByLogDateBefore(LocalDate cutoff);
}
