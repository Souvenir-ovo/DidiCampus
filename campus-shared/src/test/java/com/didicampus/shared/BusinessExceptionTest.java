package com.didicampus.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessExceptionTest {

    @Test
    void shouldKeepCodeAndReadableMessage() {
        BusinessException exception =
                new BusinessException(ErrorCode.STALE_VERSION, "order changed");

        assertEquals(ErrorCode.STALE_VERSION, exception.code());
        assertEquals("数据已被并发修改: order changed", exception.getMessage());
    }
}
