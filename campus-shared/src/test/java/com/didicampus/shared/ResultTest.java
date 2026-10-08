package com.didicampus.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void shouldBuildSuccessAndFailureResponses() {
        Result<String> success = Result.success("ok");
        Result<Void> failure = Result.failure(ErrorCode.ERRAND_NOT_FOUND);

        assertTrue(success.isSuccess());
        assertEquals("ok", success.data());
        assertFalse(failure.isSuccess());
        assertEquals("ERRAND_NOT_FOUND", failure.code());
        assertEquals("任务不存在", failure.message());
    }
}
