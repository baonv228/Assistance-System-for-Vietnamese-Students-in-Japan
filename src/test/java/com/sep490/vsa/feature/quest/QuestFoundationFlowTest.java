package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.coin.CoinDtos.Wallet;
import com.sep490.vsa.feature.coin.CoinLedger;
import com.sep490.vsa.feature.coin.CoinSettings;
import com.sep490.vsa.feature.phrase.PhraseOfDayService;
import com.sep490.vsa.feature.phrase.PhraseOfDayService.PhraseOfDay;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared pieces of "Nhiệm vụ hằng ngày" and "xu": settings as written in application.properties, the book of xu
 * (CoinLedger), the kept phrase of the day (PhraseOfDayService) and who counts as a Member (MemberGuard).
 */
class QuestFoundationFlowTest extends QuestTestSupport {

    @Autowired
    private QuestSettings settings;
    @Autowired
    private CoinSettings coinSettings;
    @Autowired
    private CoinLedger ledger;
    @Autowired
    private PhraseOfDayService phraseOfDay;
    @Autowired
    private MemberGuard guard;
    @Autowired
    private com.sep490.vsa.feature.user.PointService points;

    @Test
    void settingsAreReadAsWrittenInApplicationProperties() {
        assertThat(settings.zoneId().getId()).isEqualTo("Asia/Tokyo");
        assertThat(settings.getFixed()).isEqualTo("FLASH_REVIEW");
        assertThat(settings.getRotating()).containsExactly("LEARN_NEW", "REVIEW_LEARNING", "TOPIC", "PHRASE_OF_DAY");
        assertThat(settings.questsPerDay()).isEqualTo(3);
        assertThat(settings.targetOf("FLASH_REVIEW")).isEqualTo(10);
        assertThat(settings.targetOf("LEARN_NEW")).isEqualTo(3);
        assertThat(settings.targetOf("REVIEW_LEARNING")).isEqualTo(5);
        assertThat(settings.targetOf("TOPIC")).isEqualTo(5);
        assertThat(settings.targetOf("PHRASE_OF_DAY")).isEqualTo(1);
        assertThat(settings.getCoinsPerQuest()).isEqualTo(10);
        assertThat(settings.getBonusCoins()).isEqualTo(20);
        assertThat(settings.getDailyCap()).isEqualTo(50);
        assertThat(settings.getStreakEvery()).isEqualTo(7);
        assertThat(settings.getStreakCoins()).isEqualTo(50);
        assertThat(settings.getMinAnswerIntervalMs()).isEqualTo(1500);
        assertThat(settings.getEventsPerMinute()).isEqualTo(30);
        assertThat(settings.topicsFor("ARR")).containsExactly("pay", "train", "paper", "health");
        assertThat(settings.topicsFor("PRE")).containsExactly("paper", "ask", "train");
        assertThat(settings.topicsFor(null)).isEmpty();
        assertThat(settings.getDevApi().isEnabled()).isFalse();
        assertThat(coinSettings.getRate()).isEqualTo(50);
        assertThat(coinSettings.getMonthCap()).isEqualTo(10);
    }

    @Test
    void theBookOfXuPaysEachReferenceOnceAndKeepsTheBalance() {
        LocalDate day = freshDay();
        long user = member();

        Long first = inTransaction(() -> ledger.add(user, 10, CoinLedger.DAILY_QUEST, "Ôn 10 thẻ ghi nhớ",
                CoinLedger.REF_QUEST, 900_000_001L, day));
        Long again = inTransaction(() -> ledger.add(user, 10, CoinLedger.DAILY_QUEST, "Ôn 10 thẻ ghi nhớ",
                CoinLedger.REF_QUEST, 900_000_001L, day));
        Long bonus = inTransaction(() -> ledger.add(user, 20, CoinLedger.DAILY_QUEST_ALL, "Nhiệm vụ ngày",
                CoinLedger.REF_QUEST_DAY, 900_000_002L, day));
        Long streak = inTransaction(() -> ledger.add(user, 50, CoinLedger.QUEST_STREAK, "Học 7 ngày liên tiếp",
                CoinLedger.REF_QUEST_DAY, 900_000_002L, day));
        Long nothing = inTransaction(() -> ledger.add(user, 0, CoinLedger.DAILY_QUEST, "x", CoinLedger.REF_QUEST,
                900_000_003L, day));

        assertThat(first).isNotNull();
        assertThat(again).isNull();
        assertThat(bonus).isNotNull();
        assertThat(streak).isNotNull();
        assertThat(nothing).isNull();
        assertThat(coinsOf(user)).isEqualTo(80);
        assertThat(ledger.balance(user)).isEqualTo(80);
        // The streak milestone is outside the daily cap
        assertThat(ledger.questCoinsOn(user, day)).isEqualTo(30);
        assertThat(ledger.questCoinsOn(user, day.plusDays(1))).isZero();

        // Two exchanges (no ref) are both kept; the balance never goes below 0
        inTransaction(() -> ledger.add(user, -50, CoinLedger.EXCHANGE, "Đổi lấy 1 điểm", null, null, null));
        inTransaction(() -> ledger.add(user, -50, CoinLedger.EXCHANGE, "Đổi lấy 1 điểm", null, null, null));
        assertThat(coinsOf(user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", Integer.class, user))
                .isEqualTo(5);
    }

    @Test
    void theWalletCountsThisJapanMonthsExchangesAndTheRank() {
        LocalDate day = LocalDate.of(2150, 5, 31);
        setJapanTime(day, LocalTime.of(23, 59, 59));
        long user = member();
        jdbc.update("UPDATE users SET coins = 340, points = 98 WHERE id = ?", user);
        // 2 points this month (Japan time), 4 last month: only this month counts
        pointLine(user, 2, YearMonth.of(2150, 5).atDay(1).atStartOfDay(JAPAN).toInstant());
        pointLine(user, 4, YearMonth.of(2150, 4).atEndOfMonth().atTime(23, 59, 59).atZone(JAPAN).toInstant());

        Wallet wallet = ledger.wallet(user);
        assertThat(wallet.coins()).isEqualTo(340);
        assertThat(wallet.rate()).isEqualTo(50);
        assertThat(wallet.monthCap()).isEqualTo(10);
        assertThat(wallet.exchangedThisMonth()).isEqualTo(2);
        assertThat(wallet.exchangeableNow()).isEqualTo(6);
        assertThat(wallet.monthResetAt()).isEqualTo(LocalDate.of(2150, 6, 1).atStartOfDay(JAPAN).toInstant());
        assertThat(wallet.points()).isEqualTo(98);
        assertThat(wallet.rankName()).isEqualTo("Người mới");
        assertThat(ledger.rankName(100)).isEqualTo("Người chỉ đường");
        assertThat(ledger.rankName(300)).isEqualTo("Senpai tin cậy");

        // One second later it is June in Japan: the month's cap starts again
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(ledger.wallet(user).exchangedThisMonth()).isZero();
        assertThat(ledger.wallet(user).exchangeableNow()).isEqualTo(6);
    }

    @Test
    void anExchangeInTheLastSecondOfAJapanMonthCountsForThatMonth() {
        // 23:59:59.600 JST on the last day: MySQL would round a DATETIME up to 00:00:00 of the next month
        setJapanTime(LocalDate.of(2160, 1, 31), LocalTime.of(23, 59, 59, 600_000_000));
        long user = member();
        inTransaction(() -> {
            points.add(user, 4, CoinLedger.POINT_REASON, CoinLedger.POINT_REF, null);
            return null;
        });
        assertThat(ledger.wallet(user).exchangedThisMonth()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT created_at FROM point_transactions WHERE user_id = ?", Timestamp.class,
                user).toInstant()).isEqualTo(LocalDate.of(2160, 1, 31).atTime(23, 59, 59).atZone(JAPAN).toInstant());
        clock.advance(java.time.Duration.ofMillis(400));             // 00:00:00 on the 1st: a new month
        assertThat(ledger.wallet(user).exchangedThisMonth()).isZero();
    }

    @Test
    void thePhraseOfTheDayStaysTheSameAllDayEvenWhenTheLibraryChanges() {
        LocalDate day = freshDay();
        phrases(3);
        PhraseOfDay first = phraseOfDay.phraseOfToday().orElseThrow();
        // The Academic Manager adds and deletes phrases: the day's phrase does not move
        phrases(2);
        long other = jdbc.queryForObject("SELECT MIN(id) FROM phrases WHERE kind = 'PHRASE' AND deleted_at IS NULL AND id <> ?",
                Long.class, first.id());
        jdbc.update("UPDATE phrases SET deleted_at = NOW() WHERE id = ?", other);
        try {
            assertThat(phraseOfDay.phraseOfToday().orElseThrow().id()).isEqualTo(first.id());
            assertThat(phraseOfDay.phraseOf(day).orElseThrow().id()).isEqualTo(first.id());
            // Deleted later that day: still the day's phrase
            jdbc.update("UPDATE phrases SET deleted_at = NOW() WHERE id = ?", first.id());
            assertThat(phraseOfDay.phraseOfToday().orElseThrow().id()).isEqualTo(first.id());
        } finally {
            jdbc.update("UPDATE phrases SET deleted_at = NULL WHERE id IN (?, ?)", other, first.id());
        }
        assertThat(jdbc.queryForObject("SELECT phrase_id FROM daily_phrases WHERE phrase_date = ?", Long.class,
                java.sql.Date.valueOf(day))).isEqualTo(first.id());
    }

    @Test
    void anEmptyLibraryHasNoPhraseOfTheDay() {
        freshDay();
        isolateLibrary();
        assertThat(phraseOfDay.phraseOfToday()).isEmpty();
        phrase("konbini", "VOCAB");                          // words never become the phrase of the day
        assertThat(phraseOfDay.phraseOfToday()).isEmpty();
        long only = phrase("konbini", "PHRASE");
        assertThat(phraseOfDay.phraseOfToday().orElseThrow().id()).isEqualTo(only);
    }

    @Test
    void onlyActiveMembersPassTheGuard() {
        long user = member();
        long moderator = user("MODERATOR", "ACTIVE");
        assertThat(guard.member(principal(user, "USER"))).isEqualTo(user);
        assertThat(guard.isMember(user)).isTrue();
        assertThat(guard.isMember(moderator)).isFalse();
        assertThatThrownBy(() -> guard.member(null))
                .isInstanceOf(ApiException.class).extracting("status").hasToString("401 UNAUTHORIZED");
        assertThatThrownBy(() -> guard.member(principal(moderator, "MODERATOR")))
                .isInstanceOf(ApiException.class).hasMessage(MemberGuard.NO_PERMISSION);
    }

    private void pointLine(long user, int delta, java.time.Instant at) {
        jdbc.update("""
                INSERT INTO point_transactions (user_id, delta, reason, ref_type, ref_id, created_at)
                VALUES (?, ?, 'COIN_EXCHANGE', 'COIN', NULL, ?)""", user, delta, Timestamp.from(at));
    }

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "u" + id + "@test.local", "Quest Test", role, "/");
    }
}
