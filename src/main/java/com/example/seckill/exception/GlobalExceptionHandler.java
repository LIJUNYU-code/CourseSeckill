package com.example.seckill.exception;


import com.example.seckill.common.Result;
import com.example.seckill.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> businessExceptionHandler(BusinessException e){
        return ResponseEntity.status(e.getCode())
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(Result.error(ResultCode.BAD_REQUEST, msg));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(Result.error(ResultCode.FORBIDDEN, "无权访问"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Result.error(ResultCode.NOT_FOUND, "接口不存在"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.BAD_REQUEST, "请求体格式错误：请检查 JSON 语法和字段类型"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未处理异常 uri={} method={}", request.getRequestURI(), request.getMethod(), e);
        return ResponseEntity.status(500)
                .body(Result.error(ResultCode.INTERNAL_ERROR, "服务器内部错误，请稍后重试"));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Result.error(ResultCode.BAD_REQUEST,
                        "参数类型不正确: " + e.getName()));
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(409)
                .body(Result.error(ResultCode.CONFLICT, "操作冲突，请勿重复提交"));
    }
}
