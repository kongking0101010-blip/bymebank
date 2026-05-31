package com.khmerbank.model.enums;

public enum BankType {
    ABA("ABA Bank", "ABA PayWay"),
    ACLEDA("ACLEDA Bank", "ACLEDA Unity"),
    WING("Wing Bank", "Wing Money"),
    BAKONG("Bakong", "National Bank of Cambodia KHQR");

    private final String displayName;
    private final String description;

    BankType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
