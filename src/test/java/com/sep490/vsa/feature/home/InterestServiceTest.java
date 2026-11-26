package com.sep490.vsa.feature.home;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.home.HomeDtos.InterestSettings;
import com.sep490.vsa.feature.home.HomeDtos.InterestTopic;
import com.sep490.vsa.feature.home.InterestService.Interest;
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
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests of "Chủ đề quan tâm" ({@link InterestService}), the topics part of UC 1.2 Update Feed Preferences (and what
 * "Dành cho bạn" of UC 1.1 reads), written from CLAUDE.md "Chủ đề quan tâm": at most 5 chosen topics, repeats count
 * once, only active topics other than "Khác", Members only (MSG18 for staff), the users row locked first, topics learnt
 * from likes, saves, comments and own posts of the last 90 days.
 * <p>
 * The database is not used. {@link JdbcTemplate} and the {@link NamedParameterJdbcTemplate} InterestService builds from
 * it (swapped for a mock after construction) answer from small in-memory tables: the users row (role, status), the
 * topics the member chose, the topics learnt, and the offered topics (every active topic but "Khác", Blog order:
 * 1 Kinh nghiệm … 9 Mua sắm; 11 is "Khác", 12 a switched-off topic). The tests check what is sent to the database and
 * what is built from the rows; the clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("InterestService – Chủ đề quan tâm của bảng tin (UC 1.2)")
class InterestServiceTest {

    private static final long MEMBER_ID = 10L;
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final String NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String TOO_MANY = "Bạn chọn được tối đa 5 chủ đề.";
    private static final String NOT_OFFERED = "Có chủ đề không còn để chọn. Hãy tải lại trang rồi chọn lại.";
    private static final String INVALID_LIST = "Danh sách chủ đề không hợp lệ.";
    private static final String READ_ACCOUNT = "SELECT role, status FROM users WHERE id = ?";
    private static final String LOCK_ACCOUNT = READ_ACCOUNT + " FOR UPDATE";

    /** Every topic a member can choose, in the Blog's order (sort_order, name). */
    private static final List<Row> OFFERED = List.of(topic(1L, "Kinh nghiệm"), topic(2L, "Thủ tục giấy tờ"),
            topic(3L, "Nhà ở"), topic(4L, "Việc làm thêm"), topic(5L, "Đi lại"), topic(6L, "Ăn uống"),
            topic(7L, "Y tế"), topic(8L, "Học tiếng Nhật"), topic(9L, "Mua sắm"));

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private InterestService service;

    /** The users row (role, status); null = no such account. */
    private Row account;
    /** What the "chosen" query returns (already in the Blog's order). */
    private final List<Row> chosen = new ArrayList<>();
    /** What the "learnt" query returns (already heaviest first). */
    private final List<Row> learnt = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, this::jdbcAnswer);
        named = mock(NamedParameterJdbcTemplate.class, this::namedAnswer);
        service = new InterestService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        // InterestService builds its NamedParameterJdbcTemplate from the JdbcTemplate: the mock takes its place
        ReflectionTestUtils.setField(service, "named", named);
        accountIs("USER", "ACTIVE");
    }

    // ---------------------------------------------------------------- the mocked database

    /** One row of a mocked query: column name → value, in column order (read by name or by 1-based index). */
    private static final class Row extends LinkedHashMap<String, Object> {
        Row set(String column, Object value) {
            put(column, value);
            return this;
        }
    }

    private static Row topic(long id, String name) {
        return new Row().set("id", id).set("name", name);
    }

    private Object jdbcAnswer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        String sql = args.length > 0 && args[0] instanceof String s ? s : "";
        if (invocation.getMethod().getName().equals("query") && args.length >= 2
                && args[1] instanceof RowMapper<?> mapper) {
            if (sql.startsWith(READ_ACCOUNT)) {
                return account == null ? List.of() : mapRows(mapper, List.of(account));
            }
            if (sql.contains("FROM user_interest_topics ui")) {
                return mapRows(mapper, chosen);
            }
            if (sql.contains("FROM blog_topics WHERE is_active")) {
                return mapRows(mapper, OFFERED);
            }
            return List.of();
        }
        return RETURNS_DEFAULTS.answer(invocation);
    }

    private Object namedAnswer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        switch (invocation.getMethod().getName()) {
            case "query":
                return mapRows((RowMapper<?>) args[2], learnt);
            case "queryForObject":
                // COUNT(*) of the asked ids among the offered topics (active, not "Khác")
                Collection<?> ids = (Collection<?>) ((SqlParameterSource) args[1]).getValue("ids");
                return (int) OFFERED.stream().filter(t -> ids.contains(t.get("id"))).count();
            case "update":
                return 1;
            default:
                return RETURNS_DEFAULTS.answer(invocation);
        }
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
        return (ResultSet) Proxy.newProxyInstance(InterestServiceTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    Object value = args[0] instanceof Integer index ? byIndex.get(index - 1) : row.get(args[0]);
                    return switch (method.getName()) {
                        case "getString" -> value == null ? null : value.toString();
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    /** A statement sent to the NamedParameterJdbcTemplate. */
    private record Call(String sql, SqlParameterSource params) {

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

    private Call learntCall() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).query(sql.capture(), params.capture(), any(RowMapper.class));
        return new Call(sql.getValue(), params.getValue());
    }

    private Call offeredCheck() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(sql.capture(), params.capture(), eq(Integer.class));
        return new Call(sql.getValue(), params.getValue());
    }

    private Call deleteCall() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).update(sql.capture(), params.capture());
        return new Call(sql.getValue(), params.getValue());
    }

    /** The rows given to the INSERT batch, each as [userId, topicId]. */
    @SuppressWarnings("unchecked")
    private List<List<Object>> inserted() {
        ArgumentCaptor<List<Object[]>> rows = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(contains("INSERT INTO user_interest_topics"), rows.capture());
        return rows.getValue().stream().map(Arrays::asList).toList();
    }

    // ---------------------------------------------------------------- helpers

    /** A collection parameter (the ids bound to an IN list) as a list, in its iteration order. */
    private static List<Object> values(Object parameter) {
        return new ArrayList<>((Collection<?>) parameter);
    }

    private void accountIs(String role, String status) {
        account = new Row().set("role", role).set("status", status);
    }

    private void chosenAre(Row... topics) {
        chosen.clear();
        chosen.addAll(List.of(topics));
    }

    private void learntAre(Row... topics) {
        learnt.clear();
        learnt.addAll(List.of(topics));
    }

    /** Names of the method calls the mocks received, e.g. "update" and "batchUpdate". */
    private List<String> calls(Object mock) {
        return mockingDetails(mock).getInvocations().stream().map(i -> i.getMethod().getName()).toList();
    }

    /** No offered-topic check, no DELETE, no INSERT. */
    private void assertNothingWritten() {
        assertThat(calls(named)).doesNotContain("queryForObject", "update");
        assertThat(calls(jdbc)).doesNotContain("update", "batchUpdate");
    }

    /** Every SQL text sent to the JdbcTemplate, in order. */
    private List<String> sqlRun() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String)
                .map(i -> (String) i.getArguments()[0])
                .toList();
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

    private static List<String> flagged(InterestSettings settings, boolean chosen) {
        return settings.topics().stream().filter(t -> chosen ? t.chosen() : t.learnt()).map(InterestTopic::name)
                .toList();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. update – UC 1.2 Lưu \"Chủ đề quan tâm\" (UTC-112 → UTC-129)")
    class UpdateInterests {

        @Test
        @DisplayName("UTC-112 Lưu lựa chọn mới: kiểm tra chủ đề, bỏ chủ đề không còn chọn, thêm chủ đề mới")
        void utc112_savesTheWholeChoice() {
            service.update(MEMBER_ID, List.of(8L, 3L));

            Call check = offeredCheck();
            assertThat(values(check.param("ids"))).containsExactly(8L, 3L);
            Call delete = deleteCall();
            assertThat(delete.sql())
                    .isEqualTo("DELETE FROM user_interest_topics WHERE user_id = :userId AND topic_id NOT IN (:ids)");
            assertThat(delete.param("userId")).isEqualTo(MEMBER_ID);
            assertThat(values(delete.param("ids"))).containsExactly(8L, 3L);
            assertThat(inserted()).containsExactly(List.of(MEMBER_ID, 8L), List.of(MEMBER_ID, 3L));
        }

        @Test
        @DisplayName("UTC-113 Khóa dòng tài khoản (FOR UPDATE) trước mọi thao tác ghi")
        void utc113_usersRowLockedFirst() {
            service.update(MEMBER_ID, List.of(8L));

            InOrder order = inOrder(jdbc, named);
            order.verify(jdbc).query(eq(LOCK_ACCOUNT), any(RowMapper.class), eq(MEMBER_ID));
            order.verify(named).queryForObject(contains("FROM blog_topics"), any(SqlParameterSource.class),
                    eq(Integer.class));
            order.verify(named).update(startsWith("DELETE FROM user_interest_topics"), any(SqlParameterSource.class));
            order.verify(jdbc).batchUpdate(contains("INSERT INTO user_interest_topics"), anyList());
        }

        @Test
        @DisplayName("UTC-114 Chủ đề lặp lại chỉ tính một lần")
        void utc114_repeatsCountOnce() {
            service.update(MEMBER_ID, List.of(8L, 3L, 8L));

            assertThat(values(offeredCheck().param("ids"))).containsExactly(8L, 3L);
            assertThat(inserted()).containsExactly(List.of(MEMBER_ID, 8L), List.of(MEMBER_ID, 3L));
        }

        @Test
        @DisplayName("UTC-115 Chọn đúng 5 chủ đề: được lưu")
        void utc115_exactlyFiveSaved() {
            service.update(MEMBER_ID, List.of(1L, 2L, 3L, 4L, 5L));

            assertThat(inserted()).hasSize(5);
        }

        @Test
        @DisplayName("UTC-116 6 lựa chọn nhưng chỉ 5 chủ đề khác nhau: được lưu")
        void utc116_sixEntriesFiveDifferentSaved() {
            service.update(MEMBER_ID, List.of(1L, 2L, 3L, 4L, 5L, 1L));

            assertThat(inserted()).extracting(row -> row.get(1)).containsExactly(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        @DisplayName("UTC-117 Chọn 6 chủ đề khác nhau: báo \"tối đa 5 chủ đề\", không lưu gì")
        void utc117_sixDifferentRefused() {
            assertApiError(() -> service.update(MEMBER_ID, List.of(1L, 2L, 3L, 4L, 5L, 6L)), HttpStatus.BAD_REQUEST,
                    TOO_MANY);

            assertNothingWritten();
        }

        @Test
        @DisplayName("UTC-118 Danh sách rỗng: bỏ hết chủ đề đã chọn")
        void utc118_emptyListClears() {
            service.update(MEMBER_ID, List.of());

            assertThat(calls(named)).doesNotContain("queryForObject");
            assertThat(deleteCall().sql()).isEqualTo("DELETE FROM user_interest_topics WHERE user_id = :userId");
            assertThat(inserted()).isEmpty();
        }

        @Test
        @DisplayName("UTC-119 Không gửi danh sách (null): báo \"Danh sách chủ đề không hợp lệ.\"")
        void utc119_nullListRefused() {
            assertApiError(() -> service.update(MEMBER_ID, null), HttpStatus.BAD_REQUEST, INVALID_LIST);

            assertNothingWritten();
        }

        @Test
        @DisplayName("UTC-120 Danh sách có phần tử trống: báo \"Danh sách chủ đề không hợp lệ.\"")
        void utc120_nullElementRefused() {
            assertApiError(() -> service.update(MEMBER_ID, Arrays.asList(3L, null)), HttpStatus.BAD_REQUEST,
                    INVALID_LIST);

            assertNothingWritten();
        }

        @ParameterizedTest(name = "[{index}] topicId = {0}")
        @ValueSource(longs = {11L, 12L, 999L})
        @DisplayName("UTC-121 Chủ đề \"Khác\", đã tắt hoặc không tồn tại: báo \"không còn để chọn\", không lưu gì")
        void utc121_topicNotOfferedRefused(long topicId) {
            assertApiError(() -> service.update(MEMBER_ID, List.of(3L, topicId)), HttpStatus.BAD_REQUEST, NOT_OFFERED);

            assertThat(calls(named)).doesNotContain("update");
            assertThat(calls(jdbc)).doesNotContain("batchUpdate");
        }

        @Test
        @DisplayName("UTC-122 Đếm chủ đề trả về null: xem như không còn để chọn")
        void utc122_countNullRefused() {
            doReturn(null).when(named).queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class));

            assertApiError(() -> service.update(MEMBER_ID, List.of(3L)), HttpStatus.BAD_REQUEST, NOT_OFFERED);

            assertThat(calls(named)).doesNotContain("update");
            assertThat(calls(jdbc)).doesNotContain("batchUpdate");
        }

        @Test
        @DisplayName("UTC-123 Chỉ chủ đề đang bật và không phải \"Khác\" được tính là còn để chọn")
        void utc123_offeredCheckSql() {
            service.update(MEMBER_ID, List.of(3L));

            assertThat(offeredCheck().flat())
                    .isEqualTo("SELECT COUNT(*) FROM blog_topics WHERE id IN (:ids) AND is_active AND NOT is_other");
        }

        @Test
        @DisplayName("UTC-124 Chủ đề giữ lại không bị xóa rồi thêm lại (giữ ngày chọn đầu tiên)")
        void utc124_keptTopicsKeepTheirDate() {
            service.update(MEMBER_ID, List.of(3L, 7L));

            assertThat(deleteCall().sql()).contains("AND topic_id NOT IN (:ids)");
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).batchUpdate(sql.capture(), anyList());
            assertThat(sql.getValue().replaceAll("\\s+", " ")).isEqualTo("INSERT INTO user_interest_topics "
                    + "(user_id, topic_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE user_id = user_id");
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-125 Nhân viên: báo không có quyền (MSG18), không lưu gì")
        void utc125_staffRefused(String role) {
            accountIs(role, "ACTIVE");

            assertApiError(() -> service.update(MEMBER_ID, List.of(3L)), HttpStatus.FORBIDDEN, MSG18);

            assertNothingWritten();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-126 Thành viên bị khóa hoặc đã xóa: báo không có quyền (MSG18), không lưu gì")
        void utc126_lockedOrDeletedRefused(String status) {
            accountIs("USER", status);

            assertApiError(() -> service.update(MEMBER_ID, List.of(3L)), HttpStatus.FORBIDDEN, MSG18);

            assertNothingWritten();
        }

        @Test
        @DisplayName("UTC-127 Tài khoản không còn: báo chưa đăng nhập, không lưu gì")
        void utc127_missingAccountUnauthorized() {
            account = null;

            assertApiError(() -> service.update(MEMBER_ID, List.of(3L)), HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);

            assertNothingWritten();
        }

        @Test
        @DisplayName("UTC-128 Quyền được kiểm tra trước danh sách: nhân viên gửi null nhận MSG18")
        void utc128_roleCheckedBeforeList() {
            accountIs("MODERATOR", "ACTIVE");

            assertApiError(() -> service.update(MEMBER_ID, null), HttpStatus.FORBIDDEN, MSG18);
        }

        @Test
        @DisplayName("UTC-129 Trả về danh sách chủ đề đọc lại sau khi lưu")
        void utc129_answerIsTheNewSettings() {
            chosenAre(topic(3L, "Nhà ở"), topic(8L, "Học tiếng Nhật"));
            learntAre(topic(6L, "Ăn uống"));

            InterestSettings answer = service.update(MEMBER_ID, List.of(8L, 3L));

            assertThat(answer.max()).isEqualTo(5);
            assertThat(flagged(answer, true)).containsExactly("Nhà ở", "Học tiếng Nhật");
            assertThat(flagged(answer, false)).containsExactly("Ăn uống");
            InOrder order = inOrder(jdbc);
            order.verify(jdbc).batchUpdate(contains("INSERT INTO user_interest_topics"), anyList());
            order.verify(jdbc).query(contains("FROM user_interest_topics ui"), any(RowMapper.class), eq(MEMBER_ID),
                    eq(5));
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. settings – UC 1.2 Xem \"Chủ đề quan tâm\" (UTC-130 → UTC-137)")
    class Settings {

        @Test
        @DisplayName("UTC-130 Thành viên thấy mọi chủ đề theo thứ tự Blog, đánh dấu đã chọn và gợi ý")
        void utc130_memberSeesTopicsWithFlags() {
            chosenAre(topic(3L, "Nhà ở"));
            learntAre(topic(6L, "Ăn uống"), topic(7L, "Y tế"));

            InterestSettings settings = service.settings(MEMBER_ID);

            assertThat(settings.max()).isEqualTo(5);
            assertThat(settings.topics()).extracting(InterestTopic::id)
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
            assertThat(settings.topics()).filteredOn(t -> t.chosen() || t.learnt())
                    .extracting(InterestTopic::name, InterestTopic::chosen, InterestTopic::learnt)
                    .containsExactly(tuple("Nhà ở", true, false), tuple("Ăn uống", false, true),
                            tuple("Y tế", false, true));
        }

        @Test
        @DisplayName("UTC-131 Thành viên mới: chưa chọn, chưa có gợi ý")
        void utc131_newMemberNothingFlagged() {
            InterestSettings settings = service.settings(MEMBER_ID);

            assertThat(settings.topics()).hasSize(9);
            assertThat(settings.topics()).noneMatch(t -> t.chosen() || t.learnt());
        }

        @Test
        @DisplayName("UTC-132 Xem không khóa dòng tài khoản")
        void utc132_readDoesNotLock() {
            service.settings(MEMBER_ID);

            verify(jdbc).query(eq(READ_ACCOUNT), any(RowMapper.class), eq(MEMBER_ID));
            assertThat(sqlRun()).noneMatch(sql -> sql.contains("FOR UPDATE"));
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-133 Nhân viên: báo không có quyền (MSG18), không đọc chủ đề")
        void utc133_staffRefused(String role) {
            accountIs(role, "ACTIVE");

            assertApiError(() -> service.settings(MEMBER_ID), HttpStatus.FORBIDDEN, MSG18);

            assertThat(sqlRun()).containsExactly(READ_ACCOUNT);
            verifyNoInteractions(named);
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"LOCKED", "DELETED"})
        @DisplayName("UTC-134 Thành viên bị khóa hoặc đã xóa: báo không có quyền (MSG18)")
        void utc134_lockedOrDeletedRefused(String status) {
            accountIs("USER", status);

            assertApiError(() -> service.settings(MEMBER_ID), HttpStatus.FORBIDDEN, MSG18);
        }

        @Test
        @DisplayName("UTC-135 Tài khoản không còn: báo chưa đăng nhập")
        void utc135_missingAccountUnauthorized() {
            account = null;

            assertApiError(() -> service.settings(MEMBER_ID), HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);
        }

        @Test
        @DisplayName("UTC-136 Chỉ liệt kê chủ đề đang bật, không có \"Khác\", theo thứ tự Blog")
        void utc136_offeredTopicsSql() {
            service.settings(MEMBER_ID);

            assertThat(sqlRun()).anyMatch(sql -> sql.replaceAll("\\s+", " ").trim().equals(
                    "SELECT id, name FROM blog_topics WHERE is_active AND NOT is_other ORDER BY sort_order, name"));
        }

        @Test
        @DisplayName("UTC-137 Xem không thay đổi dữ liệu")
        void utc137_settingsWritesNothing() {
            chosenAre(topic(3L, "Nhà ở"));

            service.settings(MEMBER_ID);

            assertNothingWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. interests – UC 1.2 Chủ đề bảng tin dùng để xếp bài (UTC-138 → UTC-145)")
    class Interests {

        @Test
        @DisplayName("UTC-138 Chủ đề đã chọn trước (thứ tự Blog), chủ đề học được sau (nặng nhất trước)")
        void utc138_chosenThenLearnt() {
            chosenAre(topic(3L, "Nhà ở"), topic(8L, "Học tiếng Nhật"));
            learntAre(topic(6L, "Ăn uống"), topic(5L, "Đi lại"));

            List<Interest> interests = service.interests(MEMBER_ID);

            assertThat(interests).containsExactly(new Interest(3L, "Nhà ở", true),
                    new Interest(8L, "Học tiếng Nhật", true), new Interest(6L, "Ăn uống", false),
                    new Interest(5L, "Đi lại", false));
        }

        @Test
        @DisplayName("UTC-139 Chủ đề đã chọn: tối đa 5, chỉ chủ đề đang bật, không có \"Khác\"")
        void utc139_chosenQuery() {
            service.interests(MEMBER_ID);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(MEMBER_ID), eq(5));
            assertThat(sql.getValue().replaceAll("\\s+", " ")).contains("FROM user_interest_topics ui",
                    "WHERE ui.user_id = ? AND t.is_active AND NOT t.is_other", "ORDER BY t.sort_order, t.name LIMIT ?");
        }

        @Test
        @DisplayName("UTC-140 Chủ đề học được: 90 ngày gần đây, tối thiểu 2 điểm, tối đa 3 chủ đề")
        void utc140_learntParams() {
            service.interests(MEMBER_ID);

            Call call = learntCall();
            assertThat(call.param("userId")).isEqualTo(MEMBER_ID);
            assertThat(call.param("since")).isEqualTo(Timestamp.from(Instant.parse("2026-08-22T03:00:00Z")));
            assertThat(call.param("minWeight")).isEqualTo(2);
            assertThat(call.param("limit")).isEqualTo(3);
        }

        @Test
        @DisplayName("UTC-141 Chủ đề đã chọn không bao giờ là chủ đề học được")
        void utc141_learntExcludesChosen() {
            chosenAre(topic(3L, "Nhà ở"), topic(8L, "Học tiếng Nhật"));

            service.interests(MEMBER_ID);

            Call call = learntCall();
            assertThat(values(call.param("chosenIds"))).containsExactlyInAnyOrder(3L, 8L);
            assertThat(call.flat()).contains("AND NOT t.is_other AND t.id NOT IN (:chosenIds)");
        }

        @Test
        @DisplayName("UTC-142 Chưa chọn chủ đề nào: không loại trừ chủ đề nào")
        void utc142_noChosenNoExclusion() {
            service.interests(MEMBER_ID);

            Call call = learntCall();
            assertThat(call.has("chosenIds")).isFalse();
            assertThat(call.flat()).doesNotContain("NOT IN");
        }

        @Test
        @DisplayName("UTC-143 Trọng số: thích 1, lưu 2, bình luận đã đăng 2 (mỗi bài một lần), bài của mình 3")
        void utc143_learntWeights() {
            service.interests(MEMBER_ID);

            assertThat(learntCall().flat()).contains("SELECT l.blog_id, 1 AS weight FROM blog_likes l",
                    "SELECT s.blog_id, 2 FROM saved_blogs s", "SELECT DISTINCT c.blog_id, 2 FROM comments c",
                    "c.status = 'PUBLISHED'", "SELECT b.id, 3 FROM blogs b", "b.status = 'PUBLISHED'",
                    "HAVING SUM(acts.weight) >= :minWeight", "ORDER BY SUM(acts.weight) DESC, t.sort_order, t.name",
                    "LIMIT :limit");
        }

        @Test
        @DisplayName("UTC-144 Không chọn, không hoạt động gì: danh sách rỗng")
        void utc144_nothingGivesEmptyList() {
            assertThat(service.interests(MEMBER_ID)).isEmpty();
        }

        @Test
        @DisplayName("UTC-145 Đọc cho bảng tin: không kiểm tra vai trò, không thay đổi dữ liệu")
        void utc145_noRoleCheckNoWrite() {
            accountIs("MODERATOR", "ACTIVE");
            chosenAre(topic(3L, "Nhà ở"));

            assertThat(service.interests(MEMBER_ID)).extracting(Interest::name).containsExactly("Nhà ở");

            assertThat(sqlRun()).noneMatch(sql -> sql.contains("FROM users"));
            assertNothingWritten();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. chosenTopicIds – UC 1.2 Chủ đề đã lưu trả về cho \"Điều chỉnh\" (UTC-146 → UTC-147)")
    class ChosenTopicIds {

        @Test
        @DisplayName("UTC-146 Trả về mã các chủ đề đã chọn theo thứ tự Blog")
        void utc146_idsInBlogOrder() {
            chosenAre(topic(3L, "Nhà ở"), topic(8L, "Học tiếng Nhật"));

            assertThat(service.chosenTopicIds(MEMBER_ID)).containsExactly(3L, 8L);
        }

        @Test
        @DisplayName("UTC-147 Chưa chọn chủ đề nào: danh sách rỗng")
        void utc147_noneGivesEmptyList() {
            assertThat(service.chosenTopicIds(MEMBER_ID)).isEmpty();
            assertThat(calls(named)).isEmpty();
        }
    }
}
