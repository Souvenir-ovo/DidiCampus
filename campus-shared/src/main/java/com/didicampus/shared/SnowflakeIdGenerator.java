package com.didicampus.shared;

/**
 * 单实例雪花 ID 生成器。
 *
 * <p>布局为：时间戳 41 位 + 节点号 10 位 + 序列号 12 位。
 * 节点号由部署配置提供，后续接入 Redis 后可以改为启动时动态分配。</p>
 */
public final class SnowflakeIdGenerator {

    private static final long CUSTOM_EPOCH = 1_735_689_600_000L;
    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_NODE = (1L << NODE_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int NODE_SHIFT = SEQUENCE_BITS;
    private static final int TIME_SHIFT = NODE_BITS + SEQUENCE_BITS;

    private final long nodeId;
    private long previousMillis = -1L;
    private long sequence;

    public SnowflakeIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE) {
            throw new IllegalArgumentException("nodeId 必须在 0.." + MAX_NODE + " 之间");
        }
        this.nodeId = nodeId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < previousMillis) {
            throw new IllegalStateException("系统时钟发生回拨，暂拒绝生成 ID");
        }

        if (now == previousMillis) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                now = waitUntilNextMillis(previousMillis);
            }
        } else {
            sequence = 0;
        }

        previousMillis = now;
        return ((now - CUSTOM_EPOCH) << TIME_SHIFT)
                | (nodeId << NODE_SHIFT)
                | sequence;
    }

    private long waitUntilNextMillis(long previous) {
        long current = System.currentTimeMillis();
        while (current <= previous) {
            Thread.onSpinWait();
            current = System.currentTimeMillis();
        }
        return current;
    }
}
