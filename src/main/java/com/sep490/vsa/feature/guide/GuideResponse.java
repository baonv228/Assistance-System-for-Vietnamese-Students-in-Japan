package com.sep490.vsa.feature.guide;
import java.time.Instant;
import java.util.List;
public record GuideResponse(Long id, String title, String summary, String category, String topicName,
 boolean published, Long createdBy, String creatorName, Instant createdAt, List<GuideRequest.Section> sections) {}
