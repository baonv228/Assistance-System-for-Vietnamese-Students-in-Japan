package com.sep490.vsa.common.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Test accounts and sample content for LOCAL DEVELOPMENT ONLY (profile "local", the default on developer
 * machines). Never enable this profile on the deployed server: every account here uses password 123456.
 * <p>
 * Accounts (password 123456): user@, moderator@, academic@, admin@, plus mentor@ and kouhai@ who write the
 * sample reviews and posts. Content (places, reviews, events, blog, guide, phrases) is loaded once from
 * dev/sample-data.sql when the places table is empty.
 */
@Profile("local")
@Component
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String PASSWORD = "123456";

    private record Account(String email, String name, String role, int points, String stage, boolean inNippori,
                           int createdDaysAgo, List<String> tags) {
    }

    private static final List<Account> ACCOUNTS = List.of(
            new Account("user@gmail.com", "Trần Minh", "USER", 40, "ARR", true, 3, List.of()),
            new Account("moderator@gmail.com", "Thu", "MODERATOR", 0, null, false, 300, List.of()),
            new Account("academic@gmail.com", "Cô Yuki", "ACADEMIC_MANAGER", 0, null, false, 300,
                    List.of("Giáo viên tiếng Nhật", "Người Nhật")),
            new Account("admin@gmail.com", "Quản trị viên", "ADMIN", 0, null, false, 300, List.of()),
            new Account("mentor@gmail.com", "Lan Anh", "USER", 420, "ARR", true, 400, List.of("Mentor")),
            new Account("kouhai@gmail.com", "Hoàng", "USER", 130, "ARR", true, 40, List.of("Kouhai")));

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(JdbcTemplate jdbc, DataSource dataSource, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        int created = 0;
        for (Account account : ACCOUNTS) {
            if (createIfMissing(account)) {
                created++;
            }
        }
        if (created > 0) {
            log.warn("Created {} local test accounts (password {}). Do not use the 'local' profile on a server.",
                    created, PASSWORD);
        }
        Long places = jdbc.queryForObject("SELECT COUNT(*) FROM places", Long.class);
        if (places != null && places == 0) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("dev/sample-data.sql"));
            populator.setSqlScriptEncoding("UTF-8");
            populator.execute(dataSource);
            log.info("Loaded sample content from dev/sample-data.sql");
        }
        upgradeSamplePlaces();
    }

    /**
     * Sample places loaded before the switch to OpenStreetMap carried made-up Google place ids ("ChIJ-dev-…") and
     * showed "Google Maps": Ga Nippori becomes its real OpenStreetMap station, the others team places, as in
     * dev/sample-data.sql today. Does nothing on a database without those rows.
     */
    private void upgradeSamplePlaces() {
        int changed = jdbc.update("""
                UPDATE places SET osm_id = 'N772389775', source = 'OSM', google_place_id = NULL
                WHERE google_place_id = 'ChIJ-dev-nippori-station'
                  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM places WHERE osm_id = 'N772389775') taken)""");
        changed += jdbc.update("""
                UPDATE places SET source = 'TEAM', google_place_id = NULL
                WHERE google_place_id LIKE 'ChIJ-dev-%' AND lat IS NOT NULL AND lng IS NOT NULL""");
        if (changed > 0) {
            log.info("Moved {} sample places from made-up Google ids to OpenStreetMap / team places", changed);
        }
    }

    private boolean createIfMissing(Account a) {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, a.email());
        if (exists != null && exists > 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO users (email, password_hash, email_verified_at, display_name, role, is_adult_confirmed,
                                   area_id, stage, points, created_at)
                VALUES (?, ?, NOW(), ?, ?, TRUE,
                        CASE WHEN ? THEN (SELECT id FROM areas WHERE station = 'Nippori') END,
                        ?, ?, NOW() - INTERVAL ? DAY)""",
                a.email(), passwordEncoder.encode(PASSWORD), a.name(), a.role(), a.inNippori(), a.stage(),
                a.points(), a.createdDaysAgo());
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, a.email());
        // Accepted the current Terms, Privacy Policy and Community Rules
        jdbc.update("""
                INSERT INTO user_consents (user_id, document_id)
                SELECT ?, d.id FROM legal_documents d
                WHERE d.effective_at = (SELECT MAX(x.effective_at) FROM legal_documents x
                                        WHERE x.doc_type = d.doc_type AND x.effective_at <= NOW())""", userId);
        for (String tag : a.tags()) {
            jdbc.update("""
                    INSERT INTO user_tags (user_id, tag_type_id, granted_by)
                    SELECT ?, t.id, ? FROM tag_types t WHERE t.name = ?""", userId, userId, tag);
        }
        return true;
    }
}
