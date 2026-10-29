package com.sep490.vsa.feature.chat;

import com.sep490.vsa.feature.moderation.ImageModerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos, videos and voice messages of encrypted conversations against MySQL: who may upload, kinds and sizes, the
 * day's number of files, which files a message may carry, who may download them, recall, and the daily clean-up.
 * The bytes are random (the browser encrypts them; the server only stores them).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"              // ASVSJ places only: no calls to OpenStreetMap from the tests
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ChatFilesFlowTest extends ChatTestBase {

    @MockBean
    private ImageModerator moderator;
    @Autowired
    private ChatCleanup cleanup;

    @Test
    void filesNeedAnEncryptedConversation() throws Exception {
        long a = user("An");
        long b = user("Bình");
        long request = request(a, b, "Chào Bình");
        upload(a, request, "IMAGE", random(100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ChatService.FILES_NEED_E2EE));
        accept(b, request);
        newKey(a);
        upload(a, request, "IMAGE", random(100)).andExpect(status().isBadRequest())       // b has no key yet
                .andExpect(jsonPath("$.message").value(ChatService.FILES_NEED_E2EE));
        upload(user("Người ngoài"), request, "IMAGE", random(100)).andExpect(status().isNotFound());
        mvc.perform(multipart(API + "/" + request + "/files")
                        .file(new MockMultipartFile("file", "blob", "application/octet-stream", random(10)))
                        .param("kind", "IMAGE").cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isUnauthorized());
        newKey(b);
        upload(a, request, "IMAGE", random(100)).andExpect(status().isOk());
        // Blocked: no uploads either
        mvc.perform(withCsrf(post(API + "/blocks/" + a), b)).andExpect(status().isOk());
        upload(a, request, "IMAGE", random(100)).andExpect(status().isForbidden());
        assertThat(count("SELECT COUNT(*) FROM chat_files WHERE uploader_id = ?", a)).isEqualTo(1);
    }

    @Test
    void eachKindHasItsSize() throws Exception {
        Encrypted e = encrypted(user("An"), user("Bình"));
        long conversation = e.conversation();
        upload(e.a(), conversation, "PDF", random(100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Loại tệp không hợp lệ."));
        upload(e.a(), conversation, "IMAGE", new byte[0]).andExpect(status().isBadRequest());
        upload(e.a(), conversation, "IMAGE", new byte[(int) ChatService.IMAGE_MAX_BYTES + 1])
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Mỗi ảnh tối đa 10 MB."));
        upload(e.a(), conversation, "AUDIO", new byte[(int) ChatService.AUDIO_MAX_BYTES + 1])
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Ghi âm tối đa 5 MB."));
        mvc.perform(withCsrf(multipart(API + "/" + conversation + "/files").param("kind", "IMAGE"), e.a()))
                .andExpect(status().isBadRequest());

        byte[] image = random((int) ChatService.IMAGE_MAX_BYTES);
        long imageId = uploaded(e.a(), conversation, "image", image);
        long audioId = uploaded(e.a(), conversation, "AUDIO", random(5000));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT kind, size_bytes, storage_key, message_id, uploader_id FROM chat_files WHERE id = ?""", imageId);
        assertThat(row).containsEntry("kind", "IMAGE").containsEntry("message_id", null);
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(image.length);
        String key = (String) row.get("storage_key");
        assertThat(key).matches("chat/[0-9]{4}/[0-9]{2}/[0-9a-f-]{36}\\.bin");
        assertThat(storage.get(key)).isEqualTo(image);
        assertThat(jdbc.queryForObject("SELECT kind FROM chat_files WHERE id = ?", String.class, audioId))
                .isEqualTo("AUDIO");
    }

    @Test
    void aNewAccountUploadsTwentyFilesADay() throws Exception {
        Encrypted e = encrypted(user("Mới", "USER", 1), user("Bình"));
        for (int i = 0; i < ChatService.FILES_PER_DAY_NEW_ACCOUNT; i++) {
            upload(e.a(), e.conversation(), "IMAGE", random(64)).andExpect(status().isOk());
        }
        upload(e.a(), e.conversation(), "IMAGE", random(64)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(containsString("giới hạn của tài khoản mới")));
        upload(e.b(), e.conversation(), "IMAGE", random(64)).andExpect(status().isOk());
    }

    @Test
    void aMessageCarriesTheWritersOwnUnsentFiles() throws Exception {
        long a = user("An");
        long b = user("Bình");
        Encrypted e = encrypted(a, b);
        Encrypted other = encrypted(a, user("Chi"));
        long[] images = new long[6];
        for (int i = 0; i < images.length; i++) {
            images[i] = uploaded(a, e.conversation(), "IMAGE", random(200));
        }
        long video = uploaded(a, e.conversation(), "VIDEO", random(300));
        long audio = uploaded(a, e.conversation(), "AUDIO", random(300));
        long bImage = uploaded(b, e.conversation(), "IMAGE", random(200));
        long elsewhere = uploaded(a, other.conversation(), "IMAGE", random(200));

        for (List<Long> bad : List.of(
                List.of(bImage),
                List.of(images[0], images[1], images[2], images[3], images[4], images[5]),
                List.of(images[0], video),
                List.of(video, audio),
                List.of(images[0], images[0]),
                List.of(elsewhere),
                List.of(999999999L))) {
            Sealed sealed = seal(e.conversation(), a, e.keyA(), e.keyB(), "Ảnh đây");
            Map<String, Object> body = sealed.body();
            body.put("fileIds", bad);
            sendBody(a, e.conversation(), body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(ChatService.BAD_FILES));
        }

        long photos = sendSealed(e, a, "Năm ảnh", List.of(images[4], images[3], images[2], images[1], images[0]));
        assertThat(jdbc.queryForList("SELECT id FROM chat_files WHERE message_id = ? ORDER BY id", Long.class, photos))
                .containsExactly(images[0], images[1], images[2], images[3], images[4]);
        read(API + "/" + e.conversation() + "/messages/" + photos, b)
                .andExpect(jsonPath("$.data.fileIds").value(contains((int) images[0], (int) images[1],
                        (int) images[2], (int) images[3], (int) images[4])));
        // Already sent: cannot go with another message
        Sealed again = seal(e.conversation(), a, e.keyA(), e.keyB(), "Lại ảnh đó");
        Map<String, Object> body = again.body();
        body.put("fileIds", List.of(images[0]));
        sendBody(a, e.conversation(), body).andExpect(status().isBadRequest());
        sendSealed(e, a, "Video", List.of(video));
        sendSealed(e, a, "Ghi âm", List.of(audio));
        sendSealed(e, b, "Ảnh của Bình", List.of(bImage));
    }

    @Test
    void onlyTheTwoMembersDownloadAFile() throws Exception {
        long a = user("An");
        long b = user("Bình");
        Encrypted e = encrypted(a, b);
        byte[] bytes = random(1000);
        long fileId = uploaded(a, e.conversation(), "IMAGE", bytes);
        String url = API + "/" + e.conversation() + "/files/" + fileId;

        // Not sent yet: only its uploader
        read(url, a).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        read(url, b).andExpect(status().isNotFound());
        long message = sendSealed(e, a, "Ảnh", List.of(fileId));
        read(url, b).andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Cache-Control", containsString("max-age=86400")));
        read(url, user("Người lạ")).andExpect(status().isNotFound());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        Encrypted other = encrypted(a, user("Chi"));
        read(API + "/" + other.conversation() + "/files/" + fileId, a).andExpect(status().isNotFound());
        read(API + "/" + e.conversation() + "/files/999999999", a).andExpect(status().isNotFound());

        // Recalled: gone for both, the bytes deleted from storage
        String key = jdbc.queryForObject("SELECT storage_key FROM chat_files WHERE id = ?", String.class, fileId);
        mvc.perform(withCsrf(delete(API + "/" + e.conversation() + "/messages/" + message), a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fileIds").isEmpty());
        assertThat(count("SELECT COUNT(*) FROM chat_files WHERE id = ? AND deleted_at IS NOT NULL", fileId))
                .isEqualTo(1);
        assertThat(storage.resource(key)).isNull();
        read(url, b).andExpect(status().isNotFound());
        read(url, a).andExpect(status().isNotFound());

        // A message the viewer cleared from their side takes its files with it
        long second = uploaded(a, e.conversation(), "AUDIO", random(500));
        sendSealed(e, a, "Ghi âm", List.of(second));
        read(API + "/" + e.conversation() + "/files/" + second, b).andExpect(status().isOk());
        mvc.perform(withCsrf(delete(API + "/" + e.conversation()), b)).andExpect(status().isOk());
        read(API + "/" + e.conversation() + "/files/" + second, b).andExpect(status().isNotFound());
        read(API + "/" + e.conversation() + "/files/" + second, a).andExpect(status().isOk());
    }

    @Test
    void theDailyCleanUpEmptiesOldRecallsAndUnsentFiles() throws Exception {
        long a = user("An");
        long b = user("Bình");
        Encrypted e = encrypted(a, b);
        long oldFile = uploaded(a, e.conversation(), "IMAGE", random(100));
        long freshFile = uploaded(a, e.conversation(), "IMAGE", random(100));
        long sentFile = uploaded(a, e.conversation(), "IMAGE", random(100));
        sendSealed(e, a, "Ảnh", List.of(sentFile));
        jdbc.update("UPDATE chat_files SET created_at = ? WHERE id IN (?, ?)",
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))), oldFile, sentFile);
        String oldKey = jdbc.queryForObject("SELECT storage_key FROM chat_files WHERE id = ?", String.class, oldFile);

        long plain = accepted(a, user("Chi"));
        long longAgo = id(sendPlain(a, plain, "Tin thu hồi từ lâu"), "$.data.message.id");
        long recent = id(sendPlain(a, plain, "Tin vừa thu hồi"), "$.data.message.id");
        call(put(API + "/" + plain + "/messages/" + longAgo), a, Map.of("content", "Tin thu hồi từ lâu, đã sửa"))
                .andExpect(status().isOk());
        mvc.perform(withCsrf(delete(API + "/" + plain + "/messages/" + longAgo), a)).andExpect(status().isOk());
        mvc.perform(withCsrf(delete(API + "/" + plain + "/messages/" + recent), a)).andExpect(status().isOk());
        jdbc.update("UPDATE chat_messages SET recalled_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(31))), longAgo);
        long encrypted = sendSealed(e, a, "Mã hóa thu hồi", List.of());
        mvc.perform(withCsrf(delete(API + "/" + e.conversation() + "/messages/" + encrypted), a))
                .andExpect(status().isOk());
        jdbc.update("UPDATE chat_messages SET recalled_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(31))), encrypted);

        ChatCleanup.Result result = cleanup.run();
        assertThat(result.messagesEmptied()).isGreaterThanOrEqualTo(2);
        assertThat(result.filesDeleted()).isGreaterThanOrEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM chat_files WHERE id = ?", oldFile)).isZero();
        assertThat(storage.resource(oldKey)).isNull();
        assertThat(count("SELECT COUNT(*) FROM chat_files WHERE id IN (?, ?)", freshFile, sentFile)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT content, recalled_at IS NOT NULL AS r FROM chat_messages WHERE id = ?",
                longAgo)).containsEntry("content", null);
        assertThat(count("SELECT COUNT(*) FROM chat_message_edits WHERE message_id = ?", longAgo)).isZero();
        assertThat(jdbc.queryForObject("SELECT content FROM chat_messages WHERE id = ?", String.class, recent))
                .isEqualTo("Tin vừa thu hồi");
        assertThat(count("""
                SELECT COUNT(*) FROM chat_messages WHERE id = ? AND ciphertext IS NULL AND iv IS NULL
                AND franking_tag IS NULL""", encrypted)).isEqualTo(1);
        // Still shown as recalled
        read(API + "/" + e.conversation() + "/messages/" + encrypted, b)
                .andExpect(jsonPath("$.data.recalled").value(true));
    }
}
