package com.trex.server.dto.community;

import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportTargetType;

/** 신고 — 글·댓글은 숫자 id, 사용자는 publicId 를 {@code targetId} 로. */
public record ReportRequest(ReportTargetType targetType, String targetId, ReportReason reason) {
}
