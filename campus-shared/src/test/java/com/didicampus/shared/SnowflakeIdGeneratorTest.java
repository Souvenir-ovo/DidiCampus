package com.didicampus.shared;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnowflakeIdGeneratorTest {

    @Test
    void shouldRejectInvalidNodeId() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
    }

    @Test
    void shouldCreateUniqueIdsUnderConcurrentCalls() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(3);
        Set<Long> ids = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 2_000)
                .parallel()
                .forEach(index -> ids.add(generator.nextId()));

        assertEquals(2_000, ids.size());
    }
}
