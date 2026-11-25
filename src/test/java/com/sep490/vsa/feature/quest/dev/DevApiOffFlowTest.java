package com.sep490.vsa.feature.quest.dev;

import com.sep490.vsa.feature.quest.QuestTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The test-only API while its flag is off, on a database of this machine (contract 3.9: "Ở mọi trường hợp khác, các
 * đường dẫn /api/dev/** trả 404"): none of its beans exists, and every /api/dev/** request answers 404 with an empty
 * body, as an unknown address does, to a Member, a staff account and a guest, with or without the CSRF header. Nothing
 * is changed.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=" + DevApiTestSupport.DB_URL,
        "app.quests.dev-api.enabled=false"
})
class DevApiOffFlowTest extends QuestTestSupport {

    private record Call(HttpMethod method, String url, String body) {
    }

    private static final List<Call> CALLS = List.of(
            new Call(HttpMethod.DELETE, "/api/dev/quests/today", null),
            new Call(HttpMethod.POST, "/api/dev/quests/history", "{\"doneDays\":6}"),
            new Call(HttpMethod.PUT, "/api/dev/quests/today", "{\"codes\":[\"FLASH_REVIEW\"]}"),
            new Call(HttpMethod.PUT, "/api/dev/coins", "{\"coins\":600,\"clearMonthExchanges\":true}"),
            new Call(HttpMethod.GET, "/api/dev/quests/today", null),
            new Call(HttpMethod.GET, "/api/dev", null),
            new Call(HttpMethod.POST, "/api/dev/anything/else", "{}"));

    @Autowired
    private ApplicationContext context;

    @Test
    void theApiIsNotRegistered() {
        assertThat(context.getBeanNamesForType(DevQuestController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DevQuestService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DevSampleMarks.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DevApiClosedFilter.class)).hasSize(1);
        assertThat(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet())
                .noneMatch(info -> info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/dev")));
    }

    @Test
    void aMemberGets404OnEveryPathAndNothingChanges() throws Exception {
        freshDay();
        long member = member();
        for (Call call : CALLS) {
            MockHttpServletResponse response = send(call, member, "USER", true);
            assertThat(response.getStatus()).as(call.toString()).isEqualTo(404);
            assertThat(response.getContentAsString()).as(call.toString()).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_daily_quests WHERE user_id = ?", Integer.class,
                member)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_quest_days WHERE user_id = ?", Integer.class,
                member)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coin_transactions WHERE user_id = ?", Integer.class,
                member)).isZero();
        assertThat(coinsOf(member)).isZero();
    }

    @Test
    void staffAndGuestsGet404TooWithOrWithoutTheCsrfHeader() throws Exception {
        freshDay();
        long member = member();
        long moderator = user("MODERATOR", "ACTIVE");
        for (Call call : CALLS) {
            assertThat(send(call, moderator, "MODERATOR", true).getStatus()).as("moderator " + call).isEqualTo(404);
            assertThat(send(call, null, null, true).getStatus()).as("guest " + call).isEqualTo(404);
            assertThat(send(call, null, null, false).getStatus()).as("guest without CSRF " + call).isEqualTo(404);
            assertThat(send(call, member, "USER", false).getStatus()).as("member without CSRF " + call).isEqualTo(404);
        }
    }

    @Test
    void theAnswerIsTheOneOfAnAddressThatNeverExisted() throws Exception {
        freshDay();
        long member = member();
        MockHttpServletResponse unknown = send(new Call(HttpMethod.GET, "/api/no-such-feature/today", null), member,
                "USER", true);
        MockHttpServletResponse dev = send(new Call(HttpMethod.GET, "/api/dev/quests/today", null), member, "USER",
                true);
        assertThat(dev.getStatus()).isEqualTo(unknown.getStatus()).isEqualTo(404);
        assertThat(dev.getContentAsString()).isEqualTo(unknown.getContentAsString());
    }

    private MockHttpServletResponse send(Call call, Long userId, String role, boolean csrf) throws Exception {
        var builder = request(call.method(), call.url());
        if (csrf) {
            builder.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
        }
        if (userId != null) {
            builder.session(session(userId, role));
        }
        if (call.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(call.body());
        }
        return mvc.perform(builder).andReturn().getResponse();
    }
}
