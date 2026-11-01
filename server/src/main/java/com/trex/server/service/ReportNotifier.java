package com.trex.server.service;

import com.trex.server.entity.ReportReason;
import com.trex.server.entity.ReportTargetType;

/**
 * 새 신고를 운영자에게 알린다 — 푸시가 없으니 이걸로 신고 24시간 처리(App Store 1.2)를 지킨다.
 * 개인정보(loginId·실명·이메일·본문)를 받지 않는다 — 대상 종류·id·사유·누적 수만.
 */
public interface ReportNotifier {

    void reportFiled(ReportTargetType type, String targetId, ReportReason reason, long reportCount, boolean autoHidden);
}
