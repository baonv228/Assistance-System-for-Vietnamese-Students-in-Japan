package com.sep490.vsa.usecase.saved;

import com.sep490.vsa.usecase.UseCaseTestBase;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Data of the "Lưu" / "Đã lưu" tests (UC 1.12, UC 1.13). Posts, questions and places are made per test; guides and
 * "Nhật ngữ" entries that must stay visible are the sample ones (so no test adds entries to the shared library), and
 * the guides or phrases a test makes for itself are left deleted or without a stage, so no other page lists them.
 */
abstract class SavedTestSupport extends UseCaseTestBase {

    /** Ids of visible sample entries of "Nhật ngữ" (dev/sample-data.sql). */
    protected List<Long> samplePhrases(int n) {
        return jdbc.queryForList("SELECT id FROM phrases WHERE deleted_at IS NULL ORDER BY id LIMIT ?", Long.class, n);
    }

    /** Ids of visible sample guides (published, not deleted, with a stage). */
    protected List<Long> sampleGuides(int n) {
        return jdbc.queryForList("""
                SELECT id FROM guide_items WHERE published = TRUE AND deleted_at IS NULL AND stage_code IS NOT NULL
                ORDER BY id LIMIT ?""", Long.class, n);
    }

    /** A published guide without a stage (listed on no guide page), topic "Nhà ở". */
    protected long guide(String title, String summary) {
        jdbc.update("""
                INSERT INTO guide_items (stage_code, topic_code, title, summary, published, sort_order)
                VALUES (NULL, 'HOUSING', ?, ?, TRUE, 0)""", title, summary);
        return jdbc.queryForObject("SELECT MAX(id) FROM guide_items WHERE title = ?", Long.class, title);
    }

    /** A "Nhật ngữ" entry written by a Japanese Content Editor; the caller deletes it before the test ends. */
    protected long phrase(String japanese) {
        long editor = account("ACADEMIC_MANAGER", 30);
        jdbc.update("""
                INSERT INTO phrases (category_id, kind, japanese, romaji, meaning_vi, created_by)
                VALUES ('food', 'PHRASE', ?, 'romaji', 'nghĩa', ?)""", japanese, editor);
        return jdbc.queryForObject("SELECT MAX(id) FROM phrases WHERE japanese = ?", Long.class, japanese);
    }

    protected void deletePhrase(long phraseId) {
        jdbc.update("UPDATE phrases SET deleted_at = NOW() WHERE id = ?", phraseId);
    }

    /** A place merged into {@code targetId} (status MERGED). */
    protected long mergedInto(long targetId, String name) {
        long id = place(name);
        jdbc.update("UPDATE places SET status = 'MERGED', merged_into_id = ? WHERE id = ?", targetId, id);
        return id;
    }

    protected void setPlaceStatus(long placeId, String status) {
        jdbc.update("UPDATE places SET status = ? WHERE id = ?", status, placeId);
    }

    // ---------------------------------------------------------------- saved rows written straight to the DB

    protected void savePost(long userId, long blogId, Instant at) {
        jdbc.update("INSERT INTO saved_blogs (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                Timestamp.from(at));
    }

    protected void savePlace(long userId, long placeId, Instant at) {
        jdbc.update("INSERT INTO saved_places (user_id, place_id, saved_at) VALUES (?, ?, ?)", userId, placeId,
                Timestamp.from(at));
    }

    protected void saveGuide(long userId, long guideId, Instant at) {
        jdbc.update("INSERT INTO saved_guides (user_id, guide_id, created_at) VALUES (?, ?, ?)", userId, guideId,
                Timestamp.from(at));
    }

    protected void savePhrase(long userId, long phraseId, Instant at) {
        jdbc.update("INSERT INTO saved_phrases (user_id, phrase_id, created_at) VALUES (?, ?, ?)", userId, phraseId,
                Timestamp.from(at));
    }

    protected int savedBlogRows(long userId, long blogId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM saved_blogs WHERE user_id = ? AND blog_id = ?",
                Integer.class, userId, blogId);
    }

    protected int savedPlaceRows(long userId, long placeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM saved_places WHERE user_id = ? AND place_id = ?",
                Integer.class, userId, placeId);
    }

    protected int savedGuideRows(long userId, long guideId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM saved_guides WHERE user_id = ? AND guide_id = ?",
                Integer.class, userId, guideId);
    }

    protected int savedPhraseRows(long userId, long phraseId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM saved_phrases WHERE user_id = ? AND phrase_id = ?",
                Integer.class, userId, phraseId);
    }

    protected static Instant minutesAgo(int minutes) {
        return Instant.now().minusSeconds(minutes * 60L);
    }
}
