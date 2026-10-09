package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.errand.ports.SyncDiffRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public class JdbcSyncDiffRepository implements SyncDiffRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSyncDiffRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(Instant checkTime, long errandId, String field,
                       String dbValue, String cacheValue, boolean fixed) {
        jdbcTemplate.update("""
                INSERT INTO sync_diff
                (check_time, errand_id, field, db_value, cache_value, fixed)
                VALUES (?, ?, ?, ?, ?, ?)
                """, JdbcTime.timestamp(checkTime), errandId, field, dbValue, cacheValue, fixed ? 1 : 0);
    }

    @Override
    public long countSince(Instant since) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM sync_diff
                WHERE check_time >= ?
                """, Long.class, JdbcTime.timestamp(since));
        return count == null ? 0L : count;
    }
}
