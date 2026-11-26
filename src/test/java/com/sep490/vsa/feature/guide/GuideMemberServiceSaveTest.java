package com.sep490.vsa.feature.guide;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.guide.GuideMemberService.SaveState;
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
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.12 "Add Item to Favorites" (Thêm vào mục yêu thích) for guides ({@link GuideMemberService#save},
 * "Lưu" / "Đã lưu" on a "Cẩm nang" page), written from CLAUDE.md ("Cẩm nang progress": Members only, guest 401, staff
 * MSG18; published and not deleted guides only, else 404; guide row locked FOR UPDATE first; writes idempotent) and
 * the use case test {@code usecase/saved/AddItemToFavoritesTest} (TC-1.12-13, TC-1.12-14, TC-1.12-17).
 * <p>
 * The database is not used: {@link JdbcTemplate} and {@link GuideProgressService} are mocks. The service maps its
 * rows with its own row mappers, so the mock hands them a mocked {@link ResultSet}; the statements and parameters it
 * sends are checked on the mock.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("GuideMemberService – Lưu cẩm nang vào \"Đã lưu\" (UC 1.12)")
class GuideMemberServiceSaveTest {

    private static final long MEMBER_ID = 10L;
    private static final long GUIDE_ID = 61L;

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String MSG_NOT_SIGNED_IN = "Bạn chưa đăng nhập.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String GUIDE_GONE = "Không tìm thấy cẩm nang.";

    private JdbcTemplate jdbc;
    private GuideProgressService progress;
    private GuideMemberService service;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        progress = mock(GuideProgressService.class);
        service = new GuideMemberService(jdbc, progress);

        account(MEMBER_ID, "USER", "ACTIVE");
        doReturn(List.of(GUIDE_ID)).when(jdbc).query(contains("FROM guide_items"), any(RowMapper.class),
                eq(GUIDE_ID));
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    /** The users row of {@code id}, read through the service's own row mapper. */
    private void account(long id, String role, String status) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(role);
        when(rs.getString(2)).thenReturn(status);
        doAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)))
                .when(jdbc).query(contains("FROM users"), any(RowMapper.class), eq(id));
    }

    private void noAccount(long id) {
        doReturn(List.of()).when(jdbc).query(contains("FROM users"), any(RowMapper.class), eq(id));
    }

    private AppUserPrincipal staff(String role) throws SQLException {
        long id = switch (role) {
            case "MODERATOR" -> 20L;
            case "ACADEMIC_MANAGER" -> 30L;
            default -> 1L;
        };
        account(id, role, "ACTIVE");
        return principal(id, role);
    }

    private List<Invocation> calls() {
        return List.copyOf(mockingDetails(jdbc).getInvocations());
    }

    private static String sql(Invocation call) {
        return call.getArgument(0);
    }

    private List<Invocation> writes() {
        return calls().stream().filter(i -> i.getMethod().getName().equals("update")).toList();
    }

    private static List<Object> params(Invocation call, int from) {
        Object[] all = call.getArguments();
        return Arrays.asList(Arrays.copyOfRange(all, from, all.length));
    }

    private boolean guideRead() {
        return calls().stream().anyMatch(i -> sql(i).contains("FROM guide_items"));
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(thrown.getMessage()).isEqualTo(message);
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. save – UC 1.12 Lưu, bỏ lưu cẩm nang (UTC-046 → UTC-059)")
    class SaveGuide {

        @Test
        @DisplayName("UTC-046 Lưu cẩm nang đã đăng: nút chuyển sang \"Đã lưu\"")
        void utc046_memberSavesGuide_saved() {
            SaveState state = service.save(GUIDE_ID, MEMBER, true);

            assertThat(state.saved()).isTrue();
            assertThat(writes()).singleElement().satisfies(w -> {
                assertThat(sql(w)).contains("INSERT INTO saved_guides (user_id, guide_id) VALUES (?, ?)");
                assertThat(params(w, 1)).containsExactly(MEMBER_ID, GUIDE_ID);
            });
        }

        @Test
        @DisplayName("UTC-047 Bỏ lưu cẩm nang: nút trở về \"Lưu\"")
        void utc047_memberUnsavesGuide_notSaved() {
            SaveState state = service.save(GUIDE_ID, MEMBER, false);

            assertThat(state.saved()).isFalse();
            assertThat(writes()).singleElement().satisfies(w -> {
                assertThat(sql(w)).contains("DELETE FROM saved_guides WHERE user_id = ? AND guide_id = ?");
                assertThat(params(w, 1)).containsExactly(MEMBER_ID, GUIDE_ID);
            });
        }

        @Test
        @DisplayName("UTC-048 Chỉ cẩm nang đã đăng, chưa xóa; dòng cẩm nang được khóa")
        void utc048_guideLookup_publishedNotDeletedLocked() {
            service.save(GUIDE_ID, MEMBER, true);

            Invocation lookup = calls().stream().filter(i -> sql(i).contains("FROM guide_items")).findFirst()
                    .orElseThrow();
            assertThat(sql(lookup)).contains("published = TRUE").contains("deleted_at IS NULL").endsWith("FOR UPDATE");
            assertThat(params(lookup, 2)).containsExactly(GUIDE_ID);
        }

        @Test
        @DisplayName("UTC-049 Thứ tự: kiểm tra tài khoản, khóa cẩm nang, rồi mới ghi")
        void utc049_accountThenGuideLockThenWrite() {
            service.save(GUIDE_ID, MEMBER, true);

            assertThat(calls()).extracting(i -> {
                String s = sql(i);
                return s.contains("FROM users") ? "account" : s.contains("FROM guide_items") ? "lockGuide" : "write";
            }).containsExactly("account", "lockGuide", "write");
        }

        @Test
        @DisplayName("UTC-050 Bấm \"Lưu\" hai lần: giữ một mục với thời điểm lưu đầu tiên")
        void utc050_saveTwice_oneRowFirstTimeKept() {
            assertThat(service.save(GUIDE_ID, MEMBER, true).saved()).isTrue();
            assertThat(service.save(GUIDE_ID, MEMBER, true).saved()).isTrue();

            assertThat(writes()).hasSize(2).allSatisfy(w -> assertThat(sql(w))
                    .contains("ON DUPLICATE KEY UPDATE created_at = created_at"));
        }

        @Test
        @DisplayName("UTC-051 Bỏ lưu cẩm nang chưa từng lưu: không báo lỗi")
        void utc051_unsaveNeverSaved_noError() {
            when(jdbc.update(contains("DELETE FROM saved_guides"), eq(MEMBER_ID), eq(GUIDE_ID))).thenReturn(0);

            SaveState state = service.save(GUIDE_ID, MEMBER, false);

            assertThat(state.saved()).isFalse();
            assertThat(writes()).hasSize(1);
        }

        @Test
        @DisplayName("UTC-052 Cẩm nang chưa đăng, đã xóa hoặc không tồn tại: không tìm thấy, không lưu")
        void utc052_guideNotShown_404() {
            assertApiError(() -> service.save(999L, MEMBER, true), HttpStatus.NOT_FOUND, GUIDE_GONE);
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-053 Bỏ lưu cẩm nang đã bị xóa: không tìm thấy, mục lưu được giữ")
        void utc053_unsaveDeletedGuide_404() {
            assertApiError(() -> service.save(999L, MEMBER, false), HttpStatus.NOT_FOUND, GUIDE_GONE);
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-054 Khách bấm \"Lưu\": phải đăng nhập")
        void utc054_guestSaves_401() {
            assertApiError(() -> service.save(GUIDE_ID, null, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(jdbc);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-055 Tài khoản nhân viên bấm \"Lưu\": bị từ chối")
        void utc055_staffSaves_403(String role) throws SQLException {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.save(GUIDE_ID, staff, true), HttpStatus.FORBIDDEN, MSG18);
            assertThat(guideRead()).isFalse();
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-056 Tài khoản bị khóa bấm \"Lưu\": bị từ chối")
        void utc056_lockedMemberSaves_403() throws SQLException {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.save(GUIDE_ID, MEMBER, true), HttpStatus.FORBIDDEN, MSG18);
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-057 Tài khoản đã xóa bấm \"Lưu\": bị từ chối")
        void utc057_deletedMemberSaves_403() throws SQLException {
            account(MEMBER_ID, "USER", "DELETED");

            assertApiError(() -> service.save(GUIDE_ID, MEMBER, true), HttpStatus.FORBIDDEN, MSG18);
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-058 Tài khoản không còn trong hệ thống: phải đăng nhập")
        void utc058_accountGone_401() {
            noAccount(MEMBER_ID);

            assertApiError(() -> service.save(GUIDE_ID, MEMBER, true), HttpStatus.UNAUTHORIZED, MSG_NOT_SIGNED_IN);
            assertThat(guideRead()).isFalse();
        }

        @Test
        @DisplayName("UTC-059 Lưu cẩm nang không thay đổi tiến độ cẩm nang")
        void utc059_saveDoesNotTouchProgress() {
            service.save(GUIDE_ID, MEMBER, true);
            service.save(GUIDE_ID, MEMBER, false);

            verifyNoInteractions(progress);
            assertThat(writes()).noneMatch(w -> sql(w).contains("user_guide_progress"));
        }
    }
}
