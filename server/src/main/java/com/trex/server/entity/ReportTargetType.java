package com.trex.server.entity;

/** 신고 대상 — 글·댓글은 숫자 id, 사용자는 publicId 를 targetId 로 쓴다. */
public enum ReportTargetType {
    POST, COMMENT, USER
}
