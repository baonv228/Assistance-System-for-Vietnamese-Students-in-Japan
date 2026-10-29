package com.sep490.vsa.feature.guide;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record GuideRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String summary,
        @Size(max = 10) String category,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Section> sections,
        @Size(max = 30) String topicCode,
        @Size(max = 2000) String sourceUrl,
        @Pattern(regexp = "DRAFT|PUBLISHED") String status,
        @Min(0) @Max(4) Integer coverImageIndex,
        boolean removeCover,
        boolean preparationEnabled,
        @Size(max = 100) List<@NotNull @Valid PreparationTask> preparationTasks) {
    public GuideRequest(String title, String summary, String category, List<Section> sections,
                        String topicCode, String sourceUrl, String status, Integer coverImageIndex, boolean removeCover) {
        this(title, summary, category, sections, topicCode, sourceUrl, status, coverImageIndex, removeCover, false, List.of());
    }
    public record PreparationTask(@Positive Long id, @NotBlank @Size(max = 255) String title,
                                  @Size(max = 2000) String notes) {}
    public GuideRequest(String title, String summary, String category, List<Section> sections) {
        this(title, summary, category, sections, "LIVING", null, "PUBLISHED", null, false);
    }
    public boolean publishing() { return !"DRAFT".equals(status); }
    public record Section(@Size(max = 200) String heading,
                          @Size(max = 20000) String content,
                          @Size(max = 2000) String imageUrl,
                          @Size(max = 2000) String referenceUrl,
                          @Min(0) @Max(4) Integer imageIndex,
                          @Positive Long mediaId) {
        public Section(String heading, String content, String imageUrl, String referenceUrl, Integer imageIndex) {
            this(heading, content, imageUrl, referenceUrl, imageIndex, null);
        }
        public Section(String heading, String content, String imageUrl, String referenceUrl) {
            this(heading, content, imageUrl, referenceUrl, null, null);
        }
    }
}
