package com.didicampus.worker;

import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerConfig {

    @Bean
    public SnowflakeIdGenerator workerSnowflakeIdGenerator(
            @Value("${didicampus.node-id:2}") long nodeId) {
        return new SnowflakeIdGenerator(nodeId);
    }
}
