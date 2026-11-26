package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.moderation.ContentFilter;
import com.sep490.vsa.feature.place.MapDtos.SaveState;
import com.sep490.vsa.feature.place.MapRepository.Member;
import com.sep490.vsa.feature.place.MapRepository.PlaceRow;
import com.sep490.vsa.feature.report.ContentReportService;
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
import org.mockito.InOrder;
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of UC 1.12 "Add Item to Favorites" (Thêm vào mục yêu thích) on the Map ({@link MapService#save},
 * "Lưu" / "Đã lưu" on a place's details panel), written from CLAUDE.md ("Map page": a merged place opens as the place
 * it was merged into; removed, rejected and other people's pending places are "not on Senpai ơi any more"; the author
 * of a pending pin may save it; saving is for signed-in Members only) and the use case test
 * {@code usecase/saved/AddItemToFavoritesTest} (TC-1.12-09 → TC-1.12-12). The last group checks the statements of
 * {@link MapRepository#setSaved} that keep a place saved once.
 * <p>
 * The database is not used: {@link MapRepository} (and, for its own group, {@link JdbcTemplate}),
 * {@link PlaceRepository}, {@link PlaceService}, {@link ContentReportService}, {@link SystemSettings} and
 * {@link ContentFilter} are mocks; the clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("MapService – Lưu địa điểm trên Bản đồ vào \"Đã lưu\" (UC 1.12)")
class MapServiceSaveTest {

    private static final long MEMBER_ID = 10L;
    private static final long PIN_AUTHOR_ID = 15L;
    private static final long TEAM_ID = 2L;
    private static final long PLACE_ID = 400L;
    private static final long KEPT_ID = 401L;
    private static final long MERGED_ID = 402L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    private static final AppUserPrincipal MEMBER = principal(MEMBER_ID, "USER");

    private static final String MSG_LOGIN = "Bạn cần đăng nhập để thực hiện thao tác này.";
    private static final String MSG18 = "Bạn không có quyền thực hiện thao tác này.";
    private static final String NOT_ON_MAP = "Địa điểm này không còn trên Senpai ơi.";

    private MapRepository repository;
    private PlaceRepository places;
    private PlaceService placeService;
    private ContentReportService reports;
    private ContentFilter filter;
    private MapService service;

    @BeforeEach
    void setUp() {
        repository = mock(MapRepository.class);
        places = mock(PlaceRepository.class);
        placeService = mock(PlaceService.class);
        reports = mock(ContentReportService.class);
        filter = mock(ContentFilter.class);
        service = new MapService(repository, places, placeService, reports, mock(SystemSettings.class),
                Clock.fixed(NOW, ZoneOffset.UTC), filter, "/media/", "");

        account(MEMBER_ID, "USER", "ACTIVE");
        place(PLACE_ID, "ACTIVE", null, TEAM_ID);
    }

    // ---------------------------------------------------------------- helpers

    private static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
    }

    private void account(long id, String role, String status) {
        when(repository.member(id)).thenReturn(Optional.of(new Member(role, status)));
    }

    private void place(long id, String status, Long mergedIntoId, long createdBy) {
        place(id, status, false, mergedIntoId, createdBy);
    }

    private void place(long id, String status, boolean closed, Long mergedIntoId, long createdBy) {
        when(repository.place(id)).thenReturn(Optional.of(new PlaceRow(id, "Quán " + id, "店" + id, "Shop " + id,
                "Taito, Tokyo", null, 35.7148, 139.7967, "restaurant", "Quán ăn", "restaurant", "TEAM", status,
                closed, mergedIntoId, createdBy)));
    }

    private AppUserPrincipal staff(String role) {
        long id = switch (role) {
            case "MODERATOR" -> 20L;
            case "ACADEMIC_MANAGER" -> 30L;
            default -> 1L;
        };
        account(id, role, "ACTIVE");
        return principal(id, role);
    }

    private static void assertApiError(Runnable call, HttpStatus status, String message) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).getStatus()).isEqualTo(status);
        assertThat(thrown.getMessage()).isEqualTo(message);
    }

    private void verifyNothingSaved() {
        verify(repository, never()).setSaved(anyLong(), anyLong(), anyBoolean(), any());
    }

    /** A chain of {@code merges} merged places starting at id 500, ending at an ACTIVE place; returns the end id. */
    private long mergeChain(int merges) {
        long id = 500L;
        for (int i = 0; i < merges; i++) {
            place(id, "MERGED", id + 1, TEAM_ID);
            id++;
        }
        place(id, "ACTIVE", null, TEAM_ID);
        return id;
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. save – UC 1.12 Lưu, bỏ lưu địa điểm (UTC-023 → UTC-043)")
    class SavePlace {

        @Test
        @DisplayName("UTC-023 Lưu địa điểm đang có trên bản đồ: nút chuyển sang \"Đã lưu\"")
        void utc023_memberSavesActivePlace_saved() {
            SaveState state = service.save(MEMBER, PLACE_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).setSaved(PLACE_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-024 Bỏ lưu địa điểm")
        void utc024_memberUnsavesPlace_notSaved() {
            SaveState state = service.save(MEMBER, PLACE_ID, false);

            assertThat(state.saved()).isFalse();
            verify(repository).setSaved(PLACE_ID, MEMBER_ID, false, NOW);
        }

        @Test
        @DisplayName("UTC-025 Địa điểm mang nhãn \"Đã đóng cửa\": vẫn lưu được")
        void utc025_closedPlace_stillSaved() {
            place(PLACE_ID, "ACTIVE", true, null, TEAM_ID);

            SaveState state = service.save(MEMBER, PLACE_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).setSaved(PLACE_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-026 Lưu địa điểm đã được gộp: lưu vào địa điểm giữ lại")
        void utc026_mergedPlace_savedIntoKeptPlace() {
            place(MERGED_ID, "MERGED", KEPT_ID, TEAM_ID);
            place(KEPT_ID, "ACTIVE", null, TEAM_ID);

            SaveState state = service.save(MEMBER, MERGED_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).setSaved(KEPT_ID, MEMBER_ID, true, NOW);
            verify(repository, never()).setSaved(eq(MERGED_ID), anyLong(), anyBoolean(), any());
        }

        @Test
        @DisplayName("UTC-027 Địa điểm gộp hai lần liên tiếp: lưu vào địa điểm cuối cùng còn giữ")
        void utc027_twoMerges_savedIntoLastPlace() {
            long end = mergeChain(2);

            service.save(MEMBER, 500L, true);

            verify(repository).setSaved(end, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-028 Chuỗi gộp đúng 5 bước: vẫn lưu vào địa điểm cuối cùng")
        void utc028_fiveMerges_savedIntoLastPlace() {
            long end = mergeChain(5);

            SaveState state = service.save(MEMBER, 500L, true);

            assertThat(state.saved()).isTrue();
            assertThat(end).isEqualTo(505L);
            verify(repository).setSaved(505L, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-029 Chuỗi gộp 6 bước: dừng sau 5 bước, báo không còn trên Senpai ơi")
        void utc029_sixMerges_stopsAfterFiveHops_404() {
            mergeChain(6);

            assertApiError(() -> service.save(MEMBER, 500L, true), HttpStatus.NOT_FOUND, NOT_ON_MAP);
            verify(repository, never()).place(506L);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-030 Địa điểm gộp không rõ nơi giữ lại: không còn trên Senpai ơi")
        void utc030_mergedWithoutTarget_404() {
            place(MERGED_ID, "MERGED", null, TEAM_ID);

            assertApiError(() -> service.save(MEMBER, MERGED_ID, true), HttpStatus.NOT_FOUND, NOT_ON_MAP);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-031 Địa điểm gộp vào nơi đã bị gỡ: không còn trên Senpai ơi")
        void utc031_mergedIntoRemovedPlace_404() {
            place(MERGED_ID, "MERGED", KEPT_ID, TEAM_ID);
            place(KEPT_ID, "REMOVED", null, TEAM_ID);

            assertApiError(() -> service.save(MEMBER, MERGED_ID, true), HttpStatus.NOT_FOUND, NOT_ON_MAP);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-032 Người cắm ghim lưu ghim của mình đang chờ duyệt: được phép")
        void utc032_ownPendingPin_saved() {
            AppUserPrincipal pinAuthor = principal(PIN_AUTHOR_ID, "USER");
            account(PIN_AUTHOR_ID, "USER", "ACTIVE");
            place(PLACE_ID, "PENDING", null, PIN_AUTHOR_ID);

            SaveState state = service.save(pinAuthor, PLACE_ID, true);

            assertThat(state.saved()).isTrue();
            verify(repository).setSaved(PLACE_ID, PIN_AUTHOR_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-033 Lưu ghim chờ duyệt của người khác: không còn trên Senpai ơi")
        void utc033_othersPendingPin_404() {
            place(PLACE_ID, "PENDING", null, PIN_AUTHOR_ID);

            assertApiError(() -> service.save(MEMBER, PLACE_ID, true), HttpStatus.NOT_FOUND, NOT_ON_MAP);
            verifyNothingSaved();
        }

        @ParameterizedTest(name = "[{index}] trạng thái {0}")
        @ValueSource(strings = {"REMOVED", "REJECTED"})
        @DisplayName("UTC-034 Địa điểm đã bị gỡ hoặc ghim bị từ chối: không còn trên Senpai ơi")
        void utc034_removedOrRejectedPlace_404(String status) {
            place(PLACE_ID, status, null, MEMBER_ID);

            assertApiError(() -> service.save(MEMBER, PLACE_ID, true), HttpStatus.NOT_FOUND, NOT_ON_MAP);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-035 Địa điểm không tồn tại: không còn trên Senpai ơi")
        void utc035_missingPlace_404() {
            when(repository.place(999_999L)).thenReturn(Optional.empty());

            assertApiError(() -> service.save(MEMBER, 999_999L, true), HttpStatus.NOT_FOUND, NOT_ON_MAP);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-036 Bỏ lưu địa điểm đã bị gỡ: không còn trên Senpai ơi, mục lưu được giữ")
        void utc036_unsaveRemovedPlace_404() {
            place(PLACE_ID, "REMOVED", null, TEAM_ID);

            assertApiError(() -> service.save(MEMBER, PLACE_ID, false), HttpStatus.NOT_FOUND, NOT_ON_MAP);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-037 Bỏ lưu địa điểm chưa từng lưu: không báo lỗi")
        void utc037_unsaveNeverSaved_noError() {
            SaveState state = service.save(MEMBER, PLACE_ID, false);

            assertThat(state.saved()).isFalse();
            verify(repository).setSaved(PLACE_ID, MEMBER_ID, false, NOW);
        }

        @Test
        @DisplayName("UTC-038 Bấm \"Lưu\" hai lần: cả hai lần đều báo đã lưu, không lỗi")
        void utc038_saveTwice_bothSaved() {
            assertThat(service.save(MEMBER, PLACE_ID, true).saved()).isTrue();
            assertThat(service.save(MEMBER, PLACE_ID, true).saved()).isTrue();

            verify(repository, times(2)).setSaved(PLACE_ID, MEMBER_ID, true, NOW);
        }

        @Test
        @DisplayName("UTC-039 Khách bấm \"Lưu\": phải đăng nhập")
        void utc039_guestSaves_401() {
            assertApiError(() -> service.save(null, PLACE_ID, true), HttpStatus.UNAUTHORIZED, MSG_LOGIN);
            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-040 Tài khoản nhân viên bấm \"Lưu\": bị từ chối")
        void utc040_staffSaves_403(String role) {
            AppUserPrincipal staff = staff(role);

            assertApiError(() -> service.save(staff, PLACE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verify(repository, never()).place(anyLong());
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-041 Tài khoản bị khóa bấm \"Lưu\": bị từ chối")
        void utc041_lockedMemberSaves_403() {
            account(MEMBER_ID, "USER", "LOCKED");

            assertApiError(() -> service.save(MEMBER, PLACE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-042 Tài khoản đã xóa hoặc không còn: bị từ chối")
        void utc042_deletedOrMissingAccount_403() {
            when(repository.member(MEMBER_ID)).thenReturn(Optional.empty());

            assertApiError(() -> service.save(MEMBER, PLACE_ID, true), HttpStatus.FORBIDDEN, MSG18);
            verifyNothingSaved();
        }

        @Test
        @DisplayName("UTC-043 Kiểm tra quyền trước khi đọc địa điểm; không gọi dịch vụ bản đồ, báo cáo")
        void utc043_memberCheckedFirst_noOtherServices() {
            service.save(MEMBER, PLACE_ID, true);

            InOrder order = inOrder(repository);
            order.verify(repository).member(MEMBER_ID);
            order.verify(repository).place(PLACE_ID);
            order.verify(repository).setSaved(PLACE_ID, MEMBER_ID, true, NOW);
            verifyNoInteractions(places, placeService, reports, filter);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. MapRepository.setSaved – Ghi địa điểm đã lưu (UTC-044 → UTC-045)")
    class SetSavedStatements {

        private JdbcTemplate jdbc;
        private MapRepository store;

        @BeforeEach
        void setUpStore() {
            jdbc = mock(JdbcTemplate.class);
            store = new MapRepository(jdbc, mock(NamedParameterJdbcTemplate.class));
        }

        private Invocation onlyStatement() {
            List<Invocation> calls = List.copyOf(mockingDetails(jdbc).getInvocations());
            assertThat(calls).hasSize(1);
            return calls.get(0);
        }

        @Test
        @DisplayName("UTC-044 Lưu: thêm một dòng, lưu lần nữa thì bỏ qua (giữ một mục)")
        void utc044_saveOn_insertIgnore() {
            store.setSaved(PLACE_ID, MEMBER_ID, true, NOW);

            Invocation call = onlyStatement();
            assertThat((String) call.getArgument(0))
                    .contains("INSERT IGNORE INTO saved_places (user_id, place_id, saved_at)");
            assertThat(Arrays.copyOfRange(call.getArguments(), 1, 4))
                    .containsExactly(MEMBER_ID, PLACE_ID, Timestamp.from(NOW));
        }

        @Test
        @DisplayName("UTC-045 Bỏ lưu: xóa dòng của đúng thành viên và địa điểm")
        void utc045_saveOff_deleteRow() {
            store.setSaved(PLACE_ID, MEMBER_ID, false, NOW);

            Invocation call = onlyStatement();
            assertThat((String) call.getArgument(0))
                    .contains("DELETE FROM saved_places WHERE user_id = ? AND place_id = ?");
            assertThat(Arrays.copyOfRange(call.getArguments(), 1, 3)).containsExactly(MEMBER_ID, PLACE_ID);
        }
    }
}
