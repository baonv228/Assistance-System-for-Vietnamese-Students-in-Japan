package com.sep490.vsa.feature.quest.contract;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 5.1 "API này được gọi mỗi lần mở app hoặc mở lại từ nền, nên phải nhanh" and 3.3 (the phrase of the day kept in a
 * transaction of its own): many apps opening at the same moment (the start of a Japan day, a class all opening the
 * app) must all be answered, quickly. The application runs with Spring Boot's default connection pool (10).
 */
@TestPropertySource(properties = QaContractSupport.QA_DB)
class QaLoadContractFlowTest extends QaContractSupport {

    private static final int APPS = 16;

    @Test
    void manyGuestHomesAtOnceAreAllAnswered() throws Exception {
        freshDay();
        payLibrary(5);
        List<Callable<ResultActions>> calls = new ArrayList<>();
        for (int i = 0; i < APPS; i++) {
            calls.add(() -> guestGet("/api/home"));
        }
        expectAllQuick(calls, "GET /api/home");
    }

    @Test
    void manyMemberHomesAtOnceAreAllAnswered() throws Exception {
        freshDay();
        long[] members = new long[APPS];
        for (int i = 0; i < APPS; i++) {
            members[i] = member();
        }
        payLibrary(5, members);
        List<Callable<ResultActions>> calls = new ArrayList<>();
        for (long m : members) {
            calls.add(() -> getAs(m, "/api/me/home"));
        }
        expectAllQuick(calls, "GET /api/me/home");
    }

    @Test
    void manyFirstOpeningsOfTheQuestsAtOnceAreAllAnswered() throws Exception {
        freshDay();
        long[] members = new long[APPS];
        for (int i = 0; i < APPS; i++) {
            members[i] = member();
        }
        payLibrary(5, members);
        List<Callable<ResultActions>> calls = new ArrayList<>();
        for (long m : members) {
            calls.add(() -> getAs(m, "/api/me/quests/today"));
        }
        expectAllQuick(calls, "GET /api/me/quests/today (first of the day)");
    }

    /** Control: endpoints of "Nhật ngữ" that existed before the feature, as many at once. */
    @Test
    void controlManyOlderPhraseRequestsAtOnceAreAllAnswered() throws Exception {
        freshDay();
        long[] members = new long[APPS];
        for (int i = 0; i < APPS; i++) {
            members[i] = member();
        }
        payLibrary(5, members);
        List<Callable<ResultActions>> calls = new ArrayList<>();
        for (int i = 0; i < APPS; i++) {
            long m = members[i];
            calls.add(i % 2 == 0 ? () -> guestGet("/api/phrases/overview") : () -> getAs(m, "/api/phrases"));
        }
        expectAllQuick(calls, "GET /api/phrases/overview | /api/phrases (control)");
    }

    @SuppressWarnings("unchecked")
    private void expectAllQuick(List<Callable<ResultActions>> calls, String what) throws Exception {
        long start = System.nanoTime();
        List<Reply> replies = parallel(calls.toArray(new Callable[0]));
        long millis = (System.nanoTime() - start) / 1_000_000;
        SoftAssertions s = new SoftAssertions();
        int i = 0;
        for (Reply r : replies) {
            s.assertThat(r.status()).as(what + " #" + i++ + " of " + replies.size() + " at once; body "
                    + r.body().toString().substring(0, Math.min(300, r.body().toString().length()))).isEqualTo(200);
        }
        s.assertThat(millis).as(what + ": " + replies.size() + " at once took " + millis + " ms")
                .isLessThan(10_000);
        s.assertAll();
    }
}
