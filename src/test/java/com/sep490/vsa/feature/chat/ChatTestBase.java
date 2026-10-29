package com.sep490.vsa.feature.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sep490.vsa.feature.media.MediaStorage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the phase-2 chat tests share: fresh members (removed again afterwards with their conversations, keys, files and
 * reports), signed-in requests, identity keys made like the browser makes them (a real P-256 point, backup fields of
 * the agreed sizes) and envelopes with a real franking tag. The server never decrypts, so the ciphertext is random
 * bytes of a valid size. Each test class carries the same Spring annotations as {@link ChatFlowTest}, so they all
 * share one context.
 */
abstract class ChatTestBase {

    static final String CSRF = "test-csrf-token";
    static final String API = "/api/me/chats";
    static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MediaStorage storage;

    final List<Long> users = new ArrayList<>();

    @AfterEach
    void cleanUpChat() {
        for (long id : users) {
            for (String key : jdbc.queryForList("SELECT storage_key FROM chat_files WHERE uploader_id = ?",
                    String.class, id)) {
                storage.delete(key);
            }
        }
        for (long id : users) {
            jdbc.update("""
                    DELETE FROM report_cases WHERE target_type = 'MESSAGE'
                    AND target_id IN (SELECT id FROM chat_messages WHERE sender_id = ?)""", id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM chat_conversations WHERE user_low_id = ? OR user_high_id = ?", id, id);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // ---------------------------------------------------------------- members and requests

    long user(String name) {
        return user(name, "USER", 30);
    }

    long user(String name, String role, int daysOld) {
        String email = "chat2-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, ?, ?, 'ACTIVE', TRUE, ?)""",
                email, name, role, Timestamp.from(Instant.now().minus(Duration.ofDays(daysOld))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    static MockHttpSession session(long userId, String role) {
        var me = new com.sep490.vsa.common.security.AppUserPrincipal(userId, "u" + userId + "@test.local",
                "Chat Test", role, "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))));
        return session;
    }

    static MockHttpSession session(long userId) {
        return session(userId, "USER");
    }

    static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, long userId) {
        return request.session(session(userId)).cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    /** A request with a JSON body, signed in as {@code userId}. */
    ResultActions call(MockHttpServletRequestBuilder request, long userId, Object body) throws Exception {
        return mvc.perform(withCsrf(request, userId).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String text ? text : json.writeValueAsString(body)));
    }

    ResultActions read(String url, long userId) throws Exception {
        return mvc.perform(get(url).session(session(userId)));
    }

    /** A plain first message to a member (by their id): returns the conversation id. */
    long request(long from, long to, String text) throws Exception {
        String body = call(post(API + "/with/" + to + "/messages"), from, Map.of("content", text))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.conversationId")).longValue();
    }

    ResultActions sendPlain(long from, long conversation, String text) throws Exception {
        return call(post(API + "/" + conversation + "/messages"), from, Map.of("content", text));
    }

    ResultActions sendBody(long from, long conversation, Map<String, Object> body) throws Exception {
        return call(post(API + "/" + conversation + "/messages"), from, body);
    }

    void accept(long userId, long conversation) throws Exception {
        mvc.perform(withCsrf(post(API + "/" + conversation + "/accept"), userId)).andExpect(status().isOk());
    }

    /** A conversation both members accepted: {@code a} wrote first, {@code b} accepted. */
    long accepted(long a, long b) throws Exception {
        long conversation = request(a, b, "Chào bạn, mình hỏi chút nhé");
        accept(b, conversation);
        return conversation;
    }

    static long id(ResultActions result, String path) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, path)).longValue();
    }

    static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- encryption

    /** A raw uncompressed P-256 public point, as {@code crypto.subtle.exportKey("raw")} gives it. */
    static byte[] newPublicPoint() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, raw, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, raw, 33, 32);
        return raw;
    }

    static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(32, bytes.length);
        System.arraycopy(bytes, bytes.length - copy, out, 32 - copy, copy);
        return out;
    }

    /** The body of POST /keys for this public point, with a backup of the sizes the browser makes. */
    static Map<String, Object> keyBody(byte[] point) {
        Map<String, Object> body = new HashMap<>();
        body.put("publicKey", b64(point));
        body.put("wrappedPrivateKey", b64(random(154)));
        body.put("wrapSalt", b64(random(16)));
        body.put("wrapIv", b64(random(12)));
        body.put("wrapIterations", 600000);
        return body;
    }

    /** Turns encryption on for the member: returns the new key id. */
    long newKey(long userId) throws Exception {
        return id(call(post(API + "/keys"), userId, keyBody(newPublicPoint())), "$.data.id");
    }

    /** An encrypted message as the browser sends it, with what the recipient reveals to report it. */
    record Sealed(Map<String, Object> envelope, byte[] frankingKey, byte[] plaintext) {

        Map<String, Object> body() {
            Map<String, Object> body = new HashMap<>();
            body.put("encrypted", envelope);
            return body;
        }
    }

    Sealed seal(long conversation, long sender, long senderKey, long recipientKey, String text) throws Exception {
        byte[] fk = random(32);
        byte[] plaintext = json.writeValueAsString(Map.of("v", 1, "text", text)).getBytes(StandardCharsets.UTF_8);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("ciphertext", b64(random(32 + plaintext.length + 16)));
        envelope.put("iv", b64(random(12)));
        envelope.put("senderKeyId", senderKey);
        envelope.put("recipientKeyId", recipientKey);
        envelope.put("frankingTag", frankingTag(fk, conversation, sender, plaintext));
        return new Sealed(envelope, fk, plaintext);
    }

    /** The contract's franking tag, computed here with the JDK only (not with ChatCrypto). */
    static String frankingTag(byte[] fk, long conversation, long sender, byte[] plaintext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(fk, "HmacSHA256"));
        mac.update(("asvsj-frank-v1|" + conversation + "|" + sender + "|").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(plaintext));
    }

    /** An encrypted conversation: {@code a} wrote first, {@code b} accepted, both have keys. */
    record Encrypted(long conversation, long a, long b, long keyA, long keyB) {
    }

    /** A member in several encrypted conversations keeps one key (a new one would revoke it). */
    Encrypted encrypted(long a, long b) throws Exception {
        long conversation = accepted(a, b);
        return new Encrypted(conversation, a, b, keyOf(a), keyOf(b));
    }

    /** The member's current key, made when they have none. */
    long keyOf(long userId) throws Exception {
        List<Long> current = jdbc.queryForList("""
                SELECT id FROM chat_keys WHERE user_id = ? AND revoked_at IS NULL ORDER BY id DESC""", Long.class, userId);
        return current.isEmpty() ? newKey(userId) : current.get(0);
    }

    long sendSealed(Encrypted e, long from, String text, List<Long> fileIds) throws Exception {
        boolean fromA = from == e.a();
        Sealed sealed = seal(e.conversation(), from, fromA ? e.keyA() : e.keyB(), fromA ? e.keyB() : e.keyA(), text);
        Map<String, Object> body = sealed.body();
        body.put("fileIds", fileIds);
        return id(sendBody(from, e.conversation(), body), "$.data.message.id");
    }

    // ---------------------------------------------------------------- files

    ResultActions upload(long userId, long conversation, String kind, byte[] bytes) throws Exception {
        return mvc.perform(withCsrf(multipart(API + "/" + conversation + "/files")
                .file(new MockMultipartFile("file", "blob", "application/octet-stream", bytes))
                .param("kind", kind), userId));
    }

    long uploaded(long userId, long conversation, String kind, byte[] bytes) throws Exception {
        return id(upload(userId, conversation, kind, bytes), "$.data.fileId");
    }

    static byte[] random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
