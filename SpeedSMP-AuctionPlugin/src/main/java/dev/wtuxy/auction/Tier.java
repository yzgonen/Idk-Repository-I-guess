package dev.wtuxy.auction;

public enum Tier {
    S, A, B, C, D;

    public static Tier parse(String value) {
        return Tier.valueOf(value.toUpperCase());
    }
}
