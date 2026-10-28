package com.trex.server.exception;

/** 너무 자주 쓴다(글·댓글 속도 제한) — 429. */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
