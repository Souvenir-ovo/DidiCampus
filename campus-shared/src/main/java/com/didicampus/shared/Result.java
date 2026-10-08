package com.didicampus.shared;

/**
 * 对外接口统一响应模型。
 *
 * @param code 结果码名称，避免前端依赖 Java 枚举类型
 * @param message 面向调用方的提示信息
 * @param data 业务数据，失败时通常为空
 */
public record Result<T>(String code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.OK.name(), ErrorCode.OK.message(), data);
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode, T data) {
        return new Result<>(
                errorCode.name(),
                errorCode.message(),
                data
        );
    }

    public boolean isSuccess() {
        return ErrorCode.OK.name().equals(code);
    }
}
