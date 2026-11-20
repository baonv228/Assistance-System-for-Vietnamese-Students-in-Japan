package com.sep490.vsa.feature.quest;

import com.sep490.vsa.feature.phrase.PhraseOfDayService;
import com.sep490.vsa.feature.quest.QuestDtos.Action;
import com.sep490.vsa.feature.quest.QuestRepository.TopicStat;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The quest codes of "Nhiệm vụ hằng ngày" (contract 3.3), one {@link QuestRule} each. "Mục" is always an entry of the
 * library (Mẫu câu or Từ vựng, not deleted); "trả lời" is a flash card answer, "Chưa thuộc" or "Đã thuộc". The
 * "Luyện tập" filters of the action mean what the page and the app mean: {@code status} "new" = no answer kept,
 * "learning" = "Chưa thuộc", "learned" = "Đã thuộc".
 * <p>
 * Titles and descriptions are the texts agreed with the mobile app; the title is kept with the quest row.
 */
final class QuestRules {

    static final String FLASH_REVIEW = "FLASH_REVIEW";
    static final String LEARN_NEW = "LEARN_NEW";
    static final String REVIEW_LEARNING = "REVIEW_LEARNING";
    static final String TOPIC = "TOPIC";
    static final String PHRASE_OF_DAY = "PHRASE_OF_DAY";

    /** The only event so far: the Member listened to the phrase of the day. */
    static final String LISTEN = "LISTEN";

    private QuestRules() {
    }

    /** Every rule by its code. */
    static Map<String, QuestRule> byCode(List<QuestRule> rules) {
        return rules.stream().collect(Collectors.toMap(QuestRule::code, Function.identity(), (a, b) -> a,
                LinkedHashMap::new));
    }

    /**
     * "Ôn {n} thẻ ghi nhớ" (fixed, every day): any answer of an entry not counted yet today. Possible with one entry in
     * the library; the target is at most the library's size.
     */
    @Component
    static class FlashReview implements QuestRule {

        private final QuestRepository repository;

        FlashReview(QuestRepository repository) {
            this.repository = repository;
        }

        @Override
        public String code() {
            return FLASH_REVIEW;
        }

        @Override
        public Plan plan(long userId, LocalDate day, int fullTarget) {
            return new Plan(repository.libraryCount(), Map.of());
        }

        @Override
        public String title(int target, Map<String, Object> params) {
            return "Ôn " + target + " thẻ ghi nhớ";
        }

        @Override
        public String description(int target, Map<String, Object> params) {
            return "Trả lời " + target + " thẻ khác nhau, Chưa thuộc hay Đã thuộc đều được.";
        }

        @Override
        public Action action(int target, Map<String, Object> params) {
            return Action.practice("", "", "", "", "");
        }

        @Override
        public boolean countsAnswer(Answer answer, Map<String, Object> params) {
            return true;
        }
    }

    /**
     * "Thuộc {n} mục mới": an answer "Đã thuộc" for an entry that was not "Đã thuộc" right before and never counted
     * for this quest on any day (so "Bỏ đánh dấu" then "Đã thuộc" again never pays twice). Possible when that many
     * entries qualify. The app is sent to the entries without an answer ("new") when there are enough of them, else
     * to "Chưa thuộc"; worked out when the day is made and kept.
     */
    @Component
    static class LearnNew implements QuestRule {

        private final QuestRepository repository;

        LearnNew(QuestRepository repository) {
            this.repository = repository;
        }

        @Override
        public String code() {
            return LEARN_NEW;
        }

        @Override
        public Plan plan(long userId, LocalDate day, int fullTarget) {
            int eligible = repository.notLearnedNeverCounted(userId, LEARN_NEW);
            if (eligible == 0) {
                return Plan.none();
            }
            int target = Math.min(fullTarget, eligible);
            String status = repository.unansweredCount(userId) >= target ? "new" : "learning";
            return new Plan(eligible, Map.of("status", status));
        }

        @Override
        public String title(int target, Map<String, Object> params) {
            return "Thuộc " + target + " mục mới";
        }

        @Override
        public String description(int target, Map<String, Object> params) {
            return "Chọn “Đã thuộc” cho " + target + " mục bạn chưa thuộc.";
        }

        @Override
        public Action action(int target, Map<String, Object> params) {
            String status = QuestRule.textParam(params, "status");
            return Action.practice("", "", "", "", status.isEmpty() ? "new" : status);
        }

        /**
         * The "Luyện tập" list that can still finish the quest, worked out on every read against what is left
         * (target − progress): "new" (no mark) when enough entries not counted yet are there, else "learning" ("Chưa
         * thuộc"), else no filter (both together). The status kept when the day was made could send the Member to a
         * list too short, e.g. after answering "Chưa thuộc" to the new entries in FLASH_REVIEW.
         */
        @Override
        public Action actionNow(long userId, int target, int progress, Map<String, Object> params) {
            int left = Math.max(1, target - progress);
            String status = repository.notCountedFor(userId, LEARN_NEW, false) >= left ? "new"
                    : repository.notCountedFor(userId, LEARN_NEW, true) >= left ? "learning" : "";
            return Action.practice("", "", "", "", status);
        }

        @Override
        public boolean countsAnswer(Answer answer, Map<String, Object> params) {
            return answer.learned() && !answer.wasLearned()
                    && !repository.everCounted(answer.userId(), LEARN_NEW, answer.phraseId());
        }
    }

    /**
     * "Luyện lại {n} thẻ “Chưa thuộc”": any answer of an entry that was "Chưa thuộc" right before it (each entry once a
     * day). Possible when the Member keeps that many entries as "Chưa thuộc".
     */
    @Component
    static class ReviewLearning implements QuestRule {

        private final QuestRepository repository;

        ReviewLearning(QuestRepository repository) {
            this.repository = repository;
        }

        @Override
        public String code() {
            return REVIEW_LEARNING;
        }

        @Override
        public Plan plan(long userId, LocalDate day, int fullTarget) {
            return new Plan(repository.learningCount(userId), Map.of());
        }

        @Override
        public String title(int target, Map<String, Object> params) {
            return "Luyện lại " + target + " thẻ “Chưa thuộc”";
        }

        @Override
        public String description(int target, Map<String, Object> params) {
            return "Luyện lại " + target + " thẻ đang ở trạng thái Chưa thuộc.";
        }

        @Override
        public Action action(int target, Map<String, Object> params) {
            return Action.practice("", "", "", "", "learning");
        }

        @Override
        public boolean countsAnswer(Answer answer, Map<String, Object> params) {
            return answer.wasLearning();
        }
    }

    /**
     * "Ôn {n} thẻ chủ đề “…”": any answer of an entry linked to the topic chosen when the day was made (each entry
     * once a day). The topic: the first one of the Member's stage ({@code app.quests.stage-topics}) holding at least
     * the target; else, among the topics holding the target, the one with the most entries the Member does not keep
     * as "Đã thuộc"; else (no topic that big) the biggest topic, which is only taken with a lowered target.
     */
    @Component
    static class Topic implements QuestRule {

        private static final Comparator<TopicStat> MOST_TO_LEARN = Comparator
                .comparingInt(TopicStat::notLearned).reversed()
                .thenComparing(Comparator.comparingInt(TopicStat::entries).reversed())
                .thenComparing(TopicStat::id);
        private static final Comparator<TopicStat> BIGGEST = Comparator
                .comparingInt(TopicStat::entries).reversed()
                .thenComparing(Comparator.comparingInt(TopicStat::notLearned).reversed())
                .thenComparing(TopicStat::id);

        private final QuestRepository repository;
        private final QuestSettings settings;

        Topic(QuestRepository repository, QuestSettings settings) {
            this.repository = repository;
            this.settings = settings;
        }

        @Override
        public String code() {
            return TOPIC;
        }

        @Override
        public Plan plan(long userId, LocalDate day, int fullTarget) {
            List<TopicStat> topics = repository.topicStats(userId);
            if (topics.isEmpty()) {
                return Plan.none();
            }
            Map<String, TopicStat> byId = topics.stream()
                    .collect(Collectors.toMap(TopicStat::id, Function.identity(), (a, b) -> a));
            Optional<TopicStat> chosen = settings.topicsFor(repository.stage(userId)).stream()
                    .map(byId::get).filter(Objects::nonNull).filter(t -> t.entries() >= fullTarget).findFirst();
            if (chosen.isEmpty()) {
                chosen = topics.stream().filter(t -> t.entries() >= fullTarget).min(MOST_TO_LEARN);
            }
            TopicStat topic = chosen.orElseGet(() -> topics.stream().min(BIGGEST).orElseThrow());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("topicId", topic.id());
            params.put("topicName", topic.name());
            return new Plan(topic.entries(), params);
        }

        @Override
        public String title(int target, Map<String, Object> params) {
            return "Ôn " + target + " thẻ chủ đề “" + QuestRule.textParam(params, "topicName") + "”";
        }

        @Override
        public String description(int target, Map<String, Object> params) {
            return "Trả lời " + target + " thẻ khác nhau của chủ đề " + QuestRule.textParam(params, "topicName") + ".";
        }

        @Override
        public Action action(int target, Map<String, Object> params) {
            return Action.practice("topic", QuestRule.textParam(params, "topicId"),
                    QuestRule.textParam(params, "topicName"), "", "");
        }

        @Override
        public boolean countsAnswer(Answer answer, Map<String, Object> params) {
            String topicId = QuestRule.textParam(params, "topicId");
            return !topicId.isEmpty() && repository.inTopic(answer.phraseId(), topicId);
        }
    }

    /**
     * "Nghe câu tiếng Nhật hôm nay": the event LISTEN with the phrase of the day ({@link PhraseOfDayService}, the same
     * phrase as the Home all day), sent by the app or by "Nghe" on the web Home. Flash card answers never count.
     * Possible when the day has a phrase.
     */
    @Component
    static class PhraseOfDay implements QuestRule {

        private final PhraseOfDayService phraseOfDay;

        PhraseOfDay(PhraseOfDayService phraseOfDay) {
            this.phraseOfDay = phraseOfDay;
        }

        @Override
        public String code() {
            return PHRASE_OF_DAY;
        }

        @Override
        public Plan plan(long userId, LocalDate day, int fullTarget) {
            return phraseOfDay.phraseOf(day)
                    .map(p -> new Plan(1, Map.<String, Object>of("phraseId", p.id())))
                    .orElseGet(Plan::none);
        }

        @Override
        public String title(int target, Map<String, Object> params) {
            return "Nghe câu tiếng Nhật hôm nay";
        }

        @Override
        public String description(int target, Map<String, Object> params) {
            return "Bấm Nghe để nghe câu hôm nay một lần.";
        }

        @Override
        public Action action(int target, Map<String, Object> params) {
            return Action.listen();
        }

        @Override
        public boolean countsEvent(Event event, Map<String, Object> params) {
            Long phraseId = QuestRule.longParam(params, "phraseId");
            return LISTEN.equals(event.type()) && phraseId != null && phraseId == event.phraseId();
        }

        @Override
        public Long phraseId(Map<String, Object> params) {
            return QuestRule.longParam(params, "phraseId");
        }
    }
}
