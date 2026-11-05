package com.sep490.vsa.feature.admin;

import java.util.List;

/**
 * Dữ liệu của hai nhóm tham số an toàn ("Giới hạn tài khoản mới", "Báo cáo và minh chứng") trên trang cấu hình.
 */
public final class AdminSafetyDtos {

    private AdminSafetyDtos() {
    }

    /**
     * Một tham số. {@code editable} = hệ thống đang đọc tham số này (sửa được trong khoảng {@code min}–{@code max});
     * false = "Chưa áp dụng", chỉ xem.
     */
    public record SafetyParam(
            String key,
            String label,
            String description,
            int value,
            boolean editable,
            Integer min,
            Integer max
    ) {}

    /** Một nhóm tham số hiển thị thành một thẻ. */
    public record SafetyGroup(String code, String title, List<SafetyParam> params) {}

    /** Kết quả lưu: {@code changed} = false khi không ô nào khác giá trị đang lưu. */
    public record SafetySaveResult(boolean changed, List<SafetyGroup> groups) {}
}
