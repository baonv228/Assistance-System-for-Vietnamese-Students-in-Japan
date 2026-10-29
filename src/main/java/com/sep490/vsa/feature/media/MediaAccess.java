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
 *   <li>{@code review_media}: photos and the video of place reviews (Viết review, Cắm ghim and their edits) and of
 *       event reviews (feature/event);</li>
 *   <li>{@code events.cover_media_id}: cover photos Moderators upload for events.</li>
 * </ul>
 * The rule:
 * <ol>
 *   <li><b>Anyone</b> (guests too) gets the file only when all of these hold: it is not deleted
 *       ({@code deleted_at IS NULL}), its scan passed ({@code scan_status = 'PASSED'}), and it is attached to at least
 *       one PUBLISHED post or question whose author is ACTIVE ({@link ActiveAuthors}), or to at least one PUBLISHED
 *       review of an ACTIVE author whose place is ACTIVE (what the Map shows to everyone: not a pending pin, a
 *       removed, rejected or merged place, nor a review being scanned, hidden by reports or rejected) or whose event
 *       is not deleted, or it is the cover of an event that is not deleted. The answer may be cached for
 *       {@link #PUBLIC_CACHE}, so a post or review hidden later stops being served within that time.</li>
 *   <li><b>Its owner</b> ({@code media.owner_id}, signed in, account ACTIVE) may always see their own file while it is
 *       not deleted, so the edit page and the author's view of a post that is being scanned, rejected or hidden keep
 *       working (also the photos of their own pending pin). That answer is {@code private, no-store}. The owner's
 *       status is read here because {@link com.sep490.vsa.common.security.SessionUserRefreshFilter} skips
 *       {@code /media/}.</li>
 *   <li><b>A Moderator</b> (signed in, account ACTIVE) gets a file that passed its scan and is attached to the first
 *       review of a pin waiting for approval ("Ghim mới", UC-M06), {@code private, no-store} like the owner.</li>
 *   <li><b>Everyone else</b> gets 404: files being scanned or rejected, deleted files, files of hidden, removed or
 *       rejected posts and reviews or of locked or deleted authors, tag proofs, and files attached to nothing.</li>
 * </ol>
 * When Moderators get their report queue (deferred), its rule is added here.
 */
@Service
public class MediaAccess {

    /** How long browsers and proxies may keep a public file (cần chốt). */
    static final Duration PUBLIC_CACHE = Duration.ofDays(1);

    /** PUBLIC: anyone, cacheable; OWNER: only the signed-in owner, STAFF: a Moderator's queue; both never cached. */
    public enum Audience { PUBLIC, OWNER, STAFF }

    public record Allowed(String mimeType, Audience audience) {
    }

    private record Row(long ownerId, String mimeType, boolean shown, boolean ownerActive, boolean pendingPin) {
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
                             AND (r.place_id IS NULL OR EXISTS (
                                 SELECT 1 FROM places p WHERE p.id = r.place_id AND p.status = 'ACTIVE'))
                             AND (r.event_id IS NULL OR EXISTS (
                                 SELECT 1 FROM events re WHERE re.id = r.event_id AND re.deleted_at IS NULL)))
                         OR EXISTS (
                           SELECT 1 FROM events ce WHERE ce.cover_media_id = m.id AND ce.deleted_at IS NULL)
                         OR EXISTS (
                           SELECT 1 FROM guide_sections gs JOIN guide_items g ON g.id = gs.guide_id
                           WHERE gs.media_id = m.id AND g.published = TRUE AND g.deleted_at IS NULL)
                         OR EXISTS (
                           SELECT 1 FROM guide_items g WHERE g.cover_media_id = m.id AND g.published = TRUE AND g.deleted_at IS NULL)) AS shown,

                       EXISTS (SELECT 1 FROM users o WHERE o.id = m.owner_id AND o.status = 'ACTIVE') AS owner_active,
                       m.scan_status = 'PASSED' AND EXISTS (
                           SELECT 1 FROM review_media pm JOIN reviews pr ON pr.id = pm.review_id
                           JOIN places pp ON pp.id = pr.place_id
                           WHERE pm.media_id = m.id AND pp.status = 'PENDING') AS pending_pin
                FROM media m WHERE m.storage_key = ? AND m.deleted_at IS NULL"""
                        .formatted(ActiveAuthors.of("b"), ActiveAuthors.of("r")),
                (rs, i) -> new Row(rs.getLong("owner_id"), rs.getString("mime_type"), rs.getBoolean("shown"),
                        rs.getBoolean("owner_active"), rs.getBoolean("pending_pin")), storageKey)
                .stream().findFirst().orElse(null);
        if (row == null) {
            return Optional.empty();
        }
        if (row.shown()) {
            return Optional.of(new Allowed(row.mimeType(), Audience.PUBLIC));
        }
        if (viewerId != null && viewerId == row.ownerId() && row.ownerActive()) {
            return Optional.of(new Allowed(row.mimeType(), Audience.OWNER));
        }
        if (viewerId != null && row.pendingPin() && activeModerator(viewerId)) {
            return Optional.of(new Allowed(row.mimeType(), Audience.STAFF));
        }
        return Optional.empty();
    }

    /** Read here, not from the session: {@code /media/} skips the session refresh. */
    private boolean activeModerator(long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users WHERE id = ? AND role = 'MODERATOR' AND status = 'ACTIVE'""",
                Integer.class, userId);
        return n != null && n > 0;
    }
}
