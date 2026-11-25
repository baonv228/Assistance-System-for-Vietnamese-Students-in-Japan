package com.sep490.vsa.feature.quest;

import com.sep490.vsa.feature.quest.QuestInterests.Interest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure parts of the quests that follow the Member's interests: the day's order, groups, the mapping lookup. */
class QuestInterestsUnitTest {

    private static Interest interest(String name, int weight) {
        return new Interest(name, weight, false, List.of(new QuestGroup("topic", name.toLowerCase())));
    }

    @Test
    void zeroOrOneInterestKeepsItsOrder() {
        LocalDate day = LocalDate.of(2026, 11, 21);
        assertThat(QuestInterests.dayOrder(7, day, List.of())).isEmpty();
        List<Interest> one = List.of(interest("A", 5));
        assertThat(QuestInterests.dayOrder(7, day, one)).isEqualTo(one);
    }

    @Test
    void theDaysOrderIsTheSameForTheSameMemberAndDayAndKeepsEveryInterest() {
        List<Interest> list = List.of(interest("A", 6), interest("B", 3), interest("C", 2));
        for (int d = 0; d < 200; d++) {
            LocalDate day = LocalDate.of(2026, 1, 1).plusDays(d);
            List<Interest> order = QuestInterests.dayOrder(42, day, list);
            assertThat(QuestInterests.dayOrder(42, day, list)).isEqualTo(order);
            assertThat(order).containsExactlyInAnyOrderElementsOf(list);
            List<Interest> rest = order.subList(1, order.size());
            assertThat(rest).as("the others keep the strongest-first order")
                    .isEqualTo(list.stream().filter(i -> i != order.get(0)).toList());
        }
    }

    @Test
    void theFirstInterestOfADayIsDrawnByWeight() {
        List<Interest> list = List.of(interest("A", 8), interest("B", 1), interest("C", 1));
        Map<String, Integer> first = new HashMap<>();
        int days = 4000;
        for (long user = 1; user <= 4; user++) {
            for (int d = 0; d < days / 4; d++) {
                LocalDate day = LocalDate.of(2027, 1, 1).plusDays(d);
                first.merge(QuestInterests.dayOrder(user, day, list).get(0).name(), 1, Integer::sum);
            }
        }
        assertThat(first.get("A") / (double) days).as("8 of 10").isBetween(0.75, 0.85);
        assertThat(first.get("B") / (double) days).as("1 of 10").isBetween(0.07, 0.13);
        assertThat(first.get("C") / (double) days).as("1 of 10").isBetween(0.07, 0.13);
    }

    @Test
    void aWeightOfZeroStillGetsAChance() {
        List<Interest> list = List.of(interest("A", 0), interest("B", 0));
        int a = 0;
        for (int d = 0; d < 400; d++) {
            if (QuestInterests.dayOrder(3, LocalDate.of(2027, 1, 1).plusDays(d), list).get(0).name().equals("A")) {
                a++;
            }
        }
        assertThat(a).isBetween(120, 280);
    }

    /**
     * The shuffled order of the rotating codes moves from day to day for every Member: before the seed was mixed,
     * 491 Members out of 500 never saw one of the four codes in the first two places over 30 days.
     */
    @Test
    void theDaysOrderOfTheCodesMovesFromDayToDayForEveryMember() {
        List<String> codes = List.of("LEARN_NEW", "REVIEW_LEARNING", "TOPIC", "PHRASE_OF_DAY");
        for (long user = 1; user <= 500; user++) {
            Map<String, Integer> firstTwo = new HashMap<>();
            for (int d = 0; d < 30; d++) {
                List<String> order = QuestGenerator.shuffled(user, LocalDate.of(2027, 1, 1).plusDays(d), codes);
                order.subList(0, 2).forEach(c -> firstTwo.merge(c, 1, Integer::sum));
            }
            assertThat(firstTwo.keySet()).as("Member " + user).containsExactlyInAnyOrderElementsOf(codes);
        }
    }

    @Test
    void groupsAreParsedStrictly() {
        assertThat(QuestGroup.parse("topic:pay")).contains(new QuestGroup("topic", "pay"));
        assertThat(QuestGroup.parse(" Category : station ")).contains(new QuestGroup("category", "station"));
        for (String bad : new String[] {null, "", "pay", "topic:", ":pay", "place:park", "topic: "}) {
            assertThat(QuestGroup.parse(bad)).as(String.valueOf(bad)).isEmpty();
        }
        assertThat(new QuestGroup("topic", "pay").noun()).isEqualTo("chủ đề");
        assertThat(new QuestGroup("category", "station").noun()).isEqualTo("địa điểm");
    }

    @Test
    void aQuestRowNamesItsGroupNewOrOld() {
        assertThat(QuestGroup.of(Map.of("by", "category", "group", "station", "groupName", "Ga tàu")))
                .contains(new QuestGroup("category", "station"));
        assertThat(QuestGroup.nameOf(Map.of("by", "category", "group", "station", "groupName", "Ga tàu")))
                .isEqualTo("Ga tàu");
        assertThat(QuestGroup.of(Map.of("topicId", "pay", "topicName", "Thanh toán")))
                .as("a TOPIC row made before groups").contains(new QuestGroup("topic", "pay"));
        assertThat(QuestGroup.nameOf(Map.of("topicId", "pay", "topicName", "Thanh toán"))).isEqualTo("Thanh toán");
        assertThat(QuestGroup.of(Map.of("status", "new"))).isEqualTo(Optional.empty());
        assertThat(QuestGroup.of(Map.of("by", "", "group", ""))).isEmpty();
    }

    @Test
    void theMappingIsReadByFoldedNameAndByCode() {
        QuestSettings.Interest interest = new QuestSettings.Interest();
        interest.getBlogTopics().put("yte", List.of("topic:health", " category:hospital ", ""));
        interest.getBlogTopics().put("Ngân hàng và tiền", List.of("topic:pay"));
        interest.getGuideTopics().put("HEALTH", List.of("topic:health"));
        assertThat(interest.groupsOfBlogTopic("Y tế")).containsExactly("topic:health", "category:hospital");
        assertThat(interest.groupsOfBlogTopic("Y TẾ")).containsExactly("topic:health", "category:hospital");
        assertThat(interest.groupsOfBlogTopic("nganhangvatien")).containsExactly("topic:pay");
        assertThat(interest.groupsOfBlogTopic("Y")).isEmpty();
        assertThat(interest.groupsOfBlogTopic(null)).isEmpty();
        assertThat(interest.groupsOfGuideTopic("health")).containsExactly("topic:health");
        assertThat(interest.groupsOfGuideTopic("")).isEmpty();
        assertThat(interest.groupsOfGuideTopic(null)).isEmpty();
    }

    @Test
    void reasonsReadFromParams() {
        assertThat(QuestService.reason(Map.of("reason", "INTEREST", "reasonDetail", "Y tế")))
                .isEqualTo(new QuestDtos.Reason("INTEREST", "Chủ đề bạn quan tâm", "Y tế"));
        assertThat(QuestService.reason(Map.of("reason", "STAGE")))
                .isEqualTo(new QuestDtos.Reason("STAGE", "Hợp giai đoạn của bạn", null));
        assertThat(QuestService.reason(Map.of("topicId", "pay"))).isNull();
        assertThat(QuestService.reason(Map.of("reason", "SOMETHING"))).isNull();
        assertThat(QuestService.reason(null)).isNull();
    }
}
