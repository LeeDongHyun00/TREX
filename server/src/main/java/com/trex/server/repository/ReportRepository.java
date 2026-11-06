package com.trex.server.repository;

import com.trex.server.entity.Report;
import com.trex.server.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {

    boolean existsByReporterIdAndTargetTypeAndTargetId(Long reporterId, ReportTargetType targetType, String targetId);

    /** 미처리 신고 수 — 한 사람 한 번(유니크)이라 곧 서로 다른 신고자 수다. */
    long countByTargetTypeAndTargetIdAndResolvedAtIsNull(ReportTargetType targetType, String targetId);

    List<Report> findByReporterIdOrderByIdDesc(Long reporterId);

    List<Report> findByTargetTypeAndTargetIdAndResolvedAtIsNull(ReportTargetType targetType, String targetId);

    List<Report> findByResolvedAtIsNullOrderByIdAsc();

    List<Report> findAllByOrderByIdDesc();

    List<Report> findByTargetTypeAndTargetId(ReportTargetType targetType, String targetId);

    List<Report> findByReporterId(Long reporterId);

    long countByReporterIdAndCreatedAtAfter(Long reporterId, java.time.Instant after);

    /** 탈퇴 — 그 사람이 한 신고. */
    @Modifying
    @Query("delete from Report r where r.reporter.id = :reporter")
    void deleteByReporter(@Param("reporter") Long reporter);

    /** 탈퇴 — 지워지는 글·댓글·사용자를 대상으로 한 신고. */
    @Modifying
    @Query("delete from Report r where r.targetType = :type and r.targetId in :ids")
    void deleteTargets(@Param("type") ReportTargetType type, @Param("ids") Collection<String> ids);
}
