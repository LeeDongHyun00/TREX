package com.trex.server.exception;

/** 형식은 맞지만 받을 수 없는 요청(항목 이름 규칙 위반, 너무 큰 본문 등) — 400. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
