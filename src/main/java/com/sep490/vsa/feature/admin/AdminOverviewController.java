package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.feature.admin.AdminOverviewDtos.Overview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiếp nhận yêu cầu dữ liệu trang "Tổng quan hệ thống" (chỉ đọc).
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminOverviewController {

    private final AdminOverviewService overviewService;

    public AdminOverviewController(AdminOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    /**
     * Số liệu tổng quan. {@code period}: today, last7days (mặc định), last30days hoặc custom (kèm from, to dạng
     * yyyy-MM-dd). Khoảng ngày sai trả 400.
     */
    @GetMapping("/overview")
    public ApiResponse<Overview> getOverview(
            @RequestParam(value = "period", defaultValue = "last7days") String period,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        return ApiResponse.ok("Lấy số liệu tổng quan thành công", overviewService.getOverview(period, from, to));
    }
}
