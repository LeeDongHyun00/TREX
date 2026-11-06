package com.trex.server.dto.community;

import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportResolution;
import com.trex.server.entity.ReportTargetType;

/** 내가 한 신고 — {@code status} 는 "PENDING" 또는 "RESOLVED"(정보통신망법의 신청인 알림을 앱 안에서 보인다). */
public record MyReportDto(long id, ReportTargetType targetType, String targetId, ReportReason reason, long createdAt,
                          String status, ReportResolution resolution) {
}
