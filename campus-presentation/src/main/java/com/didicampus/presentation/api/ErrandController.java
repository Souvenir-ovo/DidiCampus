package com.didicampus.presentation.api;

import com.didicampus.application.usecase.ConfirmErrandService;
import com.didicampus.application.usecase.DeliverErrandService;
import com.didicampus.application.usecase.GrabErrandService;
import com.didicampus.application.usecase.PickUpErrandService;
import com.didicampus.application.usecase.PublishErrandService;
import com.didicampus.application.usecase.SettleErrandService;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandQueryPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/errands")
public class ErrandController {

    private final PublishErrandService publishService;
    private final GrabErrandService grabService;
    private final ConfirmErrandService confirmService;
    private final PickUpErrandService pickUpService;
    private final DeliverErrandService deliverService;
    private final SettleErrandService settleService;
    private final ErrandQueryPort errandQuery;

    public ErrandController(PublishErrandService publishService,
                            GrabErrandService grabService,
                            ConfirmErrandService confirmService,
                            PickUpErrandService pickUpService,
                            DeliverErrandService deliverService,
                            SettleErrandService settleService,
                            ErrandQueryPort errandQuery) {
        this.publishService = publishService;
        this.grabService = grabService;
        this.confirmService = confirmService;
        this.pickUpService = pickUpService;
        this.deliverService = deliverService;
        this.settleService = settleService;
        this.errandQuery = errandQuery;
    }

    @PostMapping
    public ApiResponse<PublishResponse> publish(@Valid @RequestBody PublishRequest request) {
        PublishErrandService.Result result = publishService.publish(new PublishErrandService.Command(
                request.campusId(),
                request.publisherId(),
                request.type(),
                request.title(),
                request.rewardCents(),
                request.slotTotal()));
        return ApiResponse.ok(new PublishResponse(result.errandId(), result.status().name(), result.frozenCents()));
    }

    @PostMapping("/{errandId}/grab")
    public ApiResponse<GrabResponse> grab(@PathVariable long errandId,
                                          @Valid @RequestBody GrabRequest request) {
        GrabErrandService.Result result = grabService.grab(new GrabErrandService.Command(
                errandId,
                request.runnerId(),
                request.requestId()));
        return ApiResponse.ok(new GrabResponse(
                result.code().name(),
                result.grabbed(),
                result.candidateRank()));
    }

    @PostMapping("/{errandId}/confirm")
    public ApiResponse<ConfirmResponse> confirm(@PathVariable long errandId,
                                                @Valid @RequestBody ConfirmRequest request) {
        ConfirmErrandService.Outcome outcome = confirmService.confirm(
                new ConfirmErrandService.Command(errandId, request.runnerId()));
        return ApiResponse.ok(new ConfirmResponse(outcome.name()));
    }

    @PostMapping("/{errandId}/pickup")
    public ApiResponse<FulfillmentResponse> pickUp(@PathVariable long errandId,
                                                   @Valid @RequestBody RunnerActionRequest request) {
        pickUpService.pickUp(new PickUpErrandService.Command(errandId, request.runnerId()));
        return ApiResponse.ok(new FulfillmentResponse("PICKED_UP"));
    }

    @PostMapping("/{errandId}/deliver")
    public ApiResponse<FulfillmentResponse> deliver(@PathVariable long errandId,
                                                    @Valid @RequestBody RunnerActionRequest request) {
        deliverService.deliver(new DeliverErrandService.Command(errandId, request.runnerId()));
        return ApiResponse.ok(new FulfillmentResponse("DELIVERED"));
    }

    @PostMapping("/{errandId}/settle")
    public ApiResponse<SettleResponse> settle(@PathVariable long errandId,
                                              @Valid @RequestBody SettleRequest request) {
        SettleErrandService.Result result = settleService.settle(errandId, request.operatorId());
        return ApiResponse.ok(new SettleResponse(result.name()));
    }

    @GetMapping
    public ApiResponse<List<ErrandView>> list(@RequestParam long campusId,
                                              @RequestParam String status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(errandQuery.list(campusId, status, page, size)
                .stream()
                .map(ErrandView::from)
                .toList());
    }

    @GetMapping("/cursor")
    public ApiResponse<List<CursorErrandView>> listByCursor(@RequestParam long campusId,
                                                            @RequestParam String status,
                                                            @RequestParam(required = false) Instant beforeCreatedAt,
                                                            @RequestParam(required = false) Long beforeId,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(errandQuery.listByCursor(campusId, status, beforeCreatedAt, beforeId, size)
                .stream()
                .map(item -> new CursorErrandView(ErrandView.from(item.errand()), item.createdAt()))
                .toList());
    }

    @GetMapping("/publishers/{publisherId}")
    public ApiResponse<List<ErrandView>> byPublisher(@PathVariable long publisherId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(errandQuery.listByPublisher(publisherId, page, size)
                .stream()
                .map(ErrandView::from)
                .toList());
    }

    @GetMapping("/runners/{runnerId}")
    public ApiResponse<List<ErrandView>> byRunner(@PathVariable long runnerId,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(errandQuery.listByRunner(runnerId, page, size)
                .stream()
                .map(ErrandView::from)
                .toList());
    }

    @GetMapping("/{errandId}/status-log")
    public ApiResponse<List<ErrandQueryPort.StatusChange>> statusLog(@PathVariable long errandId,
                                                                     @RequestParam long campusId) {
        return ApiResponse.ok(errandQuery.statusLog(campusId, errandId));
    }

    public record PublishRequest(@Min(1) long campusId,
                                 @Min(1) long publisherId,
                                 @NotNull ErrandType type,
                                 @NotBlank String title,
                                 @Min(1) long rewardCents,
                                 @Min(1) int slotTotal) {
    }

    public record PublishResponse(long errandId, String status, long frozenCents) {
    }

    public record GrabRequest(@Min(1) long runnerId, @NotBlank String requestId) {
    }

    public record GrabResponse(String code, boolean grabbed, Long candidateRank) {
    }

    public record ConfirmRequest(@Min(1) long runnerId) {
    }

    public record ConfirmResponse(String outcome) {
    }

    public record RunnerActionRequest(@Min(1) long runnerId) {
    }

    public record FulfillmentResponse(String outcome) {
    }

    public record SettleRequest(long operatorId) {
    }

    public record SettleResponse(String result) {
    }

    public record CursorErrandView(ErrandView errand, Instant createdAt) {
    }
}
