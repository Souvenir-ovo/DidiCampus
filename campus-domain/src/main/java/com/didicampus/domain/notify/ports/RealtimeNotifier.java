package com.didicampus.domain.notify.ports;

public interface RealtimeNotifier {

    void errandStatusChanged(long errandId, long publisherId,
                             Long grabberId, String status, int round);

    void notificationArrived(long userId, long errandId,
                             String type, String content);

    void creditChanged(long userId, int newScore, int delta, String reason);
}
