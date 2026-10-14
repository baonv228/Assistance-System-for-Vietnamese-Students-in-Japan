package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.AdminDtos.PageResult;
import com.sep490.vsa.feature.admin.AdminDtos.UpdateUserRoleRequest;
import com.sep490.vsa.feature.admin.AdminDtos.UpdateUserStatusRequest;
import com.sep490.vsa.feature.admin.AdminDtos.UserItem;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller xử lý các yêu cầu liên quan đến quản lý người dùng và phân quyền quản trị.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService userService;

    public AdminUserController(AdminUserService userService) {
        this.userService = userService;
    }

    /**
     * Lấy danh sách người dùng có tìm kiếm, lọc theo vai trò, trạng thái và phân trang.
     */
    @GetMapping
    public ApiResponse<PageResult<UserItem>> getUsers(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "15") int size) {
        PageResult<UserItem> result = userService.getUsers(keyword, role, status, page, size);
        return ApiResponse.ok("Lấy danh sách người dùng thành công", result);
    }

    /**
     * Lấy thông tin chi tiết một người dùng theo ID.
     */
    @GetMapping("/{id}")
    public ApiResponse<UserItem> getUser(@PathVariable("id") Long id) {
        UserItem user = userService.getUser(id);
        return ApiResponse.ok("Lấy thông tin người dùng thành công", user);
    }

    /**
     * Cập nhật vai trò (phân quyền) cho người dùng.
     */
    @PutMapping("/{id}/role")
    public ApiResponse<Void> updateUserRole(
            @PathVariable("id") Long id,
            @RequestBody UpdateUserRoleRequest request,
            @AuthenticationPrincipal AppUserPrincipal me) {
        userService.updateUserRole(id, request.role(), me);
        return ApiResponse.ok("Cập nhật vai trò người dùng thành công", null);
    }

    /**
     * Cập nhật trạng thái người dùng (Khóa / Mở khóa tài khoản).
     */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateUserStatus(
            @PathVariable("id") Long id,
            @RequestBody UpdateUserStatusRequest request,
            @AuthenticationPrincipal AppUserPrincipal me) {
        userService.updateUserStatus(id, request.status(), request.reason(), me);
        return ApiResponse.ok("Cập nhật trạng thái người dùng thành công", null);
    }
}
