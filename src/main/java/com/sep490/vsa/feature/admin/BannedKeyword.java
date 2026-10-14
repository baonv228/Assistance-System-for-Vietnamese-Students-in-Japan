package com.sep490.vsa.feature.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Thực thể ánh xạ bảng banned_keywords.
 * Danh sách từ khóa cấm phục vụ bộ lọc tự động kiểm duyệt nội dung.
 */
@Entity
@Table(name = "banned_keywords")
public class BannedKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Từ khóa hoặc cụm từ bị cấm, viết thường, không trùng lặp */
    @Column(name = "keyword", length = 100, nullable = false, unique = true)
    private String keyword;

    /** ID của người dùng thêm từ khóa này (null nếu là dữ liệu khởi tạo) */
    @Column(name = "created_by")
    private Long createdBy;

    /** Thời điểm thêm từ khóa */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public BannedKeyword() {
    }

    public BannedKeyword(String keyword, Long createdBy) {
        this.keyword = keyword;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
