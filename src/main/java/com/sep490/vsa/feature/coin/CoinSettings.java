package com.sep490.vsa.feature.coin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** "Xu" settings ({@code app.coins.*}): how many xu make one rank point and how many points a Japan month allows. */
@Component
@ConfigurationProperties(prefix = "app.coins")
public class CoinSettings {

    private int rate = 50;
    private int monthCap = 10;

    public int getRate() {
        return rate;
    }

    public void setRate(int rate) {
        this.rate = rate;
    }

    public int getMonthCap() {
        return monthCap;
    }

    public void setMonthCap(int monthCap) {
        this.monthCap = monthCap;
    }
}
