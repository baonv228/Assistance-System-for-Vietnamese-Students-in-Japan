package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.exception.ApiException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Makes a Member's quests of a Japan day (contract 3.2), on the day's first need (GET today, the first flash card
 * answer or the first event), with the Member's {@code users} row already locked FOR UPDATE by the caller, so two
 * first requests at the same moment make the day once: the second finds the rows of the first when it reads again.
 * Once made, the rows never change that day, whatever happens to the settings or the library.
 * <ul>
 *   <li>The fixed quest ({@code app.quests.fixed}) first, with a target of at most the library's size.</li>
 *   <li>Then {@code app.quests.rotating-per-day} of the rotating codes, in an order shuffled by a {@link Random}
 *       seeded with (Member, day), so the choice is the same however often it is made. Only quests the Member can do
 *       are given: first those possible at their full target; when that is not enough, a quest with fewer eligible
 *       entries gets its target lowered to them (at least 1) before a code is left out. A day may therefore hold fewer
 *       quests when the library is tiny.</li>
 *   <li>"Ưu tiên khác bộ của hôm qua": when the choice is exactly yesterday's set of rotating quests, its last quest
 *       gives way to the next code of the day's order that is possible at its full target, if there is one. A code of
 *       yesterday may come back next to a new one, so every pair of quests turns up over the days (avoiding every code
 *       of yesterday would leave a Member who plays daily with the same two pairs forever).</li>
 * </ul>
 */
@Component
class QuestGenerator {

    private final QuestRepository repository;
    private final QuestSettings settings;
    private final Map<String, QuestRule> rules;
    private final Clock clock;

    QuestGenerator(QuestRepository repository, QuestSettings settings, List<QuestRule> rules, Clock clock) {
        this.repository = repository;
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
     * cannot do now. Targets are lowered as in {@link #ensure}. The caller holds the users row lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<QuestRow> force(long userId, LocalDate day, List<String> codes) {
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
        List<Pick> picks = new ArrayList<>();
        for (String code : ordered) {
            Plan plan = plan(code, userId, day, new HashMap<>());
            if (plan.eligible() < 1) {
                String message = "Nhiệm vụ " + code + " không làm được với dữ liệu hiện tại.";
                throw ApiException.invalid(message, Map.of("codes", message));
            }
            picks.add(pick(code, plan));
        }
        insert(userId, day, picks);
        return repository.quests(userId, day);
    }

    // ---------------------------------------------------------------- choice

    private record Pick(QuestRule rule, int target, Map<String, Object> params) {
    }

    private List<Pick> choose(long userId, LocalDate day) {
        Map<String, Plan> plans = new HashMap<>();
        List<Pick> picks = new ArrayList<>();
        String fixed = settings.getFixed() == null ? "" : settings.getFixed().strip();
        if (rules.containsKey(fixed)) {
            Plan plan = plan(fixed, userId, day, plans);
            if (plan.eligible() >= 1) {
                picks.add(pick(fixed, plan));
            }
        }
        List<String> rotating = settings.getRotating() == null ? List.of() : settings.getRotating().stream()
                .map(String::strip).filter(rules::containsKey).filter(c -> !c.equals(fixed)).distinct().toList();
        List<String> order = shuffled(userId, day, rotating);
        int wanted = Math.max(0, settings.getRotatingPerDay());
        List<String> chosen = new ArrayList<>();
        for (String code : order) {                                     // possible at the full target
            if (chosen.size() >= wanted) {
                break;
            }
            if (plan(code, userId, day, plans).eligible() >= settings.targetOf(code)) {
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
        avoidYesterdaysSet(userId, day, fixed, order, chosen, plans);
        order.stream().filter(chosen::contains).forEach(code -> picks.add(pick(code, plans.get(code))));
        return picks;
    }

    /**
     * When the rotating choice is exactly yesterday's set, the last chosen code gives way to the first other code of
     * the day's order that is possible at its full target; with no such code the set stays (a tiny library).
     */
    private void avoidYesterdaysSet(long userId, LocalDate day, String fixed, List<String> order, List<String> chosen,
                                    Map<String, Plan> plans) {
        Set<String> yesterday = new HashSet<>(repository.codesOn(userId, day.minusDays(1)));
        yesterday.remove(fixed);
        if (chosen.isEmpty() || !yesterday.equals(new HashSet<>(chosen))) {
            return;
        }
        for (String code : order) {
            if (!chosen.contains(code) && plan(code, userId, day, plans).eligible() >= settings.targetOf(code)) {
                chosen.set(chosen.size() - 1, code);
                return;
            }
        }
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
