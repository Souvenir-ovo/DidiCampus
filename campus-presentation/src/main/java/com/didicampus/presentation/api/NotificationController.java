package com.didicampus.presentation.api;

import com.didicampus.application.usecase.MarkNotificationsReadService;
import com.didicampus.application.usecase.QueryNotificationService;
import com.didicampus.domain.notify.ports.NotificationQueryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final QueryNotificationService notificationQuery;
    private final MarkNotificationsReadService markReadService;

    public NotificationController(QueryNotificationService notificationQuery,
                                  MarkNotificationsReadService markReadService) {
        this.notificationQuery = notificationQuery;
        this.markReadService = markReadService;
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<List<NotificationQueryPort.NotificationView>> list(@PathVariable long userId,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notificationQuery.list(userId, page, size));
    }

    @GetMapping("/users/{userId}/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(@PathVariable long userId) {
        return ApiResponse.ok(new UnreadCountResponse(notificationQuery.unreadCount(userId)));
    }

    @PostMapping("/users/{userId}/read-all")
    public ApiResponse<MarkReadResponse> markAllRead(@PathVariable long userId) {
        return ApiResponse.ok(new MarkReadResponse(markReadService.markAllRead(userId)));
    }

    public record UnreadCountResponse(int count) {
    }

    public record MarkReadResponse(int changed) {
    }
}
