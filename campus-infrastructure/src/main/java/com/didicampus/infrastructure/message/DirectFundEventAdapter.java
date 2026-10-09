package com.didicampus.infrastructure.message;

import com.didicampus.domain.wallet.ports.FundEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "didicampus.mq.enabled", havingValue = "false", matchIfMissing = true)
public class DirectFundEventAdapter implements FundEventPort {

    private static final Logger log = LoggerFactory.getLogger(DirectFundEventAdapter.class);

    @Override
    public boolean publishInTransaction(FundEvent event, LocalWork localWork) {
        boolean committed = localWork.execute();
        log.debug("mq disabled, fund event not published, bizNo={}, committed={}",
                event.bizNo(), committed);
        return committed;
    }
}
