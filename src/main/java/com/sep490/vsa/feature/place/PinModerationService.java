package com.sep490.vsa.feature.place;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.place.PlaceModerationDtos.ApproveRequest;
import com.sep490.vsa.feature.place.PlaceModerationDtos.CategoryRef;
import com.sep490.vsa.feature.place.PlaceModerationDtos.ClosedQueue;
import com.sep490.vsa.feature.place.PlaceModerationDtos.Done;
import com.sep490.vsa.feature.place.PlaceModerationDtos.FirstReview;
import com.sep490.vsa.feature.place.PlaceModerationDtos.MergeRequest;
import com.sep490.vsa.feature.place.PlaceModerationDtos.NearbyPlace;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PinDetail;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PinQueue;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PinRow;
import com.sep490.vsa.feature.place.PlaceModerationDtos.RejectRequest;
import com.sep490.vsa.feature.place.PlaceModerationRepository.Around;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceState;
import com.sep490.vsa.feature.place.PlaceModerationRepository.ReviewRef;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.user.PointService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The Moderator's place queues: "Ghim mới" (UC-M05 browse, UC-M06 approve / reject / merge a community pin) and
 * "Báo đóng cửa" (UC-M04 AF4/AF5: confirm the label, or keep the place open and drop the reports; the label can be
 * taken off again when the place reopens). Every action writes {@code place_history}, the activity log and, for a
 * community pin, a notification to its author. A place another Moderator handled a moment ago answers 409.
 */
@Service
public class PinModerationService {

    static final double NEARBY_M = 200;
    static final String HANDLED = "Ghim này đã được xử lý.";
    static final String GONE = "Địa điểm này không còn trên Senpai ơi.";

    private final PlaceModerationRepository repository;
    private final MapRepository mapRepository;
    private final MediaService media;
    private final PointService points;
    private final ContentReportService reports;
    private final NotificationService notifications;
    private final ActivityLogService activityLog;
    private final SystemSettings settings;
    private final ObjectMapper json;
    private final Clock clock;
    private final String mediaBaseUrl;

    public PinModerationService(PlaceModerationRepository repository, MapRepository mapRepository, MediaService media,
                                PointService points, ContentReportService reports,
                                NotificationService notifications, ActivityLogService activityLog,
                                SystemSettings settings, ObjectMapper json, Clock clock,
                                @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl) {
        this.repository = repository;
        this.mapRepository = mapRepository;
        this.media = media;
        this.points = points;
        this.reports = reports;
        this.notifications = notifications;
        this.activityLog = activityLog;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        this.mediaBaseUrl = mediaBaseUrl;
    }

    // ---------------------------------------------------------------- UC-M05 Ghim mới

    @Transactional(readOnly = true)
    public PinQueue pins(String categoryId, boolean duplicatesOnly) {
        List<PinRow> rows = new ArrayList<>();
        for (PlaceState p : repository.pendingPins()) {
            if (categoryId != null && !categoryId.isBlank() && !categoryId.equals(p.categoryId())) {
                continue;
            }
            List<NearbyPlace> near = nearby(p);
            boolean similar = near.stream().anyMatch(NearbyPlace::similar);
            if (duplicatesOnly && !similar) {
                continue;
            }
            rows.add(new PinRow(p.id(), p.nameVi(), p.nameJa(), p.nameEn(), categoryOf(p.categoryId()), p.createdBy(),
                    p.authorName(), p.createdAt(), near.size(), similar));
        }
        return new PinQueue(rows, repository.categories());
    }

    @Transactional(readOnly = true)
    public PinDetail pin(long id) {
        PlaceState p = repository.place(id, false).filter(PinModerationService::isPin)
                .orElseThrow(() -> notFound(HANDLED));
        ReviewRef review = repository.reviewOf(id, p.createdBy()).orElse(null);
        FirstReview first = review == null ? null : new FirstReview(review.id(), review.rating(), review.content(),
                review.status(), mapRepository.media(List.of(review.id()), null, mediaBaseUrl)
                .getOrDefault(review.id(), List.of()));
        return new PinDetail(p.id(), p.nameVi(), p.nameJa(), p.nameEn(), p.address(), p.lat(), p.lng(),
                categoryOf(p.categoryId()), p.createdBy(), p.authorName(), p.createdAt(), first, nearby(p),
                repository.reasons("PIN_REJECT"), repository.categories(), scanning(review));
    }

    // ---------------------------------------------------------------- UC-M06 Duyệt / Từ chối / Gộp

    @Transactional
    public Done approve(long moderatorId, long id, ApproveRequest request) {
        PlaceState p = pending(id);
        String nameVi = request == null || request.nameVi() == null || request.nameVi().isBlank()
                ? p.nameVi() : request.nameVi().strip();
        if (nameVi.length() > 160) {
            throw ApiException.invalid("Tên tiếng Việt tối đa 160 ký tự.", Map.of("nameVi", "Tên tiếng Việt tối đa 160 ký tự."));
        }
        String categoryId = request == null || request.categoryId() == null || request.categoryId().isBlank()
                ? p.categoryId() : request.categoryId();
        if (repository.category(categoryId).isEmpty()) {
            throw ApiException.invalid("Hãy chọn danh mục.", Map.of("categoryId", "Hãy chọn danh mục."));
        }
        ReviewRef review = repository.reviewOf(id, p.createdBy()).orElse(null);
        if (scanning(review)) {
            throw ApiException.conflict("Ảnh của review đầu tiên đang được quét. Hãy duyệt sau khi có kết quả.");
        }
        Instant now = clock.instant();
        if (!repository.approve(id, nameVi, categoryId, moderatorId, now)) {
            throw ApiException.conflict(HANDLED);
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        List<Map<String, String>> shown = new ArrayList<>();
        if (!nameVi.equals(p.nameVi())) {
            changes.put("nameVi", List.of(p.nameVi(), nameVi));
            shown.add(change("Tên tiếng Việt", p.nameVi(), nameVi));
        }
        if (!categoryId.equals(p.categoryId())) {
            changes.put("categoryId", List.of(p.categoryId(), categoryId));
            shown.add(change("Danh mục", categoryOf(p.categoryId()).name(), categoryOf(categoryId).name()));
        }
        repository.history(id, "APPROVED", moderatorId, toJson(Map.of("changes", shown)), now);
        points.add(p.createdBy(), PointService.PIN_APPROVED, "PIN_APPROVED", "PLACE", id);
        notifications.send(p.createdBy(), "PIN_APPROVED",
                "Ghim “" + nameVi + "” đã được duyệt. +" + PointService.PIN_APPROVED + " điểm.", "PLACE", id);
        activityLog.recordLog(moderatorId, "PIN_APPROVED", "PLACE", id, Map.of("status", "PENDING"),
                withStatus("ACTIVE", changes), "Duyệt ghim “" + nameVi + "”");
        return new Done("Đã duyệt ghim. Địa điểm đã hiện trên bản đồ.", id);
    }

    /** The pin becomes REJECTED and its first review is deleted (its points taken back, its files discarded). */
    @Transactional
    public Done reject(long moderatorId, long id, RejectRequest request) {
        if (request == null || request.reasonId() == null) {
            throw ApiException.invalid("Hãy chọn lý do từ chối.", Map.of("reasonId", "Hãy chọn lý do từ chối."));
        }
        String reason = repository.reasonLabel(request.reasonId(), "PIN_REJECT")
                .orElseThrow(() -> ApiException.invalid("Hãy chọn lý do từ chối.",
                        Map.of("reasonId", "Hãy chọn lý do từ chối.")));
        PlaceState p = pending(id);
        Instant now = clock.instant();
        if (!repository.reject(id, reason, moderatorId, now)) {
            throw ApiException.conflict(HANDLED);
        }
        repository.reviewOf(id, p.createdBy()).ifPresent(this::deleteReview);
        repository.history(id, "REJECTED", moderatorId, toJson(Map.of("reason", reason)), now);
        notifications.send(p.createdBy(), "PIN_REJECTED", "Ghim “" + p.nameVi() + "” bị từ chối: " + reason + ".",
                "PLACE", id);
        activityLog.recordLog(moderatorId, "PIN_REJECTED", "PLACE", id, Map.of("status", "PENDING"),
                Map.of("status", "REJECTED", "reason", reason), "Từ chối ghim “" + p.nameVi() + "”: " + reason);
        return new Done("Đã từ chối ghim.", null);
    }

    /**
     * The pin is the same as an approved place within 200 m: its first review moves there (when its author has none
     * there yet; otherwise it is deleted), the pin becomes MERGED into that place. The review keeps the points it
     * earned when it was published.
     */
    @Transactional
    public Done merge(long moderatorId, long id, MergeRequest request) {
        if (request == null || request.targetId() == null) {
            throw ApiException.invalid("Hãy chọn địa điểm để gộp vào.", Map.of("targetId", "Hãy chọn địa điểm để gộp vào."));
        }
        PlaceState p = pending(id);
        PlaceState target = repository.place(request.targetId(), true)
                .filter(t -> "ACTIVE".equals(t.status()) && t.lat() != null)
                .orElseThrow(() -> ApiException.invalid("Hãy chọn địa điểm để gộp vào.",
                        Map.of("targetId", "Địa điểm này không còn hoạt động.")));
        if (PlaceService.metres(p.lat(), p.lng(), target.lat(), target.lng()) > NEARBY_M) {
            throw ApiException.invalid("Chỉ gộp được vào địa điểm trong bán kính 200 m.",
                    Map.of("targetId", "Chỉ gộp được vào địa điểm trong bán kính 200 m."));
        }
        Instant now = clock.instant();
        int moved = 0;
        ReviewRef review = repository.reviewOf(id, p.createdBy()).orElse(null);
        if (review != null) {
            if (repository.hasReview(target.id(), p.createdBy())) {
                deleteReview(review);
            } else {
                repository.moveReview(review.id(), target.id());
                moved = 1;
            }
        }
        if (!repository.mergePin(id, target.id(), moderatorId, now)) {
            throw ApiException.conflict(HANDLED);
        }
        repository.moveSaves(id, target.id());
        repository.insertMerge(target.id(), id, "{}", moved, moderatorId, now);
        repository.history(id, "MERGED", moderatorId, toJson(Map.of("into", target.id(), "pin", true)), now);
        repository.history(target.id(), "MERGED", moderatorId, toJson(Map.of("from", id, "pin", true)), now);
        notifications.send(p.createdBy(), "PIN_MERGED", "Ghim “" + p.nameVi() + "” trùng với “" + target.nameVi()
                + "” đã có trên Senpai ơi" + (moved > 0 ? ", review của bạn đã được chuyển sang đó." : "."),
                "PLACE", target.id());
        activityLog.recordLog(moderatorId, "PIN_MERGED", "PLACE", id, Map.of("status", "PENDING"),
                Map.of("status", "MERGED", "into", target.id(), "movedReviews", moved),
                "Gộp ghim “" + p.nameVi() + "” vào “" + target.nameVi() + "”");
        return new Done("Đã gộp ghim vào “" + target.nameVi() + "”.", target.id());
    }

    // ---------------------------------------------------------------- Báo đóng cửa

    @Transactional(readOnly = true)
    public ClosedQueue closedQueue() {
        int threshold = threshold();
        return new ClosedQueue(repository.waitingClosed(threshold), repository.closedPlaces(), threshold);
    }

    /**
     * "Xác nhận, gắn nhãn đã đóng cửa" / "Gỡ nhãn, địa điểm đã mở lại" (also from the place details, UC-M08 AF5).
     * The pin stays on the map, faded; reviews are kept and nobody loses points.
     */
    @Transactional
    public Done setClosed(long moderatorId, long id, boolean closed) {
        PlaceState p = repository.place(id, true).filter(x -> "ACTIVE".equals(x.status()))
                .orElseThrow(() -> notFound(GONE));
        if (p.closed() == closed) {
            throw ApiException.conflict(closed ? "Địa điểm này đã có nhãn đóng cửa." : "Địa điểm này đang mở cửa.");
        }
        Instant now = clock.instant();
        repository.setClosed(id, closed, now);
        int handled = repository.openClosedReports(id);
        if (closed) {
            repository.resolveClosedReports(id, now);
        } else {
            repository.deleteClosedReports(id);
        }
        repository.history(id, closed ? "CLOSED" : "REOPENED", moderatorId, toJson(Map.of("reports", handled)), now);
        if (closed && "USER".equals(p.source())) {
            notifications.send(p.createdBy(), "PLACE_CLOSED", "Địa điểm “" + p.nameVi()
                    + "” đã được gắn nhãn đã đóng cửa theo báo cáo của cộng đồng.", "PLACE", id);
        }
        activityLog.recordLog(moderatorId, closed ? "PLACE_CLOSED" : "PLACE_REOPENED", "PLACE", id,
                Map.of("closed", !closed), Map.of("closed", closed, "reports", handled),
                (closed ? "Gắn nhãn đã đóng cửa “" : "Gỡ nhãn đóng cửa “") + p.nameVi() + "”");
        return new Done(closed ? "Đã gắn nhãn đã đóng cửa." : "Đã gỡ nhãn đóng cửa.", id);
    }

    /** "Vẫn mở cửa, xóa các báo cáo": the reports go and the place leaves the queue. */
    @Transactional
    public Done keepOpen(long moderatorId, long id) {
        PlaceState p = repository.place(id, true).filter(x -> "ACTIVE".equals(x.status()))
                .orElseThrow(() -> notFound(GONE));
        int removed = repository.deleteClosedReports(id);
        if (removed == 0) {
            throw ApiException.conflict("Các báo cáo của địa điểm này đã được xử lý.");
        }
        activityLog.recordLog(moderatorId, "CLOSED_REPORTS_DISMISSED", "PLACE", id, Map.of("reports", removed), null,
                "Vẫn mở cửa, xóa " + removed + " báo đóng cửa của “" + p.nameVi() + "”");
        return new Done("Đã xóa các báo đóng cửa. Địa điểm vẫn mở cửa.", id);
    }

    // ---------------------------------------------------------------- helpers

    /** Approved places within 200 m of the place, nearest first, with whether the names look alike. */
    List<NearbyPlace> nearby(PlaceState p) {
        return p.lat() == null ? List.of() : around(p.lat(), p.lng(), p.id(), p.nameVi(), p.nameJa(), p.nameEn());
    }

    /** Approved places within 200 m of a point (other than {@code exceptId}), nearest first, names compared. */
    List<NearbyPlace> around(double lat, double lng, long exceptId, String... placeNames) {
        List<String> keys = names(placeNames);
        List<NearbyPlace> out = new ArrayList<>();
        for (Around a : repository.activeAround(lat, lng, NEARBY_M, exceptId)) {
            double m = PlaceService.metres(lat, lng, a.lat(), a.lng());
            if (m > NEARBY_M) {
                continue;
            }
            boolean similar = names(a.nameVi(), a.nameJa(), a.nameEn()).stream()
                    .anyMatch(n -> keys.stream().anyMatch(k -> n.contains(k) || k.contains(n)));
            out.add(new NearbyPlace(a.id(), a.nameVi(), a.nameJa(), a.categoryName(), a.lat(), a.lng(),
                    (int) Math.round(m), similar, a.reviewCount()));
        }
        out.sort(Comparator.comparingInt(NearbyPlace::distance));
        return out;
    }

    /** Names as compared: no accents, case, spaces or punctuation; one letter says nothing. */
    static List<String> names(String... names) {
        return Stream.of(names).filter(Objects::nonNull).map(VietnameseText::squash).filter(n -> n.length() > 1)
                .toList();
    }

    private PlaceState pending(long id) {
        PlaceState p = repository.place(id, true).filter(PinModerationService::isPin)
                .orElseThrow(() -> notFound(HANDLED));
        if (!"PENDING".equals(p.status())) {
            throw ApiException.conflict(HANDLED);
        }
        return p;
    }

    private static boolean isPin(PlaceState p) {
        return "USER".equals(p.source()) && p.lat() != null;
    }

    private boolean scanning(ReviewRef review) {
        return review != null && ("PROCESSING".equals(review.status()) || repository.mediaScanning(review.id()));
    }

    /** Like the author deleting it: report cases closed, points taken back, files discarded. */
    private void deleteReview(ReviewRef review) {
        reports.closeCasesOf("REVIEW", List.of(review.id()));
        points.withdrawAll(review.authorId(), "REVIEW", review.id(), "CONTENT_DELETED");
        media.discard(repository.reviewMediaIds(review.id()));
        repository.deleteReview(review.id());
    }

    private CategoryRef categoryOf(String id) {
        return repository.category(id).orElse(new CategoryRef(id, id, "place"));
    }

    private int threshold() {
        return settings.getInt("closed_report_threshold", 5);
    }

    /** One changed field as {@code place_history} keeps it: shown as "Danh mục: Quán ăn → Siêu thị". */
    static Map<String, String> change(String field, String from, String to) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("field", field);
        c.put("from", from == null ? "" : from);
        c.put("to", to == null ? "" : to);
        return c;
    }

    private static Map<String, Object> withStatus(String status, Map<String, Object> changes) {
        Map<String, Object> v = new LinkedHashMap<>(changes);
        v.put("status", status);
        return v;
    }

    private String toJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }
}
