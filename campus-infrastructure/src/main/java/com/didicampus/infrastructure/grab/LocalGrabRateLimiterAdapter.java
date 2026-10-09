package com.didicampus.infrastructure.grab;

import com.didicampus.domain.grab.ports.GrabRateLimiterPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "didicampus.grab.limit-mode", havingValue = "local", matchIfMissing = true)
public class LocalGrabRateLimiterAdapter implements GrabRateLimiterPort {

    private final boolean enabled;
    private final int permitsPerSecond;
    private final Map<Long, WindowCounter> counters = new ConcurrentHashMap<>();

    public LocalGrabRateLimiterAdapter(
            @Value("${didicampus.grab.limit-enabled:true}") boolean enabled,
            @Value("${didicampus.grab.limit-per-second:500}") int permitsPerSecond) {
        this.enabled = enabled;
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    @Override
    public boolean tryPass(long errandId, long runnerId) {
        if (!enabled) {
            return true;
        }
        long second = System.currentTimeMillis() / 1000L;
        WindowCounter counter = counters.compute(errandId, (id, old) ->
                old == null || old.second != second ? new WindowCounter(second) : old);
        boolean passed = counter.count.incrementAndGet() <= permitsPerSecond;
        if ((second & 0x1f) == 0) {
            prune(second);
        }
        return passed;
    }

    private void prune(long currentSecond) {
        Iterator<Map.Entry<Long, WindowCounter>> iterator = counters.entrySet().iterator();
        while (iterator.hasNext()) {
            if (currentSecond - iterator.next().getValue().second > 60) {
                iterator.remove();
            }
        }
    }

    private static final class WindowCounter {
        private final long second;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long second) {
            this.second = second;
        }
    }
}
