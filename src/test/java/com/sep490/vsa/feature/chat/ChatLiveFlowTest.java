package com.sep490.vsa.feature.chat;

import com.sep490.vsa.common.live.LiveEvents;
import com.sep490.vsa.feature.moderation.ImageModerator;
import com.sep490.vsa.feature.notification.NotificationService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The live update stream ({@code GET /api/me/live}, common/live/LiveEvents) for "Tin nhắn" and the bell: each change
 * reaches at once the members it shows for, with ids only; what the other member must not learn (a request read
 * before it is accepted, a decline, a block) never reaches them; nothing is announced for a rollback.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ChatLiveFlowTest extends ChatTestBase {

    /** Events go out on LiveEvents' own threads: how long a test waits for one. */
    private static final long WAIT_MS = 5000;
    private static final long SETTLE_MS = 200;

    @MockBean
    private ImageModerator moderator;
    @Autowired
    private LiveEvents live;
    @Autowired
    private NotificationService notifications;
    @Autowired
    private TransactionTemplate transactions;

    private final List<Long> listening = new ArrayList<>();

    @AfterEach
    void closeStreams() {
        listening.forEach(live::close);
    }

    @Test
    void guestsGetNoStream() throws Exception {
        mvc.perform(get("/api/me/live")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMessageReachesBothMembersAtOnceWithIdsOnly() throws Exception {
        long an = user("An");
        long binh = user("Bình");
        MvcResult anStream = stream(an);
        MvcResult binhStream = stream(binh);
        assertThat(content(binhStream)).contains("event:ready");

        long conversation = request(an, binh, "Chào Bình, mình hỏi về nhà trọ gần ga nhé");

        String event = "\"conversationId\":" + conversation;
        String received = await(binhStream, event);
        assertThat(received).contains("event:chat", "\"kind\":\"message\"", "\"messageId\":")
                .doesNotContain("nhà trọ");                       // never the text
        assertThat(await(anStream, event)).contains("\"kind\":\"message\"");   // the writer's other pages
    }

    @Test
    void readingARequestIsNotAnnouncedToItsWriterUntilItIsAccepted() throws Exception {
        long an = user("An");
        long binh = user("Bình");
        long conversation = request(an, binh, "Chào Bình, mình là An");
        MvcResult anStream = stream(an);
        MvcResult binhStream = stream(binh);

        call(post(API + "/" + conversation + "/read"), binh, Map.of()).andExpect(status().isOk());
        await(binhStream, "\"kind\":\"read\"");                    // Bình's own pages (the counts)
        settle(an, anStream);
        assertThat(content(anStream)).doesNotContain("event:chat");

        accept(binh, conversation);
        await(anStream, "\"kind\":\"status\"");                    // accepting shows: "Đã xem", encryption
        call(post(API + "/" + conversation + "/read"), binh, Map.of()).andExpect(status().isOk());
        await(anStream, "\"kind\":\"read\"");                      // now "Đã xem" shows for An
    }

    @Test
    void declinesAndBlocksNeverReachTheOtherMember() throws Exception {
        long an = user("An");
        long binh = user("Bình");
        long chi = user("Chi");
        long declined = request(an, binh, "Chào Bình, mình là An");
        long blocked = request(chi, binh, "Chào Bình, mình là Chi");
        MvcResult anStream = stream(an);
        MvcResult chiStream = stream(chi);
        MvcResult binhStream = stream(binh);

        mvc.perform(withCsrf(post(API + "/" + declined + "/decline"), binh)).andExpect(status().isOk());
        mvc.perform(withCsrf(post(API + "/blocks/" + chi), binh)).andExpect(status().isOk());
        await(binhStream, "\"conversationId\":" + declined);
        await(binhStream, "\"conversationId\":" + blocked);

        settle(an, anStream);
        settle(chi, chiStream);
        assertThat(content(anStream)).doesNotContain("event:chat");
        assertThat(content(chiStream)).doesNotContain("event:chat");
    }

    @Test
    void typingReachesOnlyTheOtherMember() throws Exception {
        long an = user("An");
        long binh = user("Bình");
        long conversation = accepted(an, binh);
        MvcResult anStream = stream(an);
        MvcResult binhStream = stream(binh);

        mvc.perform(withCsrf(post(API + "/" + conversation + "/typing"), an)).andExpect(status().isOk());
        await(binhStream, "\"kind\":\"typing\"");
        settle(an, anStream);
        assertThat(content(anStream)).doesNotContain("\"kind\":\"typing\"");
    }

    @Test
    void editsAndReactionsReachBothMembers() throws Exception {
        long an = user("An");
        long binh = user("Bình");
        long conversation = accepted(an, binh);
        long message = id(sendPlain(an, conversation, "Mai mình gặp ở ga Ueno nhé"), "$.data.message.id");
        MvcResult anStream = stream(an);
        MvcResult binhStream = stream(binh);

        call(put(API + "/" + conversation + "/messages/" + message), an, Map.of("content", "Mai mình gặp ở ga Nippori nhé"))
                .andExpect(status().isOk());
        String edited = "\"messageId\":" + message;
        assertThat(await(binhStream, edited)).contains("\"kind\":\"changed\"").doesNotContain("Nippori");

        call(put(API + "/" + conversation + "/messages/" + message + "/reaction"), binh, Map.of("emoji", "👍"))
                .andExpect(status().isOk());
        assertThat(await(anStream, edited)).contains("\"kind\":\"changed\"");
    }

    @Test
    void aNotificationIsAnnouncedAfterItsCommitAndNeverForARollback() throws Exception {
        long an = user("An");
        MvcResult anStream = stream(an);

        transactions.executeWithoutResult(tx -> {
            notifications.send(an, NotificationService.NEW_COMMENT, "Bị hủy", null, null);
            tx.setRollbackOnly();
        });
        transactions.executeWithoutResult(tx -> {
            notifications.send(an, NotificationService.BEST_ANSWER, "Được chọn", null, null);
            // not yet: the row is not committed
            assertThat(content(anStream)).doesNotContain("event:notification");
        });
        await(anStream, "event:notification");
        Thread.sleep(SETTLE_MS);
        String received = content(anStream);
        assertThat(received.split("event:notification", -1)).hasSize(2);   // exactly one: the committed one
        assertThat(received).doesNotContain("Được chọn");
    }

    @Test
    void aClosedStreamEndsNormally() throws Exception {
        long an = user("An");
        MvcResult anStream = stream(an);
        live.close(an);
        mvc.perform(asyncDispatch(anStream)).andExpect(status().isOk());
    }

    @Test
    void aBrowserThatWentAwayIsNotAnError() throws Exception {
        long an = user("An");
        MvcResult anStream = stream(an);
        // What Tomcat reports when the page was closed or reloaded while an event was on its way
        MockHttpServletRequest request = anStream.getRequest();
        MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
        IOException aborted = new IOException("An established connection was aborted by the software in your host machine");
        for (AsyncListener listener : context.getListeners()) {
            listener.onError(new AsyncEvent(context, request, anStream.getResponse(), aborted));
        }
        mvc.perform(asyncDispatch(anStream)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult stream(long userId) throws Exception {
        listening.add(userId);
        return mvc.perform(get("/api/me/live").session(session(userId)))
                .andExpect(MockMvcResultMatchers.request().asyncStarted())   // request(...) is ChatTestBase's
                .andReturn();
    }

    /**
     * Before checking that something did NOT reach a member: tells them something else and waits for it, then a little
     * longer (events go out on two threads), so whatever was sent before has surely arrived.
     */
    private void settle(long userId, MvcResult stream) throws Exception {
        int before = content(stream).split("event:notification", -1).length;
        notifications.send(userId, NotificationService.NEW_COMMENT, "Có bình luận mới", null, null);
        long until = System.currentTimeMillis() + WAIT_MS;
        while (content(stream).split("event:notification", -1).length == before) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("The notification never reached the stream:\n" + content(stream));
            }
            Thread.sleep(20);
        }
        Thread.sleep(SETTLE_MS);
    }

    private static String content(MvcResult stream) {
        return new String(stream.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** The stream's content once it holds {@code text}; fails after WAIT_MS. */
    private static String await(MvcResult stream, String text) throws Exception {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (true) {
            String content = content(stream);
            if (content.contains(text)) {
                return content;
            }
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("No \"" + text + "\" on the stream within " + WAIT_MS + " ms:\n" + content);
            }
            Thread.sleep(20);
        }
    }
}
