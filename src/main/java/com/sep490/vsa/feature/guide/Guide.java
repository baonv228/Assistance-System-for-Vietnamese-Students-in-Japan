package com.sep490.vsa.feature.guide;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="guide_items")
public class Guide {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 private String title;
 private String summary;
 @Column(name="stage_code") private String category;
 private boolean published;
 @Column(name = "created_by") private Long createdBy;
 @Column(name = "created_at", insertable = false, updatable = false) private Instant createdAt;
 protected Guide() {}
 public Guide(Long id, String title, String summary, String category, boolean published) {
  this.id = id;
  this.title = title;
  this.summary = summary;
  this.category = category;
  this.published = published;
 }
 public Long getId(){return id;}
 public String getTitle(){return title;}
 public String getSummary(){return summary;}
 public String getCategory(){return category;}
 public boolean isPublished(){return published;}
 public Long getCreatedBy(){return createdBy;}
 public Instant getCreatedAt(){return createdAt;}
}
