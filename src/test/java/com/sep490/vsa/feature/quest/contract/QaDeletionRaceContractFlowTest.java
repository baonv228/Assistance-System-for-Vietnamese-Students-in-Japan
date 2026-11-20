package com.sep490.vsa.feature.quest.contract;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 1 ("chỉ thành viên đang hoạt động" have quests and xu) and section 4 ("Xóa tài khoản": coins 0, quests, done
 * days and the answer log deleted) when a Member's request is already past the sign-in checks while "Xóa tài khoản"
 * holds the users row: the request queues behind the deletion on that row (3.7: every write locks users first) and
 * must not bring quest data or xu back to the deleted account.
 * <p>
 * The interleaving is made certain, not left to timing: the test holds the users row (as any other writer of that
 * row may), lets the deletion queue first, then the Member's request, then lets go.
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaDeletionRaceContractFlowTest extends QaContractSupport {

    @Test
    void aFlashAnswerQueuedBehindXoaTaiKhoanBringsNothingBack() throws Exception {
        freshDay();
        long m = member();
        long only = phrase("konbini", "VOCAB");
        isolateLibrary();
        clock.advance(GAP);
        Outcome o = queueBehindDeletion(m, () -> flash(m, only, true));
        expectNothingLeft(m, o, "flash answer");
    }

    @Test
    void openingTheQuestsQueuedBehindXoaTaiKhoanBringsNothingBack() throws Exception {
        freshDay();
        long m = member();
        phrase("konbini", "VOCAB");
        isolateLibrary();
        Outcome o = queueBehindDeletion(m, () -> getAs(m, "/api/me/quests/today"));
        expectNothingLeft(m, o, "GET today");
    }

    /** Control: events lock the users row through the Member check itself. */
    @Test
    void aListenQueuedBehindXoaTaiKhoanBringsNothingBack() throws Exception {
        freshDay();
        long m = member();
        payLibrary(5, m);
        long p = homePhraseId();
        Outcome o = queueBehindDeletion(m, () -> listenRaw(m, p));
        expectNothingLeft(m, o, "LISTEN");
    }

    // ------------------------------------------------------------ helpers

    private record Outcome(int deleteStatus, int requestStatus, String requestBody) {
    }

    private Outcome queueBehindDeletion(long userId, Callable<ResultActions> request) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(st -> {
                jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> deletion = pool.submit(() -> deleteAs(userId, "/api/me").andReturn().getResponse()
                    .getStatus());
            // "Xóa tài khoản" reaches its UPDATE of the users row and waits there (a thread dump shows it in
            // ProfileService.deleteAccount at "UPDATE users SET points = 0, coins = 0 …"); then the Member's request
            // passes its sign-in and Member checks and queues behind it.
            Thread.sleep(1500);
            assertThat(deletion.isDone()).as("the deletion waits for the users row").isFalse();
            Future<String[]> member = pool.submit(() -> {
                var r = request.call().andReturn().getResponse();
                return new String[]{String.valueOf(r.getStatus()), r.getContentAsString(StandardCharsets.UTF_8)};
            });
            Thread.sleep(1500);
            assertThat(member.isDone()).as("the Member's request waits for the users row").isFalse();
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            int deleteStatus = deletion.get(30, TimeUnit.SECONDS);
            String[] answer = member.get(30, TimeUnit.SECONDS);
            return new Outcome(deleteStatus, Integer.parseInt(answer[0]), answer[1]);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void expectNothingLeft(long userId, Outcome o, String what) {
        SoftAssertions s = new SoftAssertions();
        s.assertThat(o.deleteStatus()).as("Xóa tài khoản").isEqualTo(200);
        s.assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("DELETED");
        String seen = what + " answered " + o.requestStatus() + " " + o.requestBody();
        s.assertThat(coinsOf(userId)).as("xu of the deleted account; " + seen).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", userId))
                .as("quests of the deleted account; " + seen).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", userId))
                .as("done days of the deleted account; " + seen).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM phrase_answer_log WHERE user_id = ?", userId))
                .as("answer log of the deleted account; " + seen).isZero();
        s.assertThat(count("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", userId))
                .as("xu lines written for the deleted account; " + seen).isZero();
        s.assertAll();
    }
}
