package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.grab.model.GrabRecord;
import com.didicampus.domain.grab.ports.GrabRecordRepository;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 抢单的数据库事务步骤。
 *
 * <p>单独拆成 Bean 是为了让 {@code @Transactional} 通过 Spring 代理生效。
 * 外层服务负责 Redis 补偿，本类只负责 CAS 更新、抢单记录和状态日志。</p>
 */
@Service
public class GrabErrandCommitService {

    private final ErrandRepository errandRepository;
    private final GrabRecordRepository grabRecordRepository;
    private final SnowflakeIdGenerator idGenerator;

    public GrabErrandCommitService(ErrandRepository errandRepository,
                                   GrabRecordRepository grabRecordRepository,
                                   SnowflakeIdGenerator idGenerator) {
        this.errandRepository = errandRepository;
        this.grabRecordRepository = grabRecordRepository;
        this.idGenerator = idGenerator;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean commit(CommitCommand command) {
        Errand errand = errandRepository.findById(command.errandId()).orElse(null);
        if (errand == null) {
            return false;
        }

        ErrandStatus sourceStatus = errand.status();
        int takenBefore = errand.slotTaken();
        long expectedVersion = errand.version();

        // 领域对象做第一层规则检查，数据库 CAS 做最终并发裁决。
        errand.lockBy(command.runnerId(), expectedVersion, Instant.now());
        if (errandRepository.casLockForRunner(
                command.errandId(), command.runnerId(), expectedVersion) == 0) {
            return false;
        }

        grabRecordRepository.insert(GrabRecord.grabbed(
                command.recordId(),
                errand.campusId(),
                command.errandId(),
                command.runnerId(),
                takenBefore + 1,
                errand.round()));
        errandRepository.appendStatusLog(
                command.errandId(),
                sourceStatus,
                ErrandStatus.LOCKED,
                errand.round(),
                command.runnerId());
        return true;
    }

    public CommitCommand prepare(long errandId, long runnerId) {
        return new CommitCommand(errandId, runnerId, idGenerator.nextId());
    }

    public record CommitCommand(long errandId, long runnerId, long recordId) {
    }
}
