package com.sep490.vsa.feature.area;

import com.sep490.vsa.feature.place.MapDtos.DevLocation;

import java.util.List;

/** Bodies of /api/areas: the living-area picker (prefecture → municipality or ward). */
public final class AreaDtos {

    private AreaDtos() {
    }

    public record Prefecture(String code, String nameJa, String nameEn, double lat, double lng) {
    }

    /**
     * @param nameEn "Arakawa-ku" or "Kita-ku, Osaka-shi"
     * @param name   the full label, "Arakawa-ku, Tokyo · 東京都荒川区"
     */
    public record AreaOption(long id, String prefectureCode, String nameJa, String nameEn, String name,
                             Double lat, Double lng) {
    }

    /** @param devLocation the position "Vị trí của tôi" gives in a test environment ({@code app.map.dev-location}) */
    public record Picker(List<Prefecture> prefectures, DevLocation devLocation) {
    }
}
