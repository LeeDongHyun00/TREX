package com.trex.server.dto.community;

import com.trex.server.entity.ReportResolution;
import com.trex.server.entity.ReportTargetType;

/** 신고 처리 — KEPT 는 숨김을 풀고 그대로 둔다, REMOVED 는 글·댓글을 지운다(사용자면 정지한다). */
public record ResolveRequest(ReportTargetType targetType, String targetId, ReportResolution resolution) {
}
