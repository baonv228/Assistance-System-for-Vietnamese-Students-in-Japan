package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.place.MapDtos.Author;
import com.sep490.vsa.feature.place.MapDtos.DevLocation;
import com.sep490.vsa.feature.place.MapDtos.LikeState;
import com.sep490.vsa.feature.place.MapDtos.MapCategory;
import com.sep490.vsa.feature.place.MapDtos.MapCenter;
import com.sep490.vsa.feature.place.MapDtos.MapEvent;
import com.sep490.vsa.feature.place.MapDtos.MapOverview;
import com.sep490.vsa.feature.place.MapDtos.MapSearch;
import com.sep490.vsa.feature.place.MapDtos.PhraseItem;
import com.sep490.vsa.feature.place.MapDtos.PlaceDetail;
import com.sep490.vsa.feature.place.MapDtos.ReportReason;
import com.sep490.vsa.feature.place.MapDtos.ReportRequest;
import com.sep490.vsa.feature.place.MapDtos.ReviewItem;
import com.sep490.vsa.feature.place.MapDtos.ReviewMedia;
import com.sep490.vsa.feature.place.MapDtos.ReviewPage;
import com.sep490.vsa.feature.place.MapDtos.SaveState;
import com.sep490.vsa.feature.place.MapDtos.Tag;
import com.sep490.vsa.feature.place.MapRepository.PlaceRow;
import com.sep490.vsa.feature.place.MapRepository.RankRow;
import com.sep490.vsa.feature.place.MapRepository.ReviewRow;
import com.sep490.vsa.feature.place.MapRepository.ReviewTarget;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceOption;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceSearch;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Target;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Map: UC-U14 Browse Locations on Map and UC-U15 View Location Details, with saving a place (UC-U12) and liking
 * or reporting a review (UC-U11) from the details panel.
 * <ul>
 *   <li>Everyone can view the map, a place and its reviews. Saving, liking and reporting need a signed-in User;
 *       staff only read.</li>
 *   <li>A merged place opens as the place it was merged into; a removed, rejected or someone else's pending place
 *       is "not on Senpai ơi any more".</li>
 *   <li>Search: guests get Senpai ơi places only; signed-in people also get OpenStreetMap places through
 *       {@link PlaceService#search}, which limits how often one person may ask the free map services.</li>
 * </ul>
 */
@Service
public class MapService {

    static final String NOT_ON_MAP = "Địa điểm này không còn trên Senpai ơi.";
    static final String NO_PERMISSION = "Bạn không có quyền thực hiện thao tác này.";
    static final int REVIEW_PAGE = 10;
    static final int HELPFUL_LIKES = 10;
    static final int ASVSJ_SEARCH_LIMIT = 8;
    static final int EVENT_DAYS_MAX = 30;
    /** Tokyo Station: where the map opens when the viewer has no area. */
    static final MapCenter TOKYO = new MapCenter(35.681236, 139.767125, 12, null);
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final Map<String, String> STAFF_LABELS = Map.of(
            "MODERATOR", "Moderator", "ACADEMIC_MANAGER", "Người quản lý học thuật", "ADMIN", "Quản trị viên");

    private final MapRepository repository;
    private final PlaceRepository places;
    private final PlaceService placeService;
    private final ContentReportService reports;
    private final SystemSettings settings;
    private final Clock clock;
    private final String mediaBaseUrl;
    private final DevLocation devLocation;

    public MapService(MapRepository repository, PlaceRepository places, PlaceService placeService,
                      ContentReportService reports, SystemSettings settings, Clock clock,
                      @Value("${app.media.public-base-url:/media/}") String mediaBaseUrl,
                      @Value("${app.map.dev-location:}") String devLocation) {
        this.repository = repository;
        this.places = places;
        this.placeService = placeService;
        this.reports = reports;
        this.settings = settings;
        this.clock = clock;
        this.mediaBaseUrl = mediaBaseUrl;
        this.devLocation = parseDevLocation(devLocation);
    }

    // ---------------------------------------------------------------- map

    @Transactional(readOnly = true)
    public MapOverview overview(AppUserPrincipal me) {
        Long viewer = me == null ? null : me.id();
        return new MapOverview(repository.categories(), repository.places(viewer), center(viewer), me != null,
                devLocation);
    }

    /** "35.7281,139.7707,Ga Nippori" → the fixed test position; empty or invalid → null (real location). */
    public static DevLocation parseDevLocation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(",", 3);
        try {
            double lat = Double.parseDouble(parts[0].strip());
            double lng = Double.parseDouble(parts[1].strip());
            if (Math.abs(lat) > 90 || Math.abs(lng) > 180) {
                return null;
            }
            return new DevLocation(lat, lng, parts.length > 2 ? parts[2].strip() : "vị trí thử nghiệm");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MapCenter center(Long viewer) {
        if (viewer == null) {
            return TOKYO;
        }
        return repository.areaCenter(viewer)
                .map(a -> a.lat() == null ? new MapCenter(TOKYO.lat(), TOKYO.lng(), TOKYO.zoom(), a.name())
                        : new MapCenter(a.lat(), a.lng(), 15, a.name()))
                .orElse(TOKYO);
    }

    @Transactional(readOnly = true)
    public List<MapEvent> events(int days) {
        int d = days <= 15 ? 15 : EVENT_DAYS_MAX;
        return repository.events(LocalDate.now(clock.withZone(JAPAN)), d);
    }

    /** Categories whose name matches, then places (Senpai ơi first, then OpenStreetMap for signed-in people). */
    public MapSearch search(AppUserPrincipal me, String query) {
        String q = query == null ? "" : query.strip();
        String folded = VietnameseText.squash(q);
        if (folded.isEmpty()) {
            return new MapSearch(List.of(), List.of(), me == null ? "LOGIN" : "ON", null);
        }
        // A category matches at the start of a word: "ga" finds "Ga tàu", not "ngân hàng"
        String typed = VietnameseText.fold(q).strip();
        List<MapCategory> categories = repository.categories().stream()
                .filter(c -> c.count() > 0)
                .filter(c -> (" " + VietnameseText.fold(c.name())).contains(" " + typed))
                .toList();
        if (me == null) {
            List<PlaceOption> own = places.search(q, ASVSJ_SEARCH_LIMIT).stream()
                    .filter(o -> o.lat() != null && o.lng() != null)
                    .toList();
            return new MapSearch(categories, own, "LOGIN", null);
        }
        PlaceSearch found = placeService.search(me.id(), q);
        List<PlaceOption> mappable = found.items().stream()
                .filter(o -> o.lat() != null && o.lng() != null)
                .toList();
        return new MapSearch(categories, mappable, found.external(), found.externalReason());
    }

    /**
     * The places around a point clicked on the map (Senpai ơi places and OpenStreetMap places, nearest first), so any
     * shop, station or park on the map can be opened and reviewed. Signed-in people only: it asks the free map service.
     */
    public MapSearch nearby(AppUserPrincipal me, double lat, double lng) {
        if (me == null) {
            throw ApiException.unauthorized("Hãy đăng nhập để chọn địa điểm trên bản đồ.");
        }
        if (Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            throw ApiException.badRequest("Vị trí không hợp lệ.");
        }
        PlaceSearch found = placeService.nearby(me.id(), lat, lng);
        List<PlaceOption> mappable = found.items().stream()
                .filter(o -> o.lat() != null && o.lng() != null)
                .toList();
        return new MapSearch(List.of(), mappable, found.external(), found.externalReason());
    }

    // ---------------------------------------------------------------- place details

    @Transactional(readOnly = true)
    public PlaceDetail detail(long placeId, AppUserPrincipal me) {
        Long viewer = me == null ? null : me.id();
        PlaceRow row = visible(placeId, viewer);
        Long mergedFrom = row.id() == placeId ? null : placeId;
        long id = row.id();
        var figures = repository.reviewFigures(id);
        boolean canAct = me != null && isUser(me.id());
        List<ReportReason> reasons = reports.reasons().stream()
                .map(r -> new ReportReason(r.id(), r.label()))
                .toList();
        return new PlaceDetail(id, row.nameVi(), row.nameJa(), row.nameEn(), row.address(), row.osmId(), row.lat(),
                row.lng(), row.categoryId(), row.categoryName(), row.categoryIcon(), row.source(), row.status(),
                row.closed(), repository.closedReports(id),
                settings.getInt("closed_report_threshold", 5),
                viewer != null && repository.reportedClosed(id, viewer),
                figures.count(), figures.rating(), repository.scamWarnings(id),
                viewer != null && repository.saved(id, viewer), canAct,
                viewer == null ? null : repository.reviewIdOf(id, viewer), mergedFrom, reasons);
    }

    /**
     * The place as shown to this viewer: a merged place gives the place it was merged into. Removed, rejected and
     * other people's pending places are not shown.
     */
    private PlaceRow visible(long placeId, Long viewer) {
        PlaceRow row = repository.place(placeId).orElseThrow(MapService::notOnMap);
        for (int hops = 0; "MERGED".equals(row.status()) && row.mergedIntoId() != null && hops < 5; hops++) {
            row = repository.place(row.mergedIntoId()).orElseThrow(MapService::notOnMap);
        }
        boolean ownPending = "PENDING".equals(row.status()) && viewer != null && row.createdBy() == viewer;
        if (!"ACTIVE".equals(row.status()) && !ownPending) {
            throw notOnMap();
        }
        return row;
    }

    private static ApiException notOnMap() {
        return new ApiException(HttpStatus.NOT_FOUND, NOT_ON_MAP);
    }

    @Transactional(readOnly = true)
    public ReviewPage reviews(long placeId, int page, AppUserPrincipal me) {
        Long viewer = me == null ? null : me.id();
        long id = visible(placeId, viewer).id();
        int p = Math.max(0, page);
        List<ReviewRow> rows = repository.reviews(id, viewer, p * REVIEW_PAGE, REVIEW_PAGE);
        List<Long> reviewIds = rows.stream().map(ReviewRow::id).toList();
        Map<Long, List<ReviewMedia>> media = repository.media(reviewIds, viewer, mediaBaseUrl);
        Map<Long, List<Tag>> tags = repository.tags(rows.stream().map(ReviewRow::authorId).distinct().toList());
        Set<Long> liked = viewer == null ? Set.of() : repository.likedReviews(reviewIds, viewer);
        List<RankRow> ranks = repository.ranks();
        List<ReviewItem> items = new ArrayList<>();
        for (ReviewRow r : rows) {
            items.add(new ReviewItem(r.id(), author(r, tags, ranks), r.rating(), r.content(),
                    media.getOrDefault(r.id(), List.of()), r.createdAt(), r.edited(), r.status(), r.likeCount(),
                    liked.contains(r.id()), viewer != null && r.authorId() == viewer,
                    r.likeCount() > HELPFUL_LIKES));
        }
        return new ReviewPage(items, p, REVIEW_PAGE, repository.reviewCount(id, viewer));
    }

    private static Author author(ReviewRow r, Map<Long, List<Tag>> tags, List<RankRow> ranks) {
        if (r.authorDeleted()) {
            return new Author(null, "Người dùng đã xóa", null, List.of(), null, null);
        }
        String staff = STAFF_LABELS.get(r.role());
        String rank = staff != null ? null : ranks.stream()
                .filter(k -> r.points() >= k.minPoints())
                .map(RankRow::name)
                .findFirst().orElse(null);
        return new Author(r.authorId(), r.authorName(), r.avatarUrl(), tags.getOrDefault(r.authorId(), List.of()),
                rank, staff);
    }

    @Transactional(readOnly = true)
    public List<PhraseItem> phrases(long placeId, AppUserPrincipal me) {
        return repository.phrases(visible(placeId, me == null ? null : me.id()).categoryId());
    }

    // ---------------------------------------------------------------- actions

    /** Saving works on any place on the map, including the viewer's own pin waiting for approval. */
    @Transactional
    public SaveState save(AppUserPrincipal me, long placeId, boolean on) {
        long userId = member(me);
        long id = visible(placeId, userId).id();
        repository.setSaved(id, userId, on, clock.instant());
        return new SaveState(on);
    }

    /**
     * UC-U20 "Báo đã đóng cửa": one report per user and place. At the threshold (5) the place waits for a Moderator,
     * who alone sets the "Đã đóng cửa" label; the system never sets it by itself.
     */
    @Transactional
    public MapDtos.ClosedState reportClosed(AppUserPrincipal me, long placeId) {
        long userId = member(me);
        PlaceRow place = visible(placeId, userId);
        if (!"ACTIVE".equals(place.status())) {
            throw ApiException.badRequest("Ghim đang chờ duyệt, chưa báo đóng cửa được.");
        }
        if (place.closed()) {
            throw ApiException.badRequest("Địa điểm này đã được Moderator xác nhận đóng cửa.");
        }
        if (!repository.addClosedReport(place.id(), userId, clock.instant())) {
            throw ApiException.conflict("Bạn đã báo địa điểm này rồi.");
        }
        int threshold = settings.getInt("closed_report_threshold", 5);
        int count = repository.closedReports(place.id());
        return new MapDtos.ClosedState(count, threshold, count >= threshold);
    }

    @Transactional
    public LikeState likeReview(AppUserPrincipal me, long reviewId, boolean on) {
        long userId = member(me);
        ReviewTarget review = publishedReview(reviewId);
        if (review.authorId() == userId) {
            throw ApiException.badRequest("Bạn không thể thích review của chính mình.");
        }
        return new LikeState(on, repository.setReviewLike(reviewId, userId, on, clock.instant()));
    }

    /** UC-U11 for reviews: above the threshold the review is hidden until a Moderator decides. */
    @Transactional
    public void reportReview(AppUserPrincipal me, long reviewId, ReportRequest request) {
        long userId = member(me);
        ReviewTarget review = publishedReview(reviewId);
        var outcome = reports.report(new Target("REVIEW", reviewId, review.authorId()), userId,
                request.reasonId(), request.note());
        if (outcome.hide()) {
            repository.hideReview(reviewId);
        }
    }

    private ReviewTarget publishedReview(long reviewId) {
        ReviewTarget review = repository.reviewTarget(reviewId)
                .filter(r -> "PUBLISHED".equals(r.status()) && r.placeId() != null && "ACTIVE".equals(r.placeStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Review này không còn hiển thị."));
        return review;
    }

    private boolean isUser(long userId) {
        return repository.member(userId)
                .filter(m -> "USER".equals(m.role()) && "ACTIVE".equals(m.status()))
                .isPresent();
    }

    private long member(AppUserPrincipal me) {
        if (me == null) {
            throw ApiException.unauthorized("Bạn cần đăng nhập để thực hiện thao tác này.");
        }
        if (!isUser(me.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, NO_PERMISSION);
        }
        return me.id();
    }
}
