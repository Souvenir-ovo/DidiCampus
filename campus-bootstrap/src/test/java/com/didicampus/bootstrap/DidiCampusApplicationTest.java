package com.didicampus.bootstrap;

import com.didicampus.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DidiCampusApplicationTest {

    @Test
    void createsSnowflakeIdGeneratorWithConfiguredNode() {
        BootstrapConfig config = new BootstrapConfig();
        SnowflakeIdGenerator generator = config.snowflakeIdGenerator(1L);

        assertTrue(generator.nextId() > 0);
    }
}
