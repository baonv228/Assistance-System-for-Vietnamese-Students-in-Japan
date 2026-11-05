package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.admin.AdminAccountDtos.ModeratorCapacity;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.AccountTiles;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.Overview;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.Period;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.QuotaMeter;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.ReviewDay;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.ReviewTrend;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.StaffCounts;
import com.sep490.vsa.feature.moderation.GeminiMediaReader;
import com.sep490.vsa.feature.place.OsmPlaces;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tổng hợp số liệu cho trang "Tổng quan hệ thống". Chỉ đọc, không ghi bất cứ gì (kể cả nhật ký).
 * <p>
 * Ngày luôn tính theo giờ Nhật (cơ sở dữ liệu lưu UTC). Chỉ biểu đồ review và số báo cáo bị bác phụ thuộc khoảng ngày
 * đã chọn; các ô tài khoản, cấu hình và nhân sự là ảnh chụp hiện tại. Bất kỳ truy vấn nào lỗi thì cả yêu cầu lỗi, để
 * trang hiện một thông báo lỗi chung thay vì số liệu thiếu.
 */
@Service
public class AdminOverviewService {

    static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    static final String INVALID_RANGE =
            "Khoảng ngày không hợp lệ. Ngày bắt đầu phải trước ngày kết thúc và không được ở tương lai.";
    /** Khoảng tùy chọn dài nhất (biểu đồ vẽ mỗi ngày một cột). Cần chốt. */
    static final int MAX_RANGE_DAYS = 366;
    /** Thanh hạn mức chuyển sang cảnh báo khi đã dùng từ chừng này phần trăm. */
    static final int ALERT_PERCENT = 80;
    private static final double GIB = 1024.0 * 1024.0 * 1024.0;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AdminUserService userService;
    private final AdminAccountDetailService accountService;
    private final AdminSafetyConfigService safetyService;

    // Hạn mức miễn phí của dịch vụ ngoài: Google không công bố hạn mức cố định nên đây là mức do nhóm đặt (cần chốt)
    @Value("${app.admin.quota.gemini-generate-per-day:1000}")
    private int geminiGeneratePerDay;
    @Value("${app.admin.quota.gemini-upload-per-day:500}")
    private int geminiUploadPerDay;
    @Value("${app.admin.quota.openai-scan-per-day:5000}")
    private int openAiScanPerDay;
    @Value("${app.admin.quota.photon-per-day:5000}")
    private int photonPerDay;
    @Value("${app.admin.quota.nominatim-per-day:2000}")
    private int nominatimPerDay;
    @Value("${app.admin.quota.storage-gb:10}")
    private int storageGb;

    public AdminOverviewService(JdbcTemplate jdbc, Clock clock, AdminUserService userService,
                                AdminAccountDetailService accountService, AdminSafetyConfigService safetyService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.userService = userService;
        this.accountService = accountService;
        this.safetyService = safetyService;
    }

    /**
     * @param preset today, last7days, last30days hoặc custom
     * @param from   ngày bắt đầu yyyy-MM-dd (bắt buộc khi custom)
     * @param to     ngày kết thúc yyyy-MM-dd (bắt buộc khi custom)
     */
    @Transactional(readOnly = true)
    public Overview getOverview(String preset, String from, String to) {
        LocalDate today = LocalDate.now(clock.withZone(JAPAN));
        Period period = resolvePeriod(preset, from, to, today);
        LocalDate start = LocalDate.parse(period.from());
        LocalDate end = LocalDate.parse(period.to());

        ReviewTrend trend = reviewTrend(start, end);
        boolean empty = trend.totalReviews() == 0 && trend.dismissedReports() == 0;
        return new Overview(period, accountTiles(), trend, quotas(today), safetyService.getGroups(), staff(), empty);
    }

    /**
     * Khoảng ngày theo giờ Nhật, gồm cả hai đầu. Today = hôm nay; 7 ngày và 30 ngày gồm cả hôm nay. Tùy chọn: hai ngày
     * đúng dạng yyyy-MM-dd và có thật, bắt đầu không sau kết thúc, kết thúc không ở tương lai, tối đa
     * {@value #MAX_RANGE_DAYS} ngày. Bắt đầu bằng kết thúc là hợp lệ (một ngày).
     */
    static Period resolvePeriod(String preset, String from, String to, LocalDate today) {
        String key = preset == null ? "last7days" : preset.trim().toLowerCase();
        return switch (key) {
            case "today" -> new Period(key, today.toString(), today.toString());
            case "last7days" -> new Period(key, today.minusDays(6).toString(), today.toString());
            case "last30days" -> new Period(key, today.minusDays(29).toString(), today.toString());
            case "custom" -> {
                LocalDate start = parseDate(from);
                LocalDate end = parseDate(to);
                if (start.isAfter(end) || end.isAfter(today)
                        || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
                    throw ApiException.badRequest(INVALID_RANGE);
                }
                yield new Period(key, start.toString(), end.toString());
            }
            default -> throw ApiException.badRequest(INVALID_RANGE);
        };
    }

    private static LocalDate parseDate(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw ApiException.badRequest(INVALID_RANGE);
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(INVALID_RANGE);
        }
    }

    /** Các ô đếm tài khoản bằng một truy vấn; tài khoản đã xóa không được tính ở đâu cả. */
    private AccountTiles accountTiles() {
        int newDays = userService.newAccountDays();
        Timestamp newSince = Timestamp.from(clock.instant().minus(java.time.Duration.ofDays(newDays)));
        return jdbc.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(role = 'USER' AND created_at > ?), 0),
                       COALESCE(SUM(role = 'USER'), 0),
                       COALESCE(SUM(role = 'MODERATOR'), 0),
                       COALESCE(SUM(role = 'ACADEMIC_MANAGER'), 0),
                       COALESCE(SUM(role = 'ADMIN'), 0),
                       COALESCE(SUM(status = 'LOCKED'), 0)
                FROM users WHERE status <> 'DELETED'""",
                (rs, i) -> new AccountTiles(rs.getLong(1), rs.getLong(2), newDays, rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)), newSince);
    }

    /**
     * Review tạo mỗi ngày (ngày theo giờ Nhật), mọi trạng thái, ngày không có review là 0; và số báo cáo đã được
     * Moderator bác (case KEEP) có thời điểm xử lý trong khoảng.
     */
    private ReviewTrend reviewTrend(LocalDate start, LocalDate end) {
        Timestamp fromUtc = Timestamp.from(start.atStartOfDay(JAPAN).toInstant());
        Timestamp toUtc = Timestamp.from(end.plusDays(1).atStartOfDay(JAPAN).toInstant());

        Map<String, Long> perDay = new HashMap<>();
        jdbc.query("""
                SELECT DATE_FORMAT(CONVERT_TZ(created_at, '+00:00', '+09:00'), '%Y-%m-%d'), COUNT(*)
                FROM reviews WHERE created_at >= ? AND created_at < ?
                GROUP BY 1""",
                rs -> {
                    perDay.put(rs.getString(1), rs.getLong(2));
                }, fromUtc, toUtc);

        List<ReviewDay> days = new ArrayList<>();
        long total = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            long count = perDay.getOrDefault(day.toString(), 0L);
            days.add(new ReviewDay(day.toString(), count));
            total += count;
        }
        Long dismissed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_cases
                WHERE status = 'RESOLVED' AND decision = 'KEEP' AND handled_at >= ? AND handled_at < ?""",
                Long.class, fromUtc, toUtc);
        return new ReviewTrend(days, total, dismissed == null ? 0 : dismissed);
    }

    /** Mức dùng hôm nay (ngày Nhật) của các dịch vụ ngoài và dung lượng tệp đã lưu. */
    private List<QuotaMeter> quotas(LocalDate today) {
        Timestamp dayStart = Timestamp.from(today.atStartOfDay(JAPAN).toInstant());
        Timestamp dayEnd = Timestamp.from(today.plusDays(1).atStartOfDay(JAPAN).toInstant());
        Long scans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM moderation_scans WHERE engine = 'OPENAI' AND created_at >= ? AND created_at < ?",
                Long.class, dayStart, dayEnd);
        Long bytes = jdbc.queryForObject("SELECT COALESCE(SUM(size_bytes), 0) FROM media WHERE deleted_at IS NULL",
                Long.class);

        List<QuotaMeter> meters = new ArrayList<>();
        meters.add(meter("OPENAI_MODERATION", "OpenAI Moderation (quét ảnh)", scans == null ? 0 : scans,
                openAiScanPerDay, "lượt", "ngày"));
        meters.add(meter("GEMINI_GENERATE", "Gemini: đọc chữ trong ảnh/video",
                usageToday(GeminiMediaReader.GENERATE_USAGE, today), geminiGeneratePerDay, "lượt", "ngày"));
        meters.add(meter("GEMINI_FILE_UPLOAD", "Gemini: tải tệp lớn lên",
                usageToday(GeminiMediaReader.UPLOAD_USAGE, today), geminiUploadPerDay, "lượt", "ngày"));
        meters.add(meter("OSM_PHOTON_SEARCH", "OpenStreetMap: tìm địa điểm (Photon)",
                usageToday(OsmPlaces.SEARCH_USAGE, today), photonPerDay, "lượt", "ngày"));
        meters.add(meter("OSM_NOMINATIM_LOOKUP", "OpenStreetMap: tra địa điểm (Nominatim)",
                usageToday(OsmPlaces.LOOKUP_USAGE, today), nominatimPerDay, "lượt", "ngày"));
        meters.add(meter("MEDIA_STORAGE", "Dung lượng ảnh và video đã lưu",
                Math.round((bytes == null ? 0 : bytes) / GIB * 100) / 100.0, storageGb, "GB", "tổng"));
        return meters;
    }

    private double usageToday(String service, LocalDate today) {
        List<Double> rows = jdbc.query("SELECT amount FROM api_usage WHERE service = ? AND usage_date = ?",
                (rs, i) -> rs.getDouble(1), service, java.sql.Date.valueOf(today));
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** Một thanh hạn mức: cảnh báo khi used*100 >= limit*80 (so sánh không làm tròn), độ rộng tối đa 100%. */
    static QuotaMeter meter(String key, String label, double used, double limit, String unit, String window) {
        boolean alert = limit > 0 && used * 100 >= limit * ALERT_PERCENT;
        double percent = limit > 0 ? Math.min(100.0, Math.round(used / limit * 1000) / 10.0) : 0;
        return new QuotaMeter(key, label, used, limit, unit, window, percent, alert);
    }

    /** Nhân sự đang hoạt động theo vai trò; Moderator so với giới hạn cho phép. */
    private StaffCounts staff() {
        ModeratorCapacity moderators = accountService.capacity();
        Map<String, Long> byRole = new HashMap<>();
        jdbc.query("SELECT role, COUNT(*) FROM users WHERE status = 'ACTIVE' AND role IN ('ACADEMIC_MANAGER','ADMIN')"
                + " GROUP BY role", rs -> {
            byRole.put(rs.getString(1), rs.getLong(2));
        });
        return new StaffCounts(moderators, byRole.getOrDefault("ACADEMIC_MANAGER", 0L),
                byRole.getOrDefault("ADMIN", 0L));
    }
}
