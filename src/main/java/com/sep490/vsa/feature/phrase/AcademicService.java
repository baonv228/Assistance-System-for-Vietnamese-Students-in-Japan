package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.phrase.AcademicDtos.Counts;
import com.sep490.vsa.feature.phrase.AcademicDtos.LibraryPage;
import com.sep490.vsa.feature.phrase.AcademicDtos.LibraryRow;
import com.sep490.vsa.feature.phrase.AcademicDtos.Options;
import com.sep490.vsa.feature.phrase.AcademicDtos.Overview;
import com.sep490.vsa.feature.phrase.AcademicDtos.PhraseRequest;
import com.sep490.vsa.feature.phrase.AcademicDtos.ReasonCount;
import com.sep490.vsa.feature.phrase.AcademicDtos.ReportCase;
import com.sep490.vsa.feature.phrase.AcademicDtos.Reporter;
import com.sep490.vsa.feature.phrase.AcademicDtos.SaveResult;
import com.sep490.vsa.feature.phrase.AcademicDtos.TopicRequest;
import com.sep490.vsa.feature.phrase.AcademicDtos.TopicRow;
import com.sep490.vsa.feature.phrase.AcademicRepository.CaseHead;
import com.sep490.vsa.feature.phrase.AcademicRepository.CaseRow;
import com.sep490.vsa.feature.phrase.AcademicRepository.PhraseRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The Academic Manager's side of "Nhật ngữ" (UC 9–14 of the learning specification): learning topics, the vocabulary
 * library (phrases and words, soft delete) and the reports on phrases ("Sửa mẫu câu này" / "Không sai, bỏ qua").
 * Every change goes to the activity log.
 * <p>
 * Locks follow the order of a Member's report: the phrase row first, then its case. "cần chốt" values are the
 * constants below.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class AcademicService {

    private static final int TOPIC_NAME_MAX = 60;
    private static final int JAPANESE_MAX = 200;
    private static final int ROMAJI_MAX = 200;
    private static final int MEANING_MAX = 300;
    /** "Chủ đề học còn ít câu" on the overview. */
    private static final int THIN_TOPIC = 3;

    private static final String TOPIC_NOT_FOUND = "Không tìm thấy chủ đề học.";
    private static final String PHRASE_NOT_FOUND = "Không tìm thấy mẫu câu.";
    private static final String CASE_NOT_FOUND = "Báo cáo này đã được xử lý.";

    private static final String DEFAULT_ICON = "label";
    /** Icons offered for a learning topic (Material Symbols names). */
    private static final List<String> ICONS = List.of("label", "payments", "help", "restaurant_menu", "train",
            "medical_services", "description", "waving_hand", "home", "bed", "work", "school", "shopping_cart",
            "local_post_office", "smartphone", "directions_bus", "local_laundry_service", "emergency", "celebration",
            "account_balance", "translate", "forum");

    /** Hiragana, katakana (also half-width) or kanji. */
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF66-\\uFF9F々〆]");

    private final AcademicRepository repository;
    private final ActivityLogService activityLog;
    private final Clock clock;

    public AcademicService(AcademicRepository repository, ActivityLogService activityLog, Clock clock) {
        this.repository = repository;
        this.activityLog = activityLog;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- overview

    @Transactional(readOnly = true)
    public Overview overview(long managerId) {
        Counts counts = new Counts(repository.openPhraseReports(), repository.phrases("PHRASE"),
                repository.phrases("VOCAB"));
        return new Overview(counts, repository.emptyCategories(), repository.thinTopics(THIN_TOPIC),
                repository.recentActions(managerId, 5));
    }

    // ---------------------------------------------------------------- learning topics (UC 9–11)

    @Transactional(readOnly = true)
    public List<TopicRow> topics() {
        return repository.topics();
    }

    /** "Thêm chủ đề học"; the page reads the list again afterwards. */
    public void createTopic(long managerId, TopicRequest request) {
        String name = topicName(request);
        String icon = icon(request);
        Map<String, String> names = repository.lockTopicNames();
        checkTopicName(name, names, null);
        String id = newTopicId(names);
        repository.insertTopic(id, name, icon);
        activityLog.recordLog(managerId, "LEARNING_TOPIC_CREATED", "LEARNING_TOPIC", null, null,
                Map.of("id", id, "name", name, "icon", icon), "Thêm chủ đề học \"" + name + "\"");
    }

    /** "Sửa chủ đề học"; nothing is written or logged when nothing differs (UC 10 AF2). */
    public void updateTopic(long managerId, String id, TopicRequest request) {
        String name = topicName(request);
        String icon = icon(request);
        Map<String, String> names = repository.lockTopicNames();
        TopicRow old = repository.lockTopic(id).orElseThrow(AcademicService::topicNotFound);
        if (!old.name().equals(name) || !old.icon().equals(icon)) {
            checkTopicName(name, names, id);
            repository.updateTopic(id, name, icon);
            activityLog.recordLog(managerId, "LEARNING_TOPIC_UPDATED", "LEARNING_TOPIC", null,
                    Map.of("id", id, "name", old.name(), "icon", old.icon()), Map.of("id", id, "name", name, "icon", icon),
                    "Sửa chủ đề học \"" + old.name() + "\"" + (old.name().equals(name) ? "" : " thành \"" + name + "\""));
        }
    }

    /** Only the labels go: the phrases and their "Chưa thuộc" / "Đã thuộc" marks stay (UC 11). */
    public void deleteTopic(long managerId, String id) {
        repository.lockTopicNames();
        TopicRow topic = repository.lockTopic(id).orElseThrow(AcademicService::topicNotFound);
        int unlinked = repository.deleteTopic(id);
        activityLog.recordLog(managerId, "LEARNING_TOPIC_DELETED", "LEARNING_TOPIC", null,
                Map.of("id", id, "name", topic.name(), "phrases", unlinked), null,
                "Xóa chủ đề học \"" + topic.name() + "\"");
    }

    private static String topicName(TopicRequest request) {
        String name = request == null || request.name() == null ? "" : request.name().strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw ApiException.invalid("Hãy nhập tên chủ đề học.", Map.of("name", "Hãy nhập tên chủ đề học."));
        }
        if (name.length() > TOPIC_NAME_MAX) {
            String message = "Tên chủ đề học tối đa " + TOPIC_NAME_MAX + " ký tự.";
            throw ApiException.invalid(message, Map.of("name", message));
        }
        return name;
    }

    private static String icon(TopicRequest request) {
        String icon = request == null || request.icon() == null || request.icon().isBlank() ? DEFAULT_ICON
                : request.icon().strip();
        if (!ICONS.contains(icon)) {
            throw ApiException.invalid("Hãy chọn một biểu tượng trong danh sách.", Map.of("icon", "Hãy chọn một biểu tượng trong danh sách."));
        }
        return icon;
    }

    /** Duplicate = same letters and digits once accents, case, spaces and signs are ignored. */
    private static void checkTopicName(String name, Map<String, String> names, String exceptId) {
        String key = VietnameseText.squash(name);
        boolean taken = names.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(exceptId) && VietnameseText.squash(e.getValue()).equals(key));
        if (taken) {
            throw ApiException.invalid("Chủ đề học này đã có.", Map.of("name", "Chủ đề học này đã có."));
        }
    }

    private String newTopicId(Map<String, String> names) {
        long n = clock.millis();
        String id;
        do {
            id = "t" + Long.toString(n++, 36);
        } while (names.containsKey(id));
        return id;
    }

    // ---------------------------------------------------------------- library (UC 12–14)

    @Transactional(readOnly = true)
    public LibraryPage library(String category, String topic, String kind, String q, int page) {
        String c = blankToNull(category);
        String t = blankToNull(topic);
        String k = blankToNull(kind);
        String keyword = blankToNull(q);
        int p = Math.max(0, page);
        return new LibraryPage(repository.library(c, t, k, keyword, p), p, AcademicRepository.PAGE_SIZE,
                repository.libraryTotal(c, t, k, keyword));
    }

    @Transactional(readOnly = true)
    public Options options() {
        return new Options(repository.categories(), repository.topicRefs(), ICONS);
    }

    @Transactional(readOnly = true)
    public LibraryRow phrase(long id) {
        LibraryRow row = repository.libraryRow(id);
        if (row == null) {
            throw phraseNotFound();
        }
        return row;
    }

    public SaveResult createPhrase(long managerId, PhraseRequest request) {
        Fields f = fields(request);
        if (!request.confirmDuplicate()) {
            var duplicate = repository.duplicate(f.categoryId(), f.japanese(), null);
            if (duplicate.isPresent()) {
                return new SaveResult(false, repository.libraryRow(duplicate.get()),
                        "Danh mục này đã có mục \"" + f.japanese() + "\". Bạn vẫn muốn lưu?");
            }
        }
        long id = repository.insertPhrase(new PhraseRow(0, f.categoryId(), f.kind(), f.japanese(), f.romaji(),
                f.meaningVi(), managerId), clock.instant());
        repository.setTopics(id, f.topicIds());
        activityLog.recordLog(managerId, "PHRASE_CREATED", "PHRASE", id, null, values(f), "Thêm mẫu câu: " + f.japanese());
        LibraryRow row = repository.libraryRow(id);
        String where = "\"" + row.category().name() + "\"";
        return new SaveResult(true, null, f.topicIds().isEmpty()
                ? "Đã thêm. " + word(f.kind()) + " hiện ở mọi địa điểm " + where + "."
                : "Đã thêm. " + word(f.kind()) + " hiện ở mọi địa điểm " + where + " và trong tab Nhật ngữ.");
    }

    /**
     * Saves the changes; the topics are replaced. With {@code reportCaseId} ("Sửa mẫu câu này" from a report) every open
     * report of the phrase is closed as CORRECTED. The Members' "Chưa thuộc" / "Đã thuộc" marks stay.
     */
    public SaveResult updatePhrase(long managerId, long id, PhraseRequest request) {
        Fields f = fields(request);
        PhraseRow old = repository.lockPhrase(id).orElseThrow(AcademicService::phraseNotFound);
        CaseRow fromReport = null;
        if (request.reportCaseId() != null) {
            fromReport = repository.lockOpenCase(request.reportCaseId())
                    .filter(c -> "PHRASE".equals(c.targetType()) && c.targetId() == id)
                    .orElseThrow(() -> ApiException.conflict(CASE_NOT_FOUND));
        }
        List<String> oldTopics = repository.topicIdsOf(id);
        boolean changed = !old.categoryId().equals(f.categoryId()) || !old.kind().equals(f.kind())
                || !old.japanese().equals(f.japanese()) || !old.romaji().equals(f.romaji())
                || !old.meaningVi().equals(f.meaningVi())
                || !new LinkedHashSet<>(oldTopics).equals(new LinkedHashSet<>(f.topicIds()));
        if (!changed) {
            return new SaveResult(true, null, "Không có thay đổi nào.");
        }
        if (!request.confirmDuplicate()) {
            var duplicate = repository.duplicate(f.categoryId(), f.japanese(), id);
            if (duplicate.isPresent()) {
                return new SaveResult(false, repository.libraryRow(duplicate.get()),
                        "Danh mục này đã có mục \"" + f.japanese() + "\". Bạn vẫn muốn lưu?");
            }
        }
        repository.updatePhrase(new PhraseRow(id, f.categoryId(), f.kind(), f.japanese(), f.romaji(), f.meaningVi(),
                old.createdBy()), clock.instant());
        repository.setTopics(id, f.topicIds());
        Map<String, Object> before = new LinkedHashMap<>(Map.of("categoryId", old.categoryId(), "kind", old.kind(),
                "japanese", old.japanese(), "romaji", old.romaji(), "meaningVi", old.meaningVi()));
        before.put("topicIds", oldTopics);
        if (fromReport != null) {
            repository.closeCases("PHRASE", List.of(id), "CORRECTED", "Đã sửa mẫu câu.", managerId, clock.instant());
        }
        activityLog.recordLog(managerId, "PHRASE_UPDATED", "PHRASE", id, before, values(f),
                (fromReport != null ? "Sửa mẫu câu theo báo cáo: " : "Sửa mẫu câu: ") + f.japanese());
        return new SaveResult(true, null, fromReport != null ? "Đã lưu thay đổi và đóng báo cáo." : "Đã lưu thay đổi.");
    }

    /** Soft delete: hidden everywhere (Map, page, the Members' lists, search); its open reports close. */
    public void deletePhrase(long managerId, long id) {
        PhraseRow old = repository.lockPhrase(id).orElseThrow(AcademicService::phraseNotFound);
        Instant now = clock.instant();
        repository.softDeletePhrase(id, now);
        repository.closeCases("PHRASE", List.of(id), "REMOVE", "Mẫu câu đã bị xóa.", managerId, now);
        activityLog.recordLog(managerId, "PHRASE_DELETED", "PHRASE", id, Map.of("japanese", old.japanese(),
                "meaningVi", old.meaningVi(), "categoryId", old.categoryId()), null, "Xóa mẫu câu: " + old.japanese());
    }

    private record Fields(String categoryId, String kind, String japanese, String romaji, String meaningVi,
                          List<String> topicIds) {
    }

    /** UC 12 step 4: every problem at once, under its field. */
    private Fields fields(PhraseRequest request) {
        if (request == null) {
            throw ApiException.badRequest("Thiếu dữ liệu.");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String categoryId = blankToNull(request.categoryId());
        if (categoryId == null || !repository.categoryExists(categoryId)) {
            errors.put("categoryId", "Hãy chọn danh mục địa điểm.");
        }
        String kind = request.kind() == null ? "" : request.kind().strip();
        if (!kind.equals("PHRASE") && !kind.equals("VOCAB")) {
            errors.put("kind", "Hãy chọn loại: Mẫu câu hoặc Từ vựng.");
        }
        String japanese = request.japanese() == null ? "" : request.japanese().strip();
        if (!JAPANESE.matcher(japanese).find()) {
            errors.put("japanese", "Ô tiếng Nhật cần có chữ Nhật (hiragana, katakana hoặc kanji).");
        } else if (japanese.length() > JAPANESE_MAX) {
            errors.put("japanese", "Tiếng Nhật tối đa " + JAPANESE_MAX + " ký tự.");
        }
        String romaji = request.romaji() == null ? "" : request.romaji().strip();
        if (romaji.length() > ROMAJI_MAX) {
            errors.put("romaji", "Romaji tối đa " + ROMAJI_MAX + " ký tự.");
        }
        String meaning = request.meaningVi() == null ? "" : request.meaningVi().strip();
        if (meaning.isEmpty()) {
            errors.put("meaningVi", "Hãy nhập nghĩa tiếng Việt.");
        } else if (meaning.length() > MEANING_MAX) {
            errors.put("meaningVi", "Nghĩa tiếng Việt tối đa " + MEANING_MAX + " ký tự.");
        }
        List<String> topicIds = request.topicIds() == null ? List.of()
                : request.topicIds().stream().filter(Objects::nonNull).map(String::strip).distinct().sorted().toList();
        if (topicIds.stream().anyMatch(t -> !repository.topicExists(t))) {
            errors.put("topicIds", "Có chủ đề học không còn tồn tại. Hãy tải lại trang.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
        return new Fields(categoryId, kind, japanese, romaji, meaning, topicIds);
    }

    private static Map<String, Object> values(Fields f) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("categoryId", f.categoryId());
        map.put("kind", f.kind());
        map.put("japanese", f.japanese());
        map.put("romaji", f.romaji());
        map.put("meaningVi", f.meaningVi());
        map.put("topicIds", f.topicIds());
        return map;
    }

    private static String word(String kind) {
        return "VOCAB".equals(kind) ? "Từ vựng" : "Mẫu câu";
    }

    // ---------------------------------------------------------------- reports

    /** The open reports on phrases, urgent first. */
    @Transactional(readOnly = true)
    public List<ReportCase> reports() {
        List<CaseHead> heads = repository.openPhraseCases();
        Map<Long, List<Reporter>> reporters = repository.reportersOf(heads.stream().map(CaseHead::id).toList());
        List<ReportCase> cases = new ArrayList<>();
        for (CaseHead h : heads) {
            List<Reporter> by = reporters.getOrDefault(h.id(), List.of());
            cases.add(new ReportCase(h.id(), h.priority(), h.reportCount(), h.createdAt(),
                    reasonCounts(by), by, repository.libraryRow(h.targetId())));
        }
        return cases;
    }

    private static List<ReasonCount> reasonCounts(List<Reporter> by) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        by.forEach(r -> counts.merge(r.reason(), 1, Integer::sum));
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new ReasonCount(e.getKey(), e.getValue())).toList();
    }

    /** "Không sai, bỏ qua": the case closes, the phrase stays as it is. */
    public void dismissReport(long managerId, long caseId) {
        CaseRow found = repository.findCase(caseId)
                .filter(c -> "OPEN".equals(c.status()) && "PHRASE".equals(c.targetType()))
                .orElseThrow(() -> ApiException.conflict(CASE_NOT_FOUND));
        PhraseRow phrase = repository.lockPhrase(found.targetId()).orElseThrow(AcademicService::phraseNotFound);
        openCase(caseId, found);
        repository.closeCases("PHRASE", List.of(phrase.id()), "KEEP", "Không sai, giữ nguyên.", managerId,
                clock.instant());
        activityLog.recordLog(managerId, "PHRASE_REPORT_DISMISSED", "PHRASE", phrase.id(), null, null,
                "Bỏ qua báo cáo mẫu câu: " + phrase.japanese());
    }

    private void openCase(long caseId, CaseRow found) {
        repository.lockOpenCase(caseId)
                .filter(c -> c.targetType().equals(found.targetType()) && c.targetId() == found.targetId())
                .orElseThrow(() -> ApiException.conflict(CASE_NOT_FOUND));
    }

    // ---------------------------------------------------------------- helpers

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException topicNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, TOPIC_NOT_FOUND);
    }

    private static ApiException phraseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, PHRASE_NOT_FOUND);
    }
}
