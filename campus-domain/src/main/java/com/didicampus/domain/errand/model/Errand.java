package com.didicampus.domain.errand.model;

import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 跑腿任务聚合根。
 *
 * <p>状态、当前跑腿员、名额和轮次只能通过领域行为修改。
 * 数据库最终会用 CAS SQL 再做一次并发裁决，这里的版本校验负责尽早发现
 * 调用方手里的旧快照。</p>
 */
public final class Errand {

    public static final long SYSTEM_OPERATOR = -1L;

    private final long id;
    private final long campusId;
    private final long publisherId;
    private final ErrandType type;
    private final String title;
    private final Money reward;
    private final int slotTotal;
    private final List<StatusChange> statusChanges = new ArrayList<>();

    private Long currentRunnerId;
    private ErrandStatus status;
    private int slotTaken;
    private int round;
    private long version;
    private Instant lockedAt;
    private Instant deliveredAt;

    private Errand(long id, long campusId, long publisherId, ErrandType type, String title,
                   Money reward, int slotTotal, Long currentRunnerId, ErrandStatus status,
                   int slotTaken, int round, long version, Instant lockedAt, Instant deliveredAt) {
        this.id = id;
        this.campusId = campusId;
        this.publisherId = publisherId;
        this.type = Objects.requireNonNull(type, "type");
        this.title = requireTitle(title);
        this.reward = Objects.requireNonNull(reward, "reward");
        this.slotTotal = slotTotal;
        this.currentRunnerId = currentRunnerId;
        this.status = Objects.requireNonNull(status, "status");
        this.slotTaken = slotTaken;
        this.round = round;
        this.version = version;
        this.lockedAt = lockedAt;
        this.deliveredAt = deliveredAt;
    }

    public static Errand draft(long id, long campusId, long publisherId, ErrandType type,
                               String title, Money reward, int slotTotal) {
        Objects.requireNonNull(reward, "reward");
        if (reward.isZero()) {
            throw new IllegalArgumentException("悬赏金额必须大于 0");
        }
        if (slotTotal < 1) {
            throw new IllegalArgumentException("任务名额至少为 1");
        }
        return new Errand(id, campusId, publisherId, type, title, reward, slotTotal,
                null, ErrandStatus.DRAFT, 0, 0, 0, null, null);
    }

    /**
     * 从持久化记录恢复聚合。恢复过程不重新判断历史业务合法性，
     * 因为数据库里的记录是已经发生过的事实。
     */
    public static Errand rehydrate(long id, long campusId, long publisherId, ErrandType type,
                                   String title, Money reward, int slotTotal, Long runnerId,
                                   ErrandStatus status, int slotTaken, int round,
                                   long version, Instant lockedAt) {
        return rehydrate(id, campusId, publisherId, type, title, reward, slotTotal, runnerId,
                status, slotTaken, round, version, lockedAt, null);
    }

    public static Errand rehydrate(long id, long campusId, long publisherId, ErrandType type,
                                   String title, Money reward, int slotTotal, Long runnerId,
                                   ErrandStatus status, int slotTaken, int round,
                                   long version, Instant lockedAt, Instant deliveredAt) {
        return new Errand(id, campusId, publisherId, type, title, reward, slotTotal, runnerId,
                status, slotTaken, round, version, lockedAt, deliveredAt);
    }

    public void transitTo(ErrandStatus target, long expectedVersion) {
        if (version != expectedVersion) {
            throw new BusinessException(
                    ErrorCode.STALE_VERSION,
                    "errandId=" + id + ", expected=" + expectedVersion + ", actual=" + version
            );
        }
        if (!status.canTransitTo(target)) {
            throw new BusinessException(
                    ErrorCode.ILLEGAL_STATE_TRANSITION,
                    status + " -> " + target
            );
        }

        ErrandStatus previous = status;
        status = target;
        version++;
        statusChanges.add(new StatusChange(id, previous, target, round));
    }

    public void publish(long expectedVersion) {
        transitTo(ErrandStatus.PUBLISHED, expectedVersion);
    }

    public void lockBy(long runnerId, long expectedVersion, Instant now) {
        if (!status.grabbable()) {
            throw new BusinessException(ErrorCode.ERRAND_NOT_GRABBABLE, "status=" + status);
        }
        if (!slotAvailable()) {
            throw new BusinessException(
                    ErrorCode.SLOT_FULL,
                    "taken=" + slotTaken + ", total=" + slotTotal
            );
        }
        transitTo(ErrandStatus.LOCKED, expectedVersion);
        currentRunnerId = runnerId;
        slotTaken++;
        lockedAt = Objects.requireNonNull(now, "now");
    }

    public void acceptByRunner(long runnerId, long expectedVersion) {
        requireCurrentRunner(runnerId);
        transitTo(ErrandStatus.ACCEPTED, expectedVersion);
    }

    /**
     * 确认超时后的换人操作。名额不变，轮次递增，旧轮次消息因此失效。
     */
    public void transferToNextRunner(long nextRunnerId, long expectedVersion, Instant now) {
        if (status != ErrandStatus.LOCKED) {
            throw new BusinessException(
                    ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "只有 LOCKED 任务才能流转，当前=" + status
            );
        }
        transitTo(ErrandStatus.LOCKED, expectedVersion);
        currentRunnerId = nextRunnerId;
        round++;
        lockedAt = Objects.requireNonNull(now, "now");
    }

    public void revertToPublished(long expectedVersion) {
        if (status != ErrandStatus.LOCKED) {
            throw new BusinessException(
                    ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "只有 LOCKED 任务才能回退，当前=" + status
            );
        }
        transitTo(ErrandStatus.PUBLISHED, expectedVersion);
        currentRunnerId = null;
        slotTaken = Math.max(0, slotTaken - 1);
        round++;
        lockedAt = null;
    }

    public void pickUp(long runnerId, long expectedVersion) {
        requireCurrentRunner(runnerId);
        transitTo(ErrandStatus.PICKED_UP, expectedVersion);
    }

    public void deliver(long runnerId, long expectedVersion, Instant now) {
        requireCurrentRunner(runnerId);
        transitTo(ErrandStatus.DELIVERED, expectedVersion);
        deliveredAt = Objects.requireNonNull(now, "now");
    }

    public void settle(long operatorId, long expectedVersion) {
        if (operatorId != SYSTEM_OPERATOR && operatorId != publisherId) {
            throw new BusinessException(ErrorCode.NOT_PUBLISHER, "actor=" + operatorId);
        }
        transitTo(ErrandStatus.SETTLED, expectedVersion);
    }

    public void cancelByPublisher(long operatorId, long expectedVersion) {
        if (operatorId != publisherId) {
            throw new BusinessException(ErrorCode.NOT_PUBLISHER, "actor=" + operatorId);
        }
        if (status != ErrandStatus.DRAFT && status != ErrandStatus.PUBLISHED) {
            throw new BusinessException(
                    ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "任务已有跑腿参与，不能直接取消，当前=" + status
            );
        }
        transitTo(ErrandStatus.CANCELLED, expectedVersion);
        slotTaken = 0;
    }

    public void raiseDispute(long operatorId, long expectedVersion) {
        boolean publisher = operatorId == publisherId;
        boolean runner = currentRunnerId != null && currentRunnerId == operatorId;
        if (!publisher && !runner) {
            throw new BusinessException(ErrorCode.NOT_CURRENT_GRABBER, "actor=" + operatorId);
        }
        transitTo(ErrandStatus.DISPUTED, expectedVersion);
    }

    public void arbitrateToRunner(long expectedVersion) {
        requireDisputed();
        transitTo(ErrandStatus.SETTLED, expectedVersion);
    }

    public void arbitrateToPublisher(long expectedVersion) {
        requireDisputed();
        transitTo(ErrandStatus.REFUNDED, expectedVersion);
    }

    public boolean autoSettleDue(Instant now, long delaySeconds) {
        return status == ErrandStatus.DELIVERED
                && deliveredAt != null
                && deliveredAt.plusSeconds(delaySeconds).isBefore(now);
    }

    public boolean confirmTimeout(Instant now, long timeoutSeconds) {
        return status == ErrandStatus.LOCKED
                && lockedAt != null
                && lockedAt.plusSeconds(timeoutSeconds).isBefore(now);
    }

    public boolean slotAvailable() {
        return slotTaken < slotTotal;
    }

    private void requireDisputed() {
        if (status != ErrandStatus.DISPUTED) {
            throw new BusinessException(ErrorCode.NOT_ARBITRABLE, "status=" + status);
        }
    }

    private void requireCurrentRunner(long runnerId) {
        if (currentRunnerId == null || currentRunnerId != runnerId) {
            throw new BusinessException(
                    ErrorCode.NOT_CURRENT_GRABBER,
                    "currentRunner=" + currentRunnerId + ", actor=" + runnerId
            );
        }
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        return title;
    }

    public long id() { return id; }
    public long campusId() { return campusId; }
    public long publisherId() { return publisherId; }
    public ErrandType type() { return type; }
    public String title() { return title; }
    public Money reward() { return reward; }
    public int slotTotal() { return slotTotal; }
    public int slotTaken() { return slotTaken; }
    public Long grabberId() { return currentRunnerId; }
    public ErrandStatus status() { return status; }
    public int round() { return round; }
    public long version() { return version; }
    public Instant lockedAt() { return lockedAt; }
    public Instant deliveredAt() { return deliveredAt; }
    public List<StatusChange> changes() { return List.copyOf(statusChanges); }

    public record StatusChange(long errandId, ErrandStatus from, ErrandStatus to, int round) {}
}
