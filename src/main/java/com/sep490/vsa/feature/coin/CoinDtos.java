package com.sep490.vsa.feature.coin;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** JSON shapes of "xu" (contract with the mobile app: WEB_DAILY_QUESTS_PROMPT.md, sections 5.0, 5.4, 5.5). */
public final class CoinDtos {

    private CoinDtos() {
    }

    /**
     * The Member's xu and what they can turn into rank points now. {@code exchangeableNow} = min(coins / rate,
     * monthCap − exchangedThisMonth), never below 0; {@code monthResetAt} = 0:00 Japan time on the 1st of next month.
     */
    public record Wallet(int coins, int rate, int monthCap, int exchangedThisMonth, int exchangeableNow,
                         Instant monthResetAt, int points, String rankName) {
    }

    /**
     * POST /api/me/coins/exchange: the rank points wanted, a JSON whole number ≥ 1. Kept as the JSON value itself, so
     * CoinService can refuse every other value with the message on "points" (an Integer would let Jackson turn 2.5
     * into 2 and answer "3" or {} with its own generic error). {@code null} = missing.
     */
    public record ExchangeRequest(JsonNode points) {
    }

    public record ExchangeResult(int pointsAdded, int coinsSpent, String rankBefore, String rankAfter, Wallet wallet) {
    }

    /** One line of GET /api/me/coins. */
    public record CoinLine(long id, int delta, String reason, String reasonLabel, String title, String link,
                           Instant createdAt) {
    }

    public record CoinPage(List<CoinLine> items, int page, int size, long total, Wallet wallet) {
    }
}
