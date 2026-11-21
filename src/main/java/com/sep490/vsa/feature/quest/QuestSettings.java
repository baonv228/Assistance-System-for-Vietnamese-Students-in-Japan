package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.text.VietnameseText;
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
    /** Quests that follow what the Member is interested in on the Blog and in the Cẩm nang ({@link QuestInterests}). */
    private final Interest interest = new Interest();
    private final DevApi devApi = new DevApi();

    /**
     * {@code app.quests.interest.*}: how the Member's interests are read and the "Luyện tập" groups each one leads to.
     * A group is {@code topic:<learning_topics.id>} or {@code category:<place_categories.id>}, preferred ones first.
     */
    public static class Interest {
        /** Activity older than this many days is not read (topics the Member chose never expire). */
        private int days = 90;
        /** A topic learnt from activity needs at least this weight (one like is not enough). */
        private int minWeight = 2;
        /** Weight of a topic the Member chose in "Chủ đề quan tâm". */
        private int chosenWeight = 4;
        /** How many interests (strongest first) the quests follow. */
        private int top = 3;
        /** Rotating quests of a day that follow an interest, when the library allows it. */
        private int minPerDay = 1;
        /** Blog topic, keyed by its name folded by {@link VietnameseText#squash} ("Y tế" → "yte") → groups. */
        private Map<String, List<String>> blogTopics = new LinkedHashMap<>();
        /** Guide topic code ({@code guide_topics.code}, e.g. HEALTH) → groups. */
        private Map<String, List<String>> guideTopics = new LinkedHashMap<>();

        /** The groups a blog topic leads to, by its name (accents, case and spaces ignored); empty for none. */
        public List<String> groupsOfBlogTopic(String name) {
            String key = VietnameseText.squash(name);
            if (key.isEmpty()) {
                return List.of();
            }
            for (Map.Entry<String, List<String>> entry : blogTopics.entrySet()) {
                if (key.equals(VietnameseText.squash(entry.getKey()))) {
                    return clean(entry.getValue());
                }
            }
            return List.of();
        }

        /** The groups a guide topic leads to, by its code (case ignored); empty for none. */
        public List<String> groupsOfGuideTopic(String code) {
            if (code == null || code.isBlank()) {
                return List.of();
            }
            for (Map.Entry<String, List<String>> entry : guideTopics.entrySet()) {
                if (code.strip().equalsIgnoreCase(entry.getKey().strip())) {
                    return clean(entry.getValue());
                }
            }
            return List.of();
        }

        private static List<String> clean(List<String> groups) {
            return groups == null ? List.of()
                    : groups.stream().filter(g -> g != null && !g.isBlank()).map(String::strip).toList();
        }

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }

        public int getMinWeight() {
            return minWeight;
        }

        public void setMinWeight(int minWeight) {
            this.minWeight = minWeight;
        }

        public int getChosenWeight() {
            return chosenWeight;
        }

        public void setChosenWeight(int chosenWeight) {
            this.chosenWeight = chosenWeight;
        }

        public int getTop() {
            return top;
        }

        public void setTop(int top) {
            this.top = top;
        }

        public int getMinPerDay() {
            return minPerDay;
        }

        public void setMinPerDay(int minPerDay) {
            this.minPerDay = minPerDay;
        }

        public Map<String, List<String>> getBlogTopics() {
            return blogTopics;
        }

        public void setBlogTopics(Map<String, List<String>> blogTopics) {
            this.blogTopics = blogTopics;
        }

        public Map<String, List<String>> getGuideTopics() {
            return guideTopics;
        }

        public void setGuideTopics(Map<String, List<String>> guideTopics) {
            this.guideTopics = guideTopics;
        }
    }

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

    public Interest getInterest() {
        return interest;
    }

    public DevApi getDevApi() {
        return devApi;
    }
}
