package com.sep490.vsa.feature.notification;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.exception.ApiExceptionHandler;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationItem;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationPage;
import com.sep490.vsa.feature.notification.NotificationDtos.NotificationSummary;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests of the HTTP side of the bell and the "Thông báo" page ({@link NotificationController},
 * {@code /api/me/notifications}): how the query parameters and the body reach {@link NotificationService} and what
 * the JSON answer looks like.
 * <p>
 * No Spring context and no database: a standalone MockMvc around the controller with a mocked
 * {@link NotificationService}, the project's {@link ApiExceptionHandler} and the {@code @AuthenticationPrincipal}
 * resolver; the signed-in user is put in the {@link SecurityContextHolder}. The 401 for guests and the CSRF check come
 * from SecurityConfig ({@code /api/me/**} needs a login) and are covered by {@code ScanNotificationFlowTest}.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("NotificationController – API chuông thông báo /api/me/notifications")
class NotificationControllerTest {

    private static final long USER_ID = 10L;
    private static final String API = "/api/me/notifications";
    private static final Instant AT = Instant.parse("2026-11-20T03:00:00Z");

    private NotificationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        signIn(USER_ID, "USER");
        when(service.list(anyLong(), anyInt(), anyBoolean())).thenReturn(new NotificationPage(List.of(), false, 0));
        when(service.summary(anyLong(), any(), any())).thenReturn(new NotificationSummary(0, 0, 0, List.of(), 0));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(long id, String role) {
        AppUserPrincipal me = new AppUserPrincipal(id, "user" + id + "@example.org", "User " + id, role, "/");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(me, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. GET /api/me/notifications – Danh sách thông báo (UTC-066 → UTC-072)")
    class ListEndpoint {

        @Test
        @DisplayName("UTC-066 Không tham số: trang 0, tất cả; trả JSON success, \"OK\", items, hasMore, unread")
        void utc066_ctrlList_defaultsToFirstPageAll() throws Exception {
            when(service.list(USER_ID, 0, false)).thenReturn(new NotificationPage(List.of(new NotificationItem(77,
                    "NEW_COMMENT", "Lan đã bình luận bài viết “Thuê nhà”.", "/blog/12", false, AT)), true, 23));

            mvc.perform(get(API))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("OK"))
                    .andExpect(jsonPath("$.data.items[0].id").value(77))
                    .andExpect(jsonPath("$.data.items[0].type").value("NEW_COMMENT"))
                    .andExpect(jsonPath("$.data.items[0].link").value("/blog/12"))
                    .andExpect(jsonPath("$.data.items[0].read").value(false))
                    .andExpect(jsonPath("$.data.hasMore").value(true))
                    .andExpect(jsonPath("$.data.unread").value(23));
            verify(service).list(USER_ID, 0, false);
        }

        @Test
        @DisplayName("UTC-067 page=3&unread=true: chuyển đúng trang và tab \"Chưa đọc\"")
        void utc067_ctrlList_passesPageAndUnreadTab() throws Exception {
            mvc.perform(get(API).param("page", "3").param("unread", "true")).andExpect(status().isOk());

            verify(service).list(USER_ID, 3, true);
        }

        @Test
        @DisplayName("UTC-068 page=-3: chuyển nguyên cho service (service giữ trong 0–199)")
        void utc068_ctrlList_passesNegativePageToService() throws Exception {
            mvc.perform(get(API).param("page", "-3")).andExpect(status().isOk());

            verify(service).list(USER_ID, -3, false);
        }

        @Test
        @DisplayName("UTC-069 Thông báo về nội dung đã bị xóa: link = null trong JSON")
        void utc069_ctrlList_writesNullLink() throws Exception {
            when(service.list(USER_ID, 0, false)).thenReturn(new NotificationPage(List.of(new NotificationItem(5,
                    "CONTENT_PUBLISHED", "Bài viết “T” đã được đăng.", null, true, AT)), false, 0));

            mvc.perform(get(API))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].link").value(nullValue()))
                    .andExpect(jsonPath("$.data.items[0].read").value(true));
        }

        @ParameterizedTest(name = "[{index}] page = {0}")
        @ValueSource(strings = {"abc", "1.5", "99999999999"})
        @DisplayName("UTC-070 page không phải số nguyên: 400, không gọi service")
        void utc070_ctrlList_pageNotAnIntegerIs400(String page) throws Exception {
            mvc.perform(get(API).param("page", page)).andExpect(status().isBadRequest());

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("UTC-071 unread không phải true/false: 400, không gọi service")
        void utc071_ctrlList_unreadNotABooleanIs400() throws Exception {
            mvc.perform(get(API).param("unread", "maybe")).andExpect(status().isBadRequest());

            verifyNoInteractions(service);
        }

        @ParameterizedTest(name = "[{index}] vai trò {0}")
        @ValueSource(strings = {"MODERATOR", "ACADEMIC_MANAGER", "ADMIN"})
        @DisplayName("UTC-072 Nhân viên cũng xem được thông báo của chính mình")
        void utc072_ctrlList_staffReadTheirOwn(String role) throws Exception {
            signIn(20L, role);

            mvc.perform(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

            verify(service).list(20L, 0, false);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. GET /api/me/notifications/summary – Chuông thông báo (UTC-073 → UTC-075)")
    class SummaryEndpoint {

        @Test
        @DisplayName("UTC-073 Không có after, seen: gọi service với null; trả đủ các số của chuông")
        void utc073_ctrlSummary_withoutParameters() throws Exception {
            when(service.summary(USER_ID, null, null)).thenReturn(new NotificationSummary(4, 120, 1, List.of(), 4));

            mvc.perform(get(API + "/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unread").value(4))
                    .andExpect(jsonPath("$.data.latestId").value(120))
                    .andExpect(jsonPath("$.data.processing").value(1))
                    .andExpect(jsonPath("$.data.fresh").isEmpty())
                    .andExpect(jsonPath("$.data.unseen").value(4));
            verify(service).summary(USER_ID, null, null);
        }

        @Test
        @DisplayName("UTC-074 after=100&seen=95: chuyển đúng hai id")
        void utc074_ctrlSummary_passesAfterAndSeen() throws Exception {
            mvc.perform(get(API + "/summary").param("after", "100").param("seen", "95")).andExpect(status().isOk());

            verify(service).summary(USER_ID, 100L, 95L);
        }

        @Test
        @DisplayName("UTC-075 after không phải số: 400, không gọi service")
        void utc075_ctrlSummary_afterNotANumberIs400() throws Exception {
            mvc.perform(get(API + "/summary").param("after", "x1")).andExpect(status().isBadRequest());

            verifyNoInteractions(service);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. POST /api/me/notifications/read – Đánh dấu tất cả là đã đọc (UTC-076 → UTC-079)")
    class ReadAllEndpoint {

        @Test
        @DisplayName("UTC-076 Không có body: đánh dấu tất cả, trả {unread}")
        void utc076_ctrlRead_withoutBodyMarksAll() throws Exception {
            when(service.markRead(USER_ID, null)).thenReturn(0);

            mvc.perform(post(API + "/read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("OK"))
                    .andExpect(jsonPath("$.data.unread").value(0));
            verify(service).markRead(USER_ID, null);
        }

        @Test
        @DisplayName("UTC-077 Body {} (không có upToId): đánh dấu tất cả")
        void utc077_ctrlRead_emptyObjectMarksAll() throws Exception {
            mvc.perform(post(API + "/read").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());

            verify(service).markRead(USER_ID, null);
        }

        @Test
        @DisplayName("UTC-078 Body {upToId: 150}: đánh dấu tới id 150, trả số còn lại")
        void utc078_ctrlRead_upToIdMarksUpToIt() throws Exception {
            when(service.markRead(USER_ID, 150L)).thenReturn(2);

            mvc.perform(post(API + "/read").contentType(MediaType.APPLICATION_JSON).content("{\"upToId\":150}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unread").value(2));
            verify(service).markRead(USER_ID, 150L);
        }

        @Test
        @DisplayName("UTC-079 Body không phải JSON hợp lệ: 400, không đánh dấu gì")
        void utc079_ctrlRead_malformedBodyIs400() throws Exception {
            mvc.perform(post(API + "/read").contentType(MediaType.APPLICATION_JSON).content("{upToId:"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Dữ liệu gửi lên không đúng định dạng"));

            verifyNoInteractions(service);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. POST /api/me/notifications/{id}/read – Bấm vào một thông báo (UTC-080 → UTC-082)")
    class ReadOneEndpoint {

        @Test
        @DisplayName("UTC-080 Thông báo của mình: đánh dấu đã đọc, trả {unread}")
        void utc080_ctrlReadOne_marksIt() throws Exception {
            when(service.markOneRead(USER_ID, 77L)).thenReturn(3);

            mvc.perform(post(API + "/77/read"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.unread").value(3));
            verify(service).markOneRead(USER_ID, 77L);
        }

        @Test
        @DisplayName("UTC-081 Thông báo của người khác / không có: 404 với thông báo lỗi")
        void utc081_ctrlReadOne_notFoundIs404() throws Exception {
            when(service.markOneRead(USER_ID, 88L))
                    .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo này."));

            mvc.perform(post(API + "/88/read"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Không tìm thấy thông báo này."));
        }

        @Test
        @DisplayName("UTC-082 id không phải số: 400, không gọi service")
        void utc082_ctrlReadOne_idNotANumberIs400() throws Exception {
            mvc.perform(post(API + "/abc/read")).andExpect(status().isBadRequest());

            verifyNoInteractions(service);
        }
    }
}
