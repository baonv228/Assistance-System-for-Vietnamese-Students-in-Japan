package com.sep490.vsa.feature.user;

import java.time.Instant;
import java.util.List;

/** Bodies of /api/users/{id}: what anyone may see of a member. Never the email, area, stage or exact points. */
public final class PublicProfileDtos {

    private PublicProfileDtos() {
    }

    public record Tag(String name, String color) {
    }

    /**
     * @param rankName      the rank of a Member, null for staff
     * @param roleLabel     the role of a staff account instead of a rank (UC-U07 AF3), null for a Member
     * @param likesReceived likes on everything of theirs that is still shown
     * @param self          the viewer is this member
     * @param canMessage    the viewer is a Member (or a guest, who signs in first) and this member takes messages
     */
    public record PublicProfile(long id, String displayName, String avatarUrl, List<Tag> tags, String rankName,
                                String roleLabel, Instant joinedAt, long reviewCount, long postCount,
                                long questionCount, long bestAnswerCount, long likesReceived, boolean self,
                                boolean canMessage) {
    }

    /** A review of a place ({@code placeId}) or an event ({@code eventId}); {@code title} is the name of either. */
    public record ReviewLine(long id, Long placeId, Long eventId, String title, String icon, int rating, String excerpt,
                             int likeCount, Instant createdAt) {
    }

    /** A post or question; {@code solved} only means something for a question. */
    public record BlogLine(long id, String title, String excerpt, String topicName, int likeCount, int commentCount,
                           boolean solved, Instant createdAt) {
    }

    public record Page<T>(List<T> items, int page, boolean hasMore) {
    }
}
