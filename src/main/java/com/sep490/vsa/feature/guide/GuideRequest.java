package com.sep490.vsa.feature.guide;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record GuideRequest(@NotBlank @Size(max=255) String title,
 @NotBlank @Size(max=2000) String summary, @NotBlank @Size(max=10) String category,
 @NotEmpty @Size(max=50) List<@NotNull @Valid Section> sections) {
 public record Section(@NotBlank @Size(max=200) String heading,
 @NotBlank @Size(max=20000) String content, @Size(max=2000) String imageUrl,
 @Size(max=2000) String referenceUrl, @Min(0) @Max(4) Integer imageIndex) {
  public Section(String heading, String content, String imageUrl, String referenceUrl) {
   this(heading, content, imageUrl, referenceUrl, null);
  }
 }
}
