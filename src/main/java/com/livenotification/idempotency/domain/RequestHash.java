package com.livenotification.idempotency.domain;

public record RequestHash(String value) {
    private static final java.util.regex.Pattern SHA256_HEX =
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    public RequestHash {
        if (value == null || !SHA256_HEX.matcher(value).matches())
            throw new IllegalArgumentException("hash must be SHA-256 hex 64자");
    }

    public static RequestHash of(String value) {
        return new RequestHash(value);
    }
}
