package com.sep490.vsa.feature.coin;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Xóa tài khoản" (DELETE /api/me) and the daily quests (contract 4): the xu and the points go to 0, the quests, their
 * counted entries, the done days and the log of answers are deleted, the books of xu and points stay; other Members
 * keep everything.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_coin_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
class CoinAccountDeletionFlowTest extends CoinTestSupport {

    @Test
    void deletingTheAccountEmptiesTheWalletAndTheQuestsButKeepsTheBookOfXu() throws Exception {
        LocalDate day = freshDay();
        long phrase = phrase("konbini", "PHRASE");
        long leaving = member();
        long staying = member();
        long leavingQuest = studied(leaving, phrase, day);
        long stayingQuest = studied(staying, phrase, day);
        assertThat(coinsOf(leaving)).isEqualTo(300);
        assertThat(pointsOf(leaving)).isEqualTo(2);

        deleteAs(leaving, "/api/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đã xóa tài khoản."));

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, leaving))
                .isEqualTo("DELETED");
        assertThat(coinsOf(leaving)).isZero();
        assertThat(pointsOf(leaving)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", leaving)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_quest_items WHERE user_daily_quest_id = ?", leavingQuest)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", leaving)).isZero();
        assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", leaving)).isZero();
        // The books stay, like the point history of a deleted account
        assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", leaving)).isEqualTo(2);
        assertThat(coinBookSum(leaving)).isEqualTo(300);
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", leaving)).isEqualTo(1);

        // The other Member keeps everything
        assertThat(coinsOf(staying)).isEqualTo(300);
        assertThat(pointsOf(staying)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", staying)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_quest_items WHERE user_daily_quest_id = ?", stayingQuest))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", staying)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", staying)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", staying)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM point_transactions WHERE user_id = ?", staying)).isEqualTo(1);
        getAs(staying, "/api/me/coins").andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
    }

    /**
     * A Member who earned 400 xu, exchanged 2 points (300 xu left) and did a day of quests; returns the quest's id.
     * The quest rows are written by hand: the quest engine is another feature.
     */
    private long studied(long user, long phrase, LocalDate day) throws Exception {
        give(user, 400);
        exchange(user, 2).andExpect(status().isOk());
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO user_daily_quests (user_id, quest_date, code, sort_order, params, title, target, progress,
                                               coins, completed_at)
                VALUES (?, ?, 'FLASH_REVIEW', 0, '{}', 'Ôn 1 thẻ ghi nhớ', 1, 1, 10, ?)""", user, Date.valueOf(day), now);
        long quest = jdbc.queryForObject("SELECT id FROM user_daily_quests WHERE user_id = ? AND quest_date = ?",
                Long.class, user, Date.valueOf(day));
        jdbc.update("INSERT INTO user_quest_items (user_daily_quest_id, phrase_id, counted_at) VALUES (?, ?, ?)",
                quest, phrase, now);
        jdbc.update("INSERT INTO user_quest_days (user_id, quest_date, all_done_at, streak) VALUES (?, ?, ?, 1)",
                user, Date.valueOf(day), now);
        jdbc.update("INSERT INTO phrase_answer_log (user_id, phrase_id, learned, answered_at) VALUES (?, ?, TRUE, ?)",
                user, phrase, now);
        return quest;
    }
}
