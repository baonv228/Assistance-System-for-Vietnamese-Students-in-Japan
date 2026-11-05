package com.sep490.vsa.common.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.media.local-dir=target/v19-seed-test-uploads",
        "app.places.osm.enabled=false",
        "app.moderation.retry-initial-delay-ms=3600000"
})
@ActiveProfiles("local")
class GuideFaqSeedTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String database = "asvsj_v19_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        properties.add("spring.datasource.url", () -> "jdbc:mysql://localhost:3306/" + database
                + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void snapshotRestoresContentImagesAndReferencesWithoutDuplicatingOnReplay() throws Exception {
        assertThat(count("guide_items")).isEqualTo(24);
        assertThat(count("guide_sections")).isEqualTo(26);
        assertThat(count("guide_preparation_tasks")).isEqualTo(12);
        assertThat(count("faq_entries")).isEqualTo(10);
        assertThat(count("faq_resources")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM faq_resources r JOIN guide_items g ON g.id=r.target_id
                WHERE r.kind='GUIDE' AND g.deleted_at IS NULL AND g.published=TRUE
                """, Integer.class)).isEqualTo(2);
        for (String key : jdbc.queryForList("SELECT storage_key FROM media WHERE owner_id=(SELECT id FROM users WHERE email='seed-content@local.invalid')", String.class)) {
            var bundled = new ClassPathResource("dev/guide-media/" + key);
            assertThat(Files.readAllBytes(Path.of("target/v19-seed-test-uploads", key)))
                    .isEqualTo(bundled.getContentAsByteArray());
        }
        jdbc.update("UPDATE faq_entries SET answer='Locally edited answer' WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM faq_entries) x)");
        var sql = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V19__local_faq_and_guide_test_data.sql"));
        sql.setSqlScriptEncoding("UTF-8");
        sql.execute(dataSource);
        assertThat(count("guide_items")).isEqualTo(24);
        assertThat(count("guide_sections")).isEqualTo(26);
        assertThat(count("faq_entries")).isEqualTo(10);
        assertThat(count("faq_resources")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM faq_entries WHERE answer='Locally edited answer'", Integer.class)).isEqualTo(1);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
