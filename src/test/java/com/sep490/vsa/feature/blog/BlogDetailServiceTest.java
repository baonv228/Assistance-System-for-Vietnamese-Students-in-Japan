package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.BlogDetailDtos.BlogDetail;
import com.sep490.vsa.feature.blog.BlogDetailDtos.CommentNode;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Media;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Viewer;
import com.sep490.vsa.feature.blog.BlogDetailRepository.BlogRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.CommentRow;
import com.sep490.vsa.feature.blog.BlogDetailRepository.RankRow;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.report.ContentReportService;
import com.sep490.vsa.feature.report.ContentReportService.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link BlogDetailService#detail}: UC 1.4 "View Post Details" (Xem chi tiết bài viết, kind POST) and
 * the question-specific parts of UC 1.18 "View Question Details" (Xem chi tiết câu hỏi, kind QA: best answer, order of
 * the answers, a post id opened as a question).
 * <p>
 * The database is not used: {@link BlogDetailRepository}, {@link BlogFeedRepository} (tags, topics, places) and
 * {@link ContentReportService} (report reasons) are mocks. The real {@link CommentTree} builds the comment tree.
 * Rank table of the tests: "Senpai tin cậy" from 300 points, "Người chỉ đường" from 100, "Người mới" from 0.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogDetailService – Xem chi tiết bài viết và câu hỏi (UC 1.4, UC 1.18)")
class BlogDetailServiceTest {

    static final String MSG19 = "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.";

    private static final Instant T0 = Instant.parse("2026-11-01T03:00:00Z");
    private static final long POST_ID = 100L;
    private static final long QUESTION_ID = 200L;
    private static final long AUTHOR_ID = 50L;
    private static final long MEMBER_ID = 10L;
    private static final String BASE = "/media/";

    private static final AppUserPrincipal AUTHOR = principal(AUTHOR_ID, "USER");
    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");
    private static final AppUserPrincipal MODERATOR = principal(20L, "MODERATOR");
    private static final AppUserPrincipal ADMIN = principal(1L, "ADMIN");
    private static final AppUserPrincipal EDITOR = principal(3L, "ACADEMIC_MANAGER");

    private static final List<RankRow> RANKS = List.of(new RankRow("Senpai tin cậy", 300),
            new RankRow("Người chỉ đường", 100), new RankRow("Người mới", 0));
    private static final List<Reason> REASONS = List.of(new Reason(1, "Spam"), new Reason(2, "Lừa đảo"));

    private BlogDetailRepository repository;
    private BlogFeedRepository feed;
    private ContentReportService reports;
    private BlogDetailService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogDetailRepository.class);
        feed = mock(BlogFeedRepository.class);
        reports = mock(ContentReportService.class);
        service = new BlogDetailService(repository, feed, reports, BASE);
        when(repository.ranks()).thenReturn(RANKS);
        when(reports.reasons()).thenReturn(REASONS);
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private static BlogRow blog(long id, String type, String status, String authorStatus, int authorPoints,
                                Long bestCommentId) {
        return new BlogRow(id, AUTHOR_ID, "Lan", "/media/avatar/lan.jpg", authorPoints, authorStatus, type,
                "Kinh nghiệm làm thẻ cư trú", "Mang theo hộ chiếu và ảnh 3x4.", status, true, true, bestCommentId, 3, 2,
                T0);
    }

    private static BlogRow post(String status) {
        return blog(POST_ID, "POST", status, "ACTIVE", 150, null);
    }

    private static BlogRow question(String status, Long best) {
        return new BlogRow(QUESTION_ID, AUTHOR_ID, "Lan", null, 0, "ACTIVE", "QA", "Đi Ikebukuro thế nào?",
                "Mình ở Arakawa.", status, false, false, best, 1, 4, T0);
    }

    /** A comment of the post or question, written {@code minute} minutes after T0, by an active account. */
    private static CommentRow comment(long id, long blogId, Long parent, long author, int points, String status,
                                      int minute) {
        return comment(id, blogId, parent, author, points, status, minute, "ACTIVE");
    }

    private static CommentRow comment(long id, long blogId, Long parent, long author, int points, String status,
                                      int minute, String authorStatus) {
        return new CommentRow(id, blogId, parent, author, "User " + author, null, points, authorStatus,
                "Bình luận " + id, status, false, 0, T0.plus(Duration.ofMinutes(minute)));
    }

    private void givenBlog(BlogRow row) {
        when(repository.findBlog(row.id())).thenReturn(Optional.of(row));
    }

    private static ApiException apiError(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private static void assertNotFound(Runnable call) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo(MSG19);
    }

    @SuppressWarnings("unchecked")
    private List<Long> tagsAskedFor() {
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(feed).tagsOf(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private static List<Long> ids(List<CommentNode> nodes) {
        return nodes.stream().map(CommentNode::id).toList();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. detail(POST) – UC 1.4 Xem chi tiết bài viết (UTC-062 → UTC-095)")
    class ViewPost {

        @Test
        @DisplayName("UTC-062 Khách đọc bài đã đăng: tiêu đề, nội dung, ghim, \"· đã sửa\", lượt thích, số bình luận, thời gian")
        void utc062_guestReadsAPublishedPost() {
            givenBlog(post("PUBLISHED"));

            BlogDetail d = service.detail(POST_ID, null, "POST");

            assertThat(d.id()).isEqualTo(POST_ID);
            assertThat(d.type()).isEqualTo("POST");
            assertThat(d.status()).isEqualTo("PUBLISHED");
            assertThat(d.title()).isEqualTo("Kinh nghiệm làm thẻ cư trú");
            assertThat(d.content()).isEqualTo("Mang theo hộ chiếu và ảnh 3x4.");
            assertThat(d.pinned()).isTrue();
            assertThat(d.edited()).isTrue();
            assertThat(d.likeCount()).isEqualTo(3);
            assertThat(d.commentCount()).isEqualTo(2);
            assertThat(d.createdAt()).isEqualTo(T0);
            assertThat(d.bestCommentId()).isNull();
        }

        @Test
        @DisplayName("UTC-063 Tác giả: tên, ảnh đại diện, tag định danh, hạng \"Người chỉ đường\" (150 điểm)")
        void utc063_authorBlockHasTagsAndRank() {
            Tag tag = new Tag("Du học sinh", "#2E7D32");
            givenBlog(post("PUBLISHED"));
            when(feed.tagsOf(any())).thenReturn(Map.of(AUTHOR_ID, List.of(tag)));

            BlogDetail d = service.detail(POST_ID, null, "POST");

            assertThat(d.author().id()).isEqualTo(AUTHOR_ID);
            assertThat(d.author().displayName()).isEqualTo("Lan");
            assertThat(d.author().avatarUrl()).isEqualTo("/media/avatar/lan.jpg");
            assertThat(d.author().tags()).containsExactly(tag);
            assertThat(d.author().rankName()).isEqualTo("Người chỉ đường");
        }

        @Test
        @DisplayName("UTC-064 Hạng theo điểm: 0, 99 → Người mới; 100, 299 → Người chỉ đường; 300 → Senpai tin cậy (5 lần)")
        void utc064_rankFollowsThePointThresholds() {
            Map<Integer, String> expected = Map.of(0, "Người mới", 99, "Người mới", 100, "Người chỉ đường",
                    299, "Người chỉ đường", 300, "Senpai tin cậy");
            for (var e : expected.entrySet()) {
                givenBlog(blog(POST_ID, "POST", "PUBLISHED", "ACTIVE", e.getKey(), null));
                assertThat(service.detail(POST_ID, null, "POST").author().rankName()).as("%d points", e.getKey())
                        .isEqualTo(e.getValue());
            }
        }

        @Test
        @DisplayName("UTC-065 Bảng hạng trống: mọi người là \"Người mới\"")
        void utc065_emptyRankTableMeansNewcomer() {
            when(repository.ranks()).thenReturn(List.of());
            givenBlog(blog(POST_ID, "POST", "PUBLISHED", "ACTIVE", 999, null));

            assertThat(service.detail(POST_ID, null, "POST").author().rankName()).isEqualTo("Người mới");
        }

        @Test
        @DisplayName("UTC-066 Chủ đề và địa điểm liên quan (địa điểm đã gộp hiện nơi được gộp vào, có tọa độ) lấy từ kho")
        void utc066_topicsAndPlacesComeFromTheRepository() {
            TopicRef topic = new TopicRef(1L, "Giấy tờ");
            PlaceRef kept = new PlaceRef(31L, "Phòng hành chính quận Arakawa", "Cơ quan", "Arakawa, Tokyo", 35.736,
                    139.783, null);
            givenBlog(post("PUBLISHED"));
            when(feed.topicsOf(List.of(POST_ID))).thenReturn(Map.of(POST_ID, List.of(topic)));
            when(feed.placesOf(List.of(POST_ID))).thenReturn(Map.of(POST_ID, List.of(kept)));

            BlogDetail d = service.detail(POST_ID, null, "POST");

            assertThat(d.topics()).containsExactly(topic);
            assertThat(d.places()).containsExactly(kept);
            assertThat(d.places().get(0).lat()).isEqualTo(35.736);
        }

        @Test
        @DisplayName("UTC-067 Bài không có chủ đề, địa điểm, ảnh: các danh sách rỗng")
        void utc067_noTopicsPlacesOrMediaGiveEmptyLists() {
            givenBlog(post("PUBLISHED"));

            BlogDetail d = service.detail(POST_ID, null, "POST");

            assertThat(d.topics()).isEmpty();
            assertThat(d.places()).isEmpty();
            assertThat(d.media()).isEmpty();
        }

        @Test
        @DisplayName("UTC-068 Khách: chỉ ảnh/video đã qua kiểm duyệt (không gồm tệp đang quét)")
        void utc068_guestGetsOnlyPassedMedia() {
            Media photo = new Media(90L, "IMAGE", "/media/blog/2026/11/a.jpg", "image/jpeg", 64, 48);
            givenBlog(post("PUBLISHED"));
            when(repository.media(POST_ID, false, BASE)).thenReturn(List.of(photo));

            assertThat(service.detail(POST_ID, null, "POST").media()).containsExactly(photo);
            verify(repository).media(POST_ID, false, BASE);
        }

        @Test
        @DisplayName("UTC-069 Thành viên khác: chỉ ảnh/video đã qua kiểm duyệt")
        void utc069_otherMemberGetsOnlyPassedMedia() {
            givenBlog(post("PUBLISHED"));

            service.detail(POST_ID, MEMBER, "POST");

            verify(repository).media(POST_ID, false, BASE);
        }

        @Test
        @DisplayName("UTC-070 Tác giả: thấy cả ảnh đang quét của bài mình, theo thứ tự bài")
        void utc070_authorAlsoGetsFilesBeingScanned() {
            Media passed = new Media(90L, "IMAGE", "/media/blog/2026/11/a.jpg", "image/jpeg", 64, 48);
            Media scanning = new Media(91L, "IMAGE", "/media/blog/2026/11/b.jpg", "image/jpeg", 64, 48);
            givenBlog(post("PUBLISHED"));
            when(repository.media(POST_ID, true, BASE)).thenReturn(List.of(passed, scanning));

            assertThat(service.detail(POST_ID, AUTHOR, "POST").media()).containsExactly(passed, scanning);
        }

        @Test
        @DisplayName("UTC-071 Địa chỉ ảnh theo cấu hình app.media.public-base-url")
        void utc071_mediaUseTheConfiguredBase() {
            service = new BlogDetailService(repository, feed, reports, "https://cdn.example/m/");
            givenBlog(post("PUBLISHED"));

            service.detail(POST_ID, null, "POST");

            verify(repository).media(POST_ID, false, "https://cdn.example/m/");
        }

        @Test
        @DisplayName("UTC-072 Lý do báo cáo chung (Spam, Lừa đảo) đi kèm bài cho hộp thoại \"Báo cáo\"")
        void utc072_reportReasonsComeWithThePost() {
            givenBlog(post("PUBLISHED"));

            assertThat(service.detail(POST_ID, MEMBER, "POST").reportReasons()).isEqualTo(REASONS);
        }

        @Test
        @DisplayName("UTC-073 Khách: viewer toàn false, không đọc lượt thích/lưu (nút thao tác dẫn tới đăng nhập)")
        void utc073_guestViewerFlagsAreAllFalse() {
            givenBlog(post("PUBLISHED"));

            Viewer v = service.detail(POST_ID, null, "POST").viewer();

            assertThat(v).isEqualTo(new Viewer(false, false, false, false, false));
            verify(repository, never()).likedBlog(anyLong(), anyLong());
            verify(repository, never()).savedBlog(anyLong(), anyLong());
            verify(repository, never()).likedComments(anyLong(), anyLong());
        }

        @Test
        @DisplayName("UTC-074 Thành viên id=10 đã thích và đã lưu bài: signedIn, canInteract, liked, saved = true")
        void utc074_memberWhoLikedAndSavedThePost() {
            givenBlog(post("PUBLISHED"));
            when(repository.likedBlog(POST_ID, MEMBER_ID)).thenReturn(true);
            when(repository.savedBlog(POST_ID, MEMBER_ID)).thenReturn(true);

            Viewer v = service.detail(POST_ID, MEMBER, "POST").viewer();

            assertThat(v).isEqualTo(new Viewer(true, false, true, true, true));
        }

        @Test
        @DisplayName("UTC-075 Thành viên chưa thích, chưa lưu: liked = false, saved = false, vẫn được thao tác")
        void utc075_memberWhoHasNotLikedOrSaved() {
            givenBlog(post("PUBLISHED"));

            Viewer v = service.detail(POST_ID, MEMBER, "POST").viewer();

            assertThat(v).isEqualTo(new Viewer(true, false, true, false, false));
        }

        @Test
        @DisplayName("UTC-076 Nhân viên (Moderator, Admin, BTV tiếng Nhật) chỉ xem: signedIn = true, canInteract = false (3 lần)")
        void utc076_staffOnlyRead() {
            givenBlog(post("PUBLISHED"));
            for (AppUserPrincipal staff : List.of(MODERATOR, ADMIN, EDITOR)) {
                Viewer v = service.detail(POST_ID, staff, "POST").viewer();
                assertThat(v.signedIn()).as(staff.role()).isTrue();
                assertThat(v.author()).as(staff.role()).isFalse();
                assertThat(v.canInteract()).as(staff.role()).isFalse();
            }
        }

        @Test
        @DisplayName("UTC-077 Tác giả xem bài đã đăng của mình: author = true, canInteract = true")
        void utc077_authorOnOwnPublishedPost() {
            givenBlog(post("PUBLISHED"));

            Viewer v = service.detail(POST_ID, AUTHOR, "POST").viewer();

            assertThat(v.author()).isTrue();
            assertThat(v.canInteract()).isTrue();
        }

        @Test
        @DisplayName("UTC-078 Tác giả mở bài của mình đang quét / bị ẩn / bị từ chối: thấy trạng thái, không thao tác được (3 lần)")
        void utc078_authorOpensOwnUnpublishedPost() {
            for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED")) {
                givenBlog(post(state));
                BlogDetail d = service.detail(POST_ID, AUTHOR, "POST");
                assertThat(d.status()).isEqualTo(state);
                assertThat(d.viewer().author()).as(state).isTrue();
                assertThat(d.viewer().canInteract()).as(state).isFalse();
            }
        }

        @Test
        @DisplayName("UTC-079 Thành viên khác mở bài đang quét / bị ẩn / bị từ chối: 404 MSG19 (3 lần)")
        void utc079_otherMemberCannotOpenUnpublishedPost() {
            for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED")) {
                givenBlog(post(state));
                assertNotFound(() -> service.detail(POST_ID, MEMBER, "POST"));
            }
        }

        @Test
        @DisplayName("UTC-080 Khách và Moderator mở bài đang quét / bị ẩn / bị từ chối: 404 MSG19 (6 lần)")
        void utc080_guestAndModeratorCannotOpenUnpublishedPost() {
            for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED")) {
                givenBlog(post(state));
                assertNotFound(() -> service.detail(POST_ID, null, "POST"));
                assertNotFound(() -> service.detail(POST_ID, MODERATOR, "POST"));
            }
        }

        @Test
        @DisplayName("UTC-081 Bài đã bị Moderator gỡ (REMOVED): 404 MSG19 cho cả tác giả")
        void utc081_removedPostIsNotFoundEvenForItsAuthor() {
            givenBlog(post("REMOVED"));

            assertNotFound(() -> service.detail(POST_ID, AUTHOR, "POST"));
            assertNotFound(() -> service.detail(POST_ID, null, "POST"));
        }

        @Test
        @DisplayName("UTC-082 Bài không tồn tại (id 999): 404 MSG19, không đọc bình luận")
        void utc082_missingPostIsNotFound() {
            when(repository.findBlog(999L)).thenReturn(Optional.empty());

            assertNotFound(() -> service.detail(999L, MEMBER, "POST"));
            verify(repository, never()).comments(anyLong());
            verify(repository, never()).media(anyLong(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("UTC-083 Tác giả bị khóa hoặc đã xóa tài khoản: 404 MSG19 với khách, thành viên và chính tác giả (6 lần)")
        void utc083_postOfALockedOrDeletedAccountIsNotFound() {
            for (String account : List.of("LOCKED", "DELETED")) {
                givenBlog(blog(POST_ID, "POST", "PUBLISHED", account, 150, null));
                assertNotFound(() -> service.detail(POST_ID, null, "POST"));
                assertNotFound(() -> service.detail(POST_ID, MEMBER, "POST"));
                assertNotFound(() -> service.detail(POST_ID, AUTHOR, "POST"));
            }
        }

        @Test
        @DisplayName("UTC-084 Mở mã của một câu hỏi như bài viết: 404 MSG19")
        void utc084_questionIdDoesNotOpenAsAPost() {
            givenBlog(question("PUBLISHED", null));

            assertNotFound(() -> service.detail(QUESTION_ID, MEMBER, "POST"));
        }

        @Test
        @DisplayName("UTC-085 Loại null (sau thao tác bình luận dùng chung): mở được cả bài viết và câu hỏi (2 lần)")
        void utc085_kindNullOpensBothKinds() {
            givenBlog(post("PUBLISHED"));
            givenBlog(question("PUBLISHED", null));

            assertThat(service.detail(POST_ID, MEMBER, null).type()).isEqualTo("POST");
            assertThat(service.detail(QUESTION_ID, MEMBER, null).type()).isEqualTo("QA");
        }

        @Test
        @DisplayName("UTC-086 Bình luận: người viết có tag và hạng, nội dung, \"đã sửa\", lượt thích; trả lời nằm dưới, cũ trước")
        void utc086_commentTreeWithRepliesOldestFirst() {
            Tag tag = new Tag("Senpai", "#1565C0");
            CommentRow first = new CommentRow(301L, POST_ID, null, 11L, "Minh", "/media/avatar/minh.jpg", 120,
                    "ACTIVE", "Bình luận thứ nhất", "PUBLISHED", true, 4, T0.plus(Duration.ofMinutes(1)));
            givenBlog(post("PUBLISHED"));
            when(repository.comments(POST_ID)).thenReturn(List.of(first,
                    comment(302, POST_ID, 301L, 12, 0, "PUBLISHED", 5),
                    comment(303, POST_ID, 301L, 13, 0, "PUBLISHED", 3),
                    comment(304, POST_ID, null, 14, 0, "PUBLISHED", 2)));
            when(feed.tagsOf(any())).thenReturn(Map.of(11L, List.of(tag)));

            List<CommentNode> tree = service.detail(POST_ID, null, "POST").comments();

            assertThat(ids(tree)).containsExactly(301L, 304L);
            CommentNode node = tree.get(0);
            assertThat(node.author().displayName()).isEqualTo("Minh");
            assertThat(node.author().tags()).containsExactly(tag);
            assertThat(node.author().rankName()).isEqualTo("Người chỉ đường");
            assertThat(node.content()).isEqualTo("Bình luận thứ nhất");
            assertThat(node.edited()).isTrue();
            assertThat(node.likeCount()).isEqualTo(4);
            assertThat(ids(node.replies())).containsExactly(303L, 302L);
        }

        @Test
        @DisplayName("UTC-087 Trên bài viết, bình luận của \"Senpai tin cậy\" không được đưa lên trước")
        void utc087_senpaiCommentIsNotMovedUpOnAPost() {
            givenBlog(post("PUBLISHED"));
            when(repository.comments(POST_ID)).thenReturn(List.of(
                    comment(301, POST_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, POST_ID, null, 12, 450, "PUBLISHED", 2)));

            assertThat(ids(service.detail(POST_ID, null, "POST").comments())).containsExactly(301L, 302L);
        }

        @Test
        @DisplayName("UTC-088 Bình luận bị gỡ còn trả lời: chỗ trống (không người viết, không nội dung); bị ẩn không trả lời: không hiện")
        void utc088_removedCommentWithRepliesBecomesAPlaceholder() {
            givenBlog(post("PUBLISHED"));
            when(repository.comments(POST_ID)).thenReturn(List.of(
                    comment(301, POST_ID, null, 11, 0, "HIDDEN", 1),
                    comment(302, POST_ID, null, 12, 0, "REMOVED", 2),
                    comment(303, POST_ID, 302L, 13, 0, "PUBLISHED", 3)));

            List<CommentNode> tree = service.detail(POST_ID, null, "POST").comments();

            assertThat(ids(tree)).containsExactly(302L);
            CommentNode placeholder = tree.get(0);
            assertThat(placeholder.removed()).isTrue();
            assertThat(placeholder.author()).isNull();
            assertThat(placeholder.content()).isEmpty();
            assertThat(ids(placeholder.replies())).containsExactly(303L);
        }

        @Test
        @DisplayName("UTC-089 Bình luận của tài khoản bị khóa / đã xóa không hiện (còn trả lời thì thành chỗ trống)")
        void utc089_commentsOfLockedOrDeletedAccountsAreHidden() {
            givenBlog(post("PUBLISHED"));
            when(repository.comments(POST_ID)).thenReturn(List.of(
                    comment(301, POST_ID, null, 11, 0, "PUBLISHED", 1, "LOCKED"),
                    comment(302, POST_ID, null, 12, 0, "PUBLISHED", 2, "DELETED"),
                    comment(303, POST_ID, 302L, 13, 0, "PUBLISHED", 3),
                    comment(304, POST_ID, null, 14, 0, "PUBLISHED", 4)));

            List<CommentNode> tree = service.detail(POST_ID, MEMBER, "POST").comments();

            assertThat(ids(tree)).containsExactly(302L, 304L);
            assertThat(tree.get(0).removed()).isTrue();
            assertThat(tree.get(1).removed()).isFalse();
        }

        @Test
        @DisplayName("UTC-090 Chuỗi 25 bình luận trả lời nối nhau: hiện tối đa 20 tầng, các trả lời sâu hơn nằm dưới tầng 20")
        void utc090_deepReplyChainIsCappedAtTwentyLevels() {
            givenBlog(post("PUBLISHED"));
            List<CommentRow> chain = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                chain.add(comment(400 + i, POST_ID, i == 0 ? null : (long) (399 + i), 11, 0, "PUBLISHED", i));
            }
            when(repository.comments(POST_ID)).thenReturn(chain);

            List<CommentNode> tree = service.detail(POST_ID, null, "POST").comments();

            assertThat(ids(tree)).containsExactly(400L);
            CommentNode node = tree.get(0);
            for (int depth = 1; depth <= 20; depth++) {
                assertThat(node.replies()).hasSize(1);
                node = node.replies().get(0);
            }
            assertThat(node.id()).isEqualTo(420L);
            assertThat(ids(node.replies())).containsExactly(421L, 422L, 423L, 424L);
            assertThat(node.replies()).allSatisfy(r -> assertThat(r.replies()).isEmpty());
        }

        @Test
        @DisplayName("UTC-091 Thành viên id=10 đã thích bình luận 302: chỉ bình luận đó có liked = true")
        void utc091_commentsTheMemberLikesAreMarked() {
            givenBlog(post("PUBLISHED"));
            when(repository.comments(POST_ID)).thenReturn(List.of(
                    comment(301, POST_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, POST_ID, null, 12, 0, "PUBLISHED", 2)));
            when(repository.likedComments(POST_ID, MEMBER_ID)).thenReturn(Set.of(302L));

            List<CommentNode> tree = service.detail(POST_ID, MEMBER, "POST").comments();

            assertThat(tree).extracting(CommentNode::liked).containsExactly(false, true);
        }

        @Test
        @DisplayName("UTC-092 Bình luận của chính người xem có mine = true, của người khác mine = false")
        void utc092_viewersOwnCommentsAreMarkedMine() {
            givenBlog(post("PUBLISHED"));
            when(repository.comments(POST_ID)).thenReturn(List.of(
                    comment(301, POST_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, POST_ID, null, MEMBER_ID, 0, "PUBLISHED", 2)));

            List<CommentNode> tree = service.detail(POST_ID, MEMBER, "POST").comments();

            assertThat(tree).extracting(CommentNode::mine).containsExactly(false, true);
        }

        @Test
        @DisplayName("UTC-093 Tag được đọc một lần cho tác giả bài và mọi người bình luận")
        void utc093_tagsAreReadForTheAuthorAndEveryCommenter() {
            givenBlog(post("PUBLISHED"));
            when(repository.comments(POST_ID)).thenReturn(List.of(
                    comment(301, POST_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, POST_ID, 301L, 12, 0, "PUBLISHED", 2),
                    comment(303, POST_ID, null, 11, 0, "PUBLISHED", 3)));

            service.detail(POST_ID, null, "POST");

            assertThat(tagsAskedFor()).containsExactlyInAnyOrder(AUTHOR_ID, 11L, 12L);
        }

        @Test
        @DisplayName("UTC-094 Bài chưa có bình luận: danh sách bình luận rỗng")
        void utc094_postWithoutComments() {
            givenBlog(new BlogRow(POST_ID, AUTHOR_ID, "Lan", null, 0, "ACTIVE", "POST", "Bài mới", "x", "PUBLISHED",
                    false, false, null, 0, 0, T0));

            BlogDetail d = service.detail(POST_ID, MEMBER, "POST");

            assertThat(d.comments()).isEmpty();
            assertThat(d.commentCount()).isZero();
        }

        @Test
        @DisplayName("UTC-095 Xem bài không ghi gì: không thích, không lưu, không bình luận, không báo cáo")
        void utc095_viewingWritesNothing() {
            givenBlog(post("PUBLISHED"));

            service.detail(POST_ID, MEMBER, "POST");
            service.detail(POST_ID, null, "POST");

            verify(repository, never()).setBlogLike(anyLong(), anyLong(), anyBoolean(), any());
            verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean(), any());
            verify(repository, never()).insertComment(anyLong(), anyLong(), any(), anyString(), any());
            verify(repository, never()).recountComments(anyLong());
            verify(repository, never()).lockBlog(anyLong());
            verify(reports, never()).report(any(), anyLong(), any(), any());
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. detail(QA) – UC 1.18 Xem chi tiết câu hỏi (UTC-219 → UTC-238)")
    class ViewQuestion {

        private List<CommentNode> answersOf(BlogRow q, List<CommentRow> rows) {
            givenBlog(q);
            when(repository.comments(QUESTION_ID)).thenReturn(rows);
            return service.detail(QUESTION_ID, null, "QA").comments();
        }

        @Test
        @DisplayName("UTC-219 Khách xem câu hỏi: loại QA, tiêu đề, mô tả, người hỏi \"Người mới\", lượt thích, lý do báo cáo")
        void utc219_guestReadsAQuestion() {
            givenBlog(question("PUBLISHED", null));

            BlogDetail q = service.detail(QUESTION_ID, null, "QA");

            assertThat(q.type()).isEqualTo("QA");
            assertThat(q.status()).isEqualTo("PUBLISHED");
            assertThat(q.title()).isEqualTo("Đi Ikebukuro thế nào?");
            assertThat(q.content()).isEqualTo("Mình ở Arakawa.");
            assertThat(q.author().rankName()).isEqualTo("Người mới");
            assertThat(q.likeCount()).isEqualTo(1);
            assertThat(q.reportReasons()).isEqualTo(REASONS);
            assertThat(q.viewer()).isEqualTo(new Viewer(false, false, false, false, false));
        }

        @Test
        @DisplayName("UTC-220 Câu hỏi có chủ đề và địa điểm liên quan")
        void utc220_questionHasTopicsAndPlaces() {
            TopicRef topic = new TopicRef(4L, "Đi lại");
            PlaceRef station = new PlaceRef(40L, "Ga Ikebukuro", "Nhà ga", "Toshima, Tokyo", 35.7295, 139.7109,
                    "N123");
            givenBlog(question("PUBLISHED", null));
            when(feed.topicsOf(List.of(QUESTION_ID))).thenReturn(Map.of(QUESTION_ID, List.of(topic)));
            when(feed.placesOf(List.of(QUESTION_ID))).thenReturn(Map.of(QUESTION_ID, List.of(station)));

            BlogDetail q = service.detail(QUESTION_ID, null, "QA");

            assertThat(q.topics()).containsExactly(topic);
            assertThat(q.places()).containsExactly(station);
        }

        @Test
        @DisplayName("UTC-221 Mở mã của một bài viết Blog như câu hỏi: 404 MSG19")
        void utc221_postIdDoesNotOpenAsAQuestion() {
            givenBlog(post("PUBLISHED"));

            assertNotFound(() -> service.detail(POST_ID, MEMBER, "QA"));
        }

        @Test
        @DisplayName("UTC-222 Câu hỏi không tồn tại hoặc đã xóa: 404 MSG19")
        void utc222_missingQuestionIsNotFound() {
            when(repository.findBlog(QUESTION_ID)).thenReturn(Optional.empty());

            assertNotFound(() -> service.detail(QUESTION_ID, null, "QA"));
        }

        @Test
        @DisplayName("UTC-223 Câu trả lời hay nhất (302) đứng đầu, có best = true; bestCommentId = 302")
        void utc223_bestAnswerComesFirstAndIsMarked() {
            givenBlog(question("PUBLISHED", 302L));
            when(repository.comments(QUESTION_ID)).thenReturn(List.of(
                    comment(301, QUESTION_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, QUESTION_ID, null, 12, 0, "PUBLISHED", 2),
                    comment(303, QUESTION_ID, null, 13, 0, "PUBLISHED", 3)));

            BlogDetail q = service.detail(QUESTION_ID, null, "QA");

            assertThat(q.bestCommentId()).isEqualTo(302L);
            assertThat(ids(q.comments())).containsExactly(302L, 301L, 303L);
            assertThat(q.comments()).extracting(CommentNode::best).containsExactly(true, false, false);
        }

        @Test
        @DisplayName("UTC-224 Thứ tự: hay nhất, rồi \"Senpai tin cậy\" (450 điểm), rồi các câu khác cũ trước")
        void utc224_senpaiAnswersComeRightAfterTheBestOne() {
            List<CommentNode> answers = answersOf(question("PUBLISHED", 304L), List.of(
                    comment(301, QUESTION_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, QUESTION_ID, null, 12, 450, "PUBLISHED", 2),
                    comment(303, QUESTION_ID, null, 13, 0, "PUBLISHED", 3),
                    comment(304, QUESTION_ID, null, 14, 0, "PUBLISHED", 4)));

            assertThat(ids(answers)).containsExactly(304L, 302L, 301L, 303L);
            assertThat(answers.get(1).author().rankName()).isEqualTo("Senpai tin cậy");
        }

        @Test
        @DisplayName("UTC-225 Ngưỡng Senpai: 300 điểm được đưa lên, 299 điểm thì không")
        void utc225_senpaiThresholdIsTheHighestRank() {
            List<CommentNode> answers = answersOf(question("PUBLISHED", null), List.of(
                    comment(301, QUESTION_ID, null, 11, 299, "PUBLISHED", 1),
                    comment(302, QUESTION_ID, null, 12, 300, "PUBLISHED", 2)));

            assertThat(ids(answers)).containsExactly(302L, 301L);
        }

        @Test
        @DisplayName("UTC-226 Bảng hạng trống: không ai được ưu tiên, câu trả lời cũ trước")
        void utc226_noRankTableMeansOldestFirst() {
            when(repository.ranks()).thenReturn(List.of());

            List<CommentNode> answers = answersOf(question("PUBLISHED", null), List.of(
                    comment(301, QUESTION_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, QUESTION_ID, null, 12, 5000, "PUBLISHED", 2)));

            assertThat(ids(answers)).containsExactly(301L, 302L);
        }

        @Test
        @DisplayName("UTC-227 Phản hồi nằm dưới câu trả lời, cũ trước; phản hồi của Senpai không được đưa lên")
        void utc227_repliesStayUnderTheirAnswerOldestFirst() {
            List<CommentNode> answers = answersOf(question("PUBLISHED", null), List.of(
                    comment(301, QUESTION_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, QUESTION_ID, 301L, 12, 0, "PUBLISHED", 2),
                    comment(303, QUESTION_ID, 301L, 13, 450, "PUBLISHED", 3)));

            assertThat(ids(answers)).containsExactly(301L);
            assertThat(ids(answers.get(0).replies())).containsExactly(302L, 303L);
        }

        @Test
        @DisplayName("UTC-228 Câu trả lời hay nhất bị ẩn (không còn phản hồi): không còn là hay nhất, không hiện")
        void utc228_hiddenBestAnswerNoLongerCounts() {
            givenBlog(question("PUBLISHED", 302L));
            when(repository.comments(QUESTION_ID)).thenReturn(List.of(
                    comment(301, QUESTION_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, QUESTION_ID, null, 12, 0, "HIDDEN", 2)));

            BlogDetail q = service.detail(QUESTION_ID, null, "QA");

            assertThat(q.bestCommentId()).isNull();
            assertThat(ids(q.comments())).containsExactly(301L);
        }

        @Test
        @DisplayName("UTC-229 Người viết câu trả lời hay nhất bị khóa / đã xóa: bestCommentId = null (2 lần)")
        void utc229_bestAnswerOfALockedOrDeletedAccountNoLongerCounts() {
            for (String account : List.of("LOCKED", "DELETED")) {
                givenBlog(question("PUBLISHED", 302L));
                when(repository.comments(QUESTION_ID)).thenReturn(List.of(
                        comment(302, QUESTION_ID, null, 12, 0, "PUBLISHED", 2, account)));

                BlogDetail q = service.detail(QUESTION_ID, null, "QA");

                assertThat(q.bestCommentId()).as(account).isNull();
                assertThat(q.comments()).as(account).isEmpty();
            }
        }

        @Test
        @DisplayName("UTC-230 Câu trả lời hay nhất đã bị xóa khỏi kho: bestCommentId = null")
        void utc230_deletedBestAnswerNoLongerCounts() {
            givenBlog(question("PUBLISHED", 399L));
            when(repository.comments(QUESTION_ID)).thenReturn(List.of(
                    comment(301, QUESTION_ID, null, 11, 0, "PUBLISHED", 1)));

            BlogDetail q = service.detail(QUESTION_ID, null, "QA");

            assertThat(q.bestCommentId()).isNull();
            assertThat(q.comments()).extracting(CommentNode::best).containsExactly(false);
        }

        @Test
        @DisplayName("UTC-231 Câu trả lời hay nhất bị gỡ nhưng còn phản hồi: chỗ trống, best = false, không đứng đầu")
        void utc231_removedBestAnswerWithRepliesIsAPlaceholder() {
            givenBlog(question("PUBLISHED", 302L));
            when(repository.comments(QUESTION_ID)).thenReturn(List.of(
                    comment(301, QUESTION_ID, null, 11, 0, "PUBLISHED", 1),
                    comment(302, QUESTION_ID, null, 12, 0, "REMOVED", 2),
                    comment(303, QUESTION_ID, 302L, 13, 0, "PUBLISHED", 3)));

            BlogDetail q = service.detail(QUESTION_ID, null, "QA");

            assertThat(q.bestCommentId()).isNull();
            assertThat(ids(q.comments())).containsExactly(301L, 302L);
            CommentNode placeholder = q.comments().get(1);
            assertThat(placeholder.removed()).isTrue();
            assertThat(placeholder.best()).isFalse();
            assertThat(ids(placeholder.replies())).containsExactly(303L);
        }

        @Test
        @DisplayName("UTC-232 Chưa có câu trả lời: danh sách rỗng, số câu trả lời 0, chưa giải quyết")
        void utc232_questionWithoutAnswers() {
            givenBlog(new BlogRow(QUESTION_ID, AUTHOR_ID, "Lan", null, 0, "ACTIVE", "QA", "Câu hỏi mới?", "x",
                    "PUBLISHED", false, false, null, 0, 0, T0));

            BlogDetail q = service.detail(QUESTION_ID, null, "QA");

            assertThat(q.comments()).isEmpty();
            assertThat(q.commentCount()).isZero();
            assertThat(q.bestCommentId()).isNull();
        }

        @Test
        @DisplayName("UTC-233 Người hỏi xem câu hỏi của mình đang quét / bị ẩn / bị từ chối: thấy trạng thái, không thao tác (3 lần)")
        void utc233_askerOpensOwnUnpublishedQuestion() {
            for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED")) {
                givenBlog(question(state, null));
                BlogDetail q = service.detail(QUESTION_ID, AUTHOR, "QA");
                assertThat(q.status()).isEqualTo(state);
                assertThat(q.viewer().author()).isTrue();
                assertThat(q.viewer().canInteract()).as(state).isFalse();
            }
        }

        @Test
        @DisplayName("UTC-234 Khách và thành viên khác mở câu hỏi đang quét / bị ẩn / bị từ chối / bị gỡ: 404 MSG19 (8 lần)")
        void utc234_othersCannotOpenAnUnpublishedQuestion() {
            for (String state : List.of("PROCESSING", "HIDDEN", "REJECTED", "REMOVED")) {
                givenBlog(question(state, null));
                assertNotFound(() -> service.detail(QUESTION_ID, null, "QA"));
                assertNotFound(() -> service.detail(QUESTION_ID, MEMBER, "QA"));
            }
        }

        @Test
        @DisplayName("UTC-235 Người hỏi bị khóa / đã xóa tài khoản: 404 MSG19 (2 lần)")
        void utc235_questionOfALockedOrDeletedAccountIsNotFound() {
            for (String account : List.of("LOCKED", "DELETED")) {
                givenBlog(new BlogRow(QUESTION_ID, AUTHOR_ID, "Lan", null, 0, account, "QA", "Q?", "x", "PUBLISHED",
                        false, false, null, 0, 0, T0));
                assertNotFound(() -> service.detail(QUESTION_ID, MEMBER, "QA"));
            }
        }

        @Test
        @DisplayName("UTC-236 Thành viên đã thích và đã lưu câu hỏi (mở từ \"Đã lưu\"): canInteract, liked, saved = true")
        void utc236_memberWhoLikedAndSavedTheQuestion() {
            givenBlog(question("PUBLISHED", null));
            when(repository.likedBlog(QUESTION_ID, MEMBER_ID)).thenReturn(true);
            when(repository.savedBlog(QUESTION_ID, MEMBER_ID)).thenReturn(true);

            assertThat(service.detail(QUESTION_ID, MEMBER, "QA").viewer())
                    .isEqualTo(new Viewer(true, false, true, true, true));
        }

        @Test
        @DisplayName("UTC-237 Moderator chỉ xem câu hỏi: canInteract = false")
        void utc237_moderatorOnlyReadsTheQuestion() {
            givenBlog(question("PUBLISHED", null));

            Viewer v = service.detail(QUESTION_ID, MODERATOR, "QA").viewer();

            assertThat(v.signedIn()).isTrue();
            assertThat(v.canInteract()).isFalse();
        }

        @Test
        @DisplayName("UTC-238 Xem câu hỏi không chọn câu trả lời hay nhất, không cộng điểm, không báo cáo")
        void utc238_viewingAQuestionWritesNothing() {
            givenBlog(question("PUBLISHED", null));

            service.detail(QUESTION_ID, AUTHOR, "QA");

            verify(repository, never()).setBestComment(anyLong(), any());
            verify(repository, never()).lockBlog(anyLong());
            verify(repository, never()).setBlogLike(anyLong(), anyLong(), anyBoolean(), any());
            verify(reports, never()).report(any(), anyLong(), any(), any());
        }
    }
}
