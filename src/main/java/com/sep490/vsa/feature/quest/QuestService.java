package com.sep490.vsa.feature.quest;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.coin.CoinDtos.Wallet;
import com.sep490.vsa.feature.coin.CoinLedger;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.quest.QuestDtos.Action;
import com.sep490.vsa.feature.quest.QuestDtos.Bonus;
import com.sep490.vsa.feature.quest.QuestDtos.ChangedQuest;
import com.sep490.vsa.feature.quest.QuestDtos.CompletedQuest;
import com.sep490.vsa.feature.quest.QuestDtos.Day;
import com.sep490.vsa.feature.quest.QuestDtos.EventRequest;
import com.sep490.vsa.feature.quest.QuestDtos.HomeQuests;
import com.sep490.vsa.feature.quest.QuestDtos.PhraseRef;
import com.sep490.vsa.feature.quest.QuestDtos.InterestRef;
import com.sep490.vsa.feature.quest.QuestDtos.Quest;
import com.sep490.vsa.feature.quest.QuestDtos.QuestChange;
import com.sep490.vsa.feature.quest.QuestDtos.Reason;
import com.sep490.vsa.feature.quest.QuestDtos.Streak;
import com.sep490.vsa.feature.quest.QuestDtos.StreakBrief;
import com.sep490.vsa.feature.quest.QuestDtos.StreakMilestone;
import com.sep490.vsa.feature.quest.QuestDtos.Today;
import com.sep490.vsa.feature.quest.QuestRepository.DoneDay;
import com.sep490.vsa.feature.quest.QuestRepository.QuestRow;
import com.sep490.vsa.feature.quest.QuestRule.Answer;
import com.sep490.vsa.feature.quest.QuestRule.Event;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * "Nhiệm vụ hằng ngày" of "Nhật ngữ" (contract WEB_DAILY_QUESTS_PROMPT.md, sections 3 and 5): every Japan day an active
 * Member (role USER) gets a few small study quests, made on first need ({@link QuestGenerator}); flash card answers
 * ({@code POST /api/phrases/{id}/flash}, through {@link #recordFlash}) and events ({@code LISTEN} of the phrase of the
 * day) move them; a completed quest pays xu at once ({@link CoinLedger}, never rank points), and so does a day with
 * every quest done (bonus, a streak milestone every {@code streak-every} days).
 * <ul>
 *   <li><b>Counting</b>: each entry once per quest (key of {@code user_quest_items}); an answer given less than
 *       {@code min-answer-interval-ms} after the Member's previous one is kept as a mark but counts for nothing
 *       ({@code phrase_answer_log}); "Bỏ đánh dấu" never changes a quest; progress never passes the target.</li>
 *   <li><b>Xu</b>: a quest pays {@code coins-per-quest}, the day {@code bonus-coins}, together at most
 *       {@code daily-cap} a Japan day (what passes the cap is not paid, the quest is still completed with what it
 *       actually gave); the milestone is outside the cap. Each quest, bonus and milestone pays once (unique key of
 *       {@code coin_transactions}).</li>
 *   <li><b>Locks</b>: every write locks the Member's {@code users} row first, then the quest rows (READ COMMITTED), like
 *       "Chủ đề quan tâm" and "Xóa tài khoản"; two answers, two devices or a double tap run one after the other.
 *       {@code GET today} only locks while the day has no quests yet; the Home only reads.</li>
 *   <li>After every change the Member's open pages and apps are told ({@code quest} on the live stream).</li>
 * </ul>
 */
@Service
public class QuestService {

    static final String BAD_TYPE = "Loại sự kiện không hợp lệ.";
    static final String NO_PHRASE = "Hãy gửi mã câu đã nghe.";
    static final String TOO_FAST = "Bạn thao tác quá nhanh. Vui lòng thử lại sau ít phút.";
    static final String STREAK_NOTIFICATION = "QUEST_STREAK";
    /** Labels of {@code quests[].reason}, the same words as the reasons of "Dành cho bạn". */
    static final String REASON_INTEREST = "Chủ đề bạn quan tâm";
    static final String REASON_STAGE = "Hợp giai đoạn của bạn";
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    private final QuestRepository repository;
    private final QuestGenerator generator;
    private final QuestInterests interests;
    private final Map<String, QuestRule> rules;
    private final QuestSettings settings;
    private final CoinLedger coins;
    private final MemberGuard guard;
    private final NotificationService notifications;
    private final LiveEvents live;
    private final Clock clock;
    /** Recent events per Member (clock millis), for {@code events-per-minute}; in memory like the Map's search limit. */
    private final Map<Long, Deque<Long>> recentEvents = new ConcurrentHashMap<>();

    public QuestService(QuestRepository repository, QuestGenerator generator, QuestInterests interests,
                        List<QuestRule> rules, QuestSettings settings, CoinLedger coins, MemberGuard guard,
                        NotificationService notifications, LiveEvents live, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.interests = interests;
        this.rules = QuestRules.byCode(rules);
        this.settings = settings;
        this.coins = coins;
        this.guard = guard;
        this.notifications = notifications;
        this.live = live;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- GET /api/me/quests/today

    /**
     * The Member's quests of today (contract 5.1). Reads only when the day is made already; otherwise locks the users
     * row, reads again and makes it.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Today today(AppUserPrincipal me) {
        long userId = guard.member(me);
        LocalDate day = dayOf(clock.instant());
        List<QuestRow> rows = repository.quests(userId, day);
        if (rows.isEmpty()) {
            guard.lockUserRow(userId);
            QuestGenerator.Day made = generator.ensure(userId, day);
            rows = made.rows();
            if (made.made()) {
                changed(userId);
            }
        }
        StreakState streak = streak(userId, day);
        Bonus bonus = streak.todayDone()
                ? new Bonus(repository.bonusReceived(userId, day), true)
                : new Bonus(settings.getBonusCoins(), false);
        List<Day> recent = new ArrayList<>();
        for (int back = 6; back >= 0; back--) {
            LocalDate date = day.minusDays(back);
            recent.add(new Day(date, streak.done().containsKey(date)));
        }
        List<InterestRef> interestRefs = interests.of(userId).stream()
                .map(i -> new InterestRef(i.name(), i.chosen())).toList();
        return new Today(day, resetAt(day), rows.stream().map(row -> quest(userId, row)).toList(), bonus,
                new Streak(streak.current(), repository.bestStreak(userId), streak.todayDone(),
                        settings.getStreakEvery(), settings.getStreakCoins()),
                recent, coins.questCoinsOn(userId, day), settings.getDailyCap(), coins.wallet(userId), interestRefs);
    }

    // ---------------------------------------------------------------- flash card answers

    /**
     * One flash card answer of a Member, called by {@code PhraseService.flash} after it locked the phrase (FOR SHARE)
     * and the Member's users row (FOR UPDATE), with the entry's marks read before the answer is kept. Makes the day if
     * needed, logs the answer, and counts it unless it came too soon after the previous one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public QuestChange recordFlash(long userId, long phraseId, boolean learned, boolean wasLearning,
                                   boolean wasLearned) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDate day = dayOf(now);
        QuestGenerator.Day made = generator.ensure(userId, day);
        Instant previous = repository.lastAnswerAt(userId);
        repository.logAnswer(userId, phraseId, learned, now);
        boolean spaced = previous == null || !now.isBefore(previous.plusMillis(settings.getMinAnswerIntervalMs()));
        Answer answer = new Answer(userId, phraseId, learned, wasLearning, wasLearned);
        // completed_at / counted_at are DATETIME in seconds: cut, never rounded by MySQL into the next second (or day)
        Counted counted = spaced
                ? count(userId, day, made.rows(), now.truncatedTo(ChronoUnit.SECONDS),
                        (rule, row) -> rule.countsAnswer(answer, row.params()), phraseId)
                : Counted.NOTHING;
        if (made.made() || !counted.changed().isEmpty()) {
            changed(userId);
        }
        return change(userId, day, counted);
    }

    // ---------------------------------------------------------------- POST /api/me/quests/events

    /**
     * An event the server cannot see by itself (contract 5.3): {@code LISTEN} with the phrase listened to. The phrase
     * of the day completes PHRASE_OF_DAY; another phrase, a quest done already or a day without that quest change
     * nothing (200). At most {@code events-per-minute} calls a minute per Member (429).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuestChange event(AppUserPrincipal me, EventRequest request) {
        long userId = guard.member(me);
        if (!allowEvent(userId)) {
            throw ApiException.tooManyRequests(TOO_FAST);
        }
        String type = request == null ? null : request.type();
        Long phraseId = request == null ? null : request.phraseId();
        Map<String, String> errors = new LinkedHashMap<>();
        if (!QuestRules.LISTEN.equals(type)) {
            errors.put("type", BAD_TYPE);
        }
        if (phraseId == null) {
            errors.put("phraseId", NO_PHRASE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
        // The phrase first (FOR SHARE), then the users row: the order of a flash answer and of a report of that phrase
        // (phrase FOR UPDATE, then a reports row pointing at the users row), so "Nghe" and "Báo lỗi" on today's phrase
        // at the same moment wait for each other instead of deadlocking
        repository.sharePhrase(phraseId);
        guard.lockMember(me);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate day = dayOf(now);
        QuestGenerator.Day made = generator.ensure(userId, day);
        Event event = new Event(type, phraseId);
        Counted counted = count(userId, day, made.rows(), now, (rule, row) -> rule.countsEvent(event, row.params()),
                phraseId);
        if (made.made() || !counted.changed().isEmpty()) {
            changed(userId);
        }
        return change(userId, day, counted);
    }

    /**
     * What a message adds for the quests just completed: " Xong nhiệm vụ “…” · +10 xu." for each (the app reads the
     * data, not this text). A title quoting a topic already ("Ôn 5 thẻ chủ đề “Thanh toán”") follows a colon instead,
     * so the quotes never nest.
     */
    public static String completionNote(QuestChange change) {
        if (change == null) {
            return "";
        }
        return change.justCompleted().stream()
                .map(q -> " Xong nhiệm vụ" + quoted(q.title()) + " · +" + q.coins() + " xu.")
                .collect(Collectors.joining());
    }

    /** " “title”", or ": title" when the title holds quotes of its own. */
    static String quoted(String title) {
        return title.contains("“") ? ": " + title : " “" + title + "”";
    }

    // ---------------------------------------------------------------- the member Home

    /**
     * The "quests" block of the member Home (contract 5.6). Read only: never makes the day's quests; before they are
     * made, {@code generated} is false, {@code done} 0 and {@code total} the configured number.
     */
    @Transactional(readOnly = true)
    public HomeQuests homeSummary(long userId) {
        LocalDate day = dayOf(clock.instant());
        List<QuestRow> rows = repository.quests(userId, day);
        StreakState streak = streak(userId, day);
        Wallet wallet = coins.wallet(userId);
        if (rows.isEmpty()) {
            return new HomeQuests(false, 0, settings.questsPerDay(), false, streak.current(), streak.todayDone(), 0,
                    settings.getDailyCap(), wallet.coins(), wallet.exchangeableNow());
        }
        int done = (int) rows.stream().filter(QuestRow::completed).count();
        return new HomeQuests(true, done, rows.size(), streak.todayDone(), streak.current(), streak.todayDone(),
                coins.questCoinsOn(userId, day), settings.getDailyCap(), wallet.coins(), wallet.exchangeableNow());
    }

    // ---------------------------------------------------------------- the test-only API and tests

    /**
     * Makes today's quests from exactly these codes, ignoring the random choice (contract 5.9, {@code PUT
     * /api/dev/quests/today}, after the caller cleared the day): 409 when today has quests, 400 on {@code codes} for an
     * unknown code or one the Member cannot do now.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Today forceToday(AppUserPrincipal me, List<String> codes) {
        return forceToday(me, codes, false);
    }

    /**
     * {@link #forceToday(AppUserPrincipal, List)}: as for a Member without interests (the default, the contract's
     * "bỏ qua random"), or with {@code followInterests} following the Member's interests strongest first.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Today forceToday(AppUserPrincipal me, List<String> codes, boolean followInterests) {
        long userId = guard.lockMember(me);
        generator.force(userId, dayOf(clock.instant()), codes, followInterests);
        changed(userId);
        return today(me);
    }

    // ---------------------------------------------------------------- counting

    private interface Counts {
        boolean test(QuestRule rule, QuestRow row);
    }

    private record Counted(List<ChangedQuest> changed, List<CompletedQuest> justCompleted, boolean bonusJustCompleted,
                           int bonusCoins, StreakMilestone milestone, int coinsAwarded) {

        static final Counted NOTHING = new Counted(List.of(), List.of(), false, 0, null, 0);
    }

    /**
     * Counts one entry ({@code phraseId}) for every quest of the day not completed yet that takes it, once per quest;
     * pays the quests it completes and, when it completes the day, the bonus and the streak milestone. The users row
     * is locked by the caller.
     */
    private Counted count(long userId, LocalDate day, List<QuestRow> rows, Instant now, Counts counts, long phraseId) {
        List<ChangedQuest> changed = new ArrayList<>();
        List<CompletedQuest> completed = new ArrayList<>();
        int awarded = 0;
        int open = 0;
        for (QuestRow row : rows) {
            QuestRule rule = rules.get(row.code());
            if (row.completed()) {
                continue;
            }
            if (rule == null || !counts.test(rule, row) || !repository.countItem(row.id(), phraseId, now)) {
                open++;
                continue;
            }
            int progress = repository.addProgress(row.id());
            boolean done = progress >= row.target();
            if (done) {
                int paid = Math.min(settings.getCoinsPerQuest(), roomUnderCap(userId, day));
                coins.add(userId, paid, CoinLedger.DAILY_QUEST, row.title(), CoinLedger.REF_QUEST, row.id(), day);
                repository.complete(row.id(), paid, now);
                completed.add(new CompletedQuest(row.id(), row.code(), row.title(), paid));
                awarded += paid;
            } else {
                open++;
            }
            changed.add(new ChangedQuest(row.id(), row.code(), progress, row.target(), done));
        }
        if (completed.isEmpty() || open > 0) {
            return new Counted(changed, completed, false, 0, null, awarded);
        }
        // Every quest of the day is completed now: the day is done
        List<DoneDay> yesterday = repository.doneDays(userId, day.minusDays(1), day.minusDays(1));
        int streak = yesterday.isEmpty() ? 1 : yesterday.get(0).streak() + 1;
        DoneDay today = repository.markDayDone(userId, day, streak, now);
        int bonus = Math.min(settings.getBonusCoins(), roomUnderCap(userId, day));
        Long bonusLine = coins.add(userId, bonus, CoinLedger.DAILY_QUEST_ALL, "Nhiệm vụ ngày " + DAY_MONTH.format(day),
                CoinLedger.REF_QUEST_DAY, today.id(), day);
        int bonusPaid = bonusLine == null ? 0 : bonus;
        awarded += bonusPaid;
        StreakMilestone milestone = null;
        int every = settings.getStreakEvery();
        if (every > 0 && today.streak() % every == 0) {
            int milestoneCoins = settings.getStreakCoins();
            Long line = coins.add(userId, milestoneCoins, CoinLedger.QUEST_STREAK,
                    "Học " + today.streak() + " ngày liên tiếp", CoinLedger.REF_QUEST_DAY, today.id(), day);
            if (line != null) {
                milestone = new StreakMilestone(today.streak(), milestoneCoins);
                awarded += milestoneCoins;
                notifications.send(userId, STREAK_NOTIFICATION, "Bạn đã học " + today.streak()
                        + " ngày liên tiếp. +" + milestoneCoins + " xu.", CoinLedger.REF_QUEST, today.id());
            }
        }
        return new Counted(changed, completed, true, bonusPaid, milestone, awarded);
    }

    /** Xu of quests and bonus still allowed today under the daily cap. */
    private int roomUnderCap(long userId, LocalDate day) {
        return Math.max(0, settings.getDailyCap() - coins.questCoinsOn(userId, day));
    }

    private QuestChange change(long userId, LocalDate day, Counted counted) {
        StreakState streak = streak(userId, day);
        return new QuestChange(counted.changed(), counted.justCompleted(), counted.bonusJustCompleted(),
                counted.bonusCoins(), new StreakBrief(streak.current(), streak.todayDone()), counted.milestone(),
                counted.coinsAwarded(), coins.questCoinsOn(userId, day), settings.getDailyCap(), coins.wallet(userId));
    }

    // ---------------------------------------------------------------- reading

    /** One quest as GET today shows it; the action of a quest still open is worked out now (it may follow the marks). */
    private Quest quest(long userId, QuestRow row) {
        QuestRule rule = rules.get(row.code());
        String description = rule == null ? "" : rule.description(row.target(), row.params());
        Action action = rule == null ? Action.practice("", "", "", "", "")
                : row.completed() ? rule.action(row.target(), row.params())
                : rule.actionNow(userId, row.target(), row.progress(), row.params());
        Long phraseId = rule == null ? null : rule.phraseId(row.params());
        PhraseRef phrase = phraseId == null ? null : repository.phrase(phraseId).orElse(null);
        return new Quest(row.id(), row.code(), row.title(), description, row.target(), row.progress(),
                row.completed() ? row.coins() : settings.getCoinsPerQuest(), row.completed(), row.completedAt(),
                action, phrase, reason(row.params()));
    }

    /**
     * Why the quest is about its group, as kept when the day was made ({@link QuestGenerator#REASON}); null for a
     * quest that is not personal and for rows made before reasons existed.
     */
    static Reason reason(Map<String, Object> params) {
        String code = QuestRule.textParam(params, QuestGenerator.REASON);
        String detail = QuestRule.textParam(params, QuestGenerator.REASON_DETAIL);
        return switch (code) {
            case QuestGenerator.REASON_INTEREST -> new Reason(code, REASON_INTEREST, detail.isEmpty() ? null : detail);
            case QuestGenerator.REASON_STAGE -> new Reason(code, REASON_STAGE, null);
            default -> null;
        };
    }

    /**
     * The streak (contract 3.6): today's when today is done, else yesterday's when yesterday is done (still time to
     * keep it), else 0; {@code done} = the days done of the last 7 (for recentDays).
     */
    private record StreakState(int current, boolean todayDone, Map<LocalDate, Integer> done) {
    }

    private StreakState streak(long userId, LocalDate day) {
        Map<LocalDate, Integer> done = repository.doneDays(userId, day.minusDays(6), day).stream()
                .collect(Collectors.toMap(DoneDay::date, DoneDay::streak, (a, b) -> a));
        Integer today = done.get(day);
        Integer yesterday = done.get(day.minusDays(1));
        int current = today != null ? today : yesterday != null ? yesterday : 0;
        return new StreakState(current, today != null, done);
    }

    private LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, settings.zoneId());
    }

    /** 0:00 of the next Japan day. */
    private Instant resetAt(LocalDate day) {
        return day.plusDays(1).atStartOfDay(settings.zoneId()).toInstant();
    }

    private void changed(long userId) {
        live.publish(userId, "quest", Map.of());
    }

    /** At most {@code events-per-minute} events in the last 60 s (application clock) per Member. */
    private boolean allowEvent(long userId) {
        long now = clock.millis();
        Deque<Long> times = recentEvents.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && (now - times.peekFirst() >= 60_000 || times.peekFirst() > now)) {
                times.pollFirst();
            }
            if (times.size() >= settings.getEventsPerMinute()) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
