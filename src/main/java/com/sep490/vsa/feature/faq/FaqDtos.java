package com.sep490.vsa.feature.faq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class FaqDtos {
 private FaqDtos() {}
 public record Resource(@NotBlank @Pattern(regexp="GUIDE|BLOG|QUESTION|EXTERNAL") String kind,
   @Positive Long targetId,@Size(max=2000) String url,@NotBlank @Size(max=200) String label) {}
 public record Write(@NotBlank @Size(max=300) String question,@NotBlank @Size(max=20000) String answer,
   @NotBlank @Size(max=30) String topicCode,boolean published,
   @Size(max=20) List<@NotNull @Valid Resource> resources,@Positive Long sourceQuestionId,@Positive Long sourceAnswerId) {}
 public record Entry(long id,String question,String answer,String topicCode,String topicName,boolean published,
   Instant createdAt,Instant updatedAt,List<Resource> resources) {}
 public record Topic(String code,String title,long count) {}
 public record Page<T>(List<T> items,long total,int page,int size,int totalPages) {}
 public record Suggestion(long questionId,long answerId,String question,String answer,int likes,Instant createdAt,Long faqId) {}
 public record Visibility(boolean published) {}
}
