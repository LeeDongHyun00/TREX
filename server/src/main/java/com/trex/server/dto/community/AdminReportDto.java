package com.trex.server.dto.community;

import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportTargetType;

import java.util.List;

/**
 * 관리자 신고함 한 줄 — 대상별로 묶는다. {@code preview} 는 글·댓글 본문 앞 80자 또는 닉네임(지워졌으면 "(삭제됨)"),
 * {@code hidden} 은 글·댓글이면 숨김, 사용자면 정지 여부.
 */
public record AdminReportDto(ReportTargetType targetType, String targetId, String preview, long reportCount,
                             List<ReportReason> reasons, boolean hidden, long firstReportedAt) {
}
