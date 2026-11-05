package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.AdminSafetyDtos.SafetyGroup;
import com.sep490.vsa.feature.admin.AdminSafetyDtos.SafetySaveResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Đọc và lưu hai nhóm tham số an toàn (giới hạn tài khoản mới, báo cáo và minh chứng) trên trang cấu hình.
 */
@RestController
@RequestMapping("/api/admin/safety-config")
public class AdminSafetyConfigController {

    private final AdminSafetyConfigService safetyService;

    public AdminSafetyConfigController(AdminSafetyConfigService safetyService) {
        this.safetyService = safetyService;
    }

    /** Giá trị hiện tại của các tham số theo nhóm. */
    @GetMapping
    public ApiResponse<List<SafetyGroup>> getGroups() {
        return ApiResponse.ok("Lấy cấu hình thành công", safetyService.getGroups());
    }

    /** Lưu các ô đã sửa (khóa → số); xem {@link AdminSafetyConfigService#save}. */
    @PutMapping
    public ApiResponse<SafetySaveResult> save(
            @RequestBody Map<String, Object> values,
            @AuthenticationPrincipal AppUserPrincipal me) {
        SafetySaveResult result = safetyService.save(values, me);
        return ApiResponse.ok(result.changed() ? "Đã lưu cấu hình chống spam." : AdminUserController.NO_CHANGE, result);
    }
}
