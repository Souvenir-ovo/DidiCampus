package com.didicampus.bootstrap;

import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BootstrapConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${didicampus.node-id:1}") long nodeId) {
        return new SnowflakeIdGenerator(nodeId);
    }
}
