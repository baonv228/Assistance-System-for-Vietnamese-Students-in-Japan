package com.sep490.vsa.feature.quest;

import com.sep490.vsa.feature.quest.QuestDtos.Action;

import java.time.LocalDate;
import java.util.Map;

/**
 * One kind of daily quest ({@code user_daily_quests.code}): whether the Member can do it today and with how many
 * entries, what it is called, where the app sends the Member, and which flash card answers or events count for it.
 * Every code is one Spring bean; {@link QuestGenerator} chooses among them and {@link QuestService} counts with them,
 * so a new code (e.g. a server-graded QUIZ) is one more implementation and its name in {@code app.quests.rotating}.
 * <p>
 * {@code params} are kept with the quest row (JSON) when the day is made and never change that day: the title,
 * description and action are always built from them and the stored target.
 */
public interface QuestRule {

    /** The code stored in {@code user_daily_quests.code} and listed in {@code app.quests.*}. */
    String code();

    /**
     * What this quest would be for the Member on that day. {@code fullTarget} is the configured target; the generator
     * uses {@code min(fullTarget, eligible)}, so a plan with fewer eligible entries than that is only taken when not
     * enough quests are possible at their full target. {@code eligible} 0 = the quest cannot be done.
     */
    Plan plan(long userId, LocalDate day, int fullTarget);

    /** The title kept with the row (also the title of its xu line). */
    String title(int target, Map<String, Object> params);

    String description(int target, Map<String, Object> params);

    Action action(int target, Map<String, Object> params);

    /**
     * Where the app sends the Member right now for a quest not completed yet ({@code progress} of {@code target}).
     * Most actions never change ({@link #action}); one built on the Member's marks (LEARN_NEW) is worked out again,
     * since the marks move during the day.
     */
    default Action actionNow(long userId, int target, int progress, Map<String, Object> params) {
        return action(target, params);
    }

    /** Whether one flash card answer counts for this quest (once per entry is enforced by the caller). */
    default boolean countsAnswer(Answer answer, Map<String, Object> params) {
        return false;
    }

    /** Whether an event of {@code POST /api/me/quests/events} counts for this quest (once per entry). */
    default boolean countsEvent(Event event, Map<String, Object> params) {
        return false;
    }

    /** The phrase shown with the quest ({@code quests[].phrase}), or null. */
    default Long phraseId(Map<String, Object> params) {
        return null;
    }

    /** {@code eligible}: how many entries could count (0 = impossible); {@code params}: what the quest is about. */
    record Plan(int eligible, Map<String, Object> params) {

        static Plan none() {
            return new Plan(0, Map.of());
        }
    }

    /**
     * A flash card answer ({@code POST /api/phrases/{id}/flash}): {@code learned} the answer ("Đã thuộc" or "Chưa
     * thuộc"); {@code wasLearning} / {@code wasLearned} the entry's marks right before it.
     */
    record Answer(long userId, long phraseId, boolean learned, boolean wasLearning, boolean wasLearned) {
    }

    /** An event the server cannot see by itself, e.g. {@code LISTEN} of the phrase of the day. */
    record Event(String type, long phraseId) {
    }

    /** Reads a whole number kept in params (JSON numbers come back as Integer or Long). */
    static Long longParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && text.matches("\\d{1,18}")) {
            return Long.parseLong(text);
        }
        return null;
    }

    static String textParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        return value == null ? "" : value.toString();
    }
}
