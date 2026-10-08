package com.didicampus.shared;

/**
 * 可预期的业务异常。
 *
 * <p>使用运行时异常，便于在应用层触发事务回滚；
 * 对外返回什么结构由 presentation 层统一处理。</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(formatMessage(errorCode, detail));
        this.errorCode = java.util.Objects.requireNonNull(errorCode, "errorCode");
    }

    public ErrorCode code() {
        return errorCode;
    }

    private static String formatMessage(ErrorCode errorCode, String detail) {
        java.util.Objects.requireNonNull(errorCode, "errorCode");
        return detail == null || detail.isBlank()
                ? errorCode.message()
                : errorCode.message() + ": " + detail;
    }
}
