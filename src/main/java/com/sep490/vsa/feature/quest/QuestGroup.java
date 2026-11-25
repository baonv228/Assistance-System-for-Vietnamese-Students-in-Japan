package com.sep490.vsa.feature.quest;

import java.util.Map;
import java.util.Optional;

/**
 * A group of entries of "Luyện tập", as the page and the app filter them: a learning topic ({@code by} "topic", id of
 * {@code learning_topics}) or a place category ({@code by} "category", id of {@code place_categories}). Written
 * {@code topic:pay} / {@code category:station} in the settings ({@code app.quests.interest.*}).
 * <p>
 * A quest that follows a group keeps it in its params ({@code by}, {@code group}, {@code groupName}); TOPIC rows made
 * before groups existed hold {@code topicId} / {@code topicName}, read as a learning topic.
 */
record QuestGroup(String by, String id) {

    static final String TOPIC = "topic";
    static final String CATEGORY = "category";

    /** "topic:pay" or "category:station"; empty for anything else. */
    static Optional<QuestGroup> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int colon = text.indexOf(':');
        if (colon < 1 || colon == text.length() - 1) {
            return Optional.empty();
        }
        String by = text.substring(0, colon).strip().toLowerCase(java.util.Locale.ROOT);
        String id = text.substring(colon + 1).strip();
        if (id.isEmpty() || !(TOPIC.equals(by) || CATEGORY.equals(by))) {
            return Optional.empty();
        }
        return Optional.of(new QuestGroup(by, id));
    }

    /** The group a quest row follows: {@code by}/{@code group}, or the {@code topicId} of an older TOPIC row. */
    static Optional<QuestGroup> of(Map<String, Object> params) {
        String by = QuestRule.textParam(params, "by");
        String group = QuestRule.textParam(params, "group");
        if ((TOPIC.equals(by) || CATEGORY.equals(by)) && !group.isEmpty()) {
            return Optional.of(new QuestGroup(by, group));
        }
        String topicId = QuestRule.textParam(params, "topicId");
        return topicId.isEmpty() ? Optional.empty() : Optional.of(new QuestGroup(TOPIC, topicId));
    }

    /** The group's name kept with the row ({@code groupName}, or {@code topicName} of an older TOPIC row). */
    static String nameOf(Map<String, Object> params) {
        String name = QuestRule.textParam(params, "groupName");
        return name.isEmpty() ? QuestRule.textParam(params, "topicName") : name;
    }

    boolean isTopic() {
        return TOPIC.equals(by);
    }

    /** How titles name it: "chủ đề" (a learning topic) or "địa điểm" (a place category). */
    String noun() {
        return isTopic() ? "chủ đề" : "địa điểm";
    }

    @Override
    public String toString() {
        return by + ":" + id;
    }
}
