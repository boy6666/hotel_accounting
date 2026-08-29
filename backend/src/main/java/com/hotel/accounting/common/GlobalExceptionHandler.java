package com.hotel.accounting.common;

import com.hotel.accounting.security.JwtAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 全局异常处理：把业务异常/系统异常统一映射为 {@code {code,message,data}} 信封 + REST HTTP 状态。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBiz(BizException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResult.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(JwtAuthException.class)
    public ResponseEntity<ApiResult<Void>> handleJwt(JwtAuthException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResult.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleBody(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResult.error(
                BizError.BAD_REQUEST.code(), "请求体格式错误：" + rootMessage(ex)));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ApiResult<Void>> handleParam(Exception ex) {
        log.warn("请求参数错误: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResult.error(
                BizError.BAD_REQUEST.code(), "请求参数错误：" + rootMessage(ex)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405).body(ApiResult.error(
                BizError.BAD_REQUEST.code(), "不支持的请求方法：" + ex.getMethod()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> handleSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(ApiResult.error(
                BizError.BAD_REQUEST.code(), "上传文件过大（限制 20MB）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception ex) {
        log.error("未捕获异常", ex);
        return ResponseEntity.status(BizError.INTERNAL_ERROR.httpStatus())
                .body(ApiResult.error(BizError.INTERNAL_ERROR.code(), "服务端内部错误，请查看日志"));
    }

    private static String rootMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
