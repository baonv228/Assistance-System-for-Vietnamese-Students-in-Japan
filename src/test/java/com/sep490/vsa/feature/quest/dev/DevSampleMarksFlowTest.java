package com.sep490.vsa.feature.quest.dev;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sample flash card marks of user@gmail.com while the test-only API is on (contract 5.9): given at start when the
 * account has none, all three states present so every quest is possible at its full target, and never given twice.
 */
class DevSampleMarksFlowTest extends DevApiTestSupport {

    @Autowired
    private DevSampleMarks sampleMarks;

    @Test
    void userGmailHasEveryKindOfMarkSoEveryQuestIsPossibleAtItsFullTarget() throws Exception {
        freshDay();
        long user = jdbc.queryForObject("SELECT id FROM users WHERE email = 'user@gmail.com'", Long.class);
        int learned = rows("""
                SELECT COUNT(*) FROM user_phrase_marks m JOIN phrases ph ON ph.id = m.phrase_id
                WHERE m.user_id = ? AND ph.deleted_at IS NULL AND m.is_learned""", user);
        int learning = rows("""
                SELECT COUNT(*) FROM user_phrase_marks m JOIN phrases ph ON ph.id = m.phrase_id
                WHERE m.user_id = ? AND ph.deleted_at IS NULL AND m.is_learning""", user);
        int unmarked = rows("""
                SELECT COUNT(*) FROM phrases ph
                WHERE ph.deleted_at IS NULL AND NOT EXISTS (SELECT 1 FROM user_phrase_marks m
                    WHERE m.user_id = ? AND m.phrase_id = ph.id AND (m.is_learned OR m.is_learning))""", user);
        assertThat(learned).isGreaterThanOrEqualTo(5);
        assertThat(learning).isGreaterThanOrEqualTo(5);
        assertThat(unmarked).isGreaterThanOrEqualTo(3);

        try {
            JsonNode set = data(forceToday(user, "FLASH_REVIEW", "LEARN_NEW", "REVIEW_LEARNING", "TOPIC",
                    "PHRASE_OF_DAY").andExpect(status().isOk()));
            assertThat(codes(set)).containsExactly("FLASH_REVIEW", "LEARN_NEW", "REVIEW_LEARNING", "TOPIC",
                    "PHRASE_OF_DAY");
            assertThat(quest(set, "FLASH_REVIEW").path("target").asInt()).isEqualTo(10);
            assertThat(quest(set, "LEARN_NEW").path("target").asInt()).isEqualTo(3);
            assertThat(quest(set, "LEARN_NEW").path("action").path("status").asText()).isEqualTo("new");
            assertThat(quest(set, "REVIEW_LEARNING").path("target").asInt()).isEqualTo(5);
            assertThat(quest(set, "TOPIC").path("target").asInt()).isEqualTo(5);
            assertThat(quest(set, "PHRASE_OF_DAY").path("phrase").path("id").asLong()).isPositive();
        } finally {
            clearToday(user).andExpect(status().isOk());
        }

        // Starting again (or calling it again) changes nothing: the account has marks now
        Map<Long, String> marks = marks(user);
        assertThat(sampleMarks.giveSampleMarks()).isZero();
        assertThat(marks(user)).isEqualTo(marks);
    }

    @Test
    void anAccountWithoutMarksGetsTheLibraryDealtInIdOrder() {
        freshDay();
        isolateLibrary();
        List<Long> ids = phrases(7);
        long user = member();

        assertThat(sampleMarks.markLibrary(user)).isEqualTo(5);

        assertThat(marks(user)).isEqualTo(Map.of(
                ids.get(0), "learned", ids.get(1), "learning",
                ids.get(3), "learned", ids.get(4), "learning",
                ids.get(6), "learned"));
        assertThat(sampleMarks.markLibrary(user)).isZero();
        assertThat(marks(user)).hasSize(5);

        // A Member with a mark of their own gets nothing
        long other = member();
        mark(other, ids.get(2), false);
        assertThat(sampleMarks.markLibrary(other)).isZero();
        assertThat(marks(other)).isEqualTo(Map.of(ids.get(2), "learning"));
    }
}
