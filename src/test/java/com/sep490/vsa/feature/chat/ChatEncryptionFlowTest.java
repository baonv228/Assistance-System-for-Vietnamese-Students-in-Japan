package com.sep490.vsa.feature.chat;

import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.feature.moderation.ImageModerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end encryption of "Tin nhắn" against MySQL: identity keys (validation, replacement, who may read them), the
 * mode of a conversation (plain for requests and while a member has no key, encrypted once both accepted and both
 * have a key), what each mode accepts, stale key ids, encrypted edits, and reports proved with the franking tag.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ChatEncryptionFlowTest extends ChatTestBase {

    @MockBean
    private ImageModerator moderator;

    // ---------------------------------------------------------------- the crypto checks themselves

    @Test
    void theFrankingTagMatchesTheSharedTestVector() {
        byte[] fk = Base64.getDecoder().decode("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=");
        byte[] plaintext = "{\"v\":1,\"text\":\"Xin chào\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(fk).hasSize(32).startsWith(1, 2, 3).endsWith(0x1f, 0x20);
        assertThat(Base64.getEncoder().encodeToString(plaintext)).isEqualTo("eyJ2IjoxLCJ0ZXh0IjoiWGluIGNow6BvIn0=");
        String tag = ChatCrypto.frankingTag(fk, 42, 7, plaintext);
        assertThat(tag).isEqualTo("78d4be3ee84351509e736a57ae1fd15ac9a7890e456f08197b931a5b2ac980f0");
        assertThat(ChatCrypto.sameTag("78d4be3ee84351509e736a57ae1fd15ac9a7890e456f08197b931a5b2ac980f0", tag)).isTrue();
        assertThat(ChatCrypto.sameTag("78d4be3ee84351509e736a57ae1fd15ac9a7890e456f08197b931a5b2ac980f1", tag)).isFalse();
        assertThat(ChatCrypto.sameTag(null, tag)).isFalse();
        // Another conversation or writer gives another tag
        assertThat(ChatCrypto.frankingTag(fk, 43, 7, plaintext)).isNotEqualTo(tag);
        assertThat(ChatCrypto.frankingTag(fk, 42, 8, plaintext)).isNotEqualTo(tag);
    }

    @Test
    void onlyPointsOnTheCurveAreKeys() throws Exception {
        byte[] point = newPublicPoint();
        assertThat(ChatCrypto.isP256Point(point)).isTrue();
        byte[] moved = point.clone();
        moved[64] ^= 1;                                             // y changed: no longer on the curve
        assertThat(ChatCrypto.isP256Point(moved)).isFalse();
        byte[] compressed = point.clone();
        compressed[0] = 0x02;
        assertThat(ChatCrypto.isP256Point(compressed)).isFalse();
        assertThat(ChatCrypto.isP256Point(Arrays.copyOf(point, 64))).isFalse();
        assertThat(ChatCrypto.isP256Point(null)).isFalse();
        // x = p is outside the field even though it is 32 bytes
        byte[] tooBig = point.clone();
        System.arraycopy(fixed(new BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)),
                0, tooBig, 1, 32);
        assertThat(ChatCrypto.isP256Point(tooBig)).isFalse();
        assertThat(ChatCrypto.base64("not base64!")).isNull();
        assertThat(ChatCrypto.base64("ab-_")).isNull();                // URL-safe letters are not the agreed form
    }

    // ---------------------------------------------------------------- keys

    @Test
    void keysAreCheckedAndANewOneReplacesTheOld() throws Exception {
        long me = user("Minh");
        read(API + "/keys/me", me).andExpect(status().isOk()).andExpect(jsonPath("$.data.key").value(nullValue()));

        byte[] point = newPublicPoint();
        for (String bad : List.of("not base64!", b64(Arrays.copyOf(point, 64)), b64(new byte[65]))) {
            Map<String, Object> body = keyBody(point);
            body.put("publicKey", bad);
            call(post(API + "/keys"), me, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ChatKeyService.PUBLIC_KEY_INVALID));
        }
        byte[] offCurve = point.clone();
        offCurve[64] ^= 1;
        Map<String, Object> notOnCurve = keyBody(offCurve);
        call(post(API + "/keys"), me, notOnCurve).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatKeyService.PUBLIC_KEY_INVALID));

        List<Map.Entry<String, Object>> badBackups = List.of(
                Map.entry("wrapIv", b64(new byte[11])),
                Map.entry("wrapIv", "%%%"),
                Map.entry("wrapSalt", b64(new byte[8])),
                Map.entry("wrapSalt", b64(new byte[49])),
                Map.entry("wrappedPrivateKey", b64(new byte[8])),
                Map.entry("wrappedPrivateKey", b64(new byte[601])),
                Map.entry("wrapIterations", 1000),
                Map.entry("wrapIterations", 6_000_000));
        for (Map.Entry<String, Object> bad : badBackups) {
            Map<String, Object> body = keyBody(point);
            body.put(bad.getKey(), bad.getValue());
            call(post(API + "/keys"), me, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ChatKeyService.BACKUP_INVALID));
        }
        Map<String, Object> noIterations = keyBody(point);
        noIterations.remove("wrapIterations");
        call(post(API + "/keys"), me, noIterations).andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM chat_keys WHERE user_id = ?", me)).isZero();

        Map<String, Object> good = keyBody(point);
        long first = id(call(post(API + "/keys"), me, good)
                .andExpect(jsonPath("$.data.publicKey").value(b64(point)))
                .andExpect(jsonPath("$.data.wrapIterations").value(600000)), "$.data.id");
        read(API + "/keys/me", me)
                .andExpect(jsonPath("$.data.key.id").value((int) first))
                .andExpect(jsonPath("$.data.key.wrappedPrivateKey").value(good.get("wrappedPrivateKey")))
                .andExpect(jsonPath("$.data.key.wrapSalt").value(good.get("wrapSalt")))
                .andExpect(jsonPath("$.data.key.wrapIv").value(good.get("wrapIv")));

        // A new key revokes the one before; the old one stays readable
        long second = newKey(me);
        read(API + "/keys/me", me).andExpect(jsonPath("$.data.key.id").value((int) second));
        read(API + "/keys/" + first, me).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value((int) me))
                .andExpect(jsonPath("$.data.revoked").value(true));
        read(API + "/keys/" + second, me).andExpect(jsonPath("$.data.revoked").value(false));
        assertThat(count("SELECT COUNT(*) FROM chat_keys WHERE user_id = ? AND revoked_at IS NULL", me)).isEqualTo(1);

        // At most five new keys a day
        for (int i = 2; i < ChatKeyService.KEYS_PER_DAY; i++) {
            newKey(me);
        }
        call(post(API + "/keys"), me, keyBody(newPublicPoint())).andExpect(status().isTooManyRequests());
        assertThat(count("SELECT COUNT(*) FROM chat_keys WHERE user_id = ?", me))
                .isEqualTo(ChatKeyService.KEYS_PER_DAY);

        // Members only
        long staff = user("Kiểm duyệt", "MODERATOR", 30);
        mvc.perform(get(API + "/keys/me").session(session(staff, "MODERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get(API + "/keys/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void aKeyIsShownToItsOwnerAndThePeopleTheyTalkTo() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long stranger = user("Người lạ");
        long keyA = newKey(a);
        request(a, b, "Chào Bình");

        read(API + "/keys/" + keyA, a).andExpect(status().isOk());
        read(API + "/keys/" + keyA, b).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value((int) a))
                .andExpect(jsonPath("$.data.publicKey").isString())
                .andExpect(jsonPath("$.data.wrappedPrivateKey").doesNotExist());
        read(API + "/keys/" + keyA, stranger).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ChatKeyService.KEY_NOT_FOUND));
        read(API + "/keys/999999999", a).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- the mode of a conversation

    @Test
    void aConversationIsEncryptedOnceBothAcceptedAndBothHaveAKey() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long keyA = newKey(a);
        long keyB = newKey(b);

        // Before the first message, and while it is a request: plain text only
        read(API + "/with/" + b, a)
                .andExpect(jsonPath("$.data.status.encryption.mode").value("PLAIN"))
                .andExpect(jsonPath("$.data.status.encryption.myKeyId").value((int) keyA))
                .andExpect(jsonPath("$.data.status.encryption.peerKeyId").value(nullValue()))
                .andExpect(jsonPath("$.data.status.encryption.notice.code").value("REQUEST_PLAIN"))
                .andExpect(jsonPath("$.data.status.canAttach").value(false))
                .andExpect(jsonPath("$.data.limits.reactions[1]").value("❤️"))
                .andExpect(jsonPath("$.data.limits.editMinutes").value(15))
                .andExpect(jsonPath("$.data.limits.filesMax").value(5));
        long conversation = request(a, b, "Chào Bình, mình là An");
        read(API + "/" + conversation, b)
                .andExpect(jsonPath("$.data.status.encryption.mode").value("PLAIN"))
                .andExpect(jsonPath("$.data.status.encryption.notice.code").value("REQUEST_PLAIN"))
                .andExpect(jsonPath("$.data.status.canReact").value(false));
        Sealed early = seal(conversation, a, keyA, keyB, "Tin mã hóa quá sớm");
        sendBody(a, conversation, early.body()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.NOT_ENCRYPTED));

        // Accepted by both, both with a key: encrypted only
        accept(b, conversation);
        read(API + "/" + conversation, a)
                .andExpect(jsonPath("$.data.status.encryption.mode").value("E2EE"))
                .andExpect(jsonPath("$.data.status.encryption.myKeyId").value((int) keyA))
                .andExpect(jsonPath("$.data.status.encryption.peerKeyId").value((int) keyB))
                .andExpect(jsonPath("$.data.status.encryption.peerPublicKey").isString())
                .andExpect(jsonPath("$.data.status.encryption.notice").value(nullValue()))
                .andExpect(jsonPath("$.data.status.canAttach").value(true))
                .andExpect(jsonPath("$.data.status.canReact").value(true));
        sendPlain(a, conversation, "Tin chữ thường").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.ENCRYPTED_ONLY));
        call(post(API + "/with/" + b + "/messages"), a, Map.of("content", "Tin chữ thường"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.ENCRYPTED_ONLY));
        Sealed both = seal(conversation, a, keyA, keyB, "Xin chào");
        Map<String, Object> withText = both.body();
        withText.put("content", "lộ chữ");
        sendBody(a, conversation, withText).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.ENCRYPTED_ONLY));

        // The envelope must have the agreed form
        for (Map.Entry<String, Object> bad : List.<Map.Entry<String, Object>>of(
                Map.entry("iv", b64(new byte[11])),
                Map.entry("ciphertext", b64(new byte[20])),
                Map.entry("ciphertext", b64(new byte[ChatService.CIPHERTEXT_MAX_BYTES + 1])),
                Map.entry("ciphertext", "***"),
                Map.entry("frankingTag", "AB".repeat(32)),
                Map.entry("frankingTag", "ab"))) {
            Sealed sealed = seal(conversation, a, keyA, keyB, "Sai dạng");
            sealed.envelope().put(bad.getKey(), bad.getValue());
            sendBody(a, conversation, sealed.body()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ChatService.BAD_ENVELOPE));
        }

        // The keys named must be the current ones
        sendBody(a, conversation, seal(conversation, a, keyB, keyB, "Sai khóa").body())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(ChatService.KEY_CHANGED));
        long newA = newKey(a);
        sendBody(a, conversation, seal(conversation, a, keyA, keyB, "Khóa cũ").body())
                .andExpect(status().isConflict());
        Sealed good = seal(conversation, a, newA, keyB, "Xin chào");
        String sent = body(sendBody(a, conversation, good.body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message.content").value(nullValue()))
                .andExpect(jsonPath("$.data.message.encrypted.senderKeyId").value((int) newA))
                .andExpect(jsonPath("$.data.message.encrypted.recipientKeyId").value((int) keyB))
                .andExpect(jsonPath("$.data.message.encrypted.frankingTag").value(good.envelope().get("frankingTag")))
                .andExpect(jsonPath("$.data.message.fileIds", empty())));
        long message = ((Number) JsonPath.read(sent, "$.data.message.id")).longValue();
        assertThat(jdbc.queryForMap("SELECT content, ciphertext, iv, franking_tag FROM chat_messages WHERE id = ?",
                message)).containsEntry("content", null).containsEntry("ciphertext", good.envelope().get("ciphertext"));

        // The other member gets the envelope, in the conversation and as the list's last message
        read(API + "/" + conversation, b)
                .andExpect(jsonPath("$.data.messages[1].id").value((int) message))
                .andExpect(jsonPath("$.data.messages[1].mine").value(false))
                .andExpect(jsonPath("$.data.messages[1].encrypted.ciphertext").value(good.envelope().get("ciphertext")));
        read(API, b)
                .andExpect(jsonPath("$.data.items[0].lastMessage.content").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].lastMessage.encrypted.iv").value(good.envelope().get("iv")))
                .andExpect(jsonPath("$.data.items[0].lastMessage.recalled").value(false));
        read(API + "/counts", b).andExpect(jsonPath("$.data.latest.messageId").value((int) message))
                .andExpect(jsonPath("$.data.latest.peerName").value("An"))
                .andExpect(jsonPath("$.data.latest.request").value(false));
    }

    @Test
    void anAcceptedConversationStaysPlainWhileAMemberHasNoKey() throws Exception {
        long withKey = user("Có khóa");
        long without = user("Chưa có khóa");
        long key = newKey(withKey);
        long conversation = accepted(withKey, without);

        read(API + "/" + conversation, withKey)
                .andExpect(jsonPath("$.data.status.encryption.mode").value("PLAIN"))
                .andExpect(jsonPath("$.data.status.encryption.myKeyId").value((int) key))
                .andExpect(jsonPath("$.data.status.encryption.peerKeyId").value(nullValue()))
                .andExpect(jsonPath("$.data.status.encryption.notice.code").value("NO_KEY_PEER"))
                .andExpect(jsonPath("$.data.status.encryption.notice.text")
                        .value(org.hamcrest.Matchers.startsWith("Chưa có khóa chưa bật mã hóa đầu cuối")))
                .andExpect(jsonPath("$.data.status.canAttach").value(false))
                .andExpect(jsonPath("$.data.status.canReact").value(true));
        read(API + "/" + conversation, without)
                .andExpect(jsonPath("$.data.status.encryption.mode").value("PLAIN"))
                .andExpect(jsonPath("$.data.status.encryption.peerKeyId").value((int) key))
                .andExpect(jsonPath("$.data.status.encryption.notice.code").value("NO_KEY_ME"));
        // v1 rules: plain text, filtered
        sendPlain(withKey, conversation, "Vẫn nhắn chữ được").andExpect(status().isOk());
        sendPlain(withKey, conversation, "Bên mình có việc nhẹ lương cao").andExpect(status().isBadRequest());
        sendBody(withKey, conversation, seal(conversation, withKey, key, key, "Mã hóa").body())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.NOT_ENCRYPTED));
        sendBody(withKey, conversation, Map.of("content", "Có ảnh", "fileIds", List.of(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.FILES_NEED_E2EE));

        // The second key turns the conversation to E2EE at once
        newKey(without);
        read(API + "/" + conversation, withKey).andExpect(jsonPath("$.data.status.encryption.mode").value("E2EE"));
    }

    @Test
    void anEncryptedMessageIsEditedWithANewEnvelope() throws Exception {
        Encrypted e = encrypted(user("An"), user("Bình"));
        long message = sendSealed(e, e.a(), "Hẹn 7 giờ", List.of());
        String oldCiphertext = jdbc.queryForObject("SELECT ciphertext FROM chat_messages WHERE id = ?", String.class,
                message);

        call(put(API + "/" + e.conversation() + "/messages/" + message), e.a(), Map.of("content", "Hẹn 8 giờ"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(ChatService.ENCRYPTED_ONLY));
        Sealed stale = seal(e.conversation(), e.a(), e.keyB(), e.keyB(), "Hẹn 8 giờ");
        call(put(API + "/" + e.conversation() + "/messages/" + message), e.a(), stale.body())
                .andExpect(status().isConflict());
        Sealed edit = seal(e.conversation(), e.a(), e.keyA(), e.keyB(), "Hẹn 8 giờ");
        call(put(API + "/" + e.conversation() + "/messages/" + message), e.a(), edit.body())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edited").value(true))
                .andExpect(jsonPath("$.data.encrypted.ciphertext").value(edit.envelope().get("ciphertext")));
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM chat_message_edits WHERE message_id = ?", String.class,
                message)).isEqualTo(oldCiphertext);
    }

    // ---------------------------------------------------------------- reports

    @Test
    void anEncryptedMessageIsReportedWithProofOfWhatItSaid() throws Exception {
        Encrypted e = encrypted(user("Chị tuyển dụng"), user("Thành viên"));
        long reason = jdbc.queryForObject("SELECT MIN(id) FROM reason_options WHERE scope = 'REPORT' AND is_active",
                Long.class);
        String text = "Em gửi chị ảnh thẻ cư trú nhé";
        Sealed sealed = seal(e.conversation(), e.a(), e.keyA(), e.keyB(), text);
        long message = id(sendBody(e.a(), e.conversation(), sealed.body()), "$.data.message.id");
        String reportUrl = API + "/messages/" + message + "/report";

        // Without proof, with another text, with another key: refused, nothing stored
        call(post(reportUrl), e.b(), Map.of("reasonId", reason)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.REPORT_UNVERIFIED));
        byte[] forged = sealed.plaintext().clone();
        forged[forged.length - 3] ^= 1;
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("reasonId", reason, "plaintext", b64(forged), "frankingKey", b64(sealed.frankingKey())),
                Map.<String, Object>of("reasonId", reason, "plaintext", b64(sealed.plaintext()), "frankingKey", b64(random(32))),
                Map.<String, Object>of("reasonId", reason, "plaintext", b64(sealed.plaintext()),
                        "frankingKey", b64(Arrays.copyOf(sealed.frankingKey(), 31))),
                Map.<String, Object>of("reasonId", reason, "plaintext", "@@@", "frankingKey", b64(sealed.frankingKey())))) {
            call(post(reportUrl), e.b(), bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ChatService.REPORT_UNVERIFIED));
        }
        assertThat(count("SELECT COUNT(*) FROM report_cases WHERE target_type = 'MESSAGE' AND target_id = ?", message))
                .isZero();

        Map<String, Object> proof = new HashMap<>();
        proof.put("reasonId", reason);
        proof.put("note", "Xin ảnh thẻ cư trú");
        proof.put("plaintext", b64(sealed.plaintext()));
        proof.put("frankingKey", b64(sealed.frankingKey()));
        call(post(reportUrl), e.b(), proof).andExpect(status().isOk());
        Map<String, Object> evidence = jdbc.queryForMap("""
                SELECT ev.plaintext, ev.message_id, r.reporter_id FROM chat_report_evidence ev
                JOIN reports r ON r.id = ev.report_id WHERE ev.message_id = ?""", message);
        assertThat(evidence.get("plaintext")).isEqualTo(new String(sealed.plaintext(), StandardCharsets.UTF_8));
        assertThat(((Number) evidence.get("reporter_id")).longValue()).isEqualTo(e.b());
        assertThat((String) evidence.get("plaintext")).contains(text);

        // Once only
        call(post(reportUrl), e.b(), proof).andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM chat_report_evidence WHERE message_id = ?", message)).isEqualTo(1);
    }

    @Test
    void deletingTheAccountRevokesItsKeyAndItsPushSubscriptions() throws Exception {
        Encrypted e = encrypted(user("An"), user("Bình"));
        jdbc.update("""
                INSERT INTO push_subscriptions (user_id, endpoint, endpoint_hash, p256dh, auth)
                VALUES (?, 'https://fcm.googleapis.com/fcm/send/test', SHA2(UUID(), 256), 'p', 'a')""", e.a());

        mvc.perform(withCsrf(delete("/api/me"), e.a())).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM chat_keys WHERE user_id = ? AND revoked_at IS NULL", e.a())).isZero();
        assertThat(count("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?", e.a())).isZero();
        // The other member can no longer write to it, and nothing is encrypted to the revoked key
        read(API + "/" + e.conversation(), e.b())
                .andExpect(jsonPath("$.data.status.notice.code").value("DELETED"))
                .andExpect(jsonPath("$.data.status.canSend").value(false))
                .andExpect(jsonPath("$.data.status.encryption.mode").value("PLAIN"));
    }
}
