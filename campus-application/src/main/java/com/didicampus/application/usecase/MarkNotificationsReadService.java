package com.didicampus.application.usecase;

import com.didicampus.domain.notify.ports.NotificationRepository;
import org.springframework.stereotype.Service;

@Service
public class MarkNotificationsReadService {

    private final NotificationRepository notificationRepository;

    public MarkNotificationsReadService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public int markAllRead(long userId) {
        return notificationRepository.markAllRead(userId);
    }
}
