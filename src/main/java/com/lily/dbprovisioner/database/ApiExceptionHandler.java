package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.engine.EngineNotEnabledException;
import com.lily.dbprovisioner.engine.ProvisioningException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 에러 응답 형식은 lily-blog-sample 과 같다: {timestamp, code, message} */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DatabaseNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(DatabaseNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(DatabaseAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleExists(DatabaseAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("ALREADY_EXISTS", e.getMessage()));
    }

    @ExceptionHandler(DatabaseNotReadyException.class)
    public ResponseEntity<Map<String, Object>> handleNotReady(DatabaseNotReadyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("NOT_READY", e.getMessage()));
    }

    @ExceptionHandler(EngineNotEnabledException.class)
    public ResponseEntity<Map<String, Object>> handleEngine(EngineNotEnabledException e) {
        return ResponseEntity.badRequest().body(body("ENGINE_NOT_ENABLED", e.getMessage()));
    }

    /** 공용 인스턴스(RDS) 쪽 실패 */
    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<Map<String, Object>> handleProvisioning(ProvisioningException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body("PROVISIONING_FAILED", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", e.getMessage()));
    }

    /** 잘못된 JSON, 지원하지 않는 engine 값 등 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", "invalid request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        // 플랫폼의 로그 수집/원인 분류 모듈이 잡아야 하는 지점
        log.error("unhandled error: {}", e.getMessage(), e);
        // 원인 메시지는 로그에만 남긴다 (AWS/JDBC 내부 정보가 응답으로 나가지 않도록)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", "internal error"));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}
