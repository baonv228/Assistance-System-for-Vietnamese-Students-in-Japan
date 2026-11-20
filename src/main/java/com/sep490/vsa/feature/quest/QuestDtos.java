package com.sep490.vsa.feature.quest;

import com.sep490.vsa.feature.coin.CoinDtos.Wallet;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * JSON shapes of "Nhiệm vụ hằng ngày". The mobile app codes against these names (WEB_DAILY_QUESTS_PROMPT.md, section
 * 5): never rename a field; add only.
 */
public final class QuestDtos {

    private QuestDtos() {
    }

    /** GET /api/me/quests/today (5.1). */
    public record Today(LocalDate date, Instant resetAt, List<Quest> quests, Bonus bonus, Streak streak,
                        List<Day> recentDays, int coinsToday, int dailyCap, Wallet wallet) {
    }

    /**
     * One quest of the day. {@code coins}: what it will give while not completed, what it actually gave once completed
     * (0 under the daily cap). {@code progress} is never above {@code target}. {@code phrase} only for PHRASE_OF_DAY.
     */
    public record Quest(long id, String code, String title, String description, int target, int progress, int coins,
                        boolean completed, Instant completedAt, Action action, PhraseRef phrase) {
    }

    /**
     * Where the app sends the Member: {@code type} PRACTICE (the "Luyện tập" filters below, "" = no filter) or LISTEN.
     * {@code by} "" | "topic" | "category"; {@code group} the topic or category id; {@code groupName} its name;
     * {@code kind} "" | "PHRASE" | "VOCAB"; {@code status} "" | "learning" | "learned" | "new". Every field is always
     * present.
     */
    public record Action(String type, String by, String group, String groupName, String kind, String status) {

        public static Action practice(String by, String group, String groupName, String kind, String status) {
            return new Action("PRACTICE", by, group, groupName, kind, status);
        }

        public static Action listen() {
            return new Action("LISTEN", "", "", "", "", "");
        }
    }

    public record PhraseRef(long id, String japanese, String romaji, String meaningVi) {
    }

    /** The all-done bonus: {@code coins} it gives (or gave), {@code completed} once received. */
    public record Bonus(int coins, boolean completed) {
    }

    public record Streak(int current, int best, boolean todayDone, int every, int milestoneCoins) {
    }

    /** One of the 7 days of recentDays (oldest first, today last). */
    public record Day(LocalDate date, boolean done) {
    }

    /**
     * "QuestChange": what one flash answer or one event did to the day's quests (5.2). {@code changed} is empty when
     * nothing was counted (already counted, answered too fast, entry not concerned).
     */
    public record QuestChange(List<ChangedQuest> changed, List<CompletedQuest> justCompleted,
                              boolean bonusJustCompleted, int bonusCoins, StreakBrief streak,
                              StreakMilestone streakMilestone, int coinsAwarded, int coinsToday, int dailyCap,
                              Wallet wallet) {
    }

    public record ChangedQuest(long id, String code, int progress, int target, boolean completed) {
    }

    public record CompletedQuest(long id, String code, String title, int coins) {
    }

    public record StreakBrief(int current, boolean todayDone) {
    }

    /** The streak milestone just reached: {@code days} (7, 14…) and the xu it gave. */
    public record StreakMilestone(int days, int coins) {
    }

    /** POST /api/phrases/{id}/flash answer: the old {learning, learned} plus the QuestChange. */
    public record FlashAnswer(boolean learning, boolean learned, QuestChange quests) {
    }

    /** POST /api/me/quests/events. {@code type}: LISTEN; {@code phraseId}: the phrase listened to. */
    public record EventRequest(String type, Long phraseId) {
    }

    /**
     * The "quests" block of GET /api/me/home (5.6). Read only: the Home never makes the day's quests;
     * {@code generated} false = not made yet today ({@code done} 0, {@code total} = the configured count).
     */
    public record HomeQuests(boolean generated, int done, int total, boolean bonusDone, int streak, boolean todayDone,
                             int coinsToday, int dailyCap, int coins, int exchangeableNow) {
    }
}
