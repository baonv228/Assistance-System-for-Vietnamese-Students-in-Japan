package com.sep490.vsa.feature.user;

import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

/** Bodies of /api/me (the "Tôi" page); the area picker is feature/area. */
public final class ProfileDtos {

    private ProfileDtos() {
    }

    public record TagResponse(String name, String color) {
    }

    public record RankResponse(String name, int minPoints, int featuredBonus) {
    }

    /** Today's posting counts against the limits for new accounts. */
    public record DailyUsage(int posts, int maxPosts, int pins, int maxPins, int videos, int maxVideos) {
    }

    /** @param savedCount places, posts, questions, guides and phrases listed on "Đã lưu" (/saved) */
    public record ProfileResponse(
            Long id,
            String email,
            String displayName,
            String avatarUrl,
            String role,
            String roleLabel,
            int points,
            List<RankResponse> ranks,
            List<TagResponse> tags,
            String pendingTag,
            Long areaId,
            String areaName,
            String stage,
            Instant createdAt,
            long accountAgeDays,
            boolean newAccount,
            int newAccountDays,
            DailyUsage today,
            Instant reportMutedUntil,
            boolean hasPassword,
            List<String> linkedProviders,
            int savedCount) {
    }

    public record UpdateProfileRequest(
            Long areaId,
            @Pattern(regexp = "PRE|ARR|LEAVE|HOME", message = "Giai đoạn không hợp lệ") String stage) {
    }

    /**
     * {@code PUT /api/me/feed-preferences} ("Điều chỉnh" in "Dành cho bạn" on the Home): area and stage as
     * {@link UpdateProfileRequest}, and {@code topicIds} = the whole new "Chủ đề quan tâm" choice (empty clears it),
     * or null to keep the topics as they are.
     */
    public record UpdateFeedPreferencesRequest(
            Long areaId,
            @Pattern(regexp = "PRE|ARR|LEAVE|HOME", message = "Giai đoạn không hợp lệ") String stage,
            List<Long> topicIds) {
    }

    /** What is saved now; {@code topicIds} = the chosen topics still offered, in the Blog's topic order. */
    public record FeedPreferencesResponse(Long areaId, String areaName, String stage, List<Long> topicIds) {
    }
}
