package com.lumensteward.clawbot.common.exception;

import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理（8.6 / G-19）。
 *
 * <p>职责：把业务异常与系统异常分类处理，并与 HTTP 状态码<b>显式绑定</b>，禁止以单一 500 概括
 * 全部异常；响应统一走 {@link ApiResponse}（含 traceId）。文案不得包含堆栈/SQL/主机名/密钥（G-13）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：使用错误码绑定的 HTTP 状态，业务失败<b>不</b>返回 200。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBizException(BizException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        // 业务异常属于可预期失败，用 WARN 记录即可，避免污染 ERROR 告警
        log.warn("业务异常 code={} message={}", errorCode.getCode(), ex.getMessage());
        return build(errorCode, ex.getMessage());
    }

    /** 请求体校验失败（@Valid @RequestBody）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionHandler::renderFieldError)
                .orElse(ErrorCode.PARAM_MISSING.getMessage());
        return build(ErrorCode.PARAM_MISSING, message);
    }

    /** 表单/查询参数绑定失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Object>> handleBindException(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionHandler::renderFieldError)
                .orElse(ErrorCode.PARAM_MISSING.getMessage());
        return build(ErrorCode.PARAM_MISSING, message);
    }

    /** 方法参数级约束校验失败（@Validated 于类上）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
            ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        return build(ErrorCode.PARAM_INVALID, message);
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        return build(ErrorCode.PARAM_MISSING, "缺少必填参数: " + ex.getParameterName());
    }

    /** 参数类型不匹配（如路径变量应为首字母的枚举/数字却传入非法值）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.PARAM_INVALID, "参数格式非法: " + ex.getName());
    }

    /** 请求体不可解析（JSON 语法错误或类型不符）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotReadable(
            HttpMessageNotReadableException ex) {
        return build(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage());
    }

    /** 越权访问：@PreAuthorize 拒绝（FR-15 验收准则②，记越权审计由后续切面完成）。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("越权访问被拒绝: {}", ex.getMessage());
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage());
    }

    /** 认证失败（无 Token / Token 无效）。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthentication(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        return build(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.getMessage());
    }

    /** 兜底：未预期异常一律 500，且不向调用方泄露任何内部细节（G-13）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return build(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getMessage());
    }

    private static String renderFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }

    private static ResponseEntity<ApiResponse<Object>> build(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode, message));
    }
}
