package com.didicampus.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 抢单 Lua 脚本装配。
 *
 * <p>只有启用 Redis 名额模式时才创建脚本 Bean，默认内存模式不会要求
 * Redis 相关配置参与应用启动。</p>
 */
@Configuration
@ConditionalOnProperty(name = "didicampus.grab.slot-mode", havingValue = "redis")
public class RedisLuaConfig {

    @Bean("grabScript")
    public RedisScript<Long> grabScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/grab.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean("rollbackSlotScript")
    public RedisScript<Long> rollbackSlotScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rollback_slot.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
