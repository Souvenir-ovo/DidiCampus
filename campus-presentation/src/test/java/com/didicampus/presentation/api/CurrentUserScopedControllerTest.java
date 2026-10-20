package com.didicampus.presentation.api;

import com.didicampus.application.usecase.MarkNotificationsReadService;
import com.didicampus.application.usecase.QueryCreditService;
import com.didicampus.application.usecase.QueryNotificationService;
import com.didicampus.application.usecase.QueryWalletService;
import com.didicampus.domain.credit.model.CreditScore;
import com.didicampus.domain.notify.ports.NotificationQueryPort;
import com.didicampus.domain.wallet.ports.WalletQueryPort;
import com.didicampus.presentation.auth.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentUserScopedControllerTest {

    @BeforeEach
    void bind_current_user() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTR, 1001L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear_request_context() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void wallet_reads_balance_for_authenticated_user() {
        QueryWalletService service = mock(QueryWalletService.class);
        when(service.balance(1001L)).thenReturn(new WalletQueryPort.BalanceView(9000L, 100L));

        ApiResponse<WalletQueryPort.BalanceView> response = new WalletController(service).balance();

        assertEquals(9000L, response.data().availableCents());
        verify(service).balance(1001L);
    }

    @Test
    void credit_self_uses_authenticated_user() {
        QueryCreditService service = mock(QueryCreditService.class);
        when(service.score(1001L)).thenReturn(new CreditScore(1001L, 88, 3));

        ApiResponse<CreditController.CreditView> response = new CreditController(service).score();

        assertEquals(88, response.data().score());
        verify(service).score(1001L);
    }

    @Test
    void notifications_never_accept_a_user_id_from_the_url() {
        QueryNotificationService query = mock(QueryNotificationService.class);
        MarkNotificationsReadService read = mock(MarkNotificationsReadService.class);
        when(query.list(1001L, 0, 20)).thenReturn(List.of());
        when(query.unreadCount(1001L)).thenReturn(2);
        when(read.markAllRead(1001L)).thenReturn(2);
        NotificationController controller = new NotificationController(query, read);

        controller.list(0, 20);
        controller.unreadCount();
        controller.markAllRead();

        verify(query).list(1001L, 0, 20);
        verify(query).unreadCount(1001L);
        verify(read).markAllRead(1001L);
    }
}
