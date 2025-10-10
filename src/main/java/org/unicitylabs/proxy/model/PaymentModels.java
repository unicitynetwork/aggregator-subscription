package org.unicitylabs.proxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public class PaymentModels {

    public static class InitiatePaymentRequest {
        @JsonProperty("apiKey")
        private String apiKey;

        @JsonProperty("targetPlanId")
        private int targetPlanId;

        public InitiatePaymentRequest() {}

        public InitiatePaymentRequest(String apiKey, int targetPlanId) {
            this.apiKey = apiKey;
            this.targetPlanId = targetPlanId;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public long getTargetPlanId() { return targetPlanId; }
        public void setTargetPlanId(int targetPlanId) { this.targetPlanId = targetPlanId; }
    }

    public static class InitiatePaymentResponse {
        @JsonProperty("sessionId")
        private UUID sessionId;

        @JsonProperty("paymentAddress")
        private String paymentAddress;

        @JsonProperty("amountRequired")
        private BigInteger price;

        @JsonProperty("acceptedCoinId")
        private String acceptedCoinId;

        @JsonProperty("expiresAt")
        private Instant expiresAt;

        public InitiatePaymentResponse() {}

        public InitiatePaymentResponse(UUID sessionId, String paymentAddress,
                                       BigInteger price, String acceptedCoinId, Instant expiresAt) {
            this.sessionId = sessionId;
            this.paymentAddress = paymentAddress;
            this.price = price;
            this.acceptedCoinId = acceptedCoinId;
            this.expiresAt = expiresAt;
        }

        public UUID getSessionId() { return sessionId; }
        public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

        public String getPaymentAddress() { return paymentAddress; }
        public void setPaymentAddress(String paymentAddress) { this.paymentAddress = paymentAddress; }

        public BigInteger getPrice() { return price; }
        public void setPrice(BigInteger price) { this.price = price; }

        public String getAcceptedCoinId() { return acceptedCoinId; }
        public void setAcceptedCoinId(String acceptedCoinId) { this.acceptedCoinId = acceptedCoinId; }

        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    }

    public static class CompletePaymentRequest {
        @JsonProperty("sessionId")
        private UUID sessionId;

        @JsonProperty("salt")
        private String salt; // Base64 encoded

        @JsonProperty("transferCommitmentJson")
        private String transferCommitmentJson; // JSON serialized TransferCommitment

        @JsonProperty("sourceTokenJson")
        private String sourceTokenJson; // JSON serialized source Token

        public CompletePaymentRequest() {}

        public CompletePaymentRequest(UUID sessionId, String salt,
                                     String transferCommitmentJson, String sourceTokenJson) {
            this.sessionId = sessionId;
            this.salt = salt;
            this.transferCommitmentJson = transferCommitmentJson;
            this.sourceTokenJson = sourceTokenJson;
        }

        public UUID getSessionId() { return sessionId; }
        public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

        public String getSalt() { return salt; }
        public void setSalt(String salt) { this.salt = salt; }

        public String getTransferCommitmentJson() { return transferCommitmentJson; }
        public void setTransferCommitmentJson(String transferCommitmentJson) {
            this.transferCommitmentJson = transferCommitmentJson;
        }

        public String getSourceTokenJson() { return sourceTokenJson; }
        public void setSourceTokenJson(String sourceTokenJson) {
            this.sourceTokenJson = sourceTokenJson;
        }
    }

    public static class CompletePaymentResponse {
        @JsonProperty("success")
        private boolean success;

        @JsonProperty("message")
        private String message;

        @JsonProperty("newPlanId")
        private Long newPlanId; // null if payment failed

        @JsonProperty("apiKey")
        private String apiKey;

        public CompletePaymentResponse() {}

        public CompletePaymentResponse(boolean success, String message, Long newPlanId, String apiKey) {
            this.success = success;
            this.message = message;
            this.newPlanId = newPlanId;
            this.apiKey = apiKey;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public Long getNewPlanId() { return newPlanId; }
        public void setNewPlanId(Long newPlanId) { this.newPlanId = newPlanId; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public static class ErrorResponse {
        @JsonProperty("error")
        private String error;

        @JsonProperty("message")
        private String message;

        public ErrorResponse() {}

        public ErrorResponse(String error, String message) {
            this.error = error;
            this.message = message;
        }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class PricingPlanInfo {
        @JsonProperty("planId")
        private long planId;

        @JsonProperty("name")
        private String name;

        @JsonProperty("requestsPerSecond")
        private int requestsPerSecond;

        @JsonProperty("requestsPerDay")
        private int requestsPerDay;

        @JsonProperty("price")
        private BigInteger price;

        public PricingPlanInfo() {}

        public PricingPlanInfo(long planId, String name, int requestsPerSecond,
                               int requestsPerDay, BigInteger price) {
            this.planId = planId;
            this.name = name;
            this.requestsPerSecond = requestsPerSecond;
            this.requestsPerDay = requestsPerDay;
            this.price = price;
        }

        public long getPlanId() { return planId; }
        public void setPlanId(long planId) { this.planId = planId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getRequestsPerSecond() { return requestsPerSecond; }
        public void setRequestsPerSecond(int requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public int getRequestsPerDay() { return requestsPerDay; }
        public void setRequestsPerDay(int requestsPerDay) {
            this.requestsPerDay = requestsPerDay;
        }

        public BigInteger getPrice() { return price; }
        public void setPrice(BigInteger price) { this.price = price; }
    }
}