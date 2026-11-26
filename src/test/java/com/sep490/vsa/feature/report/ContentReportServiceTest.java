package com.sep490.vsa.feature.report;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.settings.SystemSettings;
import com.sep490.vsa.feature.report.ContentReportService.Outcome;
import com.sep490.vsa.feature.report.ContentReportService.Reason;
import com.sep490.vsa.feature.report.ContentReportService.Target;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of {@link ContentReportService}, the shared step of UC 1.11 "Submit Content Report" (Gửi báo cáo nội
 * dung) that every report goes through (posts, questions, comments, answers, reviews, messages, phrases), written from
 * CLAUDE.md ("Key business rules": one report per member per item, 1–5 reports → "Cần xử lý" (NORMAL), more than
 * {@code report_hide_threshold} (5) → "Khẩn" (URGENT) and hidden, reporting locked after too many false reports;
 * "Shared helpers") and the use case test {@code usecase/report/SubmitContentReportTest}.
 * <p>
 * The database is not used: {@link JdbcTemplate} and {@link SystemSettings} are mocks; the SQL statements the service
 * sends and their parameters are checked on the mock. The clock is fixed at 20/11/2026 12:00 Japan time.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ContentReportService – Ghi nhận báo cáo nội dung, hồ sơ báo cáo (UC 1.11)")
class ContentReportServiceTest {

    private static final long REPORTER_ID = 10L;
    private static final long AUTHOR_ID = 11L;
    private static final long POST_ID = 300L;
    private static final long CASE_ID = 77L;
    /** 12:00 in Japan, 20/11/2026. */
    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final Target POST = new Target("BLOG", POST_ID, AUTHOR_ID);

    private static final String OWN_CONTENT = "Bạn không thể báo cáo nội dung của chính mình.";
    private static final String MSG39 = "Bạn đã báo cáo nội dung này rồi.";
    private static final String MSG40 = "Hãy chọn lý do báo cáo.";
    private static final String NOTE_TOO_LONG = "Ghi chú tối đa 500 ký tự.";

    private JdbcTemplate jdbc;
    private SystemSettings settings;
    private ContentReportService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        settings = mock(SystemSettings.class);
        service = new ContentReportService(jdbc, settings, Clock.fixed(NOW, ZoneOffset.UTC));

        reasons("REPORT", new Reason(1, "Lừa đảo"), new Reason(2, "Sai sự thật"), new Reason(3, "Xúc phạm"),
                new Reason(4, "Spam"));
        reasons("ACADEMIC_REPORT", new Reason(21, "Sai tiếng Nhật"), new Reason(22, "Sai nghĩa"),
                new Reason(23, "Xúc phạm"), new Reason(24, "Spam"));
        mutedUntil(null);
        when(jdbc.queryForObject(contains("FROM reports r"), eq(Long.class), any(), any(), any())).thenReturn(0L);
        when(jdbc.queryForObject(contains("SELECT id FROM report_cases"), eq(Long.class), any(), any()))
                .thenReturn(CASE_ID);
        reportCount(1);
        when(settings.getInt(SystemSettings.REPORT_HIDE_THRESHOLD, 5)).thenReturn(5);
    }

    // ---------------------------------------------------------------- helpers

    private void reasons(String scope, Reason... offered) {
        doReturn(List.of(offered)).when(jdbc).query(contains("FROM reason_options"), any(RowMapper.class), eq(scope));
    }

    private void mutedUntil(Instant until) {
        when(jdbc.queryForObject(contains("report_muted_until"), eq(Timestamp.class), anyLong()))
                .thenReturn(until == null ? null : Timestamp.from(until));
    }

    private void reportCount(int count) {
        when(jdbc.queryForObject(contains("SELECT report_count"), eq(Integer.class), any())).thenReturn(count);
    }

    private void openCaseExists() {
        doReturn(List.of(CASE_ID)).when(jdbc).query(contains("FOR UPDATE"), any(RowMapper.class), any(), any());
    }

    private void alreadyReported() {
        when(jdbc.queryForObject(contains("FROM reports r"), eq(Long.class), any(), any(), any())).thenReturn(1L);
    }

    private List<Invocation> jdbcCalls() {
        return List.copyOf(mockingDetails(jdbc).getInvocations());
    }

    /** SQL of every UPDATE / INSERT the service sent, in order. */
    private List<String> writes() {
        return jdbcCalls().stream().filter(i -> i.getMethod().getName().equals("update"))
                .map(i -> (String) i.getArgument(0)).toList();
    }

    /** Parameters of the first write whose SQL contains {@code sqlPart}. */
    private List<Object> writeArgs(String sqlPart) {
        Invocation call = jdbcCalls().stream()
                .filter(i -> i.getMethod().getName().equals("update") && ((String) i.getArgument(0)).contains(sqlPart))
                .findFirst().orElseThrow(() -> new AssertionError("No statement containing " + sqlPart));
        Object[] all = call.getArguments();
        return Arrays.asList(Arrays.copyOfRange(all, 1, all.length));
    }

    private boolean wrote(String sqlPart) {
        return writes().stream().anyMatch(sql -> sql.contains(sqlPart));
    }

    /** A short name for each statement, to check the order they are sent in. */
    private List<String> statementOrder() {
        return jdbcCalls().stream().map(i -> {
            String sql = (String) i.getArgument(0);
            if (sql.contains("reason_options")) {
                return "reasons";
            }
            if (sql.contains("report_muted_until")) {
                return "muteCheck";
            }
            if (sql.contains("FROM reports r")) {
                return "alreadyReported";
            }
            if (sql.contains("FOR UPDATE")) {
                return "lockOpenCase";
            }
            if (sql.contains("INSERT INTO report_cases")) {
                return "createCase";
            }
            if (sql.contains("SELECT id FROM report_cases")) {
                return "readCaseId";
            }
            if (sql.contains("INSERT INTO reports")) {
                return "insertReport";
            }
            if (sql.contains("report_count = report_count + 1")) {
                return "countUp";
            }
            if (sql.contains("SELECT report_count")) {
                return "readCount";
            }
            if (sql.contains("URGENT")) {
                return "urgent";
            }
            return sql;
        }).toList();
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

    private static ResultSet reasonRow(long id, String label) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(id);
        when(rs.getString(2)).thenReturn(label);
        return rs;
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. reasons, academicReasons – Lý do trong hộp thoại báo cáo (UTC-049 → UTC-051)")
    class Reasons {

        @Test
        @DisplayName("UTC-049 Lý do báo cáo nội dung: lý do đang dùng, theo thứ tự sắp xếp")
        void utc049_reasons_activeReportReasonsInOrder() throws Exception {
            ResultSet first = reasonRow(1, "Lừa đảo");
            ResultSet second = reasonRow(4, "Spam");
            doAnswer(inv -> {
                RowMapper<?> mapper = inv.getArgument(1);
                return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
            }).when(jdbc).query(contains("FROM reason_options"), any(RowMapper.class), eq("REPORT"));

            List<Reason> reasons = service.reasons();

            assertThat(reasons).containsExactly(new Reason(1, "Lừa đảo"), new Reason(4, "Spam"));
            Invocation call = jdbcCalls().get(0);
            assertThat((String) call.getArgument(0)).contains("scope = ?").contains("is_active")
                    .contains("ORDER BY sort_order, id");
            assertThat(List.of(call.getArguments())).last().isEqualTo("REPORT");
        }

        @Test
        @DisplayName("UTC-050 Lý do báo cáo mẫu câu: nhóm lý do riêng của Nhật ngữ")
        void utc050_academicReasons_phraseReasons() {
            List<Reason> reasons = service.academicReasons();

            assertThat(reasons).extracting(Reason::label)
                    .containsExactly("Sai tiếng Nhật", "Sai nghĩa", "Xúc phạm", "Spam");
            assertThat(List.of(jdbcCalls().get(0).getArguments())).last().isEqualTo("ACADEMIC_REPORT");
        }

        @Test
        @DisplayName("UTC-051 Không còn lý do nào đang dùng: danh sách rỗng")
        void utc051_noActiveReason_emptyList() {
            reasons("REPORT");

            assertThat(service.reasons()).isEmpty();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. report – UC 1.11 Ghi nhận báo cáo, mở hồ sơ, ngưỡng ẩn (UTC-052 → UTC-069)")
    class ReportAccepted {

        @Test
        @DisplayName("UTC-052 Báo cáo đầu tiên của bài viết: mở hồ sơ \"Cần xử lý\" cho Moderator, lưu báo cáo")
        void utc052_firstReport_caseCreatedAndReportSaved() {
            Outcome outcome = service.report(POST, REPORTER_ID, 1L, "Đòi chuyển cọc trước khi xem nhà");

            assertThat(outcome).isEqualTo(new Outcome(1, false));
            assertThat(writeArgs("INSERT INTO report_cases"))
                    .containsExactly("BLOG", POST_ID, "MODERATOR", Timestamp.from(NOW));
            assertThat(writeArgs("INSERT INTO reports"))
                    .containsExactly(CASE_ID, REPORTER_ID, 1L, "Đòi chuyển cọc trước khi xem nhà", Timestamp.from(NOW));
            assertThat(writeArgs("report_count = report_count + 1")).containsExactly(CASE_ID);
            assertThat(wrote("URGENT")).isFalse();
        }

        @Test
        @DisplayName("UTC-053 Thứ tự các bước: kiểm tra khóa báo cáo, trùng báo cáo, rồi mở hồ sơ và đếm")
        void utc053_statementsSentInOrder() {
            service.report(POST, REPORTER_ID, 2L, null);

            assertThat(statementOrder()).containsExactly("reasons", "muteCheck", "alreadyReported", "lockOpenCase",
                    "createCase", "readCaseId", "insertReport", "countUp", "readCount");
        }

        @Test
        @DisplayName("UTC-054 Bài đã có hồ sơ đang mở: dùng lại hồ sơ, số báo cáo tăng lên 2")
        void utc054_openCaseReused() {
            openCaseExists();
            reportCount(2);

            Outcome outcome = service.report(POST, REPORTER_ID, 3L, null);

            assertThat(outcome).isEqualTo(new Outcome(2, false));
            assertThat(wrote("INSERT INTO report_cases")).isFalse();
            assertThat(writeArgs("INSERT INTO reports").get(0)).isEqualTo(CASE_ID);
            assertThat(statementOrder()).doesNotContain("createCase", "readCaseId");
        }

        @ParameterizedTest(name = "[{index}] loại {0}")
        @ValueSource(strings = {"COMMENT", "REVIEW", "MESSAGE"})
        @DisplayName("UTC-055 Báo cáo bình luận, review, tin nhắn: hồ sơ đúng loại, do Moderator xử lý")
        void utc055_otherMemberContent_moderatorCase(String type) {
            service.report(new Target(type, 700L, AUTHOR_ID), REPORTER_ID, 3L, null);

            assertThat(writeArgs("INSERT INTO report_cases")).containsExactly(type, 700L, "MODERATOR",
                    Timestamp.from(NOW));
        }

        @Test
        @DisplayName("UTC-056 Báo cáo mẫu câu: lý do của Nhật ngữ, hồ sơ do Biên tập viên tiếng Nhật xử lý")
        void utc056_phrase_academicReasonAndHandler() {
            Outcome outcome = service.report(new Target("PHRASE", 501L, 3L), REPORTER_ID, 22L, "Nghĩa chưa đúng");

            assertThat(outcome.hide()).isFalse();
            assertThat(writeArgs("INSERT INTO report_cases")).containsExactly("PHRASE", 501L, "ACADEMIC_MANAGER",
                    Timestamp.from(NOW));
            assertThat(writeArgs("INSERT INTO reports").get(2)).isEqualTo(22L);
        }

        @Test
        @DisplayName("UTC-057 Ghi chú có khoảng trắng hai đầu: lưu ghi chú đã cắt khoảng trắng")
        void utc057_noteStripped() {
            service.report(POST, REPORTER_ID, 1L, "  Đòi chuyển cọc trước  ");

            assertThat(writeArgs("INSERT INTO reports").get(3)).isEqualTo("Đòi chuyển cọc trước");
        }

        @Test
        @DisplayName("UTC-058 Không có ghi chú: lưu báo cáo không ghi chú")
        void utc058_noNote_storedAsNull() {
            service.report(POST, REPORTER_ID, 1L, null);

            assertThat(writeArgs("INSERT INTO reports").get(3)).isNull();
        }

        @ParameterizedTest(name = "[{index}] ghi chú \"{0}\"")
        @ValueSource(strings = {"", "   ", "\t\n"})
        @DisplayName("UTC-059 Ghi chú trống hoặc chỉ có khoảng trắng: lưu là không có ghi chú")
        void utc059_blankNote_storedAsNull(String note) {
            service.report(POST, REPORTER_ID, 1L, note);

            assertThat(writeArgs("INSERT INTO reports").get(3)).isNull();
        }

        @Test
        @DisplayName("UTC-060 Ghi chú đúng 500 ký tự: được nhận")
        void utc060_noteOf500_accepted() {
            String note = "a".repeat(500);

            service.report(POST, REPORTER_ID, 1L, note);

            assertThat(writeArgs("INSERT INTO reports").get(3)).isEqualTo(note);
        }

        @Test
        @DisplayName("UTC-061 Ghi chú 500 ký tự kèm khoảng trắng hai đầu: được nhận sau khi cắt")
        void utc061_noteOf500WithSpaces_acceptedAfterStrip() {
            service.report(POST, REPORTER_ID, 1L, "   " + "b".repeat(500) + "   ");

            assertThat((String) writeArgs("INSERT INTO reports").get(3)).hasSize(500);
        }

        @Test
        @DisplayName("UTC-062 Báo cáo thứ 5: hồ sơ vẫn \"Cần xử lý\", nội dung vẫn hiện")
        void utc062_fifthReport_notHidden() {
            openCaseExists();
            reportCount(5);

            Outcome outcome = service.report(POST, REPORTER_ID, 1L, null);

            assertThat(outcome).isEqualTo(new Outcome(5, false));
            assertThat(wrote("URGENT")).isFalse();
        }

        @Test
        @DisplayName("UTC-063 Báo cáo thứ 6: hồ sơ chuyển \"Khẩn\", yêu cầu ẩn nội dung")
        void utc063_sixthReport_urgentAndHide() {
            openCaseExists();
            reportCount(6);

            Outcome outcome = service.report(POST, REPORTER_ID, 1L, null);

            assertThat(outcome).isEqualTo(new Outcome(6, true));
            assertThat(writes()).last().asString().contains("SET priority = 'URGENT'")
                    .contains("AND priority = 'NORMAL'");
            assertThat(writeArgs("URGENT")).containsExactly(CASE_ID);
        }

        @Test
        @DisplayName("UTC-064 Báo cáo thứ 7 (hồ sơ đã \"Khẩn\"): vẫn yêu cầu ẩn nội dung")
        void utc064_seventhReport_hideAgain() {
            openCaseExists();
            reportCount(7);

            Outcome outcome = service.report(POST, REPORTER_ID, 1L, null);

            assertThat(outcome).isEqualTo(new Outcome(7, true));
            assertThat(wrote("URGENT")).isTrue();
        }

        @Test
        @DisplayName("UTC-065 Ngưỡng Admin đặt là 3, báo cáo thứ 3: chưa ẩn")
        void utc065_thresholdThree_thirdReportNotHidden() {
            when(settings.getInt(SystemSettings.REPORT_HIDE_THRESHOLD, 5)).thenReturn(3);
            openCaseExists();
            reportCount(3);

            Outcome outcome = service.report(POST, REPORTER_ID, 1L, null);

            assertThat(outcome).isEqualTo(new Outcome(3, false));
            verify(settings).getInt("report_hide_threshold", 5);
        }

        @Test
        @DisplayName("UTC-066 Ngưỡng Admin đặt là 3, báo cáo thứ 4: ẩn nội dung")
        void utc066_thresholdThree_fourthReportHidden() {
            when(settings.getInt(SystemSettings.REPORT_HIDE_THRESHOLD, 5)).thenReturn(3);
            openCaseExists();
            reportCount(4);

            Outcome outcome = service.report(POST, REPORTER_ID, 1L, null);

            assertThat(outcome).isEqualTo(new Outcome(4, true));
            assertThat(wrote("URGENT")).isTrue();
        }

        @Test
        @DisplayName("UTC-067 Hai báo cáo đầu tiên cùng lúc: báo cáo sau dùng hồ sơ vừa được mở")
        void utc067_caseCreatedMeanwhile_existingCaseUsed() {
            when(jdbc.update(contains("INSERT INTO report_cases"), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("uq_case_open"));
            reportCount(2);

            Outcome outcome = service.report(POST, REPORTER_ID, 2L, null);

            assertThat(outcome).isEqualTo(new Outcome(2, false));
            assertThat(writeArgs("INSERT INTO reports").get(0)).isEqualTo(CASE_ID);
        }

        @Test
        @DisplayName("UTC-068 Khóa báo cáo đã hết hạn 1 phút trước: báo cáo được")
        void utc068_muteExpired_reportAccepted() {
            mutedUntil(NOW.minus(Duration.ofMinutes(1)));

            Outcome outcome = service.report(POST, REPORTER_ID, 1L, null);

            assertThat(outcome.reportCount()).isEqualTo(1);
            assertThat(wrote("INSERT INTO reports")).isTrue();
        }

        @Test
        @DisplayName("UTC-069 Khóa báo cáo hết hạn đúng lúc này: báo cáo được")
        void utc069_muteEndsNow_reportAccepted() {
            mutedUntil(NOW);

            service.report(POST, REPORTER_ID, 1L, null);

            assertThat(wrote("INSERT INTO reports")).isTrue();
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. report – UC 1.11 Báo cáo bị từ chối (UTC-070 → UTC-082)")
    class ReportRefused {

        @Test
        @DisplayName("UTC-070 Báo cáo nội dung của chính mình: bị từ chối, không đọc gì thêm")
        void utc070_ownContent_400() {
            assertApiError(() -> service.report(POST, AUTHOR_ID, 1L, null), HttpStatus.BAD_REQUEST, OWN_CONTENT);
            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("UTC-071 Không chọn lý do: \"Hãy chọn lý do báo cáo.\", không lưu gì")
        void utc071_noReason_400() {
            assertApiError(() -> service.report(POST, REPORTER_ID, null, "Không chọn lý do"),
                    HttpStatus.BAD_REQUEST, MSG40);
            assertThat(writes()).isEmpty();
        }

        @ParameterizedTest(name = "[{index}] lý do {0}")
        @ValueSource(longs = {40L, 21L, 987_654_321L})
        @DisplayName("UTC-072 Lý do không thuộc danh sách báo cáo nội dung: yêu cầu chọn lý do")
        void utc072_reasonNotOffered_400(long reasonId) {
            assertApiError(() -> service.report(POST, REPORTER_ID, reasonId, null), HttpStatus.BAD_REQUEST, MSG40);
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-073 Báo cáo mẫu câu với lý do của bài viết: yêu cầu chọn lý do")
        void utc073_phraseWithPostReason_400() {
            assertApiError(() -> service.report(new Target("PHRASE", 501L, 3L), REPORTER_ID, 1L, null),
                    HttpStatus.BAD_REQUEST, MSG40);
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-074 Ghi chú 501 ký tự: \"Ghi chú tối đa 500 ký tự.\", không lưu gì")
        void utc074_noteOf501_400() {
            assertApiError(() -> service.report(POST, REPORTER_ID, 1L, "a".repeat(501)),
                    HttpStatus.BAD_REQUEST, NOTE_TOO_LONG);
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-075 Thành viên đang bị khóa báo cáo: báo ngày hết khóa theo giờ Nhật")
        void utc075_reportingLocked_403WithJapanDate() {
            mutedUntil(Instant.parse("2026-11-26T16:00:00Z"));   // 27/11/2026 01:00 in Japan

            assertApiError(() -> service.report(POST, REPORTER_ID, 1L, null), HttpStatus.FORBIDDEN,
                    "Bạn bị khóa báo cáo đến 27/11/2026 vì có quá nhiều báo cáo sai.");
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-076 Khóa báo cáo còn 1 giây: vẫn bị từ chối")
        void utc076_muteEndsInOneSecond_403() {
            mutedUntil(NOW.plusSeconds(1));

            assertApiError(() -> service.report(POST, REPORTER_ID, 1L, null), HttpStatus.FORBIDDEN,
                    "Bạn bị khóa báo cáo đến 20/11/2026 vì có quá nhiều báo cáo sai.");
            assertThat(writes()).isEmpty();
        }

        @Test
        @DisplayName("UTC-077 Báo cáo lần hai cùng nội dung: \"Bạn đã báo cáo nội dung này rồi.\"")
        void utc077_alreadyReported_409() {
            alreadyReported();

            assertApiError(() -> service.report(POST, REPORTER_ID, 4L, null), HttpStatus.CONFLICT, MSG39);
            assertThat(writes()).isEmpty();
            assertThat(statementOrder()).doesNotContain("lockOpenCase");
        }

        @Test
        @DisplayName("UTC-078 Kiểm tra trùng báo cáo tính cả hồ sơ đã được xử lý")
        void utc078_alreadyReportedCheck_coversHandledCases() {
            service.report(POST, REPORTER_ID, 1L, null);

            Invocation check = jdbcCalls().stream()
                    .filter(i -> ((String) i.getArgument(0)).contains("FROM reports r")).findFirst().orElseThrow();
            assertThat((String) check.getArgument(0)).doesNotContain("status");
            assertThat(Arrays.copyOfRange(check.getArguments(), 2, 5)).containsExactly("BLOG", POST_ID, REPORTER_ID);
        }

        @Test
        @DisplayName("UTC-079 Gửi hai lần cùng lúc: lần sau báo đã báo cáo rồi, số báo cáo không tăng")
        void utc079_sameReportTwiceAtOnce_409() {
            when(jdbc.update(contains("INSERT INTO reports"), any(), any(), any(), any(), any()))
                    .thenThrow(new DuplicateKeyException("uq_report_case_reporter"));

            assertApiError(() -> service.report(POST, REPORTER_ID, 1L, null), HttpStatus.CONFLICT, MSG39);
            assertThat(wrote("report_count = report_count + 1")).isFalse();
        }

        @Test
        @DisplayName("UTC-080 Nội dung của chính mình và không chọn lý do: báo lỗi nội dung của chính mình trước")
        void utc080_ownContentCheckedBeforeReason() {
            assertApiError(() -> service.report(POST, AUTHOR_ID, null, null), HttpStatus.BAD_REQUEST, OWN_CONTENT);
        }

        @Test
        @DisplayName("UTC-081 Không chọn lý do và ghi chú quá dài: báo thiếu lý do trước")
        void utc081_reasonCheckedBeforeNote() {
            assertApiError(() -> service.report(POST, REPORTER_ID, null, "a".repeat(501)),
                    HttpStatus.BAD_REQUEST, MSG40);
        }

        @Test
        @DisplayName("UTC-082 Đang bị khóa báo cáo và đã báo cáo rồi: báo khóa báo cáo trước")
        void utc082_muteCheckedBeforeDuplicate() {
            mutedUntil(Instant.parse("2026-11-26T16:00:00Z"));
            alreadyReported();

            assertApiError(() -> service.report(POST, REPORTER_ID, 1L, null), HttpStatus.FORBIDDEN,
                    "Bạn bị khóa báo cáo đến 27/11/2026 vì có quá nhiều báo cáo sai.");
            assertThat(statementOrder()).doesNotContain("alreadyReported");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. closeCasesOf – Đóng hồ sơ khi tác giả tự xóa nội dung (UTC-083 → UTC-085)")
    class CloseCases {

        @Test
        @DisplayName("UTC-083 Không có nội dung nào: không chạy câu lệnh nào")
        void utc083_emptyList_noStatement() {
            service.closeCasesOf("COMMENT", List.of());

            verifyNoInteractions(jdbc);
        }

        @Test
        @DisplayName("UTC-084 Một bài viết: đóng hồ sơ đang mở với ghi chú \"Tác giả đã tự xóa nội dung.\"")
        void utc084_oneItem_openCaseResolved() {
            service.closeCasesOf("BLOG", List.of(POST_ID));

            assertThat(writes()).singleElement().asString()
                    .contains("SET status = 'RESOLVED'")
                    .contains("decision_note = 'Tác giả đã tự xóa nội dung.'")
                    .contains("WHERE status = 'OPEN' AND target_type = ?")
                    .contains("target_id IN (?)");
            assertThat(writeArgs("RESOLVED")).containsExactly(Timestamp.from(NOW), "BLOG", POST_ID);
        }

        @Test
        @DisplayName("UTC-085 Ba bình luận: một câu lệnh, ba tham số theo đúng thứ tự")
        void utc085_threeItems_onePlaceholderEach() {
            service.closeCasesOf("COMMENT", List.of(701L, 702L, 703L));

            assertThat(writes()).singleElement().asString().contains("target_id IN (?,?,?)");
            assertThat(writeArgs("RESOLVED")).containsExactly(Timestamp.from(NOW), "COMMENT", 701L, 702L, 703L);
        }
    }
}
