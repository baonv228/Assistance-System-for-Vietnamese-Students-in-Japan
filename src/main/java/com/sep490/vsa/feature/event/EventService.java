package com.sep490.vsa.feature.event;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.event.EventDtos.AlbumPhoto;
import com.sep490.vsa.feature.event.EventDtos.Author;
import com.sep490.vsa.feature.event.EventDtos.EventCard;
import com.sep490.vsa.feature.event.EventDtos.EventDetail;
import com.sep490.vsa.feature.event.EventDtos.EventRequest;
import com.sep490.vsa.feature.event.EventDtos.Media;
import com.sep490.vsa.feature.event.EventDtos.ModEvent;
import com.sep490.vsa.feature.event.EventDtos.ModSaved;
import com.sep490.vsa.feature.event.EventDtos.PlacePick;
import com.sep490.vsa.feature.event.EventDtos.Reason;
import com.sep490.vsa.feature.event.EventDtos.ReviewItem;
import com.sep490.vsa.feature.event.EventDtos.Tag;
import com.sep490.vsa.feature.event.EventDtos.Viewer;
import com.sep490.vsa.feature.event.EventRepository.EventRow;
import com.sep490.vsa.feature.event.EventRepository.RankRow;
import com.sep490.vsa.feature.event.EventRepository.ReviewRow;
import com.sep490.vsa.feature.event.EventRules.CleanEvent;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.report.ContentReportService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Lễ hội và sự kiện": the list and detail for everyone, and UC-M07 for Moderators (post, edit and delete events).
 * <p>
 * Events are posted by Moderators only. An event with a position shows as a pin on the Map while it is happening or
 * when it starts within the window the user picks (15 or 30 days, {@link #pins}); one without a position shows in the
 * list only. Whoever went may review it once it has started: one review per user per year it is held
 * ({@link EventReviewService}). Days are Japan days.
 */
@Service
public class EventService {

    static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    /** MSG19-like answer for an event that does not exist or was deleted. */
    static final String NOT_FOUND = "Không tìm thấy sự kiện. Sự kiện có thể đã bị xóa.";
    /** Photos sent with the detail page; the count tells how many there are in all. */
    static final int ALBUM_MAX = 12;
    /** The widest map window a user can pick ("30 ngày tới"). */
    static final int MAP_WINDOW_MAX = 30;
    private static final int PLACE_PICKS = 8;

    private final EventRepository repository;
    private final ContentReportService reports;
    private final MediaService media;
    private final ActivityLogService activityLog;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EventService(EventRepository repository, ContentReportService reports, MediaService media,
                        ActivityLogService activityLog, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.reports = reports;
        this.media = media;
        this.activityLog = activityLog;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), JAPAN);
    }

    // ---------------------------------------------------------------- list, map pins, detail

    /** Every event, soonest first; the page groups them (đang diễn ra, 30 ngày tới, sau này, đã diễn ra). */
    @Transactional(readOnly = true)
    public List<EventCard> list(String q) {
        String keyword = EventRules.strip(q);
        if (keyword != null && keyword.length() > 100) {
            keyword = keyword.substring(0, 100);
        }
        Map<Long, double[]> stats = repository.reviewStats();
        LocalDate today = today();
        return repository.events(keyword).stream().map(e -> card(e, stats.get(e.id()), today)).toList();
    }

    /**
     * Pins for the Map: events with a position that are happening now or start within {@code days} (15 or 30, the
     * user's choice; 0 turns event pins off).
     */
    @Transactional(readOnly = true)
    public List<EventCard> pins(int days) {
        if (days <= 0) {
            return List.of();
        }
        int window = Math.min(days, MAP_WINDOW_MAX);
        return list(null).stream()
                .filter(e -> e.lat() != null && !"PAST".equals(e.state()) && e.daysUntil() <= window)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventDetail detail(long eventId, AppUserPrincipal me) {
        EventRow event = repository.event(eventId).orElseThrow(EventService::notFound);
        LocalDate today = today();
        EventCard card = card(event, repository.reviewStats().get(eventId), today);
        Long viewerId = me == null ? null : me.id();
        boolean member = me != null && "USER".equals(me.role());

        List<ReviewRow> rows = repository.reviews(eventId, viewerId);
        Map<Long, List<Media>> mediaByReview = repository.mediaOf(rows.stream().map(ReviewRow::id).toList(), viewerId);
        Map<Long, List<Tag>> tags = repository.tagsOf(rows.stream().map(ReviewRow::authorId).distinct().toList());
        List<RankRow> ranks = repository.ranks();
        Set<Long> liked = viewerId == null ? Set.of() : repository.likedReviews(eventId, viewerId);
        List<ReviewItem> reviews = rows.stream().map(r -> new ReviewItem(r.id(),
                new Author(r.authorId(), r.authorName(), r.avatarUrl(), tags.getOrDefault(r.authorId(), List.of()),
                        rankName(ranks, r.authorPoints())),
                r.rating(), r.content(), r.year(), r.status(), r.edited(), r.createdAt(), r.likeCount(),
                mediaByReview.getOrDefault(r.id(), List.of()), viewerId != null && viewerId.equals(r.authorId()),
                liked.contains(r.id()))).toList();

        Long myReviewId = viewerId == null ? null
                : repository.ownReviewId(eventId, card.reviewYear(), viewerId).orElse(null);
        List<AlbumPhoto> album = repository.album(eventId);
        List<Reason> reasons = reports.reasons().stream().map(r -> new Reason(r.id(), r.label())).toList();
        return new EventDetail(card, event.tip(), album.subList(0, Math.min(album.size(), ALBUM_MAX)), album.size(),
                reviews, new Viewer(me != null, member, myReviewId), reasons);
    }

    // ---------------------------------------------------------------- Moderator (UC-M07)

    @Transactional(readOnly = true)
    public List<ModEvent> moderatorList() {
        Map<Long, double[]> stats = repository.reviewStats();
        LocalDate today = today();
        return repository.events(null).stream()
                .map(e -> new ModEvent(card(e, stats.get(e.id()), today), e.tip(), e.createdByName()))
                .toList();
    }

    /** "Đăng sự kiện". The cover photo is optional; it is checked and re-encoded like any photo before saving. */
    public ModSaved create(long moderatorId, EventRequest body, MultipartFile cover) {
        CleanEvent e = EventRules.cleanEvent(body);
        Prepared coverFile = prepareCover(cover);
        long id = tx.execute(s -> {
            Instant now = clock.instant();
            long eventId = repository.insertEvent(e, moderatorId, now);
            if (coverFile != null) {
                repository.setCover(eventId, saveCover(moderatorId, coverFile));
            }
            activityLog.recordLog(moderatorId, "EVENT_CREATED", "EVENT", eventId, null, values(e),
                    "Đăng sự kiện \"" + e.title() + "\"");
            return eventId;
        });
        return saved(id, "Đã đăng.");
    }

    /** "Sửa sự kiện". A new cover replaces the old one; {@code removeCover} drops it. Reviews are kept. */
    public ModSaved update(long moderatorId, long eventId, EventRequest body, MultipartFile cover) {
        CleanEvent e = EventRules.cleanEvent(body);
        Prepared coverFile = prepareCover(cover);
        boolean removeCover = body != null && Boolean.TRUE.equals(body.removeCover());
        tx.executeWithoutResult(s -> {
            EventRow old = repository.lockEvent(eventId).orElseThrow(EventService::notFound);
            repository.updateEvent(eventId, e, clock.instant());
            if (coverFile != null || removeCover) {
                repository.setCover(eventId, coverFile == null ? null : saveCover(moderatorId, coverFile));
                if (old.coverMediaId() != null) {
                    media.discard(List.of(old.coverMediaId()));
                }
            }
            activityLog.recordLog(moderatorId, "EVENT_UPDATED", "EVENT", eventId, values(old), values(e),
                    "Sửa sự kiện \"" + e.title() + "\"");
        });
        return saved(eventId, "Đã lưu thay đổi.");
    }

    /**
     * "Xóa sự kiện". Soft delete: the event and its reviews are shown nowhere any more (detail answers 404, review
     * photos are no longer served), but no row is removed.
     */
    @Transactional
    public void delete(long moderatorId, long eventId) {
        EventRow event = repository.lockEvent(eventId).orElseThrow(EventService::notFound);
        repository.softDelete(eventId, clock.instant());
        activityLog.recordLog(moderatorId, "EVENT_DELETED", "EVENT", eventId, values(event), null,
                "Xóa sự kiện \"" + event.title() + "\"");
    }

    /** Approved ASVSJ places with a position, so the Moderator can reuse a place's name and position. */
    @Transactional(readOnly = true)
    public List<PlacePick> places(String q) {
        String keyword = EventRules.strip(q);
        if (keyword == null || keyword.length() < 2) {
            return List.of();
        }
        return repository.placesWithPosition(keyword.length() > 100 ? keyword.substring(0, 100) : keyword, PLACE_PICKS);
    }

    private ModSaved saved(long eventId, String prefix) {
        EventRow row = repository.event(eventId).orElseThrow(EventService::notFound);
        EventCard card = card(row, repository.reviewStats().get(eventId), today());
        return new ModSaved(card, publishMessage(card, prefix));
    }

    /** Whether the pin is on the map yet, worded as in the prototype. */
    static String publishMessage(EventCard e, String prefix) {
        if (e.lat() == null) {
            return prefix + " Sự kiện chỉ hiện trong danh sách vì chưa gắn vị trí.";
        }
        if ("PAST".equals(e.state())) {
            return prefix + " Sự kiện đã qua nên ghim không hiện trên bản đồ.";
        }
        if ("NOW".equals(e.state()) || e.daysUntil() <= MAP_WINDOW_MAX) {
            return prefix + " Ghim sự kiện đã hiện trên bản đồ.";
        }
        return prefix + " Ghim sẽ hiện trên bản đồ khi còn " + MAP_WINDOW_MAX + " ngày (bắt đầu sau "
                + e.daysUntil() + " ngày).";
    }

    private Prepared prepareCover(MultipartFile cover) {
        if (cover == null || cover.isEmpty()) {
            return null;
        }
        try {
            return media.prepareImage(cover);
        } catch (InvalidMediaException ex) {
            throw ApiException.invalid(ex.getMessage(), Map.of("cover", ex.getMessage()));
        }
    }

    private long saveCover(long moderatorId, Prepared file) {
        long mediaId = media.save(moderatorId, "event", file);
        repository.markCoverPassed(mediaId);
        return mediaId;
    }

    private static Map<String, Object> values(CleanEvent e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("title", e.title());
        v.put("startDate", e.startDate().toString());
        v.put("endDate", e.endDate().toString());
        v.put("locationName", e.locationName());
        v.put("lat", e.lat());
        v.put("lng", e.lng());
        v.put("costNote", e.costNote());
        v.put("tip", e.tip());
        return v;
    }

    private static Map<String, Object> values(EventRow e) {
        return values(new CleanEvent(e.title(), e.startDate(), e.endDate(), e.locationName(), e.lat(), e.lng(),
                e.costNote(), e.tip()));
    }

    // ---------------------------------------------------------------- helpers

    EventCard card(EventRow e, double[] stat, LocalDate today) {
        String state = e.endDate().isBefore(today) ? "PAST" : e.startDate().isAfter(today) ? "UPCOMING" : "NOW";
        long daysUntil = "UPCOMING".equals(state) ? ChronoUnit.DAYS.between(today, e.startDate()) : 0;
        Double avg = stat == null ? null : Math.round(stat[0] * 10) / 10.0;
        long count = stat == null ? 0 : (long) stat[1];
        return new EventCard(e.id(), e.title(), e.startDate(), e.endDate(), e.locationName(), e.lat(), e.lng(),
                e.costNote(), repository.coverUrl(e), state, daysUntil, e.startDate().getYear(),
                !"UPCOMING".equals(state), avg, count);
    }

    static String rankName(List<RankRow> ranksHighFirst, int points) {
        return ranksHighFirst.stream().filter(r -> points >= r.minPoints()).map(RankRow::name).findFirst().orElse(null);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }
}
