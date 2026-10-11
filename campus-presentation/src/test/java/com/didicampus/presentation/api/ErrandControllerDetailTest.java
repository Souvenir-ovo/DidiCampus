package com.didicampus.presentation.api;

import com.didicampus.application.usecase.ConfirmErrandService;
import com.didicampus.application.usecase.DeliverErrandService;
import com.didicampus.application.usecase.ErrandActionResolver;
import com.didicampus.application.usecase.ErrandDetail;
import com.didicampus.application.usecase.GetErrandDetailService;
import com.didicampus.application.usecase.GrabErrandService;
import com.didicampus.application.usecase.PickUpErrandService;
import com.didicampus.application.usecase.PublishErrandService;
import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.presentation.auth.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrandControllerDetailTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void detail_response_uses_current_user_to_resolve_available_actions() {
        GetErrandDetailService details = mock(GetErrandDetailService.class);
        ErrandDetail task = new ErrandDetail(
                77L,
                12L,
                1001L,
                2001L,
                "DELIVERY",
                "送到宿舍",
                800L,
                1,
                1,
                "ACCEPTED",
                0,
                3L,
                Instant.now(),
                null);
        when(details.find(77L)).thenReturn(Optional.of(task));

        ErrandController controller = new ErrandController(
                mock(PublishErrandService.class),
                mock(GrabErrandService.class),
                mock(ConfirmErrandService.class),
                mock(PickUpErrandService.class),
                mock(DeliverErrandService.class),
                mock(SettleErrandService.class),
                mock(ErrandQueryPort.class),
                details,
                new ErrandActionResolver(9001L));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTR, 2001L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ApiResponse<ErrandDetailView> response = controller.detail(77L);

        assertEquals("OK", response.code());
        assertTrue(response.data() != null);
        assertEquals("CURRENT_RUNNER", response.data().role());
        assertEquals(java.util.List.of("PICKUP"), response.data().availableActions());
    }
}
