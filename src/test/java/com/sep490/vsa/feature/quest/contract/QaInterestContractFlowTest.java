package com.sep490.vsa.feature.quest.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.feature.quest.QuestSettings;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box checks of the quests that follow the Member's interests (QUEST_INTERESTS.md, 21/11/2026) through the API
 * the app uses: the new fields ({@code quests[].reason}, {@code interests}) have the agreed shape, and the "Luyện
 * tập" filters of a quest about a group lead the app to enough cards to finish it, cards that do count.
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaInterestContractFlowTest extends QaContractSupport {

    @Autowired
    private QuestSettings settings;

    private Map<String, List<String>> blogMapping;
    private final List<Long> blogTopics = new ArrayList<>();

    @BeforeEach
    void keepMapping() {
        blogMapping = new LinkedHashMap<>(settings.getInterest().getBlogTopics());
        settings.getInterest().setBlogTopics(new LinkedHashMap<>(blogMapping));
    }

    @AfterEach
    void putBackMapping() {
        settings.getInterest().setBlogTopics(blogMapping);
        for (long id : blogTopics) {
            jdbc.update("DELETE FROM user_interest_topics WHERE topic_id = ?", id);
            jdbc.update("DELETE FROM blog_topics WHERE id = ?", id);
        }
        blogTopics.clear();
    }

    /** A blog topic the Member chose, mapped to these groups; its name. */
    private String chosenTopic(long userId, String... groups) {
        String name = "QA quan tâm " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO blog_topics (name, is_other, sort_order, is_active) VALUES (?, FALSE, 60, TRUE)", name);
        long id = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, name);
        blogTopics.add(id);
        settings.getInterest().getBlogTopics().put(name, List.of(groups));
        jdbc.update("INSERT INTO user_interest_topics (user_id, topic_id) VALUES (?, ?)", userId, id);
        return name;
    }

    @Test
    void reasonAndInterestsHaveTheAgreedShape() throws Exception {
        qaDay();
        long m = member();
        String topic = topic("Khám bệnh QA");
        phrases(6, topic);
        isolateLibrary();
        String interest = chosenTopic(m, "topic:" + topic);

        JsonNode t = today(m);

        SoftAssertions s = new SoftAssertions();
        exactKeys(s, "today", t, TODAY_KEYS);
        s.assertThat(t.path("interests").size()).isEqualTo(1);
        exactKeys(s, "interests[0]", t.path("interests").get(0), "name", "chosen");
        s.assertThat(t.path("interests").path(0).path("name").asText()).isEqualTo(interest);
        s.assertThat(t.path("interests").path(0).path("chosen").isBoolean()).isTrue();
        s.assertThat(t.path("interests").path(0).path("chosen").asBoolean()).isTrue();
        int following = 0;
        for (JsonNode q : t.path("quests")) {
            exactKeys(s, q.path("code").asText(), q, QUEST_KEYS);
            JsonNode reason = q.get("reason");
            if (reason != null && !reason.isNull()) {
                following++;
                exactKeys(s, q.path("code").asText() + ".reason", reason, "code", "label", "detail");
                s.assertThat(reason.path("code").asText()).isEqualTo("INTEREST");
                s.assertThat(reason.path("label").asText()).isEqualTo("Chủ đề bạn quan tâm");
                s.assertThat(reason.path("detail").asText()).isEqualTo(interest);
            }
        }
        s.assertThat(following).as("at least one quest follows the interest").isPositive();
        s.assertAll();
    }

    /**
     * Every quest about a group, over a week of days: the app's filters find at least what is left to do, and
     * answering those cards finishes the quest.
     */
    @Test
    void theFiltersOfAQuestAboutAGroupLeadToCardsThatFinishIt() throws Exception {
        java.time.LocalDate first = qaDay();
        long m = member();
        String topic = topic("Đi tàu QA");
        phrases(6, topic);
        for (int i = 0; i < 6; i++) {
            phrase("station", "VOCAB");
        }
        phrases(3);
        isolateLibrary();
        chosenTopic(m, "topic:" + topic, "category:station");

        int checked = 0;
        for (int d = 0; d < 7; d++) {
            setJapanTime(first.plusDays(d), LocalTime.NOON);
            for (String code : codes(today(m).path("quests"))) {
                // read again: answers for one quest may move (or finish) another one about the same group
                JsonNode q = requireQuest(today(m), code);
                if (q.path("reason").isNull() || q.path("completed").asBoolean()) {
                    continue;
                }
                int left = q.path("target").asInt() - q.path("progress").asInt();
                List<Long> cards = cardsFor(m, q.path("action"));
                assertThat(cards).as(code + " " + q.path("action")).hasSizeGreaterThanOrEqualTo(left);
                for (long card : cards) {
                    JsonNode now = requireQuest(today(m), code);
                    if (now.path("completed").asBoolean()) {
                        break;
                    }
                    answer(m, card, true);
                }
                assertThat(requireQuest(today(m), code).path("completed").asBoolean())
                        .as(code + " finished with its own cards").isTrue();
                checked++;
            }
        }
        assertThat(checked).as("quests about a group were met").isGreaterThanOrEqualTo(7);
    }
}
