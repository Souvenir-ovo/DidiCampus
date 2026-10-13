package com.didicampus.infrastructure.message;

import com.didicampus.domain.wallet.ports.FundEventPort;
import com.didicampus.domain.wallet.ports.WalletRepository;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionChecker;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RocketMqFundEventAdapter implements FundEventPort, AutoCloseable {

    public static final String TOPIC_FUND_EVENT = "errand-fund-event";

    private static final Logger log = LoggerFactory.getLogger(RocketMqFundEventAdapter.class);
    private static final long CHECK_GRACE_MILLIS = 60_000L;

    private final ClientServiceProvider provider;
    private final WalletRepository walletRepository;
    private Producer producer;

    public RocketMqFundEventAdapter(ClientServiceProvider provider,
                                    WalletRepository walletRepository) {
        this.provider = provider;
        this.walletRepository = walletRepository;
    }

    public void bindProducer(Producer producer) {
        this.producer = producer;
    }

    public TransactionChecker transactionChecker() {
        return this::resolveTransaction;
    }

    @Override
    public boolean publishInTransaction(FundEvent event, LocalWork localWork) {
        if (producer == null) {
            throw new IllegalStateException("rocketmq transaction producer is not initialized");
        }

        Transaction transaction;
        try {
            transaction = producer.beginTransaction();
            producer.send(buildMessage(event), transaction);
        } catch (ClientException ex) {
            throw new IllegalStateException("rocketmq half message send failed, bizNo=" + event.bizNo(), ex);
        }

        boolean localCommitted = false;
        try {
            localCommitted = localWork.execute();
            return localCommitted;
        } finally {
            completeTransaction(transaction, event.bizNo(), localCommitted);
        }
    }

    public boolean checkLocalTransaction(String bizNo) {
        return walletRepository.ledgerExists(bizNo);
    }

    TransactionResolution resolveTransaction(MessageView message) {
        String bizNo = message.getKeys().stream().findFirst().orElse("");
        if (checkLocalTransaction(bizNo)) {
            return TransactionResolution.COMMIT;
        }
        long messageAge = System.currentTimeMillis() - message.getBornTimestamp();
        if (messageAge > CHECK_GRACE_MILLIS) {
            log.warn("rocketmq transaction check rollback, bizNo={}, ageMs={}", bizNo, messageAge);
            return TransactionResolution.ROLLBACK;
        }
        return TransactionResolution.UNKNOWN;
    }

    static String toJson(FundEvent event) {
        return "{\"bizNo\":\"%s\",\"type\":\"%s\",\"errandId\":%d,\"publisherId\":%d,"
                .formatted(escape(event.bizNo()), escape(event.type()),
                        event.errandId(), event.publisherId())
                + "\"runnerId\":%d,\"amountCents\":%d,\"commissionCents\":%d}"
                .formatted(event.runnerId(), event.amountCents(), event.commissionCents());
    }

    @Override
    public void close() {
        if (producer == null) {
            return;
        }
        try {
            producer.close();
        } catch (IOException ex) {
            log.warn("rocketmq transaction producer close failed", ex);
        }
    }

    private Message buildMessage(FundEvent event) {
        return provider.newMessageBuilder()
                .setTopic(TOPIC_FUND_EVENT)
                .setKeys(event.bizNo())
                .setTag(event.type())
                .setBody(toJson(event).getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private void completeTransaction(Transaction transaction, String bizNo, boolean committed) {
        try {
            if (committed) {
                transaction.commit();
            } else {
                transaction.rollback();
            }
        } catch (ClientException ex) {
            log.warn("rocketmq transaction finish failed, bizNo={}, committed={}", bizNo, committed, ex);
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
