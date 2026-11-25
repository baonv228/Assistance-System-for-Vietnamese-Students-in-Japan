package com.sep490.vsa.feature.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The one-time codes of the app's Google/Facebook sign-in, and the PKCE S256 check, without Spring or a database. */
class SocialAppCodesTest {

    /** RFC 7636 appendix B. */
    private static final String RFC_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String RFC_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CHALLENGE = RFC_CHALLENGE;
    private static final PendingSocialSignup PROFILE =
            new PendingSocialSignup("GOOGLE", "g-1", "a@gmail.test", "Nguyen Van A", null);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-21T03:00:00Z"));
    private final SocialAppCodes codes = new SocialAppCodes(clock);

    @Test
    void theS256CheckFollowsRfc7636() {
        assertThat(SocialAppCodes.s256(RFC_VERIFIER)).isEqualTo(RFC_CHALLENGE);
        assertThat(SocialAppCodes.verifierMatches(RFC_VERIFIER, RFC_CHALLENGE)).isTrue();

        assertThat(SocialAppCodes.verifierMatches(RFC_VERIFIER.replace('d', 'e'), RFC_CHALLENGE)).isFalse();
        assertThat(SocialAppCodes.verifierMatches(RFC_VERIFIER, RFC_CHALLENGE.replace('E', 'F'))).isFalse();
        // "plain" PKCE (the verifier itself as the challenge) is not accepted
        assertThat(SocialAppCodes.verifierMatches(RFC_VERIFIER, RFC_VERIFIER)).isFalse();
        assertThat(SocialAppCodes.verifierMatches(null, RFC_CHALLENGE)).isFalse();
        assertThat(SocialAppCodes.verifierMatches(RFC_VERIFIER, null)).isFalse();
    }

    @Test
    void aCodeIs43RandomBase64urlCharactersAndWorksOnce() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = codes.issue(CHALLENGE, PROFILE).orElseThrow();
            assertThat(code).matches("[A-Za-z0-9_-]{43}");
            assertThat(seen.add(code)).isTrue();
        }

        String code = codes.issue(CHALLENGE, PROFILE).orElseThrow();
        Optional<SocialAppCodes.Ticket> ticket = codes.take(code);
        assertThat(ticket).isPresent();
        assertThat(ticket.get().challenge()).isEqualTo(CHALLENGE);
        assertThat(ticket.get().profile()).isEqualTo(PROFILE);
        assertThat(ticket.get().issuedAt()).isEqualTo(clock.instant());

        assertThat(codes.take(code)).isEmpty();
        assertThat(codes.take("never-issued")).isEmpty();
        assertThat(codes.take(null)).isEmpty();
    }

    @Test
    void aCodeLivesFiveMinutes() {
        String early = codes.issue(CHALLENGE, PROFILE).orElseThrow();
        String late = codes.issue(CHALLENGE, PROFILE).orElseThrow();

        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertThat(codes.take(early)).isPresent();

        clock.advance(Duration.ofMillis(1));   // exactly five minutes
        assertThat(codes.take(late)).isEmpty();
        // taken out for good: turning the clock back does not bring it back
        clock.advance(Duration.ofMinutes(-10));
        assertThat(codes.take(late)).isEmpty();
    }

    @Test
    void atMost5000CodesWaitAndExpiredOnesMakeRoom() {
        List<String> issued = new ArrayList<>();
        for (int i = 0; i < SocialAppCodes.MAX_LIVE; i++) {
            issued.add(codes.issue(CHALLENGE, PROFILE).orElseThrow());
        }
        assertThat(codes.issue(CHALLENGE, PROFILE)).isEmpty();

        // a used code makes room for one more
        assertThat(codes.take(issued.get(0))).isPresent();
        String one = codes.issue(CHALLENGE, PROFILE).orElseThrow();
        assertThat(codes.issue(CHALLENGE, PROFILE)).isEmpty();

        // once they expire, the next issue drops them all
        clock.advance(SocialAppCodes.TTL);
        String fresh = codes.issue(CHALLENGE, PROFILE).orElseThrow();
        for (int i = 1; i < SocialAppCodes.MAX_LIVE; i++) {
            assertThat(codes.issue(CHALLENGE, PROFILE)).isPresent();
        }
        assertThat(codes.issue(CHALLENGE, PROFILE)).isEmpty();
        assertThat(codes.take(one)).isEmpty();
        assertThat(codes.take(fresh)).isPresent();
    }

    @Test
    void codesTakenOrIssuedTogetherStayExact() throws Exception {
        // one code, eight takers at once: exactly one gets it
        for (int round = 0; round < 20; round++) {
            String code = codes.issue(CHALLENGE, PROFILE).orElseThrow();
            List<Callable<Boolean>> takers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                takers.add(() -> codes.take(code).isPresent());
            }
            assertThat(together(takers)).filteredOn(Boolean::booleanValue).hasSize(1);
        }

        // 8 × 700 issued at once into an empty store: exactly 5000 get a code
        SocialAppCodes empty = new SocialAppCodes(clock);
        List<Callable<Integer>> issuers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            issuers.add(() -> {
                int got = 0;
                for (int n = 0; n < 700; n++) {
                    if (empty.issue(CHALLENGE, PROFILE).isPresent()) {
                        got++;
                    }
                }
                return got;
            });
        }
        assertThat(together(issuers).stream().mapToInt(Integer::intValue).sum()).isEqualTo(SocialAppCodes.MAX_LIVE);
    }

    private static <T> List<T> together(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A clock moved by hand. */
    private static final class MovableClock extends Clock {

        private final AtomicReference<Instant> now;

        MovableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
