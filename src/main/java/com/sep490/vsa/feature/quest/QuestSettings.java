package com.sep490.vsa.feature.quest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Nhiệm vụ hằng ngày" settings ({@code app.quests.*} in application.properties). Map keys use the bracket form
 * ({@code app.quests.target[FLASH_REVIEW]=10}): without brackets Spring drops the underscore of the key.
 */
@Component
@ConfigurationProperties(prefix = "app.quests")
public class QuestSettings {

    /** The day boundary: 0:00 in this zone. */
    private String zone = "Asia/Tokyo";
    /** The quest every Member gets every day, listed first. */
    private String fixed = "FLASH_REVIEW";
    /** The quests the day's others are chosen from. */
    private List<String> rotating = new ArrayList<>(List.of("LEARN_NEW", "REVIEW_LEARNING", "TOPIC", "PHRASE_OF_DAY"));
    private int rotatingPerDay = 2;
    /** Default target of each quest code. */
    private Map<String, Integer> target = new LinkedHashMap<>(Map.of(
            "FLASH_REVIEW", 10, "LEARN_NEW", 3, "REVIEW_LEARNING", 5, "TOPIC", 5, "PHRASE_OF_DAY", 1));
    private int coinsPerQuest = 10;
    private int bonusCoins = 20;
    /** Xu of quests + the all-done bonus in one day, at most. */
    private int dailyCap = 50;
    private int streakEvery = 7;
    private int streakCoins = 50;
    /** Two answers closer than this: the second is kept as a mark but not counted for the quests. */
    private long minAnswerIntervalMs = 1500;
    /** POST /api/me/quests/events per Member and minute. */
    private int eventsPerMinute = 30;
    /** Stage (PRE, ARR, LEAVE, HOME) → learning topic ids for TOPIC, first feasible one wins. */
    private Map<String, List<String>> stageTopics = new LinkedHashMap<>();
    private final DevApi devApi = new DevApi();

    /** The test-only API /api/dev/quests/**: also needs a database on this machine (see DevApi checks). */
    public static class DevApi {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /** The default target of a code (1 when not configured). */
    public int targetOf(String code) {
        Integer value = target.get(code);
        return value == null || value < 1 ? 1 : value;
    }

    /** Learning topic ids configured for a stage, in order (empty for none or an unknown stage). */
    public List<String> topicsFor(String stage) {
        if (stage == null) {
            return List.of();
        }
        List<String> topics = stageTopics.get(stage);
        return topics == null ? List.of() : topics.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** How many quests a day has when the library allows it: the fixed one + the rotating ones. */
    public int questsPerDay() {
        return 1 + rotatingPerDay;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getFixed() {
        return fixed;
    }

    public void setFixed(String fixed) {
        this.fixed = fixed;
    }

    public List<String> getRotating() {
        return rotating;
    }

    public void setRotating(List<String> rotating) {
        this.rotating = rotating;
    }

    public int getRotatingPerDay() {
        return rotatingPerDay;
    }

    public void setRotatingPerDay(int rotatingPerDay) {
        this.rotatingPerDay = rotatingPerDay;
    }

    public Map<String, Integer> getTarget() {
        return target;
    }

    public void setTarget(Map<String, Integer> target) {
        this.target = target;
    }

    public int getCoinsPerQuest() {
        return coinsPerQuest;
    }

    public void setCoinsPerQuest(int coinsPerQuest) {
        this.coinsPerQuest = coinsPerQuest;
    }

    public int getBonusCoins() {
        return bonusCoins;
    }

    public void setBonusCoins(int bonusCoins) {
        this.bonusCoins = bonusCoins;
    }

    public int getDailyCap() {
        return dailyCap;
    }

    public void setDailyCap(int dailyCap) {
        this.dailyCap = dailyCap;
    }

    public int getStreakEvery() {
        return streakEvery;
    }

    public void setStreakEvery(int streakEvery) {
        this.streakEvery = streakEvery;
    }

    public int getStreakCoins() {
        return streakCoins;
    }

    public void setStreakCoins(int streakCoins) {
        this.streakCoins = streakCoins;
    }

    public long getMinAnswerIntervalMs() {
        return minAnswerIntervalMs;
    }

    public void setMinAnswerIntervalMs(long minAnswerIntervalMs) {
        this.minAnswerIntervalMs = minAnswerIntervalMs;
    }

    public int getEventsPerMinute() {
        return eventsPerMinute;
    }

    public void setEventsPerMinute(int eventsPerMinute) {
        this.eventsPerMinute = eventsPerMinute;
    }

    public Map<String, List<String>> getStageTopics() {
        return stageTopics;
    }

    public void setStageTopics(Map<String, List<String>> stageTopics) {
        this.stageTopics = stageTopics;
    }

    public DevApi getDevApi() {
        return devApi;
    }
}
