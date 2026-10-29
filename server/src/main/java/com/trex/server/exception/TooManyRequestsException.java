package com.trex.server.exception;

/** 너무 자주 쓴다(글·댓글 속도 제한, 비밀번호 연속 실패) — 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
