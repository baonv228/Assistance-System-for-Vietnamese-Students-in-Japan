package com.sep490.vsa.feature.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.common.mail.EmailSender;
import com.sep490.vsa.common.security.TokenHasher;
import com.sep490.vsa.feature.media.MediaStorage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email sign-up end to end against MySQL ({@code asvsj_test}): the send-code limits (client address, mailbox, whole
 * site), the code emailed only after its row is committed and a failed email that blocks nothing, wrong codes sent
 * together, one ticket completed twice at once (one account, 409 for the other), the password and display-name checks, the Admin settings of sign-up, and deleting an account (tags,
 * tag requests, proof photos, points; the email can sign up again). The mail sender is a mock, so the codes are read
 * from the emails it was given. Each test uses its own addresses (a random tag) and removes its rows afterwards.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SignUpFlowTest {

    private static final String CSRF = "test-csrf-token";
    private static final String PASSWORD = "matkhau123";
    /** The code's time is kept to the second, so the wait shown is 60 give or take one second. */
    private static final String WAIT = "Vui lòng đợi (59|60|61) giây rồi gửi lại mã\\.";
    private static final String NAME_LENGTH = "Tên hiển thị dài từ 2 đến 60 ký tự";

    @MockBean
    private EmailSender mail;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RegistrationService registration;
    @Autowired
    private MediaStorage storage;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** In every address and email of this test, so its rows can be found and removed. */
    private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private final Map<String, String> changedSettings = new HashMap<>();
    private final List<Long> otherUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        changedSettings.forEach((key, value) ->
                jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = ?", value, key));
        jdbc.update("DELETE FROM email_verification_codes WHERE email LIKE ?", "%" + tag + "%");
        List<Long> ids = new ArrayList<>(jdbc.queryForList("SELECT id FROM users WHERE email LIKE ?", Long.class,
                "%" + tag + "%"));
        ids.addAll(otherUsers);
        for (long id : ids) {
            jdbc.update("DELETE FROM tag_requests WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_tags WHERE user_id = ?", id);
            jdbc.update("DELETE FROM media WHERE owner_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // ---------------------------------------------------------------- send-code limits (S4)

    @Test
    void sixCodesFromOneAddressWithinAnHourTheSixthIsRefused() throws Exception {
        String address = address(1);
        for (int i = 1; i <= 5; i++) {
            sendCode("ip" + i + tag + "@signup.test", address).andExpect(status().isOk());
        }
        sendCode("ip6" + tag + "@signup.test", address).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        "Bạn đã yêu cầu quá nhiều mã xác thực. Vui lòng thử lại sau 60 phút."));
        assertThat(sentTo("ip6" + tag + "@signup.test")).isZero();
        assertThat(codes("ip6" + tag + "@signup.test")).isZero();
        // Another address is not affected
        sendCode("ip7" + tag + "@signup.test", address(2)).andExpect(status().isOk());

        // Requests refused for another reason send nothing, so they do not use up the address's codes
        user("taken" + tag + "@signup.test");
        for (int i = 0; i < 6; i++) {
            sendCode("taken" + tag + "@signup.test", address(3)).andExpect(status().isConflict());
        }
        for (int i = 1; i <= 5; i++) {
            sendCode("ok" + i + tag + "@signup.test", address(3)).andExpect(status().isOk());
        }
        sendCode("ok6" + tag + "@signup.test", address(3)).andExpect(status().isTooManyRequests());
    }

    @Test
    void oneMailboxSharesItsLimitsWhateverTagOrGmailDots() throws Exception {
        String local = "mb" + tag;
        sendCode(local + "+1@gmail.com", address(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(local + "+1@gmail.com"));
        for (String same : List.of(local + "+2@gmail.com", "m.b" + tag + "@gmail.com", local + "@googlemail.com",
                "M.B" + tag.toUpperCase() + "+X@GMAIL.COM")) {
            sendCode(same, address(2)).andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.message").value(matchesPattern(WAIT)));
        }
        // Another domain is another mailbox; there the "+tag" is dropped but dots count
        sendCode(local + "@signup.test", address(3)).andExpect(status().isOk());
        sendCode(local + "+abc@signup.test", address(3)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(matchesPattern(WAIT)));
        sendCode("m.b" + tag + "@signup.test", address(3)).andExpect(status().isOk());

        // The account keeps the address exactly as typed
        String ticket = JsonPath.read(verifyCode(local + "+1@gmail.com", codeSentTo(local + "+1@gmail.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.passwordMinLength").value(8))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.ticket");
        complete(ticket, "Mailbox Test", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(local + "+1@gmail.com"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class,
                local + "+1@gmail.com")).isEqualTo(1);

        // 10 codes in 24 hours per mailbox, whatever spelling they were sent to
        String day = "dl" + tag;
        for (int i = 0; i < 9; i++) {
            insertCode((i % 2 == 0 ? day + "+" + i : "d.l" + tag + "+" + i) + "@gmail.com",
                    Instant.now().minus(Duration.ofHours(2)));
        }
        sendCode(day + "@gmail.com", address(4)).andExpect(status().isOk());
        jdbc.update("UPDATE email_verification_codes SET created_at = created_at - INTERVAL 1 HOUR WHERE email = ?",
                day + "@gmail.com");
        sendCode("d.l" + tag + "+again@googlemail.com", address(4)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        "Email này đã nhận quá nhiều mã hôm nay. Vui lòng thử lại vào ngày mai."));
        sendCode("d.l" + tag + "@signup.test", address(4)).andExpect(status().isOk());
    }

    @Test
    void theWholeSiteSendsAtMost300CodesIn24Hours() throws Exception {
        Instant now = Instant.now();
        long already = jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_codes WHERE created_at > ?",
                Long.class, Timestamp.from(now.minus(Duration.ofDays(1))));
        assertThat(already).isLessThan(300);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 300 - already; i++) {
            Instant at = now.minus(Duration.ofHours(1));
            rows.add(new Object[]{"bulk-" + tag + "-" + i + "@signup.test", "0".repeat(64),
                    Timestamp.from(at.plus(Duration.ofMinutes(10))), Timestamp.from(at)});
        }
        jdbc.batchUpdate("""
                INSERT INTO email_verification_codes (email, purpose, code_hash, expires_at, created_at)
                VALUES (?, 'REGISTER', ?, ?, ?)""", rows);

        String email = "site" + tag + "@signup.test";
        sendCode(email, address(1)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        "Hôm nay Senpai ơi đã gửi quá nhiều mã xác thực. Vui lòng thử lại sau vài giờ."));
        verify(mail, never()).send(anyString(), anyString(), anyString());
        assertThat(codes(email)).isZero();

        jdbc.update("DELETE FROM email_verification_codes WHERE email LIKE ?", "bulk-" + tag + "-%");
        sendCode(email, address(1)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- the email (S12)

    @Test
    void theCodeIsEmailedOnlyAfterItsRowIsCommitted() throws Exception {
        String email = "commit" + tag + "@signup.test";
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(invocation -> {
            boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            String subject = invocation.getArgument(1);
            String code = subject.substring(subject.length() - 6);
            // Another connection, on another thread, sees only committed rows
            String hash = CompletableFuture.supplyAsync(() -> jdbc.queryForObject(
                    "SELECT code_hash FROM email_verification_codes WHERE email = ?", String.class, email))
                    .get(10, TimeUnit.SECONDS);
            seen.set("in transaction: " + inTransaction + ", committed row: "
                    + hash.equals(TokenHasher.sha256(email + ":" + code)));
            return null;
        }).when(mail).send(eq(email), anyString(), anyString());

        sendCode(email, address(1)).andExpect(status().isOk());
        assertThat(seen.get()).isEqualTo("in transaction: false, committed row: true");
    }

    @Test
    void aFailedEmailCountsTowardNoLimitAndKeepsTheEarlierCode() throws Exception {
        String email = "fail" + tag + "@signup.test";
        doThrow(new MailSendException("SMTP down")).doNothing().when(mail).send(eq(email), anyString(), anyString());
        assertThatThrownBy(() -> sendCode(email, address(1))).hasRootCauseInstanceOf(MailSendException.class);
        assertThat(codes(email)).isZero();

        // Tried again at once: no "wait 60 seconds", and the failed request used none of the address's 5 codes
        sendCode(email, address(1)).andExpect(status().isOk());
        for (int i = 2; i <= 5; i++) {
            sendCode("fail" + i + tag + "@signup.test", address(1)).andExpect(status().isOk());
        }
        sendCode("fail6" + tag + "@signup.test", address(1)).andExpect(status().isTooManyRequests());

        // "Gửi lại mã" whose email fails: the code sent before still works
        setting("email_code_resend_seconds", "0");
        String other = "keep" + tag + "@signup.test";
        sendCode(other, address(2)).andExpect(status().isOk());
        String first = codeSentTo(other);
        doThrow(new MailSendException("SMTP down")).when(mail).send(eq(other), anyString(), anyString());
        assertThatThrownBy(() -> sendCode(other, address(2))).hasRootCauseInstanceOf(MailSendException.class);
        assertThat(codes(other)).isEqualTo(1);
        verifyCode(other, first).andExpect(status().isOk()).andExpect(jsonPath("$.data.ticket").isNotEmpty());
    }

    @Test
    void aSlowEmailNeverClosesTheNewerCodeSentMeanwhile() throws Exception {
        setting("email_code_resend_seconds", "0");
        String email = "slow" + tag + "@signup.test";
        CountDownLatch firstMailing = new CountDownLatch(1);
        CountDownLatch secondSent = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger mails = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (mails.incrementAndGet() == 1) {
                firstMailing.countDown();
                secondSent.await(10, TimeUnit.SECONDS);         // the first email waits on a slow mail server
            }
            return null;
        }).when(mail).send(eq(email), anyString(), anyString());

        // Two tabs: the first code is saved, then its email hangs while the second code is saved and sent
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                return sendCode(email, address(1)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(firstMailing.await(10, TimeUnit.SECONDS)).isTrue();
        sendCode(email, address(1)).andExpect(status().isOk());
        String newest = codeSentTo(email);
        secondSent.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);

        // The slow request closed only the older codes: the newest one, in the newest email, still works
        verifyCode(email, newest).andExpect(status().isOk()).andExpect(jsonPath("$.data.ticket").isNotEmpty());
    }

    // ---------------------------------------------------------------- verify-code (S8)

    @Test
    void tenWrongCodesSentTogetherAreCountedExactlyFiveTimes() throws Exception {
        for (int round = 0; round < 3; round++) {
            String email = "race" + round + tag + "@signup.test";
            sendCode(email, address(10 + round)).andExpect(status().isOk());
            String code = codeSentTo(email);
            String wrong = code.equals("000000") ? "111111" : "000000";
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> registration.verifyCode(email, wrong));
            }
            List<Object> results = LoginFlowTest.together(calls);

            assertThat(results).allMatch(r -> r instanceof ApiException);
            List<ApiException> errors = results.stream().map(r -> (ApiException) r).toList();
            assertThat(errors).filteredOn(e -> e.getStatus() == HttpStatus.BAD_REQUEST)
                    .extracting(ApiException::getMessage)
                    .containsExactlyInAnyOrder("Mã xác thực không đúng. Bạn còn 4 lần thử.",
                            "Mã xác thực không đúng. Bạn còn 3 lần thử.", "Mã xác thực không đúng. Bạn còn 2 lần thử.",
                            "Mã xác thực không đúng. Bạn còn 1 lần thử.", "Mã xác thực không đúng. Hãy bấm Gửi lại mã.");
            assertThat(errors).filteredOn(e -> e.getStatus() == HttpStatus.TOO_MANY_REQUESTS)
                    .extracting(ApiException::getMessage)
                    .containsOnly("Bạn đã nhập sai quá nhiều lần. Hãy bấm Gửi lại mã.").hasSize(5);
            assertThat(jdbc.queryForObject("SELECT attempts FROM email_verification_codes WHERE email = ?",
                    Integer.class, email)).isEqualTo(5);
            verifyCode(email, code).andExpect(status().isTooManyRequests());
        }
    }

    // ---------------------------------------------------------------- complete sent twice

    @Test
    void theSameTicketSentTwiceAtOnceMakesOneAccountAndTheOtherHears409() throws Exception {
        String ticket = null;
        for (int round = 0; round < 5; round++) {
            String email = "twice" + round + tag + "@signup.test";
            String roundTicket = ticket(email, address(30 + round));
            ticket = roundTicket;
            List<Object> results = LoginFlowTest.together(List.of(
                    () -> complete(roundTicket, "Twice One", PASSWORD).andReturn().getResponse(),
                    () -> complete(roundTicket, "Twice Two", PASSWORD).andReturn().getResponse()));
            List<MockHttpServletResponse> responses = results.stream().map(r -> (MockHttpServletResponse) r).toList();
            assertThat(responses).extracting(MockHttpServletResponse::getStatus).as("round " + round)
                    .containsExactlyInAnyOrder(200, 409);
            MockHttpServletResponse refused = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
            assertThat(JsonPath.<String>read(refused.getContentAsString(StandardCharsets.UTF_8), "$.message"))
                    .isEqualTo("Email này đã được đăng ký. Hãy đăng nhập.");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM email_verification_codes WHERE email = ?",
                    Boolean.class, email)).isTrue();
        }
        // Sent again later (a double click after the page moved on): the same answer
        complete(ticket, "Twice Again", PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email này đã được đăng ký. Hãy đăng nhập."));
        // An unknown or expired ticket still says the session is over
        complete("not-a-ticket", "Twice Again", PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Phiên đăng ký đã hết hạn. Vui lòng nhập lại email để nhận mã mới."));
    }

    @Test
    void aSocialSignUpThatMeetsAnotherNewAccountWithItsEmailAnswers409() throws Exception {
        String email = "socialrace" + tag + "@signup.test";
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingSocialSignup.SESSION_KEY,
                new PendingSocialSignup("GOOGLE", "g-race-" + tag, email, "Race Test", null));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Another tab is creating the same email: its row exists but is not committed yet, so the check before
            // the INSERT does not see it and the request meets it at the email's unique key
            Future<?> otherTab = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.update("""
                        INSERT INTO users (email, password_hash, display_name, role, status, is_adult_confirmed)
                        VALUES (?, NULL, 'Other Tab', 'USER', 'ACTIVE', TRUE)""", email);
                inserted.countDown();
                try {
                    assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<MockHttpServletResponse> mine = pool.submit(() ->
                    completeSocial(session, "Race Test").andReturn().getResponse());
            waitForTheInsert();
            release.countDown();
            otherTab.get(30, TimeUnit.SECONDS);

            MockHttpServletResponse response = mine.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.getContentAsString(StandardCharsets.UTF_8), "$.message"))
                    .isEqualTo("Email này đã được đăng ký. Hãy đăng nhập.");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT display_name FROM users WHERE email = ?", String.class, email))
                .containsExactly("Other Tab");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_auth_providers WHERE provider_user_id = ?",
                Long.class, "g-race-" + tag)).isZero();
    }

    /**
     * Waits until the request runs its INSERT into users (so it has passed the check before it), which then waits for
     * the other account's row; a row-lock wait counts too.
     */
    private void waitForTheInsert() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE INFO LIKE 'insert into users%')
                     + (SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_state = 'LOCK WAIT')""",
                Long.class) == 0) {
            assertThat(System.nanoTime()).as("the request never reached its INSERT").isLessThan(until);
            Thread.sleep(20);
        }
    }

    // ---------------------------------------------------------------- password and display name (S9, S11, S7)

    @Test
    void aPasswordOver72BytesIsRefused() throws Exception {
        String email = "bytes" + tag + "@signup.test";
        String ticket = ticket(email, address(1));
        String tooLong = "ậ".repeat(30) + "12";   // 32 characters, 92 bytes: BCrypt would keep 24 characters
        complete(ticket, "Byte Test", tooLong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.password").value(
                        "Mật khẩu quá dài (chữ có dấu được tính 2–3 ký tự). Hãy dùng mật khẩu ngắn hơn"));
        String exactly72 = "ậ".repeat(22) + "123456";
        assertThat(exactly72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        complete(ticket, "Byte Test", exactly72).andExpect(status().isOk());
        login(email, exactly72).andExpect(status().isOk());
        login(email, "ậ".repeat(22) + "12345").andExpect(status().isUnauthorized());
    }

    @Test
    void aDisplayNameIsCheckedAfterItsSpacesAreRemoved() throws Exception {
        String email = "name" + tag + "@signup.test";
        String ticket = ticket(email, address(1));
        complete(ticket, " a", PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.displayName").value(NAME_LENGTH));
        // Blank: @NotBlank and @Size both fail; either message may come first
        complete(ticket, "　 \t", PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.displayName").value(
                        matchesPattern("Vui lòng nhập tên hiển thị|" + NAME_LENGTH)));
        complete(ticket, "　 Lan \t", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Lan"));
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE email = ?", String.class, email))
                .isEqualTo("Lan");

        // The same on the Google/Facebook sign-up page
        String socialEmail = "social" + tag + "@signup.test";
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(PendingSocialSignup.SESSION_KEY,
                new PendingSocialSignup("GOOGLE", "g-" + tag, socialEmail, "Google Name", null));
        completeSocial(session, " a").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.displayName").value(NAME_LENGTH));
        completeSocial(session, "  Hà  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Hà"));
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE email = ?", String.class, socialEmail))
                .isEqualTo("Hà");
    }

    @Test
    void thePasswordMinimumFollowsTheAdminSetting() throws Exception {
        setting("password_min_length", "10");
        String email = "min" + tag + "@signup.test";
        sendCode(email, address(1)).andExpect(status().isOk());
        String ticket = JsonPath.read(verifyCode(email, codeSentTo(email)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordMinLength").value(10))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.ticket");
        complete(ticket, "Min Test", "abcdefg12").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mật khẩu dài từ 10 đến 72 ký tự"))
                .andExpect(jsonPath("$.data.password").value("Mật khẩu dài từ 10 đến 72 ký tự"));
        complete(ticket, "Min Test", "abcdefgh12").andExpect(status().isOk());

        // Not a number: the agreed default (8)
        setting("password_min_length", "tám");
        String other = "min2" + tag + "@signup.test";
        String ticket2 = ticket(other, address(2));
        complete(ticket2, "Min Test", "abcdef1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.password").value("Mật khẩu dài từ 8 đến 72 ký tự"));
        complete(ticket2, "Min Test", "abcdefg1").andExpect(status().isOk());
    }

    @Test
    void theOtherSignUpSettingsAreUsed() throws Exception {
        setting("email_code_minutes", "3");
        setting("email_code_resend_seconds", "90");
        setting("email_code_attempts", "2");
        setting("register_ticket_minutes", "5");
        String email = "set" + tag + "@signup.test";
        sendCode(email, address(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInMinutes").value(3))
                .andExpect(jsonPath("$.data.resendAfterSeconds").value(90));
        String text = lastMailTo(email).getArgument(2);
        assertThat(text).contains("Mã có hiệu lực trong 3 phút.");
        assertThat(jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, created_at, expires_at) FROM email_verification_codes WHERE email = ?""",
                Long.class, email)).isBetween(179L, 181L);
        sendCode(email, address(1)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(matchesPattern("Vui lòng đợi (89|90|91) giây rồi gửi lại mã\\.")));

        String code = codeSentTo(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        verifyCode(email, wrong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mã xác thực không đúng. Bạn còn 1 lần thử."));
        verifyCode(email, wrong).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mã xác thực không đúng. Hãy bấm Gửi lại mã."));
        verifyCode(email, code).andExpect(status().isTooManyRequests());

        String other = "set2" + tag + "@signup.test";
        sendCode(other, address(2)).andExpect(status().isOk());
        verifyCode(other, codeSentTo(other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInMinutes").value(5));
        assertThat(jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND, verified_at, ticket_expires_at) FROM email_verification_codes
                WHERE email = ?""", Long.class, other)).isBetween(299L, 301L);
    }

    // ---------------------------------------------------------------- account deletion (S13)

    @Test
    void deletingTheAccountClosesTagsPointsAndProofsAndFreesTheEmail() throws Exception {
        String email = "del" + tag + "@signup.test";
        complete(ticket(email, address(1)), "Del Test", PASSWORD).andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        otherUsers.add(id);   // its email changes when it is deleted

        long kouhai = tagType("Kouhai");
        long mentor = tagType("Mentor");
        jdbc.update("INSERT INTO user_tags (user_id, tag_type_id, granted_by) VALUES (?, ?, ?)", id, kouhai, id);
        String pendingKey = "proofs/2026/10/" + UUID.randomUUID() + ".jpg";
        String approvedKey = "proofs/2026/10/" + UUID.randomUUID() + ".jpg";
        long pendingProof = proof(id, pendingKey);
        long approvedProof = proof(id, approvedKey);
        long pending = tagRequest(id, mentor, pendingProof, "PENDING");
        long approved = tagRequest(id, kouhai, approvedProof, "APPROVED");
        jdbc.update("UPDATE users SET points = 50 WHERE id = ?", id);

        MockHttpSession session = (MockHttpSession) login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
        mvc.perform(withCsrf(delete("/api/me")).session(session)).andExpect(status().isOk());

        Map<String, Object> user = jdbc.queryForMap("SELECT status, points, email FROM users WHERE id = ?", id);
        assertThat(user).containsEntry("status", "DELETED").containsEntry("points", 0)
                .containsEntry("email", "deleted-" + id + "@deleted.invalid");
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM user_tags WHERE user_id = ?", Timestamp.class, id))
                .isNotNull();
        Map<String, Object> closed = jdbc.queryForMap(
                "SELECT status, reject_reason, reviewed_at FROM tag_requests WHERE id = ?", pending);
        assertThat(closed).containsEntry("status", "REJECTED").containsEntry("reject_reason", "Tài khoản đã xóa");
        assertThat(closed.get("reviewed_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM tag_requests WHERE id = ?", String.class, approved))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media WHERE id IN (?, ?) AND deleted_at IS NOT NULL",
                Long.class, pendingProof, approvedProof)).isEqualTo(2);
        assertThatThrownBy(() -> storage.get(pendingKey)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> storage.get(approvedKey)).isInstanceOf(RuntimeException.class);

        // The email is free: the same person signs up again (the first code is older than the resend wait by now)
        jdbc.update("UPDATE email_verification_codes SET created_at = created_at - INTERVAL 2 MINUTE WHERE email = ?",
                email);
        complete(ticket(email, address(2)), "Del Again", "matkhau456").andExpect(status().isOk());
        long again = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(again).isNotEqualTo(id);
        login(email, "matkhau456").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    /** A made-up client address, different for every test. */
    private String address(int n) {
        return "2001:db8:" + tag.substring(0, 4) + ":" + tag.substring(4) + "::" + n;
    }

    private ResultActions sendCode(String email, String address) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/register/send-code"))
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .content(json.writeValueAsString(Map.of("email", email))));
    }

    private ResultActions verifyCode(String email, String code) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/register/verify-code"))
                .content(json.writeValueAsString(Map.of("email", email, "code", code))));
    }

    private ResultActions complete(String ticket, String displayName, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticket);
        body.put("displayName", displayName);
        body.put("password", password);
        body.put("adultConfirmed", true);
        body.put("acceptTerms", true);
        return mvc.perform(withCsrf(post("/api/auth/register/complete")).content(json.writeValueAsString(body)));
    }

    private ResultActions completeSocial(MockHttpSession session, String displayName) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/social/complete")).session(session).content(json.writeValueAsString(
                Map.of("displayName", displayName, "adultConfirmed", true, "acceptTerms", true))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(withCsrf(post("/api/auth/login"))
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    /** Sends a code, verifies it and returns the ticket of the password step. */
    private String ticket(String email, String address) throws Exception {
        sendCode(email, address).andExpect(status().isOk());
        return JsonPath.read(verifyCode(email, codeSentTo(email)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.ticket");
    }

    private Invocation lastMailTo(String email) {
        List<Invocation> sends = mockingDetails(mail).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send") && email.equals(i.getArgument(0)))
                .toList();
        assertThat(sends).as("emails sent to " + email).isNotEmpty();
        return sends.get(sends.size() - 1);
    }

    /** The code in the subject of the last email sent to this address. */
    private String codeSentTo(String email) {
        String subject = lastMailTo(email).getArgument(1);
        return subject.substring(subject.length() - 6);
    }

    private long sentTo(String email) {
        return mockingDetails(mail).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send") && email.equals(i.getArgument(0)))
                .count();
    }

    private long codes(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_codes WHERE email = ?", Long.class, email);
    }

    private void insertCode(String email, Instant createdAt) {
        jdbc.update("""
                INSERT INTO email_verification_codes (email, purpose, code_hash, expires_at, created_at)
                VALUES (?, 'REGISTER', ?, ?, ?)""", email, "0".repeat(64),
                Timestamp.from(createdAt.plus(Duration.ofMinutes(10))), Timestamp.from(createdAt));
    }

    private void setting(String key, String value) {
        changedSettings.putIfAbsent(key, jdbc.queryForObject(
                "SELECT setting_value FROM system_settings WHERE setting_key = ?", String.class, key));
        jdbc.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = ?", value, key);
    }

    private long user(String email) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, display_name, role, status, is_adult_confirmed)
                VALUES (?, NULL, 'Taken', 'USER', 'ACTIVE', TRUE)""", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long tagType(String name) {
        return jdbc.queryForObject("SELECT id FROM tag_types WHERE name = ?", Long.class, name);
    }

    private long proof(long owner, String key) {
        storage.put(key, new byte[]{1, 2, 3});
        jdbc.update("""
                INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, scan_status)
                VALUES (?, 'IMAGE', ?, 'image/jpeg', 3, 'PASSED')""", owner, key);
        return jdbc.queryForObject("SELECT id FROM media WHERE storage_key = ?", Long.class, key);
    }

    private long tagRequest(long userId, long tagType, long proofMedia, String status) {
        long proofType = jdbc.queryForObject("SELECT MIN(id) FROM tag_proof_types WHERE tag_type_id = ?", Long.class,
                tagType);
        jdbc.update("""
                INSERT INTO tag_requests (user_id, tag_type_id, proof_type_id, proof_media_id, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)""", userId, tagType, proofType, proofMedia, status,
                Timestamp.from(Instant.now().plus(Duration.ofDays(14))));
        return jdbc.queryForObject("SELECT MAX(id) FROM tag_requests WHERE user_id = ?", Long.class, userId);
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
