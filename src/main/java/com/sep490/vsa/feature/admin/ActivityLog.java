package com.sep490.vsa.feature.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Thực thể ánh xạ bảng activity_logs.
 * Lưu trữ nhật ký kiểm toán hành vi của các quản trị viên và thành viên phụ trách (ai làm gì, vào thời điểm nào).
 */
@Entity
@Table(name = "activity_logs")
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID của người thực hiện hành động */
    @Column(name = "actor_id")
    private Long actorId;

    /** Tên hành động (ví dụ: USER_ROLE_CHANGE, USER_STATUS_CHANGE, SETTING_UPDATE...) */
    @Column(name = "action", length = 50, nullable = false)
    private String action;

    /** Loại đối tượng bị tác động (USER, SETTING, BANNED_KEYWORD...) */
    @Column(name = "target_type", length = 20)
    private String targetType;

    /** ID của đối tượng bị tác động (nếu có) */
    @Column(name = "target_id")
    private Long targetId;

    /** Dữ liệu cũ trước khi thay đổi (định dạng chuỗi JSON) */
    @Column(name = "old_value", columnDefinition = "JSON")
    private String oldValue;

    /** Dữ liệu mới sau khi thay đổi (định dạng chuỗi JSON) */
    @Column(name = "new_value", columnDefinition = "JSON")
    private String newValue;

    /** Mô tả tóm tắt hành động bằng tiếng Việt */
    @Column(name = "description", length = 1000)
    private String description;

    /** Thời điểm ghi nhận nhật ký */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public ActivityLog() {
    }

    public ActivityLog(Long actorId, String action, String targetType, Long targetId,
                       String oldValue, String newValue, String description) {
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
