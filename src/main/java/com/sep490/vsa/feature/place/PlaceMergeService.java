package com.sep490.vsa.feature.place;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.place.PlaceMergeRepository.Candidate;
import com.sep490.vsa.feature.place.PlaceMergeRepository.PickRow;
import com.sep490.vsa.feature.place.PlaceMergeRepository.Twin;
import com.sep490.vsa.feature.place.PlaceModerationDtos.CategoryRef;
import com.sep490.vsa.feature.place.PlaceModerationDtos.DismissRequest;
import com.sep490.vsa.feature.place.PlaceModerationDtos.Done;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeCompare;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeDecision;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergePair;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergePick;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergePlace;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeQueue;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeSide;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceExtra;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceState;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceValues;
import com.sep490.vsa.feature.report.ContentReportService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * UC-M09 "Gộp trùng lặp". The queue is worked out when the page asks for it, so it always matches the places as they
 * are now: two approved places at most {@value #PAIR_M} m apart whose names look alike (accents, case and spaces
 * ignored, one may contain the other), not marked "Không trùng". "Cao" = same category, at most {@value #HIGH_M} m and
 * the same Japanese or English name; the rest "Trung bình". A merge is final: the kept place takes the chosen values
 * and gets the reviews, saves, post links and guide items; the other becomes MERGED and its links open the kept place.
 */
@Service
public class PlaceMergeService {

    static final double PAIR_M = 200;
    static final double HIGH_M = 50;
    static final int PAGE_SIZE = 10;
    /** The fields the Moderator chooses one side of. */
    static final List<String> FIELDS = List.of("nameVi", "nameJa", "nameEn", "address", "categoryId", "areaId", "position");
    static final String CHANGED = "Một trong hai địa điểm không còn hoạt động. Hãy tải lại hàng chờ.";

    private final PlaceMergeRepository repository;
    private final PlaceModerationRepository places;
    private final MediaService media;
    private final ContentReportService reports;
    private final NotificationService notifications;
    private final ActivityLogService activityLog;
    private final ObjectMapper json;
    private final Clock clock;

    public PlaceMergeService(PlaceMergeRepository repository, PlaceModerationRepository places, MediaService media,
                             ContentReportService reports, NotificationService notifications,
                             ActivityLogService activityLog, ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.places = places;
        this.media = media;
        this.reports = reports;
        this.notifications = notifications;
        this.activityLog = activityLog;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the queue

    @Transactional(readOnly = true)
    public MergeQueue queue(int page) {
        List<MergePair> pairs = pairs();
        int p = Math.max(0, page);
        int from = Math.min(pairs.size(), p * PAGE_SIZE);
        return new MergeQueue(pairs.subList(from, Math.min(pairs.size(), from + PAGE_SIZE)), p, PAGE_SIZE, pairs.size());
    }

    /** Every suspected pair, "Cao" first, then the closest. Places are bucketed by ~200 m cells to compare neighbours only. */
    List<MergePair> pairs() {
        List<Candidate> all = repository.activePlaces();
        Set<String> dismissed = repository.dismissed();
        double cell = PAIR_M / 111_320;
        Map<Long, List<Candidate>> grid = new HashMap<>();
        for (Candidate c : all) {
            grid.computeIfAbsent(cellKey((long) Math.floor(c.lat() / cell),
                    (long) Math.floor(c.lng() / cell)), k -> new ArrayList<>()).add(c);
        }
        List<MergePair> out = new ArrayList<>();
        for (Candidate a : all) {
            long row = (long) Math.floor(a.lat() / cell);
            long col = (long) Math.floor(a.lng() / cell);
            for (long dr = -1; dr <= 1; dr++) {
                for (long dc = -2; dc <= 2; dc++) {         // a degree of longitude is shorter than one of latitude
                    for (Candidate b : grid.getOrDefault(cellKey(row + dr, col + dc), List.of())) {
                        if (b.id() <= a.id() || dismissed.contains(a.id() + ":" + b.id())) {
                            continue;
                        }
                        double m = PlaceService.metres(a.lat(), a.lng(), b.lat(), b.lng());
                        if (m <= PAIR_M && similar(a, b)) {
                            out.add(new MergePair(side(a), side(b), (int) Math.round(m), level(a.categoryId(),
                                    b.categoryId(), m, a.nameJa(), b.nameJa(), a.nameEn(), b.nameEn())));
                        }
                    }
                }
            }
        }
        out.sort(Comparator.comparing((MergePair x) -> "HIGH".equals(x.level()) ? 0 : 1)
                .thenComparingInt(MergePair::distance).thenComparingLong(x -> x.a().id()));
        return out;
    }

    private static long cellKey(long row, long col) {
        return row * 4_000_000L + col;
    }

    private static boolean similar(Candidate a, Candidate b) {
        List<String> left = PinModerationService.names(a.nameVi(), a.nameJa(), a.nameEn());
        return PinModerationService.names(b.nameVi(), b.nameJa(), b.nameEn()).stream()
                .anyMatch(n -> left.stream().anyMatch(k -> n.contains(k) || k.contains(n)));
    }

    static String level(String catA, String catB, double metres, String jaA, String jaB, String enA, String enB) {
        boolean sameName = same(jaA, jaB) || same(enA, enB);
        return Objects.equals(catA, catB) && metres <= HIGH_M && sameName ? "HIGH" : "MEDIUM";
    }

    private static boolean same(String x, String y) {
        return x != null && y != null && !VietnameseText.squash(x).isEmpty()
                && VietnameseText.squash(x).equals(VietnameseText.squash(y));
    }

    private static MergeSide side(Candidate c) {
        return new MergeSide(c.id(), c.nameVi(), c.nameJa(), c.source(), c.reviewCount());
    }

    /** "Không trùng": the pair is not suggested again. */
    @Transactional
    public Done dismiss(long moderatorId, DismissRequest request) {
        if (request == null || request.aId() == null || request.bId() == null || request.aId().equals(request.bId())) {
            throw ApiException.badRequest("Hãy chọn hai địa điểm khác nhau.");
        }
        PlaceState a = places.place(request.aId(), false).orElseThrow(() -> notFound());
        PlaceState b = places.place(request.bId(), false).orElseThrow(() -> notFound());
        if (!repository.dismiss(a.id(), b.id(), moderatorId, clock.instant())) {
            throw ApiException.conflict("Cặp này đã được đánh dấu không trùng.");
        }
        activityLog.recordLog(moderatorId, "PLACE_DUPLICATE_DISMISSED", "PLACE", Math.min(a.id(), b.id()), null,
                Map.of("a", a.id(), "b", b.id()), "Không trùng: “" + a.nameVi() + "” và “" + b.nameVi() + "”");
        return new Done("Đã ghi nhận hai địa điểm không trùng.", null);
    }

    // ---------------------------------------------------------------- "So sánh"

    @Transactional(readOnly = true)
    public MergeCompare compare(long aId, long bId) {
        if (aId == bId) {
            throw ApiException.badRequest("Hãy chọn hai địa điểm khác nhau.");
        }
        PlaceState a = active(aId);
        PlaceState b = active(bId);
        double m = a.lat() == null || b.lat() == null ? -1 : PlaceService.metres(a.lat(), a.lng(), b.lat(), b.lng());
        return new MergeCompare(mergePlace(a), mergePlace(b), (int) Math.round(m),
                m < 0 ? "MEDIUM" : level(a.categoryId(), b.categoryId(), m, a.nameJa(), b.nameJa(), a.nameEn(), b.nameEn()),
                repository.sameAuthors(aId, bId));
    }

    /** "Chọn địa điểm để so sánh": approved places by name or address, with the distance from {@code nearId}. */
    @Transactional(readOnly = true)
    public List<MergePick> search(String q, Long nearId) {
        String text = q == null ? "" : q.strip();
        if (VietnameseText.squash(text).length() < 2) {
            return List.of();
        }
        PlaceState near = nearId == null ? null : places.place(nearId, false).orElse(null);
        List<MergePick> out = new ArrayList<>();
        for (PickRow r : repository.search(text, near == null ? 0 : near.id())) {
            Integer distance = near == null || near.lat() == null || r.lat() == null ? null
                    : (int) Math.round(PlaceService.metres(near.lat(), near.lng(), r.lat(), r.lng()));
            out.add(new MergePick(r.id(), r.name(), r.categoryName(), r.address(), distance));
        }
        if (near != null) {
            out.sort(Comparator.comparing(x -> x.distance() == null ? Integer.MAX_VALUE : x.distance()));
        }
        return out;
    }

    private MergePlace mergePlace(PlaceState p) {
        PlaceExtra x = places.extra(p.id()).orElseThrow();
        CategoryRef category = places.category(p.categoryId()).orElse(new CategoryRef(p.categoryId(), p.categoryId(), "place"));
        return new MergePlace(p.id(), p.nameVi(), p.nameJa(), p.nameEn(), p.address(), category, x.areaId(),
                x.areaName(), p.lat(), p.lng(), p.source(), x.osmId(), p.closed(), repository.counts(p.id()));
    }

    // ---------------------------------------------------------------- "Gộp"

    /**
     * The merge. People who reviewed both places keep only their newer review (no points taken: they did nothing
     * wrong). The merged place keeps its own OpenStreetMap id, which now leads to the kept place.
     */
    @Transactional
    public Done merge(long moderatorId, MergeDecision d) {
        if (d == null || d.keptId() == null || d.mergedId() == null || d.keptId().equals(d.mergedId())) {
            throw ApiException.badRequest("Hãy chọn hai địa điểm khác nhau.");
        }
        if (!d.confirmed()) {
            throw ApiException.badRequest("Hãy tick ô xác nhận trước khi gộp.");
        }
        repository.lockPair(d.keptId(), d.mergedId());
        PlaceState kept = places.place(d.keptId(), false).filter(p -> "ACTIVE".equals(p.status()))
                .orElseThrow(() -> ApiException.conflict(CHANGED));
        PlaceState merged = places.place(d.mergedId(), false).filter(p -> "ACTIVE".equals(p.status()))
                .orElseThrow(() -> ApiException.conflict(CHANGED));
        PlaceExtra keptExtra = places.extra(kept.id()).orElseThrow();
        PlaceExtra mergedExtra = places.extra(merged.id()).orElseThrow();
        Map<String, String> choices = new LinkedHashMap<>();
        for (String f : FIELDS) {
            String side = d.fields() == null ? null : d.fields().get(f);
            choices.put(f, "MERGED".equals(side) ? "MERGED" : "KEPT");
        }
        boolean fromMerged = "MERGED".equals(choices.get("position")) && merged.lat() != null;
        Double lat = fromMerged || kept.lat() == null ? merged.lat() : kept.lat();
        Double lng = fromMerged || kept.lng() == null ? merged.lng() : kept.lng();
        if (lat == null || lng == null) {
            throw ApiException.badRequest("Cần ít nhất một địa điểm có vị trí trên bản đồ.");
        }
        PlaceValues values = new PlaceValues(
                pick(choices, "nameVi", kept.nameVi(), merged.nameVi(), true),
                pick(choices, "nameJa", kept.nameJa(), merged.nameJa(), false),
                pick(choices, "nameEn", kept.nameEn(), merged.nameEn(), false),
                pick(choices, "address", kept.address(), merged.address(), false),
                pick(choices, "categoryId", kept.categoryId(), merged.categoryId(), true),
                "MERGED".equals(choices.get("areaId")) ? mergedExtra.areaId() : keptExtra.areaId(), lat, lng);

        Instant now = clock.instant();
        int dropped = 0;
        for (Twin t : repository.twins(kept.id(), merged.id())) {
            deleteReview(t.mergedIsNewer() ? t.keptReview() : t.mergedReview());
            dropped++;
        }
        int moved = repository.moveReviews(merged.id(), kept.id());
        repository.moveSaves(merged.id(), kept.id());
        repository.movePostLinks(merged.id(), kept.id());
        repository.moveGuideItems(merged.id(), kept.id());
        repository.moveClosedReports(merged.id(), kept.id());
        repository.repointMerged(merged.id(), kept.id());
        if (!repository.markMerged(merged.id(), kept.id(), now)) {
            throw ApiException.conflict(CHANGED);
        }
        repository.applyValues(kept.id(), values, now);

        List<Map<String, String>> changes = changes(kept, keptExtra, values);
        places.insertMerge(kept.id(), merged.id(), toJson(choices), moved, moderatorId, now);
        places.history(kept.id(), "MERGED", moderatorId, toJson(Map.of("from", merged.id(), "changes", changes,
                "movedReviews", moved, "droppedReviews", dropped)), now);
        places.history(merged.id(), "MERGED", moderatorId, toJson(Map.of("into", kept.id())), now);
        for (PlaceState p : List.of(kept, merged)) {
            if ("USER".equals(p.source())) {
                notifications.send(p.createdBy(), "PLACE_MERGED", "Địa điểm “" + p.nameVi() + "” bạn cắm ghim đã được gộp"
                        + (p == merged ? " vào “" + values.nameVi() + "”" : " với một địa điểm trùng") + ".", "PLACE", kept.id());
            }
        }
        activityLog.recordLog(moderatorId, "PLACE_MERGED", "PLACE", kept.id(), Map.of("merged", merged.id()),
                Map.of("kept", kept.id(), "fields", choices, "movedReviews", moved, "droppedReviews", dropped),
                "Gộp “" + merged.nameVi() + "” vào “" + values.nameVi() + "”");
        return new Done("Đã gộp “" + merged.nameVi() + "” vào “" + values.nameVi() + "”.", kept.id());
    }

    private static String pick(Map<String, String> choices, String field, String kept, String merged, boolean required) {
        String chosen = "MERGED".equals(choices.get(field)) ? merged : kept;
        return required && (chosen == null || chosen.isBlank()) ? kept : chosen;
    }

    private List<Map<String, String>> changes(PlaceState kept, PlaceExtra extra, PlaceValues v) {
        List<Map<String, String>> out = new ArrayList<>();
        add(out, "Tên tiếng Việt", kept.nameVi(), v.nameVi());
        add(out, "Tên tiếng Nhật", kept.nameJa(), v.nameJa());
        add(out, "Tên tiếng Anh", kept.nameEn(), v.nameEn());
        add(out, "Địa chỉ", kept.address(), v.address());
        if (!Objects.equals(kept.categoryId(), v.categoryId())) {
            out.add(PinModerationService.change("Danh mục", places.category(kept.categoryId()).map(CategoryRef::name)
                    .orElse(kept.categoryId()), places.category(v.categoryId()).map(CategoryRef::name).orElse(v.categoryId())));
        }
        if (!Objects.equals(extra.areaId(), v.areaId())) {
            out.add(PinModerationService.change("Khu vực", extra.areaName(), v.areaId() == null ? null
                    : places.area(v.areaId()).map(PlaceModerationDtos.AreaRef::name).orElse(null)));
        }
        if (kept.lat() == null || PlaceService.metres(kept.lat(), kept.lng(), v.lat(), v.lng()) >= 1) {
            out.add(PinModerationService.change("Vị trí", "cũ", "theo địa điểm được gộp"));
        }
        return out;
    }

    private static void add(List<Map<String, String>> out, String field, String from, String to) {
        if (!Objects.equals(from, to)) {
            out.add(PinModerationService.change(field, from, to));
        }
    }

    private void deleteReview(long reviewId) {
        reports.closeCasesOf("REVIEW", List.of(reviewId));
        media.discard(repository.reviewMediaIds(reviewId));
        repository.deleteReview(reviewId);
    }

    private PlaceState active(long id) {
        return places.place(id, false).filter(p -> "ACTIVE".equals(p.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Chỉ so sánh được hai địa điểm đang hoạt động."));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, PinModerationService.GONE);
    }

    private String toJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
