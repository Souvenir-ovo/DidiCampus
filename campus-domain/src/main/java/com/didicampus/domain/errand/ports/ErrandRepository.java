package com.didicampus.domain.errand.ports;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;

import java.util.List;
import java.util.Optional;

/**
 * 任务写端口。基础设施层负责实现 SQL，领域层只描述业务需要的原子操作。
 */
public interface ErrandRepository {

    void insert(Errand errand);

    Optional<Errand> findById(long errandId);

    int casLockForRunner(long errandId, long runnerId, long expectedVersion);

    int casPublish(long errandId, long expectedVersion);

    void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to,
                         int round, long operatorId);

    int casAccept(long errandId, long runnerId, long expectedVersion);

    int casTransferToNext(long errandId, long nextRunnerId,
                          long expectedVersion, int expectedRound);

    int casRevertToPublished(long errandId, long expectedVersion, int expectedRound);

    List<Errand> findConfirmTimeout(long timeoutSeconds, int limit);

    int casPickUp(long errandId, long runnerId, long expectedVersion);

    int casDeliver(long errandId, long runnerId, long expectedVersion);

    int casSettle(long errandId, long expectedVersion);

    int casRefundFromDispute(long errandId, long expectedVersion);

    int casCancel(long errandId, long expectedVersion);

    int casDispute(long errandId, long expectedVersion);

    int casSettleFromDispute(long errandId, long expectedVersion);

    List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit);
}
