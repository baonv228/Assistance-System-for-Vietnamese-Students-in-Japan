package com.sep490.vsa.feature.coin;

import java.util.Locale;

/**
 * Vietnamese texts of "xu" shared by the exchange (CoinService) and "Lịch sử điểm" (PointHistoryService). Numbers are
 * written the Vietnamese way, with a dot between thousands ("1.250 xu"), as in the contract with the mobile app.
 */
public final class CoinText {

    private CoinText() {
    }

    /** 1250 → "1.250", -1500 → "-1.500", 999 → "999". */
    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', '.');
    }

    /** Title of the EXCHANGE row of coin_transactions: "Đổi lấy 3 điểm". */
    public static String exchangeTitle(long points) {
        return "Đổi lấy " + number(points) + " điểm";
    }

    /** Message of a successful exchange: "Đã đổi 150 xu lấy 3 điểm.". */
    public static String exchanged(long coins, long points) {
        return "Đã đổi " + number(coins) + " xu lấy " + number(points) + " điểm.";
    }

    /** Title of the COIN_EXCHANGE line of "Lịch sử điểm": "Đổi 150 xu" (the xu that bought the points). */
    public static String pointLineTitle(long coins) {
        return "Đổi " + number(coins) + " xu";
    }
}
