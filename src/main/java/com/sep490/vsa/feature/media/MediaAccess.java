package com.sep490.vsa.feature.media;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Who may download a stored photo or video at {@code /media/<storage_key>} ({@link MediaController}). Files are
 * written before their scan and kept after a post is hidden, so the folder itself is never public.
 * <p>
 * Where media is attached today (every foreign key to {@code media}):
 * <ul>
 *   <li>{@code blog_media}: photos and the video of posts and questions (Write Post, Đặt câu hỏi and their edits);</li>
 *   <li>{@code tag_requests.proof_media_id}: proof photos of identity-tag requests (identity documents);</li>
 *   <li>{@code review_media}: photos and the video of event reviews (feature/event; place reviews later);</li>
 *   <li>{@code events.cover_media_id}: cover photos Moderators upload for events.</li>
 * </ul>
 * The rule:
 * <ol>
 *   <li><b>Anyone</b> (guests too) gets the file only when all of these hold: it is not deleted
 *       ({@code deleted_at IS NULL}), its scan passed ({@code scan_status = 'PASSED'}), and it is attached to at least
 *       one PUBLISHED post or question whose author is ACTIVE ({@link ActiveAuthors}), or to a PUBLISHED review of
 *       an ACTIVE author whose event (if any) is not deleted, or it is the cover of an event that is not deleted.
 *       The answer may be cached for
 *       {@link #PUBLIC_CACHE}, so a post hidden later stops being served within that time.</li>
 *   <li><b>Its owner</b> ({@code media.owner_id}, signed in, account ACTIVE) may always see their own file while it is
 *       not deleted, so the edit page and the author's view of a post that is being scanned, rejected or hidden keep
 *       working. That answer is {@code private, no-store}. The owner's status is read here because
 *       {@link com.sep490.vsa.common.security.SessionUserRefreshFilter} skips {@code /media/}.</li>
 *   <li><b>Everyone else</b> gets 404: files being scanned or rejected, deleted files, files of hidden, removed or
 *       rejected posts or of locked or deleted authors, tag proofs, and files attached to nothing.</li>
 * </ol>
 * When Moderators get their review queue (deferred), its rule is added here.
 */
@Service
public class MediaAccess {

    /** How long browsers and proxies may keep a public file (cần chốt). */
    static final Duration PUBLIC_CACHE = Duration.ofDays(1);

    /** PUBLIC: anyone, cacheable; OWNER: only the signed-in owner, never cached. */
    public enum Audience { PUBLIC, OWNER }

    public record Allowed(String mimeType, Audience audience) {
    }

    private record Row(long ownerId, String mimeType, boolean shown, boolean ownerActive) {
    }

    private final JdbcTemplate jdbc;

    public MediaAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether the file with this key may be sent to this viewer, and to whom the answer belongs.
     *
     * @param viewerId the signed-in user, or null for a guest
     * @return empty when the answer is 404
     */
    public Optional<Allowed> check(String storageKey, Long viewerId) {
        Row row = jdbc.query("""
                SELECT m.owner_id, m.mime_type,
                       m.scan_status = 'PASSED' AND (EXISTS (
                           SELECT 1 FROM blog_media bm JOIN blogs b ON b.id = bm.blog_id
                           WHERE bm.media_id = m.id AND b.status = 'PUBLISHED' AND %s)
                         OR EXISTS (
                           SELECT 1 FROM review_media rm JOIN reviews r ON r.id = rm.review_id
                           WHERE rm.media_id = m.id AND r.status = 'PUBLISHED' AND %s
                             AND (r.event_id IS NULL OR EXISTS (
                                 SELECT 1 FROM events re WHERE re.id = r.event_id AND re.deleted_at IS NULL)))
                         OR EXISTS (
                           SELECT 1 FROM events ce WHERE ce.cover_media_id = m.id AND ce.deleted_at IS NULL)) AS shown,
                       EXISTS (SELECT 1 FROM users o WHERE o.id = m.owner_id AND o.status = 'ACTIVE') AS owner_active
                FROM media m WHERE m.storage_key = ? AND m.deleted_at IS NULL""".formatted(ActiveAuthors.of("b"),
                        ActiveAuthors.of("r")),
                (rs, i) -> new Row(rs.getLong("owner_id"), rs.getString("mime_type"), rs.getBoolean("shown"),
                        rs.getBoolean("owner_active")), storageKey).stream().findFirst().orElse(null);
        if (row == null) {
            return Optional.empty();
        }
        if (row.shown()) {
            return Optional.of(new Allowed(row.mimeType(), Audience.PUBLIC));
        }
        if (viewerId != null && viewerId == row.ownerId() && row.ownerActive()) {
            return Optional.of(new Allowed(row.mimeType(), Audience.OWNER));
        }
        return Optional.empty();
    }
}
