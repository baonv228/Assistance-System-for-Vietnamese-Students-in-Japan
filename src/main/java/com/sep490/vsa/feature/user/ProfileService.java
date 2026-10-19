package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.saved.SavedRepository;
import com.sep490.vsa.feature.user.ProfileDtos.AreaResponse;
import com.sep490.vsa.feature.user.ProfileDtos.DailyUsage;
import com.sep490.vsa.feature.user.ProfileDtos.ProfileResponse;
import com.sep490.vsa.feature.user.ProfileDtos.RankResponse;
import com.sep490.vsa.feature.user.ProfileDtos.TagResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The "Tôi" page. Tags, ranks and daily usage belong to other features, so they are read with plain SQL
 * here instead of creating entities for them.
 */
@Service
public class ProfileService {

    static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final UserRepository users;
    private final UserAuthProviderRepository providers;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SystemSettings settings;
    private final SavedRepository saved;

    public ProfileService(UserRepository users, UserAuthProviderRepository providers, JdbcTemplate jdbc, Clock clock,
                          SystemSettings settings, SavedRepository saved) {
        this.users = users;
        this.providers = providers;
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.saved = saved;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(Long userId) {
        User user = load(userId);
        Instant now = clock.instant();
        long ageDays = user.getCreatedAt() == null ? 0 : Duration.between(user.getCreatedAt(), now).toDays();

        List<RankResponse> ranks = jdbc.query(
                "SELECT name, min_points, featured_bonus FROM ranks ORDER BY min_points",
                (rs, i) -> new RankResponse(rs.getString(1), rs.getInt(2), rs.getInt(3)));
        List<TagResponse> tags = jdbc.query("""
                SELECT t.name, t.color FROM user_tags ut JOIN tag_types t ON t.id = ut.tag_type_id
                WHERE ut.user_id = ? AND ut.revoked_at IS NULL ORDER BY ut.granted_at""",
                (rs, i) -> new TagResponse(rs.getString(1), rs.getString(2)), userId);
        String pendingTag = jdbc.query("""
                SELECT t.name FROM tag_requests r JOIN tag_types t ON t.id = r.tag_type_id
                WHERE r.user_id = ? AND r.status = 'PENDING' ORDER BY r.submitted_at DESC LIMIT 1""",
                (rs, i) -> rs.getString(1), userId).stream().findFirst().orElse(null);
        String areaName = user.getAreaId() == null ? null : jdbc.query(
                "SELECT display_name FROM areas WHERE id = ?", (rs, i) -> rs.getString(1), user.getAreaId())
                .stream().findFirst().orElse(null);
        int postsPerDay = settings.getInt(SystemSettings.POSTS_PER_DAY, 3);
        int pinsPerDay = settings.getInt(SystemSettings.PINS_PER_DAY, 2);
        int videosPerDay = settings.getInt(SystemSettings.VIDEOS_PER_DAY, 1);
        int newAccountDays = settings.getInt(SystemSettings.NEW_ACCOUNT_DAYS, 7);
        DailyUsage today = jdbc.query(
                "SELECT posts, pins, videos FROM user_daily_usage WHERE user_id = ? AND usage_date = ?",
                (rs, i) -> new DailyUsage(rs.getInt(1), postsPerDay, rs.getInt(2), pinsPerDay,
                        rs.getInt(3), videosPerDay),
                userId, Date.valueOf(LocalDate.now(clock.withZone(JAPAN))))
                .stream().findFirst()
                .orElse(new DailyUsage(0, postsPerDay, 0, pinsPerDay, 0, videosPerDay));
        List<String> linked = jdbc.query("SELECT provider FROM user_auth_providers WHERE user_id = ?",
                (rs, i) -> rs.getString(1), userId);
        Instant muted = user.getReportMutedUntil() != null && user.getReportMutedUntil().isAfter(now)
                ? user.getReportMutedUntil() : null;

        return new ProfileResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getAvatarUrl(),
                user.getRole().name(), user.getRole().label(), user.getPoints(), ranks, tags, pendingTag,
                user.getAreaId(), areaName, user.getStage(), user.getCreatedAt(), ageDays,
                ageDays < newAccountDays, newAccountDays, today, muted,
                user.getPasswordHash() != null, linked, saved.count(userId));
    }

    @Transactional
    public ProfileResponse update(Long userId, Long areaId, String stage) {
        User user = load(userId);
        if (areaId != null && areas().stream().noneMatch(a -> a.id().equals(areaId))) {
            throw ApiException.badRequest("Khu vực không hợp lệ");
        }
        user.updateProfile(areaId, stage == null || stage.isBlank() ? null : stage);
        users.flush();
        return get(userId);
    }

    @Transactional
    public void deleteAccount(Long userId) {
        User user = load(userId);
        providers.deleteByUserId(userId);
        user.anonymize(clock.instant());
    }

    public List<AreaResponse> areas() {
        return jdbc.query("SELECT id, display_name FROM areas WHERE is_active = TRUE ORDER BY sort_order, id",
                (rs, i) -> new AreaResponse(rs.getLong(1), rs.getString(2)));
    }

    private User load(Long userId) {
        return users.findById(userId)
                .filter(u -> u.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> ApiException.unauthorized("Bạn chưa đăng nhập."));
    }
}
