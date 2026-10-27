package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.area.AreaService;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceOption;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain SQL on {@code places}, {@code place_categories}, {@code category_osm_tags} and {@code api_usage} for the place
 * search and for saving OpenStreetMap places picked in a post. The Map feature may add its own entity for
 * {@code places}; this class only reads approved places and inserts OpenStreetMap places.
 */
@Repository
public class PlaceRepository {

    static final String OTHER_CATEGORY = "other";
    /** One degree of latitude, in metres. */
    private static final double METRES_PER_DEGREE = 111_320;

    private static final String OPTION_COLUMNS = """
            p.id, p.osm_id, p.name_vi, p.name_ja, c.name_vi AS category, p.address, p.source, p.lat, p.lng""";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public PlaceRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private static PlaceOption option(ResultSet rs, int i) throws SQLException {
        return new PlaceOption(rs.getLong("id"), rs.getString("osm_id"), rs.getString("name_vi"),
                rs.getString("name_ja"), rs.getString("category"), rs.getString("address"), rs.getString("source"),
                decimal(rs, "lat"), decimal(rs, "lng"));
    }

    private static Double decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    // ---------------------------------------------------------------- search

    /**
     * Approved places (any source: OpenStreetMap places already in ASVSJ, team places, approved community pins) whose
     * Vietnamese, Japanese or English name or address contains the keyword, ignoring accents and case ("pho" finds
     * "Phở"). Names that start with the keyword come first.
     */
    public List<PlaceOption> search(String keyword, int limit) {
        String kw = escapeLike(keyword);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kw", "%" + kw + "%")
                .addValue("prefix", kw + "%")
                .addValue("limit", limit);
        return named.query("SELECT " + OPTION_COLUMNS + """

                FROM places p JOIN place_categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE'
                  AND (p.name_vi COLLATE utf8mb4_0900_ai_ci LIKE :kw OR p.name_ja COLLATE utf8mb4_0900_ai_ci LIKE :kw
                       OR p.name_en COLLATE utf8mb4_0900_ai_ci LIKE :kw OR p.address COLLATE utf8mb4_0900_ai_ci LIKE :kw)
                ORDER BY (p.name_vi COLLATE utf8mb4_0900_ai_ci LIKE :prefix) DESC, p.name_vi
                LIMIT :limit""", params, PlaceRepository::option);
    }

    /**
     * An OpenStreetMap id ASVSJ already has.
     *
     * @param status status of {@code shown}
     * @param shown  the place itself, or the place it was merged into
     */
    public record Known(String externalId, String status, PlaceOption shown) {
    }

    /** The ASVSJ rows of these OpenStreetMap ids, keyed by id. A merged place reports its target. */
    public Map<String, Known> byOsmIds(Collection<String> osmIds) {
        Map<String, Known> out = new HashMap<>();
        if (osmIds.isEmpty()) {
            return out;
        }
        named.query("SELECT g.osm_id AS asked, p.status AS shown_status, " + OPTION_COLUMNS + """

                FROM places g
                JOIN places p ON p.id = CASE WHEN g.status = 'MERGED' THEN g.merged_into_id ELSE g.id END
                JOIN place_categories c ON c.id = p.category_id
                WHERE g.osm_id IN (:ids)""", Map.of("ids", osmIds), rs -> {
            String asked = rs.getString("asked");
            out.put(asked, new Known(asked, rs.getString("shown_status"), option(rs, 0)));
        });
        return out;
    }

    /**
     * An approved place with a position, and all its names: to recognise an OpenStreetMap place that ASVSJ already
     * has without its OSM id (a team place, a community pin, a place saved from Google Maps before).
     */
    public record Nearby(PlaceOption option, String categoryId, String nameEn, double lat, double lng) {
    }

    /** Approved places within about {@code metres} of any of these points ({lat, lng}). */
    public List<Nearby> activeNear(List<double[]> points, double metres) {
        if (points.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> boxes = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double lat = points.get(i)[0];
            double lng = points.get(i)[1];
            double dLat = metres / METRES_PER_DEGREE;
            double dLng = metres / (METRES_PER_DEGREE * Math.max(0.1, Math.cos(Math.toRadians(lat))));
            boxes.add("(p.lat BETWEEN :s" + i + " AND :n" + i + " AND p.lng BETWEEN :w" + i + " AND :e" + i + ")");
            params.addValue("s" + i, lat - dLat).addValue("n" + i, lat + dLat)
                    .addValue("w" + i, lng - dLng).addValue("e" + i, lng + dLng);
        }
        return named.query("SELECT " + OPTION_COLUMNS + ", p.category_id, p.name_en" + """

                FROM places p JOIN place_categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE' AND p.lat IS NOT NULL AND p.lng IS NOT NULL AND (""" + String.join(" OR ", boxes)
                + ") LIMIT 200", params, (rs, i) -> new Nearby(option(rs, i), rs.getString("category_id"),
                rs.getString("name_en"), rs.getDouble("lat"), rs.getDouble("lng")));
    }

    // ---------------------------------------------------------------- categories

    public record Category(String id, String name) {
    }

    /** ASVSJ category of each of these OSM tags ("shop=convenience") that has one (category_osm_tags). */
    public Map<String, Category> categoriesOfTags(Collection<String> osmTags) {
        Map<String, Category> out = new HashMap<>();
        if (osmTags.isEmpty()) {
            return out;
        }
        named.query("""
                SELECT t.osm_tag, c.id, c.name_vi FROM category_osm_tags t
                JOIN place_categories c ON c.id = t.category_id WHERE t.osm_tag IN (:tags)""",
                Map.of("tags", osmTags),
                rs -> {
                    out.put(rs.getString(1), new Category(rs.getString(2), rs.getString(3)));
                });
        return out;
    }

    /** "Địa điểm khác", for places whose OSM tag fits no category (migration V3). */
    public Category otherCategory() {
        return jdbc.query("SELECT id, name_vi FROM place_categories WHERE id = ?",
                        (rs, i) -> new Category(rs.getString(1), rs.getString(2)), OTHER_CATEGORY)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("place category 'other' is missing (migration V3)"));
    }

    // ---------------------------------------------------------------- saving an OpenStreetMap place

    /** An OpenStreetMap place to add to ASVSJ when the post that links it is saved. */
    public record NewOsmPlace(String osmId, String nameVi, String nameJa, String nameEn, String categoryId,
                              String address, double lat, double lng) {
    }

    /**
     * Adds an OpenStreetMap place to ASVSJ: source OSM, ACTIVE at once (map places skip the Moderator), with its OSM
     * id, names, address and position (OSM data may be stored, ODbL). When another request
     * saved the same place a moment earlier, that row is returned instead.
     *
     * @return the place id, and whether this call created it
     */
    public Saved insertOsmPlace(NewOsmPlace place, long createdBy) {
        KeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO places (osm_id, name_vi, name_ja, name_en, category_id, address, lat, lng,
                                            source, status, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OSM', 'ACTIVE', ?)""", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, place.osmId());
                ps.setString(2, place.nameVi());
                ps.setString(3, place.nameJa());
                ps.setString(4, place.nameEn());
                ps.setString(5, place.categoryId());
                ps.setString(6, place.address());
                ps.setBigDecimal(7, BigDecimal.valueOf(place.lat()).setScale(6, RoundingMode.HALF_UP));
                ps.setBigDecimal(8, BigDecimal.valueOf(place.lng()).setScale(6, RoundingMode.HALF_UP));
                ps.setLong(9, createdBy);
                return ps;
            }, key);
        } catch (DuplicateKeyException e) {
            // osm_id is unique: someone linked the same place at the same moment. Only this statement failed
            // (InnoDB), the surrounding transaction goes on. FOR SHARE reads the row the other transaction just
            // committed; a plain SELECT would read this transaction's older snapshot and find nothing.
            Long existing = jdbc.queryForObject("SELECT id FROM places WHERE osm_id = ? FOR SHARE",
                    Long.class, place.osmId());
            return new Saved(existing, false);
        }
        long id = key.getKey().longValue();
        AreaService.assignPlace(jdbc, id);
        jdbc.update("""
                INSERT INTO place_history (place_id, action, actor_id, detail)
                VALUES (?, 'CREATED', ?, JSON_OBJECT('source', 'OSM', 'via', 'BLOG'))""", id, createdBy);
        return new Saved(id, true);
    }

    public record Saved(Long id, boolean created) {
    }

    // ---------------------------------------------------------------- api_usage

    public void addUsage(String service, LocalDate day) {
        jdbc.update("""
                INSERT INTO api_usage (service, usage_date, amount) VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE amount = amount + 1""", service, Date.valueOf(day));
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
