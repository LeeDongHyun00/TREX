package com.trex.server.exception;

/** 로그인은 됐지만 그 일을 할 수 없다(닉네임·이용규칙 전, 정지, 남의 글, 관리자 아님) — 403. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
