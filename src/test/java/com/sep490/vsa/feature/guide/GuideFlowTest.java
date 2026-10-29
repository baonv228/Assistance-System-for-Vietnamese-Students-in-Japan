package com.sep490.vsa.feature.guide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.security.AppUserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.sep490.vsa.feature.moderation.ImageModerator;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.BeforeEach;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/${guide.test.database:asvsj_test}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.places.osm.enabled=false", "app.moderation.retry-initial-delay-ms=3600000",
        "app.media.local-dir=target/test-guide-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class GuideFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired GuideService guideService;
    @MockBean ImageModerator imageModerator;

    @BeforeEach
    void scanner() {
        when(imageModerator.engine()).thenReturn("OPENAI");
        when(imageModerator.check(any())).thenReturn(new ImageModerator.Verdict("PASS", "{}"));
    }

    private MockHttpSession session(String role) {
        String tag = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(email,display_name,role,status,is_adult_confirmed) VALUES(?,?,?,'ACTIVE',TRUE)",
                tag + "@test.local", tag, role);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, tag + "@test.local");
        var principal = new AppUserPrincipal(id, tag + "@test.local", tag, role, "/moderator");
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(auth));
        return session;
    }

    private GuideRequest request(String category, String image) {
        return new GuideRequest("Guide " + UUID.randomUUID(), "Purpose and audience", category,
                List.of(new GuideRequest.Section("Step one", "Content", image, "https://example.org/reference"),
                        new GuideRequest.Section("Step two", "More content", null, null)));
    }

    @Test
    void publishReadSearchAndCreationAudit() throws Exception {
        var moderator = session("MODERATOR");
        var req = request("PRE", "https://example.org/image.jpg");
        Instant before = Instant.now().minusSeconds(2);
        var response = mvc.perform(post("/api/moderator/guides").session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.published").value(true))
                .andExpect(jsonPath("$.data.sections[1].heading").value("Step two"))
                .andReturn().getResponse();
        var data = json.readTree(response.getContentAsByteArray()).get("data");
        long id = data.get("id").asLong();
        assertThat(Instant.parse(data.get("createdAt").asText())).isAfter(before);
        assertThat(data.get("createdBy").asLong()).isPositive();
        mvc.perform(get("/api/guides/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections.length()").value(2));
        mvc.perform(get("/api/guides").param("category", "PRE").param("q", req.title()))
                .andExpect(jsonPath("$.data[0].id").value(id));
        mvc.perform(get("/api/guides").param("category", "HOME").param("q", req.title()))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/moderator/guides").session(moderator)).andExpect(status().isOk())
                .andExpect(forwardedUrl("/pages/moderator/guides.html"));
    }

    @Test
    void rejectWrongRoleInvalidTopicInvalidUrlAndMissingFields() throws Exception {
        var user = session("USER");
        var moderator = session("MODERATOR");
        mvc.perform(get("/api/moderator/guides").session(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/moderator/guides").session(user)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request("PRE", null))))
                .andExpect(status().isForbidden());
        for (var req : List.of(request("INVALID", null), request("PRE", "javascript:alert(1)"),
                new GuideRequest(" ", " ", "PRE", List.of()))) {
            mvc.perform(post("/api/moderator/guides").session(moderator)
                            .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token")
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(req)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/guides/9223372036854775807")).andExpect(status().isNotFound());
        mvc.perform(get("/api/moderator/guides")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadStoresPhotoLinksItToItsSectionAndServesItPublicly() throws Exception {
        var moderator = session("MODERATOR");
        var request = new GuideRequest("Photo guide", "Summary", "ARR", List.of(
                new GuideRequest.Section("No photo", "First", null, null),
                new GuideRequest.Section("Photo step", "Second", null, null, 0)));
        var response = mvc.perform(multipart("/api/moderator/guides")
                        .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(request)))
                        .file(photo()).session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var data = json.readTree(response.getContentAsByteArray()).get("data");
        String imageUrl = data.get("sections").get(1).get("imageUrl").asText();
        assertThat(imageUrl).startsWith("/media/guide/").endsWith(".jpg");
        assertThat(data.get("sections").get(0).get("imageUrl").isNull()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM guide_sections gs JOIN media m ON m.id = gs.media_id
                WHERE gs.guide_id = ? AND gs.sort_order = 1 AND m.scan_status = 'PASSED'
                  AND m.mime_type = 'image/jpeg'
                """, Integer.class, data.get("id").asLong())).isEqualTo(1);
        mvc.perform(get(imageUrl)).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void invalidOrFlaggedPhotosDoNotCreateGuidesOrMedia() throws Exception {
        var moderator = session("MODERATOR");
        var request = new GuideRequest("Photo guide", "Summary", "PRE",
                List.of(new GuideRequest.Section("Step", "Content", null, null, 0)));
        int guides = jdbc.queryForObject("SELECT COUNT(*) FROM guide_items", Integer.class);
        int media = jdbc.queryForObject("SELECT COUNT(*) FROM media", Integer.class);
        for (var file : List.of(new MockMultipartFile("images", "bad.png", "image/png", new byte[]{1, 2, 3}), photo())) {
            when(imageModerator.check(any())).thenReturn(new ImageModerator.Verdict("FLAG", "{}"));
            mvc.perform(multipart("/api/moderator/guides")
                            .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(request)))
                            .file(file).session(moderator)
                            .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM guide_items", Integer.class)).isEqualTo(guides);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM media", Integer.class)).isEqualTo(media);
    }

    private MockMultipartFile photo() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("images", "photo.png", "image/png", bytes.toByteArray());
    }

    private AppUserPrincipal principal(MockHttpSession session) {
        return (AppUserPrincipal) ((SecurityContextImpl) session.getAttribute("SPRING_SECURITY_CONTEXT")).getAuthentication().getPrincipal();
    }

    private GuideRequest preparationRequest(String title, List<GuideRequest.PreparationTask> tasks, boolean enabled) {
        return new GuideRequest(title, "Summary", "PRE", List.of(new GuideRequest.Section("Nội dung", "Content", null, null)),
                "LIVING", null, "PUBLISHED", null, false, enabled, tasks);
    }

    @Test
    void preparationProgressIsPersistentIdempotentAndPrivatePerUser() throws Exception {
        var moderator = session("MODERATOR");
        var guide = guideService.create(preparationRequest("Checklist", List.of(
                new GuideRequest.PreparationTask(null, "Passport", "Check expiration"),
                new GuideRequest.PreparationTask(null, "Visa", null)), true), principal(moderator));
        long firstTask = guide.preparationTasks().get(0).id(), secondTask = guide.preparationTasks().get(1).id();
        mvc.perform(get("/api/guides/" + guide.id()))
                .andExpect(jsonPath("$.data.preparationEnabled").value(true))
                .andExpect(jsonPath("$.data.preparationTasks.length()").value(2));
        String base = "/api/guides/" + guide.id() + "/preparation";
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.signedIn").doesNotExist())
                .andExpect(jsonPath("$.data.signedIn").value(false)).andExpect(jsonPath("$.data.completed").value(0));
        mvc.perform(put(base + "/" + firstTask).cookie(new Cookie("XSRF-TOKEN", "token"))
                        .header("X-XSRF-TOKEN", "token").contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isUnauthorized());
        var user = session("USER");
        for (int i = 0; i < 2; i++) {
            mvc.perform(put(base + "/" + firstTask).session(user).cookie(new Cookie("XSRF-TOKEN", "token"))
                            .header("X-XSRF-TOKEN", "token").contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.completed").value(1))
                    .andExpect(jsonPath("$.data.percent").value(50));
        }
        mvc.perform(get(base).session(session("USER"))).andExpect(jsonPath("$.data.completed").value(0));
        mvc.perform(get(base).session(user)).andExpect(jsonPath("$.data.completed").value(1));
        mvc.perform(put(base + "/" + secondTask).session(user).cookie(new Cookie("XSRF-TOKEN", "token"))
                        .header("X-XSRF-TOKEN", "token").contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(jsonPath("$.data.percent").value(100));
        mvc.perform(put(base + "/" + firstTask).session(user).cookie(new Cookie("XSRF-TOKEN", "token"))
                        .header("X-XSRF-TOKEN", "token").contentType(MediaType.APPLICATION_JSON).content("{\"completed\":false}"))
                .andExpect(jsonPath("$.data.percent").value(50));
        mvc.perform(put(base + "/9223372036854775807").session(user).cookie(new Cookie("XSRF-TOKEN", "token"))
                        .header("X-XSRF-TOKEN", "token").contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isNotFound());
        guideService.delete(guide.id(), principal(moderator));
        mvc.perform(get(base).session(user)).andExpect(status().isNotFound());
    }

    @Test
    void editingTaskOrderKeepsProgressAndDisablingChecklistRestoresOrdinaryGuide() throws Exception {
        var moderator = session("MODERATOR");
        var guide = guideService.create(preparationRequest("Checklist edit", List.of(
                new GuideRequest.PreparationTask(null, "First", null), new GuideRequest.PreparationTask(null, "Second", null)), true), principal(moderator));
        var user = session("USER");
        long first = guide.preparationTasks().get(0).id(), second = guide.preparationTasks().get(1).id();
        String base = "/api/guides/" + guide.id() + "/preparation";
        mvc.perform(put(base + "/" + first).session(user).cookie(new Cookie("XSRF-TOKEN", "token"))
                        .header("X-XSRF-TOKEN", "token").contentType(MediaType.APPLICATION_JSON).content("{\"completed\":true}"))
                .andExpect(status().isOk());
        guideService.updateWithImages(guide.id(), preparationRequest("Checklist edit", List.of(
                new GuideRequest.PreparationTask(second, "Second renamed", null),
                new GuideRequest.PreparationTask(first, "First renamed", "Notes")), true), principal(moderator), List.of());
        mvc.perform(get(base).session(user)).andExpect(jsonPath("$.data.completed").value(1))
                .andExpect(jsonPath("$.data.tasks[0].id").value(second))
                .andExpect(jsonPath("$.data.tasks[1].completed").value(true));
        assertThatThrownBy(() -> guideService.updateWithImages(guide.id(), preparationRequest("Bad", List.of(
                new GuideRequest.PreparationTask(Long.MAX_VALUE, "Foreign task", null)), true), principal(moderator), List.of()))
                .isInstanceOf(com.sep490.vsa.common.exception.ApiException.class);
        guideService.updateWithImages(guide.id(), preparationRequest("No checklist", List.of(), false), principal(moderator), List.of());
        mvc.perform(get(base).session(user)).andExpect(status().isNotFound());
        mvc.perform(get("/api/guides/" + guide.id())).andExpect(jsonPath("$.data.preparationEnabled").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM guide_preparation_progress WHERE user_id=?", Integer.class, principal(user).id())).isZero();
    }

    @Test
    void ordinaryGuidesHaveNoChecklistAndEnabledPublicChecklistsNeedTasks() throws Exception {
        var moderator = session("MODERATOR");
        var normal = guideService.create(request("PRE", null), principal(moderator));
        mvc.perform(get("/api/guides/" + normal.id())).andExpect(jsonPath("$.data.preparationEnabled").value(false))
                .andExpect(jsonPath("$.data.preparationTasks.length()").value(0));
        mvc.perform(get("/api/guides/" + normal.id() + "/preparation")).andExpect(status().isNotFound());
        for (var invalid : List.of(preparationRequest("Empty", List.of(), true),
                preparationRequest("Blank", List.of(new GuideRequest.PreparationTask(null, " ", null)), true))) {
            mvc.perform(post("/api/moderator/guides").session(moderator).cookie(new Cookie("XSRF-TOKEN", "token"))
                            .header("X-XSRF-TOKEN", "token").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(invalid)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void moderatorUpdatesPublishedGuideKeepsSectionImagesAndDeletesItFromAllReaders() throws Exception {
        var moderator = session("MODERATOR");
        var original = new GuideRequest("Update-delete " + UUID.randomUUID(), "Summary", "PRE",
                List.of(new GuideRequest.Section("First", "Original content", null, null, 0),
                        new GuideRequest.Section("Second", "Second content", null, "https://example.org/reference")));
        var createdResponse = mvc.perform(multipart("/api/moderator/guides")
                        .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(original)))
                        .file(photo()).session(moderator).cookie(new Cookie("XSRF-TOKEN", "token"))
                        .header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var created = json.readTree(createdResponse.getContentAsByteArray()).get("data");
        long id = created.get("id").asLong();
        long imageId = created.get("sections").get(0).get("mediaId").asLong();
        String imageUrl = created.get("sections").get(0).get("imageUrl").asText();
        var edited = new GuideRequest(original.title() + " edited", "Updated summary", "ARR",
                List.of(new GuideRequest.Section("First edited", "New content", null, null, null, imageId),
                        original.sections().get(1)), "LIVING", "https://example.org/source", "PUBLISHED", null, false);
        var updatedResponse = mvc.perform(multipart("/api/moderator/guides/" + id)
                        .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(edited)))
                        .with(request -> { request.setMethod("PUT"); return request; }).session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sections.length()").value(2))
                .andExpect(jsonPath("$.data.sections[0].imageUrl").value(imageUrl))
                .andExpect(jsonPath("$.data.sections[1].referenceUrl").value("https://example.org/reference"))
                .andReturn().getResponse();
        var updated = json.readTree(updatedResponse.getContentAsByteArray()).get("data");
        assertThat(updated.get("createdBy")).isEqualTo(created.get("createdBy"));
        assertThat(updated.get("createdAt")).isEqualTo(created.get("createdAt"));
        mvc.perform(get(imageUrl)).andExpect(status().isOk());
        mvc.perform(get("/api/guides/" + id)).andExpect(jsonPath("$.data.title").value(edited.title()));

        var user = session("USER");
        mvc.perform(delete("/api/moderator/guides/" + id).session(user)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/moderator/guides/" + id)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/moderator/guides/" + id).session(moderator))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/moderator/guides/" + id).session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isOk());
        for (String path : List.of("/api/guides/" + id, "/api/moderator/guides/" + id)) {
            mvc.perform(get(path).session(moderator)).andExpect(status().isNotFound());
        }
        mvc.perform(get(imageUrl)).andExpect(status().isNotFound());
        mvc.perform(get("/api/guides/browse").param("q", edited.title()))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/moderator/guides/browse").session(moderator).param("q", edited.title()))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(multipart("/api/moderator/guides/" + id)
                        .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(edited)))
                        .with(request -> { request.setMethod("PUT"); return request; }).session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM guide_items WHERE id=?", Boolean.class, id)).isTrue();
    }

    @Test
    void updateCannotAttachAnotherGuidesPrivateImage() throws Exception {
        var moderator = session("MODERATOR");
        var principal = (AppUserPrincipal) ((SecurityContextImpl) moderator.getAttribute("SPRING_SECURITY_CONTEXT"))
                .getAuthentication().getPrincipal();
        var first = guideService.create(request("PRE", null), principal);
        var invalid = new GuideRequest(first.title(), "Summary", "PRE",
                List.of(new GuideRequest.Section("Step", "New content", null, null, null, Long.MAX_VALUE)));
        mvc.perform(multipart("/api/moderator/guides/" + first.id())
                        .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(invalid)))
                        .with(request -> { request.setMethod("PUT"); return request; }).session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/guides/" + first.id()))
                .andExpect(jsonPath("$.data.sections[0].heading").value("Step one"));
    }

    @Test
    void draftCanBeResumedAndPublishedWithoutChangingCreationAuditOrLosingCover() throws Exception {
        var moderator = session("MODERATOR");
        String title = "Draft " + UUID.randomUUID();
        var draft = new GuideRequest(title, null, null,
                List.of(new GuideRequest.Section("", "", null, null)), null, null, "DRAFT", 0, false);
        var response = mvc.perform(multipart("/api/moderator/guides")
                        .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(draft)))
                        .file(photo()).session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.published").value(false)).andReturn().getResponse();
        var saved = json.readTree(response.getContentAsByteArray()).get("data");
        long id = saved.get("id").asLong();
        String cover = saved.get("coverImageUrl").asText();
        mvc.perform(get("/api/guides/" + id)).andExpect(status().isNotFound());
        mvc.perform(get(cover)).andExpect(status().isNotFound());
        mvc.perform(get(cover).session(moderator)).andExpect(status().isOk());
        mvc.perform(get("/api/moderator/guides/" + id).session(moderator))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        mvc.perform(get("/api/moderator/guides/browse").session(moderator).param("status", "DRAFT").param("q", title))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/guides/browse").param("q", title))
                .andExpect(jsonPath("$.data.total").value(0));

        var published = new GuideRequest(title, "Summary", "ARR",
                List.of(new GuideRequest.Section("Nội dung", "**Hướng dẫn**", null, null)),
                "PROCEDURES", "https://example.org/source", "PUBLISHED", null, false);
        var updated = mvc.perform(multipart("/api/moderator/guides/" + id)
                        .file(new MockMultipartFile("guide", "guide.json", "application/json", json.writeValueAsBytes(published)))
                        .with(request -> { request.setMethod("PUT"); return request; }).session(moderator)
                        .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.topicName").value("Thủ tục"))
                .andExpect(jsonPath("$.data.coverImageUrl").value(cover)).andReturn().getResponse();
        var result = json.readTree(updated.getContentAsByteArray()).get("data");
        assertThat(result.get("createdAt")).isEqualTo(saved.get("createdAt"));
        assertThat(result.get("createdBy")).isEqualTo(saved.get("createdBy"));
        mvc.perform(get("/api/guides/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceUrl").value("https://example.org/source"));
        mvc.perform(get(cover)).andExpect(status().isOk());
        mvc.perform(get("/api/guides/browse").param("q", title).param("category", "ARR").param("topicCode", "PROCEDURES"))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/guides/browse").param("q", title).param("topicCode", "LIVING"))
                .andExpect(jsonPath("$.data.total").value(0));
        String topicKeyword = "Subject-" + UUID.randomUUID();
        jdbc.update("UPDATE guide_topics SET title=? WHERE code='PROCEDURES'", topicKeyword);
        mvc.perform(get("/api/guides/browse").param("q", topicKeyword).param("category", "ARR"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id))
                .andExpect(jsonPath("$.data.items[0].coverImageUrl").value(cover));
    }

    @Test
    void publishingRequiresClassificationsAndContentAndRejectsInvalidSource() throws Exception {
        var moderator = session("MODERATOR");
        for (var request : List.of(
                new GuideRequest("Title", null, null, List.of(new GuideRequest.Section("", "", null, null)), null, null, "PUBLISHED", null, false),
                new GuideRequest("Title", null, "PRE", List.of(new GuideRequest.Section("Heading", "Content", null, null)), "INVALID", null, "PUBLISHED", null, false),
                new GuideRequest("Title", null, "PRE", List.of(new GuideRequest.Section("Heading", "Content", null, null)), "LIVING", "javascript:alert(1)", "DRAFT", null, false))) {
            mvc.perform(post("/api/moderator/guides").session(moderator)
                            .cookie(new Cookie("XSRF-TOKEN", "token")).header("X-XSRF-TOKEN", "token")
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void guestAndMemberBrowseOnlyPublicGuidesWithSearchSortingAndPagination() throws Exception {
        var moderatorSession = session("MODERATOR");
        var context = (SecurityContextImpl) moderatorSession.getAttribute("SPRING_SECURITY_CONTEXT");
        var moderator = (AppUserPrincipal) context.getAuthentication().getPrincipal();
        String keyword = "Browse-" + UUID.randomUUID();
        var first = guideService.create(new GuideRequest("Alpha " + keyword, "Description", "PRE",
                List.of(new GuideRequest.Section("Step", "Instructions " + keyword, null, null))), moderator);
        var second = guideService.create(new GuideRequest("Beta " + keyword, "Description", "PRE",
                List.of(new GuideRequest.Section("Step", "Instructions", null, null))), moderator);
        var hidden = guideService.create(new GuideRequest("Hidden " + keyword, "Description", "PRE",
                List.of(new GuideRequest.Section("Step", keyword, null, null))), moderator);
        jdbc.update("UPDATE guide_items SET published = FALSE WHERE id = ?", hidden.id());

        mvc.perform(get("/api/guides/browse").param("q", keyword).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(second.id()));
        mvc.perform(get("/api/guides/browse").param("q", keyword).param("size", "1")
                        .param("page", "1").session(session("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.id()));
        for (String sort : List.of("TITLE", "OLDEST")) {
            mvc.perform(get("/api/guides/browse").param("q", keyword).param("sort", sort))
                    .andExpect(jsonPath("$.data.items[0].id").value(first.id()));
        }
        mvc.perform(get("/api/guides/browse").param("q", "Instructions " + keyword))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.id()));
        mvc.perform(get("/api/guides/browse").param("q", keyword).param("category", "HOME"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/guides/browse").param("q", keyword).param("size", "1").param("page", "999"))
                .andExpect(jsonPath("$.data.page").value(1));
        mvc.perform(get("/api/moderator/guides/browse").session(moderatorSession)
                        .param("status", "PUBLISHED").param("q", keyword).param("category", "PRE").param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.id()));
        mvc.perform(get("/api/moderator/guides/browse").session(moderatorSession)
                        .param("q", keyword).param("category", "HOME"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/moderator/guides/browse"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/moderator/guides/browse").session(session("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/guides/" + hidden.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/guides/" + first.id()).session(session("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value(first.title()));
        mvc.perform(get("/guides/" + first.id()).param("q", keyword).param("page", "1"))
                .andExpect(status().isOk()).andExpect(forwardedUrl("/pages/guides.html"));
        for (String[] invalid : List.of(new String[]{"size", "51"}, new String[]{"page", "-1"},
                new String[]{"sort", "INVALID"}, new String[]{"q", "x".repeat(201)})) {
            mvc.perform(get("/api/guides/browse").param(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest());
        }
    }
}
