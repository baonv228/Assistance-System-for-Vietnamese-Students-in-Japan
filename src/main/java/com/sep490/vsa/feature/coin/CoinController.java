package com.sep490.vsa.feature.coin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sep490.vsa.common.dto.ApiResponse;
import com.sep490.vsa.common.security.AppUserPrincipal;
import com.sep490.vsa.feature.coin.CoinDtos.CoinPage;
import com.sep490.vsa.feature.coin.CoinDtos.ExchangeRequest;
import com.sep490.vsa.feature.coin.CoinDtos.ExchangeResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Member's "xu": history and "Đổi" into rank points (contract 5.4, 5.5). /api/me/** requires login (SecurityConfig);
 * Members only, MSG18 for staff ({@link CoinService}).
 */
@RestController
public class CoinController {

    private final CoinService service;

    public CoinController(CoinService service) {
        this.service = service;
    }

    /** "Lịch sử xu": 20 a page, newest first, with the wallet. */
    @GetMapping("/api/me/coins")
    public ApiResponse<CoinPage> history(@AuthenticationPrincipal AppUserPrincipal me,
                                         @RequestParam(defaultValue = "0") int page) {
        return ApiResponse.ok("OK", service.history(me, page));
    }

    /**
     * {@code {"points": 3}}. The body is read as any JSON value, so a missing body, a list or a "points" that is not a
     * whole number all get the same message on "points" instead of the generic "Dữ liệu gửi lên không đúng định dạng".
     */
    @PostMapping("/api/me/coins/exchange")
    public ApiResponse<ExchangeResult> exchange(@AuthenticationPrincipal AppUserPrincipal me,
                                                @RequestBody(required = false) JsonNode body) {
        ExchangeResult result = service.exchange(me, new ExchangeRequest(body == null ? null : body.get("points")));
        return ApiResponse.ok(CoinText.exchanged(result.coinsSpent(), result.pointsAdded()), result);
    }
}
