package com.didicampus.infrastructure.grab;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.didicampus.domain.grab.ports.GrabRateLimiterPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 热点参数限流适配器。
 *
 * <p>资源代表抢单入口，参数代表具体任务 ID。这样一个热门任务达到阈值时，
 * 不会把其他任务的抢单请求一起拒绝。</p>
 */
@Primary
@Component
@ConditionalOnProperty(name = "didicampus.grab.limit-mode", havingValue = "sentinel")
public class SentinelGrabRateLimiterAdapter implements GrabRateLimiterPort {

    private final boolean enabled;
    private final String resourceName;

    public SentinelGrabRateLimiterAdapter(
            @Value("${didicampus.grab.limit-enabled:true}") boolean enabled,
            @Value("${didicampus.grab.limit-per-second:500}") int permitsPerSecond,
            @Value("${didicampus.grab.limit-resource:didicampus-grab}") String resourceName) {
        this.enabled = enabled;
        this.resourceName = resourceName;
        installRule(resourceName, Math.max(1, permitsPerSecond));
    }

    @Override
    public boolean tryPass(long errandId, long runnerId) {
        if (!enabled) {
            return true;
        }

        Entry entry = null;
        try {
            entry = SphU.entry(resourceName, EntryType.IN, 1, errandId);
            return true;
        } catch (BlockException ex) {
            return false;
        } finally {
            if (entry != null) {
                entry.exit(1, errandId);
            }
        }
    }

    private static void installRule(String resourceName, int permitsPerSecond) {
        ParamFlowRule rule = new ParamFlowRule(resourceName)
                .setParamIdx(0)
                .setCount(permitsPerSecond)
                .setDurationInSec(1);
        List<ParamFlowRule> rules = new ArrayList<>(ParamFlowRuleManager.getRules());
        rules.removeIf(existing -> resourceName.equals(existing.getResource()));
        rules.add(rule);
        ParamFlowRuleManager.loadRules(rules);
    }
}
