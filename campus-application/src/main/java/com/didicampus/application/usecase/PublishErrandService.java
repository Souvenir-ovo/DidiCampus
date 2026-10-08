package com.didicampus.application.usecase;

import com.didicampus.domain.errand.model.Errand;
import com.didicampus.domain.errand.model.ErrandStatus;
import com.didicampus.domain.errand.model.ErrandType;
import com.didicampus.domain.errand.ports.ErrandCachePort;
import com.didicampus.domain.errand.ports.ErrandRepository;
import com.didicampus.domain.grab.ports.GrabSlotPort;
import com.didicampus.domain.wallet.model.AccountType;
import com.didicampus.domain.wallet.model.EscrowOrder;
import com.didicampus.domain.wallet.model.LedgerEntry;
import com.didicampus.domain.wallet.model.WalletAccount;
import com.didicampus.domain.wallet.ports.WalletRepository;
import com.didicampus.shared.BusinessException;
import com.didicampus.shared.ErrorCode;
import com.didicampus.shared.Money;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布跑腿任务的应用层用例。
 *
 * <p>应用层负责把钱包、托管单、任务聚合和抢单名额串成一条业务流程；
 * 具体的 SQL、Redis 命令由 infrastructure 层通过端口提供。</p>
 */
@Service
public class PublishErrandService {

    private static final long ESCROW_OWNER_ID = -1L;
    private static final long SLOT_KEY_TTL_SECONDS = 7 * 24 * 60 * 60L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final GrabSlotPort grabSlotPort;
    private final ErrandCachePort errandCache;
    private final SnowflakeIdGenerator idGenerator;

    public PublishErrandService(ErrandRepository errandRepository,
                                WalletRepository walletRepository,
                                GrabSlotPort grabSlotPort,
                                ErrandCachePort errandCache,
                                SnowflakeIdGenerator idGenerator) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.grabSlotPort = grabSlotPort;
        this.errandCache = errandCache;
        this.idGenerator = idGenerator;
    }

    public record Command(long campusId,
                          long publisherId,
                          ErrandType type,
                          String title,
                          long rewardCents,
                          int slotTotal) {
    }

    public record Result(long errandId, ErrandStatus status, long frozenCents) {
    }

    /**
     * 事务覆盖数据库写入；Redis 初始化放在最后，避免前置业务失败时提前暴露名额。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result publish(Command command) {
        Money reward = Money.fromCents(command.rewardCents());
        long errandId = idGenerator.nextId();

        WalletAccount publisherAccount = walletRepository
                .findByOwner(command.publisherId(), AccountType.USER)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        "publisherId=" + command.publisherId()));

        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_OWNER_ID, AccountType.ESCROW)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ACCOUNT_NOT_FOUND,
                        "escrow account is missing"));

        // CAS 是最终扣款裁决，余额快照只用于构造本次流水的 balanceAfter。
        if (walletRepository.casDebit(publisherAccount.id(), reward) == 0) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_BALANCE,
                    "publisherId=" + command.publisherId());
        }

        String escrowBizNo = LedgerEntry.escrowBizNo(errandId);
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                escrowBizNo,
                publisherAccount.id(),
                command.publisherId(),
                LedgerEntry.Direction.DEBIT,
                reward,
                publisherAccount.available().minus(reward),
                LedgerEntry.RefType.ESCROW,
                errandId));

        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(),
                escrowBizNo,
                escrowAccount.id(),
                command.publisherId(),
                LedgerEntry.Direction.CREDIT,
                reward,
                escrowAccount.available().plus(reward),
                LedgerEntry.RefType.ESCROW,
                errandId));

        if (walletRepository.casCredit(escrowAccount.id(), reward) == 0) {
            throw new BusinessException(ErrorCode.GRAB_CONFLICT,
                    "escrow credit failed, errandId=" + errandId);
        }

        walletRepository.insertEscrow(EscrowOrder.held(
                idGenerator.nextId(),
                command.campusId(),
                errandId,
                command.publisherId(),
                reward));

        Errand errand = Errand.draft(
                errandId,
                command.campusId(),
                command.publisherId(),
                command.type(),
                command.title(),
                reward,
                command.slotTotal());
        errandRepository.insert(errand);

        long oldVersion = errand.version();
        errand.publish(oldVersion);
        if (errandRepository.casPublish(errandId, oldVersion) == 0) {
            throw new BusinessException(ErrorCode.STALE_VERSION,
                    "publish failed, errandId=" + errandId);
        }
        errandRepository.appendStatusLog(
                errandId,
                ErrandStatus.DRAFT,
                ErrandStatus.PUBLISHED,
                errand.round(),
                command.publisherId());

        grabSlotPort.initSlot(errandId, command.slotTotal(), SLOT_KEY_TTL_SECONDS);
        errandCache.registerExisting(errandId);

        return new Result(errandId, ErrandStatus.PUBLISHED, reward.cents());
    }
}
