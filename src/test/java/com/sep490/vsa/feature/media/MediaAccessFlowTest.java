package com.sep490.vsa.feature.media;

import com.sep490.vsa.common.security.AppUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * {@code /media/<storage_key>} end to end against MySQL ({@code asvsj_test}): who gets which file, with which cache
 * headers ({@link MediaAccess}, {@link MediaController}). Every test writes its own files and rows and removes them.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/asvsj_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "app.media.local-dir=target/test-uploads",
        "app.moderation.retry-initial-delay-ms=3600000",
        "app.places.osm.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class MediaAccessFlowTest {

    private static final String PUBLIC = "max-age=86400, public";
    private static final String OWNER_ONLY = "no-store, private";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MediaStorage storage;

    private final List<Long> users = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private long owner;
    private long other;

    @BeforeEach
    void setUp() {
        owner = user("ACTIVE");
        other = user("ACTIVE");
    }

    @AfterEach
    void cleanUp() {
        for (long id : users) {
            jdbc.update("DELETE FROM tag_requests WHERE user_id = ?", id);
            jdbc.update("DELETE FROM blogs WHERE author_id = ?", id);           // blog_media goes with them
            jdbc.update("DELETE FROM reviews WHERE author_id = ?", id);         // review_media goes with them
        }
        for (long id : users) {
            // Merged places first: they point at the place they were merged into
            jdbc.update("DELETE FROM places WHERE created_by = ? ORDER BY merged_into_id IS NULL", id);
        }
        for (String key : keys) {
            jdbc.update("DELETE FROM media WHERE storage_key = ?", key);
            storage.delete(key);
        }
        for (long id : users) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void aFileBeingScannedIsShownOnlyToItsOwnerAndNeverCached() throws Exception {
        String key = file(owner, "PROCESSING", "jpg", "image/jpeg");
        attach(blog(owner, "PROCESSING"), key);

        assertNotFound(key, null);
        assertNotFound(key, other);
        MockHttpServletResponse mine = fetch(key, owner);
        assertThat(mine.getStatus()).isEqualTo(200);
        assertThat(mine.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("bytes of " + key);
        assertThat(mine.getContentType()).isEqualTo("image/jpeg");
        assertThat(mine.getHeader("Cache-Control")).isEqualTo(OWNER_ONLY);
        assertThat(mine.getHeaderValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(mine.getHeader("Pragma")).isNull();

        // Once the scan passes and the post is published, it is everybody's, and may be cached for a day
        jdbc.update("UPDATE media SET scan_status = 'PASSED' WHERE storage_key = ?", key);
        jdbc.update("UPDATE blogs SET status = 'PUBLISHED' WHERE author_id = ?", owner);
        MockHttpServletResponse guest = fetch(key, null);
        assertThat(guest.getStatus()).isEqualTo(200);
        assertThat(guest.getHeader("Cache-Control")).isEqualTo(PUBLIC);
        assertThat(fetch(key, owner).getHeader("Cache-Control")).isEqualTo(PUBLIC);
    }

    @Test
    void aRejectedFileIsShownOnlyToItsOwnerUntilItIsDeleted() throws Exception {
        String key = file(owner, "REJECTED", "jpg", "image/jpeg");
        attach(blog(owner, "PUBLISHED"), key);                  // even on a published post
        assertNotFound(key, null);
        assertNotFound(key, other);
        assertThat(fetch(key, owner).getHeader("Cache-Control")).isEqualTo(OWNER_ONLY);

        jdbc.update("UPDATE media SET deleted_at = ? WHERE storage_key = ?", Timestamp.from(Instant.now()), key);
        assertNotFound(key, owner);
    }

    @Test
    void aDeletedFileIsShownToNobodyEvenWhileItIsStillOnDisk() throws Exception {
        String key = file(owner, "PASSED", "jpg", "image/jpeg");
        attach(blog(owner, "PUBLISHED"), key);
        jdbc.update("UPDATE media SET deleted_at = ? WHERE storage_key = ?", Timestamp.from(Instant.now()), key);
        assertThat(storage.resource(key)).isNotNull();
        assertNotFound(key, null);
        assertNotFound(key, other);
        assertNotFound(key, owner);
    }

    @Test
    void aPassedFileOfAPublishedPostIsPublicWithItsTypeAndAnswersRangesAndRevalidation() throws Exception {
        String photo = file(owner, "PASSED", "jpg", "image/jpeg");
        String video = file(owner, "PASSED", "mov", "video/quicktime");
        long post = blog(owner, "PUBLISHED");
        attach(post, photo);
        attach(post, video);

        for (Long viewer : new Long[]{null, other, owner}) {
            MockHttpServletResponse answer = fetch(photo, viewer);
            assertThat(answer.getStatus()).isEqualTo(200);
            assertThat(answer.getContentType()).isEqualTo("image/jpeg");
            assertThat(answer.getHeader("Cache-Control")).isEqualTo(PUBLIC);
            assertThat(answer.getHeaderValues("X-Content-Type-Options")).containsExactly("nosniff");
            assertThat(answer.getHeader("Expires")).isNull();
        }
        MockHttpServletResponse movie = fetch(video, null);
        assertThat(movie.getContentType()).isEqualTo("video/quicktime");
        assertThat(movie.getHeader("Accept-Ranges")).isEqualTo("bytes");

        // Video seeking: a Range request gets only that part
        MockHttpServletResponse part = mvc.perform(get("/media/" + video).header("Range", "bytes=0-4"))
                .andReturn().getResponse();
        assertThat(part.getStatus()).isEqualTo(206);
        assertThat(part.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("bytes");
        assertThat(part.getHeader("Content-Range")).startsWith("bytes 0-4/");

        // A browser checking its copy again is told it has not changed
        String modified = movie.getHeader("Last-Modified");
        assertThat(modified).isNotNull();
        assertThat(mvc.perform(get("/media/" + video).header("If-Modified-Since", modified)).andReturn()
                .getResponse().getStatus()).isEqualTo(304);
        assertThat(mvc.perform(head("/media/" + photo)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aPassedFileOfAHiddenRemovedOrRejectedPostIsShownOnlyToItsOwner() throws Exception {
        for (String status : List.of("HIDDEN", "REMOVED", "REJECTED", "PROCESSING")) {
            String key = file(owner, "PASSED", "jpg", "image/jpeg");
            attach(blog(owner, status), key);
            assertNotFound(key, null);
            assertNotFound(key, other);
            assertThat(fetch(key, owner).getHeader("Cache-Control")).as(status).isEqualTo(OWNER_ONLY);
        }
        // Also published elsewhere (one file on two posts): public
        String shared = file(owner, "PASSED", "jpg", "image/jpeg");
        attach(blog(owner, "HIDDEN"), shared);
        attach(blog(owner, "PUBLISHED"), shared);
        assertThat(fetch(shared, null).getHeader("Cache-Control")).isEqualTo(PUBLIC);
    }

    @Test
    void aFileOfALockedOrDeletedAuthorIsShownToNobody() throws Exception {
        String key = file(owner, "PASSED", "jpg", "image/jpeg");
        attach(blog(owner, "PUBLISHED"), key);
        assertThat(fetch(key, null).getStatus()).isEqualTo(200);

        for (String status : List.of("LOCKED", "DELETED")) {
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, owner);
            assertNotFound(key, null);
            assertNotFound(key, other);
            // /media/ skips the session check, so the owner's status is read with the file: a stale session gets nothing
            assertNotFound(key, owner);
        }
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", owner);
        assertThat(fetch(key, other).getHeader("Cache-Control")).isEqualTo(PUBLIC);
    }

    // ---------------------------------------------------------------- review photos (Map)

    @Test
    void aPassedFileOfAPublishedReviewIsPublicLikeAPostPhoto() throws Exception {
        String photo = file("review", owner, "PASSED", "jpg", "image/jpeg");
        String video = file("review", owner, "PASSED", "mp4", "video/mp4");
        long place = place(other, "ACTIVE");
        long review = review(owner, place, "PUBLISHED");
        attachToReview(review, photo);
        attachToReview(review, video);

        for (Long viewer : new Long[]{null, other, owner}) {
            MockHttpServletResponse answer = fetch(photo, viewer);
            assertThat(answer.getStatus()).isEqualTo(200);
            assertThat(answer.getContentType()).isEqualTo("image/jpeg");
            assertThat(answer.getHeader("Cache-Control")).isEqualTo(PUBLIC);
            assertThat(answer.getHeaderValues("X-Content-Type-Options")).containsExactly("nosniff");
        }
        MockHttpServletResponse movie = fetch(video, null);
        assertThat(movie.getStatus()).isEqualTo(200);
        assertThat(movie.getContentType()).isEqualTo("video/mp4");

        // A place labelled "đã đóng cửa" is still on the Map with its reviews
        jdbc.update("UPDATE places SET is_closed = TRUE WHERE id = ?", place);
        assertThat(fetch(photo, null).getHeader("Cache-Control")).isEqualTo(PUBLIC);
    }

    @Test
    void aFileOfAReviewTheMapDoesNotShowToEveryoneIsShownOnlyToItsOwner() throws Exception {
        // The review is being scanned (also after an edit with new files), hidden by reports, rejected or removed
        for (String status : List.of("PROCESSING", "HIDDEN", "REJECTED", "REMOVED")) {
            String key = file("review", owner, "PASSED", "jpg", "image/jpeg");
            attachToReview(review(owner, place(other, "ACTIVE"), status), key);
            assertOwnerOnly(key, "review " + status);
        }
        // A published review of a place not on the Map for everyone: the first review of a pending pin, a removed or
        // rejected place, a place merged into another one (the Map shows the reviews of the place it went into)
        for (String status : List.of("PENDING", "REMOVED", "REJECTED", "MERGED")) {
            String key = file("review", owner, "PASSED", "jpg", "image/jpeg");
            attachToReview(review(owner, place(owner, status), "PUBLISHED"), key);
            assertOwnerOnly(key, "place " + status);
        }
        // The file itself still being scanned or rejected, on a published review
        for (String scan : List.of("PROCESSING", "REJECTED")) {
            String key = file("review", owner, scan, "jpg", "image/jpeg");
            attachToReview(review(owner, place(other, "ACTIVE"), "PUBLISHED"), key);
            assertOwnerOnly(key, "scan " + scan);
        }
    }

    @Test
    void aReviewFileOfALockedOrDeletedAuthorIsShownToNobody() throws Exception {
        String key = file("review", owner, "PASSED", "jpg", "image/jpeg");
        attachToReview(review(owner, place(other, "ACTIVE"), "PUBLISHED"), key);
        assertThat(fetch(key, null).getStatus()).isEqualTo(200);

        for (String status : List.of("LOCKED", "DELETED")) {
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, owner);
            assertNotFound(key, null);
            assertNotFound(key, other);
            assertNotFound(key, owner);
        }
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", owner);
        assertThat(fetch(key, other).getHeader("Cache-Control")).isEqualTo(PUBLIC);
    }

    @Test
    void filesAttachedToNothingAndTagProofsAreShownOnlyToTheirOwner() throws Exception {
        String loose = file(owner, "PASSED", "jpg", "image/jpeg");
        assertNotFound(loose, null);
        assertNotFound(loose, other);
        assertThat(fetch(loose, owner).getHeader("Cache-Control")).isEqualTo(OWNER_ONLY);

        // A proof photo of an identity-tag request (an identity document)
        String proof = file(owner, "PASSED", "jpg", "image/jpeg");
        long tagType = jdbc.queryForObject("SELECT id FROM tag_types WHERE name = 'Mentor'", Long.class);
        long proofType = jdbc.queryForObject("SELECT MIN(id) FROM tag_proof_types WHERE tag_type_id = ?", Long.class,
                tagType);
        jdbc.update("""
                INSERT INTO tag_requests (user_id, tag_type_id, proof_type_id, proof_media_id, status, expires_at)
                VALUES (?, ?, ?, (SELECT id FROM media WHERE storage_key = ?), 'APPROVED', ?)""",
                owner, tagType, proofType, proof, Timestamp.from(Instant.now().plus(Duration.ofDays(14))));
        assertNotFound(proof, null);
        assertNotFound(proof, other);
        assertThat(fetch(proof, owner).getHeader("Cache-Control")).isEqualTo(OWNER_ONLY);
    }

    @Test
    void anythingButAStoredKeyIsRefused() throws Exception {
        String key = file(owner, "PASSED", "jpg", "image/jpeg");
        attach(blog(owner, "PUBLISHED"), key);
        assertThat(fetch(key, null).getStatus()).isEqualTo(200);
        String name = key.substring(key.lastIndexOf('/') + 1);

        // Not a stored key (most are not even in the form of one, checked before any lookup): 404
        for (String path : List.of("/media/", "/media", "/media/" + key.toUpperCase(), "/media/" + key + ".bak",
                "/media/" + key.replace(".jpg", ".JPG"), "/media/blog/" + name, "/media/x/" + key,
                "/media/" + key.replace("blog/", "blog-/"), "/media/blog/2026/10/not-a-uuid.jpg",
                "/media/application.properties", "/media/test-uploads/" + key)) {
            assertThat(mvc.perform(get(URI.create(path))).andReturn().getResponse().getStatus()).as(path)
                    .isEqualTo(404);
        }
        // Traversal and encoded separators: refused by the firewall (400) or by the key check (404), never served
        for (String path : List.of("/media/../application.properties", "/media/blog/../../pom.xml",
                "/media/" + key.replace("/", "%2F"), "/media/" + key.replace("/", "%2f"),
                "/media/" + key.replace("/", "%5C"), "/media/%2e%2e/" + key, "/media/blog/2026/10/..%2F..%2Fpom.xml",
                "/media/" + key + "%00", "/media/" + key + "%20", "/media/" + key.replace("blog/", "blog//"),
                "/media/./" + key, "/media/" + key + ";jsessionid=x")) {
            MockHttpServletResponse answer = mvc.perform(get(URI.create(path))).andReturn().getResponse();
            assertThat(answer.getStatus()).as(path).isIn(400, 404);
            assertThat(answer.getContentAsString(StandardCharsets.UTF_8)).as(path).doesNotContain("bytes of");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void assertNotFound(String key, Long viewer) throws Exception {
        MockHttpServletResponse answer = fetch(key, viewer);
        assertThat(answer.getStatus()).as("%s as %s", key, viewer).isEqualTo(404);
        assertThat(answer.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("bytes of");
        assertThat(String.valueOf(answer.getHeader("Cache-Control"))).doesNotContain("public");
    }

    private void assertOwnerOnly(String key, String what) throws Exception {
        assertNotFound(key, null);
        assertNotFound(key, other);
        MockHttpServletResponse mine = fetch(key, owner);
        assertThat(mine.getStatus()).as(what).isEqualTo(200);
        assertThat(mine.getHeader("Cache-Control")).as(what).isEqualTo(OWNER_ONLY);
    }

    private MockHttpServletResponse fetch(String key, Long viewer) throws Exception {
        var request = get("/media/" + key);
        if (viewer != null) {
            request.session(session(viewer));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private String file(long ownerId, String scanStatus, String extension, String mimeType) {
        return file("blog", ownerId, scanStatus, extension, mimeType);
    }

    /** A stored file and its media row, in the form MediaService makes ("blog/yyyy/MM/uuid.ext"). */
    private String file(String folder, long ownerId, String scanStatus, String extension, String mimeType) {
        String key = folder + "/2026/10/" + UUID.randomUUID() + "." + extension;
        byte[] bytes = ("bytes of " + key).getBytes(StandardCharsets.UTF_8);
        storage.put(key, bytes);
        keys.add(key);
        jdbc.update("""
                INSERT INTO media (owner_id, media_type, storage_key, mime_type, size_bytes, scan_status)
                VALUES (?, ?, ?, ?, ?, ?)""", ownerId, mimeType.startsWith("video") ? "VIDEO" : "IMAGE", key, mimeType,
                bytes.length, scanStatus);
        return key;
    }

    private long blog(long authorId, String status) {
        String title = "Media " + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO blogs (author_id, blog_type, title, content, status) VALUES (?, 'POST', ?, 'Ảnh', ?)""",
                authorId, title, status);
        return jdbc.queryForObject("SELECT id FROM blogs WHERE title = ?", Long.class, title);
    }

    private void attach(long blogId, String key) {
        jdbc.update("INSERT INTO blog_media (blog_id, media_id) SELECT ?, id FROM media WHERE storage_key = ?",
                blogId, key);
    }

    /** A place in this status; a MERGED one points at a new ACTIVE place (the schema requires it). */
    private long place(long createdBy, String status) {
        Long mergedInto = "MERGED".equals(status) ? place(createdBy, "ACTIVE") : null;
        String name = "Media place " + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places (name_vi, category_id, lat, lng, source, status, merged_into_id, created_by)
                VALUES (?, 'food', 35.7, 139.7, 'TEAM', ?, ?, ?)""", name, status, mergedInto, createdBy);
        return jdbc.queryForObject("SELECT id FROM places WHERE name_vi = ?", Long.class, name);
    }

    private long review(long authorId, long placeId, String status) {
        jdbc.update("""
                INSERT INTO reviews (author_id, place_id, rating, content, status)
                VALUES (?, ?, 5, 'Quán ngon, có ảnh thực đơn.', ?)""", authorId, placeId, status);
        return jdbc.queryForObject("SELECT id FROM reviews WHERE author_id = ? AND place_id = ?", Long.class,
                authorId, placeId);
    }

    private void attachToReview(long reviewId, String key) {
        jdbc.update("INSERT INTO review_media (review_id, media_id) SELECT ?, id FROM media WHERE storage_key = ?",
                reviewId, key);
    }

    private long user(String status) {
        String email = "media-" + UUID.randomUUID() + "@test.local";
        jdbc.update("""
                INSERT INTO users (email, display_name, role, status, is_adult_confirmed, created_at)
                VALUES (?, 'Media Test', 'USER', ?, TRUE, ?)""",
                email, status, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        users.add(id);
        return id;
    }

    private static MockHttpSession session(long userId) {
        AppUserPrincipal me = new AppUserPrincipal(userId, "u" + userId + "@test.local", "Media Test", "USER", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(me, null, List.of(new SimpleGrantedAuthority("ROLE_USER")))));
        return session;
    }
}
