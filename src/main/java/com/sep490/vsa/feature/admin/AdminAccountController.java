package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.feature.admin.AdminAccountDtos.AccountDetail;
import com.sep490.vsa.feature.admin.AdminAccountDtos.AccountListOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiếp nhận yêu cầu xem chi tiết tài khoản và dữ liệu thanh lọc của danh sách tài khoản (Admin).
 */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AdminAccountDetailService detailService;

    public AdminAccountController(AdminAccountDetailService detailService) {
        this.detailService = detailService;
    }

    /** Các tag để lọc, bộ đếm Moderator và số ngày "tài khoản mới" cho đầu trang danh sách. */
    @GetMapping("/options")
    public ApiResponse<AccountListOptions> getOptions() {
        return ApiResponse.ok("Lấy dữ liệu bộ lọc thành công", detailService.listOptions());
    }

    /** Chi tiết đầy đủ một tài khoản. */
    @GetMapping("/{id}")
    public ApiResponse<AccountDetail> getDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok("Lấy chi tiết tài khoản thành công", detailService.getDetail(id));
    }
}
