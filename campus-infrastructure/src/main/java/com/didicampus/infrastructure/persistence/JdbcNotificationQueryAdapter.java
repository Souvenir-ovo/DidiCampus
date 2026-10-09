package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.notify.ports.NotificationQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcNotificationQueryAdapter implements NotificationQueryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<NotificationView> list(long userId, int page, int size) {
        return jdbcTemplate.query("""
                SELECT id, errand_id, type, content, created_at
                FROM notification
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new NotificationView(
                rs.getLong("id"),
                rs.getLong("errand_id"),
                rs.getString("type"),
                rs.getString("content"),
                JdbcTime.instant(rs.getTimestamp("created_at"))), userId, size, Math.max(page, 0) * size);
    }

    @Override
    public int unreadCount(long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM notification
                WHERE user_id = ? AND read_flag = 0
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }
}
