package com.sep490.vsa.feature.chat.notify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sep490.vsa.common.mail.EmailSender;
import com.sep490.vsa.feature.chat.notify.NotifyRepository.DigestRow;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the e-mail digest of unread chat messages ({@link ChatEmailNotifier}): one e-mail per member every
 * 5 minutes for messages left unread 10 minutes, each conversation at most once in 6 hours, a link built from
 * {@code app.public-url}, never the message text; plus the Java side of {@link NotifyRepository#digestRows} and
 * {@link NotifyRepository#markEmailed} (the SQL that applies the e-mail setting and the other rules). From CLAUDE.md
 * "Tin nhắn" → "New-message notifications" and {@code NotifyFlowTest}.
 * <p>
 * {@link NotifyRepository} (or its {@link JdbcTemplate}) and {@link EmailSender} are mocks; the clock is fixed at
 * 20/11/2026 03:00 UTC; log lines are read with a Logback {@link ListAppender}.
 */
@TestClassOrder(ClassOrderer.DisplayName.class)
@DisplayName("ChatEmailNotifier – E-mail nhắc tin nhắn chưa đọc")
class ChatEmailNotifierTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private static final String SUBJECT = "Bạn có tin nhắn mới trên Senpai ơi";
    private static final String PUBLIC_URL = "https://senpaioi.com";

    private NotifyRepository repository;
    private EmailSender mail;
    private ChatEmailNotifier notifier;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        repository = mock(NotifyRepository.class);
        mail = mock(EmailSender.class);
        notifier = notifier(PUBLIC_URL, 10, 6);
        logger = (Logger) LoggerFactory.getLogger(ChatEmailNotifier.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
    }

    private ChatEmailNotifier notifier(String publicUrl, long delayMinutes, long spacingHours) {
        return new ChatEmailNotifier(repository, mail, Clock.fixed(NOW, ZoneOffset.UTC), publicUrl, delayMinutes,
                spacingHours);
    }

    private static DigestRow row(long userId, String email, String userName, long conversationId, boolean request,
                                 String peerName, long newestId) {
        return new DigestRow(userId, email, userName, conversationId, request, peerName, newestId);
    }

    /** Rows of member 10 (Minh) from peers named as given, newest first, conversation ids 1, 2, … */
    private static List<DigestRow> rowsOfMinh(String... peers) {
        List<DigestRow> rows = new ArrayList<>();
        for (int i = 0; i < peers.length; i++) {
            rows.add(row(10, "minh@example.org", "Minh", i + 1, false, peers[i], 900 - i));
        }
        return rows;
    }

    private void digestReturns(List<DigestRow> rows) {
        when(repository.digestRows(any(), any(), anyInt())).thenReturn(rows);
    }

    private List<String> messages(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("01. sendDigests – Một lượt gửi e-mail nhắc tin nhắn (UTC-166 → UTC-176)")
    class SendDigests {

        @Test
        @DisplayName("UTC-166 Không có tin nhắn nào cần nhắc: không gửi, không đánh dấu, trả 0")
        void utc166_digest_nothingToAnnounce() {
            digestReturns(List.of());

            assertThat(notifier.sendDigests()).isZero();
            verifyNoInteractions(mail);
            verify(repository, never()).markEmailed(anyLong(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-167 Hỏi tin nhắn chưa đọc quá 10 phút, chưa nhắc trong 6 giờ, tối đa 1000 dòng")
        void utc167_digest_asksWithDelaySpacingAndLimit() {
            digestReturns(List.of());

            notifier.sendDigests();

            verify(repository).digestRows(NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofHours(6)), 1000);
            assertThat(ChatEmailNotifier.ROWS_PER_RUN).isEqualTo(1000);
        }

        @Test
        @DisplayName("UTC-168 Cấu hình 15 phút / 12 giờ: dùng đúng hai mốc mới, e-mail ghi \"12 giờ\"")
        void utc168_digest_usesConfiguredDelayAndSpacing() {
            ChatEmailNotifier custom = notifier(PUBLIC_URL, 15, 12);
            digestReturns(rowsOfMinh("Lan"));

            custom.sendDigests();

            verify(repository).digestRows(NOW.minus(Duration.ofMinutes(15)), NOW.minus(Duration.ofHours(12)), 1000);
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(mail).send(anyString(), anyString(), text.capture());
            assertThat(text.getValue()).contains("nhiều nhất một lần trong 12 giờ");
        }

        @Test
        @DisplayName("UTC-169 Một cuộc trò chuyện: một e-mail tới địa chỉ của thành viên, rồi đánh dấu đã nhắc")
        void utc169_digest_oneConversationOneEmail() {
            digestReturns(List.of(row(10, "minh@example.org", "Minh", 5, false, "Lan", 900)));

            assertThat(notifier.sendDigests()).isEqualTo(1);

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(mail).send(eq("minh@example.org"), eq(SUBJECT), text.capture());
            assertThat(text.getValue()).contains("Chào Minh,", "từ Lan.", "https://senpaioi.com/messages");
            verify(repository).markEmailed(5, 10, 900, NOW);
        }

        @Test
        @DisplayName("UTC-170 Một thành viên, ba cuộc trò chuyện: chỉ một e-mail, đánh dấu cả ba")
        void utc170_digest_oneEmailPerMemberMarksEveryConversation() {
            digestReturns(rowsOfMinh("Lan", "Tiến", "Hoa"));

            assertThat(notifier.sendDigests()).isEqualTo(1);

            verify(mail, times(1)).send(anyString(), anyString(), anyString());
            verify(repository).markEmailed(1, 10, 900, NOW);
            verify(repository).markEmailed(2, 10, 899, NOW);
            verify(repository).markEmailed(3, 10, 898, NOW);
        }

        @Test
        @DisplayName("UTC-171 Hai thành viên: mỗi người một e-mail riêng, trả 2")
        void utc171_digest_oneEmailEachForTwoMembers() {
            digestReturns(List.of(row(10, "minh@example.org", "Minh", 1, false, "Lan", 900),
                    row(11, "lan@example.org", "Lan", 1, false, "Minh", 901),
                    row(11, "lan@example.org", "Lan", 7, true, "Người lạ", 950)));

            assertThat(notifier.sendDigests()).isEqualTo(2);

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(mail).send(eq("lan@example.org"), eq(SUBJECT), text.capture());
            assertThat(text.getValue()).contains("Chào Lan,", "từ Minh và Người lạ (lời mời nhắn tin).");
            verify(mail).send(eq("minh@example.org"), eq(SUBJECT), anyString());
            verify(repository).markEmailed(1, 10, 900, NOW);
            verify(repository).markEmailed(1, 11, 901, NOW);
            verify(repository).markEmailed(7, 11, 950, NOW);
        }

        @Test
        @DisplayName("UTC-172 Gửi e-mail cho một người lỗi: người khác vẫn nhận; người lỗi không bị đánh dấu")
        void utc172_digest_failedEmailIsNotMarkedOthersGoOn() {
            digestReturns(List.of(row(10, "minh@example.org", "Minh", 1, false, "Lan", 900),
                    row(11, "lan@example.org", "Lan", 1, false, "Minh", 901)));
            doThrow(new MailSendException("SMTP down")).when(mail).send(eq("minh@example.org"), anyString(),
                    anyString());

            assertThat(notifier.sendDigests()).isEqualTo(1);

            verify(repository, never()).markEmailed(eq(1L), eq(10L), anyLong(), any());
            verify(repository).markEmailed(1, 11, 901, NOW);
            assertThat(messages(Level.WARN)).containsExactly("Chat e-mail digest to member 10 not sent: SMTP down");
        }

        @Test
        @DisplayName("UTC-173 Mọi e-mail đều lỗi: trả 0, không đánh dấu gì (lượt sau gửi lại)")
        void utc173_digest_allFailedMarksNothing() {
            digestReturns(rowsOfMinh("Lan", "Tiến"));
            doThrow(new MailSendException("SMTP down")).when(mail).send(anyString(), anyString(), anyString());

            assertThat(notifier.sendDigests()).isZero();

            verify(repository, never()).markEmailed(anyLong(), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("UTC-174 Gửi e-mail trước rồi mới đánh dấu đã nhắc")
        void utc174_digest_sendsBeforeMarking() {
            digestReturns(rowsOfMinh("Lan"));

            notifier.sendDigests();

            InOrder order = inOrder(mail, repository);
            order.verify(mail).send(anyString(), anyString(), anyString());
            order.verify(repository).markEmailed(1, 10, 900, NOW);
        }

        @Test
        @DisplayName("UTC-175 E-mail không chứa nội dung tin nhắn, nói rõ lý do")
        void utc175_digest_neverContainsMessageText() {
            digestReturns(rowsOfMinh("Lan"));

            notifier.sendDigests();

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(mail).send(anyString(), anyString(), text.capture());
            assertThat(text.getValue())
                    .contains("Để bảo vệ sự riêng tư của bạn, e-mail này không chứa nội dung tin nhắn.");
            assertThat(DigestRow.class.getRecordComponents()).extracting(c -> c.getName())
                    .containsExactly("userId", "email", "userName", "conversationId", "request", "peerName",
                            "newestId");
        }

        @Test
        @DisplayName("UTC-176 Lỗi đọc cơ sở dữ liệu: lỗi được ném lên, không gửi gì")
        void utc176_digest_databaseErrorIsPassedOn() {
            when(repository.digestRows(any(), any(), anyInt()))
                    .thenThrow(new DataAccessResourceFailureException("DB down"));

            org.assertj.core.api.Assertions.assertThatThrownBy(notifier::sendDigests)
                    .isInstanceOf(DataAccessResourceFailureException.class);
            verifyNoInteractions(mail);
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("02. run – Việc chạy định kỳ 5 phút một lần (UTC-177 → UTC-180)")
    class ScheduledRun {

        @Test
        @DisplayName("UTC-177 Có e-mail được gửi: ghi log số e-mail")
        void utc177_run_logsTheNumberSent() {
            digestReturns(List.of(row(10, "minh@example.org", "Minh", 1, false, "Lan", 900),
                    row(11, "lan@example.org", "Lan", 1, false, "Minh", 901)));

            notifier.run();

            assertThat(messages(Level.INFO)).containsExactly("Chat e-mail digest: 2 e-mails sent");
        }

        @Test
        @DisplayName("UTC-178 Không có gì để gửi: không ghi log")
        void utc178_run_quietWhenNothingSent() {
            digestReturns(List.of());

            notifier.run();

            assertThat(logs.list).isEmpty();
        }

        @Test
        @DisplayName("UTC-179 Lỗi cơ sở dữ liệu: không ném lỗi, ghi cảnh báo, lượt sau chạy lại")
        void utc179_run_swallowsAndLogsFailure() {
            when(repository.digestRows(any(), any(), anyInt()))
                    .thenThrow(new DataAccessResourceFailureException("DB down"));

            assertThatCode(notifier::run).doesNotThrowAnyException();

            assertThat(messages(Level.WARN)).containsExactly("Chat e-mail digest failed: DB down");
            verifyNoInteractions(mail);
        }

        @Test
        @DisplayName("UTC-180 Lịch chạy: mỗi 5 phút (300000 ms), lần đầu sau 1 phút")
        void utc180_run_isScheduledEveryFiveMinutes() throws Exception {
            Scheduled scheduled = ChatEmailNotifier.class.getMethod("run").getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelayString()).isEqualTo("${app.chat.notify.email-interval-ms:300000}");
            assertThat(scheduled.initialDelayString()).isEqualTo("${app.chat.notify.email-initial-delay-ms:60000}");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("03. text – Nội dung e-mail (UTC-181 → UTC-190)")
    class EmailText {

        @Test
        @DisplayName("UTC-181 Một người viết: \"từ Lan.\"")
        void utc181_text_oneName() {
            assertThat(notifier.text("Minh", rowsOfMinh("Lan")))
                    .contains("Bạn có tin nhắn chưa đọc trên Senpai ơi từ Lan.");
        }

        @Test
        @DisplayName("UTC-182 Hai người: \"Lan và Tiến\"")
        void utc182_text_twoNames() {
            assertThat(notifier.text("Minh", rowsOfMinh("Lan", "Tiến"))).contains("từ Lan và Tiến.");
        }

        @Test
        @DisplayName("UTC-183 Ba người: \"Lan, Tiến và Hoa\"")
        void utc183_text_threeNames() {
            assertThat(notifier.text("Minh", rowsOfMinh("Lan", "Tiến", "Hoa"))).contains("từ Lan, Tiến và Hoa.");
        }

        @Test
        @DisplayName("UTC-184 Đúng 5 người: nêu đủ 5 tên, không có \"người khác\"")
        void utc184_text_exactlyFiveNames() {
            assertThat(notifier.text("Minh", rowsOfMinh("A1", "A2", "A3", "A4", "A5")))
                    .contains("từ A1, A2, A3, A4 và A5.")
                    .doesNotContain("người khác");
        }

        @Test
        @DisplayName("UTC-185 6 người: 5 tên mới nhất + \"và 1 người khác\"")
        void utc185_text_sixNamesGiveOneOther() {
            assertThat(notifier.text("Minh", rowsOfMinh("A1", "A2", "A3", "A4", "A5", "A6")))
                    .contains("từ A1, A2, A3, A4, A5 và 1 người khác.")
                    .doesNotContain("A6");
        }

        @Test
        @DisplayName("UTC-186 7 người: 5 tên mới nhất + \"và 2 người khác\"")
        void utc186_text_sevenNamesGiveTwoOthers() {
            assertThat(notifier.text("Minh", rowsOfMinh("Người gửi 7", "Người gửi 6", "Người gửi 5", "Người gửi 4",
                    "Người gửi 3", "Người gửi 2", "Người gửi 1")))
                    .contains("từ Người gửi 7, Người gửi 6, Người gửi 5, Người gửi 4, Người gửi 3 và 2 người khác.")
                    .doesNotContain("Người gửi 2,", "Người gửi 1");
        }

        @Test
        @DisplayName("UTC-187 Lời mời nhắn tin chưa nhận: ghi \"(lời mời nhắn tin)\" sau tên")
        void utc187_text_requestIsMarked() {
            List<DigestRow> rows = List.of(row(10, "m@x", "Minh", 1, false, "Lan", 9),
                    row(10, "m@x", "Minh", 2, true, "Tiến", 8));

            assertThat(notifier.text("Minh", rows)).contains("từ Lan và Tiến (lời mời nhắn tin).");
        }

        @Test
        @DisplayName("UTC-188 Lời chào, liên kết Tin nhắn, cách tắt e-mail, \"6 giờ\"")
        void utc188_text_greetingLinkAndHowToTurnOff() {
            String text = notifier.text("Minh", rowsOfMinh("Lan"));

            assertThat(text).startsWith("Chào Minh,")
                    .contains("Mở Tin nhắn để đọc và trả lời: https://senpaioi.com/messages")
                    .contains("Mỗi cuộc trò chuyện được nhắc nhiều nhất một lần trong 6 giờ.")
                    .contains("vào \"Cài đặt thông báo\" và bỏ chọn \"Thông báo qua e-mail\"")
                    .endsWith("Senpai ơi\n");
        }

        @ParameterizedTest(name = "[{index}] app.public-url = {0}")
        @ValueSource(strings = {"https://senpaioi.com", "https://senpaioi.com/", "https://senpaioi.com///"})
        @DisplayName("UTC-189 Địa chỉ công khai có / không có \"/\" cuối: liên kết luôn là .../messages")
        void utc189_text_linkDropsTrailingSlashes(String publicUrl) {
            assertThat(notifier(publicUrl, 10, 6).text("Minh", rowsOfMinh("Lan")))
                    .contains("trả lời: https://senpaioi.com/messages\n");
        }

        @Test
        @DisplayName("UTC-190 Máy phát triển (http://localhost:8080): liên kết http://localhost:8080/messages")
        void utc190_text_localDevelopmentLink() {
            assertThat(notifier("http://localhost:8080", 10, 6).text("Minh", rowsOfMinh("Lan")))
                    .contains("trả lời: http://localhost:8080/messages");
        }
    }

    // =================================================================================================================
    @Nested
    @TestMethodOrder(MethodOrderer.DisplayName.class)
    @DisplayName("04. NotifyRepository.digestRows / markEmailed – chọn tin nhắn để nhắc (UTC-191 → UTC-197)")
    class DigestSelection {

        private JdbcTemplate jdbc;
        private NotifyRepository real;

        @BeforeEach
        void realRepository() {
            jdbc = mock(JdbcTemplate.class);
            real = new NotifyRepository(jdbc);
        }

        private String digestSql() {
            real.digestRows(NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofHours(6)), 1000);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
            return sql.getValue();
        }

        @Test
        @DisplayName("UTC-191 Tham số: mốc đã nhắc, mốc tin nhắn, giới hạn – đúng thứ tự")
        void utc191_repoDigest_parametersInOrder() {
            real.digestRows(NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofHours(6)), 1000);

            verify(jdbc).query(anyString(), any(RowMapper.class),
                    eq(Timestamp.from(Instant.parse("2026-11-19T21:00:00Z"))),
                    eq(Timestamp.from(Instant.parse("2026-11-20T02:50:00Z"))), eq(1000));
        }

        @Test
        @DisplayName("UTC-192 Chỉ Thành viên ACTIVE đang bật e-mail (không có dòng = bật)")
        void utc192_repoDigest_onlyActiveMembersWithEmailOn() {
            assertThat(digestSql()).contains("u.role = 'USER' AND u.status = 'ACTIVE'")
                    .contains("(s.user_id IS NULL OR s.email_enabled)");
        }

        @Test
        @DisplayName("UTC-193 Chỉ hộp thư / lời mời, chưa nhắc trong 6 giờ, đợi ít nhất 10 phút")
        void utc193_repoDigest_inboxOrRequestSpacingAndDelay() {
            assertThat(digestSql()).contains("me.state IN ('ACCEPTED', 'REQUEST')")
                    .contains("(me.emailed_at IS NULL OR me.emailed_at <= ?)")
                    .contains("HAVING MIN(m.created_at) <= ?");
        }

        @Test
        @DisplayName("UTC-194 Bỏ qua: đã đọc / đã xóa / đã nhắc, thu hồi, người viết bị khóa, người bị chặn")
        void utc194_repoDigest_leavesOutWhatIsNotAnnounced() {
            assertThat(digestSql())
                    .contains("m.id > GREATEST(me.last_read_id, me.cleared_id, me.notified_up_to_id)")
                    .contains("m.recalled_at IS NULL")
                    .contains("p.status = 'ACTIVE'")
                    .contains("NOT EXISTS (SELECT 1 FROM chat_blocks b WHERE b.blocker_id = me.user_id "
                            + "AND b.blocked_id = p.id)");
        }

        @Test
        @DisplayName("UTC-195 Không bao giờ đọc nội dung tin nhắn; nhóm theo thành viên, mới nhất trước")
        void utc195_repoDigest_neverReadsTextAndOrdersByMember() {
            assertThat(digestSql()).doesNotContain("m.content", "ciphertext", "m.body")
                    .contains("ORDER BY me.user_id, newest_id DESC");
        }

        @Test
        @DisplayName("UTC-196 Ánh xạ dòng: trạng thái REQUEST → lời mời, ACCEPTED → không")
        void utc196_repoDigest_mapsRequestState() throws Exception {
            real.digestRows(NOW, NOW, 10);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<RowMapper<DigestRow>> mapper = ArgumentCaptor.forClass(RowMapper.class);
            verify(jdbc).query(anyString(), mapper.capture(), any(Object[].class));
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(10L);
            when(rs.getString(2)).thenReturn("minh@example.org");
            when(rs.getString(3)).thenReturn("Minh");
            when(rs.getLong(4)).thenReturn(5L);
            when(rs.getString(5)).thenReturn("REQUEST", "ACCEPTED");
            when(rs.getString(6)).thenReturn("Lan");
            when(rs.getLong(7)).thenReturn(900L);

            assertThat(mapper.getValue().mapRow(rs, 0))
                    .isEqualTo(row(10, "minh@example.org", "Minh", 5, true, "Lan", 900));
            assertThat(mapper.getValue().mapRow(rs, 1).request()).isFalse();
        }

        @Test
        @DisplayName("UTC-197 Đánh dấu đã nhắc: notified_up_to_id không bao giờ lùi, emailed_at = bây giờ")
        void utc197_repoMarkEmailed_neverMovesBack() {
            real.markEmailed(5, 10, 900, NOW);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(sql.capture(), eq(900L), eq(Timestamp.from(NOW)), eq(5L), eq(10L));
            assertThat(sql.getValue()).contains("notified_up_to_id = GREATEST(notified_up_to_id, ?)")
                    .contains("WHERE conversation_id = ? AND user_id = ?");
        }
    }
}
