package com.sep490.vsa.feature.coin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.quest.QuestTestSupport;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Helpers of the "xu" flow tests, on their own database {@code asvsj_coin_test} (one Spring context for every class
 * of this package). Xu are given through the book ({@link CoinLedger#add}, reason DEV_ADJUST), so the balance always
 * equals the sum of the Member's coin_transactions.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_coin_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
abstract class CoinTestSupport extends QuestTestSupport {

    static final String EXCHANGE_URL = "/api/me/coins/exchange";
    static final String INVALID = "Hãy chọn số điểm muốn đổi (ít nhất 1 điểm).";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected CoinLedger ledger;
    @Autowired
    protected CoinService coins;
    @Autowired
    protected CoinSettings coinSettings;

    /** Gives xu through the book (a DEV_ADJUST row, "Điều chỉnh khi thử"). */
    protected void give(long userId, int xu) {
        inTransaction(() -> ledger.add(userId, xu, CoinLedger.DEV_ADJUST, "Điều chỉnh khi thử", null, null, null));
    }

    protected ResultActions exchange(long userId, int points) throws Exception {
        return postAs(userId, EXCHANGE_URL, "{\"points\":" + points + "}");
    }

    protected ResultActions exchange(long userId, String json) throws Exception {
        return postAs(userId, EXCHANGE_URL, json);
    }

    /** POST /api/me/coins/exchange as a Member without any body. */
    protected ResultActions exchangeWithoutBody(long userId) throws Exception {
        MockHttpServletRequestBuilder request = post(EXCHANGE_URL).session(session(userId, "USER"))
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(request);
    }

    /** 0:00 Japan time on the 1st of the month after this Japan date. */
    protected static Instant monthResetAfter(LocalDate day) {
        return YearMonth.from(day).plusMonths(1).atDay(1).atStartOfDay(JAPAN).toInstant();
    }

    protected int coinBookSum(long userId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta), 0) FROM coin_transactions WHERE user_id = ?",
                Integer.class, userId);
    }

    protected int pointBookSum(long userId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta), 0) FROM point_transactions WHERE user_id = ?",
                Integer.class, userId);
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected static AppUserPrincipal principal(long id, String role) {
        return new AppUserPrincipal(id, "u" + id + "@test.local", "Coin Test", role, "/");
    }

    protected static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(body(result));
    }

    protected static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
