package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.media.InvalidMediaException;
import com.sep490.vsa.feature.moderation.ImageModerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service
public class GuideUploadService {
    public record Image(MediaService.Prepared file, String engine, String scanDetail) {}

    private final GuideService guides;
    private final MediaService media;
    private final ImageModerator moderator;

    public GuideUploadService(GuideService guides, MediaService media, ImageModerator moderator) {
        this.guides = guides;
        this.media = media;
        this.moderator = moderator;
    }

    // Decode and scan before opening the write transaction. Failed uploads leave no files or rows.
    public GuideResponse create(GuideRequest request, List<MultipartFile> files, AppUserPrincipal me) {
        if (me == null || !"MODERATOR".equals(me.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Chỉ Moderator được tạo cẩm nang.");
        }
        files = files == null ? List.of() : files;
        if (files.size() > 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tối đa 5 ảnh, mỗi ảnh JPG/PNG không quá 20 MB.");
        }
        var used = new HashSet<Integer>();
        for (var section : request.sections()) {
            if (section.imageUrl() != null && !section.imageUrl().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Vui lòng chọn ảnh từ máy tính.");
            }
            if (section.imageIndex() != null && (section.imageIndex() >= files.size()
                    || !used.add(section.imageIndex()))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh đính kèm không khớp với mục hướng dẫn.");
            }
        }
        if (used.size() != files.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mỗi ảnh phải được gắn với một mục hướng dẫn.");
        }
        List<Image> prepared = new ArrayList<>();
        for (var file : files) {
            MediaService.Prepared image;
            try {
                image = media.prepareImage(file);
            } catch (InvalidMediaException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            ImageModerator.Verdict verdict;
            try {
                verdict = moderator.check(image.bytes());
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Chưa kiểm tra được ảnh. Bản nháp vẫn được giữ, vui lòng thử lại sau.");
            }
            if ("FLAG".equals(verdict.result())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Ảnh không phù hợp. Vui lòng chọn ảnh khác.");
            }
            if (!"PASS".equals(verdict.result())) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Chưa kiểm tra được ảnh. Bản nháp vẫn được giữ, vui lòng thử lại sau.");
            }
            prepared.add(new Image(image, moderator.engine(), verdict.detail()));
        }
        return guides.createWithImages(request, me, prepared);
    }
}
