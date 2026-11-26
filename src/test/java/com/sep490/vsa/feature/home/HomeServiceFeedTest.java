package com.sep490.vsa.feature.home;

import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.blog.BlogDetailDtos.Media;
import com.sep490.vsa.feature.blog.BlogDtos;
import com.sep490.vsa.feature.blog.BlogDtos.Author;
import com.sep490.vsa.feature.blog.BlogDtos.PlaceRef;
import com.sep490.vsa.feature.blog.BlogDtos.TopicRef;
import com.sep490.vsa.feature.blog.BlogFeedRepository;
import com.sep490.vsa.feature.blog.FeaturedSnapshotService;
import com.sep490.vsa.feature.guide.GuideProgressService;
import com.sep490.vsa.feature.guide.GuideProgressService.State;
import com.sep490.vsa.feature.home.HomeDtos.FeedPost;
import com.sep490.vsa.feature.home.HomeDtos.FeedReason;
import com.sep490.vsa.feature.home.HomeDtos.ForYouFeed;
import com.sep490.vsa.feature.home.HomeDtos.GuideCard;
import com.sep490.vsa.feature.home.HomeDtos.MemberHome;
import com.sep490.vsa.feature.home.HomeDtos.ScopeCount;
import com.sep490.vsa.feature.home.InterestService.Interest;
import com.sep490.vsa.feature.phrase.PhraseOfDayService;
import com.sep490.vsa.feature.quest.QuestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of "Dành cho bạn", the personalized feed of UC 1.1 Browse Personalized Feed (SRS Report 3, II.1.1, and
 * the later decisions in CLAUDE.md "Personalized feed = the member Home"): the card on the member Home
 * ({@link HomeService#member}), the page "Xem tất cả" with its scopes, paging and search ({@link HomeService#forYouPage}),
 * the small text helpers the cards use and {@link HomeController}.
 * <p>
 * The database is not used. {@link JdbcTemplate} and the {@link NamedParameterJdbcTemplate} HomeService builds from it
 * (swapped for a mock after construction) answer from small in-memory tables: the users row, the rows the ranking
 * query returns (already in ranking order: the ranking itself is SQL, covered by HomeFeedFlowTest; only LIMIT and
 * OFFSET are applied here), the scope counts and the guides of a stage. The tests check what the service sends to the
 * database (filters, parameters, clauses) and what it builds from the rows. {@link InterestService},
 * {@link BlogFeedRepository}, {@link GuideProgressService}, {@link FeaturedSnapshotService}, {@link PhraseOfDayService}
 * and {@link QuestService} are mocks; the clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("HomeService – Bảng tin cá nhân hóa \"Dành cho bạn\" (UC 1.1)")
class HomeServiceFeedTest {

    private static final long MEMBER_ID = 10L;
    private static final long AUTHOR_ID = 50L;
    private static final long OTHER_AUTHOR_ID = 51L;
    private static final long AREA_ID = 101L;
    private static final String PREFECTURE = "13";
    private static final String AREA_NAME = "Arakawa-ku, Tokyo · 東京都荒川区";
    /** 12:00 in Japan on 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final String MEDIA_BASE = "/media/";

    private static final String REASON_AREA = "Gần khu vực của bạn";
    private static final String REASON_PREFECTURE = "Cùng tỉnh với bạn";
    private static final String REASON_STAGE = "Hợp giai đoạn của bạn";
    private static final String REASON_INTEREST = "Chủ đề bạn quan tâm";
    private static final String TOO_LONG = "Từ khóa tìm kiếm tối đa 100 ký tự.";

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private FeaturedSnapshotService featuredPosts;
    private GuideProgressService guideProgress;
    private InterestService interestService;
    private PhraseOfDayService phraseOfDay;
    private QuestService questService;
    private BlogFeedRepository blogCards;
    private HomeService service;

    /** The users row (with the area's name and prefecture) the service reads first. */
    private Row me;
    /** What the ranking query returns, already in ranking order; the fake applies LIMIT and OFFSET. */
    private final List<Row> ranked = new ArrayList<>();
    /** What the scope count query returns (COUNT as Long, SUM as BigDecimal, as MySQL answers). */
    private Map<String, Object> counts;
    /** Published guides of the stage the Home reads, in guide order. */
    private final List<Row> guides = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, this::jdbcAnswer);
        named = mock(NamedParameterJdbcTemplate.class, this::namedAnswer);
        featuredPosts = mock(FeaturedSnapshotService.class);
        guideProgress = mock(GuideProgressService.class);
        interestService = mock(InterestService.class);
        phraseOfDay = mock(PhraseOfDayService.class);
        questService = mock(QuestService.class);
        blogCards = mock(BlogFeedRepository.class);
        service = new HomeService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), featuredPosts, guideProgress,
                interestService, phraseOfDay, questService, blogCards, MEDIA_BASE);
        // HomeService builds its NamedParameterJdbcTemplate from the JdbcTemplate: the mock takes its place
        ReflectionTestUtils.setField(service, "named", named);
        clearInvocations(jdbc);
        memberWith(null, null, null);
        counts = counts(0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- the mocked database

    /** One row of a mocked query: column name → value, in column order (read by name or by 1-based index). */
    private static final class Row extends LinkedHashMap<String, Object> {
        Row set(String column, Object value) {
            put(column, value);
            return this;
        }
    }

    private Object jdbcAnswer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        String sql = args.length > 0 && args[0] instanceof String s ? s : "";
        String method = invocation.getMethod().getName();
        if (method.equals("queryForMap") && sql.contains("FROM users u")) {
            return me;
        }
        if (method.equals("query") && args.length >= 2 && args[1] instanceof RowMapper<?> mapper) {
            if (sql.contains("FROM guide_items")) {
                return mapRows(mapper, guides);
            }
            if (sql.contains("FROM guide_stages")) {
                return mapRows(mapper, List.of(new Row().set("title", "Giai đoạn " + args[2])));
            }
            return List.of();               // ranks, events, reviews: none
        }
        return RETURNS_DEFAULTS.answer(invocation);
    }

    private Object namedAnswer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        String method = invocation.getMethod().getName();
        if (method.equals("queryForMap")) {
            return counts;
        }
        if (method.equals("query") && args.length == 3 && args[1] instanceof MapSqlParameterSource params
                && args[2] instanceof RowMapper<?> mapper) {
            int limit = (Integer) params.getValue("limit");
            int offset = (Integer) params.getValue("offset");
            int from = Math.min(offset, ranked.size());
            return mapRows(mapper, ranked.subList(from, Math.min(from + limit, ranked.size())));
        }
        return RETURNS_DEFAULTS.answer(invocation);
    }

    private static List<Object> mapRows(RowMapper<?> mapper, List<? extends Map<String, Object>> rows)
            throws SQLException {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(mapper.mapRow(resultSet(rows.get(i)), i));
        }
        return out;
    }

    /** A read-only ResultSet over one row. */
    private static ResultSet resultSet(Map<String, Object> row) {
        List<Object> byIndex = new ArrayList<>(row.values());
        return (ResultSet) Proxy.newProxyInstance(HomeServiceFeedTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    Object value = args == null || args.length == 0 ? null
                            : args[0] instanceof Integer index ? byIndex.get(index - 1) : row.get(args[0]);
                    return switch (method.getName()) {
                        case "getString" -> value == null ? null : value.toString();
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                        case "getBoolean" -> value instanceof Boolean b ? b
                                : value != null && ((Number) value).intValue() != 0;
                        case "getTimestamp", "getObject" -> value;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    /** A statement sent to the NamedParameterJdbcTemplate. */
    private record Call(String sql, MapSqlParameterSource params) {

        /** The SQL on one line, so a clause is found whatever its indentation. */
        String flat() {
            return sql.replaceAll("\\s+", " ");
        }

        Object param(String name) {
            return params.getValue(name);
        }

        boolean has(String name) {
            return params.hasValue(name);
        }
    }

    private Call rankingCall() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).query(sql.capture(), params.capture(), any(RowMapper.class));
        return new Call(sql.getValue(), params.getValue());
    }

    private Call countsCall() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForMap(sql.capture(), params.capture());
        return new Call(sql.getValue(), params.getValue());
    }

    // ---------------------------------------------------------------- helpers

    private void memberWith(Long areaId, String prefecture, String stage) {
        me = new Row().set("display_name", "Minh Anh").set("stage", stage).set("area_id", areaId)
                .set("area_name", areaId == null ? null : AREA_NAME).set("prefecture_code", prefecture)
                .set("points", 120);
    }

    private void interests(Interest... interests) {
        when(interestService.interests(MEMBER_ID)).thenReturn(List.of(interests));
    }

    private static Interest chosen(long id, String name) {
        return new Interest(id, name, true);
    }

    private static Interest learnt(long id, String name) {
        return new Interest(id, name, false);
    }

    /** A ranked post by AUTHOR_ID that matched no reason, posted {@code id} hours ago. */
    private static Row post(long id) {
        return new Row().set("id", id).set("title", "Bài " + id).set("content_start", "Nội dung bài " + id + ".")
                .set("author_id", AUTHOR_ID).set("display_name", "Senpai Lan").set("avatar_url", null)
                .set("like_count", 0).set("comment_count", 0)
                .set("created_at", Timestamp.from(NOW.minus(Duration.ofHours(id))))
                .set("recent", true).set("topic_name", "Mua sắm")
                .set("near", 0).set("near_place", null).set("chosen_topic", null).set("stage_topic", null)
                .set("learnt_topic", null).set("engaged", false);
    }

    /** Posts 1…count in ranking order. */
    private void rankedPosts(int count) {
        for (long id = 1; id <= count; id++) {
            ranked.add(post(id));
        }
    }

    private static Map<String, Object> counts(long total, long near, long stage, long interest) {
        return Map.of("total", total, "near", BigDecimal.valueOf(near), "stage", BigDecimal.valueOf(stage),
                "interest", BigDecimal.valueOf(interest));
    }

    private static Row guide(long id) {
        return new Row().set("id", id).set("title", "Cẩm nang " + id).set("summary_start", "Tóm tắt cẩm nang " + id + ".")
                .set("topic_name", "Thủ tục giấy tờ").set("due_note", null).set("place_name", null).set("place_id", null);
    }

    private static State plainState(long id, boolean done) {
        return new State(id, false, 0, 0, done, null, done ? NOW : null);
    }

    private static State checklistState(long id, int ticked, int total) {
        return new State(id, true, ticked, total, ticked == total, ticked == total ? null : "Việc " + (ticked + 1), NOW);
    }

    private static List<Long> ids(List<FeedPost> posts) {
        return posts.stream().map(FeedPost::id).toList();
    }

    private static List<Long> range(long from, long to) {
        List<Long> out = new ArrayList<>();
        for (long id = from; id <= to; id++) {
            out.add(id);
        }
        return out;
    }

    private static ApiException apiError(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        ApiException e = apiError(call);
        assertThat(e.getStatus()).isEqualTo(status);
        assertThat(e.getMessage()).isEqualTo(message);
    }

    /** Reading the feed never writes: no UPDATE, INSERT or DELETE, no change of the interests. */
    private void assertNothingWritten() {
        assertThat(mockingDetails(jdbc).getInvocations()).extracting(i -> i.getMethod().getName())
                .doesNotContain("update", "batchUpdate", "execute");
        assertThat(mockingDetails(named).getInvocations()).extracting(i -> i.getMethod().getName())
                .doesNotContain("update", "batchUpdate", "execute");
        verify(interestService, never()).update(anyLong(), any());
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. member – UC 1.1 Thẻ \"Dành cho bạn\" trên Trang chủ (UTC-001 → UTC-038)")
    class HomeCard {

        @Test
        @DisplayName("UTC-001 Bài gần khu vực hiện trên thẻ với đủ thông tin")
        void utc001_homeCardShowsAreaPostWithFullCard() {
            memberWith(AREA_ID, PREFECTURE, "ARR");
            ranked.add(post(7L).set("title", "Thuê nhà ở Arakawa")
                    .set("content_start", "Kinh nghiệm thuê nhà ở Arakawa cho người mới sang Nhật. ".repeat(6))
                    .set("like_count", 3).set("comment_count", 2).set("near", 2).set("near_place", "Quán Phở Ueno"));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.forYou()).hasSize(1);
            FeedPost post = home.forYou().get(0);
            assertThat(post.id()).isEqualTo(7L);
            assertThat(post.title()).isEqualTo("Thuê nhà ở Arakawa");
            assertThat(post.excerpt()).startsWith("Kinh nghiệm thuê nhà ở Arakawa").endsWith("…");
            assertThat(post.excerpt().codePointCount(0, post.excerpt().length())).isLessThanOrEqualTo(141);
            assertThat(post.authorName()).isEqualTo("Senpai Lan");
            assertThat(post.topicName()).isEqualTo("Mua sắm");
            assertThat(post.likeCount()).isEqualTo(3);
            assertThat(post.commentCount()).isEqualTo(2);
            assertThat(post.createdAt()).isEqualTo(NOW.minus(Duration.ofHours(7)));
            assertThat(post.reason()).isEqualTo(REASON_AREA);
            assertThat(post.reasons()).containsExactly(new FeedReason("AREA", REASON_AREA, "Quán Phở Ueno"));
            assertThat(post.engaged()).isFalse();
        }

        @Test
        @DisplayName("UTC-002 Thẻ đọc 5 bài đầu của toàn bảng tin, không lọc, không tìm")
        void utc002_homeCardAsksFirstFivePostsOfWholeFeed() {
            memberWith(AREA_ID, PREFECTURE, "ARR");

            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.param("limit")).isEqualTo(5);
            assertThat(call.param("offset")).isEqualTo(0);
            assertThat(call.flat()).contains(") f ORDER BY engaged").doesNotContain("WHERE f.", "LIKE :kw");
            assertThat(call.has("kw")).isFalse();
        }

        @Test
        @DisplayName("UTC-003 Có 6 bài phù hợp: thẻ chỉ hiện 5 bài đầu")
        void utc003_homeCardShowsAtMostFiveOfSixPosts() {
            rankedPosts(6);

            MemberHome home = service.member(MEMBER_ID);

            assertThat(ids(home.forYou())).containsExactly(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        @DisplayName("UTC-004 Chỉ bài viết đã đăng của thành viên khác đang hoạt động")
        void utc004_onlyPublishedPostsOfOtherActiveMembers() {
            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.flat()).contains("b.status = 'PUBLISHED'", "b.blog_type = 'POST'",
                    "b.author_id <> :userId", ActiveAuthors.of("b"));
            assertThat(call.param("userId")).isEqualTo(MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-005 Thứ tự xếp: bài đã tương tác cuối, bài mới trước, rồi khu vực → chủ đề chọn → giai đoạn → chủ đề học được → điểm → mới nhất")
        void utc005_rankingOrder() {
            service.member(MEMBER_ID);

            assertThat(rankingCall().flat()).contains("ORDER BY engaged, recent DESC, near DESC, "
                    + "chosen_topic IS NOT NULL DESC, stage_topic IS NOT NULL DESC, learnt_topic IS NOT NULL DESC, "
                    + "like_count + 2 * comment_count DESC, created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        }

        @Test
        @DisplayName("UTC-006 \"Gần đây\" = 30 ngày tính đến lúc xem")
        void utc006_recentMeansLast30Days() {
            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.param("since")).isEqualTo(Timestamp.from(Instant.parse("2026-10-21T03:00:00Z")));
            assertThat(call.flat()).contains("b.created_at >= :since AS recent");
        }

        @Test
        @DisplayName("UTC-007 Giữ đúng thứ tự cơ sở dữ liệu trả về")
        void utc007_keepsDatabaseOrder() {
            ranked.add(post(7L));
            ranked.add(post(3L));
            ranked.add(post(9L));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(ids(home.forYou())).containsExactly(7L, 3L, 9L);
        }

        @Test
        @DisplayName("UTC-008 Có khu vực: xếp theo nơi gắn với bài ở cùng khu vực (2) hoặc cùng tỉnh (1)")
        void utc008_areaAndPrefectureBound() {
            memberWith(AREA_ID, PREFECTURE, null);

            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.param("areaId")).isEqualTo(AREA_ID);
            assertThat(call.param("prefecture")).isEqualTo(PREFECTURE);
            assertThat(call.flat()).contains("WHEN shown.area_id = :areaId THEN 2",
                    "WHEN sa.prefecture_code = :prefecture THEN 1",
                    "CASE WHEN p.status = 'MERGED' THEN p.merged_into_id ELSE p.id END", "shown.status = 'ACTIVE'");
        }

        @Test
        @DisplayName("UTC-009 Chưa chọn khu vực: bỏ qua bước xếp theo khu vực")
        void utc009_noAreaNoNearRanking() {
            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.has("areaId")).isFalse();
            assertThat(call.has("prefecture")).isFalse();
            assertThat(call.flat()).contains("0 AS near, NULL AS near_place");
        }

        @Test
        @DisplayName("UTC-010 Khu vực không có mã tỉnh: tỉnh được truyền là null")
        void utc010_areaWithoutPrefecture() {
            memberWith(AREA_ID, null, null);

            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.param("areaId")).isEqualTo(AREA_ID);
            assertThat(call.has("prefecture")).isTrue();
            assertThat(call.param("prefecture")).isNull();
        }

        @Test
        @DisplayName("UTC-011 Bài gắn nơi cùng tỉnh: huy hiệu \"Cùng tỉnh với bạn\" kèm tên nơi")
        void utc011_prefecturePostReason() {
            memberWith(AREA_ID, PREFECTURE, null);
            ranked.add(post(7L).set("near", 1).set("near_place", "Chợ Ameyoko"));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.reason()).isEqualTo(REASON_PREFECTURE);
            assertThat(post.reasons()).containsExactly(new FeedReason("PREFECTURE", REASON_PREFECTURE, "Chợ Ameyoko"));
        }

        @ParameterizedTest(name = "[{index}] giai đoạn {0}")
        @ValueSource(strings = {"PRE", "ARR", "LEAVE", "HOME"})
        @DisplayName("UTC-012 Mỗi giai đoạn dùng đúng nhóm chủ đề phù hợp")
        void utc012_eachStageUsesItsTopics(String stage) {
            memberWith(null, null, stage);

            service.member(MEMBER_ID);

            List<String> expected = switch (stage) {
                case "PRE" -> List.of("Thủ tục giấy tờ", "Visa và cư trú", "Nhà ở", "Học tiếng Nhật");
                case "ARR" -> List.of("Kinh nghiệm", "Nhà ở", "Việc làm thêm", "Đi lại", "Ăn uống", "Y tế",
                        "Ngân hàng và tiền", "Cảnh báo lừa đảo");
                case "LEAVE" -> List.of("Thủ tục giấy tờ", "Visa và cư trú", "Ngân hàng và tiền", "Nhà ở");
                default -> List.of("Sau khi về nước", "Kinh nghiệm");
            };
            Call call = rankingCall();
            assertThat(call.param("stageTopics")).isEqualTo(expected);
            assertThat(call.flat()).contains("t.name IN (:stageTopics) ORDER BY t.is_other")
                    .doesNotContain("t.id IN (:chosenIds)");
        }

        @Test
        @DisplayName("UTC-013 Chưa chọn giai đoạn: bỏ qua bước xếp theo giai đoạn")
        void utc013_noStageNoStageRanking() {
            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.has("stageTopics")).isFalse();
            assertThat(call.flat()).contains("NULL AS stage_topic");
        }

        @Test
        @DisplayName("UTC-014 Mã giai đoạn lạ trong tài khoản: xem như chưa chọn")
        void utc014_unknownStageTreatedAsNone() {
            memberWith(null, null, "SOON");

            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.has("stageTopics")).isFalse();
            assertThat(call.flat()).contains("NULL AS stage_topic");
        }

        @Test
        @DisplayName("UTC-015 Bài có chủ đề hợp giai đoạn: huy hiệu \"Hợp giai đoạn của bạn\" kèm chủ đề")
        void utc015_stagePostReason() {
            memberWith(null, null, "ARR");
            ranked.add(post(7L).set("stage_topic", "Nhà ở"));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.reason()).isEqualTo(REASON_STAGE);
            assertThat(post.reasons()).containsExactly(new FeedReason("STAGE", REASON_STAGE, "Nhà ở"));
        }

        @Test
        @DisplayName("UTC-016 Chủ đề đã chọn và chủ đề học được truyền theo đúng thứ tự")
        void utc016_interestIdsBoundInTheirOrder() {
            interests(chosen(3L, "Nhà ở"), chosen(8L, "Học tiếng Nhật"), learnt(12L, "Ăn uống"));

            service.member(MEMBER_ID);

            Call call = rankingCall();
            assertThat(call.param("chosenIds")).isEqualTo(List.of(3L, 8L));
            assertThat(call.param("learntIds")).isEqualTo(List.of(12L));
            assertThat(call.flat()).contains("t.id IN (:chosenIds) ORDER BY FIELD(t.id, :chosenIds) LIMIT 1) AS chosen_topic",
                    "t.id IN (:learntIds) ORDER BY FIELD(t.id, :learntIds) LIMIT 1) AS learnt_topic");
        }

        @Test
        @DisplayName("UTC-017 Lý do giai đoạn ưu tiên chủ đề khác với chủ đề đã chọn")
        void utc017_stageTopicAvoidsChosenTopic() {
            memberWith(null, null, "ARR");
            interests(chosen(3L, "Nhà ở"));

            service.member(MEMBER_ID);

            assertThat(rankingCall().flat()).contains(
                    "t.name IN (:stageTopics) ORDER BY t.id IN (:chosenIds), t.is_other, t.sort_order, t.name LIMIT 1)");
        }

        @Test
        @DisplayName("UTC-018 Bài hợp chủ đề đã chọn và giai đoạn: \"Chủ đề bạn quan tâm\" trước, bỏ chủ đề học được")
        void utc018_chosenInterestBeforeStageLearntDropped() {
            memberWith(null, null, "ARR");
            ranked.add(post(7L).set("chosen_topic", "Học tiếng Nhật").set("stage_topic", "Nhà ở")
                    .set("learnt_topic", "Ăn uống"));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.reason()).isEqualTo(REASON_INTEREST);
            assertThat(post.reasons()).extracting(FeedReason::code, FeedReason::detail)
                    .containsExactly(tuple("INTEREST", "Học tiếng Nhật"), tuple("STAGE", "Nhà ở"));
        }

        @Test
        @DisplayName("UTC-019 Chủ đề học được xếp sau lý do giai đoạn")
        void utc019_learntInterestAfterStage() {
            memberWith(null, null, "ARR");
            ranked.add(post(7L).set("stage_topic", "Nhà ở").set("learnt_topic", "Ăn uống"));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.reason()).isEqualTo(REASON_STAGE);
            assertThat(post.reasons()).containsExactly(new FeedReason("STAGE", REASON_STAGE, "Nhà ở"),
                    new FeedReason("INTEREST", REASON_INTEREST, "Ăn uống"));
        }

        @Test
        @DisplayName("UTC-020 Chỉ hợp chủ đề học được: huy hiệu \"Chủ đề bạn quan tâm\"")
        void utc020_learntInterestOnly() {
            ranked.add(post(7L).set("learnt_topic", "Ăn uống"));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.reason()).isEqualTo(REASON_INTEREST);
            assertThat(post.reasons()).containsExactly(new FeedReason("INTEREST", REASON_INTEREST, "Ăn uống"));
        }

        @Test
        @DisplayName("UTC-021 Vừa gần khu vực vừa hợp giai đoạn: hai lý do, khu vực đứng đầu")
        void utc021_areaAndStageBothReasons() {
            memberWith(AREA_ID, PREFECTURE, "LEAVE");
            ranked.add(post(7L).set("near", 2).set("near_place", "Quán Phở Ueno").set("stage_topic", "Visa và cư trú"));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.reason()).isEqualTo(REASON_AREA);
            assertThat(post.reasons()).containsExactly(new FeedReason("AREA", REASON_AREA, "Quán Phở Ueno"),
                    new FeedReason("STAGE", REASON_STAGE, "Visa và cư trú"));
        }

        @Test
        @DisplayName("UTC-022 Bài không hợp lý do nào: không có huy hiệu")
        void utc022_noMatchNoBadge() {
            ranked.add(post(7L).set("like_count", 40).set("comment_count", 9));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.reason()).isNull();
            assertThat(post.reasons()).isEmpty();
        }

        @Test
        @DisplayName("UTC-023 Bài đã thích / lưu / bình luận được đánh dấu engaged")
        void utc023_engagedFlag() {
            ranked.add(post(7L));
            ranked.add(post(8L).set("engaged", true));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.forYou()).extracting(FeedPost::id, FeedPost::engaged)
                    .containsExactly(tuple(7L, false), tuple(8L, true));
        }

        @Test
        @DisplayName("UTC-024 Thẻ bài như Blog: tác giả, tag, chủ đề, địa điểm, ảnh và lượt thích của người xem")
        void utc024_cardPartsFromBlogHelpers() {
            ranked.add(post(7L).set("avatar_url", "/media/avatar/lan.jpg"));
            List<BlogDtos.Tag> tags = List.of(new BlogDtos.Tag("Du học sinh", "#2a9d8f"));
            List<TopicRef> topics = List.of(new TopicRef(9L, "Mua sắm"), new TopicRef(3L, "Nhà ở"));
            List<PlaceRef> places = List.of(new PlaceRef(201L, "Quán Phở Ueno", "Quán ăn", "Ueno 1-2", 35.71, 139.77,
                    "N772389775"));
            List<Media> media = List.of(new Media(900L, "IMAGE", "/media/blog/2026/11/a.jpg", "image/jpeg", 1600, 900));
            when(blogCards.tagsOf(any())).thenReturn(Map.of(AUTHOR_ID, tags));
            when(blogCards.topicsOf(any())).thenReturn(Map.of(7L, topics));
            when(blogCards.placesOf(any())).thenReturn(Map.of(7L, places));
            when(blogCards.mediaOf(any(), any(), any())).thenReturn(Map.of(7L, media));
            when(blogCards.likedBy(any(), any())).thenReturn(Set.of(7L));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.author()).isEqualTo(new Author(AUTHOR_ID, "Senpai Lan", "/media/avatar/lan.jpg", tags));
            assertThat(post.topics()).isEqualTo(topics);
            assertThat(post.places()).isEqualTo(places);
            assertThat(post.media()).isEqualTo(media);
            assertThat(post.liked()).isTrue();
        }

        @Test
        @DisplayName("UTC-025 Bài không có tag, chủ đề, địa điểm, ảnh: danh sách rỗng, chưa thích")
        void utc025_cardPartsDefaultEmpty() {
            ranked.add(post(7L));

            FeedPost post = service.member(MEMBER_ID).forYou().get(0);

            assertThat(post.author()).isEqualTo(new Author(AUTHOR_ID, "Senpai Lan", null, List.of()));
            assertThat(post.topics()).isEmpty();
            assertThat(post.places()).isEmpty();
            assertThat(post.media()).isEmpty();
            assertThat(post.liked()).isFalse();
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("UTC-026 Phần thẻ được đọc một lần cho các bài hiện ra, tag mỗi tác giả một lần")
        void utc026_cardPartsReadOnceForShownPosts() {
            ranked.add(post(7L));
            ranked.add(post(3L));
            ranked.add(post(9L).set("author_id", OTHER_AUTHOR_ID).set("display_name", "Senpai Huy"));

            service.member(MEMBER_ID);

            List<Long> shown = List.of(7L, 3L, 9L);
            verify(blogCards).topicsOf(shown);
            verify(blogCards).placesOf(shown);
            verify(blogCards).mediaOf(shown, MEMBER_ID, MEDIA_BASE);
            verify(blogCards).likedBy(MEMBER_ID, shown);
            ArgumentCaptor<Collection<Long>> authors = ArgumentCaptor.forClass(Collection.class);
            verify(blogCards).tagsOf(authors.capture());
            assertThat(authors.getValue()).containsExactly(AUTHOR_ID, OTHER_AUTHOR_ID);
        }

        @Test
        @DisplayName("UTC-027 Không có bài phù hợp: thẻ rỗng, không đọc thêm phần thẻ")
        void utc027_noPostNoCardReads() {
            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.forYou()).isEmpty();
            verifyNoInteractions(blogCards);
        }

        @Test
        @DisplayName("UTC-028 \"Dựa trên\": chủ đề đã chọn trước, chủ đề học được sau")
        void utc028_interestNamesChosenFirst() {
            interests(chosen(3L, "Nhà ở"), chosen(8L, "Học tiếng Nhật"), learnt(12L, "Ăn uống"));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.interests()).containsExactly("Nhà ở", "Học tiếng Nhật", "Ăn uống");
            assertThat(home.chosenInterests()).containsExactly("Nhà ở", "Học tiếng Nhật");
        }

        @Test
        @DisplayName("UTC-029 Chưa có chủ đề quan tâm: bỏ qua bước xếp theo chủ đề")
        void utc029_noInterestsNoInterestRanking() {
            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.interests()).isEmpty();
            assertThat(home.chosenInterests()).isEmpty();
            Call call = rankingCall();
            assertThat(call.has("chosenIds")).isFalse();
            assertThat(call.has("learntIds")).isFalse();
            assertThat(call.flat()).contains("NULL AS chosen_topic", "NULL AS learnt_topic");
        }

        @Test
        @DisplayName("UTC-030 Trang chủ cho biết khu vực và giai đoạn bảng tin dựa vào")
        void utc030_basedOnAreaAndStage() {
            memberWith(AREA_ID, PREFECTURE, "ARR");

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.displayName()).isEqualTo("Minh Anh");
            assertThat(home.areaId()).isEqualTo(AREA_ID);
            assertThat(home.areaName()).isEqualTo(AREA_NAME);
            assertThat(home.stage()).isEqualTo("ARR");
        }

        @Test
        @DisplayName("UTC-031 Chưa chọn khu vực và giai đoạn: các giá trị là null")
        void utc031_noAreaNoStage() {
            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.areaId()).isNull();
            assertThat(home.areaName()).isNull();
            assertThat(home.stage()).isNull();
        }

        @Test
        @DisplayName("UTC-032 Cẩm nang theo giai đoạn: tối đa 3, việc đang làm dở trước, bỏ cẩm nang của \"Việc tiếp theo\"")
        void utc032_stageGuidesStartedFirstWithoutNextAtMostThree() {
            memberWith(null, null, "PRE");
            for (long id = 1; id <= 6; id++) {
                guides.add(guide(id));
            }
            when(guideProgress.states(eq(MEMBER_ID), any())).thenReturn(Map.of(
                    1L, plainState(1L, true), 2L, checklistState(2L, 1, 4), 3L, checklistState(3L, 2, 5)));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.guide().next().guideId()).isEqualTo(2L);
            assertThat(home.stageGuides()).extracting(GuideCard::id).containsExactly(3L, 4L, 5L);
            assertThat(home.stageGuides()).extracting(GuideCard::inProgress).containsExactly(true, false, false);
        }

        @Test
        @DisplayName("UTC-033 Chưa chọn giai đoạn: không có cẩm nang trong \"Dành cho bạn\", \"Việc tiếp theo\" dùng giai đoạn ARR")
        void utc033_noStageNoStageGuides() {
            guides.add(guide(1L));
            guides.add(guide(2L));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.stageGuides()).isEmpty();
            assertThat(home.guide().stageCode()).isEqualTo("ARR");
            verify(jdbc).query(contains("FROM guide_items"), any(RowMapper.class), eq("ARR"));
        }

        @Test
        @DisplayName("UTC-034 Đã xong mọi cẩm nang của giai đoạn: không còn cẩm nang để gợi ý")
        void utc034_allGuidesDoneNoStageGuides() {
            memberWith(null, null, "ARR");
            guides.add(guide(1L));
            guides.add(guide(2L));
            when(guideProgress.states(eq(MEMBER_ID), any())).thenReturn(Map.of(
                    1L, plainState(1L, true), 2L, checklistState(2L, 4, 4)));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.stageGuides()).isEmpty();
            assertThat(home.guide().next()).isNull();
            assertThat(home.guide().done()).isEqualTo(2);
            assertThat(home.guide().total()).isEqualTo(2);
        }

        @Test
        @DisplayName("UTC-035 Thẻ cẩm nang: tóm tắt cắt 120 ký tự, tiến độ danh sách việc")
        void utc035_stageGuideCardFields() {
            memberWith(null, null, "ARR");
            guides.add(guide(1L));
            guides.add(guide(2L).set("title", "Làm thẻ cư trú").set("summary_start",
                    "Đăng ký địa chỉ ở phường, làm thẻ My Number và mở tài khoản ngân hàng trong 14 ngày đầu. ".repeat(3)));
            guides.add(guide(3L).set("title", "Mua vé tàu").set("topic_name", "Đi lại").set("summary_start", null));
            when(guideProgress.states(eq(MEMBER_ID), any())).thenReturn(Map.of(
                    1L, checklistState(1L, 1, 4), 2L, new State(2L, true, 2, 5, false, "Mua SIM", NOW)));

            List<GuideCard> cards = service.member(MEMBER_ID).stageGuides();

            assertThat(cards).extracting(GuideCard::id).containsExactly(2L, 3L);
            GuideCard checklist = cards.get(0);
            assertThat(checklist.title()).isEqualTo("Làm thẻ cư trú");
            assertThat(checklist.topicName()).isEqualTo("Thủ tục giấy tờ");
            assertThat(checklist.excerpt()).startsWith("Đăng ký địa chỉ ở phường").endsWith("…");
            assertThat(checklist.excerpt().codePointCount(0, checklist.excerpt().length())).isLessThanOrEqualTo(121);
            assertThat(checklist.checklist()).isTrue();
            assertThat(checklist.tasksDone()).isEqualTo(2);
            assertThat(checklist.tasksTotal()).isEqualTo(5);
            assertThat(checklist.percent()).isEqualTo(40);
            assertThat(checklist.nextTask()).isEqualTo("Mua SIM");
            assertThat(checklist.inProgress()).isTrue();
            GuideCard plain = cards.get(1);
            assertThat(plain.topicName()).isEqualTo("Đi lại");
            assertThat(plain.excerpt()).isNull();
            assertThat(plain.checklist()).isFalse();
            assertThat(plain.percent()).isZero();
            assertThat(plain.nextTask()).isNull();
            assertThat(plain.inProgress()).isFalse();
        }

        @Test
        @DisplayName("UTC-036 Cẩm nang không có trong kết quả tiến độ: xem như chưa bắt đầu")
        void utc036_guideMissingFromProgressNotStarted() {
            memberWith(null, null, "HOME");
            guides.add(guide(1L));
            guides.add(guide(2L));

            MemberHome home = service.member(MEMBER_ID);

            assertThat(home.guide().next().guideId()).isEqualTo(1L);
            assertThat(home.stageGuides()).extracting(GuideCard::id, GuideCard::checklist, GuideCard::percent,
                    GuideCard::inProgress).containsExactly(tuple(2L, false, 0, false));
        }

        @Test
        @DisplayName("UTC-037 Cẩm nang của giai đoạn và tiến độ chỉ đọc một lần cho cả hai thẻ")
        void utc037_guidesReadOnce() {
            memberWith(null, null, "PRE");
            guides.add(guide(1L));
            guides.add(guide(2L));
            guides.add(guide(3L));

            service.member(MEMBER_ID);

            verify(jdbc, times(1)).query(contains("FROM guide_items"), any(RowMapper.class), eq("PRE"));
            verify(guideProgress, times(1)).states(MEMBER_ID, List.of(1L, 2L, 3L));
        }

        @Test
        @DisplayName("UTC-038 Xem Trang chủ không thay đổi dữ liệu")
        void utc038_homeChangesNoData() {
            memberWith(AREA_ID, PREFECTURE, "ARR");
            interests(chosen(3L, "Nhà ở"));
            rankedPosts(3);

            service.member(MEMBER_ID);

            assertNothingWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. forYouPage – UC 1.1 Trang \"Dành cho bạn\": phạm vi và phân trang (UTC-039 → UTC-061)")
    class ForYouPage {

        @Test
        @DisplayName("UTC-039 Mở trang: phạm vi \"Tất cả\", số bài từng phạm vi và thông tin bảng tin dựa vào")
        void utc039_pageShowsScopesCountsAndBasis() {
            memberWith(AREA_ID, PREFECTURE, "ARR");
            interests(chosen(3L, "Nhà ở"), learnt(12L, "Ăn uống"));
            counts = counts(23, 5, 7, 4);
            rankedPosts(3);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, null);

            assertThat(feed.scope()).isEqualTo("ALL");
            assertThat(feed.scopes()).containsExactly(new ScopeCount("ALL", 23), new ScopeCount("NEAR", 5),
                    new ScopeCount("STAGE", 7), new ScopeCount("INTEREST", 4));
            assertThat(feed.page()).isZero();
            assertThat(feed.hasMore()).isFalse();
            assertThat(ids(feed.items())).containsExactly(1L, 2L, 3L);
            assertThat(feed.q()).isNull();
            assertThat(feed.areaName()).isEqualTo(AREA_NAME);
            assertThat(feed.stage()).isEqualTo("ARR");
            assertThat(feed.interests()).containsExactly("Nhà ở", "Ăn uống");
            assertThat(feed.chosenInterests()).containsExactly("Nhà ở");
        }

        @Test
        @DisplayName("UTC-040 Thành viên mới (chưa khu vực, giai đoạn, chủ đề): chỉ có phạm vi \"Tất cả\"")
        void utc040_newMemberOnlyAll() {
            counts = counts(4, 0, 0, 0);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, null);

            assertThat(feed.scopes()).containsExactly(new ScopeCount("ALL", 4));
            assertThat(feed.areaName()).isNull();
            assertThat(feed.stage()).isNull();
            assertThat(feed.interests()).isEmpty();
            assertThat(feed.chosenInterests()).isEmpty();
        }

        @Test
        @DisplayName("UTC-041 Chưa chọn khu vực: không có phạm vi \"Gần bạn\"")
        void utc041_noAreaNoNearScope() {
            memberWith(null, null, "PRE");
            interests(learnt(12L, "Ăn uống"));
            counts = counts(9, 0, 3, 2);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, null);

            assertThat(feed.scopes()).extracting(ScopeCount::code).containsExactly("ALL", "STAGE", "INTEREST");
        }

        @Test
        @DisplayName("UTC-042 Mã giai đoạn lạ: không có phạm vi \"Hợp giai đoạn\"")
        void utc042_unknownStageNoStageScope() {
            memberWith(AREA_ID, PREFECTURE, "SOON");
            counts = counts(9, 2, 0, 0);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, null);

            assertThat(feed.scopes()).extracting(ScopeCount::code).containsExactly("ALL", "NEAR");
        }

        @Test
        @DisplayName("UTC-043 Số bài từng phạm vi được đếm trên chính bảng tin đó")
        void utc043_countsQueryCountsTheSameFeed() {
            memberWith(AREA_ID, PREFECTURE, "ARR");
            interests(chosen(3L, "Nhà ở"));

            service.forYouPage(MEMBER_ID, null, 0, null);

            Call count = countsCall();
            Call rank = rankingCall();
            String select = rank.sql().substring("SELECT * FROM (".length(), rank.sql().lastIndexOf(") f"));
            assertThat(count.sql()).contains("FROM (" + select + ") f");
            assertThat(count.flat()).contains("COUNT(*) AS total", "COALESCE(SUM(f.near > 0), 0) AS near",
                    "COALESCE(SUM(f.stage_topic IS NOT NULL), 0) AS stage",
                    "COALESCE(SUM(f.chosen_topic IS NOT NULL OR f.learnt_topic IS NOT NULL), 0) AS interest");
            Map<String, Object> rankParams = new LinkedHashMap<>(rank.params().getValues());
            rankParams.remove("limit");
            rankParams.remove("offset");
            assertThat(count.params().getValues()).isEqualTo(rankParams);
        }

        @Test
        @DisplayName("UTC-044 Phạm vi \"Gần bạn\": chỉ bài gắn nơi cùng khu vực hoặc cùng tỉnh")
        void utc044_scopeNearKeepsNearPosts() {
            memberWith(AREA_ID, PREFECTURE, "ARR");

            ForYouFeed feed = service.forYouPage(MEMBER_ID, "NEAR", 0, null);

            assertThat(feed.scope()).isEqualTo("NEAR");
            assertThat(rankingCall().flat()).contains(") f WHERE f.near > 0 ORDER BY engaged");
        }

        @Test
        @DisplayName("UTC-045 Phạm vi \"Hợp giai đoạn\": chỉ bài có chủ đề hợp giai đoạn")
        void utc045_scopeStage() {
            memberWith(AREA_ID, PREFECTURE, "ARR");

            ForYouFeed feed = service.forYouPage(MEMBER_ID, "STAGE", 0, null);

            assertThat(feed.scope()).isEqualTo("STAGE");
            assertThat(rankingCall().flat()).contains(") f WHERE f.stage_topic IS NOT NULL ORDER BY engaged");
        }

        @Test
        @DisplayName("UTC-046 Phạm vi \"Chủ đề quan tâm\": chỉ bài có chủ đề đã chọn hoặc học được")
        void utc046_scopeInterest() {
            interests(learnt(12L, "Ăn uống"));

            ForYouFeed feed = service.forYouPage(MEMBER_ID, "INTEREST", 0, null);

            assertThat(feed.scope()).isEqualTo("INTEREST");
            assertThat(rankingCall().flat()).contains(
                    ") f WHERE f.chosen_topic IS NOT NULL OR f.learnt_topic IS NOT NULL ORDER BY engaged");
        }

        @Test
        @DisplayName("UTC-047 Phạm vi \"Tất cả\": không lọc theo lý do")
        void utc047_scopeAllExplicit() {
            memberWith(AREA_ID, PREFECTURE, "ARR");

            ForYouFeed feed = service.forYouPage(MEMBER_ID, "ALL", 0, null);

            assertThat(feed.scope()).isEqualTo("ALL");
            assertThat(rankingCall().flat()).contains(") f ORDER BY engaged").doesNotContain("WHERE f.");
        }

        @ParameterizedTest(name = "[{index}] scope = {0}")
        @ValueSource(strings = {"NEAR", "STAGE", "INTEREST"})
        @DisplayName("UTC-048 Phạm vi thành viên chưa dùng được: trở về \"Tất cả\"")
        void utc048_unavailableScopeFallsBackToAll(String scope) {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, scope, 0, null);

            assertThat(feed.scope()).isEqualTo("ALL");
            assertThat(feed.scopes()).extracting(ScopeCount::code).containsExactly("ALL");
            assertThat(rankingCall().flat()).doesNotContain("WHERE f.");
        }

        @ParameterizedTest(name = "[{index}] scope = \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"near", "bogus"})
        @DisplayName("UTC-049 Phạm vi không có hoặc không hợp lệ: \"Tất cả\"")
        void utc049_unknownScopeFallsBackToAll(String scope) {
            memberWith(AREA_ID, PREFECTURE, "ARR");
            interests(chosen(3L, "Nhà ở"));

            ForYouFeed feed = service.forYouPage(MEMBER_ID, scope, 0, null);

            assertThat(feed.scope()).isEqualTo("ALL");
            assertThat(rankingCall().flat()).doesNotContain("WHERE f.");
        }

        @Test
        @DisplayName("UTC-050 Trang đầu đọc 11 bài (10 bài + 1 để biết còn bài)")
        void utc050_firstPageAsksElevenRows() {
            service.forYouPage(MEMBER_ID, null, 0, null);

            Call call = rankingCall();
            assertThat(call.param("limit")).isEqualTo(11);
            assertThat(call.param("offset")).isEqualTo(0);
        }

        @Test
        @DisplayName("UTC-051 Trang 2 bắt đầu từ bài thứ 11, còn bài thì \"Xem thêm\"")
        void utc051_secondPageOffsetTen() {
            rankedPosts(23);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 1, null);

            assertThat(rankingCall().param("offset")).isEqualTo(10);
            assertThat(feed.page()).isEqualTo(1);
            assertThat(ids(feed.items())).isEqualTo(range(11, 20));
            assertThat(feed.hasMore()).isTrue();
        }

        @Test
        @DisplayName("UTC-052 Trang cuối thiếu bài: hiện phần còn lại, hết \"Xem thêm\"")
        void utc052_lastPartialPage() {
            rankedPosts(23);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 2, null);

            assertThat(ids(feed.items())).containsExactly(21L, 22L, 23L);
            assertThat(feed.hasMore()).isFalse();
        }

        @Test
        @DisplayName("UTC-053 Còn đúng 10 bài: đủ trang, không còn bài sau")
        void utc053_exactlyTenLeftNoMore() {
            rankedPosts(20);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 1, null);

            assertThat(ids(feed.items())).isEqualTo(range(11, 20));
            assertThat(feed.hasMore()).isFalse();
        }

        @Test
        @DisplayName("UTC-054 Có 11 bài: trang đầu hiện 10, còn bài sau")
        void utc054_elevenRowsMeansMore() {
            rankedPosts(11);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, null);

            assertThat(ids(feed.items())).isEqualTo(range(1, 10));
            assertThat(feed.hasMore()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] page = {0}")
        @ValueSource(ints = {-1, -5})
        @DisplayName("UTC-055 Số trang âm: về trang đầu")
        void utc055_negativePageIsFirst(int page) {
            rankedPosts(3);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, page, null);

            assertThat(feed.page()).isZero();
            assertThat(rankingCall().param("offset")).isEqualTo(0);
            assertThat(ids(feed.items())).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("UTC-056 Trang 199 là trang sâu nhất được đọc")
        void utc056_page199IsLast() {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 199, null);

            assertThat(feed.page()).isEqualTo(199);
            assertThat(rankingCall().param("offset")).isEqualTo(1990);
        }

        @ParameterizedTest(name = "[{index}] page = {0}")
        @ValueSource(ints = {200, 100_000})
        @DisplayName("UTC-057 Số trang quá 199: giữ ở trang 199")
        void utc057_pageBeyond199IsClamped(int page) {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, page, null);

            assertThat(feed.page()).isEqualTo(199);
            assertThat(rankingCall().param("offset")).isEqualTo(1990);
        }

        @Test
        @DisplayName("UTC-058 Trang 199 không bao giờ có \"Xem thêm\" dù còn bài")
        void utc058_page199NeverHasMore() {
            rankedPosts(2001);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 199, null);

            assertThat(ids(feed.items())).isEqualTo(range(1991, 2000));
            assertThat(feed.hasMore()).isFalse();
        }

        @Test
        @DisplayName("UTC-059 Không có bài phù hợp: danh sách rỗng, số bài 0")
        void utc059_emptyFeed() {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, null);

            assertThat(feed.items()).isEmpty();
            assertThat(feed.hasMore()).isFalse();
            assertThat(feed.scopes()).containsExactly(new ScopeCount("ALL", 0));
            verifyNoInteractions(blogCards);
        }

        @Test
        @DisplayName("UTC-060 Bài trên trang có lý do và phần thẻ như trên Trang chủ")
        void utc060_pageItemsLikeHomeCards() {
            memberWith(AREA_ID, PREFECTURE, null);
            ranked.add(post(7L).set("near", 1).set("near_place", "Chợ Ameyoko"));
            when(blogCards.likedBy(any(), any())).thenReturn(Set.of(7L));

            FeedPost post = service.forYouPage(MEMBER_ID, "NEAR", 0, null).items().get(0);

            assertThat(post.reasons()).containsExactly(new FeedReason("PREFECTURE", REASON_PREFECTURE, "Chợ Ameyoko"));
            assertThat(post.author().id()).isEqualTo(AUTHOR_ID);
            assertThat(post.liked()).isTrue();
            verify(blogCards).mediaOf(List.of(7L), MEMBER_ID, MEDIA_BASE);
        }

        @Test
        @DisplayName("UTC-061 Xem trang \"Dành cho bạn\" không thay đổi dữ liệu")
        void utc061_pageChangesNoData() {
            memberWith(AREA_ID, PREFECTURE, "ARR");
            rankedPosts(12);

            service.forYouPage(MEMBER_ID, "NEAR", 1, "ueno");

            assertNothingWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. forYouPage – UC 1.1 Tìm trong bài dành cho bạn (UTC-062 → UTC-072)")
    class SearchForYou {

        @Test
        @DisplayName("UTC-062 Từ khóa bỏ dấu, chữ thường; trang hiện lại từ khóa đã nhập")
        void utc062_searchTextFoldedAndEchoed() {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, "  Chợ Đêm Ueno  ");

            assertThat(feed.q()).isEqualTo("Chợ Đêm Ueno");
            assertThat(rankingCall().param("kw")).isEqualTo("%cho dem ueno%");
        }

        @Test
        @DisplayName("UTC-063 Tìm trong tiêu đề, nội dung và tên địa điểm đang hoạt động gắn với bài")
        void utc063_searchCoversTitleContentAndPlaceNames() {
            service.forYouPage(MEMBER_ID, null, 0, "ueno");

            assertThat(rankingCall().flat()).contains("b.title COLLATE utf8mb4_0900_ai_ci LIKE :kw",
                    "b.content COLLATE utf8mb4_0900_ai_ci LIKE :kw", "kp.status = 'ACTIVE'",
                    "kp.name_vi COLLATE utf8mb4_0900_ai_ci LIKE :kw", "kp.name_ja COLLATE utf8mb4_0900_ai_ci LIKE :kw",
                    "kp.name_en COLLATE utf8mb4_0900_ai_ci LIKE :kw");
        }

        @Test
        @DisplayName("UTC-064 Số bài từng phạm vi được đếm trên các bài tìm thấy")
        void utc064_countsUseSearch() {
            memberWith(AREA_ID, PREFECTURE, "ARR");
            counts = counts(2, 1, 1, 0);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, "ueno");

            Call count = countsCall();
            assertThat(count.param("kw")).isEqualTo("%ueno%");
            assertThat(count.flat()).contains("LIKE :kw");
            assertThat(feed.scopes()).containsExactly(new ScopeCount("ALL", 2), new ScopeCount("NEAR", 1),
                    new ScopeCount("STAGE", 1));
        }

        @ParameterizedTest(name = "[{index}] q = \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("UTC-065 Ô tìm kiếm trống: không tìm, hiện cả bảng tin")
        void utc065_blankQueryNoSearch(String q) {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, q);

            assertThat(feed.q()).isNull();
            Call call = rankingCall();
            assertThat(call.has("kw")).isFalse();
            assertThat(call.flat()).doesNotContain("LIKE :kw");
        }

        @Test
        @DisplayName("UTC-066 Từ khóa đúng 100 ký tự: được tìm")
        void utc066_q100Accepted() {
            String q = "x".repeat(100);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, q);

            assertThat(feed.q()).isEqualTo(q);
            assertThat(rankingCall().param("kw")).isEqualTo("%" + q + "%");
        }

        @Test
        @DisplayName("UTC-067 Từ khóa 101 ký tự: báo lỗi, không đọc dữ liệu")
        void utc067_q101Refused() {
            assertApiError(() -> service.forYouPage(MEMBER_ID, null, 0, "x".repeat(101)), HttpStatus.BAD_REQUEST,
                    TOO_LONG);

            verifyNoInteractions(jdbc, named, interestService, blogCards);
        }

        @Test
        @DisplayName("UTC-068 100 ký tự kèm khoảng trắng hai đầu: được tìm (khoảng trắng bị bỏ)")
        void utc068_q100WithSurroundingSpacesAccepted() {
            String q = "x".repeat(100);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, "  " + q + "  ");

            assertThat(feed.q()).isEqualTo(q);
        }

        @Test
        @DisplayName("UTC-069 150 khoảng trắng: xem như ô trống, không báo lỗi")
        void utc069_onlySpacesNoSearch() {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, " ".repeat(150));

            assertThat(feed.q()).isNull();
            assertThat(rankingCall().has("kw")).isFalse();
        }

        @ParameterizedTest(name = "[{index}] q = {0}")
        @CsvSource(delimiter = '|', value = {"50%|%50\\%%", "_abc|%\\_abc%", "a\\b|%a\\\\b%"})
        @DisplayName("UTC-070 Ký tự %, _ và \\ được tìm như chữ thường")
        void utc070_likeSignsEscaped(String q, String expected) {
            service.forYouPage(MEMBER_ID, null, 0, q);

            assertThat(rankingCall().param("kw")).isEqualTo(expected);
        }

        @Test
        @DisplayName("UTC-071 Tìm trong một phạm vi: lọc phạm vi và từ khóa cùng lúc")
        void utc071_searchWithScope() {
            memberWith(AREA_ID, PREFECTURE, null);

            ForYouFeed feed = service.forYouPage(MEMBER_ID, "NEAR", 0, "ueno");

            assertThat(feed.scope()).isEqualTo("NEAR");
            assertThat(feed.q()).isEqualTo("ueno");
            Call call = rankingCall();
            assertThat(call.flat()).contains("WHERE f.near > 0", "LIKE :kw");
            assertThat(call.param("kw")).isEqualTo("%ueno%");
        }

        @Test
        @DisplayName("UTC-072 Từ khóa tiếng Nhật được giữ nguyên")
        void utc072_japaneseTextKept() {
            ForYouFeed feed = service.forYouPage(MEMBER_ID, null, 0, "東京タワー");

            assertThat(feed.q()).isEqualTo("東京タワー");
            assertThat(rankingCall().param("kw")).isEqualTo("%東京タワー%");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. escapeLike, excerpt – UC 1.1 Hàm phụ của thẻ bài (UTC-073 → UTC-079)")
    class TextHelpers {

        @Test
        @DisplayName("UTC-073 escapeLike thoát %, _ và \\, giữ nguyên chữ thường")
        void utc073_escapeLike() {
            assertThat(HomeService.escapeLike("50%")).isEqualTo("50\\%");
            assertThat(HomeService.escapeLike("a_b")).isEqualTo("a\\_b");
            assertThat(HomeService.escapeLike("c:\\x")).isEqualTo("c:\\\\x");
            assertThat(HomeService.escapeLike("Ueno")).isEqualTo("Ueno");
        }

        @Test
        @DisplayName("UTC-074 Đoạn trích đúng 140 ký tự: giữ nguyên, không thêm \"…\"")
        void utc074_excerptExactMaxUnchanged() {
            String text = "a".repeat(140);

            assertThat(HomeService.excerpt(text, 140)).isEqualTo(text);
        }

        @Test
        @DisplayName("UTC-075 Đoạn trích 141 ký tự: cắt còn 140 và thêm \"…\"")
        void utc075_excerptOverMaxCut() {
            assertThat(HomeService.excerpt("a".repeat(141), 140)).isEqualTo("a".repeat(140) + "…");
        }

        @Test
        @DisplayName("UTC-076 Đoạn trích gộp xuống dòng và khoảng trắng thành một dòng")
        void utc076_excerptFlattensWhitespace() {
            assertThat(HomeService.excerpt("  Dòng 1\n\n  Dòng 2\t ", 140)).isEqualTo("Dòng 1 Dòng 2");
        }

        @Test
        @DisplayName("UTC-077 Không có nội dung: đoạn trích rỗng")
        void utc077_excerptOfNullIsEmpty() {
            assertThat(HomeService.excerpt(null, 140)).isEmpty();
        }

        @Test
        @DisplayName("UTC-078 Không cắt đôi emoji ở chỗ cắt")
        void utc078_excerptNeverSplitsEmoji() {
            String text = "a".repeat(139) + "😀😀";

            assertThat(HomeService.excerpt(text, 140)).isEqualTo("a".repeat(139) + "😀…");
        }

        @Test
        @DisplayName("UTC-079 Chỗ cắt là khoảng trắng: bỏ khoảng trắng trước \"…\"")
        void utc079_excerptTrimsBeforeEllipsis() {
            assertThat(HomeService.excerpt("a".repeat(139) + " bcd", 140)).isEqualTo("a".repeat(139) + "…");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("05. HomeController.member / forYou – UC 1.1 API bảng tin (UTC-080 → UTC-082)")
    class Controller {

        private final AppUserPrincipal member = new AppUserPrincipal(MEMBER_ID, "member10@example.org", "Minh Anh",
                "USER", "/");
        private HomeService homeService;
        private PhraseOfDayService phrases;
        private HomeController controller;

        @BeforeEach
        void setUpController() {
            homeService = mock(HomeService.class);
            phrases = mock(PhraseOfDayService.class);
            controller = new HomeController(homeService, mock(HomeReviewService.class), phrases);
        }

        @Test
        @DisplayName("UTC-080 /api/me/home giữ câu tiếng Nhật hôm nay trước rồi mới đọc Trang chủ")
        void utc080_memberKeepsPhraseFirst() {
            MemberHome home = new MemberHome("Minh Anh", "ARR", AREA_ID, AREA_NAME, 120, "Người mới", null, null,
                    List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), null);
            when(homeService.member(MEMBER_ID)).thenReturn(home);

            ApiResponse<MemberHome> response = controller.member(member);

            InOrder order = inOrder(phrases, homeService);
            order.verify(phrases).phraseOfToday();
            order.verify(homeService).member(MEMBER_ID);
            assertThat(response.success()).isTrue();
            assertThat(response.message()).isEqualTo("OK");
            assertThat(response.data()).isSameAs(home);
        }

        @Test
        @DisplayName("UTC-081 /api/me/for-you chuyển nguyên phạm vi, trang và từ khóa")
        void utc081_forYouPassesParams() {
            ForYouFeed feed = new ForYouFeed("NEAR", "ueno", AREA_NAME, "ARR", List.of(), List.of(), List.of(), 2,
                    false, List.of());
            when(homeService.forYouPage(MEMBER_ID, "NEAR", 2, "ueno")).thenReturn(feed);

            ApiResponse<ForYouFeed> response = controller.forYou(member, "NEAR", 2, "ueno");

            verify(homeService).forYouPage(MEMBER_ID, "NEAR", 2, "ueno");
            verifyNoInteractions(phrases);
            assertThat(response.success()).isTrue();
            assertThat(response.message()).isEqualTo("OK");
            assertThat(response.data()).isSameAs(feed);
        }

        @Test
        @DisplayName("UTC-082 /api/me/for-you: lỗi từ khóa quá dài được trả về nguyên vẹn")
        void utc082_forYouPropagatesError() {
            String q = "x".repeat(101);
            when(homeService.forYouPage(MEMBER_ID, null, 0, q)).thenThrow(ApiException.badRequest(TOO_LONG));

            assertApiError(() -> controller.forYou(member, null, 0, q), HttpStatus.BAD_REQUEST, TOO_LONG);
        }
    }
}
