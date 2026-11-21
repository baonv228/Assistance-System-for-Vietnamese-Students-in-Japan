package com.sep490.vsa.usecase.blog;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.usecase.UseCaseTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC 1.3 Browse Posts (SRS Report 3, II.1.3 "Browse Posts"; CLAUDE.md "Blog feed (feature/blog)").
 * <p>
 * API: {@code GET /api/blogs?q=&topicIds=&sort=NEWEST|LIKES|COMMENTS&page=&size=} (public) and
 * {@code GET /api/blogs/overview} (featured posts, topics, "Chủ đề phổ biến", "Senpai viết nhiều").
 * The messages MSG32 / MSG33 / MSG17 and the "Xóa bộ lọc" / "Thử lại" buttons are drawn by the page from the
 * answer ({@code totalItems}, {@code filtered}); the tests check the data the page decides on.
 * Every list is scoped by a keyword holding this test's own {@link #token()}, because the database is shared.
 */
@DisplayName("UC 1.3 – Browse Posts (Duyệt bài viết)")
class BrowsePostsTest extends UseCaseTestBase {

    // ================================================================ normal flow

    @Test
    @DisplayName("TC-1.3-01 [Luồng chính] – Khách mở Blog: chỉ bài đã đăng, mới nhất trước, thẻ bài đủ tiêu đề, trích đoạn, chủ đề, địa điểm, tác giả có tag, lượt thích, bình luận, thời gian")
    void guestBrowsesPublishedPostsNewestFirstWithFullCards() throws Exception {
        String tk = token();
        long author = member();
        grantMentorTag(author);
        long older = insertBlog(author, "POST", "Kinh nghiệm thuê nhà " + tk, "Nội dung ngắn.", "PUBLISHED",
                minutesAgo(30), topic("Nhà ở"));
        String longContent = ("Đi chợ ở Nhật cần chú ý giá cả và giờ giảm giá buổi tối. ").repeat(10);
        long newer = insertBlog(author, "POST", "Mẹo đi chợ " + tk, longContent, "PUBLISHED", minutesAgo(5),
                topic("Mua sắm"), topic("Kinh nghiệm"));
        long place = place("Siêu thị Gyomu " + tk);
        jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", newer, place);

        JsonNode page = data(getAs(null, "/api/blogs?q={q}", tk).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)));

        assertThat(ids(page)).containsExactly(newer, older);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(10);
        assertThat(page.get("totalItems").asLong()).isEqualTo(2);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("filtered").asBoolean()).isTrue();

        JsonNode card = page.get("items").get(0);
        assertThat(card.get("type").asText()).isEqualTo("POST");
        assertThat(card.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(card.get("title").asText()).isEqualTo("Mẹo đi chợ " + tk);
        // Excerpt: one line, at most 180 characters + "…"
        assertThat(card.get("excerpt").asText()).endsWith("…").hasSizeLessThanOrEqualTo(181);
        assertThat(texts(card.get("topics"), "name")).containsExactlyInAnyOrder("Mua sắm", "Kinh nghiệm");
        assertThat(texts(card.get("places"), "name")).containsExactly("Siêu thị Gyomu " + tk);
        assertThat(card.get("author").get("id").asLong()).isEqualTo(author);
        assertThat(card.get("author").get("displayName").asText()).isEqualTo(displayName(author));
        assertThat(texts(card.get("author").get("tags"), "name")).containsExactly("Mentor");
        assertThat(card.get("likeCount").asInt()).isZero();
        assertThat(card.get("commentCount").asInt()).isZero();
        assertThat(card.hasNonNull("createdAt")).isTrue();
    }

    @Test
    @DisplayName("TC-1.3-02 [Luồng chính] – Phân trang 10 bài mỗi trang: 12 bài → trang 1 có 10 bài, trang 2 có 2 bài, không trùng")
    void pagesHoldTenPostsEach() throws Exception {
        String tk = token();
        long author = member();
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            created.add(insertBlog(author, "POST", "Bài số " + i + " " + tk, "Nội dung " + i, "PUBLISHED",
                    minutesAgo(60 - i), topic("Kinh nghiệm")));
        }

        JsonNode first = data(getAs(null, "/api/blogs?q={q}&page=0", tk).andExpect(status().isOk()));
        JsonNode second = data(getAs(null, "/api/blogs?q={q}&page=1", tk).andExpect(status().isOk()));

        assertThat(first.get("items")).hasSize(10);
        assertThat(second.get("items")).hasSize(2);
        assertThat(first.get("totalItems").asLong()).isEqualTo(12);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(second.get("page").asInt()).isEqualTo(1);
        List<Long> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        // Newest first across both pages, every post once
        List<Long> expected = new ArrayList<>(created);
        java.util.Collections.reverse(expected);
        assertThat(all).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("TC-1.3-03 [Luồng chính] – Bài \"Đã ghim\" luôn ở đầu danh sách khi sắp xếp \"Mới nhất\"")
    void pinnedPostStaysOnTopOfNewest() throws Exception {
        String tk = token();
        long author = member();
        long pinnedOld = insertBlog(author, "POST", "Nội quy cộng đồng " + tk, "Đọc trước khi đăng.", "PUBLISHED",
                daysAgo(20), topic("Khác"));
        jdbc.update("UPDATE blogs SET is_pinned = TRUE WHERE id = ?", pinnedOld);
        long fresh = insertBlog(author, "POST", "Bài mới " + tk, "Mới viết.", "PUBLISHED", minutesAgo(1),
                topic("Kinh nghiệm"));

        JsonNode page = data(getAs(null, "/api/blogs?q={q}&sort=NEWEST", tk).andExpect(status().isOk()));

        assertThat(ids(page)).containsExactly(pinnedOld, fresh);
        assertThat(page.get("items").get(0).get("pinned").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("TC-1.3-04 [Luồng chính] – Tìm theo tiêu đề không phân biệt dấu và chữ hoa/thường")
    void keywordIgnoresAccentsAndCase() throws Exception {
        String tk = token();
        long author = member();
        long post = insertBlog(author, "POST", "Phân loại RÁC ở Đà Nẵng " + tk, "Cách bỏ rác.", "PUBLISHED",
                minutesAgo(3), topic("Kinh nghiệm"));

        for (String q : List.of("phan loai rac o da nang " + tk, "PHÂN LOẠI rác ở đà NẴNG " + tk, "Phan Loai RAC O DA NANG " + tk)) {
            JsonNode page = data(getAs(null, "/api/blogs?q={q}", q).andExpect(status().isOk()));
            assertThat(ids(page)).as("tìm \"%s\"", q).containsExactly(post);
        }
    }

    @Test
    @DisplayName("TC-1.3-05 [Luồng chính] – Từ khóa khớp tiêu đề, nội dung hoặc tên địa điểm liên quan (địa điểm đã bị gỡ thì không khớp)")
    void keywordMatchesTitleContentAndLinkedPlaceName() throws Exception {
        String tk = token();
        long author = member();
        long byTitle = insertBlog(author, "POST", "Tiêu đề có " + tk, "Không có từ khóa.", "PUBLISHED",
                minutesAgo(30), topic("Kinh nghiệm"));
        long byContent = insertBlog(author, "POST", "Tiêu đề bình thường", "Nội dung nhắc tới " + tk + " ở giữa.",
                "PUBLISHED", minutesAgo(20), topic("Kinh nghiệm"));
        long byPlace = insertBlog(author, "POST", "Ăn phở ở Tokyo", "Quán rất ngon.", "PUBLISHED", minutesAgo(10),
                topic("Ăn uống"));
        jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", byPlace, place("Quán Phở " + tk));
        long byRemovedPlace = insertBlog(author, "POST", "Quán đã đóng", "Không còn.", "PUBLISHED", minutesAgo(5),
                topic("Ăn uống"));
        long removed = place("Quán cũ " + tk);
        jdbc.update("UPDATE places SET status = 'REMOVED', removed_reason = 'test' WHERE id = ?", removed);
        jdbc.update("INSERT INTO blog_place_links (blog_id, place_id) VALUES (?, ?)", byRemovedPlace, removed);

        JsonNode page = data(getAs(null, "/api/blogs?q={q}", tk).andExpect(status().isOk()));

        assertThat(ids(page)).containsExactly(byPlace, byContent, byTitle);
        assertThat(ids(page)).doesNotContain(byRemovedPlace);
    }

    @Test
    @DisplayName("TC-1.3-06 [Luồng chính] – Lọc theo một hoặc nhiều chủ đề (bài có ít nhất một chủ đề được chọn); chủ đề không tồn tại hoặc đã tắt bị bỏ qua")
    void topicFilterMatchesAnySelectedTopicAndIgnoresUnknownOrInactiveTopics() throws Exception {
        String tk = token();
        long author = member();
        long visa = insertBlog(author, "POST", "Gia hạn visa " + tk, "Thủ tục.", "PUBLISHED", minutesAgo(30),
                topic("Visa và cư trú"));
        long food = insertBlog(author, "POST", "Món ngon " + tk, "Ăn gì.", "PUBLISHED", minutesAgo(20),
                topic("Ăn uống"));
        long other = insertBlog(author, "POST", "Linh tinh " + tk, "Khác.", "PUBLISHED", minutesAgo(10),
                topic("Khác"));
        jdbc.update("INSERT INTO blog_topics (name, sort_order, is_active) VALUES (?, 99, FALSE)", "Tắt " + tk);
        long inactive = jdbc.queryForObject("SELECT id FROM blog_topics WHERE name = ?", Long.class, "Tắt " + tk);

        JsonNode one = data(getAs(null, "/api/blogs?q={q}&topicIds={t}", tk, topic("Visa và cư trú"))
                .andExpect(status().isOk()));
        assertThat(ids(one)).containsExactly(visa);
        assertThat(one.get("filtered").asBoolean()).isTrue();

        JsonNode two = data(getAs(null, "/api/blogs?q={q}&topicIds={a},{b}", tk, topic("Visa và cư trú"),
                topic("Ăn uống")).andExpect(status().isOk()));
        assertThat(ids(two)).containsExactly(food, visa);

        // An unknown id and an inactive topic are ignored: the list is the same as without a topic filter
        JsonNode ignored = data(getAs(null, "/api/blogs?q={q}&topicIds={a},{b}", tk, 987654321L, inactive)
                .andExpect(status().isOk()));
        assertThat(ids(ignored)).containsExactly(other, food, visa);

        // The inactive topic is not offered in the filter
        JsonNode overview = data(getAs(null, "/api/blogs/overview").andExpect(status().isOk()));
        assertThat(texts(overview.get("topics"), "name")).doesNotContain("Tắt " + tk).contains("Visa và cư trú");
    }

    @Test
    @DisplayName("TC-1.3-07 [Luồng chính] – Sắp xếp \"Nhiều thích\" (LIKES) và \"Nhiều bình luận\" (COMMENTS)")
    void sortsByLikesAndByComments() throws Exception {
        String tk = token();
        long author = member();
        long a = insertBlog(author, "POST", "A " + tk, "a", "PUBLISHED", minutesAgo(30), topic("Kinh nghiệm"));
        long b = insertBlog(author, "POST", "B " + tk, "b", "PUBLISHED", minutesAgo(20), topic("Kinh nghiệm"));
        long c = insertBlog(author, "POST", "C " + tk, "c", "PUBLISHED", minutesAgo(10), topic("Kinh nghiệm"));
        jdbc.update("UPDATE blogs SET like_count = 9, comment_count = 1 WHERE id = ?", a);
        jdbc.update("UPDATE blogs SET like_count = 2, comment_count = 7 WHERE id = ?", b);
        jdbc.update("UPDATE blogs SET like_count = 5, comment_count = 3 WHERE id = ?", c);

        assertThat(ids(data(getAs(null, "/api/blogs?q={q}&sort=LIKES", tk).andExpect(status().isOk()))))
                .containsExactly(a, c, b);
        assertThat(ids(data(getAs(null, "/api/blogs?q={q}&sort=COMMENTS", tk).andExpect(status().isOk()))))
                .containsExactly(b, c, a);
        // Lower case is accepted too; no sort = newest first
        assertThat(ids(data(getAs(null, "/api/blogs?q={q}&sort=likes", tk).andExpect(status().isOk()))))
                .containsExactly(a, c, b);
        assertThat(ids(data(getAs(null, "/api/blogs?q={q}", tk).andExpect(status().isOk()))))
                .containsExactly(c, b, a);
    }

    @Test
    @DisplayName("TC-1.3-08 [Tiền điều kiện] – Chỉ liệt kê bài đã đăng của tài khoản đang hoạt động: không câu hỏi, không bài ẩn/bị từ chối/bị gỡ, không bài của tài khoản bị khóa hoặc đã xóa")
    void listsOnlyPublishedPostsOfActiveAccounts() throws Exception {
        String tk = token();
        long author = member();
        long published = insertBlog(author, "POST", "Đã đăng " + tk, "x", "PUBLISHED", minutesAgo(50), topic("Kinh nghiệm"));
        long question = insertBlog(author, "QA", "Câu hỏi " + tk, "x", "PUBLISHED", minutesAgo(45), topic("Kinh nghiệm"));
        long hidden = insertBlog(author, "POST", "Bị ẩn " + tk, "x", "HIDDEN", minutesAgo(40), topic("Kinh nghiệm"));
        long rejected = insertBlog(author, "POST", "Bị từ chối " + tk, "x", "REJECTED", minutesAgo(35), topic("Kinh nghiệm"));
        long removed = insertBlog(author, "POST", "Bị gỡ " + tk, "x", "REMOVED", minutesAgo(30), topic("Kinh nghiệm"));
        long lockedAuthor = member();
        long ofLocked = insertBlog(lockedAuthor, "POST", "Của tài khoản bị khóa " + tk, "x", "PUBLISHED", minutesAgo(25),
                topic("Kinh nghiệm"));
        setStatus(lockedAuthor, "LOCKED");
        long deletedAuthor = member();
        long ofDeleted = insertBlog(deletedAuthor, "POST", "Của tài khoản đã xóa " + tk, "x", "PUBLISHED", minutesAgo(20),
                topic("Kinh nghiệm"));
        setStatus(deletedAuthor, "DELETED");
        long viewer = member();

        for (Long who : new Long[]{null, viewer, author}) {
            JsonNode page = data(getAs(who, "/api/blogs?q={q}", tk).andExpect(status().isOk()));
            assertThat(ids(page)).as("người xem %s", who).containsExactly(published)
                    .doesNotContain(question, hidden, rejected, removed, ofLocked, ofDeleted);
        }

        // Nothing was deleted: once the account is unlocked its post shows again
        setStatus(lockedAuthor, "ACTIVE");
        assertThat(ids(data(getAs(null, "/api/blogs?q={q}", tk).andExpect(status().isOk()))))
                .containsExactly(ofLocked, published);
    }

    @Test
    @DisplayName("TC-1.3-09 [Luồng thay thế 2b] – Tác giả đã đăng nhập thấy bài của mình đang quét ảnh/video (PROCESSING); khách và người khác không thấy")
    void authorAlsoSeesOwnPostsBeingScanned() throws Exception {
        String tk = token();
        long author = member();
        long scanning = insertBlog(author, "POST", "Đang quét " + tk, "x", "PROCESSING", minutesAgo(5), topic("Kinh nghiệm"));
        long published = insertBlog(author, "POST", "Đã đăng " + tk, "x", "PUBLISHED", minutesAgo(10), topic("Kinh nghiệm"));
        long other = member();

        JsonNode own = data(getAs(author, "/api/blogs?q={q}", tk).andExpect(status().isOk()));
        assertThat(ids(own)).containsExactly(scanning, published);
        assertThat(own.get("items").get(0).get("status").asText()).isEqualTo("PROCESSING");
        assertThat(own.get("totalItems").asLong()).isEqualTo(2);

        assertThat(ids(data(getAs(other, "/api/blogs?q={q}", tk).andExpect(status().isOk())))).containsExactly(published);
        assertThat(ids(data(getAs(null, "/api/blogs?q={q}", tk).andExpect(status().isOk())))).containsExactly(published);
    }

    // ================================================================ validation of the criteria

    @Test
    @DisplayName("TC-1.3-10 [Biên / Luồng thay thế 4a] – Từ khóa đúng 100 ký tự được chấp nhận; 101 ký tự bị từ chối \"Từ khóa tìm kiếm tối đa 100 ký tự.\"")
    void keywordOfHundredCharactersPassesAndHundredOneIsRefused() throws Exception {
        String tk = token();                                  // 13 characters
        long author = member();
        String title = tk + "x".repeat(87);                    // 100 characters
        long post = insertBlog(author, "POST", title, "x", "PUBLISHED", minutesAgo(1), topic("Kinh nghiệm"));

        JsonNode page = data(getAs(null, "/api/blogs?q={q}", title).andExpect(status().isOk()));
        assertThat(ids(page)).containsExactly(post);

        // Spaces around the keyword are not counted
        getAs(null, "/api/blogs?q={q}", "  " + title + "  ").andExpect(status().isOk());

        getAs(null, "/api/blogs?q={q}", title + "y")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Từ khóa tìm kiếm tối đa 100 ký tự."));
    }

    @Test
    @DisplayName("TC-1.3-11 [Luồng thay thế 4a] – Cách sắp xếp không hợp lệ bị từ chối \"Giá trị lọc không hợp lệ: <giá trị>\"")
    void unknownSortIsRefused() throws Exception {
        getAs(null, "/api/blogs?sort=POPULAR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Giá trị lọc không hợp lệ: POPULAR"));
    }

    @Test
    @DisplayName("TC-1.3-12 [Luồng thay thế 4] – Trang vượt quá trang cuối được đưa về trang cuối")
    void pagePastTheEndBecomesTheLastPage() throws Exception {
        String tk = token();
        long author = member();
        for (int i = 0; i < 3; i++) {
            insertBlog(author, "POST", "Bài " + i + " " + tk, "x", "PUBLISHED", minutesAgo(10 - i), topic("Kinh nghiệm"));
        }

        JsonNode page = data(getAs(null, "/api/blogs?q={q}&page=7", tk).andExpect(status().isOk()));

        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("items")).hasSize(3);

        // A negative page is read as the first page
        assertThat(data(getAs(null, "/api/blogs?q={q}&page=-3", tk).andExpect(status().isOk())).get("page").asInt())
                .isZero();
    }

    @Test
    @DisplayName("TC-1.3-13 [Luồng thay thế 6a] – Không có bài khớp: danh sách rỗng, totalItems 0, filtered = true (trang hiện MSG33 \"Xóa bộ lọc\"); chỉ đổi cách sắp xếp thì filtered = false")
    void noMatchGivesAnEmptyFilteredPage() throws Exception {
        JsonNode none = data(getAs(null, "/api/blogs?q={q}", token()).andExpect(status().isOk()));

        assertThat(none.get("items")).isEmpty();
        assertThat(none.get("totalItems").asLong()).isZero();
        assertThat(none.get("totalPages").asInt()).isEqualTo(1);
        assertThat(none.get("filtered").asBoolean()).isTrue();

        JsonNode sortOnly = data(getAs(null, "/api/blogs?sort=COMMENTS").andExpect(status().isOk()));
        assertThat(sortOnly.get("filtered").asBoolean()).isFalse();
        JsonNode topicOnly = data(getAs(null, "/api/blogs?topicIds={t}", topic("Y tế")).andExpect(status().isOk()));
        assertThat(topicOnly.get("filtered").asBoolean()).isTrue();
    }

    // ================================================================ overview (featured, topics, side cards)

    @Test
    @DisplayName("TC-1.3-14 [Luồng chính] – Tổng quan Blog: bài nổi bật tuần/tháng (tối đa featured_top_n, mỗi tác giả một bài, chỉ bài đã đăng), danh sách chủ đề đang bật (\"Khác\" cuối), \"Chủ đề phổ biến\" ≤ 5, \"Senpai viết nhiều\" 30 ngày")
    void overviewHoldsFeaturedPostsTopicsAndSideCards() throws Exception {
        JsonNode overview = data(getAs(null, "/api/blogs/overview").andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)));

        JsonNode featured = overview.get("featured");
        int topN = Integer.parseInt(jdbc.queryForObject(
                "SELECT setting_value FROM system_settings WHERE setting_key = 'featured_top_n'", String.class).strip());
        if (featured.get("period").isNull()) {
            assertThat(featured.get("posts")).isEmpty();
        } else {
            assertThat(featured.get("period").asText()).isIn("WEEK", "MONTH");
            assertThat(featured.get("posts").size()).isBetween(1, topN);
            Set<Long> authors = new HashSet<>();
            for (JsonNode post : featured.get("posts")) {
                assertThat(post.get("type").asText()).isEqualTo("POST");
                assertThat(post.get("status").asText()).isEqualTo("PUBLISHED");
                assertThat(authors.add(post.get("author").get("id").asLong())).as("mỗi tác giả một bài").isTrue();
            }
        }

        List<String> topics = texts(overview.get("topics"), "name");
        assertThat(topics).contains("Kinh nghiệm", "Cảnh báo lừa đảo", "Visa và cư trú");
        assertThat(topics.get(topics.size() - 1)).isEqualTo("Khác");

        JsonNode popular = overview.get("popularTopics");
        assertThat(popular.size()).isLessThanOrEqualTo(5);
        for (int i = 1; i < popular.size(); i++) {
            assertThat(popular.get(i).get("postCount").asLong())
                    .isLessThanOrEqualTo(popular.get(i - 1).get("postCount").asLong());
        }

        assertThat(overview.get("topWritersDays").asInt()).isEqualTo(30);
        JsonNode writers = overview.get("topWriters");
        assertThat(writers.size()).isLessThanOrEqualTo(5);
        for (int i = 1; i < writers.size(); i++) {
            assertThat(writers.get(i).get("postCount").asLong())
                    .isLessThanOrEqualTo(writers.get(i - 1).get("postCount").asLong());
        }
    }

    @Test
    @DisplayName("TC-1.3-15 [Luồng chính] – \"Senpai viết nhiều\" chỉ đếm bài đã đăng trong 30 ngày qua của tài khoản đang hoạt động")
    void topWritersCountOnlyTheLastThirtyDaysOfActiveAccounts() throws Exception {
        String tk = token();
        long prolific = member();
        long oldWriter = member();
        // One post more than today's top writer, so this member leads whatever the database already holds; the old
        // writer has even more posts, but 40 days old, so they must not count
        JsonNode before = data(getAs(null, "/api/blogs/overview").andExpect(status().isOk())).get("topWriters");
        int recent = Math.max(12, before.isEmpty() ? 0 : before.get(0).get("postCount").asInt() + 1);
        for (int i = 0; i < recent; i++) {
            insertBlog(prolific, "POST", "Bài " + i + " " + tk, "x", "PUBLISHED", minutesAgo(i + 1), topic("Kinh nghiệm"));
        }
        for (int i = 0; i < recent + 5; i++) {
            insertBlog(oldWriter, "POST", "Bài cũ " + i + " " + tk, "x", "PUBLISHED", daysAgo(40), topic("Kinh nghiệm"));
        }
        // Questions and unpublished posts do not count
        insertBlog(prolific, "QA", "Câu hỏi " + tk, "x", "PUBLISHED", minutesAgo(1), topic("Kinh nghiệm"));
        insertBlog(prolific, "POST", "Đang quét " + tk, "x", "PROCESSING", minutesAgo(1), topic("Kinh nghiệm"));

        try {
            JsonNode writers = data(getAs(null, "/api/blogs/overview").andExpect(status().isOk())).get("topWriters");
            JsonNode top = writers.get(0);
            assertThat(top.get("author").get("id").asLong()).isEqualTo(prolific);
            assertThat(top.get("postCount").asLong()).isEqualTo(recent);
            assertThat(authorIds(writers)).doesNotContain(oldWriter);

            // A locked account leaves the card at once
            setStatus(prolific, "LOCKED");
            JsonNode after = data(getAs(null, "/api/blogs/overview").andExpect(status().isOk())).get("topWriters");
            assertThat(authorIds(after)).doesNotContain(prolific);
        } finally {
            // Both accounts end locked: their posts are hidden everywhere and never weigh on later runs
            setStatus(prolific, "LOCKED");
            setStatus(oldWriter, "LOCKED");
        }
    }

    // ================================================================ helpers

    private static Instant minutesAgo(int minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(card -> ids.add(card.get("id").asLong()));
        return ids;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.get(field).asText()));
        return values;
    }

    private static List<Long> authorIds(JsonNode writers) {
        List<Long> ids = new ArrayList<>();
        writers.forEach(w -> ids.add(w.get("author").get("id").asLong()));
        return ids;
    }
}
