package com.sep490.vsa.feature.qa;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Media;
import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.Tag;
import com.sep490.vsa.feature.blog.BlogDtos.TopicCount;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.blog.BlogFeedRepository;
import com.sep490.vsa.feature.qa.QaDtos.Answerer;
import com.sep490.vsa.feature.qa.QaDtos.Overview;
import com.sep490.vsa.feature.qa.QaDtos.QuestionCard;
import com.sep490.vsa.feature.qa.QaDtos.QuestionPage;
import com.sep490.vsa.feature.qa.QaDtos.Stats;
import com.sep490.vsa.feature.qa.QaRepository.AnswererRow;
import com.sep490.vsa.feature.qa.QaRepository.Row;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.17 "Browse Questions" (Duyệt câu hỏi): the filters of the Hỏi-Đáp list ({@link QaListQuery#of}),
 * the page of question cards ({@link QaService#list}) and the side cards ({@link QaService#overview}: topics,
 * "Cộng đồng hỏi đáp", "Chủ đề hay được hỏi", "Senpai giải đáp nhiều").
 * <p>
 * The database is not used: {@link QaRepository} and {@link BlogFeedRepository} (topics, places, media, likes and
 * identity tags of a page of cards) are mocks; the clock is fixed at 20/11/2026 12:00 Japan time. The SQL rules of
 * "solved" and "unanswered" live in {@link QaRepository}: here the service must carry the flags it gets to the cards.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("QaService – Duyệt câu hỏi: bộ lọc, danh sách câu hỏi, thẻ bên (UC 1.17)")
class QaServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final long MEMBER_ID = 10L;
    private static final String BASE = "/media/";
    private static final String KEYWORD_TOO_LONG = "Từ khóa tìm kiếm tối đa 100 ký tự.";

    private QaRepository repository;
    private BlogFeedRepository cards;
    private QaService service;

    @BeforeEach
    void setUp() {
        repository = mock(QaRepository.class);
        cards = mock(BlogFeedRepository.class);
        service = new QaService(repository, cards, Clock.fixed(NOW, ZoneOffset.UTC), BASE);
    }

    // ---------------------------------------------------------------- helpers

    /** A published question by {@code authorId}, nobody answered yet. */
    private static Row row(long id, long authorId) {
        return new Row(id, "PUBLISHED", false, false, true, "Câu hỏi " + id + "?", "Mô tả " + id, authorId,
                "User " + authorId, null, 0, 0, NOW.minus(Duration.ofMinutes(id)));
    }

    private static Row flags(long id, boolean solved, boolean unanswered, int answers) {
        return new Row(id, "PUBLISHED", false, solved, unanswered, "Câu hỏi " + id + "?", "x", 50L, "User 50", null, 0,
                answers, NOW);
    }

    private static Row withContent(long id, String content) {
        return new Row(id, "PUBLISHED", false, false, true, "Câu hỏi " + id + "?", content, 50L, "User 50", null, 0, 0,
                NOW);
    }

    private static QaListQuery query() {
        return QaListQuery.of(null, null, null, null, null, null, null);
    }

    private static QaListQuery status(String status) {
        return QaListQuery.of(null, null, status, null, null, null, null);
    }

    private QuestionPage listWith(QaListQuery query, long total, List<Row> rows) {
        when(repository.count(any())).thenReturn(total);
        when(repository.find(any())).thenReturn(rows);
        return service.list(query);
    }

    private QaListQuery countedQuery() {
        ArgumentCaptor<QaListQuery> captor = ArgumentCaptor.forClass(QaListQuery.class);
        verify(repository).count(captor.capture());
        return captor.getValue();
    }

    private QaListQuery foundQuery() {
        ArgumentCaptor<QaListQuery> captor = ArgumentCaptor.forClass(QaListQuery.class);
        verify(repository).find(captor.capture());
        return captor.getValue();
    }

    private String excerptOf(String content) {
        return listWith(query(), 1, List.of(withContent(1, content))).items().get(0).excerpt();
    }

    @SuppressWarnings("unchecked")
    private List<Long> tagsAskedFor() {
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(cards).tagsOf(captor.capture());
        return new ArrayList<>(captor.getValue());
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

    private static Author author(long id) {
        return new Author(id, "User " + id, null, List.of());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. QaListQuery.of – UC 1.17 bước 3-5: từ khóa, chủ đề, trạng thái, sắp xếp, phân trang (UTC-177 → UTC-189)")
    class QuestionQueryParsing {

        @Test
        @DisplayName("UTC-177 Không truyền gì: không từ khóa, mọi chủ đề, \"Tất cả\", \"Mới nhất\", trang 0, 10 câu, không lọc")
        void utc177_defaultsWhenNothingIsGiven() {
            QaListQuery q = query();

            assertThat(q.keyword()).isNull();
            assertThat(q.topicIds()).isEmpty();
            assertThat(q.status()).isEqualTo(QaListQuery.Status.ALL);
            assertThat(q.sort()).isEqualTo(QaListQuery.Sort.NEWEST);
            assertThat(q.page()).isZero();
            assertThat(q.size()).isEqualTo(10);
            assertThat(q.viewerId()).isNull();
            assertThat(q.filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-178 Từ khóa bỏ dấu, chữ thường, gộp khoảng trắng: \"  Gia HẠN  visa \" → \"gia han visa\"")
        void utc178_keywordIsFolded() {
            QaListQuery q = QaListQuery.of("  Gia HẠN  visa ", null, null, null, null, null, null);

            assertThat(q.keyword()).isEqualTo("gia han visa");
            assertThat(q.filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-179 Từ khóa rỗng hoặc chỉ khoảng trắng: không tìm kiếm (3 lần)")
        void utc179_blankKeywordIsNoSearch() {
            for (String blank : List.of("", "   ", "\t\n")) {
                assertThat(QaListQuery.of(blank, null, null, null, null, null, null).keyword()).isNull();
            }
        }

        @Test
        @DisplayName("UTC-180 Từ khóa đúng 100 ký tự được nhận")
        void utc180_keywordOf100CharactersIsAccepted() {
            assertThat(QaListQuery.of("x".repeat(100), null, null, null, null, null, null).keyword()).hasSize(100);
        }

        @Test
        @DisplayName("UTC-181 Từ khóa 101 ký tự bị từ chối \"Từ khóa tìm kiếm tối đa 100 ký tự.\"")
        void utc181_keywordOf101CharactersIsRefused() {
            assertBadRequest(() -> QaListQuery.of("x".repeat(101), null, null, null, null, null, null),
                    KEYWORD_TOO_LONG);
        }

        @Test
        @DisplayName("UTC-182 Trạng thái không phân biệt hoa thường: unanswered, \" Unsolved \", SOLVED, all (4 lần)")
        void utc182_statusIsParsedIgnoringCase() {
            assertThat(status("unanswered").status()).isEqualTo(QaListQuery.Status.UNANSWERED);
            assertThat(status(" Unsolved ").status()).isEqualTo(QaListQuery.Status.UNSOLVED);
            assertThat(status("SOLVED").status()).isEqualTo(QaListQuery.Status.SOLVED);
            assertThat(status("all").status()).isEqualTo(QaListQuery.Status.ALL);
        }

        @Test
        @DisplayName("UTC-183 Trạng thái không hợp lệ: \"Giá trị lọc không hợp lệ: CLOSED\"")
        void utc183_unknownStatusIsRefused() {
            assertBadRequest(() -> status("CLOSED"), "Giá trị lọc không hợp lệ: CLOSED");
        }

        @Test
        @DisplayName("UTC-184 Sắp xếp: likes → LIKES, answers → ANSWERS, rỗng → NEWEST (3 lần)")
        void utc184_sortIsParsed() {
            assertThat(QaListQuery.of(null, null, null, "likes", null, null, null).sort())
                    .isEqualTo(QaListQuery.Sort.LIKES);
            assertThat(QaListQuery.of(null, null, null, "answers", null, null, null).sort())
                    .isEqualTo(QaListQuery.Sort.ANSWERS);
            assertThat(QaListQuery.of(null, null, null, " ", null, null, null).sort())
                    .isEqualTo(QaListQuery.Sort.NEWEST);
        }

        @Test
        @DisplayName("UTC-185 Sắp xếp không hợp lệ: OLDEST và COMMENTS (của Blog) bị từ chối (2 lần)")
        void utc185_unknownSortIsRefused() {
            assertBadRequest(() -> QaListQuery.of(null, null, null, "OLDEST", null, null, null),
                    "Giá trị lọc không hợp lệ: OLDEST");
            assertBadRequest(() -> QaListQuery.of(null, null, null, "COMMENTS", null, null, null),
                    "Giá trị lọc không hợp lệ: COMMENTS");
        }

        @Test
        @DisplayName("UTC-186 Lọc trạng thái là có lọc; \"Tất cả\" và chỉ sắp xếp thì không")
        void utc186_statusIsAFilterSortIsNot() {
            assertThat(status("UNANSWERED").filtered()).isTrue();
            assertThat(status("SOLVED").filtered()).isTrue();
            assertThat(status("ALL").filtered()).isFalse();
            assertThat(QaListQuery.of(null, null, null, "ANSWERS", null, null, null).filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-187 Danh sách chủ đề: bỏ trùng, null, 0, số âm; giữ thứ tự")
        void utc187_topicIdsAreCleaned() {
            QaListQuery q = QaListQuery.of(null, Arrays.asList(4L, 4L, 0L, null, -3L, 9L), null, null, null, null,
                    null);

            assertThat(q.topicIds()).containsExactly(4L, 9L);
            assertThat(q.filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-188 Trang âm → 0; cỡ trang giữ trong 1–30: 0 → 1, 31 → 30, 999 → 30 (4 lần)")
        void utc188_pagingIsClamped() {
            assertThat(QaListQuery.of(null, null, null, null, -1, null, null).page()).isZero();
            assertThat(QaListQuery.of(null, null, null, null, null, 0, null).size()).isEqualTo(1);
            assertThat(QaListQuery.of(null, null, null, null, null, 31, null).size()).isEqualTo(30);
            assertThat(QaListQuery.of(null, null, null, null, null, 999, null).size()).isEqualTo(30);
        }

        @Test
        @DisplayName("UTC-189 Người xem đã đăng nhập được giữ (để thấy câu hỏi mình đang quét)")
        void utc189_viewerIdIsKept() {
            assertThat(QaListQuery.of(null, null, null, null, null, null, MEMBER_ID).viewerId()).isEqualTo(MEMBER_ID);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. list – UC 1.17 Duyệt câu hỏi: danh sách thẻ câu hỏi, lọc trạng thái, sắp xếp, phân trang (UTC-190 → UTC-212)")
    class BrowseQuestions {

        @Test
        @DisplayName("UTC-190 Khách xem danh sách: câu hỏi theo đúng thứ tự kho trả về, trang 0/1, không lọc")
        void utc190_guestGetsQuestionsInRepositoryOrder() {
            QuestionPage page = listWith(query(), 3, List.of(row(3, 50), row(2, 51), row(1, 52)));

            assertThat(page.items()).extracting(QuestionCard::id).containsExactly(3L, 2L, 1L);
            assertThat(page.page()).isZero();
            assertThat(page.size()).isEqualTo(10);
            assertThat(page.totalItems()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-191 Thẻ câu hỏi đủ thông tin: tiêu đề, trích đoạn, người hỏi có tag, chủ đề, địa điểm, ảnh, thích, số trả lời")
        void utc191_cardCarriesEveryField() {
            Row r = new Row(7L, "PUBLISHED", true, false, false, "Đi Ikebukuro thế nào?", "Mình cần hỏi về ga.", 50L,
                    "Lan", "/media/avatar/lan.jpg", 3, 4, NOW);
            TopicRef topic = new TopicRef(4L, "Đi lại");
            PlaceRef place = new PlaceRef(40L, "Ga Ikebukuro", "Nhà ga", "Toshima, Tokyo", 35.7295, 139.7109, "N1");
            Media photo = new Media(90L, "IMAGE", "/media/blog/2026/11/q.jpg", "image/jpeg", 800, 600);
            Tag tag = new Tag("Du học sinh", "#2E7D32");
            when(cards.topicsOf(List.of(7L))).thenReturn(Map.of(7L, List.of(topic)));
            when(cards.placesOf(List.of(7L))).thenReturn(Map.of(7L, List.of(place)));
            when(cards.mediaOf(List.of(7L), null, BASE)).thenReturn(Map.of(7L, List.of(photo)));
            when(cards.tagsOf(any())).thenReturn(Map.of(50L, List.of(tag)));

            QuestionCard card = listWith(query(), 1, List.of(r)).items().get(0);

            assertThat(card.id()).isEqualTo(7L);
            assertThat(card.status()).isEqualTo("PUBLISHED");
            assertThat(card.pinned()).isTrue();
            assertThat(card.title()).isEqualTo("Đi Ikebukuro thế nào?");
            assertThat(card.excerpt()).isEqualTo("Mình cần hỏi về ga.");
            assertThat(card.author()).isEqualTo(new Author(50L, "Lan", "/media/avatar/lan.jpg", List.of(tag)));
            assertThat(card.topics()).containsExactly(topic);
            assertThat(card.places()).containsExactly(place);
            assertThat(card.media()).containsExactly(photo);
            assertThat(card.likeCount()).isEqualTo(3);
            assertThat(card.answerCount()).isEqualTo(4);
            assertThat(card.createdAt()).isEqualTo(NOW);
            assertThat(card.liked()).isFalse();
        }

        @Test
        @DisplayName("UTC-192 Cờ trạng thái: đã giải quyết / chưa ai trả lời / có trả lời nhưng chưa giải quyết (3 câu)")
        void utc192_solvedAndUnansweredFlagsAreCarried() {
            QuestionPage page = listWith(query(), 3, List.of(flags(1, true, false, 2), flags(2, false, true, 0),
                    flags(3, false, false, 1)));

            assertThat(page.items()).extracting(QuestionCard::solved).containsExactly(true, false, false);
            assertThat(page.items()).extracting(QuestionCard::unanswered).containsExactly(false, true, false);
        }

        @Test
        @DisplayName("UTC-193 Chỉ có phản hồi (không câu trả lời trực tiếp): số trả lời 1 nhưng vẫn \"Chưa ai trả lời\"")
        void utc193_answerCountIncludesRepliesButUnansweredFollowsTheRule() {
            QuestionCard card = listWith(query(), 1, List.of(flags(1, false, true, 1))).items().get(0);

            assertThat(card.answerCount()).isEqualTo(1);
            assertThat(card.unanswered()).isTrue();
        }

        @Test
        @DisplayName("UTC-194 Câu hỏi không có chủ đề, địa điểm, ảnh, tag: các danh sách rỗng")
        void utc194_missingDetailsGiveEmptyLists() {
            QuestionCard card = listWith(query(), 1, List.of(row(1, 50))).items().get(0);

            assertThat(card.topics()).isEmpty();
            assertThat(card.places()).isEmpty();
            assertThat(card.media()).isEmpty();
            assertThat(card.author().tags()).isEmpty();
        }

        @Test
        @DisplayName("UTC-195 Lọc \"Chưa ai trả lời\", \"Chưa giải quyết\", \"Đã giải quyết\" được gửi xuống kho (3 lần)")
        void utc195_statusFilterIsPassedDown() {
            when(repository.count(any())).thenReturn(1L);
            when(repository.find(any())).thenReturn(List.of(row(1, 50)));

            for (String s : List.of("UNANSWERED", "UNSOLVED", "SOLVED")) {
                assertThat(service.list(status(s)).filtered()).isTrue();
            }

            ArgumentCaptor<QaListQuery> captor = ArgumentCaptor.forClass(QaListQuery.class);
            verify(repository, times(3)).find(captor.capture());
            assertThat(captor.getAllValues()).extracting(QaListQuery::status).containsExactly(
                    QaListQuery.Status.UNANSWERED, QaListQuery.Status.UNSOLVED, QaListQuery.Status.SOLVED);
        }

        @Test
        @DisplayName("UTC-196 Sắp xếp \"Nhiều thích\" và \"Nhiều trả lời\" được gửi xuống kho (2 lần)")
        void utc196_sortIsPassedDown() {
            when(repository.count(any())).thenReturn(1L);
            when(repository.find(any())).thenReturn(List.of(row(1, 50)));

            service.list(QaListQuery.of(null, null, null, "LIKES", null, null, null));
            service.list(QaListQuery.of(null, null, null, "ANSWERS", null, null, null));

            ArgumentCaptor<QaListQuery> captor = ArgumentCaptor.forClass(QaListQuery.class);
            verify(repository, times(2)).find(captor.capture());
            assertThat(captor.getAllValues()).extracting(QaListQuery::sort)
                    .containsExactly(QaListQuery.Sort.LIKES, QaListQuery.Sort.ANSWERS);
        }

        @Test
        @DisplayName("UTC-197 Từ khóa đã chuẩn hóa được gửi xuống kho cho cả đếm và tìm")
        void utc197_foldedKeywordIsPassedDown() {
            listWith(QaListQuery.of("Gia HẠN Visa", null, null, null, null, null, null), 1, List.of(row(1, 50)));

            assertThat(countedQuery().keyword()).isEqualTo("gia han visa");
            assertThat(foundQuery().keyword()).isEqualTo("gia han visa");
        }

        @Test
        @DisplayName("UTC-198 Thành viên id=10 đã thích câu hỏi 2: chỉ thẻ đó liked = true; ảnh theo người xem")
        void utc198_memberSeesWhichQuestionsTheyLike() {
            when(cards.likedBy(MEMBER_ID, List.of(3L, 2L, 1L))).thenReturn(Set.of(2L));

            QuestionPage page = listWith(QaListQuery.of(null, null, null, null, null, null, MEMBER_ID), 3,
                    List.of(row(3, 50), row(2, 51), row(1, 52)));

            assertThat(page.items()).extracting(QuestionCard::liked).containsExactly(false, true, false);
            verify(cards).mediaOf(List.of(3L, 2L, 1L), MEMBER_ID, BASE);
        }

        @Test
        @DisplayName("UTC-199 Khách: không câu hỏi nào \"đã thích\", ảnh theo luật của khách")
        void utc199_guestHasNoLikes() {
            QuestionPage page = listWith(query(), 2, List.of(row(2, 50), row(1, 51)));

            verify(cards).likedBy(null, List.of(2L, 1L));
            verify(cards).mediaOf(List.of(2L, 1L), null, BASE);
            assertThat(page.items()).extracting(QuestionCard::liked).containsOnly(false);
        }

        @Test
        @DisplayName("UTC-200 Người hỏi thấy câu hỏi của mình đang quét (PROCESSING): mã người xem được gửi xuống kho")
        void utc200_askerSeesOwnQuestionBeingScanned() {
            Row own = new Row(9L, "PROCESSING", false, false, true, "Câu hỏi đang quét?", "x", MEMBER_ID, "User 10",
                    null, 0, 0, NOW);

            QuestionCard card = listWith(QaListQuery.of(null, null, null, null, null, null, MEMBER_ID), 1,
                    List.of(own)).items().get(0);

            assertThat(countedQuery().viewerId()).isEqualTo(MEMBER_ID);
            assertThat(card.status()).isEqualTo("PROCESSING");
        }

        @Test
        @DisplayName("UTC-201 Tag được đọc một lần cho mỗi người hỏi, theo thứ tự xuất hiện")
        void utc201_tagsAreReadOncePerAsker() {
            listWith(query(), 3, List.of(row(3, 51), row(2, 50), row(1, 51)));

            assertThat(tagsAskedFor()).containsExactly(51L, 50L);
        }

        @Test
        @DisplayName("UTC-202 Trích đoạn dài được cắt ở ranh giới từ, ≤ 180 ký tự, kết thúc bằng \"…\"")
        void utc202_longDescriptionIsCutAtAWord() {
            String content = "Mình muốn hỏi cách đi từ Arakawa tới Ikebukuro ".repeat(10);

            String excerpt = excerptOf(content);

            assertThat(excerpt).endsWith("…");
            assertThat(excerpt.length()).isLessThanOrEqualTo(181);
            String shown = excerpt.substring(0, excerpt.length() - 1);
            assertThat(content).startsWith(shown);
            assertThat(content.charAt(shown.length())).isEqualTo(' ');
        }

        @Test
        @DisplayName("UTC-203 Mô tả đúng 180 ký tự giữ nguyên; 181 ký tự (một từ) thì cắt cứng còn 180 + \"…\" (2 lần)")
        void utc203_exactly180CharactersAreKept() {
            assertThat(excerptOf("q".repeat(180))).isEqualTo("q".repeat(180));
            assertThat(excerptOf("q".repeat(181))).isEqualTo("q".repeat(180) + "…");
        }

        @Test
        @DisplayName("UTC-204 Câu hỏi không có mô tả (null): trích đoạn rỗng")
        void utc204_noDescriptionGivesAnEmptyExcerpt() {
            assertThat(excerptOf(null)).isEmpty();
        }

        @Test
        @DisplayName("UTC-205 25 câu hỏi, trang 1: totalPages = 3, kho được hỏi trang 1")
        void utc205_pagingAsksForTheRequestedPage() {
            QuestionPage page = listWith(QaListQuery.of(null, null, null, null, 1, null, null), 25,
                    List.of(row(11, 50)));

            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalPages()).isEqualTo(3);
            assertThat(foundQuery().page()).isEqualTo(1);
        }

        @Test
        @DisplayName("UTC-206 Trang vượt quá trang cuối (trang 9 của 3 trang) trở thành trang cuối (trang 2)")
        void utc206_pagePastTheEndBecomesTheLastPage() {
            QuestionPage page = listWith(QaListQuery.of(null, null, null, null, 9, null, null), 25,
                    List.of(row(21, 50)));

            assertThat(page.page()).isEqualTo(2);
            assertThat(foundQuery().page()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-207 Chưa có câu hỏi nào: danh sách rỗng, 1 trang, không đọc chi tiết thẻ")
        void utc207_noQuestionAtAll() {
            QuestionPage page = listWith(QaListQuery.of(null, null, null, null, 2, null, null), 0, List.of());

            assertThat(page.items()).isEmpty();
            assertThat(page.totalItems()).isZero();
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.page()).isZero();
            assertThat(page.filtered()).isFalse();
            verify(repository, never()).find(any());
            verify(cards, never()).topicsOf(any());
        }

        @Test
        @DisplayName("UTC-208 Không câu hỏi nào khớp lọc \"Đã giải quyết\": danh sách rỗng, filtered = true")
        void utc208_noMatchForAStatusFilter() {
            QuestionPage page = listWith(status("SOLVED"), 0, List.of());

            assertThat(page.items()).isEmpty();
            assertThat(page.filtered()).isTrue();
        }

        @Test
        @DisplayName("UTC-209 Lọc chủ đề: chỉ giữ chủ đề đang bật (4), bỏ chủ đề không tồn tại hoặc đã tắt (99)")
        void utc209_topicFilterKeepsOnlyActiveTopics() {
            when(cards.activeTopics()).thenReturn(List.of(new TopicRef(4L, "Đi lại"), new TopicRef(5L, "Visa")));

            listWith(QaListQuery.of(null, List.of(4L, 99L), null, null, null, null, null), 1, List.of(row(1, 50)));

            assertThat(countedQuery().topicIds()).containsExactly(4L);
            assertThat(foundQuery().topicIds()).containsExactly(4L);
        }

        @Test
        @DisplayName("UTC-210 Chỉ chọn chủ đề đã tắt (99): bỏ qua, filtered = false")
        void utc210_onlyInactiveTopicsAreIgnored() {
            when(cards.activeTopics()).thenReturn(List.of(new TopicRef(4L, "Đi lại")));

            QuestionPage page = listWith(QaListQuery.of(null, List.of(99L), null, null, null, null, null), 2,
                    List.of(row(1, 50)));

            assertThat(countedQuery().topicIds()).isEmpty();
            assertThat(page.filtered()).isFalse();
        }

        @Test
        @DisplayName("UTC-211 Không lọc chủ đề: không đọc danh sách chủ đề")
        void utc211_noTopicFilterReadsNoTopicList() {
            listWith(query(), 1, List.of(row(1, 50)));

            verify(cards, never()).activeTopics();
        }

        @Test
        @DisplayName("UTC-212 Địa chỉ ảnh theo cấu hình app.media.public-base-url")
        void utc212_mediaUrlsUseTheConfiguredBase() {
            service = new QaService(repository, cards, Clock.fixed(NOW, ZoneOffset.UTC), "https://cdn.example/m/");

            listWith(query(), 1, List.of(row(1, 50)));

            verify(cards).mediaOf(List.of(1L), null, "https://cdn.example/m/");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. overview – UC 1.17 bước 2: chủ đề, \"Cộng đồng hỏi đáp\", chủ đề hay hỏi, \"Senpai giải đáp nhiều\" (UTC-213 → UTC-218)")
    class QaOverview {

        @Test
        @DisplayName("UTC-213 Thẻ bên: chủ đề đang bật, thống kê (12 câu, 4 chưa ai trả lời, 5 đã giải quyết), 5 chủ đề hay hỏi, 30 ngày")
        void utc213_overviewHoldsTopicsStatsAndPopularTopics() {
            List<TopicRef> topics = List.of(new TopicRef(4L, "Đi lại"), new TopicRef(9L, "Khác"));
            List<TopicCount> popular = List.of(new TopicCount(4L, "Đi lại", 7));
            Stats stats = new Stats(12, 4, 5);
            when(cards.activeTopics()).thenReturn(topics);
            when(repository.stats()).thenReturn(stats);
            when(repository.popularTopics(5)).thenReturn(popular);

            Overview overview = service.overview();

            assertThat(overview.topics()).isEqualTo(topics);
            assertThat(overview.stats()).isEqualTo(stats);
            assertThat(overview.popularTopics()).isEqualTo(popular);
            assertThat(overview.topAnswerersDays()).isEqualTo(30);
        }

        @Test
        @DisplayName("UTC-214 \"Senpai giải đáp nhiều\" tính từ đúng 30 ngày trước (21/10/2026 03:00 UTC), tối đa 5 người")
        void utc214_topAnswerersCountTheLastThirtyDays() {
            service.overview();

            verify(repository).topAnswerers(Instant.parse("2026-10-21T03:00:00Z"), 5);
        }

        @Test
        @DisplayName("UTC-215 Người giải đáp: tag định danh, số câu trả lời, số lần được chọn hay nhất, giữ thứ tự kho trả về")
        void utc215_topAnswerersCarryTagsAndCounts() {
            Tag tag = new Tag("Senpai", "#1565C0");
            when(repository.topAnswerers(any(), anyInt())).thenReturn(List.of(new AnswererRow(author(60), 9, 3),
                    new AnswererRow(author(61), 12, 1)));
            when(cards.tagsOf(List.of(60L, 61L))).thenReturn(Map.of(60L, List.of(tag)));

            List<Answerer> answerers = service.overview().topAnswerers();

            assertThat(answerers).extracting(a -> a.author().id()).containsExactly(60L, 61L);
            assertThat(answerers).extracting(Answerer::answerCount).containsExactly(9L, 12L);
            assertThat(answerers).extracting(Answerer::bestCount).containsExactly(3L, 1L);
            assertThat(answerers.get(0).author().tags()).containsExactly(tag);
            assertThat(answerers.get(1).author().tags()).isEmpty();
        }

        @Test
        @DisplayName("UTC-216 Chưa ai trả lời câu hỏi nào trong 30 ngày: danh sách người giải đáp rỗng")
        void utc216_noAnswererGivesAnEmptyList() {
            assertThat(service.overview().topAnswerers()).isEmpty();
        }

        @Test
        @DisplayName("UTC-217 Chưa có câu hỏi: thống kê 0 / 0 / 0")
        void utc217_emptyStats() {
            when(repository.stats()).thenReturn(new Stats(0, 0, 0));

            assertThat(service.overview().stats()).isEqualTo(new Stats(0, 0, 0));
        }

        @Test
        @DisplayName("UTC-218 Mở thẻ bên không đọc danh sách câu hỏi")
        void utc218_overviewDoesNotListQuestions() {
            service.overview();

            verify(repository, never()).find(any());
            verify(repository, never()).count(any());
        }
    }
}
