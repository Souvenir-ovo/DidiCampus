package com.didicampus.domain.notify.ports;

import java.time.Instant;
import java.util.List;

public interface NotificationQueryPort {

    List<NotificationView> list(long userId, int page, int size);

    int unreadCount(long userId);

    record NotificationView(long id, long errandId, String type,
                            String content, Instant time) {}
}
