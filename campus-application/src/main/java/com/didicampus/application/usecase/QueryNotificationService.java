package com.didicampus.application.usecase;

import com.didicampus.domain.notify.ports.NotificationQueryPort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryNotificationService {

    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationQueryPort queryPort;

    public QueryNotificationService(NotificationQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    public List<NotificationQueryPort.NotificationView> list(long userId,
                                                              int page,
                                                              int size) {
        return queryPort.list(userId, Math.max(page, 0), cap(size));
    }

    public int unreadCount(long userId) {
        return queryPort.unreadCount(userId);
    }

    private int cap(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
