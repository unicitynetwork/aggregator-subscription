package com.unicity.proxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
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
        private BigInteger amountRequired;

        @JsonProperty("expiresAt")
        private Instant expiresAt;

        public InitiatePaymentResponse() {}

        public InitiatePaymentResponse(UUID sessionId, String paymentAddress,
                                       BigInteger amountRequired, Instant expiresAt) {
            this.sessionId = sessionId;
            this.paymentAddress = paymentAddress;
            this.amountRequired = amountRequired;
            this.expiresAt = expiresAt;
        }

        public UUID getSessionId() { return sessionId; }
        public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

        public String getPaymentAddress() { return paymentAddress; }
        public void setPaymentAddress(String paymentAddress) { this.paymentAddress = paymentAddress; }

        public BigInteger getAmountRequired() { return amountRequired; }
        public void setAmountRequired(BigInteger amountRequired) { this.amountRequired = amountRequired; }

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

        public CompletePaymentResponse() {}

        public CompletePaymentResponse(boolean success, String message, Long newPlanId) {
            this.success = success;
            this.message = message;
            this.newPlanId = newPlanId;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public Long getNewPlanId() { return newPlanId; }
        public void setNewPlanId(Long newPlanId) { this.newPlanId = newPlanId; }
    }

    public static class PaymentStatusResponse {
        @JsonProperty("sessionId")
        private UUID sessionId;

        /**
         * Payment session status.
         * Allowed values: "pending", "completed", "failed", "expired"
         */
        @JsonProperty("status")
        private String status;

        @JsonProperty("amountRequired")
        private BigInteger amountRequired;

        @JsonProperty("createdAt")
        private Instant createdAt;

        @JsonProperty("completedAt")
        private Instant completedAt;

        @JsonProperty("expiresAt")
        private Instant expiresAt;

        public PaymentStatusResponse() {}

        public PaymentStatusResponse(UUID sessionId, String status, BigInteger amountRequired,
                                    Instant createdAt, Instant completedAt, Instant expiresAt) {
            this.sessionId = sessionId;
            this.status = status;
            this.amountRequired = amountRequired;
            this.createdAt = createdAt;
            this.completedAt = completedAt;
            this.expiresAt = expiresAt;
        }

        public UUID getSessionId() { return sessionId; }
        public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public BigInteger getAmountRequired() { return amountRequired; }
        public void setAmountRequired(BigInteger amountRequired) { this.amountRequired = amountRequired; }

        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

        public Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    }

    public static class CreateApiKeyRequest {
        public CreateApiKeyRequest() {}
    }

    public static class CreateApiKeyResponse {
        @JsonProperty("apiKey")
        private String apiKey;

        @JsonProperty("message")
        private String message;

        @JsonProperty("availablePlans")
        private List<PricingPlanInfo> availablePlans;

        public CreateApiKeyResponse() {}

        public CreateApiKeyResponse(String apiKey, String message, List<PricingPlanInfo> availablePlans) {
            this.apiKey = apiKey;
            this.message = message;
            this.availablePlans = availablePlans;
        }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public List<PricingPlanInfo> getAvailablePlans() { return availablePlans; }
        public void setAvailablePlans(List<PricingPlanInfo> availablePlans) {
            this.availablePlans = availablePlans;
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
}