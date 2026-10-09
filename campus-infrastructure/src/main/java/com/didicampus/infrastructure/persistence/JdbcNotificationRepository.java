package com.didicampus.infrastructure.persistence;

import com.didicampus.domain.notify.ports.NotificationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertIfAbsent(long id, String messageKey, long userId,
                                  long errandId, String type, String content) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO notification
                    (id, msg_key, user_id, errand_id, type, content)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, messageKey, userId, errandId, type, content);
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Override
    public int markAllRead(long userId) {
        return jdbcTemplate.update("""
                UPDATE notification
                SET read_flag = 1
                WHERE user_id = ? AND read_flag = 0
                """, userId);
    }
}
