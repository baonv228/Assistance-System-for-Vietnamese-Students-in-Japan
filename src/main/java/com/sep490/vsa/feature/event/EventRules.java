package com.sep490.vsa.feature.event;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.event.EventDtos.EventRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field rules of the event form (Moderator) and of the event review form. Values marked "cần chốt" are not in the SRS
 * yet; the review limits are the agreed review rules (≤ 2,000 characters, ≤ 5 photos of ≤ 20 MB, one video ≤ 30 s).
 */
final class EventRules {

    // Event form: lengths of the events columns
    static final int TITLE_MAX = 160;
    static final int LOCATION_MAX = 160;
    static final int COST_MAX = 120;
    static final int TIP_MAX = 500;
    /** An event lasts at most this many days (cần chốt): catches a mistyped year. */
    static final int DAYS_MAX = 366;

    // Review form
    static final int CONTENT_MAX = 2000;
    static final int IMAGES_MAX = 5;
    static final int VIDEOS_MAX = 1;

    static final String NO_LOCATION = "Chưa gắn vị trí";
    static final String MAP_LOCATION = "Vị trí trên bản đồ";
    static final String COST_UNKNOWN = "Chưa rõ";

    private EventRules() {
    }

    /** An event form after trimming: empty optional texts become null, a missing place name gets its default. */
    record CleanEvent(String title, LocalDate startDate, LocalDate endDate, String locationName, BigDecimal lat,
                      BigDecimal lng, String costNote, String tip) {

        boolean located() {
            return lat != null;
        }
    }

    /** Checks the Moderator's event form and returns it cleaned. Throws with one message per field. */
    static CleanEvent cleanEvent(EventRequest body) {
        if (body == null) {
            throw ApiException.badRequest("Hãy nhập tên sự kiện.");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String title = strip(body.title());
        if (title == null) {
            errors.put("title", "Hãy nhập tên sự kiện.");
        } else if (title.length() > TITLE_MAX) {
            errors.put("title", "Tên sự kiện tối đa " + TITLE_MAX + " ký tự.");
        }
        if (body.startDate() == null || body.endDate() == null) {
            errors.put("dates", "Hãy chọn ngày bắt đầu và ngày kết thúc.");
        } else if (body.endDate().isBefore(body.startDate())) {
            errors.put("dates", "Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.");
        } else if (body.startDate().plusDays(DAYS_MAX).isBefore(body.endDate())) {
            errors.put("dates", "Sự kiện kéo dài tối đa " + DAYS_MAX + " ngày. Hãy kiểm tra lại năm.");
        }
        BigDecimal lat = body.lat();
        BigDecimal lng = body.lng();
        if ((lat == null) != (lng == null)) {
            errors.put("location", "Vị trí cần đủ vĩ độ và kinh độ, hoặc bỏ vị trí.");
        } else if (lat != null && (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            errors.put("location", "Vị trí không hợp lệ. Hãy bấm lại lên bản đồ.");
        }
        String place = strip(body.locationName());
        if (place != null && place.length() > LOCATION_MAX) {
            errors.put("locationName", "Tên nơi tổ chức tối đa " + LOCATION_MAX + " ký tự.");
        }
        String cost = strip(body.costNote());
        if (cost != null && cost.length() > COST_MAX) {
            errors.put("costNote", "Chi phí tối đa " + COST_MAX + " ký tự.");
        }
        String tip = strip(body.tip());
        if (tip != null && tip.length() > TIP_MAX) {
            errors.put("tip", "Mẹo cho người đi tối đa " + TIP_MAX + " ký tự.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
        boolean located = lat != null;
        return new CleanEvent(title, body.startDate(), body.endDate(),
                place != null ? place : located ? MAP_LOCATION : NO_LOCATION,
                located ? lat.setScale(6, RoundingMode.HALF_UP) : null,
                located ? lng.setScale(6, RoundingMode.HALF_UP) : null,
                cost != null ? cost : COST_UNKNOWN, tip);
    }

    /**
     * Checks the review fields and the number of files. Returns the per-field messages (empty when all is well).
     *
     * @param imageCount photos the review will have (on an edit: kept + new)
     * @param videoCount videos the review will have
     */
    static Map<String, String> checkReview(Integer rating, String content, int imageCount, int videoCount) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (rating == null || rating < 1 || rating > 5) {
            errors.put("rating", "Hãy chọn số sao (1 đến 5).");
        }
        if (content == null) {
            errors.put("content", "Hãy viết vài dòng về sự kiện.");
        } else if (content.length() > CONTENT_MAX) {
            errors.put("content", "Nội dung tối đa 2.000 ký tự (hiện có " + content.length() + ").");
        }
        if (imageCount > IMAGES_MAX) {
            errors.put("images", "Tối đa " + IMAGES_MAX + " ảnh.");
        }
        if (videoCount > VIDEOS_MAX) {
            errors.put("video", "Tối đa " + VIDEOS_MAX + " video.");
        }
        return errors;
    }

    /** Trimmed text (Unicode spaces too), or null when nothing is left. */
    static String strip(String text) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        return s.isEmpty() ? null : s;
    }
}
