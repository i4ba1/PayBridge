package com.paybridge.domain.money;

/** Supported currencies and their minor-unit scale (FR-007): IDR has no decimals, USD has two. */
public enum CurrencyCode {
    IDR(0),
    USD(2);

    private final int scale;

    CurrencyCode(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    public static CurrencyCode parse(String code) {
        for (CurrencyCode c : values()) {
            if (c.name().equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unsupported currency: " + code);
    }
}
