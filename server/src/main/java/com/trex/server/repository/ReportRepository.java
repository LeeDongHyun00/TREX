package com.trex.server.repository;

import com.trex.server.entity.Report;
import com.trex.server.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
