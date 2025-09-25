package com.unicity.proxy.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ApiKeyStatus {
    ACTIVE("active"),

    REVOKED("revoked");

    private final String value;

    ApiKeyStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static ApiKeyStatus fromValue(String value) {
        if (value == null) {
            return null;
        }

        for (ApiKeyStatus status : ApiKeyStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Unknown API key status: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}