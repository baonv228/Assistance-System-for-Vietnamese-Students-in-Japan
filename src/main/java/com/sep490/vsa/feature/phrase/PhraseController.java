package com.sep490.vsa.feature.phrase;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.phrase.PhraseDtos.FlashRequest;
import com.sep490.vsa.feature.phrase.PhraseDtos.Marks;
import com.sep490.vsa.feature.phrase.PhraseDtos.Overview;
import com.sep490.vsa.feature.phrase.PhraseDtos.PhraseCard;
import com.sep490.vsa.feature.phrase.PhraseDtos.ReportRequest;
import com.sep490.vsa.feature.phrase.PhraseDtos.SaveState;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Nhật ngữ" (page /phrases). GETs are public (the Member's own lists need login); the actions need a signed-in
 * Member, checked in {@link PhraseService}.
 */
@RestController
public class PhraseController {

    private final PhraseService service;

    public PhraseController(PhraseService service) {
        this.service = service;
    }

    @GetMapping("/api/phrases/overview")
    public ApiResponse<Overview> overview(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.overview(me));
    }

    /** {@code mine}: learning ("Chưa thuộc"), learned ("Đã thuộc") or saved ("Đã lưu"). */
    @GetMapping("/api/phrases")
    public ApiResponse<List<PhraseCard>> list(@RequestParam(required = false) String topic,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String mine,
                                              @RequestParam(required = false) String q,
                                              @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.list(me, topic, category, mine, q));
    }

    @PostMapping("/api/phrases/{id:\\d+}/flash")
    public ApiResponse<Marks> flash(@PathVariable long id, @Valid @RequestBody FlashRequest request,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok(request.learned() ? "Đã lưu: Đã thuộc." : "Đã lưu: Chưa thuộc.",
                service.flash(me, id, request.learned()));
    }

    @DeleteMapping("/api/phrases/{id:\\d+}/flash")
    public ApiResponse<Marks> clearFlash(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã bỏ đánh dấu.", service.clearFlash(me, id));
    }

    @PostMapping("/api/phrases/{id:\\d+}/save")
    public ApiResponse<SaveState> save(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã lưu.", service.save(me, id, true));
    }

    @DeleteMapping("/api/phrases/{id:\\d+}/save")
    public ApiResponse<SaveState> unsave(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã bỏ lưu.", service.save(me, id, false));
    }

    @PostMapping("/api/phrases/{id:\\d+}/report")
    public ApiResponse<Void> reportPhrase(@PathVariable long id, @Valid @RequestBody ReportRequest request,
                                          @AuthenticationPrincipal AppUserPrincipal me) {
        service.reportPhrase(me, id, request);
        return ApiResponse.ok("Đã gửi báo cáo cho Biên tập viên nội dung tiếng Nhật.", null);
    }
}
