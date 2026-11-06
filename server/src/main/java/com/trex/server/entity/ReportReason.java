package com.trex.server.entity;

/** 신고 사유(설계 §5). {@link #label} 은 디스코드 알림·관리자 화면 문구. */
public enum ReportReason {
    SPAM("스팸·광고"),
    ABUSE("욕설·혐오"),
    SEXUAL_VIOLENT("음란·폭력"),
    PRIVACY("개인정보 노출"),
    RIGHTS("권리 침해"),
    OTHER("기타");

    public final String label;

    ReportReason(String label) {
        this.label = label;
    }
}
