package com.sep490.vsa.feature.blog;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Media;
import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.BlogCard;
import com.sep490.vsa.feature.blog.BlogDtos.BlogPage;
import com.sep490.vsa.feature.blog.BlogDtos.Featured;
import com.sep490.vsa.feature.blog.BlogDtos.Overview;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.blog.BlogDtos.TopicCount;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.blog.BlogDtos.Writer;
import com.sep490.vsa.feature.blog.BlogFeedRepository.Row;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.3 "Browse Posts" (Duyệt bài viết): the filters of the Blog list ({@link BlogFeedQuery#of}), the
 * page of post cards ({@link BlogFeedService#list}) and the parts of the Blog screen that do not depend on the filters
 * ({@link BlogFeedService#overview}: "Bài nổi bật", topics, "Chủ đề phổ biến", "Senpai viết nhiều").
 * <p>
 * The database is not used: {@link BlogFeedRepository} and {@link FeaturedSnapshotService} are mocks (a few tests
 * build a real {@link FeaturedSnapshotService} over a mocked {@link SystemSettings} to check the Admin settings
 * featured_top_n / featured_min_score); the clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("BlogFeedService – Duyệt bài viết: bộ lọc, danh sách bài, tổng quan Blog (UC 1.3)")
class BlogFeedServiceTest {

    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final long MEMBER_ID = 10L;
    private static final String BASE = "/media/";

    private BlogFeedRepository repository;
    private FeaturedSnapshotService featured;
    private BlogFeedService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogFeedRepository.class);
        featured = mock(FeaturedSnapshotService.class);
        when(featured.topN()).thenReturn(3);
        when(featured.minScore()).thenReturn(10);
        service = new BlogFeedService(repository, featured, Clock.fixed(NOW, ZoneOffset.UTC), BASE);
    }

    // ---------------------------------------------------------------- helpers

    /** A published post by {@code authorId}, written {@code id} minutes before now. */
    private static Row row(long id, long authorId) {
        return new Row(id, "POST", "PUBLISHED", false, false, "Bài " + id, "Nội dung bài " + id, authorId,
                "User " + authorId, null, 0, 0, NOW.minus(Duration.ofMinutes(id)));
    }

    private static Row rowWithContent(long id, String content) {
        return new Row(id, "POST", "PUBLISHED", false, false, "Bài " + id, content, 50L, "User 50", null, 0, 0, NOW);
    }

    private static Row pinnedRow(long id, long authorId, boolean pinned) {
        return new Row(id, "POST", "PUBLISHED", pinned, false, "Bài " + id, "x", authorId, "User " + authorId, null,
                0, 0, NOW.minus(Duration.ofMinutes(id)));
    }

    private static BlogFeedQuery query() {
        return BlogFeedQuery.of(null, null, null, null, null, null);
    }

    private static ApiException apiError(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private static void assertBadRequest(Runnable call, String message) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getMessage()).isEqualTo(message);
    }

    /** Runs {@code list} with {@code total} posts in the database and the given rows on the asked page. */
    private BlogPage listWith(BlogFeedQuery query, long total, List<Row> rows) {
        when(repository.count(any())).thenReturn(total);
        when(repository.find(any())).thenReturn(rows);
        return service.list(query);
    }

    private BlogFeedQuery countedQuery() {
        ArgumentCaptor<BlogFeedQuery> captor = ArgumentCaptor.forClass(BlogFeedQuery.class);
        verify(repository).count(captor.capture());
        return captor.getValue();
    }

    private BlogFeedQuery foundQuery() {
        ArgumentCaptor<BlogFeedQuery> captor = ArgumentCaptor.forClass(BlogFeedQuery.class);
        verify(repository).find(captor.capture());
        return captor.getValue();
    }

    /** The excerpt the list shows for a post whose first 400 characters are {@code content}. */
    private String excerptOf(String content) {
        return listWith(query(), 1, List.of(rowWithContent(1, content))).items().get(0).excerpt();
    }

    @SuppressWarnings("unchecked")
    private List<Long> tagsAskedFor() {
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(repository).tagsOf(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    private static Author author(long id) {
        return new Author(id, "User " + id, null, List.of());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. BlogFeedQuery.of – UC 1.3 bước 3-4: từ khóa, chủ đề, sắp xếp, phân trang (UTC-001 → UTC-017)")
    class QueryParsing {

        @Test
        @DisplayName("UTC-001 Không truyền gì: không từ khóa, mọi chủ đề, \"Mới nhất\", trang 0, 10 bài, không lọc")
        void utc001_defaultsWhenNothingIsGiven() {
            BlogFeedQuery q = BlogFeedQuery.of(null, null, null, null, null, null);

            assertThat(q.keyword()).isNull();
            assertThat(q.topicIds()).isEmpty();
            assertThat(q.sort()).isEqualTo(BlogFeedQuery.Sort.NEWEST);
            assertThat(q.page()).isZero();
            assertThat(q.size()).isEqualTo(10);
            assertThat(q.viewerId()).isNull();
            assertThat(q.filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-002 Từ khóa bỏ dấu, chữ thường, gộp khoảng trắng: \"  Phân loại RÁC   ở Đà Nẵng \"")
        void utc002_keywordIsFoldedWithoutAccentsCaseAndExtraSpaces() {
            BlogFeedQuery q = BlogFeedQuery.of("  Phân loại RÁC   ở Đà Nẵng ", null, null, null, null, null);

            assertThat(q.keyword()).isEqualTo("phan loai rac o da nang");
            assertThat(q.filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-003 Từ khóa tiếng Nhật được giữ; dấu kana (゛゜) bị bỏ như dấu tiếng Việt: \"パン屋\" → \"ハン屋\"")
        void utc003_japaneseKeywordIsKeptWithoutVoicingMarks() {
            BlogFeedQuery q = BlogFeedQuery.of("パン屋", null, null, null, null, null);

            assertThat(q.keyword()).isEqualTo("ハン屋");
            assertThat(q.filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-004 Từ khóa rỗng hoặc chỉ khoảng trắng: không tìm kiếm (3 lần)")
        void utc004_blankKeywordIsNoSearch() {
            for (String blank : List.of("", "   ", "\t\n ")) {
                BlogFeedQuery q = BlogFeedQuery.of(blank, null, null, null, null, null);
                assertThat(q.keyword()).as("[%s]", blank).isNull();
                assertThat(q.filtered()).isFalse();
            }
        }

        @Test
        @DisplayName("UTC-005 Từ khóa đúng 100 ký tự được nhận")
        void utc005_keywordOfExactly100CharactersIsAccepted() {
            BlogFeedQuery q = BlogFeedQuery.of("a".repeat(100), null, null, null, null, null);

            assertThat(q.keyword()).hasSize(100);
        }

        @Test
        @DisplayName("UTC-006 Từ khóa 101 ký tự bị từ chối \"Từ khóa tìm kiếm tối đa 100 ký tự.\"")
        void utc006_keywordOf101CharactersIsRefused() {
            assertBadRequest(() -> BlogFeedQuery.of("a".repeat(101), null, null, null, null, null),
                    "Từ khóa tìm kiếm tối đa 100 ký tự.");
        }

        @Test
        @DisplayName("UTC-007 Khoảng trắng đầu cuối không tính vào 100 ký tự")
        void utc007_surroundingSpacesDoNotCountTowardTheLimit() {
            BlogFeedQuery q = BlogFeedQuery.of("   " + "b".repeat(100) + "   ", null, null, null, null, null);

            assertThat(q.keyword()).isEqualTo("b".repeat(100));
        }

        @Test
        @DisplayName("UTC-008 Cách sắp xếp không phân biệt hoa thường, bỏ khoảng trắng: likes, \" Comments \", NEWEST (3 lần)")
        void utc008_sortIsParsedIgnoringCaseAndSpaces() {
            assertThat(BlogFeedQuery.of(null, null, "likes", null, null, null).sort()).isEqualTo(BlogFeedQuery.Sort.LIKES);
            assertThat(BlogFeedQuery.of(null, null, " Comments ", null, null, null).sort())
                    .isEqualTo(BlogFeedQuery.Sort.COMMENTS);
            assertThat(BlogFeedQuery.of(null, null, "NEWEST", null, null, null).sort())
                    .isEqualTo(BlogFeedQuery.Sort.NEWEST);
        }

        @Test
        @DisplayName("UTC-009 Cách sắp xếp rỗng hoặc chỉ khoảng trắng: mặc định \"Mới nhất\"")
        void utc009_blankSortFallsBackToNewest() {
            assertThat(BlogFeedQuery.of(null, null, "", null, null, null).sort()).isEqualTo(BlogFeedQuery.Sort.NEWEST);
            assertThat(BlogFeedQuery.of(null, null, "  ", null, null, null).sort()).isEqualTo(BlogFeedQuery.Sort.NEWEST);
        }

        @Test
        @DisplayName("UTC-010 Cách sắp xếp không hợp lệ: \"Giá trị lọc không hợp lệ: POPULAR\"")
        void utc010_unknownSortIsRefused() {
            assertBadRequest(() -> BlogFeedQuery.of(null, null, "POPULAR", null, null, null),
                    "Giá trị lọc không hợp lệ: POPULAR");
        }

        @Test
        @DisplayName("UTC-011 Cách sắp xếp của Hỏi-Đáp (ANSWERS) không dùng được cho Blog")
        void utc011_questionSortIsRefusedOnTheBlog() {
            assertBadRequest(() -> BlogFeedQuery.of(null, null, "answers", null, null, null),
                    "Giá trị lọc không hợp lệ: answers");
        }

        @Test
        @DisplayName("UTC-012 Chỉ đổi cách sắp xếp không tính là lọc; chọn chủ đề thì có lọc")
        void utc012_sortingAloneIsNotAFilterButATopicIs() {
            assertThat(BlogFeedQuery.of(null, null, "COMMENTS", null, null, null).filtered()).isFalse();
            assertThat(BlogFeedQuery.of(null, List.of(2L), null, null, null, null).filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-013 Danh sách chủ đề: bỏ trùng, bỏ null, 0 và số âm, giữ thứ tự")
        void utc013_topicIdsAreCleaned() {
            BlogFeedQuery q = BlogFeedQuery.of(null, Arrays.asList(3L, 3L, null, 0L, -1L, 5L), null, null, null, null);

            assertThat(q.topicIds()).containsExactly(3L, 5L);
        }

        @Test
        @DisplayName("UTC-014 Chỉ có mã chủ đề không hợp lệ: coi như không lọc chủ đề")
        void utc014_onlyInvalidTopicIdsMeanNoTopicFilter() {
            BlogFeedQuery q = BlogFeedQuery.of(null, Arrays.asList(0L, -7L, null), null, null, null, null);

            assertThat(q.topicIds()).isEmpty();
            assertThat(q.filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-015 Trang âm thành trang 0; trang 4 giữ nguyên")
        void utc015_negativePageBecomesZero() {
            assertThat(BlogFeedQuery.of(null, null, null, -2, null, null).page()).isZero();
            assertThat(BlogFeedQuery.of(null, null, null, 4, null, null).page()).isEqualTo(4);
        }

        @Test
        @DisplayName("UTC-016 Cỡ trang được giữ trong 1–30: 0 → 1, -5 → 1, 1 → 1, 30 → 30, 31 → 30, 500 → 30 (6 lần)")
        void utc016_pageSizeIsKeptWithinOneToThirty() {
            int[][] cases = {{0, 1}, {-5, 1}, {1, 1}, {30, 30}, {31, 30}, {500, 30}};
            for (int[] c : cases) {
                assertThat(BlogFeedQuery.of(null, null, null, null, c[0], null).size()).as("size %d", c[0])
                        .isEqualTo(c[1]);
            }
        }

        @Test
        @DisplayName("UTC-017 Người xem đã đăng nhập được giữ trong bộ lọc (để thấy bài mình đang quét)")
        void utc017_viewerIdIsKept() {
            assertThat(BlogFeedQuery.of(null, null, null, null, null, MEMBER_ID).viewerId()).isEqualTo(MEMBER_ID);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. list – UC 1.3 Duyệt bài viết: danh sách thẻ bài, tìm kiếm, lọc, phân trang (UTC-018 → UTC-045)")
    class BrowsePosts {

        @Test
        @DisplayName("UTC-018 Khách mở Blog: 3 bài đã đăng theo đúng thứ tự kho trả về, trang 0/1, 10 bài mỗi trang, không lọc")
        void utc018_guestGetsThePublishedPostsInRepositoryOrder() {
            BlogPage page = listWith(query(), 3, List.of(row(3, 50), row(2, 51), row(1, 52)));

            assertThat(page.items()).extracting(BlogCard::id).containsExactly(3L, 2L, 1L);
            assertThat(page.page()).isZero();
            assertThat(page.size()).isEqualTo(10);
            assertThat(page.totalItems()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-019 Thẻ bài đủ thông tin: tiêu đề, trích đoạn, tác giả có tag, chủ đề, địa điểm, ảnh, lượt thích, bình luận, thời gian")
        void utc019_cardCarriesEveryField() {
            Row r = new Row(7L, "POST", "PUBLISHED", true, false, "Kinh nghiệm thuê nhà", "Nên xem nhà vào buổi sáng.",
                    50L, "Lan", "/media/avatar/lan.jpg", 4, 2, NOW);
            TopicRef topic = new TopicRef(1L, "Nhà ở");
            PlaceRef place = new PlaceRef(30L, "Ga Nippori", "Nhà ga", "Arakawa, Tokyo", 35.7281, 139.7707,
                    "N772389775");
            Media photo = new Media(90L, "IMAGE", "/media/blog/2026/11/a.jpg", "image/jpeg", 1600, 900);
            Tag tag = new Tag("Du học sinh", "#2E7D32");
            when(repository.topicsOf(List.of(7L))).thenReturn(Map.of(7L, List.of(topic)));
            when(repository.placesOf(List.of(7L))).thenReturn(Map.of(7L, List.of(place)));
            when(repository.mediaOf(List.of(7L), null, BASE)).thenReturn(Map.of(7L, List.of(photo)));
            when(repository.tagsOf(any())).thenReturn(Map.of(50L, List.of(tag)));

            BlogCard card = listWith(query(), 1, List.of(r)).items().get(0);

            assertThat(card.id()).isEqualTo(7L);
            assertThat(card.type()).isEqualTo("POST");
            assertThat(card.status()).isEqualTo("PUBLISHED");
            assertThat(card.pinned()).isTrue();
            assertThat(card.title()).isEqualTo("Kinh nghiệm thuê nhà");
            assertThat(card.excerpt()).isEqualTo("Nên xem nhà vào buổi sáng.");
            assertThat(card.author()).isEqualTo(new Author(50L, "Lan", "/media/avatar/lan.jpg", List.of(tag)));
            assertThat(card.topics()).containsExactly(topic);
            assertThat(card.places()).containsExactly(place);
            assertThat(card.media()).containsExactly(photo);
            assertThat(card.likeCount()).isEqualTo(4);
            assertThat(card.commentCount()).isEqualTo(2);
            assertThat(card.createdAt()).isEqualTo(NOW);
            assertThat(card.liked()).isFalse();
        }

        @Test
        @DisplayName("UTC-020 Bài không có chủ đề, địa điểm, ảnh, tag: các danh sách rỗng (không null)")
        void utc020_missingDetailsGiveEmptyLists() {
            BlogCard card = listWith(query(), 1, List.of(row(1, 50))).items().get(0);

            assertThat(card.topics()).isEmpty();
            assertThat(card.places()).isEmpty();
            assertThat(card.media()).isEmpty();
            assertThat(card.author().tags()).isEmpty();
        }

        @Test
        @DisplayName("UTC-021 Tag định danh được đọc một lần cho mỗi tác giả, theo thứ tự xuất hiện")
        void utc021_tagsAreReadOncePerAuthor() {
            listWith(query(), 3, List.of(row(3, 50), row(2, 51), row(1, 50)));

            assertThat(tagsAskedFor()).containsExactly(50L, 51L);
        }

        @Test
        @DisplayName("UTC-022 Khách: ảnh theo luật của khách (chỉ ảnh đã qua kiểm duyệt), không bài nào \"đã thích\"")
        void utc022_guestGetsMediaAsAGuestAndNoLikes() {
            BlogPage page = listWith(query(), 2, List.of(row(2, 50), row(1, 51)));

            verify(repository).mediaOf(List.of(2L, 1L), null, BASE);
            verify(repository).likedBy(null, List.of(2L, 1L));
            assertThat(page.items()).extracting(BlogCard::liked).containsOnly(false);
        }

        @Test
        @DisplayName("UTC-023 Thành viên id=10 đã thích bài 2: chỉ thẻ bài 2 có liked = true")
        void utc023_memberSeesWhichPostsTheyLike() {
            when(repository.likedBy(MEMBER_ID, List.of(3L, 2L, 1L))).thenReturn(Set.of(2L));
            BlogFeedQuery q = BlogFeedQuery.of(null, null, null, null, null, MEMBER_ID);

            BlogPage page = listWith(q, 3, List.of(row(3, 50), row(2, 51), row(1, 52)));

            assertThat(page.items()).extracting(BlogCard::liked).containsExactly(false, true, false);
            verify(repository).mediaOf(List.of(3L, 2L, 1L), MEMBER_ID, BASE);
        }

        @Test
        @DisplayName("UTC-024 Tác giả thấy bài của mình đang quét ảnh/video (PROCESSING): mã người xem được gửi xuống kho")
        void utc024_authorSeesOwnPostStillBeingScanned() {
            Row own = new Row(9L, "POST", "PROCESSING", false, false, "Bài đang quét", "x", MEMBER_ID, "User 10", null,
                    0, 0, NOW);
            BlogFeedQuery q = BlogFeedQuery.of(null, null, null, null, null, MEMBER_ID);

            BlogCard card = listWith(q, 1, List.of(own)).items().get(0);

            assertThat(countedQuery().viewerId()).isEqualTo(MEMBER_ID);
            assertThat(foundQuery().viewerId()).isEqualTo(MEMBER_ID);
            assertThat(card.status()).isEqualTo("PROCESSING");
        }

        @Test
        @DisplayName("UTC-025 Địa chỉ ảnh theo cấu hình app.media.public-base-url")
        void utc025_mediaUrlsUseTheConfiguredBase() {
            service = new BlogFeedService(repository, featured, Clock.fixed(NOW, ZoneOffset.UTC), "https://cdn.example/m/");

            listWith(query(), 1, List.of(row(1, 50)));

            verify(repository).mediaOf(List.of(1L), null, "https://cdn.example/m/");
        }

        @Test
        @DisplayName("UTC-026 Trích đoạn ngắn giữ nguyên, không có \"…\"")
        void utc026_shortContentIsShownWhole() {
            assertThat(excerptOf("Mang theo hộ chiếu.")).isEqualTo("Mang theo hộ chiếu.");
        }

        @Test
        @DisplayName("UTC-027 Trích đoạn dài (400 ký tự) được cắt ở ranh giới từ, ≤ 180 ký tự, kết thúc bằng \"…\"")
        void utc027_longContentIsCutAtAWordBoundary() {
            String content = "một hai ba bốn năm sáu bảy tám chín mười ".repeat(10).substring(0, 400);

            String excerpt = excerptOf(content);

            assertThat(excerpt).endsWith("…");
            assertThat(excerpt.length()).isLessThanOrEqualTo(181);
            String shown = excerpt.substring(0, excerpt.length() - 1);
            assertThat(content).startsWith(shown);
            assertThat(content.charAt(shown.length())).isEqualTo(' ');
        }

        @Test
        @DisplayName("UTC-028 Trích đoạn đúng 180 ký tự giữ nguyên; 181 ký tự thì bị cắt")
        void utc028_exactly180CharactersAreKept() {
            String exact = "x".repeat(180);
            assertThat(excerptOf(exact)).isEqualTo(exact);

            String over = "x".repeat(181);
            assertThat(listWith(query(), 1, List.of(rowWithContent(2, over))).items().get(0).excerpt())
                    .isEqualTo("x".repeat(180) + "…");
        }

        @Test
        @DisplayName("UTC-029 Xuống dòng, tab và nhiều khoảng trắng trong trích đoạn thành một khoảng trắng")
        void utc029_whitespaceIsFlattened() {
            assertThat(excerptOf("  Dòng 1\n\n   Dòng 2\t\t3  ")).isEqualTo("Dòng 1 Dòng 2 3");
        }

        @Test
        @DisplayName("UTC-030 Bài không có nội dung (null): trích đoạn rỗng")
        void utc030_nullContentGivesAnEmptyExcerpt() {
            assertThat(excerptOf(null)).isEmpty();
        }

        @Test
        @DisplayName("UTC-031 Khoảng trắng cuối cùng nằm trước 60% (vị trí 100): cắt cứng ở 180 ký tự")
        void utc031_spaceTooEarlyMeansAHardCut() {
            String content = "a".repeat(100) + " " + "b".repeat(150);

            assertThat(excerptOf(content)).isEqualTo("a".repeat(100) + " " + "b".repeat(79) + "…");
        }

        @Test
        @DisplayName("UTC-032 Khoảng trắng cuối cùng ngay sau 60% (vị trí 109): cắt ở khoảng trắng đó")
        void utc032_spaceJustPastSixtyPercentIsUsed() {
            String content = "a".repeat(109) + " " + "b".repeat(150);

            assertThat(excerptOf(content)).isEqualTo("a".repeat(109) + "…");
        }

        @Test
        @DisplayName("UTC-033 25 bài, trang 1: totalPages = 3, kho được hỏi trang 1")
        void utc033_pagingAsksTheRepositoryForTheRequestedPage() {
            BlogFeedQuery q = BlogFeedQuery.of(null, null, null, 1, null, null);

            BlogPage page = listWith(q, 25, List.of(row(11, 50)));

            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalPages()).isEqualTo(3);
            assertThat(page.totalItems()).isEqualTo(25);
            assertThat(foundQuery().page()).isEqualTo(1);
        }

        @Test
        @DisplayName("UTC-034 Trang vượt quá trang cuối (trang 5 của 3 trang) được đưa về trang cuối (trang 2)")
        void utc034_pagePastTheEndBecomesTheLastPage() {
            BlogFeedQuery q = BlogFeedQuery.of(null, null, null, 5, null, null);

            BlogPage page = listWith(q, 25, List.of(row(21, 50)));

            assertThat(page.page()).isEqualTo(2);
            assertThat(foundQuery().page()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-035 Đúng trang cuối (trang 2 của 3 trang) giữ nguyên")
        void utc035_theLastPageItselfIsKept() {
            BlogFeedQuery q = BlogFeedQuery.of(null, null, null, 2, null, null);

            assertThat(listWith(q, 25, List.of(row(21, 50))).page()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-036 Số trang: 10 bài = 1 trang, 11 bài = 2 trang (10 bài mỗi trang) (2 lần)")
        void utc036_totalPagesAtTheBoundary() {
            assertThat(listWith(query(), 10, List.of(row(1, 50))).totalPages()).isEqualTo(1);
            assertThat(listWith(query(), 11, List.of(row(1, 50))).totalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-037 Chưa có bài nào: danh sách rỗng, 1 trang, không đọc chi tiết thẻ (trang hiện MSG32)")
        void utc037_noPostAtAllGivesAnEmptyUnfilteredPage() {
            BlogFeedQuery q = BlogFeedQuery.of(null, null, null, 3, null, null);

            BlogPage page = listWith(q, 0, List.of());

            assertThat(page.items()).isEmpty();
            assertThat(page.totalItems()).isZero();
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.page()).isZero();
            assertThat(page.filtered()).isFalse();
            verify(repository, never()).find(any());
            verify(repository, never()).topicsOf(any());
            verify(repository, never()).tagsOf(any());
        }

        @Test
        @DisplayName("UTC-038 Không có bài khớp từ khóa: danh sách rỗng, filtered = true (trang hiện MSG33 \"Xóa bộ lọc\")")
        void utc038_noMatchForAKeywordGivesAnEmptyFilteredPage() {
            BlogPage page = listWith(BlogFeedQuery.of("không có bài nào", null, null, null, null, null), 0, List.of());

            assertThat(page.items()).isEmpty();
            assertThat(page.filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-039 Từ khóa đã chuẩn hóa được gửi xuống kho cho cả đếm và tìm")
        void utc039_foldedKeywordIsPassedDown() {
            listWith(BlogFeedQuery.of("  Thuê NHÀ ", null, null, null, null, null), 1, List.of(row(1, 50)));

            assertThat(countedQuery().keyword()).isEqualTo("thue nha");
            assertThat(foundQuery().keyword()).isEqualTo("thue nha");
        }

        @Test
        @DisplayName("UTC-040 Lọc chủ đề: chỉ giữ chủ đề đang bật (2), bỏ chủ đề không tồn tại hoặc đã tắt (9)")
        void utc040_topicFilterKeepsOnlyActiveTopics() {
            when(repository.activeTopics()).thenReturn(List.of(new TopicRef(1L, "Nhà ở"), new TopicRef(2L, "Việc làm"),
                    new TopicRef(3L, "Khác")));

            BlogPage page = listWith(BlogFeedQuery.of(null, List.of(2L, 9L), null, null, null, null), 1,
                    List.of(row(1, 50)));

            assertThat(countedQuery().topicIds()).containsExactly(2L);
            assertThat(foundQuery().topicIds()).containsExactly(2L);
            assertThat(page.filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-041 Chỉ chọn chủ đề đã tắt (9): bỏ qua, hiện mọi bài, filtered = false")
        void utc041_onlyInactiveTopicsAreIgnored() {
            when(repository.activeTopics()).thenReturn(List.of(new TopicRef(1L, "Nhà ở")));

            BlogPage page = listWith(BlogFeedQuery.of(null, List.of(9L), null, null, null, null), 4,
                    List.of(row(1, 50)));

            assertThat(countedQuery().topicIds()).isEmpty();
            assertThat(page.filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-042 Không lọc chủ đề: không đọc danh sách chủ đề")
        void utc042_noTopicFilterReadsNoTopicList() {
            listWith(query(), 1, List.of(row(1, 50)));

            verify(repository, never()).activeTopics();
        }

        @Test
        @DisplayName("UTC-043 Sắp xếp \"Nhiều thích\" và \"Nhiều bình luận\" được gửi xuống kho (2 lần)")
        void utc043_sortIsPassedDown() {
            when(repository.count(any())).thenReturn(1L);
            when(repository.find(any())).thenReturn(List.of(row(1, 50)));

            service.list(BlogFeedQuery.of(null, null, "LIKES", null, null, null));
            service.list(BlogFeedQuery.of(null, null, "COMMENTS", null, null, null));

            ArgumentCaptor<BlogFeedQuery> captor = ArgumentCaptor.forClass(BlogFeedQuery.class);
            verify(repository, times(2)).find(captor.capture());
            assertThat(captor.getAllValues()).extracting(BlogFeedQuery::sort)
                    .containsExactly(BlogFeedQuery.Sort.LIKES, BlogFeedQuery.Sort.COMMENTS);
        }

        @Test
        @DisplayName("UTC-044 Bài \"Đã ghim\" kho trả về đầu tiên vẫn ở đầu, có pinned = true")
        void utc044_pinnedPostKeepsItsPlaceAndFlag() {
            BlogPage page = listWith(query(), 3, List.of(pinnedRow(9, 50, true), pinnedRow(1, 51, false),
                    pinnedRow(2, 52, false)));

            assertThat(page.items()).extracting(BlogCard::id).containsExactly(9L, 1L, 2L);
            assertThat(page.items()).extracting(BlogCard::pinned).containsExactly(true, false, false);
        }

        @Test
        @DisplayName("UTC-045 Duyệt danh sách không ghi gì và không đụng tới bài nổi bật")
        void utc045_browsingWritesNothing() {
            listWith(query(), 2, List.of(row(2, 50), row(1, 51)));

            verify(repository, never()).replaceFeatured(anyString(), any(), anyList());
            verify(repository, never()).latestFeatured(anyString(), anyInt());
            verifyNoInteractions(featured);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. overview – UC 1.3 bước 2: bài nổi bật, chủ đề, \"Chủ đề phổ biến\", \"Senpai viết nhiều\" (UTC-046 → UTC-061)")
    class BlogOverview {

        @Test
        @DisplayName("UTC-046 Tổng quan: chủ đề đang bật, 5 chủ đề phổ biến, người viết nhiều trong 30 ngày")
        void utc046_overviewHoldsTopicsPopularTopicsAndWriters() {
            List<TopicRef> topics = List.of(new TopicRef(1L, "Nhà ở"), new TopicRef(3L, "Khác"));
            List<TopicCount> popular = List.of(new TopicCount(1L, "Nhà ở", 12));
            when(repository.activeTopics()).thenReturn(topics);
            when(repository.popularTopics(5)).thenReturn(popular);

            Overview overview = service.overview();

            assertThat(overview.topics()).isEqualTo(topics);
            assertThat(overview.popularTopics()).isEqualTo(popular);
            assertThat(overview.topWritersDays()).isEqualTo(30);
            verify(repository).popularTopics(5);
        }

        @Test
        @DisplayName("UTC-047 \"Senpai viết nhiều\" đếm từ đúng 30 ngày trước (21/10/2026 03:00 UTC), tối đa 5 người")
        void utc047_topWritersCountTheLastThirtyDays() {
            service.overview();

            verify(repository).topWriters(Instant.parse("2026-10-21T03:00:00Z"), 5);
        }

        @Test
        @DisplayName("UTC-048 Người viết nhiều kèm tag định danh và số bài, giữ thứ tự kho trả về")
        void utc048_topWritersCarryTheirTagsAndPostCount() {
            Tag tag = new Tag("Du học sinh", "#2E7D32");
            when(repository.topWriters(any(), anyInt())).thenReturn(List.of(Map.entry(author(50), 6L),
                    Map.entry(author(51), 4L)));
            when(repository.tagsOf(List.of(50L, 51L))).thenReturn(Map.of(50L, List.of(tag)));

            List<Writer> writers = service.overview().topWriters();

            assertThat(writers).extracting(w -> w.author().id()).containsExactly(50L, 51L);
            assertThat(writers).extracting(Writer::postCount).containsExactly(6L, 4L);
            assertThat(writers.get(0).author().tags()).containsExactly(tag);
            assertThat(writers.get(1).author().tags()).isEmpty();
        }

        @Test
        @DisplayName("UTC-049 Không ai viết bài trong 30 ngày: danh sách người viết rỗng")
        void utc049_noWriterGivesAnEmptyList() {
            assertThat(service.overview().topWriters()).isEmpty();
        }

        @Test
        @DisplayName("UTC-050 Bài nổi bật tuần: lấy ảnh chụp WEEK với điểm tối thiểu 10, không đọc ảnh chụp MONTH")
        void utc050_weeklySnapshotIsUsedFirst() {
            when(repository.latestFeatured("WEEK", 10)).thenReturn(List.of(5L, 6L));
            when(repository.findPublished(List.of(5L, 6L))).thenReturn(List.of(row(5, 50), row(6, 51)));

            Featured f = service.overview().featured();

            assertThat(f.period()).isEqualTo("WEEK");
            assertThat(f.posts()).extracting(BlogCard::id).containsExactly(5L, 6L);
            verify(repository, never()).latestFeatured(eq("MONTH"), anyInt());
        }

        @Test
        @DisplayName("UTC-051 Tuần không có bài đạt điểm: dùng bài nổi bật tháng (MONTH)")
        void utc051_monthlySnapshotWhenTheWeekHasNone() {
            when(repository.latestFeatured("MONTH", 10)).thenReturn(List.of(8L));
            when(repository.findPublished(List.of(8L))).thenReturn(List.of(row(8, 50)));

            Featured f = service.overview().featured();

            assertThat(f.period()).isEqualTo("MONTH");
            assertThat(f.posts()).extracting(BlogCard::id).containsExactly(8L);
        }

        @Test
        @DisplayName("UTC-052 Cả tuần và tháng đều không có bài: period = null, không có bài nổi bật")
        void utc052_noFeaturedPostAtAll() {
            Featured f = service.overview().featured();

            assertThat(f.period()).isNull();
            assertThat(f.posts()).isEmpty();
        }

        @Test
        @DisplayName("UTC-053 Mỗi tác giả một bài: 5 (tác giả 50), 6 (tác giả 50), 7 (tác giả 51) → 5 và 7")
        void utc053_onePostPerAuthor() {
            when(repository.latestFeatured("WEEK", 10)).thenReturn(List.of(5L, 6L, 7L));
            when(repository.findPublished(List.of(5L, 6L, 7L))).thenReturn(List.of(row(5, 50), row(6, 50), row(7, 51)));

            assertThat(service.overview().featured().posts()).extracting(BlogCard::id).containsExactly(5L, 7L);
        }

        @Test
        @DisplayName("UTC-054 Tối đa featured_top_n = 3 bài dù ảnh chụp có 5 bài của 5 tác giả")
        void utc054_atMostTopNPosts() {
            List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);
            when(repository.latestFeatured("WEEK", 10)).thenReturn(ids);
            when(repository.findPublished(ids)).thenReturn(List.of(row(1, 51), row(2, 52), row(3, 53), row(4, 54),
                    row(5, 55)));

            assertThat(service.overview().featured().posts()).extracting(BlogCard::id).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("UTC-055 featured_top_n = 1: chỉ bài hạng nhất")
        void utc055_topNOfOne() {
            when(featured.topN()).thenReturn(1);
            when(repository.latestFeatured("WEEK", 10)).thenReturn(List.of(1L, 2L));
            when(repository.findPublished(List.of(1L, 2L))).thenReturn(List.of(row(1, 51), row(2, 52)));

            assertThat(service.overview().featured().posts()).extracting(BlogCard::id).containsExactly(1L);
        }

        @Test
        @DisplayName("UTC-056 featured_min_score = 15 được dùng khi đọc ảnh chụp tuần và tháng")
        void utc056_minimumScoreIsPassedDown() {
            when(featured.minScore()).thenReturn(15);

            service.overview();

            verify(repository).latestFeatured("WEEK", 15);
            verify(repository).latestFeatured("MONTH", 15);
        }

        @Test
        @DisplayName("UTC-057 Bài trong ảnh chụp tuần không còn hiện (tác giả bị khóa sau ảnh chụp): chuyển sang tháng")
        void utc057_weeklyPostsNoLongerShownFallBackToTheMonth() {
            when(repository.latestFeatured("WEEK", 10)).thenReturn(List.of(5L));
            when(repository.findPublished(List.of(5L))).thenReturn(List.of());
            when(repository.latestFeatured("MONTH", 10)).thenReturn(List.of(8L));
            when(repository.findPublished(List.of(8L))).thenReturn(List.of(row(8, 51)));

            Featured f = service.overview().featured();

            assertThat(f.period()).isEqualTo("MONTH");
            assertThat(f.posts()).extracting(BlogCard::id).containsExactly(8L);
        }

        @Test
        @DisplayName("UTC-058 Thẻ bài nổi bật: trích đoạn ≤ 150 ký tự, liked = false, ảnh theo luật của khách")
        void utc058_featuredCardsAreShortAndNeverLiked() {
            Row long1 = new Row(5L, "POST", "PUBLISHED", false, false, "Bài 5", "y".repeat(400), 50L, "User 50", null,
                    20, 3, NOW);
            when(repository.latestFeatured("WEEK", 10)).thenReturn(List.of(5L));
            when(repository.findPublished(List.of(5L))).thenReturn(List.of(long1));

            BlogCard card = service.overview().featured().posts().get(0);

            assertThat(card.excerpt()).isEqualTo("y".repeat(150) + "…");
            assertThat(card.liked()).isFalse();
            verify(repository).mediaOf(List.of(5L), null, BASE);
            verify(repository).likedBy(isNull(), eq(List.of(5L)));
        }

        @Test
        @DisplayName("UTC-059 Cài đặt Admin featured_top_n = 5 bị giới hạn còn 3; = 0 thành 1 (2 lần)")
        void utc059_topNSettingIsKeptWithinOneToThree() {
            SystemSettings settings = mock(SystemSettings.class);
            FeaturedSnapshotService real = new FeaturedSnapshotService(repository, settings,
                    Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
            service = new BlogFeedService(repository, real, Clock.fixed(NOW, ZoneOffset.UTC), BASE);
            List<Long> ids = List.of(1L, 2L, 3L, 4L);
            when(settings.getInt(SystemSettings.FEATURED_MIN_SCORE, 10)).thenReturn(10);
            when(repository.latestFeatured("WEEK", 10)).thenReturn(ids);
            when(repository.findPublished(ids)).thenReturn(List.of(row(1, 51), row(2, 52), row(3, 53), row(4, 54)));

            when(settings.getInt(SystemSettings.FEATURED_TOP_N, 3)).thenReturn(5);
            assertThat(service.overview().featured().posts()).hasSize(3);

            when(settings.getInt(SystemSettings.FEATURED_TOP_N, 3)).thenReturn(0);
            assertThat(service.overview().featured().posts()).extracting(BlogCard::id).containsExactly(1L);
        }

        @Test
        @DisplayName("UTC-060 Cài đặt Admin featured_min_score âm (-4) được coi là 0")
        void utc060_negativeMinimumScoreBecomesZero() {
            SystemSettings settings = mock(SystemSettings.class);
            FeaturedSnapshotService real = new FeaturedSnapshotService(repository, settings,
                    Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
            service = new BlogFeedService(repository, real, Clock.fixed(NOW, ZoneOffset.UTC), BASE);
            when(settings.getInt(SystemSettings.FEATURED_TOP_N, 3)).thenReturn(3);
            when(settings.getInt(SystemSettings.FEATURED_MIN_SCORE, 10)).thenReturn(-4);

            service.overview();

            verify(repository).latestFeatured("WEEK", 0);
        }

        @Test
        @DisplayName("UTC-061 Mở tổng quan không ghi gì (không tính lại ảnh chụp bài nổi bật)")
        void utc061_overviewWritesNothing() {
            service.overview();

            verify(repository, never()).replaceFeatured(anyString(), any(), anyList());
            verify(repository, never()).featuredCandidates(any(), anyInt());
        }
    }
}
