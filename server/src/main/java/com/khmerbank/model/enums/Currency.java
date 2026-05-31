package com.khmerbank.model.enums;

public enum Currency {
    USD("840", "$"),
    KHR("116", "៛");

    private final String numericCode;
    private final String symbol;

    Currency(String numericCode, String symbol) {
        this.numericCode = numericCode;
        this.symbol = symbol;
    }

    public String getNumericCode() { return numericCode; }
    public String getSymbol() { return symbol; }
}
