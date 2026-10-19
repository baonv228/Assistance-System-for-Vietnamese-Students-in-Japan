package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.feature.admin.AdminDtos.ActivityLogItem;
import com.sep490.vsa.feature.admin.AdminDtos.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller tiếp nhận yêu cầu tra cứu nhật ký kiểm toán hoạt động hệ thống.
 */
@RestController
@RequestMapping("/api/admin/logs")
public class AdminActivityLogController {

    private final ActivityLogService logService;

    public AdminActivityLogController(ActivityLogService logService) {
        this.logService = logService;
    }

    /**
     * Lấy danh sách nhật ký kiểm toán có phân trang và lọc theo loại thao tác.
     */
    @GetMapping
    public ApiResponse<PageResult<ActivityLogItem>> getLogs(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "action", required = false) String action) {
        PageResult<ActivityLogItem> result = logService.getLogs(page, size, action);
        return ApiResponse.ok("Lấy nhật ký kiểm toán thành công", result);
    }
}
