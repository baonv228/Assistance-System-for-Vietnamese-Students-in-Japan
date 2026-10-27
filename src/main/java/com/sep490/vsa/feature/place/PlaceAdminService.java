package com.sep490.vsa.feature.place;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sep490.vsa.common.exception.ApiException;
import com.sep490.vsa.feature.admin.ActivityLogService;
import com.sep490.vsa.feature.notification.NotificationService;
import com.sep490.vsa.feature.place.PlaceDtos.PlaceSearch;
import com.sep490.vsa.feature.place.PlaceModerationDtos.AreaRef;
import com.sep490.vsa.feature.place.PlaceModerationDtos.CategoryRef;
import com.sep490.vsa.feature.place.PlaceModerationDtos.Done;
import com.sep490.vsa.feature.place.PlaceModerationDtos.HistoryItem;
import com.sep490.vsa.feature.place.PlaceModerationDtos.NearbyPlace;
import com.sep490.vsa.feature.place.PlaceModerationDtos.OsmPick;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlaceForm;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlaceInfo;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlacePage;
import com.sep490.vsa.feature.place.PlaceModerationDtos.PlaceSaved;
import com.sep490.vsa.feature.place.PlaceModerationDtos.RemoveRequest;
import com.sep490.vsa.feature.place.PlaceModerationRepository.HistoryRow;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceExtra;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceFilter;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceState;
import com.sep490.vsa.feature.place.PlaceModerationRepository.PlaceValues;
import com.sep490.vsa.feature.place.PlaceRepository.NewOsmPlace;
import com.sep490.vsa.feature.place.PlaceService.PlacePicks;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Moderator's "Địa điểm" page: the places list (search, filters, 10 a page), a place's details and history,
 * "Thêm địa điểm" from OpenStreetMap or by hand (UC-M07), "Sửa thông tin", "Gỡ khỏi bản đồ" and "Khôi phục" (UC-M08).
 * The closed label is {@link PinModerationService#setClosed}. Places are never deleted: a removed place keeps its
 * reviews, saves and links, hidden, and comes back whole when restored. OpenStreetMap is never written to.
 */
@Service
public class PlaceAdminService {

    static final int PAGE_SIZE = 10;
    static final int NAME_MAX = 160;
    static final int ADDRESS_MAX = 255;
    static final int NOTE_MAX = 200;
    static final Set<String> STATUSES = Set.of("PENDING", "ACTIVE", "REJECTED", "REMOVED", "MERGED");
    static final Set<String> SOURCES = Set.of("OSM", "GOOGLE", "TEAM", "MODERATOR", "USER");

    private final PlaceModerationRepository repository;
    private final PinModerationService pins;
    private final PlaceService places;
    private final NotificationService notifications;
    private final ActivityLogService activityLog;
    private final ObjectMapper json;
    private final Clock clock;

    public PlaceAdminService(PlaceModerationRepository repository, PinModerationService pins, PlaceService places,
                             NotificationService notifications, ActivityLogService activityLog, ObjectMapper json,
                             Clock clock) {
        this.repository = repository;
        this.pins = pins;
        this.places = places;
        this.notifications = notifications;
        this.activityLog = activityLog;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- list and details

    @Transactional(readOnly = true)
    public PlacePage list(String q, String categoryId, String source, String status, Long areaId, int page) {
        PlaceFilter filter = new PlaceFilter(q == null ? null : q.strip(), categoryId,
                source != null && SOURCES.contains(source) ? source : null,
                status != null && STATUSES.contains(status) ? status : null, areaId);
        int p = Math.max(0, page);
        return new PlacePage(repository.places(filter, p * PAGE_SIZE, PAGE_SIZE), p, PAGE_SIZE,
                repository.countPlaces(filter), repository.categories(), repository.areas());
    }

    @Transactional(readOnly = true)
    public PlaceInfo detail(long id) {
        PlaceState p = repository.place(id, false).orElseThrow(() -> notFound(PinModerationService.GONE));
        PlaceExtra x = repository.extra(id).orElseThrow();
        List<HistoryItem> history = repository.history(id).stream().map(this::historyItem).toList();
        return new PlaceInfo(p.id(), p.nameVi(), p.nameJa(), p.nameEn(), p.address(), x.osmId(), p.lat(), p.lng(),
                categoryOf(p.categoryId()), x.areaId(), x.areaName(), p.source(), p.status(), p.closed(), x.closedAt(),
                x.removedReason(), x.rejectReason(), x.mergedIntoId(), x.mergedIntoName(), p.createdBy(),
                p.authorName(), p.createdAt(), x.reviewCount(), x.saveCount(), repository.openClosedReports(id),
                history, repository.categories(), repository.areas(), repository.reasons("PLACE_REMOVE"));
    }

    // ---------------------------------------------------------------- UC-M07 Thêm địa điểm

    /** Step 1 "Tìm trên OpenStreetMap": Senpai ơi places first (id set), then OpenStreetMap places (externalId). */
    public PlaceSearch searchOsm(long moderatorId, String q) {
        return places.search(moderatorId, q == null ? "" : q);
    }

    /** "Chọn": the OpenStreetMap place looked up (Nominatim), as a filled-in form, or the Senpai ơi place it already is. */
    public OsmPick pickOsm(long moderatorId, String externalId) {
        if (externalId == null || !OsmPlaces.OSM_ID.matcher(externalId).matches()) {
            throw ApiException.badRequest("Mã OpenStreetMap không hợp lệ.");
        }
        PlacePicks picks = places.resolve(moderatorId, List.of(externalId));
        if (picks.error() != null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Chưa tìm được trên OpenStreetMap, hãy thử lại sau. Bạn vẫn có thể cắm ghim tay.");
        }
        if (!picks.knownIds().isEmpty()) {
            return new OsmPick(picks.knownIds().get(0), null);
        }
        NewOsmPlace n = picks.fresh().get(0);
        return new OsmPick(null, new PlaceForm(n.osmId(), n.nameVi(), n.nameJa(), n.nameEn(), n.address(),
                n.categoryId(), null, n.lat(), n.lng(), false));
    }

    /**
     * "Thêm vào Senpai ơi" (with an OpenStreetMap id) or "Thêm địa điểm" (pinned by hand): ACTIVE at once, no
     * approval and no first review. A similar name within 200 m stops the save until "Vẫn lưu".
     */
    @Transactional
    public PlaceSaved create(long moderatorId, PlaceForm form) {
        PlaceValues v = check(form);
        String osmId = form.osmId() == null || form.osmId().isBlank() ? null : form.osmId().strip();
        if (osmId != null) {
            if (!OsmPlaces.OSM_ID.matcher(osmId).matches()) {
                throw ApiException.badRequest("Mã OpenStreetMap không hợp lệ.");
            }
            Long existing = repository.placeIdOfOsm(osmId).orElse(null);
            if (existing != null) {
                return new PlaceSaved("Địa điểm này đã có trên Senpai ơi.", existing, List.of());
            }
        }
        if (!form.confirmDuplicate()) {
            List<NearbyPlace> similar = similar(v, 0);
            if (!similar.isEmpty()) {
                return new PlaceSaved("Có thể trùng với địa điểm đã có.", null, similar);
            }
        }
        Instant now = clock.instant();
        long id;
        try {
            id = repository.insertPlace(v, osmId, moderatorId, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Địa điểm này vừa được thêm vào Senpai ơi.");
        }
        repository.history(id, "CREATED", moderatorId,
                toJson(Map.of("source", osmId == null ? "MODERATOR" : "OSM", "via", "MODERATOR")), now);
        activityLog.recordLog(moderatorId, "PLACE_CREATED", "PLACE", id, null, values(v, osmId),
                "Thêm địa điểm “" + v.nameVi() + "”");
        return new PlaceSaved("Đã thêm “" + v.nameVi() + "” vào Senpai ơi.", id, List.of());
    }

    // ---------------------------------------------------------------- UC-M08 Sửa, Gỡ, Khôi phục

    /**
     * "Lưu thay đổi": each changed field goes to the history as "Danh mục: Quán ăn → Siêu thị". Reviews and saves
     * stay. A new name or position is checked for duplicates like a new place.
     */
    @Transactional
    public PlaceSaved update(long moderatorId, long id, PlaceForm form) {
        PlaceState p = repository.place(id, true).orElseThrow(() -> notFound(PinModerationService.GONE));
        if (!"ACTIVE".equals(p.status()) && !"REMOVED".equals(p.status())) {
            throw ApiException.conflict("Chỉ sửa được địa điểm đang hoạt động hoặc đã gỡ.");
        }
        PlaceValues v = check(form);
        PlaceExtra x = repository.extra(id).orElseThrow();
        List<Map<String, String>> changes = new ArrayList<>();
        diff(changes, "Tên tiếng Việt", p.nameVi(), v.nameVi());
        diff(changes, "Tên tiếng Nhật", p.nameJa(), v.nameJa());
        diff(changes, "Tên tiếng Anh", p.nameEn(), v.nameEn());
        diff(changes, "Địa chỉ", p.address(), v.address());
        if (!v.categoryId().equals(p.categoryId())) {
            changes.add(PinModerationService.change("Danh mục", categoryOf(p.categoryId()).name(),
                    categoryOf(v.categoryId()).name()));
        }
        if (!Objects.equals(v.areaId(), x.areaId())) {
            changes.add(PinModerationService.change("Khu vực", x.areaName(),
                    v.areaId() == null ? null : repository.area(v.areaId()).map(AreaRef::name).orElse(null)));
        }
        int moved = p.lat() == null ? 0 : (int) Math.round(PlaceService.metres(p.lat(), p.lng(), v.lat(), v.lng()));
        boolean newPosition = p.lat() == null || moved >= 1;
        if (newPosition) {
            changes.add(PinModerationService.change("Vị trí", p.lat() == null ? "chưa có" : "cũ",
                    p.lat() == null ? "đã đặt" : "dời " + moved + " m"));
        }
        if (changes.isEmpty()) {
            return new PlaceSaved("Không có thay đổi nào.", id, List.of());
        }
        boolean renamed = !Objects.equals(p.nameVi(), v.nameVi()) || !Objects.equals(p.nameJa(), v.nameJa())
                || !Objects.equals(p.nameEn(), v.nameEn());
        if (!form.confirmDuplicate() && (renamed || newPosition)) {
            List<NearbyPlace> similar = similar(v, id);
            if (!similar.isEmpty()) {
                return new PlaceSaved("Có thể trùng với địa điểm đã có.", null, similar);
            }
        }
        Instant now = clock.instant();
        repository.updatePlace(id, v, now);
        repository.history(id, "EDITED", moderatorId, toJson(Map.of("changes", changes)), now);
        if ("USER".equals(p.source())) {
            notifications.send(p.createdBy(), "PLACE_UPDATED", "Moderator đã cập nhật thông tin địa điểm “"
                    + v.nameVi() + "” bạn cắm ghim.", "PLACE", id);
        }
        activityLog.recordLog(moderatorId, "PLACE_UPDATED", "PLACE", id, null, Map.of("changes", changes),
                "Sửa địa điểm “" + v.nameVi() + "”");
        return new PlaceSaved("Đã lưu thay đổi.", id, List.of());
    }

    /** "Gỡ khỏi bản đồ": hidden from the map, search and posts; reviews kept but hidden; never deleted. */
    @Transactional
    public Done remove(long moderatorId, long id, RemoveRequest request) {
        if (request == null || request.reasonId() == null) {
            throw ApiException.invalid("Hãy chọn lý do gỡ.", Map.of("reasonId", "Hãy chọn lý do gỡ."));
        }
        String reason = repository.reasonLabel(request.reasonId(), "PLACE_REMOVE")
                .orElseThrow(() -> ApiException.invalid("Hãy chọn lý do gỡ.", Map.of("reasonId", "Hãy chọn lý do gỡ.")));
        String note = request.note() == null ? "" : request.note().strip();
        if (note.length() > NOTE_MAX) {
            throw ApiException.invalid("Ghi chú tối đa 200 ký tự.", Map.of("note", "Ghi chú tối đa 200 ký tự."));
        }
        PlaceState p = repository.place(id, true).orElseThrow(() -> notFound(PinModerationService.GONE));
        Instant now = clock.instant();
        if (!repository.remove(id, note.isEmpty() ? reason : reason + ": " + note, now)) {
            throw ApiException.conflict("Địa điểm này không còn đang hoạt động.");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        if (!note.isEmpty()) {
            detail.put("note", note);
        }
        repository.history(id, "REMOVED", moderatorId, toJson(detail), now);
        if ("USER".equals(p.source())) {
            notifications.send(p.createdBy(), "PLACE_REMOVED", "Địa điểm “" + p.nameVi()
                    + "” bạn cắm ghim đã được gỡ khỏi bản đồ: " + reason + ".", "PLACE", id);
        }
        activityLog.recordLog(moderatorId, "PLACE_REMOVED", "PLACE", id, Map.of("status", "ACTIVE"),
                withStatus("REMOVED", detail), "Gỡ địa điểm “" + p.nameVi() + "”: " + reason);
        return new Done("Đã gỡ “" + p.nameVi() + "” khỏi bản đồ.", id);
    }

    /** "Khôi phục": the place and its reviews are back on the map. */
    @Transactional
    public Done restore(long moderatorId, long id) {
        PlaceState p = repository.place(id, true).orElseThrow(() -> notFound(PinModerationService.GONE));
        Instant now = clock.instant();
        if (!repository.restore(id, now)) {
            throw ApiException.conflict("Địa điểm này không ở trạng thái đã gỡ.");
        }
        repository.history(id, "RESTORED", moderatorId, "{}", now);
        activityLog.recordLog(moderatorId, "PLACE_RESTORED", "PLACE", id, Map.of("status", "REMOVED"),
                Map.of("status", "ACTIVE"), "Khôi phục địa điểm “" + p.nameVi() + "”");
        return new Done("Đã khôi phục “" + p.nameVi() + "” lên bản đồ.", id);
    }

    // ---------------------------------------------------------------- checks

    /** UC-M07 AF3: "Hãy nhập tên hiển thị.", "Hãy chọn danh mục.", "Hãy bấm lên bản đồ để đặt vị trí.". */
    PlaceValues check(PlaceForm form) {
        if (form == null) {
            throw ApiException.badRequest("Thiếu thông tin địa điểm.");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String nameVi = text(form.nameVi(), NAME_MAX, "nameVi", "Tên tiếng Việt", errors);
        if (nameVi == null && !errors.containsKey("nameVi")) {
            errors.put("nameVi", "Hãy nhập tên hiển thị.");
        }
        String nameJa = text(form.nameJa(), NAME_MAX, "nameJa", "Tên tiếng Nhật", errors);
        String nameEn = text(form.nameEn(), NAME_MAX, "nameEn", "Tên tiếng Anh", errors);
        String address = text(form.address(), ADDRESS_MAX, "address", "Địa chỉ", errors);
        if (form.categoryId() == null || repository.category(form.categoryId()).isEmpty()) {
            errors.put("categoryId", "Hãy chọn danh mục.");
        }
        if (form.areaId() != null && repository.area(form.areaId()).isEmpty()) {
            errors.put("areaId", "Khu vực không hợp lệ.");
        }
        if (form.lat() == null || form.lng() == null || Math.abs(form.lat()) > 90 || Math.abs(form.lng()) > 180) {
            errors.put("position", "Hãy bấm lên bản đồ để đặt vị trí.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalid(errors.values().iterator().next(), errors);
        }
        return new PlaceValues(nameVi, nameJa, nameEn, address, form.categoryId(), form.areaId(), form.lat(),
                form.lng());
    }

    private List<NearbyPlace> similar(PlaceValues v, long exceptId) {
        return pins.around(v.lat(), v.lng(), exceptId, v.nameVi(), v.nameJa(), v.nameEn()).stream()
                .filter(NearbyPlace::similar).toList();
    }

    private static String text(String value, int max, String field, String label, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String t = value.strip();
        if (t.length() > max) {
            errors.put(field, label + " tối đa " + max + " ký tự.");
        }
        return t;
    }

    private static void diff(List<Map<String, String>> changes, String field, String from, String to) {
        if (!Objects.equals(from, to)) {
            changes.add(PinModerationService.change(field, from, to));
        }
    }

    // ---------------------------------------------------------------- history

    /** "Danh mục: Quán ăn → Siêu thị; Vị trí: cũ → dời 35 m", "Gỡ khỏi bản đồ: Không còn tồn tại"… */
    HistoryItem historyItem(HistoryRow row) {
        JsonNode d = read(row.detail());
        String text = switch (row.action()) {
            case "CREATED" -> switch (d.path("via").asText("")) {
                case "PIN" -> "Người dùng cắm ghim";
                case "MODERATOR" -> "OSM".equals(d.path("source").asText()) ? "Moderator thêm từ OpenStreetMap"
                        : "Moderator cắm ghim tay";
                case "BLOG" -> "Thêm từ OpenStreetMap khi được gắn vào bài viết";
                case "REVIEW" -> "Thêm từ OpenStreetMap khi có review đầu tiên";
                default -> "Thêm địa điểm";
            };
            case "APPROVED" -> join("Duyệt ghim", d);
            case "EDITED" -> join("Sửa thông tin", d);
            case "REJECTED" -> "Từ chối: " + d.path("reason").asText("");
            case "REMOVED" -> "Gỡ khỏi bản đồ: " + d.path("reason").asText("")
                    + (d.hasNonNull("note") ? " (" + d.path("note").asText() + ")" : "");
            case "RESTORED" -> "Khôi phục lên bản đồ";
            case "CLOSED" -> "Gắn nhãn đã đóng cửa";
            case "REOPENED" -> "Gỡ nhãn đóng cửa";
            case "MERGED" -> d.has("into") ? "Gộp vào địa điểm #" + d.path("into").asLong()
                    : join("Nhận gộp từ địa điểm #" + d.path("from").asLong()
                    + (d.path("movedReviews").asInt(0) > 0 ? " (" + d.path("movedReviews").asInt() + " review)" : ""), d);
            default -> row.action();
        };
        return new HistoryItem(row.action(), row.actorName(), row.at(), text);
    }

    private static String join(String title, JsonNode d) {
        List<String> parts = new ArrayList<>();
        d.path("changes").forEach(c -> parts.add(c.path("field").asText() + ": "
                + blank(c.path("from").asText()) + " → " + blank(c.path("to").asText())));
        return parts.isEmpty() ? title : title + " · " + String.join("; ", parts);
    }

    private static String blank(String value) {
        return value.isBlank() ? "(trống)" : value;
    }

    private JsonNode read(String detail) {
        try {
            return detail == null ? json.createObjectNode() : json.readTree(detail);
        } catch (JsonProcessingException e) {
            return json.createObjectNode();
        }
    }

    // ---------------------------------------------------------------- helpers

    private CategoryRef categoryOf(String id) {
        return repository.category(id).orElse(new CategoryRef(id, id, "place"));
    }

    private static Map<String, Object> values(PlaceValues v, String osmId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nameVi", v.nameVi());
        m.put("categoryId", v.categoryId());
        m.put("source", osmId == null ? "MODERATOR" : "OSM");
        if (osmId != null) {
            m.put("osmId", osmId);
        }
        return m;
    }

    private static Map<String, Object> withStatus(String status, Map<String, Object> detail) {
        Map<String, Object> m = new LinkedHashMap<>(detail);
        m.put("status", status);
        return m;
    }

    private String toJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }
}
