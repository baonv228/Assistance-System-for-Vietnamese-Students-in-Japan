package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.feature.admin.AdminDtos.DashboardStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller tiếp nhận yêu cầu số liệu thống kê tổng quan dành cho Quản trị viên.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * Lấy các chỉ số thống kê tổng quan và nhật ký hoạt động mới nhất.
     */
    @GetMapping("/stats")
    public ApiResponse<DashboardStats> getStats() {
        DashboardStats stats = dashboardService.getDashboardStats();
        return ApiResponse.ok("Lấy số liệu thống kê thành công", stats);
    }
}
