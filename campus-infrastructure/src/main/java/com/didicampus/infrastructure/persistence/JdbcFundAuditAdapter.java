package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.wallet.ports.FundAuditPort;
import com.didicampus.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcFundAuditAdapter implements FundAuditPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcFundAuditAdapter.class);

    private final JdbcTemplate jdbcTemplate;
    private final SnowflakeIdGenerator idGenerator;

    public JdbcFundAuditAdapter(JdbcTemplate jdbcTemplate, SnowflakeIdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String bizNo, String action, long errandId, long operatorId,
                       String detailJson, boolean success, String message) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO fund_audit_log
                    (id, biz_no, action, errand_id, operator_id, detail, result, message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    idGenerator.nextId(),
                    bizNo,
                    action,
                    errandId,
                    operatorId,
                    detailJson,
                    success ? "SUCCESS" : "FAILED",
                    shorten(message, 255));
        } catch (RuntimeException ex) {
            log.warn("fund audit write failed, bizNo={}, action={}", bizNo, action, ex);
        }
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
