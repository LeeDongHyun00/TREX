package com.trex.server.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("입력값이 올바르지 않습니다", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("요청 본문을 읽을 수 없습니다"));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of(e.getMessage()));
    }

    // 커뮤니티 사진 한 장 2MB·요청 11MB(spring.servlet.multipart) 를 넘었다.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ErrorResponse.of("사진이 너무 커요(장당 2MB까지)"));
    }

    // existsBy 선검사를 통과한 동시 요청이 DB 유니크 제약에 걸리는 경우의 최종 방어선.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("데이터 무결성 위반: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("이미 사용 중인 값이 포함되어 있습니다"));
    }

    // 없는 주소. 운영 서버는 공개돼 있어 봇이 아무 경로나 두드린다 — 500·ERROR 로그로 남기지 않는다.
    // (보안 필터를 건너뛰는 /h2-console 은 운영에서 콘솔이 꺼져 있으면 여기로 온다.)
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("없는 주소입니다"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("서버 오류가 발생했습니다"));
    }
}
