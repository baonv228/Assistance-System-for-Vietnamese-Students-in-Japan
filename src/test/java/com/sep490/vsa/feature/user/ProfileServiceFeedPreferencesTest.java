package com.sep490.vsa.feature.user;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.blog.AuthorStatusSync;
import com.sep490.vsa.feature.home.InterestService;
import com.sep490.vsa.feature.media.MediaService;
import com.sep490.vsa.feature.saved.SavedRepository;
import com.sep490.vsa.feature.user.ProfileDtos.FeedPreferencesResponse;
import com.sep490.vsa.feature.user.ProfileDtos.ProfileResponse;
import com.sep490.vsa.feature.user.ProfileDtos.UpdateFeedPreferencesRequest;
import com.sep490.vsa.feature.user.ProfileDtos.UpdateProfileRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.2 Update Feed Preferences on the {@link ProfileService} side (SRS Report 3, II.1.2, and the later
 * decisions in CLAUDE.md): "Điều chỉnh" in "Dành cho bạn" on the member Home saves area, stage and "Chủ đề quan tâm"
 * together or not at all ({@link ProfileService#updateFeedPreferences}), "Tôi" saves area and stage
 * ({@link ProfileService#update}); the stage values are checked by the request bodies ({@link UpdateProfileRequest},
 * {@link UpdateFeedPreferencesRequest}).
 * <p>
 * The database is not used: {@link UserRepository} returns real {@link User} entities built in the test, the
 * {@link JdbcTemplate} mock answers the users-row lock, the active areas (101 and 102; 998 is switched off, 999 does not
 * exist) and the reads of the "Tôi" page from small in-memory tables. {@link InterestService} (the topic rules, tested
 * in InterestServiceTest), {@link SavedRepository}, {@link SystemSettings} and the other collaborators are mocks.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ProfileService – Cập nhật tùy chọn bảng tin: khu vực, giai đoạn, chủ đề quan tâm (UC 1.2)")
class ProfileServiceFeedPreferencesTest {

    private static final long MEMBER_ID = 10L;
    private static final long MOD_ID = 20L;
    private static final long AREA_ID = 101L;
    private static final long OTHER_AREA_ID = 102L;
    private static final long OFF_AREA_ID = 998L;
    private static final long MISSING_AREA_ID = 999L;
    private static final String AREA_NAME = "Arakawa-ku, Tokyo · 東京都荒川区";
    private static final String OTHER_AREA_NAME = "Naha-shi, Okinawa · 沖縄県那覇市";
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final String NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String INVALID_AREA = "Khu vực không hợp lệ";
    private static final String INVALID_STAGE = "Giai đoạn không hợp lệ";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String TOO_MANY = "Bạn chọn được tối đa 5 chủ đề.";
    private static final String NOT_OFFERED = "Có chủ đề không còn để chọn. Hãy tải lại trang rồi chọn lại.";
    private static final String INVALID_LIST = "Danh sách chủ đề không hợp lệ.";
    private static final String AREA_CHECK = "SELECT COUNT(*) FROM areas WHERE id = ? AND is_active";
    private static final String USERS_LOCK = "SELECT id FROM users WHERE id = ? FOR UPDATE";

    /** Areas a member can choose (is_active). */
    private static final Map<Long, String> ACTIVE_AREAS = Map.of(AREA_ID, AREA_NAME, OTHER_AREA_ID, OTHER_AREA_NAME);
    /** Every area row, switched off ones included (their names can still be read). */
    private static final Map<Long, String> ALL_AREAS = Map.of(AREA_ID, AREA_NAME, OTHER_AREA_ID, OTHER_AREA_NAME,
            OFF_AREA_ID, "Khu tắt");

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private UserRepository users;
    private JdbcTemplate jdbc;
    private SystemSettings settings;
    private SavedRepository saved;
    private InterestService interests;
    private ProfileService service;
    /** Member 10: area 101 and stage ARR saved before. */
    private User member;

    @BeforeAll
    static void startValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        jdbc = mock(JdbcTemplate.class, this::jdbcAnswer);
        settings = mock(SystemSettings.class);
        saved = mock(SavedRepository.class);
        interests = mock(InterestService.class);
        service = new ProfileService(users, mock(UserAuthProviderRepository.class), jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC), settings, saved, mock(ActiveAdminGuard.class),
                mock(AuthorStatusSync.class), mock(MediaService.class), mock(LiveEvents.class), interests);
        member = account(MEMBER_ID, Role.USER, UserStatus.ACTIVE, AREA_ID, "ARR");
    }

    // ---------------------------------------------------------------- the mocked database

    private Object jdbcAnswer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        String sql = args.length > 0 && args[0] instanceof String s ? s : "";
        switch (invocation.getMethod().getName()) {
            case "query":
                if (sql.equals(USERS_LOCK)) {
                    return List.of(args[2]);
                }
                if (sql.startsWith("SELECT display_name FROM areas")) {
                    String name = ALL_AREAS.get((Long) args[2]);
                    return name == null ? List.of() : List.of(name);
                }
                return List.of();               // ranks, tags, tag requests, daily usage, sign-in providers: none
            case "queryForObject":
                if (sql.equals(AREA_CHECK)) {
                    return ACTIVE_AREAS.containsKey((Long) args[2]) ? 1 : 0;
                }
                if (sql.startsWith("SELECT coins")) {
                    return 35;
                }
                return null;
            default:
                return RETURNS_DEFAULTS.answer(invocation);
        }
    }

    /** Every SQL text sent to the JdbcTemplate, in order. */
    private List<String> sqlRun() {
        return mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArguments().length > 0 && i.getArguments()[0] instanceof String)
                .map(i -> (String) i.getArguments()[0])
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    /** A real account (users row) that {@link UserRepository#findById} returns. */
    private User account(long id, Role role, UserStatus status, Long areaId, String stage) {
        User user = User.register("user" + id + "@example.org", "Minh Anh", "$2a$10$hash", null, NOW);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "role", role);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "createdAt", NOW.minus(Duration.ofDays(30)));
        user.updateProfile(areaId, stage);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
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

    /** Area and stage of {@code user} are still what they were, and nothing was flushed. */
    private void assertUnchanged(User user, Long areaId, String stage) {
        assertThat(user.getAreaId()).isEqualTo(areaId);
        assertThat(user.getStage()).isEqualTo(stage);
        verify(users, never()).flush();
    }

    private static List<String> stageMessages(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. updateFeedPreferences – UC 1.2 \"Điều chỉnh\" trên Trang chủ (UTC-083 → UTC-100)")
    class UpdateFeedPreferences {

        @Test
        @DisplayName("UTC-083 Lưu khu vực, giai đoạn và chủ đề quan tâm trong một lần")
        void utc083_savesAreaStageAndTopicsTogether() {
            when(interests.chosenTopicIds(MEMBER_ID)).thenReturn(List.of(3L, 8L));

            FeedPreferencesResponse answer = service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "PRE",
                    List.of(8L, 3L));

            assertThat(answer).isEqualTo(new FeedPreferencesResponse(OTHER_AREA_ID, OTHER_AREA_NAME, "PRE",
                    List.of(3L, 8L)));
            verify(interests).update(MEMBER_ID, List.of(8L, 3L));
            assertThat(member.getAreaId()).isEqualTo(OTHER_AREA_ID);
            assertThat(member.getStage()).isEqualTo("PRE");
            verify(users).flush();
        }

        @Test
        @DisplayName("UTC-084 Khóa dòng tài khoản trước, rồi kiểm tra khu vực, lưu chủ đề, lưu khu vực/giai đoạn")
        void utc084_usersRowLockedFirstThenOrder() {
            service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "PRE", List.of(8L));

            InOrder order = inOrder(jdbc, users, interests);
            order.verify(jdbc).query(eq(USERS_LOCK), any(RowMapper.class), eq(MEMBER_ID));
            order.verify(users).findById(MEMBER_ID);
            order.verify(jdbc).queryForObject(eq(AREA_CHECK), eq(Integer.class), eq(OTHER_AREA_ID));
            order.verify(interests).update(MEMBER_ID, List.of(8L));
            order.verify(users).flush();
            order.verify(interests).chosenTopicIds(MEMBER_ID);
        }

        @Test
        @DisplayName("UTC-085 topicIds = null: giữ nguyên chủ đề quan tâm, chỉ lưu khu vực và giai đoạn")
        void utc085_nullTopicsKept() {
            when(interests.chosenTopicIds(MEMBER_ID)).thenReturn(List.of(3L));

            FeedPreferencesResponse answer = service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "LEAVE", null);

            verify(interests, never()).update(anyLong(), any());
            assertThat(answer.topicIds()).containsExactly(3L);
            assertThat(answer.stage()).isEqualTo("LEAVE");
            assertThat(member.getAreaId()).isEqualTo(OTHER_AREA_ID);
        }

        @Test
        @DisplayName("UTC-086 Danh sách chủ đề rỗng: bỏ hết chủ đề quan tâm")
        void utc086_emptyTopicsCleared() {
            FeedPreferencesResponse answer = service.updateFeedPreferences(MEMBER_ID, AREA_ID, "LEAVE", List.of());

            verify(interests).update(MEMBER_ID, List.of());
            assertThat(answer.topicIds()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] giai đoạn {0}")
        @ValueSource(strings = {"PRE", "ARR", "LEAVE", "HOME"})
        @DisplayName("UTC-087 Bốn giai đoạn đều được lưu")
        void utc087_fourStagesSaved(String stage) {
            FeedPreferencesResponse answer = service.updateFeedPreferences(MEMBER_ID, null, stage, null);

            assertThat(member.getStage()).isEqualTo(stage);
            assertThat(answer.stage()).isEqualTo(stage);
        }

        @ParameterizedTest(name = "[{index}] stage = \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("UTC-088 Giai đoạn để trống: lưu là chưa chọn")
        void utc088_blankStageMeansNone(String stage) {
            FeedPreferencesResponse answer = service.updateFeedPreferences(MEMBER_ID, AREA_ID, stage, null);

            assertThat(member.getStage()).isNull();
            assertThat(answer.stage()).isNull();
        }

        @Test
        @DisplayName("UTC-089 \"Chưa chọn\" khu vực và giai đoạn: xóa cả hai, không cần tra khu vực")
        void utc089_clearAreaAndStage() {
            FeedPreferencesResponse answer = service.updateFeedPreferences(MEMBER_ID, null, null, null);

            assertThat(member.getAreaId()).isNull();
            assertThat(member.getStage()).isNull();
            assertThat(answer.areaId()).isNull();
            assertThat(answer.areaName()).isNull();
            assertThat(answer.stage()).isNull();
            assertThat(sqlRun()).noneMatch(sql -> sql.contains("FROM areas"));
            verify(users).flush();
        }

        @ParameterizedTest(name = "[{index}] areaId = {0}")
        @ValueSource(longs = {OFF_AREA_ID, MISSING_AREA_ID})
        @DisplayName("UTC-090 Khu vực đã tắt hoặc không tồn tại: báo lỗi, không lưu gì")
        void utc090_invalidAreaRefused(long areaId) {
            assertApiError(() -> service.updateFeedPreferences(MEMBER_ID, areaId, "PRE", List.of(3L)),
                    HttpStatus.BAD_REQUEST, INVALID_AREA);

            verify(interests, never()).update(anyLong(), any());
            assertUnchanged(member, AREA_ID, "ARR");
        }

        @Test
        @DisplayName("UTC-091 Khu vực được kiểm tra còn bật, tên khu vực đọc từ bảng areas")
        void utc091_areaCheckedActiveAndNameRead() {
            FeedPreferencesResponse answer = service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "PRE", null);

            verify(jdbc).queryForObject(AREA_CHECK, Integer.class, OTHER_AREA_ID);
            verify(jdbc).query(eq("SELECT display_name FROM areas WHERE id = ?"), any(RowMapper.class),
                    eq(OTHER_AREA_ID));
            assertThat(answer.areaName()).isEqualTo(OTHER_AREA_NAME);
        }

        @Test
        @DisplayName("UTC-092 Khu vực sai được báo trước khi xét chủ đề")
        void utc092_invalidAreaBeforeTopics() {
            List<Long> six = List.of(1L, 2L, 3L, 4L, 5L, 6L);

            assertApiError(() -> service.updateFeedPreferences(MEMBER_ID, MISSING_AREA_ID, "PRE", six),
                    HttpStatus.BAD_REQUEST, INVALID_AREA);

            verifyNoInteractions(interests);
        }

        @Test
        @DisplayName("UTC-093 Chọn quá 5 chủ đề: báo lỗi, khu vực và giai đoạn cũng không đổi")
        void utc093_tooManyTopicsSavesNothing() {
            List<Long> six = List.of(1L, 2L, 3L, 4L, 5L, 6L);
            when(interests.update(MEMBER_ID, six)).thenThrow(ApiException.badRequest(TOO_MANY));

            assertApiError(() -> service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "HOME", six),
                    HttpStatus.BAD_REQUEST, TOO_MANY);

            assertUnchanged(member, AREA_ID, "ARR");
            verify(interests, never()).chosenTopicIds(anyLong());
        }

        @Test
        @DisplayName("UTC-094 Chủ đề không còn để chọn (\"Khác\"): báo lỗi, không lưu gì")
        void utc094_topicNotOfferedSavesNothing() {
            when(interests.update(MEMBER_ID, List.of(11L))).thenThrow(ApiException.badRequest(NOT_OFFERED));

            assertApiError(() -> service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "HOME", List.of(11L)),
                    HttpStatus.BAD_REQUEST, NOT_OFFERED);

            assertUnchanged(member, AREA_ID, "ARR");
        }

        @Test
        @DisplayName("UTC-095 Danh sách chủ đề có phần tử trống: báo lỗi, không lưu gì")
        void utc095_invalidListSavesNothing() {
            List<Long> withHole = Arrays.asList(3L, null);
            when(interests.update(MEMBER_ID, withHole)).thenThrow(ApiException.badRequest(INVALID_LIST));

            assertApiError(() -> service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "PRE", withHole),
                    HttpStatus.BAD_REQUEST, INVALID_LIST);

            assertUnchanged(member, AREA_ID, "ARR");
        }

        @Test
        @DisplayName("UTC-096 Nhân viên gửi kèm chủ đề quan tâm: báo không có quyền (MSG18), không lưu gì")
        void utc096_staffWithTopicsRefused() {
            User moderator = account(MOD_ID, Role.MODERATOR, UserStatus.ACTIVE, null, null);
            when(interests.update(eq(MOD_ID), any())).thenThrow(ApiException.forbidden(MSG18));

            assertApiError(() -> service.updateFeedPreferences(MOD_ID, AREA_ID, "ARR", List.of(3L)),
                    HttpStatus.FORBIDDEN, MSG18);

            assertUnchanged(moderator, null, null);
        }

        @Test
        @DisplayName("UTC-097 Nhân viên không gửi chủ đề: khu vực và giai đoạn vẫn được lưu")
        void utc097_staffWithoutTopicsSavesAreaAndStage() {
            User moderator = account(MOD_ID, Role.MODERATOR, UserStatus.ACTIVE, null, null);

            FeedPreferencesResponse answer = service.updateFeedPreferences(MOD_ID, AREA_ID, "ARR", null);

            assertThat(moderator.getAreaId()).isEqualTo(AREA_ID);
            assertThat(moderator.getStage()).isEqualTo("ARR");
            assertThat(answer.topicIds()).isEmpty();
            verify(interests, never()).update(anyLong(), any());
        }

        @Test
        @DisplayName("UTC-098 Tài khoản không còn: báo chưa đăng nhập, không lưu gì")
        void utc098_missingAccountUnauthorized() {
            assertApiError(() -> service.updateFeedPreferences(77L, AREA_ID, "ARR", List.of()),
                    HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);

            verifyNoInteractions(interests);
            verify(users, never()).flush();
            assertThat(sqlRun()).noneMatch(sql -> sql.contains("FROM areas"));
        }

        @Test
        @DisplayName("UTC-099 Tài khoản đã xóa: báo chưa đăng nhập, không lưu gì")
        void utc099_deletedAccountUnauthorized() {
            User deleted = account(30L, Role.USER, UserStatus.DELETED, null, null);

            assertApiError(() -> service.updateFeedPreferences(30L, AREA_ID, "ARR", List.of()),
                    HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);

            assertUnchanged(deleted, null, null);
            verifyNoInteractions(interests);
        }

        @Test
        @DisplayName("UTC-100 Câu trả lời không đọc dữ liệu trang Tôi (Đã lưu, hạng, tag, xu)")
        void utc100_answerReadsNothingOfProfilePage() {
            service.updateFeedPreferences(MEMBER_ID, OTHER_AREA_ID, "PRE", List.of(3L));

            verify(saved, never()).count(anyLong());
            verifyNoInteractions(settings);
            assertThat(sqlRun()).containsExactly(USERS_LOCK, AREA_CHECK, "SELECT display_name FROM areas WHERE id = ?");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. update – UC 1.2 Khu vực và giai đoạn trên trang Tôi (UTC-101 → UTC-108)")
    class UpdateProfile {

        @Test
        @DisplayName("UTC-101 Lưu khu vực và giai đoạn, trả về trang Tôi với giá trị mới")
        void utc101_savesAreaAndStageAnswersProfile() {
            ProfileResponse profile = service.update(MEMBER_ID, OTHER_AREA_ID, "LEAVE");

            assertThat(member.getAreaId()).isEqualTo(OTHER_AREA_ID);
            assertThat(member.getStage()).isEqualTo("LEAVE");
            verify(users).flush();
            assertThat(profile.id()).isEqualTo(MEMBER_ID);
            assertThat(profile.areaId()).isEqualTo(OTHER_AREA_ID);
            assertThat(profile.areaName()).isEqualTo(OTHER_AREA_NAME);
            assertThat(profile.stage()).isEqualTo("LEAVE");
        }

        @Test
        @DisplayName("UTC-102 Thứ tự: đọc tài khoản, kiểm tra khu vực, lưu, rồi đọc lại trang Tôi")
        void utc102_orderLoadCheckSaveRead() {
            service.update(MEMBER_ID, OTHER_AREA_ID, "LEAVE");

            InOrder order = inOrder(users, jdbc);
            order.verify(users).findById(MEMBER_ID);
            order.verify(jdbc).queryForObject(eq(AREA_CHECK), eq(Integer.class), eq(OTHER_AREA_ID));
            order.verify(users).flush();
            order.verify(jdbc).query(eq("SELECT name, min_points, featured_bonus FROM ranks ORDER BY min_points"),
                    any(RowMapper.class));
        }

        @ParameterizedTest(name = "[{index}] giai đoạn {0}")
        @ValueSource(strings = {"PRE", "ARR", "LEAVE", "HOME"})
        @DisplayName("UTC-103 Bốn giai đoạn đều được lưu")
        void utc103_fourStagesSaved(String stage) {
            ProfileResponse profile = service.update(MEMBER_ID, AREA_ID, stage);

            assertThat(member.getStage()).isEqualTo(stage);
            assertThat(profile.stage()).isEqualTo(stage);
        }

        @Test
        @DisplayName("UTC-104 Giai đoạn chỉ có khoảng trắng: lưu là chưa chọn")
        void utc104_blankStageMeansNone() {
            ProfileResponse profile = service.update(MEMBER_ID, AREA_ID, "   ");

            assertThat(member.getStage()).isNull();
            assertThat(profile.stage()).isNull();
        }

        @Test
        @DisplayName("UTC-105 \"Chưa chọn\" khu vực và giai đoạn: xóa cả hai")
        void utc105_clearBoth() {
            ProfileResponse profile = service.update(MEMBER_ID, null, null);

            assertThat(member.getAreaId()).isNull();
            assertThat(member.getStage()).isNull();
            assertThat(profile.areaId()).isNull();
            assertThat(profile.areaName()).isNull();
            assertThat(profile.stage()).isNull();
        }

        @Test
        @DisplayName("UTC-106 Khu vực không hợp lệ: báo lỗi, không lưu, không đọc lại trang Tôi")
        void utc106_invalidAreaRefused() {
            assertApiError(() -> service.update(MEMBER_ID, OFF_AREA_ID, "HOME"), HttpStatus.BAD_REQUEST, INVALID_AREA);

            assertUnchanged(member, AREA_ID, "ARR");
            assertThat(sqlRun()).noneMatch(sql -> sql.contains("FROM ranks"));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"MISSING", "DELETED"})
        @DisplayName("UTC-107 Tài khoản không còn hoặc đã xóa: báo chưa đăng nhập")
        void utc107_missingOrDeletedUnauthorized(String which) {
            long id = 30L;
            if (which.equals("DELETED")) {
                account(id, Role.USER, UserStatus.DELETED, null, null);
            }

            assertApiError(() -> service.update(id, AREA_ID, "ARR"), HttpStatus.UNAUTHORIZED, NOT_SIGNED_IN);

            verify(users, never()).flush();
        }

        @Test
        @DisplayName("UTC-108 Lưu ở trang Tôi không động tới chủ đề quan tâm")
        void utc108_doesNotTouchInterests() {
            service.update(MEMBER_ID, OTHER_AREA_ID, "PRE");

            verifyNoInteractions(interests);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. UpdateProfileRequest / UpdateFeedPreferencesRequest – UC 1.2 Kiểm tra giai đoạn (UTC-109 → UTC-111)")
    class RequestValidation {

        @ParameterizedTest(name = "[{index}] stage = {0}")
        @NullSource
        @ValueSource(strings = {"PRE", "ARR", "LEAVE", "HOME"})
        @DisplayName("UTC-109 Bốn giai đoạn và \"Chưa chọn\" (null) hợp lệ")
        void utc109_validStages(String stage) {
            assertThat(stageMessages(new UpdateProfileRequest(AREA_ID, stage))).isEmpty();
            assertThat(stageMessages(new UpdateFeedPreferencesRequest(AREA_ID, stage, List.of(3L)))).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] stage = \"{0}\"")
        @ValueSource(strings = {"SOON", "arr", "", " ARR"})
        @DisplayName("UTC-110 Giai đoạn ngoài 4 giá trị: \"Giai đoạn không hợp lệ\"")
        void utc110_invalidStages(String stage) {
            assertThat(stageMessages(new UpdateProfileRequest(AREA_ID, stage)))
                    .containsExactly("stage: " + INVALID_STAGE);
            assertThat(stageMessages(new UpdateFeedPreferencesRequest(AREA_ID, stage, List.of())))
                    .containsExactly("stage: " + INVALID_STAGE);
        }

        @Test
        @DisplayName("UTC-111 Khu vực và danh sách chủ đề không bị kiểm tra ở bước này")
        void utc111_areaAndTopicsNotCheckedHere() {
            assertThat(stageMessages(new UpdateFeedPreferencesRequest(-5L, "ARR", Arrays.asList(1L, null)))).isEmpty();
            assertThat(stageMessages(new UpdateFeedPreferencesRequest(null, null, null))).isEmpty();
        }
    }
}
