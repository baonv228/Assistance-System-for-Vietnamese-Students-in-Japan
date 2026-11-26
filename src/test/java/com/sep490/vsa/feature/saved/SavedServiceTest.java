package com.sep490.vsa.feature.saved;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.blog.BlogFeedRepository;
import com.sep490.vsa.feature.qa.QaRepository;
import com.sep490.vsa.feature.saved.SavedDtos.Counts;
import com.sep490.vsa.feature.saved.SavedDtos.SavedAuthor;
import com.sep490.vsa.feature.saved.SavedDtos.SavedBlog;
import com.sep490.vsa.feature.saved.SavedDtos.SavedGuide;
import com.sep490.vsa.feature.saved.SavedDtos.SavedPhrase;
import com.sep490.vsa.feature.saved.SavedDtos.SavedPlace;
import com.sep490.vsa.feature.saved.SavedDtos.SavedResponse;
import com.sep490.vsa.feature.saved.SavedRepository.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.13 "Browse Favorites" (Duyệt mục yêu thích, the "Đã lưu" page): {@link SavedService#list} and the
 * Java side of {@link SavedRepository} (row mapping, the guide summary cut by {@link SavedRepository#excerpt}, the
 * total of "Đã lưu (N)"), written from CLAUDE.md ("Đã lưu": saved ACTIVE places, a merged place shown as the place it
 * was merged into, PUBLISHED posts and questions of active accounts, published guides not deleted, phrases not
 * deleted, newest saved first; the rows of hidden items stay) and the use case test
 * {@code usecase/saved/BrowseFavoritesTest}.
 * <p>
 * The database is not used: {@link SavedRepository} and {@link BlogFeedRepository} are mocks for the service; for the
 * repository groups {@link JdbcTemplate} is a mock that hands the repository's row mappers mocked {@link ResultSet}s
 * and records the SQL (what is left out is decided in that SQL, so the tests check its conditions).
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("SavedService – Trang \"Đã lưu\" (UC 1.13)")
class SavedServiceTest {

    private static final long MEMBER_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final String MSG_NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";

    private SavedRepository repository;
    private BlogFeedRepository feed;
    private SavedService service;

    @BeforeEach
    void setUp() {
        repository = mock(SavedRepository.class);
        feed = mock(BlogFeedRepository.class);
        service = new SavedService(repository, feed);
        when(repository.account(MEMBER_ID)).thenReturn(Optional.of(new Account("USER", "ACTIVE")));
    }

    // ---------------------------------------------------------------- helpers

    private static Instant ago(int minutes) {
        return NOW.minus(Duration.ofMinutes(minutes));
    }

    private static SavedPlace savedPlace(long id, String name, boolean closed, int minutesAgo) {
        return new SavedPlace(id, name, "restaurant", "Quán ăn", "restaurant", "Taito, Tokyo", closed, ago(minutesAgo));
    }

    private static SavedBlog savedBlog(long id, String type, String title, boolean solved, int minutesAgo) {
        return new SavedBlog(id, type, title, new SavedAuthor(11L, "Tác giả", null), List.of(), 4, 2, solved,
                NOW.minus(Duration.ofDays(3)), ago(minutesAgo));
    }

    private static SavedGuide savedGuide(long id, String title, int minutesAgo) {
        return new SavedGuide(id, title, "Mang theo thẻ cư trú.", "Mới đến", "Ngân hàng", ago(minutesAgo));
    }

    private static SavedPhrase savedPhrase(long id, String japanese, int minutesAgo) {
        return new SavedPhrase(id, "PHRASE", japanese, "kore o kudasai", "Cho tôi cái này", "Cửa hàng tiện lợi",
                "store", ago(minutesAgo));
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(thrown.getMessage()).isEqualTo(message);
    }

    private void verifyNoListRead() {
        verify(repository, never()).places(anyLong());
        verify(repository, never()).blogs(anyLong(), anyString());
        verify(repository, never()).guides(anyLong());
        verify(repository, never()).phrases(anyLong());
        verifyNoInteractions(feed);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. list – UC 1.13 Duyệt mục yêu thích (UTC-060 → UTC-072)")
    class ListSaved {

        @Test
        @DisplayName("UTC-060 Có mục lưu đủ các loại: trang liệt kê từng loại và số lượng")
        void utc060_everyKind_listsAndCounts() {
            when(repository.places(MEMBER_ID)).thenReturn(List.of(savedPlace(401, "Ga Nippori", false, 1),
                    savedPlace(402, "Quán phở Ueno", false, 5)));
            when(repository.blogs(MEMBER_ID, "POST")).thenReturn(List.of(savedBlog(300, "POST", "Kinh nghiệm thuê nhà",
                    false, 2), savedBlog(302, "POST", "Đi chợ ở Ueno", false, 8)));
            when(repository.blogs(MEMBER_ID, "QA")).thenReturn(List.of(savedBlog(301, "QA", "Mua SIM ở đâu rẻ?",
                    true, 3)));
            when(repository.guides(MEMBER_ID)).thenReturn(List.of(savedGuide(61, "Mở tài khoản ngân hàng", 4)));
            when(repository.phrases(MEMBER_ID)).thenReturn(List.of(savedPhrase(501, "これをください", 6),
                    savedPhrase(502, "袋はいりません", 7), savedPhrase(503, "いくらですか", 9)));

            SavedResponse saved = service.list(MEMBER_ID);

            assertThat(saved.places()).extracting(SavedPlace::id).containsExactly(401L, 402L);
            assertThat(saved.posts()).extracting(SavedBlog::id).containsExactly(300L, 302L);
            assertThat(saved.questions()).extracting(SavedBlog::id).containsExactly(301L);
            assertThat(saved.guides()).extracting(SavedGuide::id).containsExactly(61L);
            assertThat(saved.phrases()).extracting(SavedPhrase::id).containsExactly(501L, 502L, 503L);
            assertThat(saved.counts()).isEqualTo(new Counts(2, 2, 1, 1, 3, 9));
        }

        @Test
        @DisplayName("UTC-061 Mỗi danh sách giữ thứ tự mục lưu gần nhất lên đầu")
        void utc061_eachListNewestSavedFirst() {
            when(repository.blogs(MEMBER_ID, "POST")).thenReturn(List.of(savedBlog(305, "POST", "Mới lưu", false, 1),
                    savedBlog(304, "POST", "Lưu hôm qua", false, 1440), savedBlog(303, "POST", "Lưu tuần trước",
                            false, 10080)));
            when(repository.places(MEMBER_ID)).thenReturn(List.of(savedPlace(410, "B", false, 2),
                    savedPlace(409, "A", false, 30)));

            SavedResponse saved = service.list(MEMBER_ID);

            assertThat(saved.posts()).extracting(SavedBlog::id).containsExactly(305L, 304L, 303L);
            assertThat(saved.posts()).extracting(SavedBlog::savedAt).isSortedAccordingTo((a, b) -> b.compareTo(a));
            assertThat(saved.places()).extracting(SavedPlace::id).containsExactly(410L, 409L);
        }

        @Test
        @DisplayName("UTC-062 Bài viết, câu hỏi kèm chủ đề; bài không có chủ đề nhận danh sách rỗng")
        void utc062_topicsAttached() {
            when(repository.blogs(MEMBER_ID, "POST")).thenReturn(List.of(savedBlog(300, "POST", "Thuê nhà", false, 1),
                    savedBlog(302, "POST", "Không chủ đề", false, 2)));
            when(repository.blogs(MEMBER_ID, "QA")).thenReturn(List.of(savedBlog(301, "QA", "Mua SIM", false, 3)));
            when(feed.topicsOf(any())).thenReturn(Map.of(
                    300L, List.of(new TopicRef(1L, "Nhà ở"), new TopicRef(2L, "Kinh nghiệm")),
                    301L, List.of(new TopicRef(5L, "Mua sắm"))));

            SavedResponse saved = service.list(MEMBER_ID);

            assertThat(saved.posts().get(0).topics()).extracting(TopicRef::name).containsExactly("Nhà ở", "Kinh nghiệm");
            assertThat(saved.posts().get(1).topics()).isEmpty();
            assertThat(saved.questions().get(0).topics()).containsExactly(new TopicRef(5L, "Mua sắm"));
        }

        @Test
        @DisplayName("UTC-063 Chủ đề được đọc một lần cho cả bài viết và câu hỏi")
        @SuppressWarnings("unchecked")
        void utc063_topicsReadOnceForPostsAndQuestions() {
            when(repository.blogs(MEMBER_ID, "POST")).thenReturn(List.of(savedBlog(300, "POST", "A", false, 1),
                    savedBlog(302, "POST", "B", false, 2)));
            when(repository.blogs(MEMBER_ID, "QA")).thenReturn(List.of(savedBlog(301, "QA", "C", false, 3)));

            service.list(MEMBER_ID);

            ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(feed).topicsOf(ids.capture());
            assertThat(ids.getValue()).containsExactly(300L, 302L, 301L);
        }

        @Test
        @DisplayName("UTC-064 Thông tin khác của bài giữ nguyên khi gắn chủ đề")
        void utc064_otherFieldsKept() {
            SavedBlog row = new SavedBlog(300L, "POST", "Kinh nghiệm thuê nhà", new SavedAuthor(11L, "Tác giả",
                    "/media/avatar/2026/11/a.jpg"), List.of(), 12, 7, false, NOW.minus(Duration.ofDays(3)), ago(15));
            when(repository.blogs(MEMBER_ID, "POST")).thenReturn(List.of(row));
            when(feed.topicsOf(any())).thenReturn(Map.of(300L, List.of(new TopicRef(1L, "Nhà ở"))));

            SavedBlog shown = service.list(MEMBER_ID).posts().get(0);

            assertThat(shown).usingRecursiveComparison().ignoringFields("topics").isEqualTo(row);
            assertThat(shown.topics()).containsExactly(new TopicRef(1L, "Nhà ở"));
        }

        @Test
        @DisplayName("UTC-065 Câu hỏi đã lưu giữ đúng nhãn \"Đã giải quyết\"")
        void utc065_questionSolvedFlagKept() {
            when(repository.blogs(MEMBER_ID, "QA")).thenReturn(List.of(savedBlog(301, "QA", "Đã có câu trả lời hay",
                    true, 1), savedBlog(303, "QA", "Chưa ai giải quyết", false, 2)));

            SavedResponse saved = service.list(MEMBER_ID);

            assertThat(saved.questions()).extracting(SavedBlog::solved).containsExactly(true, false);
            assertThat(saved.questions()).extracting(SavedBlog::type).containsOnly("QA");
        }

        @Test
        @DisplayName("UTC-066 Chưa lưu gì: mọi danh sách rỗng, tổng 0")
        void utc066_nothingSaved_emptyListsAndZeroCounts() {
            SavedResponse saved = service.list(MEMBER_ID);

            assertThat(saved.places()).isEmpty();
            assertThat(saved.posts()).isEmpty();
            assertThat(saved.questions()).isEmpty();
            assertThat(saved.guides()).isEmpty();
            assertThat(saved.phrases()).isEmpty();
            assertThat(saved.counts()).isEqualTo(new Counts(0, 0, 0, 0, 0, 0));
            verify(feed).topicsOf(List.of());
        }

        @Test
        @DisplayName("UTC-067 Chỉ đọc mục do chính thành viên lưu; bài viết và câu hỏi đọc riêng")
        void utc067_onlyOwnItemsRead() {
            service.list(MEMBER_ID);

            verify(repository).places(MEMBER_ID);
            verify(repository).blogs(MEMBER_ID, "POST");
            verify(repository).blogs(MEMBER_ID, "QA");
            verify(repository).guides(MEMBER_ID);
            verify(repository).phrases(MEMBER_ID);
            verify(repository, never()).places(eq(11L));
            verify(repository, never()).blogs(eq(11L), anyString());
        }

        @Test
        @DisplayName("UTC-068 Chỉ lưu địa điểm: tổng bằng số địa điểm")
        void utc068_onlyPlaces_totalEqualsPlaces() {
            when(repository.places(MEMBER_ID)).thenReturn(List.of(savedPlace(401, "A", false, 1),
                    savedPlace(402, "B", true, 2), savedPlace(403, "C", false, 3)));

            SavedResponse saved = service.list(MEMBER_ID);

            assertThat(saved.counts()).isEqualTo(new Counts(3, 0, 0, 0, 0, 3));
            assertThat(saved.places()).extracting(SavedPlace::closed).containsExactly(false, true, false);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-069 Tài khoản nhân viên mở \"Đã lưu\": bị từ chối")
        void utc069_staffOpensSaved_403(String role) {
            when(repository.account(20L)).thenReturn(Optional.of(new Account(role, "ACTIVE")));

            assertApiError(() -> service.list(20L), HttpStatus.FORBIDDEN, MSG18);
            verifyNoListRead();
        }

        @Test
        @DisplayName("UTC-070 Tài khoản bị khóa mở \"Đã lưu\": bị từ chối")
        void utc070_lockedMember_403() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.of(new Account("USER", "LOCKED")));

            assertApiError(() -> service.list(MEMBER_ID), HttpStatus.FORBIDDEN, MSG18);
            verifyNoListRead();
        }

        @Test
        @DisplayName("UTC-071 Tài khoản đã xóa mở \"Đã lưu\": bị từ chối")
        void utc071_deletedMember_403() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.of(new Account("USER", "DELETED")));

            assertApiError(() -> service.list(MEMBER_ID), HttpStatus.FORBIDDEN, MSG18);
            verifyNoListRead();
        }

        @Test
        @DisplayName("UTC-072 Tài khoản không còn trong hệ thống: phải đăng nhập")
        void utc072_accountGone_401() {
            when(repository.account(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.list(MEMBER_ID), HttpStatus.UNAUTHORIZED, MSG_NOT_SIGNED_IN);
            verifyNoListRead();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. SavedRepository – Đọc và chuyển dữ liệu các mục đã lưu (UTC-073 → UTC-080)")
    class RepositoryRows {

        private JdbcTemplate jdbc;
        private SavedRepository store;

        @BeforeEach
        void setUpStore() {
            jdbc = mock(JdbcTemplate.class);
            store = new SavedRepository(jdbc);
        }

        /** The repository's query whose SQL holds {@code sqlPart} answers {@code rows} through its own mapper. */
        private void answer(String sqlPart, Object[] params, ResultSet... rows) {
            Object[] matchers = new Object[params.length];
            doAnswer(inv -> {
                RowMapper<?> mapper = inv.getArgument(1);
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < rows.length; i++) {
                    out.add(mapper.mapRow(rows[i], i));
                }
                return out;
            }).when(jdbc).query(contains(sqlPart), any(RowMapper.class), argsMatching(params, matchers));
        }

        /** {@code eq} matchers for the given parameters, as the varargs of the stubbed call. */
        private Object[] argsMatching(Object[] params, Object[] out) {
            for (int i = 0; i < params.length; i++) {
                out[i] = eq(params[i]);
            }
            return out;
        }

        private String onlySql() {
            List<Invocation> calls = List.copyOf(mockingDetails(jdbc).getInvocations());
            assertThat(calls).hasSize(1);
            return calls.get(0).getArgument(0);
        }

        private boolean anyWrite() {
            return mockingDetails(jdbc).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("update"));
        }

        @Test
        @DisplayName("UTC-073 Tài khoản: đọc vai trò và trạng thái; không có dòng thì rỗng")
        void utc073_account_roleAndStatus() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("USER");
            when(rs.getString(2)).thenReturn("LOCKED");
            answer("FROM users", new Object[] {MEMBER_ID}, rs);

            assertThat(store.account(MEMBER_ID)).contains(new Account("USER", "LOCKED"));
            assertThat(store.account(99L)).isEmpty();
        }

        @Test
        @DisplayName("UTC-074 Địa điểm: địa điểm đã gộp hiện thành nơi giữ lại, một lần, chỉ nơi còn trên bản đồ")
        void utc074_places_keptPlaceOnceActiveOnly() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(401L);
            when(rs.getString(2)).thenReturn("Ga Nippori");
            when(rs.getString(3)).thenReturn("station");
            when(rs.getString(4)).thenReturn("Nhà ga");
            when(rs.getString(5)).thenReturn("train");
            when(rs.getString(6)).thenReturn("Arakawa, Tokyo");
            when(rs.getBoolean(7)).thenReturn(true);
            when(rs.getTimestamp(8)).thenReturn(Timestamp.from(ago(5)));
            answer("FROM saved_places", new Object[] {MEMBER_ID}, rs);

            List<SavedPlace> places = store.places(MEMBER_ID);

            assertThat(places).containsExactly(new SavedPlace(401L, "Ga Nippori", "station", "Nhà ga", "train",
                    "Arakawa, Tokyo", true, ago(5)));
            assertThat(onlySql())
                    .contains("CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END")
                    .contains("shown.status = 'ACTIVE'")
                    .contains("GROUP BY shown.id")
                    .contains("MAX(sp.saved_at)")
                    .contains("ORDER BY saved_at DESC");
        }

        @Test
        @DisplayName("UTC-075 Câu hỏi: chỉ bài đã đăng của tài khoản đang hoạt động; mục lưu của bài bị ẩn không bị xóa")
        void utc075_blogs_publishedActiveAuthorOnly() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(301L);
            when(rs.getString(2)).thenReturn("QA");
            when(rs.getString(3)).thenReturn("Mua SIM ở đâu rẻ?");
            when(rs.getLong(4)).thenReturn(11L);
            when(rs.getString(5)).thenReturn("Tác giả");
            when(rs.getString(6)).thenReturn("/media/avatar/2026/11/a.jpg");
            when(rs.getInt(7)).thenReturn(4);
            when(rs.getInt(8)).thenReturn(2);
            when(rs.getBoolean(9)).thenReturn(true);
            when(rs.getTimestamp(10)).thenReturn(Timestamp.from(NOW.minus(Duration.ofDays(3))));
            when(rs.getTimestamp(11)).thenReturn(Timestamp.from(ago(1)));
            answer("FROM saved_blogs", new Object[] {MEMBER_ID, "QA"}, rs);

            List<SavedBlog> questions = store.blogs(MEMBER_ID, "QA");

            assertThat(questions).containsExactly(new SavedBlog(301L, "QA", "Mua SIM ở đâu rẻ?",
                    new SavedAuthor(11L, "Tác giả", "/media/avatar/2026/11/a.jpg"), List.of(), 4, 2, true,
                    NOW.minus(Duration.ofDays(3)), ago(1)));
            assertThat(onlySql())
                    .contains("b.status = 'PUBLISHED'")
                    .contains(ActiveAuthors.of("b"))
                    .contains(QaRepository.SOLVED_SQL)
                    .contains("b.blog_type = ?")
                    .contains("ORDER BY sb.created_at DESC");
            assertThat(anyWrite()).isFalse();
        }

        @Test
        @DisplayName("UTC-076 Cẩm nang: chỉ cẩm nang đã đăng, chưa xóa; tóm tắt được rút gọn một dòng")
        void utc076_guides_publishedNotDeletedSummaryCut() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(61L);
            when(rs.getString(2)).thenReturn("Mở tài khoản ngân hàng");
            when(rs.getString(3)).thenReturn("  Mang theo\n  thẻ cư trú   và con dấu.  ");
            when(rs.getString(4)).thenReturn("Mới đến");
            when(rs.getString(5)).thenReturn("Ngân hàng");
            when(rs.getTimestamp(6)).thenReturn(Timestamp.from(ago(4)));
            answer("FROM saved_guides", new Object[] {MEMBER_ID}, rs);

            List<SavedGuide> guides = store.guides(MEMBER_ID);

            assertThat(guides).containsExactly(new SavedGuide(61L, "Mở tài khoản ngân hàng",
                    "Mang theo thẻ cư trú và con dấu.", "Mới đến", "Ngân hàng", ago(4)));
            assertThat(onlySql())
                    .contains("g.published = TRUE AND g.deleted_at IS NULL")
                    .contains("COALESCE(t.title, s.title)")
                    .contains("ORDER BY sg.created_at DESC");
        }

        @Test
        @DisplayName("UTC-077 Mẫu câu: chỉ mẫu câu chưa bị xóa, kèm danh mục")
        void utc077_phrases_notDeleted() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(501L);
            when(rs.getString(2)).thenReturn("PHRASE");
            when(rs.getString(3)).thenReturn("これをください");
            when(rs.getString(4)).thenReturn("kore o kudasai");
            when(rs.getString(5)).thenReturn("Cho tôi cái này");
            when(rs.getString(6)).thenReturn("Cửa hàng tiện lợi");
            when(rs.getString(7)).thenReturn("store");
            when(rs.getTimestamp(8)).thenReturn(Timestamp.from(ago(6)));
            answer("FROM saved_phrases", new Object[] {MEMBER_ID}, rs);

            List<SavedPhrase> phrases = store.phrases(MEMBER_ID);

            assertThat(phrases).containsExactly(new SavedPhrase(501L, "PHRASE", "これをください", "kore o kudasai",
                    "Cho tôi cái này", "Cửa hàng tiện lợi", "store", ago(6)));
            assertThat(onlySql()).contains("ph.deleted_at IS NULL").contains("ORDER BY sv.created_at DESC");
        }

        @Test
        @DisplayName("UTC-078 Thời điểm lưu trống: trả về không có thời điểm")
        void utc078_missingTimestamp_null() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(61L);
            when(rs.getString(2)).thenReturn("Đổi bằng lái");
            when(rs.getString(3)).thenReturn(null);
            when(rs.getTimestamp(6)).thenReturn(null);
            answer("FROM saved_guides", new Object[] {MEMBER_ID}, rs);

            SavedGuide guide = store.guides(MEMBER_ID).get(0);

            assertThat(guide.savedAt()).isNull();
            assertThat(guide.summary()).isNull();
        }

        @Test
        @DisplayName("UTC-079 Số \"Đã lưu (N)\": cộng địa điểm, bài viết và câu hỏi, cẩm nang, mẫu câu")
        void utc079_count_sumOfFourParts() {
            when(jdbc.queryForObject(contains("COUNT(DISTINCT shown.id)"), eq(Integer.class), eq(MEMBER_ID)))
                    .thenReturn(2);
            when(jdbc.queryForObject(contains("FROM saved_blogs"), eq(Integer.class), eq(MEMBER_ID))).thenReturn(3);
            when(jdbc.queryForObject(contains("FROM saved_guides"), eq(Integer.class), eq(MEMBER_ID))).thenReturn(1);
            when(jdbc.queryForObject(contains("FROM saved_phrases"), eq(Integer.class), eq(MEMBER_ID))).thenReturn(4);

            assertThat(store.count(MEMBER_ID)).isEqualTo(10);
        }

        @Test
        @DisplayName("UTC-080 Số \"Đã lưu (N)\" khi các phần không có kết quả: 0")
        void utc080_count_nullPartsAsZero() {
            doReturn(null).when(jdbc).queryForObject(anyString(), eq(Integer.class), eq(MEMBER_ID));

            assertThat(store.count(MEMBER_ID)).isZero();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. SavedRepository.excerpt – Tóm tắt cẩm nang tối đa 160 ký tự (UTC-081 → UTC-087)")
    class Excerpt {

        @Test
        @DisplayName("UTC-081 Cẩm nang không có tóm tắt: không có tóm tắt")
        void utc081_null_null() {
            assertThat(SavedRepository.excerpt(null)).isNull();
        }

        @ParameterizedTest(name = "[{index}] tóm tắt \"{0}\"")
        @ValueSource(strings = {"", "   ", "\n\t "})
        @DisplayName("UTC-082 Tóm tắt trống hoặc chỉ có khoảng trắng: không có tóm tắt")
        void utc082_blank_null(String summary) {
            assertThat(SavedRepository.excerpt(summary)).isNull();
        }

        @Test
        @DisplayName("UTC-083 Tóm tắt nhiều dòng: gộp thành một dòng, bỏ khoảng trắng thừa")
        void utc083_whitespaceCollapsed() {
            assertThat(SavedRepository.excerpt("  Bước 1:\n\n  đến quầy\tsố 3.  "))
                    .isEqualTo("Bước 1: đến quầy số 3.");
        }

        @Test
        @DisplayName("UTC-084 Tóm tắt đúng 160 ký tự: giữ nguyên")
        void utc084_exactly160_unchanged() {
            String summary = "b".repeat(160);

            assertThat(SavedRepository.excerpt(summary)).isEqualTo(summary);
        }

        @Test
        @DisplayName("UTC-085 Tóm tắt 161 ký tự: cắt còn 159 ký tự kèm \"…\"")
        void utc085_161_cutWithEllipsis() {
            String cut = SavedRepository.excerpt("c".repeat(161));

            assertThat(cut).isEqualTo("c".repeat(159) + "…");
            assertThat(cut.codePointCount(0, cut.length())).isEqualTo(160);
        }

        @Test
        @DisplayName("UTC-086 Tóm tắt có biểu tượng cảm xúc: đếm là một ký tự, không bị cắt đôi")
        void utc086_emojiNotSplit() {
            String summary = "🏦".repeat(170);

            String cut = SavedRepository.excerpt(summary);

            assertThat(cut).isEqualTo("🏦".repeat(159) + "…");
            assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 2))).isFalse();
        }

        @Test
        @DisplayName("UTC-087 Chỗ cắt rơi vào sau khoảng trắng: bỏ khoảng trắng trước \"…\"")
        void utc087_spaceBeforeCutStripped() {
            String summary = "a".repeat(158) + " " + "d".repeat(10);

            assertThat(SavedRepository.excerpt(summary)).isEqualTo("a".repeat(158) + "…");
        }
    }
}
