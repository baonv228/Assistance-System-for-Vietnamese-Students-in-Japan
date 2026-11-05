package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.phrase.PhraseDtos.Marks;
import com.sep490.vsa.feature.phrase.PhraseDtos.Overview;
import com.sep490.vsa.feature.phrase.PhraseDtos.Part;
import com.sep490.vsa.feature.phrase.PhraseDtos.PhraseCard;
import com.sep490.vsa.feature.phrase.PhraseDtos.ReportRequest;
import com.sep490.vsa.feature.phrase.PhraseRepository.Filter;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Nhật ngữ" (the prototype's "Học" tab): Japanese phrases and words written by the Academic Manager, each tied to one
 * place category and to learning topics. Everyone can browse, search, listen, flip flash cards and show a phrase to
 * staff; signed-in Members (role USER) also keep their flash card answers ("Chưa thuộc" / "Đã thuộc", the last answer
 * counts) and report a wrong entry (reasons ACADEMIC_REPORT, handled by the Academic Manager). A phrase is never
 * hidden by reports; the Academic Manager corrects it.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PhraseService {

    private static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";          // MSG18
    private static final String PHRASE_NOT_FOUND = "Không tìm thấy mẫu câu này.";

    private static final int SEARCH_MAX = 100;

    /** The two parts of the page, in order: kind → {name, icon}. */
    private static final Map<String, String[]> PARTS = new LinkedHashMap<>();

    static {
        PARTS.put("PHRASE", new String[] {"Mẫu câu", "chat"});
        PARTS.put("VOCAB", new String[] {"Từ vựng", "abc"});
    }

    private final PhraseRepository repository;
    private final ContentReportService reports;

    public PhraseService(PhraseRepository repository, ContentReportService reports) {
        this.repository = repository;
        this.reports = reports;
    }

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public Overview overview(AppUserPrincipal me) {
        boolean member = isMember(me);
        long viewer = member ? me.id() : -1;
        List<Part> parts = PARTS.entrySet().stream().map(e -> {
            String kind = e.getKey();
            int[] totals = repository.kindTotals(viewer, kind);
            return new Part(kind, e.getValue()[0], e.getValue()[1], totals[0], totals[1],
                    repository.topics(viewer, kind), repository.categories(viewer, kind));
        }).toList();
        return new Overview(parts, member, reports.academicReasons());
    }

    /**
     * Phrases of one topic, one category or one of the viewer's own lists ({@code mine} = learning or
     * learned; needs a signed-in Member), or a search over all of them.
     */
    @Transactional(readOnly = true)
    public List<PhraseCard> list(AppUserPrincipal me, String topic, String category, String mine, String q) {
        String list = blankToNull(mine);
        if (list != null) {
            if (!PhraseRepository.MINE.containsKey(list)) {
                throw ApiException.badRequest("Danh sách không hợp lệ.");
            }
            member(me);
        }
        long viewer = isMember(me) ? me.id() : -1;
        String keyword = q == null || q.isBlank() ? null : q.strip();
        if (keyword != null && keyword.length() > SEARCH_MAX) {
            keyword = keyword.substring(0, SEARCH_MAX);
        }
        return repository.phrases(new Filter(blankToNull(topic), blankToNull(category), list, keyword), viewer);
    }

    // ---------------------------------------------------------------- marks

    /** A flash card answer: "Đã thuộc" ({@code learned}) or "Chưa thuộc"; the last answer replaces the one before. */
    public Marks flash(AppUserPrincipal me, long phraseId, boolean learned) {
        long userId = lock(me, phraseId);
        repository.setFlash(userId, phraseId, learned);
        return marks(userId, phraseId);
    }

    /** "Bỏ đánh dấu": neither "Chưa thuộc" nor "Đã thuộc" any more. */
    public Marks clearFlash(AppUserPrincipal me, long phraseId) {
        long userId = lock(me, phraseId);
        repository.clearFlash(userId, phraseId);
        return marks(userId, phraseId);
    }

    private long lock(AppUserPrincipal me, long phraseId) {
        long userId = member(me);
        repository.lockPhrase(phraseId, false).orElseThrow(PhraseService::phraseNotFound);
        return userId;
    }

    private Marks marks(long userId, long phraseId) {
        boolean[] m = repository.marks(userId, phraseId);
        return new Marks(m[0], m[1]);
    }

    // ---------------------------------------------------------------- reports

    /** "Báo lỗi" on a phrase. It stays shown whatever the count; the Academic Manager corrects it. */
    public void reportPhrase(AppUserPrincipal me, long phraseId, ReportRequest request) {
        long userId = member(me);
        long writer = repository.lockPhrase(phraseId, true).orElseThrow(PhraseService::phraseNotFound);
        reports.report(new Target("PHRASE", phraseId, writer), userId, request.reasonId(), request.note());
    }

    // ---------------------------------------------------------------- checks

    private boolean isMember(AppUserPrincipal me) {
        return me != null && repository.account(me.id())
                .filter(a -> "USER".equals(a.role()) && "ACTIVE".equals(a.status())).isPresent();
    }

    /** The signed-in Member's id. Guests get 401; staff roles and locked accounts get MSG18. */
    private long member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        var account = repository.account(me.id()).orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
        if (!"USER".equals(account.role()) || !"ACTIVE".equals(account.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return me.id();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException phraseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, PHRASE_NOT_FOUND);
    }
}
