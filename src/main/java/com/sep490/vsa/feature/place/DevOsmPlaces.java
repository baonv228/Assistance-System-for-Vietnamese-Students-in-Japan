package com.sep490.vsa.feature.place;

import com.sep490.vsa.common.text.VietnameseText;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for OpenStreetMap while developing offline ({@code app.places.dev-fake=true}): a few places in Tokyo and
 * Hanoi with ids starting "DEV-", so the search and the "new place" flow can be shown without the internet.
 * "Nippori Station" stands about 100 m from Ga Nippori of dev/sample-data.sql: the search shows it once, as the ASVSJ
 * place. Never turn this on outside development.
 */
public class DevOsmPlaces implements ExternalPlaces {

    private static final String JAPAN = "Nhật Bản";

    private static final List<Hit> PLACES = List.of(
            place("DEV-tokyo-tower", "Tokyo Tower", "Tháp Tokyo", "東京タワー", "Shibakōen 4, Minato, Tokyo, " + JAPAN,
                    "man_made=tower", "jp", 35.658449, 139.745536),
            place("DEV-nippori-station", "Nippori Station", null, "日暮里駅", "Higashi-Nippori 6-chome, Arakawa, Tokyo, " + JAPAN,
                    "railway=station", "jp", 35.728920, 139.771364),
            place("DEV-ueno-station", "Ueno Station", "Ga Ueno", "上野駅", "Ueno 7, Taito, Tokyo, " + JAPAN,
                    "railway=station", "jp", 35.711708, 139.776154),
            place("DEV-lawson-ueno", "Lawson", null, "ローソン", "Ueno 5, Taito, Tokyo, " + JAPAN,
                    "shop=convenience", "jp", 35.702811, 139.775430),
            place("DEV-sensoji", "Sensō-ji", "Chùa Sensō-ji", "浅草寺", "Asakusa 2, Taito, Tokyo, " + JAPAN,
                    "amenity=place_of_worship", "jp", 35.713403, 139.795527),
            place("DEV-todai", "The University of Tokyo", "Đại học Tokyo", "東京大学", "Hongo 7, Bunkyo, Tokyo, " + JAPAN,
                    "amenity=university", "jp", 35.712600, 139.762100),
            place("DEV-immigration", "Tokyo Regional Immigration Services Bureau", "Cục Quản lý Xuất nhập cảnh Tokyo",
                    "東京出入国在留管理局", "Konan 5, Minato, Tokyo, " + JAPAN, "office=government", "jp", 35.629700, 139.749300),
            place("DEV-vn-embassy", "Embassy of Vietnam", "Đại sứ quán Việt Nam tại Nhật Bản", "駐日ベトナム大使館",
                    "Motoyoyogicho, Shibuya, Tokyo, " + JAPAN, "office=diplomatic", "jp", 35.671600, 139.687800),
            place("DEV-ho-guom", "Hoàn Kiếm Lake", "Hồ Hoàn Kiếm", null, "Hoan Kiem Ward, Hà Nội, Việt Nam",
                    "water=lake", "vn", 21.028831, 105.852536),
            place("DEV-fpt-hoa-lac", "FPT University", "Trường Đại học FPT", null, "Hoa Lac Hi-Tech Park, Hà Nội, Việt Nam",
                    "amenity=university", "vn", 21.013528, 105.525262));

    private static Hit place(String id, String nameEn, String nameVi, String nameJa, String address, String tag,
                             String cc, double lat, double lng) {
        return new Hit(id, nameEn, nameVi == null ? nameEn : nameVi, nameJa, nameEn, address, tag, cc, lat, lng);
    }

    @Override
    public String engine() {
        return "DEV";
    }

    @Override
    public List<Hit> search(String text) {
        String q = VietnameseText.squash(text);
        return PLACES.stream()
                .filter(p -> VietnameseText.squash(String.join(" ", p.name(), p.nameVi(), String.valueOf(p.nameJa()),
                        p.address())).contains(q))
                .map(DevOsmPlaces::asSearchResult)
                .limit(OsmPlaces.SEARCH_KEPT)
                .toList();
    }

    @Override
    public List<Hit> searchStations(String name) {
        return search(name).stream().filter(p -> p.tag().equals("railway=station")).toList();
    }

    @Override
    public Map<String, Hit> lookup(Collection<String> ids) {
        Map<String, Hit> found = new LinkedHashMap<>();
        PLACES.stream().filter(p -> ids.contains(p.id())).forEach(p -> found.put(p.id(), p));
        return found;
    }

    /** Like Photon, the search knows only the name to show, not the Vietnamese one. */
    private static Hit asSearchResult(Hit p) {
        return new Hit(p.id(), p.name(), null, null, null, p.address(), p.tag(), p.countryCode(), p.lat(), p.lng());
    }
}
