package com.sep490.vsa.feature.scholarship;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDate;

@Entity
public class Scholarship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String description;
    private String provider;
    private LocalDate deadline;
    private boolean active;

    protected Scholarship() {
    }

    public Scholarship(Long id, String title, String description, String provider, LocalDate deadline, boolean active) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.provider = provider;
        this.deadline = deadline;
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getProvider() {
        return provider;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public boolean isActive() {
        return active;
    }
}
