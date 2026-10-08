package com.didicampus.domain.notify.ports;

public interface NotificationRepository {

    boolean insertIfAbsent(long id, String messageKey, long userId,
                           long errandId, String type, String content);

    int markAllRead(long userId);
}
