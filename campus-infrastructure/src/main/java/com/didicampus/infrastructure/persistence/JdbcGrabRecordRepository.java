package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.grab.model.GrabRecord;
import com.didicampus.domain.grab.ports.GrabRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGrabRecordRepository implements GrabRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcGrabRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(GrabRecord record) {
        jdbcTemplate.update("""
                INSERT INTO grab_record
                (id, campus_id, errand_id, runner_id, seq, round, result, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(),
                record.campusId(),
                record.errandId(),
                record.runnerId(),
                record.seq(),
                record.round(),
                record.result().name(),
                JdbcTime.timestamp(record.createdAt()));
    }

    @Override
    public int countGrabbed(long campusId, long errandId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM grab_record
                WHERE campus_id = ? AND errand_id = ? AND result = ?
                """, Integer.class, campusId, errandId, GrabRecord.GrabResultType.GRABBED.name());
        return count == null ? 0 : count;
    }
}
