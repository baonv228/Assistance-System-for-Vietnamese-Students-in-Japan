package com.sep490.vsa.feature.area;

import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.area.AreaDtos.AreaOption;
import com.sep490.vsa.feature.area.AreaDtos.Picker;
import com.sep490.vsa.feature.area.AreaDtos.Prefecture;
import com.sep490.vsa.feature.place.MapDtos.DevLocation;
import com.sep490.vsa.feature.place.MapService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Living areas (V10): the 47 prefectures and their 1,892 municipalities / wards of designated cities, from the Geolonia
 * address data (CC BY 4.0). Users store only an area id; a point picked on the map is used once to suggest the
 * nearest area and is never stored.
 */
@Service
public class AreaService {

    static final String NOT_IN_JAPAN = "Vị trí này không ở Nhật Bản. Hãy chọn một điểm ở Nhật.";
    static final int SEARCH_LIMIT = 12;

    private static final String COLUMNS = """
            SELECT a.id, a.prefecture_code, a.name_ja, a.name_en, a.display_name, a.lat, a.lng FROM areas a
            """;
    private static final RowMapper<AreaOption> AREA = (rs, i) -> new AreaOption(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), decimal(rs.getBigDecimal(6)),
            decimal(rs.getBigDecimal(7)));

    private final JdbcTemplate jdbc;
    private final DevLocation devLocation;

    public AreaService(JdbcTemplate jdbc, @Value("${app.map.dev-location:}") String devLocation) {
        this.jdbc = jdbc;
        this.devLocation = MapService.parseDevLocation(devLocation);
    }

    public Picker picker() {
        List<Prefecture> prefectures = jdbc.query(
                "SELECT code, name_ja, name_en, lat, lng FROM prefectures ORDER BY code",
                (rs, i) -> new Prefecture(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).doubleValue(), rs.getBigDecimal(5).doubleValue()));
        return new Picker(prefectures, devLocation);
    }

    /** The areas of one prefecture in the official order (wards of a designated city together). */
    public List<AreaOption> inPrefecture(String prefectureCode) {
        return jdbc.query(COLUMNS + "WHERE a.is_active AND a.prefecture_code = ? ORDER BY a.sort_order, a.id",
                AREA, prefectureCode);
    }

    /**
     * Areas whose name (romaji, kanji or kana) or prefecture contains the text, accents and case ignored by the
     * column collation; names starting with it first. "Arakawa ku" finds "Arakawa-ku".
     */
    public List<AreaOption> search(String query) {
        String q = query == null ? "" : query.strip().replace('-', ' ').replaceAll("\\s+", " ");
        if (q.isEmpty()) {
            return List.of();
        }
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String start = like.substring(1);
        return jdbc.query(COLUMNS + """
                JOIN prefectures p ON p.code = a.prefecture_code
                WHERE a.is_active AND (REPLACE(a.name_en, '-', ' ') LIKE ? OR a.name_ja LIKE ? OR a.name_kana LIKE ?
                      OR REPLACE(a.display_name, '-', ' ') LIKE ? OR CONCAT(p.name_ja, a.name_ja) LIKE ?)
                ORDER BY (REPLACE(a.name_en, '-', ' ') LIKE ? OR a.name_ja LIKE ? OR a.name_kana LIKE ?) DESC,
                         a.sort_order, a.id
                LIMIT ?""", AREA, like, like, like, like, like, start, start, start, SEARCH_LIMIT);
    }

    /**
     * The area whose representative point is nearest (a suggestion the user confirms; may be the neighbour near a
     * border). Points outside Japan answer 400.
     */
    public AreaOption nearest(double lat, double lng) {
        if (!inJapan(lat, lng)) {
            throw ApiException.badRequest(NOT_IN_JAPAN);
        }
        return jdbc.query(COLUMNS + """
                WHERE a.is_active AND a.lat IS NOT NULL
                ORDER BY POW(a.lat - ?, 2) + POW((a.lng - ?) * COS(RADIANS(?)), 2), a.id LIMIT 1""",
                AREA, lat, lng, lat).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_IN_JAPAN));
    }

    /** The area of a place at this position, or null outside Japan (places in Vietnam have none). */
    public Long areaIdAt(double lat, double lng) {
        return inJapan(lat, lng) ? jdbc.query("""
                SELECT id FROM areas WHERE is_active AND lat IS NOT NULL
                ORDER BY POW(lat - ?, 2) + POW((lng - ?) * COS(RADIANS(?)), 2), id LIMIT 1""",
                (rs, i) -> rs.getLong(1), lat, lng, lat).stream().findFirst().orElse(null) : null;
    }

    /**
     * Gives a new place in Japan the area nearest to its position (OSM places, community pins), in the caller's
     * transaction. Same rule as V10 for the places that existed then.
     */
    public static void assignPlace(JdbcTemplate jdbc, long placeId) {
        jdbc.update("""
                UPDATE places p
                SET p.area_id = (SELECT a.id FROM areas a WHERE a.is_active AND a.lat IS NOT NULL
                                 ORDER BY POW(a.lat - p.lat, 2) + POW((a.lng - p.lng) * COS(RADIANS(p.lat)), 2), a.id
                                 LIMIT 1)
                WHERE p.id = ? AND p.lat BETWEEN 20 AND 46 AND p.lng BETWEEN 122 AND 154""", placeId);
    }

    static boolean inJapan(double lat, double lng) {
        return lat >= 20 && lat <= 46 && lng >= 122 && lng <= 154;
    }

    public Optional<AreaOption> find(long id) {
        return jdbc.query(COLUMNS + "WHERE a.id = ? AND a.is_active", AREA, id).stream().findFirst();
    }

    private static Double decimal(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
