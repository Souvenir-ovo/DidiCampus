package com.didicampus.presentation.api;

import com.didicampus.application.usecase.ArbitrateErrandService;
import com.didicampus.application.usecase.CancelErrandService;
import com.didicampus.application.usecase.ConfirmErrandService;
import com.didicampus.application.usecase.DeliverErrandService;
import com.didicampus.application.usecase.DisputeErrandService;
import com.didicampus.application.usecase.ErrandActionResolver;
import com.didicampus.application.usecase.GetErrandDetailService;
import com.didicampus.application.usecase.GrabErrandService;
import com.didicampus.application.usecase.PickUpErrandService;
import com.didicampus.application.usecase.PublishErrandService;
import com.didicampus.application.usecase.QueryErrandListService;
import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.presentation.auth.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrandControllerIdentityTest {

    private static final long CURRENT_USER = 2001L;

    private PublishErrandService publishService;
    private GrabErrandService grabService;
    private ConfirmErrandService confirmService;
    private PickUpErrandService pickUpService;
    private DeliverErrandService deliverService;
    private SettleErrandService settleService;
    private ErrandController controller;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUser.ATTR, CURRENT_USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        publishService = mock(PublishErrandService.class);
        grabService = mock(GrabErrandService.class);
        confirmService = mock(ConfirmErrandService.class);
        pickUpService = mock(PickUpErrandService.class);
        deliverService = mock(DeliverErrandService.class);
        settleService = mock(SettleErrandService.class);
        controller = new ErrandController(
                publishService,
                mock(CancelErrandService.class),
                grabService,
                confirmService,
                pickUpService,
                deliverService,
                settleService,
                mock(DisputeErrandService.class),
                mock(ArbitrateErrandService.class),
                mock(QueryErrandListService.class),
                mock(GetErrandDetailService.class),
                new ErrandActionResolver(9001L));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void task_commands_use_current_user_for_every_authenticated_action() {
        when(publishService.publish(any())).thenReturn(
                new PublishErrandService.Result(77L, ErrandStatus.PUBLISHED, 800L));
        when(grabService.grab(any())).thenReturn(GrabErrandService.Result.success());
        when(confirmService.confirm(any())).thenReturn(ConfirmErrandService.Outcome.CONFIRMED);
        when(settleService.settle(77L, CURRENT_USER)).thenReturn(SettleErrandService.Result.SETTLED);
        doNothing().when(pickUpService).pickUp(any());
        doNothing().when(deliverService).deliver(any());

        controller.publish(new ErrandController.PublishRequest(
                1L, ErrandType.DELIVERY, "送到宿舍", 800L, 1));
        controller.grab(77L, "request-1");
        controller.confirm(77L);
        controller.pickUp(77L);
        controller.deliver(77L);
        controller.settle(77L);

        verify(publishService).publish(argThat(command -> command.publisherId() == CURRENT_USER));
        verify(grabService).grab(argThat(command -> command.runnerId() == CURRENT_USER
                && command.requestId().equals("request-1")));
        verify(confirmService).confirm(argThat(command -> command.runnerId() == CURRENT_USER));
        verify(pickUpService).pickUp(argThat(command -> command.runnerId() == CURRENT_USER));
        verify(deliverService).deliver(argThat(command -> command.runnerId() == CURRENT_USER));
        verify(settleService).settle(77L, CURRENT_USER);
    }
}
