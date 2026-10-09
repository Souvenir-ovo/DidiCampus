package com.didicampus.infrastructure.realtime;

import com.didicampus.domain.notify.ports.RealtimeNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "didicampus.ws.enabled", havingValue = "false", matchIfMissing = true)
public class NoopRealtimeNotifier implements RealtimeNotifier {

    @Override
    public void errandStatusChanged(long errandId, long publisherId,
                                    Long grabberId, String status, int round) {
    }

    @Override
    public void notificationArrived(long userId, long errandId, String type, String content) {
    }

    @Override
    public void creditChanged(long userId, int newScore, int delta, String reason) {
    }
}
