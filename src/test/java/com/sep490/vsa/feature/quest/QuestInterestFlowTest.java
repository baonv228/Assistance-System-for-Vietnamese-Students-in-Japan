package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quests that follow what the Member is interested in on the Blog and in the Cẩm nang (QUEST_INTERESTS.md,
 * 21/11/2026): the interests read from chosen topics, likes, saves, comments, own posts and guides; the groups of
 * "Luyện tập" they lead to; the day's quests that follow them; counting inside a group; rows made before.
 * <p>
 * Every blog topic, guide topic and learning topic is made by the test with a random name and mapped by the test
 * ({@code app.quests.interest.*}, put back afterwards), so the shared mapping of application.properties never meets
 * them; {@link #isolateLibrary()} makes the library exactly the test's own.
 */
class QuestInterestFlowTest extends QuestFlowSupport {

    @Autowired
    private QuestSettings settings;

    private record Topic(long id, String name) {
    }

    private record Ref(String name, boolean chosen) {
    }

    private final List<Long> blogs = new ArrayList<>();
    private final List<Long> blogTopics = new ArrayList<>();
    private final List<Long> guides = new ArrayList<>();
    private final List<String> guideTopics = new ArrayList<>();
    private Map<String, List<String>> blogMapping;
    private Map<String, List<String>> guideMapping;
    private int top;
    private int minPerDay;
    private Long staff;
    private Long author;
    private LocalDate day;

    @BeforeEach
    void keepSettings() {
        QuestSettings.Interest interest = settings.getInterest();
        blogMapping = new LinkedHashMap<>(interest.getBlogTopics());
        guideMapping = new LinkedHashMap<>(interest.getGuideTopics());
        top = interest.getTop();
        minPerDay = interest.getMinPerDay();
        interest.setBlogTopics(new LinkedHashMap<>(blogMapping));
        interest.setGuideTopics(new LinkedHashMap<>(guideMapping));
        day = freshDay();
    }

    @AfterEach
    void putBack() {
        QuestSettings.Interest interest = settings.getInterest();
        interest.setBlogTopics(blogMapping);
        interest.setGuideTopics(guideMapping);
        interest.setTop(top);
        interest.setMinPerDay(minPerDay);
        for (long id : guides) {
            jdbc.update("DELETE FROM guide_items WHERE id = ?", id);
        }
        guides.clear();
        for (String code : guideTopics) {
            jdbc.update("DELETE FROM guide_topics WHERE code = ?", code);
        }
        guideTopics.clear();
        for (long id : blogs) {
            jdbc.update("DELETE FROM blogs WHERE id = ?", id);
        }
        blogs.clear();
        for (long id : blogTopics) {
            jdbc.update("DELETE FROM user_interest_topics WHERE topic_id = ?", id);
            jdbc.update("DELETE FROM blog_topics WHERE id = ?", id);
        }
        blogTopics.clear();
        staff = null;
        author = null;
    }

    /** Today's quests from exactly these codes, following the Member's interests (strongest first). */
    @Override
    protected JsonNode force(long user, String... codes) {
        return json.valueToTree(quests.forceToday(principal(user), List.of(codes), true));
    }

    // ---------------------------------------------------------------- what the Member is interested in

    @Test
    void aTopicTheMemberChoseIsAnInterestAtOnce() throws Exception {
        long m = member();
        String health = topic("Khám bệnh");
        phrases(5, health);
        isolateLibrary();
        Topic yTe = blogTopic("QI Y tế", "topic:" + health);

        assertThat(interests(today(m))).isEmpty();
        choose(m, yTe);

        assertThat(interests(today(m))).as("read on every call").containsExactly(new Ref(yTe.name(), true));
    }

    @Test
    void oneLikeIsNotEnoughTwoAre() throws Exception {
        long m = member();
        String train = topic("Đi tàu");
        phrases(5, train);
        isolateLibrary();
        Topic diLai = blogTopic("QI Đi lại", "topic:" + train);

        like(m, post(diLai), 3);
        assertThat(interests(today(m))).as("one like: weight 1 < 2").isEmpty();
        like(m, post(diLai), 3);
        assertThat(interests(today(m))).containsExactly(new Ref(diLai.name(), false));
    }

    @Test
    void everyBlogActivityCountsWithItsWeight() throws Exception {
        long m = member();
        String ta = topic("A");
        String tb = topic("B");
        String tc = topic("C");
        String td = topic("D");
        for (String t : List.of(ta, tb, tc, td)) {
            phrases(3, t);
        }
        isolateLibrary();
        Topic a = blogTopic("QI A", "topic:" + ta);
        Topic b = blogTopic("QI B", "topic:" + tb);
        Topic c = blogTopic("QI C", "topic:" + tc);
        Topic d = blogTopic("QI D", "topic:" + td);

        long postA = post(a);                                       // two comments on one post: 2, not 4
        comment(m, postA, "PUBLISHED", 1);
        comment(m, postA, "PUBLISHED", 1);
        long postB = post(b);                                       // like 1 + save 2 = 3
        like(m, postB, 2);
        saveBlog(m, postB, 2);
        ownPost(m, c, "PUBLISHED", 5);                              // own published post or question: 3
        ownPost(m, c, "PUBLISHED", 6);                              // 6 in all
        comment(m, post(d), "PROCESSING", 1);                       // not published: nothing
        ownPost(m, d, "PROCESSING", 1);

        assertThat(interests(today(m))).as("strongest first")
                .containsExactly(new Ref(c.name(), false), new Ref(b.name(), false), new Ref(a.name(), false));
    }

    @Test
    void activityOlderThanTheWindowIsForgottenButAChosenTopicIsNot() throws Exception {
        long m = member();
        String to = topic("Old");
        String tn = topic("New");
        String tc = topic("Chosen");
        phrases(3, to, tn, tc);
        isolateLibrary();
        Topic old = blogTopic("QI Old", "topic:" + to);
        Topic recent = blogTopic("QI New", "topic:" + tn);
        Topic chosen = blogTopic("QI Chosen", "topic:" + tc);
        saveBlog(m, post(old), 91);
        saveBlog(m, post(recent), 89);
        choose(m, chosen);

        assertThat(interests(today(m))).extracting(Ref::name).containsExactlyInAnyOrder(recent.name(), chosen.name());
    }

    @Test
    void guidesCountTooOncePerGuideForTheChecklist() throws Exception {
        long m = member();
        String t1 = topic("G1");
        String t2 = topic("G2");
        String t3 = topic("G3");
        String t4 = topic("G4");
        phrases(3, t1, t2, t3, t4);
        isolateLibrary();
        String saved = guideTopic("QI Lưu", "topic:" + t1);
        String done = guideTopic("QI Xong", "topic:" + t2);
        String ticked = guideTopic("QI Tick", "topic:" + t3);
        String hidden = guideTopic("QI Ẩn", "topic:" + t4);

        saveGuide(m, guide(saved, true, false), 2);                  // 2
        doneGuide(m, guide(done, true, false), 2);                   // 1: not enough alone
        long checklist = guide(ticked, true, false);                 // three ticks of one guide: 2, not 6
        tick(m, checklist, 1);
        tick(m, checklist, 1);
        tick(m, checklist, 1);
        doneGuide(m, checklist, 1);                                  // + 1 = 3
        saveGuide(m, guide(hidden, false, false), 1);                // not published
        saveGuide(m, guide(hidden, true, true), 1);                  // deleted

        assertThat(interests(today(m))).containsExactly(new Ref(titleOf(ticked), false), new Ref(titleOf(saved), false));
    }

    @Test
    void aBlogTopicAndAGuideTopicOfTheSameNameAreOneInterest() throws Exception {
        long m = member();
        String train = topic("Đi tàu");
        String station = topic("Ga");
        phrases(3, train, station);
        isolateLibrary();
        Topic blog = blogTopic("QI Đi lại", "topic:" + train);
        String guide = guideTopicTitled(blog.name(), "topic:" + station);

        like(m, post(blog), 1);                                      // 1
        doneGuide(m, guide(guide, true, false), 1);                  // + 1 = 2, under one name

        JsonNode t = today(m);
        assertThat(interests(t)).containsExactly(new Ref(blog.name(), false));
    }

    @Test
    void onlyInterestsTheLibraryCanFollowAreListedTheThreeStrongestFirst() throws Exception {
        long m = member();
        String gone = topic("Gone");
        List<Long> goneEntries = phrases(1, gone);
        String t1 = topic("T1");
        String t2 = topic("T2");
        String t3 = topic("T3");
        String t4 = topic("T4");
        phrases(3, t1, t2, t3, t4);
        String two = topic("Hai thẻ");
        phrases(2, two);                                             // under the smallest target (3): no quest can follow
        isolateLibrary();
        jdbc.update("UPDATE phrases SET deleted_at = ? WHERE id = ?", Timestamp.from(clock.instant()),
                goneEntries.get(0));
        Topic tiny = blogTopic("QI Tiny", "topic:" + two);
        choose(m, tiny);
        saveBlog(m, post(tiny), 1);
        saveBlog(m, post(tiny), 1);                                  // 8: the strongest, still not listed
        Topic unmapped = blogTopic("QI Unmapped");
        Topic empty = blogTopic("QI Empty", "topic:" + gone, "topic:nosuchtopic");
        Topic other = blogTopic("QI Khác", "topic:" + t1);
        jdbc.update("UPDATE blog_topics SET is_other = TRUE WHERE id = ?", other.id());
        Topic off = blogTopic("QI Off", "topic:" + t1);
        jdbc.update("UPDATE blog_topics SET is_active = FALSE WHERE id = ?", off.id());
        Topic a = blogTopic("QI A", "topic:" + t1);
        Topic b = blogTopic("QI B", "topic:" + t2);
        Topic c = blogTopic("QI C", "topic:" + t3);
        Topic d = blogTopic("QI D", "topic:" + t4);
        for (Topic t : List.of(unmapped, empty, other, off, a, b, c, d)) {
            choose(m, t);                                            // 4 each
        }
        saveBlog(m, post(c), 1);                                     // C 6
        like(m, post(b), 1);                                         // B 5
        // A and D 4: chosen both, A before D by name

        assertThat(interests(today(m))).containsExactly(new Ref(c.name(), true), new Ref(b.name(), true),
                new Ref(a.name(), true));
    }

    // ---------------------------------------------------------------- the day's quests

    @Test
    void topicFollowsTheInterestBeforeTheStageAndTheMostToLearn() throws Exception {
        long m = member();
        setStage(m, "ARR");
        String mine = topic("Khám bệnh");
        String other = topic("Thanh toán QI");
        phrases(6, mine);
        phrases(9, other);
        String stageTopic = settings.topicsFor("ARR").isEmpty() ? null : settings.topicsFor("ARR").get(0);
        isolateLibrary();
        Topic yTe = blogTopic("QI Y tế", "topic:" + mine);
        choose(m, yTe);

        JsonNode t = force(m, "TOPIC");

        JsonNode topic = quest(t, "TOPIC");
        assertThat(topic.path("title").asText()).isEqualTo("Ôn 5 thẻ chủ đề “Khám bệnh”");
        assertThat(topic.path("description").asText()).isEqualTo("Trả lời 5 thẻ khác nhau của chủ đề Khám bệnh.");
        assertThat(action(topic)).isEqualTo(List.of("PRACTICE", "topic", mine, "Khám bệnh", "", ""));
        assertThat(reason(topic)).isEqualTo(List.of("INTEREST", "Chủ đề bạn quan tâm", yTe.name()));
        assertThat(stageTopic).as("the stage's topics are not in this library").isNotEqualTo(mine);
        JsonNode params = json.readTree(jdbc.queryForObject(
                "SELECT params FROM user_daily_quests WHERE id = ?", String.class, topic.path("id").asLong()));
        assertThat(params.path("topicId").asText()).as("a build of before groups still reads the topic")
                .isEqualTo(mine);
        assertThat(params.path("topicName").asText()).isEqualTo("Khám bệnh");
    }

    @Test
    void theDevApiFollowsTheStrongestInterestEveryDayAndNothingByDefault() throws Exception {
        long m = member();
        String strong = topic("Mạnh");
        String weak = topic("Yếu");
        phrases(6, strong);
        phrases(6, weak);
        isolateLibrary();
        Topic s = blogTopic("QI Mạnh", "topic:" + strong);
        Topic w = blogTopic("QI Yếu", "topic:" + weak);
        choose(m, s);
        saveBlog(m, post(s), 1);                                     // 6
        choose(m, w);                                                // 4: drawn first on some days, never when forced
        for (int d = 0; d < 6; d++) {
            setJapanTime(day.plusDays(d), LocalTime.NOON);
            JsonNode t = force(m, "TOPIC", "LEARN_NEW");
            assertThat(reason(quest(t, "TOPIC")).get(2)).as("day " + d).isEqualTo(s.name());
            assertThat(reason(quest(t, "LEARN_NEW")).get(2)).as("day " + d).isEqualTo(w.name());
        }
        setJapanTime(day.plusDays(7), LocalTime.NOON);
        JsonNode plain = json.valueToTree(quests.forceToday(principal(m), List.of("TOPIC", "LEARN_NEW")));
        assertThat(reason(quest(plain, "TOPIC"))).as("the dev API's default: as without interests").isNull();
        assertThat(reason(quest(plain, "LEARN_NEW"))).isNull();
        assertThat(interests(plain)).extracting(Ref::name).containsExactly(s.name(), w.name());
    }

    @Test
    void withoutAnInterestTopicStillFollowsTheStageWithReasonStage() throws Exception {
        long m = member();
        setStage(m, "ARR");
        List<String> stageTopics = settings.topicsFor("ARR");
        assertThat(stageTopics).isNotEmpty();
        String stageTopic = stageTopics.get(0);
        boolean existed = rows("SELECT COUNT(*) FROM learning_topics WHERE id = ?", stageTopic) > 0;
        if (!existed) {
            jdbc.update("INSERT INTO learning_topics (id, name, icon) VALUES (?, 'Giai đoạn QI', 'label')", stageTopic);
        }
        try {
            List<Long> entries = phrases(5);
            for (long id : entries) {
                jdbc.update("INSERT INTO phrase_topic_links (phrase_id, topic_id) VALUES (?, ?)", id, stageTopic);
            }
            isolateLibrary();

            JsonNode topic = quest(force(m, "TOPIC"), "TOPIC");

            assertThat(action(topic).get(2)).isEqualTo(stageTopic);
            assertThat(reason(topic)).isEqualTo(List.of("STAGE", "Hợp giai đoạn của bạn", ""));
            assertThat(topic.path("reason").get("detail").isNull()).isTrue();
        } finally {
            if (!existed) {
                jdbc.update("DELETE FROM phrase_topic_links WHERE topic_id = ?", stageTopic);
                jdbc.update("DELETE FROM learning_topics WHERE id = ?", stageTopic);
            }
        }
    }

    @Test
    void questsOfADayFollowDifferentInterestsFirst() throws Exception {
        long m = member();
        String t1 = topic("Một");
        String t2 = topic("Hai");
        phrases(6, t1);
        phrases(6, t2);
        isolateLibrary();
        Topic i1 = blogTopic("QI Một", "topic:" + t1);
        Topic i2 = blogTopic("QI Hai", "topic:" + t2);
        choose(m, i1);
        choose(m, i2);

        JsonNode t = force(m, "LEARN_NEW", "TOPIC");

        JsonNode topic = quest(t, "TOPIC");
        JsonNode learn = quest(t, "LEARN_NEW");
        assertThat(Set.of(reason(topic).get(2), reason(learn).get(2))).containsExactlyInAnyOrder(i1.name(), i2.name());
        String topicGroup = reason(topic).get(2).equals(i1.name()) ? t1 : t2;
        String learnGroup = topicGroup.equals(t1) ? t2 : t1;
        assertThat(action(topic).get(2)).isEqualTo(topicGroup);
        assertThat(action(learn)).isEqualTo(List.of("PRACTICE", "topic", learnGroup, learnGroup.equals(t1) ? "Một"
                : "Hai", "", "new"));
        assertThat(learn.path("title").asText()).isEqualTo("Thuộc 3 mục mới chủ đề “" + (learnGroup.equals(t1)
                ? "Một" : "Hai") + "”");
    }

    @Test
    void oneInterestCanLeadEveryQuestOfTheDay() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        List<Long> entries = phrases(8, mine);
        isolateLibrary();
        for (int i = 0; i < 5; i++) {
            mark(m, entries.get(i), false);                           // "Chưa thuộc"
        }
        Topic yTe = blogTopic("QI Y tế", "topic:" + mine);
        choose(m, yTe);

        JsonNode t = force(m, "FLASH_REVIEW", "TOPIC", "LEARN_NEW", "REVIEW_LEARNING");

        assertThat(reason(quest(t, "FLASH_REVIEW"))).as("the fixed quest stays the same for all").isNull();
        for (String code : List.of("TOPIC", "LEARN_NEW", "REVIEW_LEARNING")) {
            assertThat(reason(quest(t, code))).as(code).containsExactly("INTEREST", "Chủ đề bạn quan tâm", yTe.name());
            assertThat(action(quest(t, code)).subList(1, 4)).as(code).containsExactly("topic", mine, "Khám bệnh");
        }
        JsonNode review = quest(t, "REVIEW_LEARNING");
        assertThat(review.path("title").asText()).isEqualTo("Luyện lại 5 thẻ “Chưa thuộc” chủ đề “Khám bệnh”");
        assertThat(review.path("description").asText())
                .isEqualTo("Luyện lại 5 thẻ đang ở trạng thái Chưa thuộc của chủ đề Khám bệnh.");
        assertThat(action(review).get(5)).isEqualTo("learning");
        JsonNode learn = quest(t, "LEARN_NEW");
        assertThat(learn.path("description").asText())
                .isEqualTo("Chọn “Đã thuộc” cho 3 mục bạn chưa thuộc của chủ đề Khám bệnh.");
        assertThat(action(learn).get(5)).as("3 entries without a mark: new").isEqualTo("new");
    }

    @Test
    void aGroupWithTooFewEntriesForTheFullTargetIsNotFollowed() throws Exception {
        long m = member();
        String small = topic("Nhỏ");
        String big = topic("Lớn");
        phrases(4, small);                                            // < 5 for TOPIC, ≥ 3 for LEARN_NEW
        phrases(6, big);
        isolateLibrary();
        Topic i = blogTopic("QI Nhỏ", "topic:" + small);
        choose(m, i);

        JsonNode t = force(m, "TOPIC", "LEARN_NEW");

        JsonNode topic = quest(t, "TOPIC");
        assertThat(action(topic).get(2)).as("TOPIC by the most to learn, full target").isEqualTo(big);
        assertThat(topic.path("target").asInt()).isEqualTo(5);
        assertThat(reason(topic)).isNull();
        JsonNode learn = quest(t, "LEARN_NEW");
        assertThat(action(learn).get(2)).isEqualTo(small);
        assertThat(reason(learn)).containsExactly("INTEREST", "Chủ đề bạn quan tâm", i.name());
    }

    @Test
    void theFirstGroupOfAnInterestWithEnoughEntriesIsTakenPlacesToo() throws Exception {
        long m = member();
        String small = topic("Vé tàu");
        phrases(3, small);
        List<Long> stationEntries = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            stationEntries.add(phrase("station", "PHRASE"));
        }
        isolateLibrary();
        Topic diLai = blogTopic("QI Đi lại", "topic:" + small, "category:station");
        choose(m, diLai);

        JsonNode t = force(m, "TOPIC", "LEARN_NEW");

        JsonNode topic = quest(t, "TOPIC");
        assertThat(topic.path("title").asText()).isEqualTo("Ôn 5 thẻ địa điểm “Ga tàu”");
        assertThat(topic.path("description").asText()).isEqualTo("Trả lời 5 thẻ khác nhau của địa điểm Ga tàu.");
        assertThat(action(topic)).isEqualTo(List.of("PRACTICE", "category", "station", "Ga tàu", "", ""));
        JsonNode learn = quest(t, "LEARN_NEW");
        assertThat(action(learn).subList(1, 4)).as("first group with 3 entries").containsExactly("topic", small, "Vé tàu");
        assertThat(stationEntries).hasSize(6);
    }

    @Test
    void questsAboutAGroupCountOnlyItsEntries() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        String elsewhere = topic("Khác QI");
        List<Long> in = phrases(6, mine);
        List<Long> out = phrases(6, elsewhere);
        isolateLibrary();
        choose(m, blogTopic("QI Y tế", "topic:" + mine));
        force(m, "FLASH_REVIEW", "TOPIC", "LEARN_NEW");

        JsonNode outside = answer(m, out.get(0), true).path("quests");
        assertThat(changedCodes(outside)).containsExactly("FLASH_REVIEW");
        next();
        JsonNode inside = answer(m, in.get(0), true).path("quests");
        assertThat(changedCodes(inside)).containsExactlyInAnyOrder("FLASH_REVIEW", "TOPIC", "LEARN_NEW");
    }

    @Test
    void reviewLearningAboutAGroupCountsOnlyItsChuaThuocEntries() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        String elsewhere = topic("Khác QI");
        List<Long> in = phrases(5, mine);
        List<Long> out = phrases(5, elsewhere);
        isolateLibrary();
        for (long id : in) {
            mark(m, id, false);
        }
        for (long id : out) {
            mark(m, id, false);
        }
        choose(m, blogTopic("QI Y tế", "topic:" + mine));
        force(m, "REVIEW_LEARNING");

        assertThat(changedCodes(answer(m, out.get(0), true).path("quests"))).isEmpty();
        next();
        assertThat(changedCodes(answer(m, in.get(0), true).path("quests"))).containsExactly("REVIEW_LEARNING");
    }

    @Test
    void aTopicAboutAPlaceCountsTheEntriesOfThatPlace() throws Exception {
        long m = member();
        List<Long> station = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            station.add(phrase("station", "VOCAB"));
        }
        List<Long> konbini = phrases(3);
        isolateLibrary();
        choose(m, blogTopic("QI Đi lại", "category:station"));
        force(m, "TOPIC");

        assertThat(changedCodes(answer(m, konbini.get(0), true).path("quests"))).isEmpty();
        next();
        JsonNode change = answer(m, station.get(0), false).path("quests");
        assertThat(changedCodes(change)).containsExactly("TOPIC");
        assertThat(change.path("changed").path(0).path("progress").asInt()).isEqualTo(1);
    }

    @Test
    void learnNewAboutAGroupSendsTheAppToWhatIsLeftInThatGroup() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        List<Long> in = phrases(4, mine);
        phrases(10);                                                  // plenty of new entries outside the group
        isolateLibrary();
        choose(m, blogTopic("QI Y tế", "topic:" + mine));
        force(m, "LEARN_NEW");
        assertThat(action(quest(today(m), "LEARN_NEW")).get(5)).isEqualTo("new");

        answer(m, in.get(0), false);                                  // "Chưa thuộc": not counted, 3 new left
        next();
        answer(m, in.get(1), false);                                  // 2 new + 2 "Chưa thuộc" left, 3 needed
        next();
        assertThat(action(quest(today(m), "LEARN_NEW")).get(5)).as("neither list holds 3 in the group").isEmpty();

        answer(m, in.get(2), true);                                   // counted: 2 needed, 2 "Chưa thuộc" left
        JsonNode learn = quest(today(m), "LEARN_NEW");
        assertThat(learn.path("progress").asInt()).isEqualTo(1);
        assertThat(action(learn)).isEqualTo(List.of("PRACTICE", "topic", mine, "Khám bệnh", "", "learning"));
    }

    @Test
    void aTopicQuestMadeBeforeGroupsExistedStillReadsAndCounts() throws Exception {
        long m = member();
        String old = topic("Thanh toán cũ");
        List<Long> entries = phrases(5, old);
        isolateLibrary();
        jdbc.update("""
                INSERT INTO user_daily_quests (user_id, quest_date, code, sort_order, params, title, target, created_at)
                VALUES (?, ?, 'TOPIC', 0, ?, ?, 5, ?)""", m, Date.valueOf(day),
                "{\"topicId\":\"" + old + "\",\"topicName\":\"Thanh toán cũ\"}", "Ôn 5 thẻ chủ đề “Thanh toán cũ”",
                Timestamp.from(clock.instant()));

        JsonNode topic = quest(today(m), "TOPIC");
        assertThat(action(topic)).isEqualTo(List.of("PRACTICE", "topic", old, "Thanh toán cũ", "", ""));
        assertThat(topic.path("description").asText()).isEqualTo("Trả lời 5 thẻ khác nhau của chủ đề Thanh toán cũ.");
        assertThat(topic.get("reason").isNull()).isTrue();
        assertThat(changedCodes(answer(m, entries.get(0), true).path("quests"))).containsExactly("TOPIC");
    }

    /**
     * Only TOPIC can follow the interest here (its 5 entries are all "Đã thuộc": nothing for LEARN_NEW or
     * REVIEW_LEARNING in the group), while LEARN_NEW, REVIEW_LEARNING and PHRASE_OF_DAY are all possible on the whole
     * library: without {@code min-per-day} most days would hold no quest about the interest.
     */
    @Test
    void everyDayAQuestFollowsAnInterestAndTheDaysStillVary() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        List<Long> known = phrases(5, mine);
        List<Long> learning = phrases(5);
        phrases(2);
        isolateLibrary();
        known.forEach(id -> mark(m, id, true));
        learning.forEach(id -> mark(m, id, false));
        Topic yTe = blogTopic("QI Y tế", "topic:" + mine);
        choose(m, yTe);

        Set<Set<String>> sets = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int d = 0; d < 21; d++) {
            setJapanTime(day.plusDays(d), LocalTime.NOON);
            JsonNode t = today(m);
            Set<String> rotating = new HashSet<>(codes(t));
            rotating.remove("FLASH_REVIEW");
            assertThat(t.path("quests").size()).as("day " + d).isEqualTo(3);
            JsonNode topic = quest(t, "TOPIC");
            assertThat(topic).as("day " + d + ": " + codes(t)).isNotNull();
            assertThat(reason(topic)).containsExactly("INTEREST", "Chủ đề bạn quan tâm", yTe.name());
            for (JsonNode q : t.path("quests")) {
                if (!"TOPIC".equals(q.path("code").asText())) {
                    assertThat(reason(q)).as(q.path("code").asText()).isNull();
                }
            }
            sets.add(rotating);
            seen.addAll(rotating);
        }
        assertThat(sets.size()).as("the pairs of rotating quests vary over the days").isGreaterThan(1);
        assertThat(seen).as("the other quests still turn up")
                .contains("PHRASE_OF_DAY", "LEARN_NEW", "REVIEW_LEARNING");
    }

    @Test
    void minPerDayZeroLeavesTheChoiceToChance() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        List<Long> known = phrases(5, mine);
        List<Long> learning = phrases(5);
        phrases(2);
        isolateLibrary();
        known.forEach(id -> mark(m, id, true));
        learning.forEach(id -> mark(m, id, false));
        choose(m, blogTopic("QI Y tế", "topic:" + mine));
        settings.getInterest().setMinPerDay(0);

        int withTopic = 0;
        for (int d = 0; d < 21; d++) {
            setJapanTime(day.plusDays(d), LocalTime.NOON);
            JsonNode topic = quest(today(m), "TOPIC");
            if (topic != null) {
                withTopic++;
                assertThat(reason(topic).get(0)).as("a TOPIC still follows the interest").isEqualTo("INTEREST");
            }
        }
        assertThat(withTopic).as("TOPIC is no longer on every day").isLessThan(21);
    }

    @Test
    void theSharedMappingBindsFromApplicationProperties() {
        QuestSettings.Interest interest = settings.getInterest();
        assertThat(interest.getDays()).isEqualTo(90);
        assertThat(interest.getMinWeight()).isEqualTo(2);
        assertThat(interest.getChosenWeight()).isEqualTo(4);
        assertThat(blogMapping).as("blog topics of V1 by folded name").containsKeys("thutucgiayto", "visavacutru",
                "nhao", "anuong", "dilai", "hoctiengnhat", "yte", "muasam", "nganhangvatien", "saukhivenuoc");
        assertThat(interest.groupsOfBlogTopic("Y tế"))
                .containsExactly("topic:health", "category:hospital", "category:pharmacy");
        assertThat(interest.groupsOfBlogTopic("  thủ TỤC giấy  tờ ")).containsExactly("topic:paper", "category:ward");
        assertThat(interest.groupsOfBlogTopic("Kinh nghiệm")).isEmpty();
        assertThat(interest.groupsOfGuideTopic("HEALTH"))
                .containsExactly("topic:health", "category:hospital", "category:pharmacy");
        assertThat(interest.groupsOfGuideTopic("transport")).containsExactly("topic:train", "category:station");
        assertThat(interest.groupsOfGuideTopic("WORK")).isEmpty();
        List<String> blogNames = jdbc.queryForList("SELECT name FROM blog_topics WHERE NOT is_other", String.class);
        for (String name : List.of("Thủ tục giấy tờ", "Visa và cư trú", "Nhà ở", "Ăn uống", "Đi lại",
                "Học tiếng Nhật", "Y tế", "Mua sắm", "Ngân hàng và tiền", "Sau khi về nước")) {
            assertThat(blogNames).as("V1 still names the topic " + name).contains(name);
            assertThat(interest.groupsOfBlogTopic(name)).as(name).isNotEmpty();
        }
        for (String code : List.of("LIVING", "PROCEDURES", "STUDY", "HOUSING", "HEALTH", "TRANSPORT")) {
            assertThat(rows("SELECT COUNT(*) FROM guide_topics WHERE code = ?", code)).as(code).isEqualTo(1);
            assertThat(interest.groupsOfGuideTopic(code)).as(code).isNotEmpty();
        }
        for (List<String> groups : List.of(blogMapping.values(), guideMapping.values()).stream()
                .flatMap(c -> c.stream()).toList()) {
            for (String group : groups) {
                QuestGroup g = QuestGroup.parse(group).orElseThrow(() -> new AssertionError("bad group " + group));
                if (!g.isTopic()) {
                    assertThat(rows("SELECT COUNT(*) FROM place_categories WHERE id = ?", g.id())).as(group)
                            .isEqualTo(1);
                }
            }
        }
    }

    @Test
    void withoutInterestsNoQuestClaimsOne() throws Exception {
        long m = member();
        String any = topic("Bất kỳ");
        phrases(6, any);
        isolateLibrary();
        Topic unmapped = blogTopic("QI Không ánh xạ");
        choose(m, unmapped);

        for (int d = 0; d < 7; d++) {
            setJapanTime(day.plusDays(d), LocalTime.NOON);
            JsonNode t = today(m);
            assertThat(interests(t)).isEmpty();
            for (JsonNode q : t.path("quests")) {
                assertThat(q.path("reason").path("code").asText()).as(codes(t).toString()).isNotEqualTo("INTEREST");
            }
        }
    }

    @Test
    void interestsChangedDuringTheDayShowAtOnceAndLeadTheQuestsFromTheNextDay() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        phrases(6, mine);
        isolateLibrary();
        Topic yTe = blogTopic("QI Y tế", "topic:" + mine);
        JsonNode before = force(m, "FLASH_REVIEW", "TOPIC");
        assertThat(reason(quest(before, "TOPIC"))).isNull();

        choose(m, yTe);
        JsonNode later = today(m);
        assertThat(ids(later)).as("the day's quests never change").isEqualTo(ids(before));
        assertThat(reason(quest(later, "TOPIC"))).isNull();
        assertThat(interests(later)).containsExactly(new Ref(yTe.name(), true));

        setJapanTime(day.plusDays(1), LocalTime.NOON);
        JsonNode tomorrow = today(m);
        assertThat(tomorrow.path("quests")).anySatisfy(q ->
                assertThat(q.path("reason").path("detail").asText()).isEqualTo(yTe.name()));
    }

    @Test
    void topZeroTurnsTheInterestsOff() throws Exception {
        long m = member();
        String mine = topic("Khám bệnh");
        phrases(6, mine);
        isolateLibrary();
        choose(m, blogTopic("QI Y tế", "topic:" + mine));
        settings.getInterest().setTop(0);

        JsonNode t = force(m, "TOPIC", "LEARN_NEW");

        assertThat(interests(t)).isEmpty();
        assertThat(reason(quest(t, "TOPIC"))).isNull();
        assertThat(reason(quest(t, "LEARN_NEW"))).isNull();
        assertThat(quest(t, "LEARN_NEW").path("title").asText()).isEqualTo("Thuộc 3 mục mới");
    }

    @Test
    void theInterestsOfTheDayAreChosenOnceAndStayAllDay() throws Exception {
        long m = member();
        String t1 = topic("Một");
        String t2 = topic("Hai");
        phrases(6, t1);
        phrases(6, t2);
        isolateLibrary();
        choose(m, blogTopic("QI Một", "topic:" + t1));
        choose(m, blogTopic("QI Hai", "topic:" + t2));

        JsonNode first = today(m);
        clock.advance(Duration.ofHours(5));
        JsonNode again = today(m);

        assertThat(again.path("quests")).isEqualTo(first.path("quests"));
    }

    // ---------------------------------------------------------------- helpers

    /** {@code interests} of GET today. */
    private static List<Ref> interests(JsonNode today) {
        List<Ref> refs = new ArrayList<>();
        for (JsonNode i : today.path("interests")) {
            assertThat(i.size()).as("interest keys: name, chosen").isEqualTo(2);
            refs.add(new Ref(i.path("name").asText(), i.path("chosen").asBoolean()));
        }
        return refs;
    }

    /** [type, by, group, groupName, kind, status] of a quest's action. */
    private static List<String> action(JsonNode quest) {
        JsonNode a = quest.path("action");
        return List.of(a.path("type").asText(), a.path("by").asText(), a.path("group").asText(),
                a.path("groupName").asText(), a.path("kind").asText(), a.path("status").asText());
    }

    /** [code, label, detail] of a quest's reason ("" for a null detail), null when the quest has none. */
    private static List<String> reason(JsonNode quest) {
        JsonNode r = quest.get("reason");
        assertThat(r).as("reason is always present").isNotNull();
        if (r.isNull()) {
            return null;
        }
        assertThat(r.size()).as("reason keys: code, label, detail").isEqualTo(3);
        return List.of(r.path("code").asText(), r.path("label").asText(),
                r.get("detail").isNull() ? "" : r.path("detail").asText());
    }

    private static String rand() {
        return Long.toString(ThreadLocalRandom.current().nextLong(1L << 40), 36);
    }

    private Timestamp daysAgo(int days) {
        return Timestamp.from(clock.instant().minus(Duration.ofDays(days)).minusSeconds(60));
    }

    /** A blog topic made for this test (unique name), mapped to these groups. */
    private Topic blogTopic(String name, String... groups) {
        String unique = name + " " + rand();
        jdbc.update("INSERT INTO blog_topics (name, is_other, sort_order, is_active) VALUES (?, FALSE, 50, TRUE)",
                unique);
        long id = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, unique);
        blogTopics.add(id);
        if (groups.length > 0) {
            settings.getInterest().getBlogTopics().put(unique, List.of(groups));
        }
        return new Topic(id, unique);
    }

    /** A guide topic made for this test (unique code and title), mapped to these groups; its code. */
    private String guideTopic(String title, String... groups) {
        return guideTopicTitled(title + " " + rand(), groups);
    }

    private String guideTopicTitled(String title, String... groups) {
        String code = "QI" + rand().toUpperCase();
        jdbc.update("INSERT INTO guide_topics (code, title, sort_order) VALUES (?, ?, 50)", code, title);
        guideTopics.add(code);
        settings.getInterest().getGuideTopics().put(code, List.of(groups));
        return code;
    }

    private String titleOf(String guideTopic) {
        return jdbc.queryForObject("SELECT title FROM guide_topics WHERE code = ?", String.class, guideTopic);
    }

    private void choose(long userId, Topic topic) {
        jdbc.update("INSERT INTO user_interest_topics (user_id, topic_id) VALUES (?, ?)", userId, topic.id());
    }

    private long author() {
        if (author == null) {
            author = member();
        }
        return author;
    }

    /** A published post of another Member in these topics. */
    private long post(Topic... topics) {
        return blog(author(), "PUBLISHED", 10, topics);
    }

    private void ownPost(long userId, Topic topic, String status, int daysAgo) {
        blog(userId, status, daysAgo, topic);
    }

    private long blog(long authorId, String status, int daysAgo, Topic... topics) {
        String title = "QI bài " + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status, created_at)
                VALUES (?, 'POST', ?, 'Nội dung thử', ?, ?)""", authorId, title, status, daysAgo(daysAgo));
        long id = jdbc.queryForObject("SELECT id FROM blogs WHERE title = ?", Long.class, title);
        blogs.add(id);
        for (Topic topic : topics) {
            jdbc.update("INSERT INTO blog_topic_links (blog_id, topic_id) VALUES (?, ?)", id, topic.id());
        }
        return id;
    }

    private void like(long userId, long blogId, int daysAgo) {
        jdbc.update("INSERT INTO blog_likes (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                daysAgo(daysAgo));
    }

    private void saveBlog(long userId, long blogId, int daysAgo) {
        jdbc.update("INSERT INTO saved_blogs (blog_id, user_id, created_at) VALUES (?, ?, ?)", blogId, userId,
                daysAgo(daysAgo));
    }

    private void comment(long userId, long blogId, String status, int daysAgo) {
        jdbc.update("INSERT INTO comments (blog_id, author_id, content, status, created_at) VALUES (?, ?, ?, ?, ?)",
                blogId, userId, "Bình luận thử", status, daysAgo(daysAgo));
    }

    private long staff() {
        if (staff == null) {
            staff = user("MODERATOR", "ACTIVE");
        }
        return staff;
    }

    /** A guide of this topic. */
    private long guide(String topicCode, boolean published, boolean deleted) {
        String title = "QI cẩm nang " + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guide_items (stage_code, title, topic_code, published, deleted_at, created_by, updated_by)
                VALUES ('ARR', ?, ?, ?, ?, ?, ?)""", title, topicCode, published,
                deleted ? Timestamp.from(clock.instant()) : null, staff(), staff());
        long id = jdbc.queryForObject("SELECT id FROM guide_items WHERE title = ?", Long.class, title);
        guides.add(id);
        return id;
    }

    private void saveGuide(long userId, long guideId, int daysAgo) {
        jdbc.update("INSERT INTO saved_guides (user_id, guide_id, created_at) VALUES (?, ?, ?)", userId, guideId,
                daysAgo(daysAgo));
    }

    private void doneGuide(long userId, long guideId, int daysAgo) {
        jdbc.update("INSERT INTO user_guide_progress (user_id, item_id, done_at) VALUES (?, ?, ?)", userId, guideId,
                daysAgo(daysAgo));
    }

    /** A new checklist task of the guide, ticked by the Member. */
    private void tick(long userId, long guideId, int daysAgo) {
        String title = "QI việc " + UUID.randomUUID();
        jdbc.update("INSERT INTO guide_preparation_tasks (guide_id, title, sort_order) VALUES (?, ?, 0)", guideId,
                title);
        long task = jdbc.queryForObject("SELECT id FROM guide_preparation_tasks WHERE title = ?", Long.class, title);
        jdbc.update("INSERT INTO guide_preparation_progress (user_id, task_id, completed_at) VALUES (?, ?, ?)", userId,
                task, daysAgo(daysAgo));
    }
}
