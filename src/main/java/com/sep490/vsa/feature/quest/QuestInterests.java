package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.quest.QuestInterestRepository.TopicWeight;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * What a Member is interested in, for the quests that follow it (decided 21/11/2026: the quests stay Japanese flash
 * cards; what changes is the topic or place of the cards). Read from the Blog and the Cẩm nang:
 * <ul>
 *   <li>blog topics the Member chose in "Chủ đề quan tâm" ({@code app.quests.interest.chosen-weight}, never expire);</li>
 *   <li>in the last {@code app.quests.interest.days} days, by the topics of the posts and questions: like
 *       {@value #LIKE}, save {@value #SAVE}, published comment {@value #COMMENT} (once per post), own published post or
 *       question {@value #OWN} (the weights of "Dành cho bạn", {@code InterestService});</li>
 *   <li>in the same days, by the topic of the guides: save {@value #GUIDE_SAVE}, a checklist task ticked
 *       {@value #GUIDE_TASK} (once per guide), "Đánh dấu đã xong" {@value #GUIDE_DONE}.</li>
 * </ul>
 * A blog topic and a guide topic of the same name ("Y tế", "Đi lại", "Nhà ở") are one interest. An interest counts when
 * the Member chose it or its weight reaches {@code min-weight}, and only when it leads to at least one "Luyện tập" group
 * holding entries ({@code app.quests.interest.blog-topics} / {@code guide-topics}); the {@code top} strongest are kept.
 */
@Component
class QuestInterests {

    static final int LIKE = 1;
    static final int SAVE = 2;
    static final int COMMENT = 2;
    static final int OWN = 3;
    static final int GUIDE_SAVE = 2;
    static final int GUIDE_TASK = 2;
    static final int GUIDE_DONE = 1;

    /** Mixed into the day's seed, so the interest of the day does not move in step with the shuffled quest codes. */
    private static final long FOCUS_SALT = 0x2545F4914F6CDD1DL;

    /**
     * One interest of the Member: its name (as the Blog or the Cẩm nang writes it), its weight, whether the Member chose
     * it, and the "Luyện tập" groups it leads to that hold entries, preferred ones first.
     */
    record Interest(String name, int weight, boolean chosen, List<QuestGroup> groups) {
    }

    private final QuestInterestRepository repository;
    private final QuestRepository quests;
    private final QuestSettings settings;
    private final Clock clock;

    QuestInterests(QuestInterestRepository repository, QuestRepository quests, QuestSettings settings, Clock clock) {
        this.repository = repository;
        this.quests = quests;
        this.settings = settings;
        this.clock = clock;
    }

    /** {@link #of(long, Map)} with the library read now; nothing is read when the interests are off ({@code top} 0). */
    List<Interest> of(long userId) {
        return settings.getInterest().getTop() <= 0 ? List.of() : of(userId, quests.groupEntries());
    }

    /**
     * The Member's interests, strongest first (then the chosen ones, then by name as Vietnamese reads), at most
     * {@code top}; {@code library} = the entries of each "Luyện tập" group. A group counts only when it holds at least
     * the smallest target of the quests that follow groups (TOPIC, LEARN_NEW, REVIEW_LEARNING: 3 by default), so every
     * interest listed can lead a quest; empty when the Member has none.
     */
    List<Interest> of(long userId, Map<QuestGroup, Integer> library) {
        QuestSettings.Interest config = settings.getInterest();
        if (config.getTop() <= 0 || library.isEmpty()) {
            return List.of();
        }
        int minEntries = Math.min(settings.targetOf(QuestRules.TOPIC),
                Math.min(settings.targetOf(QuestRules.LEARN_NEW), settings.targetOf(QuestRules.REVIEW_LEARNING)));
        Map<QuestGroup, Integer> usable = new java.util.HashMap<>();
        library.forEach((group, entries) -> {
            if (entries != null && entries >= minEntries) {
                usable.put(group, entries);
            }
        });
        var since = clock.instant().minus(Duration.ofDays(Math.max(0, config.getDays())));
        Map<String, Builder> byName = new LinkedHashMap<>();
        for (TopicWeight topic : repository.blogTopics(userId, since, config.getChosenWeight())) {
            add(byName, topic, config.groupsOfBlogTopic(topic.key()), usable);
        }
        for (TopicWeight topic : repository.guideTopics(userId, since)) {
            add(byName, topic, config.groupsOfGuideTopic(topic.key()), usable);
        }
        return byName.values().stream()
                .filter(b -> b.chosen || b.weight >= config.getMinWeight())
                .filter(b -> !b.groups.isEmpty())
                .map(b -> new Interest(b.name, b.weight, b.chosen, List.copyOf(b.groups)))
                .sorted(Comparator.comparingInt(Interest::weight).reversed()
                        .thenComparing(Interest::chosen, Comparator.reverseOrder())
                        .thenComparing(i -> VietnameseText.fold(i.name()))
                        .thenComparing(Interest::name))
                .limit(config.getTop())
                .toList();
    }

    /**
     * The interests in the order a day follows them: the first one drawn at random, weighted by the interests'
     * weights, with a {@link Random} seeded by (Member, day) (the same however often the day is made); the others
     * after it, strongest first. So a Member with several interests meets each of them over the days, the strongest
     * most often.
     */
    static List<Interest> dayOrder(long userId, LocalDate day, List<Interest> interests) {
        if (interests.size() < 2) {
            return interests;
        }
        long total = interests.stream().mapToLong(i -> Math.max(1, i.weight())).sum();
        long draw = Math.floorMod(new Random(QuestGenerator.seed(userId, day) ^ FOCUS_SALT).nextLong(), total);
        int first = 0;
        for (long seen = 0; first < interests.size(); first++) {
            seen += Math.max(1, interests.get(first).weight());
            if (draw < seen) {
                break;
            }
        }
        List<Interest> ordered = new ArrayList<>(interests.size());
        ordered.add(interests.get(first));
        for (int i = 0; i < interests.size(); i++) {
            if (i != first) {
                ordered.add(interests.get(i));
            }
        }
        return ordered;
    }

    private static void add(Map<String, Builder> byName, TopicWeight topic, List<String> groups,
                            Map<QuestGroup, Integer> library) {
        String name = topic.name() == null ? "" : topic.name().strip();
        if (name.isEmpty()) {
            return;
        }
        Builder builder = byName.computeIfAbsent(VietnameseText.fold(name), k -> new Builder(name));
        builder.weight += Math.max(0, topic.weight());
        builder.chosen |= topic.chosen();
        for (String text : groups) {
            Optional<QuestGroup> group = QuestGroup.parse(text);
            if (group.isPresent() && library.containsKey(group.get())) {
                builder.groups.add(group.get());
            }
        }
    }

    /** An interest being summed up from the blog and guide topics of its name. */
    private static final class Builder {
        final String name;
        final Set<QuestGroup> groups = new LinkedHashSet<>();
        int weight;
        boolean chosen;

        Builder(String name) {
            this.name = name;
        }
    }
}
