package com.sep490.vsa.feature.review;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.common.text.VietnameseText;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.review.ReviewDtos.Duplicate;
import com.sep490.vsa.feature.review.ReviewDtos.PinCompose;
import com.sep490.vsa.feature.review.ReviewDtos.PinRequest;
import com.sep490.vsa.feature.review.ReviewDtos.PinSaved;
import com.sep490.vsa.feature.review.ReviewRepository.Account;
import com.sep490.vsa.feature.review.ReviewRepository.NearPlace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * UC-U19 "Cắm ghim": a place that is not on the map yet, pinned by a User together with the first review.
 * <ul>
 *   <li>At least one name (Japanese or English), a category, a position; the first review follows the review rules
 *       (stars, 10–2,000 characters, ≤ 5 photos, links, banned keywords).</li>
 *   <li>Same name (accents and case ignored, one name may contain the other) within 200 m of an approved place →
 *       "Có thể trùng" (409 with the places) until the user chooses "Vẫn gửi" ({@code confirmDuplicate}).</li>
 *   <li>New accounts: at most {@code pins_per_day} (2) pins a day.</li>
 *   <li>The pin is a place with source USER and status PENDING: only its author sees it until a Moderator approves
 *       it (+15 then). Its first review is stored like any review (+10 when published).</li>
 * </ul>
 */
@Service
public class PinService {

    static final int NAME_MAX = 160;
    static final int ADDRESS_MAX = 255;
    static final double DUPLICATE_M = 200;
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final ReviewRepository repository;
    private final ReviewService reviews;
    private final MediaService media;
    private final ContentFilter filter;
    private final SystemSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PinService(ReviewRepository repository, ReviewService reviews, MediaService media, ContentFilter filter,
                      SystemSettings settings, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.reviews = reviews;
        this.media = media;
        this.filter = filter;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PinCompose compose(AppUserPrincipal me) {
        Account account = reviews.member(me);
        Instant now = clock.instant();
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        return new PinCompose(repository.categories(), reviews.limits(), isNew(account, newDays, now),
                repository.pinsToday(account.id(), LocalDate.ofInstant(now, JAPAN)),
                settings.getInt(SystemSettings.PINS_PER_DAY, 2), newDays);
    }

    /** Approved places within 200 m whose name looks like one of the pin's names, nearest first. */
    @Transactional(readOnly = true)
    public List<Duplicate> duplicates(double lat, double lng, List<String> names) {
        List<String> keys = names.stream().filter(Objects::nonNull).map(PinService::key)
                .filter(k -> k.length() > 1).toList();
        if (keys.isEmpty()) {
            return List.of();
        }
        List<Duplicate> found = new ArrayList<>();
        for (NearPlace p : repository.activeAround(lat, lng, DUPLICATE_M)) {
            double m = metres(lat, lng, p.lat(), p.lng());
            boolean similar = Stream.of(p.nameVi(), p.nameJa(), p.nameEn()).filter(Objects::nonNull).map(PinService::key)
                    .filter(n -> n.length() > 1)
                    .anyMatch(n -> keys.stream().anyMatch(k -> n.contains(k) || k.contains(n)));
            if (m <= DUPLICATE_M && similar) {
                found.add(new Duplicate(p.id(), p.nameVi(), p.nameJa(), p.categoryName(), (int) Math.round(m)));
            }
        }
        found.sort(Comparator.comparingInt(Duplicate::distance));
        return found;
    }

    public PinSaved create(AppUserPrincipal me, PinRequest request, List<MultipartFile> images) {
        Account account = reviews.member(me);
        PinRequest r = request == null ? new PinRequest(null, null, null, null, null, null, null, null, null, null)
                : request;
        Map<String, String> errors = new LinkedHashMap<>();
        String nameJa = clean(r.nameJa(), NAME_MAX, "nameJa", "Tên tối đa 160 ký tự.", errors);
        String nameEn = clean(r.nameEn(), NAME_MAX, "nameEn", "Tên tối đa 160 ký tự.", errors);
        String nameVi = clean(r.nameVi(), NAME_MAX, "nameVi", "Tên tối đa 160 ký tự.", errors);
        String address = clean(r.address(), ADDRESS_MAX, "address", "Địa chỉ tối đa 255 ký tự.", errors);
        if (nameJa == null && nameEn == null) {
            errors.put("names", "Hãy nhập ít nhất một tên (tiếng Nhật hoặc tiếng Anh).");
        }
        if (r.categoryId() == null || r.categoryId().isBlank() || !repository.categoryExists(r.categoryId())) {
            errors.put("categoryId", "Hãy chọn danh mục.");
        }
        if (r.lat() == null || r.lng() == null || Math.abs(r.lat()) > 90 || Math.abs(r.lng()) > 180) {
            errors.put("position", "Hãy bấm lên bản đồ để đặt vị trí.");
        }
        Map<String, String> reviewErrors = new LinkedHashMap<>();
        String content = ReviewService.clean(r.rating(), r.content(), reviewErrors);
        if (reviewErrors.containsKey("rating")) {
            errors.put("rating", "Hãy chấm sao cho review đầu tiên.");
        }
        if (reviewErrors.containsKey("content")) {
            errors.put("content", reviewErrors.get("content").replace("Nội dung", "Review đầu tiên"));
        }
        List<MultipartFile> imageFiles = images == null ? List.of()
                : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        List<Prepared> files = reviews.prepare(imageFiles, null, imageFiles.size(), 0, errors);
        ReviewService.throwIf(errors);
        String shown = nameVi != null ? nameVi : nameEn != null ? nameEn : nameJa;
        return tx.execute(s -> save(account, r, shown, nameJa, nameEn, address, content, files));
    }

    private PinSaved save(Account account, PinRequest r, String nameVi, String nameJa, String nameEn, String address,
                          String content, List<Prepared> files) {
        long userId = account.id();
        Stream.of(nameVi, nameJa, nameEn, address).filter(Objects::nonNull).forEach(filter::checkKeywords);
        filter.checkLinks(content, userId);
        filter.checkQrCodes(MediaService.qrCodesOfPhotos(files), userId);
        filter.checkKeywords(content);
        filter.checkQrKeywords(MediaService.qrCodesOfPhotos(files));

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, JAPAN);
        int pins = repository.lockPins(userId, today);
        int newDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        int limit = settings.getInt(SystemSettings.PINS_PER_DAY, 2);
        if (isNew(account, newDays, now) && pins >= limit) {
            throw ApiException.tooManyRequests("Tài khoản mới chỉ được cắm " + limit + " ghim mỗi ngày.");
        }
        if (!Boolean.TRUE.equals(r.confirmDuplicate())
                && !duplicates(r.lat(), r.lng(), Stream.of(nameVi, nameJa, nameEn).toList()).isEmpty()) {
            throw ApiException.conflict("Có thể trùng với địa điểm đã có.");
        }

        long placeId = repository.insertPin(userId, nameVi, nameJa, nameEn, r.categoryId(), address, r.lat(), r.lng(),
                now);
        boolean withMedia = !files.isEmpty();
        long reviewId = repository.insert(userId, placeId, r.rating(), content, withMedia ? "PROCESSING" : "PUBLISHED",
                now);
        int order = 0;
        for (Prepared file : files) {
            repository.linkMedia(reviewId, media.save(userId, "review", file), order++);
        }
        repository.addPin(userId, today);
        reviews.afterSave(userId, reviewId, withMedia);
        return new PinSaved(placeId, reviewId, withMedia ? "PROCESSING" : "PUBLISHED");
    }

    private static String clean(String value, int max, String field, String tooLong, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            errors.put(field, tooLong);
        }
        return v;
    }

    private static boolean isNew(Account account, int newDays, Instant now) {
        return account.createdAt().isAfter(now.minus(Duration.ofDays(newDays)));
    }

    /** A name as compared: no accents, case, spaces or punctuation. */
    static String key(String name) {
        return VietnameseText.squash(name);
    }

    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double x = Math.toRadians(lng2 - lng1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * 6_371_000;
    }
}
