package com.example.seckill.exception;

import com.example.seckill.common.ResultCode;
import lombok.Getter;

/**
 * 业务规则异常。继承 RuntimeException 是有意的：受检异常会逼每个中间层写 throws，
 * 而业务失败应当一路抛到 GlobalExceptionHandler 统一翻译。
 */
@Getter                       // 只要 getCode()，不要 @Data
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);       // 消息交给父类，不自己存
        this.code = code;
    }

    // 常用场景的便捷构造器，省得每次写 ResultCode
    public BusinessException(String message) {
        this(ResultCode.BAD_REQUEST, message);
    }
}