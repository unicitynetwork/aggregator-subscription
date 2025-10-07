package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.PricingPlanRepository;
import org.unicitylabs.proxy.service.ApiKeyService;
import org.unicitylabs.proxy.service.PaymentService;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PaymentHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(PaymentHandler.class);

    private final PaymentService paymentService;
    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;
    private final PricingPlanRepository pricingPlanRepository;
    private final ObjectMapper objectMapper;

    public PaymentHandler(ProxyConfig config, byte[] serverSecret) {
        this.paymentService = new PaymentService(config, serverSecret);
        this.apiKeyService = new ApiKeyService();
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
        this.objectMapper = ObjectMapperUtils.createObjectMapper();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();

        if (!path.startsWith("/api/payment/")) {
            return false;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Handling payment request: {} {}", method, path);
        }

        try {
            if ("GET".equals(method) && "/api/payment/plans".equals(path)) {
                handleGetPaymentPlans(request, response, callback);
                return true;
            } else if ("POST".equals(method) && "/api/payment/initiate".equals(path)) {
                handleInitiatePayment(request, response, callback);
                return true;
            } else if ("POST".equals(method) && "/api/payment/complete".equals(path)) {
                handleCompletePayment(request, response, callback);
                return true;
            } else if ("GET".equals(method) && path.startsWith("/api/payment/key/")) {
                handleGetApiKeyDetails(request, response, callback);
                return true;
            } else {
                sendErrorResponse(response, callback, HttpStatus.NOT_FOUND_404,
                    "Not Found", "Endpoint not found");
                return true;
            }
        } catch (Exception e) {
            logger.error("Error handling payment request", e);
            sendErrorResponse(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                "Internal Server Error", e.getMessage());
            return true;
        }
    }

    private void handleInitiatePayment(Request request, Response response, Callback callback)
            throws Exception {

        String requestBody = Content.Source.asString(request, StandardCharsets.UTF_8);

        if (logger.isDebugEnabled()) {
            logger.debug("Initiate payment request body: {}", requestBody);
        }

        PaymentModels.InitiatePaymentRequest initiateRequest =
            objectMapper.readValue(requestBody, PaymentModels.InitiatePaymentRequest.class);

        if (initiateRequest.getTargetPlanId() < 0) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Valid target plan ID is required");
            return;
        }

        if (initiateRequest.getTokenId() == null || initiateRequest.getTokenId().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Token ID is required");
            return;
        }

        if (initiateRequest.getTokenType() == null || initiateRequest.getTokenType().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Token type is required");
            return;
        }

        byte[] tokenId;
        byte[] tokenType;
        try {
            tokenId = Base64.getDecoder().decode(initiateRequest.getTokenId());
            if (tokenId.length > 32) {
                sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                    "Bad Request", "Token ID must not exceed 32 bytes");
                return;
            }
        } catch (IllegalArgumentException e) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Invalid Base64 encoding for token ID");
            return;
        }

        try {
            tokenType = Base64.getDecoder().decode(initiateRequest.getTokenType());
            // TODO: For production, we need to verify the token types
        } catch (IllegalArgumentException e) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Invalid Base64 encoding for token type");
            return;
        }

        try {
            PaymentModels.InitiatePaymentResponse initiateResponse =
                paymentService.initiatePayment(initiateRequest, tokenId, tokenType);

            sendJsonResponse(response, callback, HttpStatus.OK_200, initiateResponse);

        } catch (IllegalArgumentException e) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", e.getMessage());
        } catch (IllegalStateException e) {
            sendErrorResponse(response, callback, HttpStatus.CONFLICT_409,
                "Conflict", e.getMessage());
        }
    }

    private void handleCompletePayment(Request request, Response response, Callback callback)
            throws Exception {

        String requestBody = Content.Source.asString(request, StandardCharsets.UTF_8);
        if (logger.isDebugEnabled()) {
            logger.debug("Complete payment request body: {}", requestBody);
        }
        PaymentModels.CompletePaymentRequest completeRequest =
            objectMapper.readValue(requestBody, PaymentModels.CompletePaymentRequest.class);

        if (completeRequest.getSessionId() == null) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Session ID is required");
            return;
        }

        if (completeRequest.getSalt() == null || completeRequest.getSalt().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Salt is required");
            return;
        }

        if (completeRequest.getTransferCommitmentJson() == null ||
            completeRequest.getTransferCommitmentJson().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Transfer commitment is required");
            return;
        }

        if (completeRequest.getSourceTokenJson() == null ||
            completeRequest.getSourceTokenJson().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Source token is required");
            return;
        }

        try {
            PaymentModels.CompletePaymentResponse completeResponse =
                paymentService.completePayment(completeRequest);

            int statusCode = completeResponse.isSuccess() ?
                HttpStatus.OK_200 : HttpStatus.PAYMENT_REQUIRED_402;

            sendJsonResponse(response, callback, statusCode, completeResponse);

        } catch (Exception e) {
            logger.error("Error completing payment", e);
            sendErrorResponse(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                "Internal Server Error", "Payment processing failed: " + e.getMessage());
        }
    }

    private void handleGetPaymentPlans(Request request, Response response, Callback callback) {
        try {
            var availablePlans = apiKeyService.getAvailablePlans();
            Map<String, Object> responseBody = Map.of("availablePlans", availablePlans);
            sendJsonResponse(response, callback, HttpStatus.OK_200, responseBody);
        } catch (Exception e) {
            logger.error("Error retrieving payment plans", e);
            sendErrorResponse(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                "Internal Server Error", "Failed to retrieve payment plans: " + e.getMessage());
        }
    }

    private void handleGetApiKeyDetails(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();
        String apiKey = path.substring("/api/payment/key/".length());

        if (apiKey.isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "API key is required");
            return;
        }

        try {
            var apiKeyInfo = apiKeyRepository.findByKeyIfNotRevoked(apiKey);

            if (apiKeyInfo.isEmpty()) {
                sendErrorResponse(response, callback, HttpStatus.NOT_FOUND_404,
                    "Not Found", "API key not found or revoked");
                return;
            }

            var keyInfo = apiKeyInfo.get();

            ObjectNode responseJson = objectMapper.createObjectNode();

            responseJson.put("status", "active"); // If we got here, it's not revoked

            if (keyInfo.activeUntil() != null) {
                responseJson.put("expiresAt", keyInfo.activeUntil().toInstant().toString());
            } else {
                responseJson.putNull("expiresAt");
            }

            if (keyInfo.pricingPlanId() != null) {
                var pricingPlan = pricingPlanRepository.findById(keyInfo.pricingPlanId());

                ObjectNode planNode = objectMapper.createObjectNode();
                planNode.put("id", pricingPlan.getId());
                planNode.put("name", pricingPlan.getName());
                planNode.put("requestsPerSecond", pricingPlan.getRequestsPerSecond());
                planNode.put("requestsPerDay", pricingPlan.getRequestsPerDay());
                planNode.put("price", pricingPlan.getPrice().toString());
                responseJson.set("pricingPlan", planNode);
            } else {
                responseJson.putNull("pricingPlan");
                responseJson.put("message", "No active pricing plan. Payment required to activate API key.");
            }

            sendJsonResponse(response, callback, HttpStatus.OK_200, responseJson);

        } catch (Exception e) {
            logger.error("Error retrieving API key details", e);
            sendErrorResponse(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                "Internal Server Error", "Failed to retrieve API key details: " + e.getMessage());
        }
    }

    private void sendJsonResponse(Response response, Callback callback,
                                 int statusCode, Object body) {
        try {
            response.setStatus(statusCode);
            response.getHeaders().put("Content-Type", "application/json");

            String json = objectMapper.writeValueAsString(body);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            if (logger.isDebugEnabled()) {
                logger.debug("Sending JSON response (status {}): {}", statusCode, json);
            }

            response.write(true, ByteBuffer.wrap(bytes), callback);

        } catch (Exception e) {
            logger.error("Error sending JSON response", e);
            callback.failed(e);
        }
    }

    private void sendErrorResponse(Response response, Callback callback,
                                  int statusCode, String error, String message) {
        PaymentModels.ErrorResponse errorResponse =
            new PaymentModels.ErrorResponse(error, message);
        sendJsonResponse(response, callback, statusCode, errorResponse);
    }
}