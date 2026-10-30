package com.trex.server.repository;

import com.trex.server.entity.SharedRoutine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SharedRoutineRepository extends JpaRepository<SharedRoutine, Long> {

    Optional<SharedRoutine> findByCode(String code);

    boolean existsByCode(String code);

    long countByOwnerIdAndCreatedAtAfter(Long ownerId, Instant after);

    @Modifying
    @Query("delete from SharedRoutine r where r.owner.id = :ownerId")
    void deleteByOwnerId(@Param("ownerId") Long ownerId);
}
