package com.nexcompute.management.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("业务异常: {} - {} [{}]", ex.getErrorCode().getCode(), ex.getMessage(), request.getRequestURI());
        HttpStatus status = mapHttpStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, msg));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.PERMISSION_DENIED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 [{}]: ", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ex.getMessage()));
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }

    private HttpStatus mapHttpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case UNAUTHORIZED, TOKEN_INVALID, LOGIN_FAILED, ACCOUNT_DISABLED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, USER_NOT_FOUND, GROUP_NOT_FOUND, INSTANCE_NOT_FOUND,
                 CONTAINER_NOT_FOUND, STORAGE_POOL_NOT_FOUND, IMAGE_NOT_FOUND,
                 TICKET_NOT_FOUND, ANNOUNCEMENT_NOT_FOUND, NOTIFICATION_NOT_FOUND,
                 PERMISSION_MATRIX_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, USER_ALREADY_EXISTS, MACHINE_NUMBER_CONFLICT -> HttpStatus.CONFLICT;
            case BAD_REQUEST, PASSWORD_TOO_WEAK, REGISTRATION_LINK_INVALID,
                 REGISTRATION_LINK_EXPIRED, PORT_ALLOCATION_FAILED -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.OK;
        };
    }
}
