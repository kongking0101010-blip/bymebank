package com.khmerbank.util;

/**
 * Builds an EMV / KHQR TLV (Tag-Length-Value) string.
 * Format: [2-digit tag][2-digit length][value]
 */
public final class TlvBuilder {

    private final StringBuilder sb = new StringBuilder();

    public TlvBuilder add(String tag, String value) {
        if (value == null || value.isEmpty()) return this;
        if (tag.length() != 2) throw new IllegalArgumentException("Tag must be 2 chars");
        if (value.length() > 99) throw new IllegalArgumentException("Value too long for tag " + tag);
        sb.append(tag);
        sb.append(String.format("%02d", value.length()));
        sb.append(value);
        return this;
    }

    public TlvBuilder addOptional(String tag, String value) {
        if (value != null && !value.isBlank()) add(tag, value);
        return this;
    }

    public String build() {
        return sb.toString();
    }

    @Override
    public String toString() {
        return build();
    }
}
