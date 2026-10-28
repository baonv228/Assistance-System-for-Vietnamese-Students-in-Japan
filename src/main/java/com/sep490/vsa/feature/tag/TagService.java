package com.sep490.vsa.feature.tag;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.MediaService.Prepared;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.tag.TagDtos.Done;
import com.sep490.vsa.feature.tag.TagDtos.PendingRequest;
import com.sep490.vsa.feature.tag.TagDtos.ProofType;
import com.sep490.vsa.feature.tag.TagDtos.RequestDetail;
import com.sep490.vsa.feature.tag.TagDtos.RequestPage;
import com.sep490.vsa.feature.tag.TagDtos.Submitted;
import com.sep490.vsa.feature.tag.TagDtos.TagCompose;
import com.sep490.vsa.feature.tag.TagDtos.TagOption;
import com.sep490.vsa.feature.tag.TagDtos.TagRequestForm;
import com.sep490.vsa.feature.tag.TagRepository.RequestState;
import com.sep490.vsa.feature.tag.TagRepository.TagType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identity tags (Kouhai, Mentor, Người Nhật…), asked for with a proof photo and granted by a Moderator.
 * <ul>
 *   <li>UC-U09: one waiting request per user; the photo (student card, residence card…) is re-encoded like any photo
 *       (EXIF/GPS dropped) and stored under {@code tagproof/}. It is never sent to the image scanner (an identity
 *       document must not leave the server) and is never public: only its owner and, through the Moderator API, a
 *       Moderator while the request waits can open it ({@code MediaAccess} serves no tag proof to anyone else).</li>
 *   <li>UC-M01 / UC-M02: the queue (oldest first, 10 a page), the details with the photo, "Duyệt và cấp tag" and
 *       "Từ chối" with a reason. Either way the photo is deleted at once (media row marked deleted, file removed after
 *       commit) and the user is notified; the action goes to the activity log.</li>
 *   <li>AF4: a request still waiting after {@code proof_keep_days} (14) is closed EXPIRED, its photo deleted and the
 *       user told to send again (job every hour).</li>
 * </ul>
 */
@Service
public class TagService {

    private static final Logger log = LoggerFactory.getLogger(TagService.class);

    static final int KEEP_DAYS = 14;
    static final int PAGE_SIZE = 10;
    static final String HANDLED = "Yêu cầu này đã được xử lý.";
    static final List<String> REJECT_REASONS = List.of("Ảnh mờ", "Không đúng loại giấy tờ", "Thông tin không khớp",
            "Không phải người quen của nhóm");
    static final String PHOTO_RULE = "Ảnh phải là JPG, PNG hoặc HEIC và không quá 20 MB.";

    private final TagRepository repository;
    private final MediaService media;
    private final NotificationService notifications;
    private final ActivityLogService activityLog;
    private final SystemSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TagService(TagRepository repository, MediaService media, NotificationService notifications,
                      ActivityLogService activityLog, SystemSettings settings,
                      PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.media = media;
        this.notifications = notifications;
        this.activityLog = activityLog;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- UC-U09

    @Transactional(readOnly = true)
    public TagCompose compose(long userId) {
        return new TagCompose(options(userId), repository.pendingOf(userId).orElse(null), keepDays(), 20);
    }

    /**
     * "Gửi yêu cầu". The photo is prepared (decoded, re-encoded) before the transaction; the checks that need the
     * database run with the users row locked, so two requests sent together cannot both wait.
     */
    public Submitted submit(long userId, TagRequestForm form, MultipartFile photo) {
        if (!"USER".equals(repository.role(userId))) {
            throw ApiException.forbidden("Chỉ người dùng mới xin được tag định danh.");
        }
        TagRequestForm f = form == null ? new TagRequestForm(null, null, null) : form;
        Map<String, String> errors = new LinkedHashMap<>();
        TagType tag = f.tagId() == null ? null : repository.activeTag(f.tagId()).orElse(null);
        if (tag == null) {
            errors.put("tagId", "Hãy chọn một tag.");
        }
        List<ProofType> proofs = tag == null ? List.of() : repository.proofTypes().getOrDefault(tag.id(), List.of());
        Long proofTypeId = null;
        if (tag != null && tag.selfieOnly()) {
            proofTypeId = proofs.isEmpty() ? null : proofs.get(0).id();   // the portrait, no choice to make
        } else if (tag != null) {
            proofTypeId = proofs.stream().map(ProofType::id).filter(id -> id.equals(f.proofTypeId())).findFirst()
                    .orElse(null);
            if (proofTypeId == null) {
                errors.put("proofTypeId", "Hãy chọn loại minh chứng.");
            }
        }
        Prepared prepared = null;
        if (photo == null || photo.isEmpty()) {
            errors.put("photo", tag != null && tag.selfieOnly() ? "Hãy chọn ảnh chân dung." : "Hãy chọn ảnh minh chứng.");
        } else {
            try {
                prepared = media.prepareImage(photo);
            } catch (InvalidMediaException e) {
                errors.put("photo", PHOTO_RULE);
            }
        }
        if (!Boolean.TRUE.equals(f.consent())) {
            errors.put("consent", "Bạn cần đồng ý cho Kiểm duyệt viên xem ảnh.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
        Prepared file = prepared;
        long proofType = proofTypeId;
        return tx.execute(s -> {
            if (!"ACTIVE".equals(repository.lockUser(userId))) {
                throw ApiException.unauthorized("Bạn chưa đăng nhập.");
            }
            if (repository.pendingOf(userId).isPresent()) {
                throw ApiException.conflict("Bạn đang có một yêu cầu tag chờ duyệt.");
            }
            if (repository.ownedTagIds(userId).contains(tag.id())) {
                throw ApiException.conflict("Bạn đã có tag này.");
            }
            Instant now = clock.instant();
            long mediaId = media.save(userId, "tagproof", file);
            repository.insertRequest(userId, tag.id(), proofType, mediaId, now, now.plus(Duration.ofDays(keepDays())));
            PendingRequest pending = repository.pendingOf(userId).orElseThrow();
            return new Submitted("Đã gửi yêu cầu tag.", pending);
        });
    }

    private List<TagOption> options(long userId) {
        Set<Long> owned = repository.ownedTagIds(userId);
        Map<Long, List<ProofType>> proofs = repository.proofTypes();
        return repository.activeTags().stream().map(t -> new TagOption(t.id(), t.name(), t.description(), t.color(),
                t.selfieOnly(), proofs.getOrDefault(t.id(), List.of()), owned.contains(t.id()))).toList();
    }

    // ---------------------------------------------------------------- UC-M01 / UC-M02

    @Transactional(readOnly = true)
    public RequestPage queue(Long tagId, String name, int page) {
        int p = Math.max(0, page);
        Instant now = clock.instant();
        List<TagOption> tags = repository.activeTags().stream().map(t -> new TagOption(t.id(), t.name(),
                t.description(), t.color(), t.selfieOnly(), List.of(), false)).toList();
        return new RequestPage(repository.pending(tagId, name, p * PAGE_SIZE, PAGE_SIZE, now), p, PAGE_SIZE,
                repository.countPending(tagId, name), tags);
    }

    @Transactional(readOnly = true)
    public RequestDetail detail(long id) {
        RequestState r = waiting(id, false);
        return new RequestDetail(r.id(), r.userId(), r.userName(), r.tagName(), r.tagColor(), r.selfieOnly(),
                r.proofType(), r.submittedAt(), r.expiresAt(), TagRepository.daysLeft(r.expiresAt(), clock.instant()),
                "/api/moderator/tag-requests/" + r.id() + "/photo", REJECT_REASONS);
    }

    public record Photo(byte[] bytes, String mimeType) {
    }

    /** The proof photo, only while the request waits (afterwards it is deleted). */
    @Transactional(readOnly = true)
    public Photo photo(long id) {
        RequestState r = waiting(id, false);
        MediaService.MediaRow row = r.mediaId() == null ? null : media.find(r.mediaId()).filter(m -> !m.deleted())
                .orElse(null);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Không còn ảnh minh chứng.");
        }
        return new Photo(media.read(row.storageKey()), "image/jpeg");
    }

    @Transactional
    public Done approve(long moderatorId, long id) {
        RequestState r = waiting(id, true);
        Instant now = clock.instant();
        if (!repository.close(id, "APPROVED", null, moderatorId, now)) {
            throw ApiException.conflict(HANDLED);
        }
        repository.grant(r.userId(), r.tagId(), id, moderatorId, now);
        discardPhoto(r);
        notifications.send(r.userId(), "TAG_GRANTED", "Bạn đã được cấp tag " + r.tagName() + ".", "TAG", id);
        activityLog.recordLog(moderatorId, "TAG_APPROVED", "USER", r.userId(), Map.of("status", "PENDING"),
                Map.of("status", "APPROVED", "tag", r.tagName(), "request", id),
                "Cấp tag " + r.tagName() + " cho " + r.userName());
        return new Done("Đã cấp tag " + r.tagName() + " cho " + r.userName() + ".");
    }

    @Transactional
    public Done reject(long moderatorId, long id, String reason) {
        if (reason == null || !REJECT_REASONS.contains(reason)) {
            throw ApiException.invalid("Hãy chọn lý do từ chối.", Map.of("reason", "Hãy chọn lý do từ chối."));
        }
        RequestState r = waiting(id, true);
        Instant now = clock.instant();
        if (!repository.close(id, "REJECTED", reason, moderatorId, now)) {
            throw ApiException.conflict(HANDLED);
        }
        discardPhoto(r);
        notifications.send(r.userId(), "TAG_REJECTED", "Yêu cầu tag " + r.tagName() + " bị từ chối: " + reason
                + ". Bạn có thể gửi lại.", "TAG", id);
        activityLog.recordLog(moderatorId, "TAG_REJECTED", "USER", r.userId(), Map.of("status", "PENDING"),
                Map.of("status", "REJECTED", "tag", r.tagName(), "reason", reason, "request", id),
                "Từ chối tag " + r.tagName() + " của " + r.userName() + ": " + reason);
        return new Done("Đã từ chối yêu cầu tag.");
    }

    /** AF4: every hour, requests past their 14 days are closed EXPIRED and their photos deleted. */
    @Scheduled(cron = "0 15 * * * *")
    public void expireOld() {
        for (Long id : repository.expiredIds(clock.instant(), 100)) {
            try {
                tx.executeWithoutResult(s -> expire(id));
            } catch (RuntimeException e) {
                log.warn("Could not expire tag request {}: {}", id, e.getMessage());
            }
        }
    }

    void expire(long id) {
        RequestState r = repository.request(id, true).orElse(null);
        Instant now = clock.instant();
        if (r == null || !"PENDING".equals(r.status()) || r.expiresAt().isAfter(now)
                || !repository.close(id, "EXPIRED", null, null, now)) {
            return;
        }
        discardPhoto(r);
        notifications.send(r.userId(), "TAG_EXPIRED", "Yêu cầu tag " + r.tagName() + " đã quá " + keepDays()
                + " ngày chưa được duyệt nên đã bị hủy và ảnh minh chứng đã được xóa. Bạn có thể gửi lại.", "TAG", id);
    }

    // ---------------------------------------------------------------- helpers

    private RequestState waiting(long id, boolean lock) {
        RequestState r = repository.request(id, lock)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu tag."));
        if (!"PENDING".equals(r.status())) {
            throw ApiException.conflict(HANDLED);
        }
        return r;
    }

    /** The photo is locked at once (row marked deleted) and its file removed after commit (MediaService.discard). */
    private void discardPhoto(RequestState r) {
        if (r.mediaId() != null) {
            media.discard(List.of(r.mediaId()));
        }
    }

    private int keepDays() {
        return Math.max(1, Math.min(60, settings.getInt("proof_keep_days", KEEP_DAYS)));
    }
}
