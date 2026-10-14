package com.sep490.vsa.feature.admin;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.admin.AdminDtos.BannedKeywordItem;
import com.sep490.vsa.feature.admin.AdminDtos.CreateBannedKeywordRequest;
import com.sep490.vsa.feature.admin.AdminDtos.SystemSettingItem;
import com.sep490.vsa.feature.admin.AdminDtos.UpdateSettingRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller tiếp nhận yêu cầu về cấu hình hệ thống và danh mục từ khóa cấm.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {

    private final AdminConfigService configService;

    public AdminConfigController(AdminConfigService configService) {
        this.configService = configService;
    }

    /**
     * Lấy toàn bộ danh sách các tham số cấu hình hệ thống.
     */
    @GetMapping("/settings")
    public ApiResponse<List<SystemSettingItem>> getSettings() {
        List<SystemSettingItem> settings = configService.getAllSettings();
        return ApiResponse.ok("Lấy danh sách cấu hình thành công", settings);
    }

    /**
     * Cập nhật giá trị của một tham số cấu hình hệ thống.
     */
    @PutMapping("/settings/{key}")
    public ApiResponse<Void> updateSetting(
            @PathVariable("key") String key,
            @RequestBody UpdateSettingRequest request,
            @AuthenticationPrincipal AppUserPrincipal me) {
        configService.updateSetting(key, request.settingValue(), me);
        return ApiResponse.ok("Cập nhật cấu hình thành công", null);
    }

    /**
     * Lấy danh sách từ khóa cấm (có thể tìm kiếm).
     */
    @GetMapping("/keywords")
    public ApiResponse<List<BannedKeywordItem>> getBannedKeywords(@RequestParam(value = "search", required = false) String search) {
        List<BannedKeywordItem> keywords = configService.getBannedKeywords(search);
        return ApiResponse.ok("Lấy danh sách từ khóa cấm thành công", keywords);
    }

    /**
     * Thêm từ khóa cấm mới vào danh mục kiểm duyệt.
     */
    @PostMapping("/keywords")
    public ApiResponse<BannedKeywordItem> addBannedKeyword(
            @RequestBody CreateBannedKeywordRequest request,
            @AuthenticationPrincipal AppUserPrincipal me) {
        BannedKeywordItem item = configService.addBannedKeyword(request.keyword(), me);
        return ApiResponse.ok("Thêm từ khóa cấm thành công", item);
    }

    /**
     * Xóa một từ khóa cấm khỏi danh mục kiểm duyệt.
     */
    @DeleteMapping("/keywords/{id}")
    public ApiResponse<Void> deleteBannedKeyword(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal AppUserPrincipal me) {
        configService.deleteBannedKeyword(id, me);
        return ApiResponse.ok("Xóa từ khóa cấm thành công", null);
    }
}
