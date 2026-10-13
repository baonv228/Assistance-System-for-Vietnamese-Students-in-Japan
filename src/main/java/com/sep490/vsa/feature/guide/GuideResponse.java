package com.sep490.vsa.feature.guide;

public record GuideResponse(
        Long id,
        String title,
        String summary,
        String category
) {
    static GuideResponse from(Guide guide) {
        return new GuideResponse(guide.getId(), guide.getTitle(), guide.getSummary(), guide.getCategory());
    }
}
