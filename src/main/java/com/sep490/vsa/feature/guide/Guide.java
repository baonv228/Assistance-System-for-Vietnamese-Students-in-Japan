package com.sep490.vsa.feature.guide;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Guide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String summary;
    private String category;
    private boolean published;

    protected Guide() {
    }

    public Guide(Long id, String title, String summary, String category, boolean published) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.category = category;
        this.published = published;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getCategory() {
        return category;
    }

    public boolean isPublished() {
        return published;
    }
}
