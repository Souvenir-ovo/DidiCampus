package com.didicampus.application.usecase;

/**
 * 延迟任务的主题、幂等键和消息体约定。
 *
 * <p>消息只携带重新查询任务所需的定位信息，不携带任务快照。
 * 消费者收到消息后必须以数据库当前状态为准。</p>
 */
public final class DelayTaskPolicy {

    public static final String CONFIRM_TIMEOUT_TOPIC = "errand-confirm-timeout";
    public static final String AUTO_SETTLE_TOPIC = "errand-auto-settle";

    private DelayTaskPolicy() {
    }

    public static String timeoutKey(long errandId, int round) {
        return "timeout:" + errandId + ":" + round;
    }

    public static String timeoutPayload(long errandId, int round, long version) {
        return "{\"errandId\":%d,\"round\":%d,\"version\":%d"
                .formatted(errandId, round, version) + "}";
    }

    public static String autoSettleKey(long errandId) {
        return "autosettle:" + errandId;
    }

    public static String autoSettlePayload(long errandId) {
        return "{\"errandId\":%d}".formatted(errandId);
    }
}
