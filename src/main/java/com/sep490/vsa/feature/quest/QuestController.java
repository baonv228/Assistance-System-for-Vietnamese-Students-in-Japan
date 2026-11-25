package com.sep490.vsa.feature.quest;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.quest.QuestDtos.EventRequest;
import com.sep490.vsa.feature.quest.QuestDtos.QuestChange;
import com.sep490.vsa.feature.quest.QuestDtos.Today;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Nhiệm vụ hằng ngày" (contract 5.1, 5.3). /api/me/** requires login (SecurityConfig, 401 for guests); Members only
 * (MSG18 for staff), checked in {@link QuestService}.
 */
@RestController
public class QuestController {

    private final QuestService service;

    public QuestController(QuestService service) {
        this.service = service;
    }

    @GetMapping("/api/me/quests/today")
    public ApiResponse<Today> today(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("OK", service.today(me));
    }

    /**
     * {@code {type: "LISTEN", phraseId}}. The body is read loosely, so a wrong value is answered on its own field
     * ({@code type}, {@code phraseId}) instead of the generic "Dữ liệu gửi lên không đúng định dạng".
     */
    @PostMapping("/api/me/quests/events")
    public ApiResponse<QuestChange> event(@RequestBody(required = false) JsonNode body,
                                          @AuthenticationPrincipal AppUserPrincipal me) {
        QuestChange change = service.event(me, new EventRequest(text(body, "type"), id(body, "phraseId")));
        return ApiResponse.ok("Đã ghi nhận." + QuestService.completionNote(change), change);
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return node != null && node.isTextual() ? node.asText().strip() : null;
    }

    /** A whole number (or its digits as text); anything else is missing. */
    private static Long id(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.asLong();
        }
        if (node.isTextual() && node.asText().strip().matches("\\d{1,18}")) {
            return Long.parseLong(node.asText().strip());
        }
        return null;
    }
}
