package com.unicitylabs.proxy.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PaymentSessionStatus {
    PENDING("pending"),
    COMPLETED("completed"),
    FAILED("failed"),
    EXPIRED("expired");

    private final String value;

    PaymentSessionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static PaymentSessionStatus fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Status value cannot be null");
        }

        for (PaymentSessionStatus status : PaymentSessionStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid payment session status: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}