package com.sep490.vsa.feature.place;

import com.sep490.vsa.feature.place.ExternalPlaces.Hit;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceOption;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceSearch;
import com.sep490.vsa.feature.place.PlaceRepository.Nearby;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceServiceRulesTest {

    private static Hit hit(String id, String name, String tag, double lat, double lng) {
        return new Hit(id, name, null, null, null, null, tag, "jp", lat, lng);
    }

    @Test
    void vietnameseStationWordsAskForAStation() {
        assertThat(PlaceService.stationName("ga Nippori")).isEqualTo("Nippori");
        assertThat(PlaceService.stationName("  Nhà ga  Ueno ")).isEqualTo("Ueno");
        assertThat(PlaceService.stationName("nha ga Ueno")).isEqualTo("Ueno");
        assertThat(PlaceService.stationName("Ga")).isNull();
        assertThat(PlaceService.stationName("gà rán Nippori")).isNull();
        assertThat(PlaceService.stationName("Nippori")).isNull();
        assertThat(PlaceService.stationName("galaxy")).isNull();
    }

    @Test
    void namesAreComparedWithoutTheWordForStation() {
        assertThat(PlaceService.nameKey("Ga Nippori")).isEqualTo("nippori");
        assertThat(PlaceService.nameKey("Nippori Station")).isEqualTo("nippori");
        assertThat(PlaceService.nameKey("Nippori")).isEqualTo("nippori");
        assertThat(PlaceService.nameKey("日暮里駅")).isEqualTo("日暮里");
        assertThat(PlaceService.nameKey("Ueno Railway Station")).isEqualTo("ueno");
        assertThat(PlaceService.nameKey("Sensō-ji")).isEqualTo("sensoji");
        assertThat(PlaceService.nameKey("Nhà hát Hồ Gươm")).isEqualTo("nhahathoguom");
        assertThat(PlaceService.nameKey("Station")).isEqualTo("station");
    }

    @Test
    void aStationIsListedOnceThoughTheMapHasAPointPerLine() {
        // Real Photon answer for "nippori" (JR, Keisei and Nippori-Toneri Liner points, two station buildings)
        List<Hit> hits = List.of(
                hit("N772389775", "Nippori", "railway=station", 35.7289204, 139.7713635),
                hit("N172670767", "Nippori", "railway=station", 35.7283798, 139.7709817),
                hit("W253844168", "Nippori Station", "building=train_station", 35.7280064, 139.7713776),
                hit("W182119141", "Nippori", "building=train_station", 35.7281156, 139.7705503),
                hit("W170949956", "Nippori Yasunaga Building", "building=yes", 35.7281702, 139.7723895),
                hit("N3399014638", "Nippori Dental Clinic", "amenity=dentist", 35.7282453, 139.7720659),
                // Ueno: the station building and a station point 280 m apart are one station
                hit("R10758384", "Ueno Railway Station", "building=train_station", 35.7139483, 139.7775579),
                hit("N7786715957", "Ueno", "railway=station", 35.7117081, 139.7761542),
                // Two Lawson shops 240 m apart are two shops
                hit("N992577008", "Lawson", "shop=convenience", 35.7048075, 139.7743291),
                hit("W636881980", "Lawson", "shop=convenience", 35.7069492, 139.7746846));
        assertThat(PlaceService.distinctPlaces(hits, Set.of())).extracting(Hit::id).containsExactly(
                "N772389775", "W170949956", "N3399014638", "R10758384", "N992577008", "W636881980");
    }

    @Test
    void chainBranchesCloseTogetherStayApartButAShopAndItsBuildingAreOne() {
        List<Hit> hits = List.of(
                hit("N1", "Lawson", "shop=convenience", 35.70000, 139.77000),
                hit("N2", "Lawson", "shop=convenience", 35.70100, 139.77050),      // 120 m away: another branch
                hit("N3", "Ueno", "amenity=restaurant", 35.71400, 139.77700),
                hit("N4", "Ueno", "railway=station", 35.71170, 139.77615),         // 270 m: a station, not the restaurant
                hit("N5", "Foo Cafe", "amenity=cafe", 35.69000, 139.70000),
                hit("W5", "Foo Cafe", "building=yes", 35.69020, 139.70010),        // 24 m: the cafe's building
                hit("N1", "Lawson", "shop=convenience", 35.70000, 139.77000));     // the same id again
        assertThat(PlaceService.distinctPlaces(hits, Set.of())).extracting(Hit::id)
                .containsExactly("N1", "N2", "N3", "N4", "N5");
        // The copy ASVSJ already knows stands for both
        assertThat(PlaceService.distinctPlaces(hits, Set.of("W5"))).extracting(Hit::id)
                .containsExactly("N1", "N2", "N3", "N4", "W5");
    }

    @Test
    void aMapPlaceAsvsjHasWithoutItsOsmIdIsRecognisedByNameCloseBy() {
        // Ga Nippori saved from Google Maps before (no OSM id) and the real OSM station point 110 m away
        Nearby gaNippori = new Nearby(new PlaceOption(1L, null, "Ga Nippori", "日暮里駅", "Ga tàu", null, "GOOGLE"),
                "station", "Nippori Station", 35.7281, 139.7707);
        Nearby hana = new Nearby(new PlaceOption(2L, null, "Quán cơm Hana", "はな食堂", "Quán ăn", null, "USER"),
                "food", "Hana Shokudo", 35.7267, 139.7735);
        List<Nearby> near = List.of(gaNippori, hana);

        assertThat(PlaceService.sameAsvsjPlace(hit("N772389775", "Nippori", "railway=station", 35.7289204, 139.7713635),
                near)).contains(gaNippori);
        // By the Japanese name too
        assertThat(PlaceService.sameAsvsjPlace(new Hit("N1", "日暮里", null, "日暮里", null, null, "railway=station", "jp",
                35.7283, 139.7709), near)).contains(gaNippori);
        assertThat(PlaceService.sameAsvsjPlace(hit("N2", "Hana Shokudo", "amenity=restaurant", 35.7268, 139.7736), near))
                .contains(hana);
        // Same name but 300 m away: another restaurant
        assertThat(PlaceService.sameAsvsjPlace(hit("N3", "Hana Shokudo", "amenity=restaurant", 35.7294, 139.7735), near))
                .isEmpty();
        // Close by but another name
        assertThat(PlaceService.sameAsvsjPlace(hit("N4", "Lawson", "shop=convenience", 35.7268, 139.7736), near))
                .isEmpty();
        // Another branch of a chain 140 m from the one ASVSJ has
        Nearby lawson = new Nearby(new PlaceOption(3L, null, "Lawson", null, "Cửa hàng tiện lợi", null, "TEAM"),
                "konbini", "Lawson", 35.7000, 139.7700);
        assertThat(PlaceService.sameAsvsjPlace(hit("N5", "Lawson", "shop=convenience", 35.7012, 139.7704),
                List.of(lawson))).isEmpty();
        assertThat(PlaceService.sameAsvsjPlace(hit("N6", "Lawson", "shop=convenience", 35.7002, 139.7701),
                List.of(lawson))).contains(lawson);
        // A restaurant named like the station 300 m away is not the station
        assertThat(PlaceService.sameAsvsjPlace(hit("N7", "Nippori", "amenity=restaurant", 35.7300, 139.7735), near))
                .isEmpty();
    }

    @Test
    void aMapPlaceIsShownAsTheAsvsjPlaceWhenOnlyItsTwinIsCloseToIt() {
        // N3 and W2 are one cafe (same name, 40 m apart); W2 is 30 m from the ASVSJ place P, N3 is 70 m from it
        Nearby p = new Nearby(new PlaceOption(7L, null, "Cafe Hoa", null, "Quán ăn", null, "TEAM"), "food", "Cafe Hoa",
                35.70000, 139.70000);
        Hit n3 = hit("N3", "Cafe Hoa", "amenity=cafe", 35.70063, 139.70000);
        Hit w2 = hit("W2", "Cafe Hoa", "building=yes", 35.70027, 139.70000);
        assertThat(PlaceService.metres(n3.lat(), n3.lng(), w2.lat(), w2.lng())).isCloseTo(40, offset(0.5));
        assertThat(PlaceService.metres(w2.lat(), w2.lng(), p.lat(), p.lng())).isCloseTo(30, offset(0.5));
        assertThat(PlaceService.metres(n3.lat(), n3.lng(), p.lat(), p.lng())).isCloseTo(70, offset(0.5));

        PlaceRepository repository = repository();
        when(repository.activeNear(anyList(), anyDouble())).thenReturn(List.of(p));
        ExternalPlaces map = map();
        when(map.search(anyString())).thenReturn(List.of(n3, w2));

        PlaceSearch found = new PlaceService(repository, map, Clock.systemUTC()).search(1L, "Cafe Hoa");
        assertThat(found.items()).extracting(PlaceOption::id).containsExactly(7L);    // P, and no new place
        // Both copies were compared with the ASVSJ places, not only the one kept
        verify(repository).activeNear(argThat(points -> points.size() == 2), anyDouble());
    }

    @Test
    void theSearchMemoryKeepsJapaneseVoicingMarksAndAccentsApart() {
        PlaceRepository repository = repository();
        ExternalPlaces map = map();
        when(map.search(anyString())).thenReturn(List.of());
        PlaceService service = new PlaceService(repository, map, Clock.systemUTC());

        for (String text : List.of("パン", "バン", "ハン", " パン ", "phở", "pho", "PHO", "Phở  Hà   Nội", "phở hà nội")) {
            service.search(1L, text);
        }
        for (String asked : List.of("パン", "バン", "ハン", "phở", "pho", "Phở  Hà   Nội")) {
            verify(map, times(1)).search(asked);
        }
        verify(map, times(6)).search(anyString());            // " パン ", "PHO" and "phở hà nội" came from memory
        assertThat(PlaceService.searchKey(" Phở 　 HÀ  Nội ")).isEqualTo("phở hà nội");
    }

    private static PlaceRepository repository() {
        PlaceRepository repository = mock(PlaceRepository.class);
        when(repository.search(anyString(), anyInt())).thenReturn(List.of());
        when(repository.otherCategory()).thenReturn(new PlaceRepository.Category("other", "Địa điểm khác"));
        return repository;
    }

    private static ExternalPlaces map() {
        ExternalPlaces map = mock(ExternalPlaces.class);
        when(map.engine()).thenReturn("OSM");
        when(map.enabled()).thenReturn(true);
        return map;
    }

    @Test
    void distancesAreInMetres() {
        assertThat(PlaceService.metres(35.0, 139.0, 35.001, 139.0)).isCloseTo(111.2, offset(0.5));
        assertThat(PlaceService.metres(35.0, 139.0, 35.0, 139.001)).isCloseTo(91.1, offset(0.5));
    }

    @Test
    void mapNoiseIsLeftOutButStationsStay() {
        assertThat(OsmPlaces.noise("highway", "bus_stop")).isTrue();
        assertThat(OsmPlaces.noise("railway", "stop")).isTrue();
        assertThat(OsmPlaces.noise("railway", "platform")).isTrue();
        assertThat(OsmPlaces.noise("public_transport", "platform")).isTrue();
        assertThat(OsmPlaces.noise("amenity", "parking")).isTrue();
        assertThat(OsmPlaces.noise("emergency", "assembly_point")).isTrue();
        assertThat(OsmPlaces.noise("railway", "station")).isFalse();
        assertThat(OsmPlaces.noise("public_transport", "station")).isFalse();
        assertThat(OsmPlaces.noise("building", "train_station")).isFalse();
        assertThat(OsmPlaces.noise("amenity", "cafe")).isFalse();
        assertThat(OsmPlaces.noise("shop", "convenience")).isFalse();
    }

    @Test
    void validIdsAreOpenStreetMapElementsOrMadeUpOnes() {
        assertThat(List.of("N772389775", "W253844168", "R4247312", "DEV-tokyo-tower"))
                .allMatch(PlaceService::validExternalId);
        assertThat(List.of("N0", "n1", "X1", "N1a", "node/1", "N1234567890123456", "DEV-", "DEV-a b", "ChIJabc"))
                .noneMatch(PlaceService::validExternalId);
    }
}
