package com.didicampus.presentation.api;

import com.didicampus.application.usecase.MarkNotificationsReadService;
import com.didicampus.application.usecase.QueryNotificationService;
import com.didicampus.domain.notify.ports.NotificationQueryPort;
import com.didicampus.presentation.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping
    public ApiResponse<List<NotificationQueryPort.NotificationView>> list(
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(notificationQuery.list(CurrentUser.id(), page, size));
    }

    @GetMapping("/unread")
    public ApiResponse<UnreadCountResponse> unreadCount() {
        return ApiResponse.ok(new UnreadCountResponse(notificationQuery.unreadCount(CurrentUser.id())));
    }

    @PostMapping("/read")
    public ApiResponse<MarkReadResponse> markAllRead() {
        return ApiResponse.ok(new MarkReadResponse(markReadService.markAllRead(CurrentUser.id())));
    }

    public record UnreadCountResponse(int count) {
    }

    public record MarkReadResponse(int changed) {
    }
}
