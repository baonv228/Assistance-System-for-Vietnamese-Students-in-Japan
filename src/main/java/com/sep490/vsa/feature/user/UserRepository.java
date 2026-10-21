package com.sep490.vsa.feature.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    // ----- Outcome of a password login (feature/auth AuthService). Each is one UPDATE statement: the database adds to
    // or clears the counter itself, so logins of one account running at the same moment are all counted (S3).
    // The caller runs them in a transaction.

    /** Counts one wrong password, unless the account is locked at {@code now} (then nothing changes and 0 is returned). */
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.failedLoginCount = u.failedLoginCount + 1 "
            + "where u.id = :id and (u.lockedUntil is null or u.lockedUntil <= :now)")
    int countFailedLogin(@Param("id") long id, @Param("now") Instant now);

    /** Locks the account until {@code until} once it has {@code maxAttempts} wrong passwords in a row; 1 when it did. */
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.lockedUntil = :until, u.failedLoginCount = 0 "
            + "where u.id = :id and u.failedLoginCount >= :maxAttempts")
    int lockAfterFailedLogins(@Param("id") long id, @Param("maxAttempts") int maxAttempts,
                              @Param("until") Instant until);

    /**
     * A right password: clears the counter and the lock and sets last_login_at, only for an ACTIVE account that is not
     * locked at {@code now}. 0 means the person may not sign in (locked or blocked meanwhile) and nothing changed.
     */
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.failedLoginCount = 0, u.lockedUntil = null, u.lastLoginAt = :now "
            + "where u.id = :id and u.status = com.sep490.vsa.feature.user.UserStatus.ACTIVE "
            + "and (u.lockedUntil is null or u.lockedUntil <= :now)")
    int recordLogin(@Param("id") long id, @Param("now") Instant now);
}
