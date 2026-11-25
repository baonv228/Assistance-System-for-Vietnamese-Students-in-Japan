package com.sep490.vsa.feature.quest.dev;

/** Answers of the test-only API that are not a wallet or a day of quests (contract 5.9). */
public final class DevQuestDtos {

    private DevQuestDtos() {
    }

    /**
     * {@code POST /api/dev/quests/history}: the days done now before today, and the streak as {@code GET
     * /api/me/quests/today} shows it (contract 3.6: today's when today is done, else yesterday's, else 0).
     */
    public record History(int doneDays, int streak) {
    }
}
