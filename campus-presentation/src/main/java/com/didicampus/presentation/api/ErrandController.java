package com.didicampus.presentation.api;

import com.didicampus.application.usecase.ConfirmErrandService;
import com.didicampus.application.usecase.CancelErrandService;
import com.didicampus.application.usecase.DeliverErrandService;
import com.didicampus.application.usecase.DisputeErrandService;
import com.didicampus.application.usecase.ArbitrateErrandService;
import com.didicampus.application.usecase.ErrandActionResolver;
import com.didicampus.application.usecase.GetErrandDetailService;
import com.didicampus.application.usecase.GrabErrandService;
import com.didicampus.application.usecase.PickUpErrandService;
import com.didicampus.application.usecase.PublishErrandService;
import com.didicampus.application.usecase.QueryErrandListService;
import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import com.didicampus.presentation.auth.CurrentUser;
import com.didicampus.shared.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/errands")
public class ErrandController {

    private final PublishErrandService publishService;
    private final CancelErrandService cancelService;
    private final GrabErrandService grabService;
    private final ConfirmErrandService confirmService;
    private final PickUpErrandService pickUpService;
    private final DeliverErrandService deliverService;
    private final SettleErrandService settleService;
    private final DisputeErrandService disputeService;
    private final ArbitrateErrandService arbitrateService;
    private final QueryErrandListService queryService;
    private final GetErrandDetailService detailService;
    private final ErrandActionResolver actionResolver;

    public ErrandController(PublishErrandService publishService,
                            CancelErrandService cancelService,
                            GrabErrandService grabService,
                            ConfirmErrandService confirmService,
                            PickUpErrandService pickUpService,
                            DeliverErrandService deliverService,
                            SettleErrandService settleService,
                            DisputeErrandService disputeService,
                            ArbitrateErrandService arbitrateService,
                            QueryErrandListService queryService,
                            GetErrandDetailService detailService,
                            ErrandActionResolver actionResolver) {
        this.publishService = publishService;
        this.cancelService = cancelService;
        this.grabService = grabService;
        this.confirmService = confirmService;
        this.pickUpService = pickUpService;
        this.deliverService = deliverService;
        this.settleService = settleService;
        this.disputeService = disputeService;
        this.arbitrateService = arbitrateService;
        this.queryService = queryService;
        this.detailService = detailService;
        this.actionResolver = actionResolver;
    }

    @PostMapping
    public ApiResponse<PublishResponse> publish(@Valid @RequestBody PublishRequest request) {
        PublishErrandService.Result result = publishService.publish(new PublishErrandService.Command(
                request.campusId(),
                CurrentUser.id(),
                request.type(),
                request.title(),
                request.rewardCents(),
                request.slotTotal()));
        return ApiResponse.ok(new PublishResponse(result.errandId(), result.status().name(), result.frozenCents()));
    }

    @PostMapping("/{errandId}/grab")
    public ApiResponse<GrabResponse> grab(@PathVariable long errandId,
                                          @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        GrabErrandService.Result result = grabService.grab(new GrabErrandService.Command(
                errandId,
                CurrentUser.id(),
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId));
        return ApiResponse.ok(new GrabResponse(
                result.code().name(),
                result.grabbed(),
                result.candidateRank()));
    }

    @PostMapping("/{errandId}/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable long errandId) {
        ConfirmErrandService.Outcome outcome = confirmService.confirm(
                new ConfirmErrandService.Command(errandId, CurrentUser.id()));
        return ApiResponse.ok(new ConfirmResponse(outcome.name()));
    }

    @PostMapping("/{errandId}/pickup")
    public ApiResponse<FulfillmentResponse> pickUp(@PathVariable long errandId) {
        pickUpService.pickUp(new PickUpErrandService.Command(errandId, CurrentUser.id()));
        return ApiResponse.ok(new FulfillmentResponse("PICKED_UP"));
    }

    @PostMapping("/{errandId}/deliver")
    public ApiResponse<FulfillmentResponse> deliver(@PathVariable long errandId) {
        deliverService.deliver(new DeliverErrandService.Command(errandId, CurrentUser.id()));
        return ApiResponse.ok(new FulfillmentResponse("DELIVERED"));
    }

    @PostMapping("/{errandId}/settle")
    public ApiResponse<SettleResponse> settle(@PathVariable long errandId) {
        SettleErrandService.Result result = settleService.settle(errandId, CurrentUser.id());
        return ApiResponse.ok(new SettleResponse(result.name()));
    }

    @PostMapping("/{errandId}/cancel")
    public ApiResponse<CancelResponse> cancel(@PathVariable long errandId) {
        CancelErrandService.Result result = cancelService.cancel(errandId, CurrentUser.id());
        return ApiResponse.ok(new CancelResponse(result.name()));
    }

    @PostMapping("/{errandId}/dispute")
    public ApiResponse<Void> dispute(@PathVariable long errandId) {
        disputeService.raise(errandId, CurrentUser.id());
        return ApiResponse.ok();
    }

    @PostMapping("/{errandId}/arbitrate")
    public ApiResponse<ArbitrateResponse> arbitrate(
            @PathVariable long errandId,
            @Valid @RequestBody ArbitrateRequest request) {
        ArbitrateErrandService.Favor favor = ArbitrateErrandService.Favor.valueOf(
                request.favor().trim().toUpperCase(Locale.ROOT));
        ArbitrateErrandService.Result result = arbitrateService.arbitrate(
                errandId,
                favor,
                CurrentUser.id());
        return ApiResponse.ok(new ArbitrateResponse(result.name()));
    }

    @GetMapping
    public ApiResponse<List<ErrandView>> list(@RequestParam(defaultValue = "1") long campusId,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.list(campusId, status, page, size)
                .stream()
                .map(ErrandView::from)
                .toList());
    }

    @GetMapping("/{errandId}")
    public ApiResponse<ErrandDetailView> detail(@PathVariable long errandId) {
        long viewerId = CurrentUser.id();
        return detailService.find(errandId)
                .map(detail -> ErrandDetailView.from(
                        detail,
                        actionResolver.resolve(detail, viewerId)))
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(
                        ErrorCode.ERRAND_NOT_FOUND.name(),
                        ErrorCode.ERRAND_NOT_FOUND.message()));
    }

    @GetMapping("/mine")
    public ApiResponse<List<ErrandView>> mine(
            @RequestParam(defaultValue = "PUBLISHED_BY_ME") String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        long currentUser = CurrentUser.id();
        List<com.didicampus.domain.errand.model.Errand> errands =
                "GRABBED_BY_ME".equalsIgnoreCase(role)
                        ? queryService.grabbedBy(currentUser, page, size)
                        : queryService.publishedBy(currentUser, page, size);
        return ApiResponse.ok(errands.stream().map(ErrandView::from).toList());
    }

    @GetMapping("/cursor")
    public ApiResponse<CursorPageView> listByCursor(
                                                            @RequestParam(defaultValue = "1") long campusId,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(defaultValue = "20") int size) {
        QueryErrandListService.CursorPage<com.didicampus.domain.errand.model.Errand> page =
                queryService.listByCursor(campusId, status, cursor, size);
        return ApiResponse.ok(new CursorPageView(
                page.items().stream().map(ErrandView::from).toList(),
                page.nextCursor()));
    }

    @GetMapping("/publishers/{publisherId}")
    public ApiResponse<List<ErrandView>> byPublisher(@PathVariable long publisherId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.publishedBy(publisherId, page, size)
                .stream()
                .map(ErrandView::from)
                .toList());
    }

    @GetMapping("/runners/{runnerId}")
    public ApiResponse<List<ErrandView>> byRunner(@PathVariable long runnerId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.grabbedBy(runnerId, page, size)
                .stream()
                .map(ErrandView::from)
                .toList());
    }

    @GetMapping("/{errandId}/status-log")
    public ApiResponse<List<ErrandQueryPort.StatusChange>> statusLog(@PathVariable long errandId) {
        return ApiResponse.ok(queryService.statusLog(errandId));
    }

    public record PublishRequest(@Min(1) long campusId,
                                 @NotNull ErrandType type,
                                 @NotBlank String title,
                                 @Min(1) long rewardCents,
                                 @Min(1) int slotTotal) {
    }

    public record PublishResponse(long errandId, String status, long frozenCents) {
    }

    public record GrabResponse(String code, boolean grabbed, Long candidateRank) {
    }

    public record ConfirmResponse(String outcome) {
    }

    public record FulfillmentResponse(String outcome) {
    }

    public record SettleResponse(String result) {
    }

    public record CancelResponse(String result) {
    }

    public record ArbitrateRequest(@NotBlank String favor) {
    }

    public record ArbitrateResponse(String result) {
    }

    public record CursorPageView(List<ErrandView> items, String nextCursor) {
    }
}
