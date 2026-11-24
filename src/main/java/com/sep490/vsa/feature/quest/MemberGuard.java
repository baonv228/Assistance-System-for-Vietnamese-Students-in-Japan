package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may have quests and xu: signed-in Members (role USER, status ACTIVE). Guests get 401; staff roles get MSG18
 * (403), like the other Member-only APIs (Nhật ngữ, Chủ đề quan tâm, Tin nhắn, Cẩm nang). A locked or deleted account
 * never gets here: SessionUserRefreshFilter already ended its session (401).
 * <p>
 * Lock order of every write of quests and xu: the users row first ({@link #lockMember}, FOR UPDATE), then the quest
 * rows, like InterestService and "Xóa tài khoản" (ProfileService.deleteAccount locks the users row first too).
 */
@Component
public class MemberGuard {

    /** MSG18. */
    public static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";

    private final JdbcTemplate jdbc;

    public MemberGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The signed-in Member's id, without locking (reads). */
    public long member(AppUserPrincipal me) {
        return check(me, false);
    }

    /** The signed-in Member's id with the users row locked FOR UPDATE until the transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long lockMember(AppUserPrincipal me) {
        return check(me, true);
    }

    /** True when this user id is an active Member (no exception; e.g. to decide whether a flash answer has quests). */
    public boolean isMember(long userId) {
        return jdbc.query("SELECT role, status FROM users WHERE id = ?",
                        (rs, i) -> "USER".equals(rs.getString(1)) && "ACTIVE".equals(rs.getString(2)), userId)
                .stream().findFirst().orElse(false);
    }

    /**
     * Locks the users row (FOR UPDATE) of a Member checked before without a lock (PhraseService, GET today) and checks
     * the role and status again under the lock: an account an Admin locked, or "Xóa tài khoản" that ran first while
     * this request waited for the row, gets MSG18 and nothing of this request is written (no quests, xu or answer log
     * brought back to a deleted account).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUserRow(long userId) {
        boolean member = jdbc.query("SELECT role, status FROM users WHERE id = ? FOR UPDATE",
                        (rs, i) -> "USER".equals(rs.getString(1)) && "ACTIVE".equals(rs.getString(2)), userId)
                .stream().findFirst().orElse(false);
        if (!member) {
            throw ApiException.forbidden(NO_PERMISSION);
        }
    }

    private long check(AppUserPrincipal me, boolean lock) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        record Account(String role, String status) {
        }
        Account account = jdbc.query("SELECT role, status FROM users WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                        (rs, i) -> new Account(rs.getString(1), rs.getString(2)), me.id())
                .stream().findFirst()
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw ApiException.forbidden(NO_PERMISSION);
        }
        return me.id();
    }
}
