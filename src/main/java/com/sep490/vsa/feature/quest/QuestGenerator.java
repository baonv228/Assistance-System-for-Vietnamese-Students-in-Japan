package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.quest.QuestInterests.Interest;
import com.sep490.vsa.feature.quest.QuestRepository.GroupStat;
import com.sep490.vsa.feature.quest.QuestRepository.QuestRow;
import com.sep490.vsa.feature.quest.QuestRule.Plan;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Makes a Member's quests of a Japan day (contract 3.2), on the day's first need (GET today, the first flash card
 * answer or the first event), with the Member's {@code users} row already locked FOR UPDATE by the caller, so two
 * first requests at the same moment make the day once: the second finds the rows of the first when it reads again.
 * Once made, the rows never change that day, whatever happens to the settings, the library or the Member's interests.
 * <ul>
 *   <li>The fixed quest ({@code app.quests.fixed}) first, with a target of at most the library's size.</li>
 *   <li>Then {@code app.quests.rotating-per-day} of the rotating codes, in an order shuffled by a {@link Random}
 *       seeded with (Member, day), so the choice is the same however often it is made. Only quests the Member can do
 *       are given: first those possible at their full target; when that is not enough, a quest with fewer eligible
 *       entries gets its target lowered to them (at least 1) before a code is left out. A day may therefore hold fewer
 *       quests when the library is tiny.</li>
 *   <li><b>Interests</b> (QUEST_INTERESTS.md, 21/11/2026): a code that {@linkplain QuestRule#followsGroups follows
 *       groups} (TOPIC, LEARN_NEW, REVIEW_LEARNING) "follows an interest" when a "Luyện tập" group of one of the
 *       Member's interests ({@link QuestInterests}) holds enough entries for its full target; such a code is possible
 *       at its full target. When fewer than {@code app.quests.interest.min-per-day} chosen codes follow an interest,
 *       a code of the day's order that does replaces the last chosen one that does not.</li>
 *   <li>"Ưu tiên khác bộ của hôm qua": when the choice is exactly yesterday's set of rotating quests, its last quest
 *       gives way to the next code of the day's order that is possible at its full target, if there is one (a quest
 *       that follows an interest only to another one when it is needed for {@code min-per-day}). A code of yesterday
 *       may come back next to a new one, so every pair of quests turns up over the days.</li>
 *   <li>Then each chosen code that can follow an interest gets its group: TOPIC first, then the others in the day's
 *       order; the interests in the day's order ({@link QuestInterests#dayOrder}), one no other quest of the day
 *       follows first, and within an interest its first group holding enough entries. The quest keeps its group and
 *       its reason (INTEREST and the interest's name) in its params. The other codes are made as before interests
 *       existed (TOPIC by the stage, reason STAGE, else by the topic with the most entries to learn).</li>
 * </ul>
 */
@Component
class QuestGenerator {

    /** Params keys of a quest's reason ({@code quests[].reason}) and its codes. */
    static final String REASON = "reason";
    static final String REASON_DETAIL = "reasonDetail";
    static final String REASON_INTEREST = "INTEREST";
    static final String REASON_STAGE = "STAGE";

    private final QuestRepository repository;
    private final QuestInterests interests;
    private final QuestSettings settings;
    private final Map<String, QuestRule> rules;
    private final Clock clock;

    QuestGenerator(QuestRepository repository, QuestInterests interests, QuestSettings settings,
                   List<QuestRule> rules, Clock clock) {
        this.repository = repository;
        this.interests = interests;
        this.settings = settings;
        this.rules = QuestRules.byCode(rules);
        this.clock = clock;
    }

    /** The day's quests, and whether this call made them. */
    record Day(List<QuestRow> rows, boolean made) {
    }

    /** The day's quests, made now if the day has none yet. The caller holds the users row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    Day ensure(long userId, LocalDate day) {
        List<QuestRow> rows = repository.quests(userId, day);
        if (!rows.isEmpty()) {
            return new Day(rows, false);
        }
        List<Pick> picks = choose(userId, day);
        insert(userId, day, picks);
        return new Day(repository.quests(userId, day), !picks.isEmpty());
    }

    /**
     * The day's quests made from exactly these codes (the fixed one first if listed), for the test-only API and the
     * tests: 409 when the day already has quests, 400 on field {@code codes} for an unknown code or one the Member
     * cannot do now. Targets are lowered as in {@link #ensure}, and codes that can follow an interest do. The caller
     * holds the users row lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<QuestRow> force(long userId, LocalDate day, List<String> codes) {
        return force(userId, day, codes, false);
    }

    /**
     * {@link #force(long, LocalDate, List)}, made as for a Member without interests (the contract's "bỏ qua random",
     * the default) or, with {@code followInterests}, following the Member's interests strongest first (no draw of the
     * day, so a test gets the same set every day).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<QuestRow> force(long userId, LocalDate day, List<String> codes, boolean followInterests) {
        if (!repository.quests(userId, day).isEmpty()) {
            throw ApiException.conflict("Hôm nay đã có nhiệm vụ.");
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String code : codes == null ? List.<String>of() : codes) {
            String c = code == null ? "" : code.strip();
            if (!rules.containsKey(c)) {
                String message = "Mã nhiệm vụ không hợp lệ: " + c + ".";
                throw ApiException.invalid(message, Map.of("codes", message));
            }
            wanted.add(c);
        }
        if (wanted.isEmpty()) {
            String message = "Hãy chọn ít nhất một mã nhiệm vụ.";
            throw ApiException.invalid(message, Map.of("codes", message));
        }
        List<String> ordered = new ArrayList<>();
        if (wanted.remove(settings.getFixed())) {
            ordered.add(settings.getFixed());
        }
        ordered.addAll(wanted);
        Focus focus = followInterests ? focus(userId, day, false) : Focus.NONE;
        Map<String, Plan> plans = new HashMap<>();
        for (String code : ordered) {
            if (plan(code, userId, day, plans).eligible() < 1 && !focus.follows(rules.get(code), target(code))) {
                String message = "Nhiệm vụ " + code + " không làm được với dữ liệu hiện tại.";
                throw ApiException.invalid(message, Map.of("codes", message));
            }
        }
        insert(userId, day, assign(userId, day, ordered, plans, focus));
        return repository.quests(userId, day);
    }

    // ---------------------------------------------------------------- choice

    private record Pick(QuestRule rule, int target, Map<String, Object> params) {
    }

    /**
     * The Member's interests in the day's order, with the library's groups; NONE when the Member has no interest the
     * library can follow (then every code is made as before interests existed).
     */
    private record Focus(List<Interest> interests, Map<QuestGroup, GroupStat> library) {

        static final Focus NONE = new Focus(List.of(), Map.of());

        /**
         * The interest and group the rule follows at this target: the interests in order, those not in {@code used}
         * first, and within one its first group holding enough entries; null when there is none.
         */
        Choice choice(QuestRule rule, int target, Set<String> used) {
            if (rule == null || !rule.followsGroups()) {
                return null;
            }
            for (boolean fresh : new boolean[] {true, false}) {
                for (Interest interest : interests) {
                    if (fresh == used.contains(interest.name())) {
                        continue;
                    }
                    for (QuestGroup group : interest.groups()) {
                        GroupStat stat = library.get(group);
                        if (stat != null && rule.eligibleIn(stat) >= target) {
                            return new Choice(interest, stat);
                        }
                    }
                }
            }
            return null;
        }

        /** Whether the rule can follow one of the interests at this target. */
        boolean follows(QuestRule rule, int target) {
            return choice(rule, target, Set.of()) != null;
        }
    }

    private record Choice(Interest interest, GroupStat group) {
    }

    /** The Member's interests with the library; {@code drawn}: in the day's order, else strongest first. */
    private Focus focus(long userId, LocalDate day, boolean drawn) {
        if (settings.getInterest().getTop() <= 0) {
            return Focus.NONE;
        }
        Map<QuestGroup, GroupStat> library = repository.groupStats(userId);
        Map<QuestGroup, Integer> entries = new HashMap<>();
        library.forEach((group, stat) -> entries.put(group, stat.entries()));
        List<Interest> found = interests.of(userId, entries);
        return found.isEmpty() ? Focus.NONE
                : new Focus(drawn ? QuestInterests.dayOrder(userId, day, found) : found, library);
    }

    private List<Pick> choose(long userId, LocalDate day) {
        Map<String, Plan> plans = new HashMap<>();
        List<String> codes = new ArrayList<>();
        String fixed = settings.getFixed() == null ? "" : settings.getFixed().strip();
        if (rules.containsKey(fixed) && plan(fixed, userId, day, plans).eligible() >= 1) {
            codes.add(fixed);
        }
        List<String> rotating = settings.getRotating() == null ? List.of() : settings.getRotating().stream()
                .map(String::strip).filter(rules::containsKey).filter(c -> !c.equals(fixed)).distinct().toList();
        List<String> order = shuffled(userId, day, rotating);
        Focus focus = order.stream().anyMatch(c -> rules.get(c).followsGroups()) ? focus(userId, day, true)
                : Focus.NONE;
        Map<String, Boolean> follows = new HashMap<>();
        Predicate<String> followsInterest = code -> follows.computeIfAbsent(code,
                c -> focus.follows(rules.get(c), target(c)));
        Predicate<String> possible = code -> plan(code, userId, day, plans).eligible() >= target(code)
                || followsInterest.test(code);
        int wanted = Math.max(0, settings.getRotatingPerDay());
        List<String> chosen = new ArrayList<>();
        for (String code : order) {                                     // possible at the full target
            if (chosen.size() >= wanted) {
                break;
            }
            if (possible.test(code)) {
                chosen.add(code);
            }
        }
        for (String code : order) {                                     // else with a lowered target
            if (chosen.size() >= wanted) {
                break;
            }
            if (!chosen.contains(code) && plan(code, userId, day, plans).eligible() >= 1) {
                chosen.add(code);
            }
        }
        int need = Math.min(Math.max(0, settings.getInterest().getMinPerDay()), chosen.size());
        followInterests(order, chosen, need, followsInterest);
        avoidYesterdaysSet(userId, day, fixed, order, chosen, need, possible, followsInterest);
        order.stream().filter(chosen::contains).forEach(codes::add);
        return assign(userId, day, codes, plans, focus);
    }

    /**
     * At least {@code need} chosen codes follow an interest when the day's order holds enough such codes: each one not
     * chosen replaces the last chosen code that does not follow an interest.
     */
    private static void followInterests(List<String> order, List<String> chosen, int need,
                                        Predicate<String> followsInterest) {
        long have = chosen.stream().filter(followsInterest).count();
        for (String code : order) {
            if (have >= need) {
                return;
            }
            if (chosen.contains(code) || !followsInterest.test(code)) {
                continue;
            }
            int replaced = -1;
            for (int i = chosen.size() - 1; i >= 0 && replaced < 0; i--) {
                if (!followsInterest.test(chosen.get(i))) {
                    replaced = i;
                }
            }
            if (replaced < 0) {
                return;
            }
            chosen.set(replaced, code);
            have++;
        }
    }

    /**
     * When the rotating choice is exactly yesterday's set, a chosen code gives way to the first other code of the
     * day's order that is possible at its full target: the last chosen code, or an earlier one when the last is a
     * quest that follows an interest, is needed for {@code min-per-day} and only codes that do not follow one could
     * take its place. With no such code the set stays (a tiny library).
     */
    private void avoidYesterdaysSet(long userId, LocalDate day, String fixed, List<String> order, List<String> chosen,
                                    int need, Predicate<String> possible, Predicate<String> followsInterest) {
        Set<String> yesterday = new HashSet<>(repository.codesOn(userId, day.minusDays(1)));
        yesterday.remove(fixed);
        if (chosen.isEmpty() || !yesterday.equals(new HashSet<>(chosen))) {
            return;
        }
        long have = chosen.stream().filter(followsInterest).count();
        for (int at = chosen.size() - 1; at >= 0; at--) {
            boolean anyCode = !followsInterest.test(chosen.get(at)) || have - 1 >= need;
            for (String code : order) {
                if (!chosen.contains(code) && possible.test(code) && (anyCode || followsInterest.test(code))) {
                    chosen.set(at, code);
                    return;
                }
            }
        }
    }

    /**
     * The picks of these codes, in this order: each code that can follow an interest gets its group (TOPIC first,
     * then the others in order; an interest no quest of the day follows yet first), the others their usual plan.
     */
    private List<Pick> assign(long userId, LocalDate day, List<String> codes, Map<String, Plan> plans, Focus focus) {
        Map<String, Pick> picks = new HashMap<>();
        Set<String> used = new HashSet<>();
        List<String> turn = new ArrayList<>(codes);
        if (turn.remove(QuestRules.TOPIC)) {
            turn.add(0, QuestRules.TOPIC);
        }
        for (String code : turn) {
            QuestRule rule = rules.get(code);
            int target = target(code);
            Choice choice = focus.choice(rule, target, used);
            if (choice != null) {
                used.add(choice.interest().name());
                Map<String, Object> params = new LinkedHashMap<>(rule.paramsIn(choice.group(), target));
                params.put(REASON, REASON_INTEREST);
                params.put(REASON_DETAIL, choice.interest().name());
                picks.put(code, new Pick(rule, target, params));
            } else {
                picks.put(code, pick(code, plan(code, userId, day, plans)));
            }
        }
        return codes.stream().map(picks::get).toList();
    }

    /** The rotating codes in the order they are offered on that day: shuffled by a {@link Random} seeded with (Member, day). */
    static List<String> shuffled(long userId, LocalDate day, List<String> codes) {
        List<String> shuffled = new ArrayList<>(codes);
        Collections.shuffle(shuffled, new Random(seed(userId, day)));
        return shuffled;
    }

    /**
     * The seed of a (Member, day), mixed by SplitMix64's finalizer. {@link Random} alone keeps the first draws of
     * nearby seeds alike, and the seeds of two days in a row differ only in their last bits: the shuffled order then
     * hardly moved from day to day (for 491 Members out of 500, one code never came in the first two places in 30
     * days; found 21/11/2026). Mixed, every code turns up.
     */
    static long seed(long userId, LocalDate day) {
        long z = userId * 0x9E3779B97F4A7C15L ^ day.toEpochDay();
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private int target(String code) {
        return settings.targetOf(code);
    }

    private Plan plan(String code, long userId, LocalDate day, Map<String, Plan> cache) {
        return cache.computeIfAbsent(code, c -> rules.get(c).plan(userId, day, settings.targetOf(c)));
    }

    private Pick pick(String code, Plan plan) {
        return new Pick(rules.get(code), Math.max(1, Math.min(settings.targetOf(code), plan.eligible())),
                plan.params());
    }

    private void insert(long userId, LocalDate day, List<Pick> picks) {
        Instant now = clock.instant();
        int sort = 0;
        for (Pick pick : picks) {
            repository.insertQuest(userId, day, pick.rule().code(), sort++, pick.params(),
                    pick.rule().title(pick.target(), pick.params()), pick.target(), now);
        }
    }
}
