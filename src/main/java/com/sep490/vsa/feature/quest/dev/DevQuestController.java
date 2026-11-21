package com.sep490.vsa.feature.quest.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.coin.CoinDtos.Wallet;
import com.sep490.vsa.feature.coin.CoinText;
import com.sep490.vsa.feature.quest.QuestDtos.Today;
import com.sep490.vsa.feature.quest.dev.DevQuestDtos.History;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The test-only API of "Nhiệm vụ hằng ngày" (contract 5.9). Registered only when {@link DevApiGuard} allows it
 * (app.quests.dev-api.enabled=true AND a database on this machine); otherwise every /api/dev/** path answers 404
 * ({@link DevApiClosedFilter}). Not GET, so Spring Security asks for a session (401 for guests) and the CSRF header;
 * Members only (MSG18 for staff), checked in {@link DevQuestService}.
 * <p>
 * Bodies are read as any JSON value, so a wrong value is answered on its own field ({@code doneDays}, {@code codes},
 * {@code coins}, {@code clearMonthExchanges}) instead of the generic "Dữ liệu gửi lên không đúng định dạng".
 */
@RestController
@RequestMapping("/api/dev")
@Conditional(DevApiGuard.On.class)
public class DevQuestController {

    private final DevQuestService service;

    public DevQuestController(DevQuestService service) {
        this.service = service;
    }

    /** Clears today's quests, their counted entries, the day done and the xu they paid; answers the wallet. */
    @DeleteMapping("/quests/today")
    public ApiResponse<Wallet> clearToday(@AuthenticationPrincipal AppUserPrincipal me) {
        return ApiResponse.ok("Đã xóa nhiệm vụ hôm nay.", service.clearToday(me));
    }

    /** {@code {"doneDays": 6}}: the 6 days before today done (streak 1…6), no xu; 0 removes the days before today. */
    @PostMapping("/quests/history")
    public ApiResponse<History> history(@AuthenticationPrincipal AppUserPrincipal me,
                                        @RequestBody(required = false) JsonNode body) {
        History history = service.history(me, field(body, "doneDays"));
        String message = history.doneDays() == 0
                ? "Đã xóa các ngày trước hôm nay."
                : "Đã tạo " + history.doneDays() + " ngày đã xong liền trước hôm nay.";
        return ApiResponse.ok(message, history);
    }

    /**
     * {@code {"codes": ["FLASH_REVIEW", "TOPIC", "PHRASE_OF_DAY"]}}: today's quests made from exactly these codes, as
     * for a Member without interests (so a test of the quests themselves never depends on the account's interests).
     * {@code "followInterests": true} (optional) makes the codes that can follow the Member's interests do so, the
     * strongest interest first, the same every day (QUEST_INTERESTS.md).
     */
    @PutMapping("/quests/today")
    public ApiResponse<Today> forceToday(@AuthenticationPrincipal AppUserPrincipal me,
                                         @RequestBody(required = false) JsonNode body) {
        JsonNode follow = field(body, "followInterests");
        boolean followInterests = follow != null && follow.isBoolean() && follow.asBoolean();
        return ApiResponse.ok("Đã đặt nhiệm vụ hôm nay.",
                service.forceToday(me, field(body, "codes"), followInterests));
    }

    /** {@code {"coins": 600, "clearMonthExchanges": true}}: the balance set by one DEV_ADJUST row. */
    @PutMapping("/coins")
    public ApiResponse<Wallet> setCoins(@AuthenticationPrincipal AppUserPrincipal me,
                                        @RequestBody(required = false) JsonNode body) {
        Wallet wallet = service.setCoins(me, field(body, "coins"), field(body, "clearMonthExchanges"));
        return ApiResponse.ok("Đã đặt số dư " + CoinText.number(wallet.coins()) + " xu.", wallet);
    }

    private static JsonNode field(JsonNode body, String name) {
        return body == null ? null : body.get(name);
    }
}
