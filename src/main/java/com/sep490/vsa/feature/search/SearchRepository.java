package com.sep490.vsa.feature.search;

import com.sep490.vsa.feature.blog.ActiveAuthors;
import com.sep490.vsa.feature.search.SearchDtos.PlaceHit;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Read-only SQL of the site search for the one part no other feature lists by text without asking OpenStreetMap: the
 * Senpai ơi places (the Map's own search also asks the free map service, which must not run on every key press).
 * Like feature/home it only reads other features' tables.
 */
@Repository
public class SearchRepository {

    /**
     * A place matches by its Vietnamese, Japanese or English name or its address (anywhere, accents and case ignored,
     * as the Blog search) or by its category name at the start of a word, as the Map's search ("ga" finds "Ga tàu",
     * not "Ngân hàng").
     */
    private static final String PLACE_MATCH = """
            p.status = 'ACTIVE'
            AND (p.name_vi COLLATE utf8mb4_0900_ai_ci LIKE :kw OR p.name_ja COLLATE utf8mb4_0900_ai_ci LIKE :kw
                 OR p.name_en COLLATE utf8mb4_0900_ai_ci LIKE :kw OR p.address COLLATE utf8mb4_0900_ai_ci LIKE :kw
                 OR CONCAT(' ', c.name_vi) COLLATE utf8mb4_0900_ai_ci LIKE :word)""";
    private static final String NAME_MATCH = """
            (p.name_vi COLLATE utf8mb4_0900_ai_ci LIKE :kw OR p.name_ja COLLATE utf8mb4_0900_ai_ci LIKE :kw
             OR p.name_en COLLATE utf8mb4_0900_ai_ci LIKE :kw)""";

    private final NamedParameterJdbcTemplate jdbc;

    public SearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countPlaces(String keyword) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM places p JOIN place_categories c ON c.id = p.category_id WHERE " + PLACE_MATCH,
                params(keyword), Long.class);
        return n == null ? 0 : n;
    }

    /**
     * The first {@code limit} places: a name that starts with the text, then a name holding it, then those found by
     * address or category; within each, the most reviewed first, then by name. Review numbers count published place
     * reviews of active accounts, as the Map's pins.
     */
    public List<PlaceHit> places(String keyword, int limit) {
        MapSqlParameterSource params = params(keyword).addValue("limit", limit);
        return jdbc.query("""
                SELECT p.id, p.name_vi, p.name_ja, p.name_en, c.name_vi AS category_name, c.icon, a.display_name AS area_name,
                       p.address, p.is_closed, COALESCE(rs.n, 0) AS review_count, rs.rating
                FROM places p JOIN place_categories c ON c.id = p.category_id
                LEFT JOIN areas a ON a.id = p.area_id
                LEFT JOIN (SELECT r.place_id, COUNT(*) AS n, AVG(r.rating) AS rating FROM reviews r
                           WHERE r.place_id IS NOT NULL AND r.status = 'PUBLISHED' AND %s
                           GROUP BY r.place_id) rs ON rs.place_id = p.id
                WHERE %s
                ORDER BY (p.name_vi COLLATE utf8mb4_0900_ai_ci LIKE :prefix OR p.name_ja COLLATE utf8mb4_0900_ai_ci LIKE :prefix
                          OR p.name_en COLLATE utf8mb4_0900_ai_ci LIKE :prefix) DESC,
                         %s DESC, review_count DESC, p.name_vi, p.id
                LIMIT :limit""".formatted(ActiveAuthors.of("r"), PLACE_MATCH, NAME_MATCH),
                params,
                (rs, i) -> new PlaceHit(rs.getLong("id"), rs.getString("name_vi"), rs.getString("name_ja"),
                        rs.getString("name_en"), rs.getString("category_name"), rs.getString("icon"),
                        rs.getString("area_name"), rs.getString("address"), rs.getBoolean("is_closed"),
                        rs.getLong("review_count"), rating(rs.getBigDecimal("rating"))));
    }

    private static MapSqlParameterSource params(String keyword) {
        String kw = escapeLike(keyword);
        return new MapSqlParameterSource()
                .addValue("kw", "%" + kw + "%")
                .addValue("prefix", kw + "%")
                .addValue("word", "% " + kw + "%");
    }

    /** One decimal, as the Map shows it. */
    private static Double rating(BigDecimal average) {
        return average == null ? null : average.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
