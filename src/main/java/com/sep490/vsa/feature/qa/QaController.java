package com.sep490.vsa.feature.qa;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogActionService;
import com.sep490.vsa.feature.blog.BlogDetailDtos.BlogDetail;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.LikeState;
import com.sep490.vsa.feature.blog.BlogDetailDtos.ReportRequest;
import com.sep490.vsa.feature.blog.BlogDetailDtos.SaveState;
import com.sep490.vsa.feature.blog.BlogDetailService;
import com.sep490.vsa.feature.blog.BlogWriteDtos.ComposeOptions;
import com.sep490.vsa.feature.blog.BlogWriteDtos.CreateRequest;
import com.sep490.vsa.feature.blog.BlogWriteController;
import com.sep490.vsa.feature.blog.BlogWriteDtos.Created;
import com.sep490.vsa.feature.blog.BlogWriteDtos.EditData;
import com.sep490.vsa.feature.blog.BlogWriteDtos.UpdateRequest;
import com.sep490.vsa.feature.blog.BlogWriteService;
import com.sep490.vsa.feature.qa.QaDtos.BestAnswerRequest;
import com.sep490.vsa.feature.qa.QaDtos.Overview;
import com.sep490.vsa.feature.qa.QaDtos.QuestionPage;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Hỏi-Đáp: questions and their answers, a separate section from the Blog.
 * <p>
 * Questions are stored in {@code blogs} with {@code blog_type = 'QA'}, so asking, reading and acting on a
 * question reuse the blog services with kind QA (same rules: checks in order, media scan, daily limit, keywords,
 * reports). An answer is a comment on the question; liking, reporting and deleting an answer use the shared
 * {@code /api/comments/{id}/...} calls. GETs are public; the other calls need a signed-in User.
 */
@RestController
@RequestMapping("/api/questions")
public class QaController {

    static final String KIND = "QA";

    private final QaService qaService;
    private final BlogDetailService detailService;
    private final BlogActionService actionService;
    private final BlogWriteService writeService;

    public QaController(QaService qaService, BlogDetailService detailService, BlogActionService actionService,
                        BlogWriteService writeService) {
        this.qaService = qaService;
        this.detailService = detailService;
        this.actionService = actionService;
        this.writeService = writeService;
    }

    // ---------------------------------------------------------------- list

    /**
     * GET /api/questions?q=&topicIds=1,2&status=ALL|UNANSWERED|UNSOLVED|SOLVED&sort=NEWEST|LIKES|ANSWERS&page=0&size=10
     * ({@code page} starts at 0).
     */
    @GetMapping
    public ApiResponse<QuestionPage> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) List<Long> topicIds,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String sort,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @AuthenticationPrincipal AppUserPrincipal me) {
        QaListQuery query = QaListQuery.of(q, topicIds, status, sort, page, size, me == null ? null : me.id());
        return ApiResponse.ok("OK", qaService.list(query));
    }

    /** Topics for the filter, counts, popular topics and the members who answer most. */
    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.ok("OK", qaService.overview());
    }

    // ---------------------------------------------------------------- ask

    @GetMapping("/compose")
    public ApiResponse<ComposeOptions> compose(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", writeService.compose(me));
    }

    /**
     * Multipart: JSON part "question" ({title, content, topicIds, placeIds, externalPlaceIds}), up to 5 "images",
     * 1 "video". Places are searched with GET /api/places/search (feature/place).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Created> ask(@RequestPart("question") CreateRequest request,
                                    @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                    @RequestPart(value = "video", required = false) MultipartFile video,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        Created created = writeService.create(me, KIND, request, images, video);
        return ApiResponse.ok(askedMessage(created), created);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Created> askJson(@RequestBody CreateRequest request, @AuthenticationPrincipal AppUserPrincipal me) {
        Created created = writeService.create(me, KIND, request, List.of(), null);
        return ApiResponse.ok(askedMessage(created), created);
    }

    private static String askedMessage(Created created) {
        return "PROCESSING".equals(created.status())
                ? "Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy câu hỏi."
                : "Câu hỏi của bạn đã được đăng.";                                             // MSG34
    }

    // ---------------------------------------------------------------- edit ("Sửa câu hỏi")

    @GetMapping("/{id:\\d+}/edit")
    public ApiResponse<EditData> editData(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", writeService.editData(me, KIND, id));
    }

    /** Multipart: JSON part "question" (create fields + keepMediaIds), new "images" and "video". */
    @PutMapping(value = "/{id:\\d+}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Created> update(@PathVariable long id, @RequestPart("question") UpdateRequest request,
                                       @RequestPart(value = "images", required = false) List<MultipartFile> images,
                                       @RequestPart(value = "video", required = false) MultipartFile video,
                                       @AuthenticationPrincipal AppUserPrincipal me) {
        Created saved = writeService.update(me, KIND, id, request, images, video);
        return ApiResponse.ok(BlogWriteController.updatedMessage(saved, "câu hỏi"), saved);
    }

    @PutMapping(value = "/{id:\\d+}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Created> updateJson(@PathVariable long id, @RequestBody UpdateRequest request,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        Created saved = writeService.update(me, KIND, id, request, List.of(), null);
        return ApiResponse.ok(BlogWriteController.updatedMessage(saved, "câu hỏi"), saved);
    }

    // ---------------------------------------------------------------- one question

    @GetMapping("/{id:\\d+}")
    public ApiResponse<BlogDetail> detail(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", detailService.detail(id, me, KIND));
    }

    @DeleteMapping("/{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        actionService.deleteBlog(me, KIND, id);
        return ApiResponse.ok("Đã xóa câu hỏi.", null);
    }

    @PostMapping("/{id:\\d+}/like")
    public ApiResponse<LikeState> like(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", actionService.likeBlog(me, KIND, id, true));
    }

    @DeleteMapping("/{id:\\d+}/like")
    public ApiResponse<LikeState> unlike(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", actionService.likeBlog(me, KIND, id, false));
    }

    @PostMapping("/{id:\\d+}/save")
    public ApiResponse<SaveState> save(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã lưu câu hỏi.", actionService.saveBlog(me, KIND, id, true));
    }

    @DeleteMapping("/{id:\\d+}/save")
    public ApiResponse<SaveState> unsave(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã bỏ lưu câu hỏi.", actionService.saveBlog(me, KIND, id, false));
    }

    @PostMapping("/{id:\\d+}/report")
    public ApiResponse<Void> report(@PathVariable long id, @Valid @RequestBody ReportRequest request,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        actionService.reportBlog(me, KIND, id, request);
        return ApiResponse.ok("Cảm ơn bạn. Báo cáo đã được gửi.", null);                       // MSG38
    }

    // ---------------------------------------------------------------- answers

    /** An answer to the question, or a reply to an answer when {@code parentId} is set. */
    @PostMapping("/{id:\\d+}/answers")
    public ApiResponse<BlogDetail> answer(@PathVariable long id, @RequestBody CommentRequest request,
                                          @AuthenticationPrincipal AppUserPrincipal me) {
        actionService.comment(me, KIND, id, request);
        return ApiResponse.ok(request != null && request.parentId() != null ? "Đã gửi phản hồi." : "Đã đăng câu trả lời.",
                detailService.detail(id, me, KIND));
    }

    /** The asker marks one answer as "Câu trả lời hay nhất" (+5 points to its writer, moved if changed). */
    @PutMapping("/{id:\\d+}/best-answer")
    public ApiResponse<BlogDetail> best(@PathVariable long id, @Valid @RequestBody BestAnswerRequest request,
                                        @AuthenticationPrincipal AppUserPrincipal me) {
        actionService.chooseBest(me, id, request.answerId());
        return ApiResponse.ok("Đã chọn câu trả lời hay nhất.", detailService.detail(id, me, KIND));
    }
}
