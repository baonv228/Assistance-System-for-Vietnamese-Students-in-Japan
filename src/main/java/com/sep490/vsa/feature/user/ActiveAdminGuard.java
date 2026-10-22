package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps at least one ACTIVE Admin. Without one nobody can manage accounts, settings or keywords any more and only SQL
 * can recover, so an Admin account is never deleted, locked or moved to another role when it is the last one.
 */
@Component
public class ActiveAdminGuard {

    public static final String LAST_ADMIN = "Hệ thống cần ít nhất một quản trị viên đang hoạt động.";

    private final JdbcTemplate jdbc;

    public ActiveAdminGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Answers 400 unless another ACTIVE Admin than {@code userId} remains. Runs in the caller's transaction and reads
     * the other Admins' rows FOR UPDATE: two Admins demoting, locking or deleting each other at the same moment are
     * done one after the other, and the second one sees the first change and is refused.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertAnotherActiveAdmin(long userId) {
        Long others = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id <> ? FOR UPDATE",
                Long.class, userId);
        if (others == null || others < 1) {
            throw ApiException.badRequest(LAST_ADMIN);
        }
    }
}
