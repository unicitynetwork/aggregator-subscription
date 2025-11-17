package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.PaymentRepository;
import org.unicitylabs.proxy.repository.PricingPlanRepository;
import org.unicitylabs.proxy.service.PaymentService;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.proxy.shard.ShardRouter;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PaymentHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(PaymentHandler.class);

    private final PaymentService paymentService;
    private final ApiKeyRepository apiKeyRepository;
    private final PricingPlanRepository pricingPlanRepository;
    private final ObjectMapper objectMapper;
    private final BigInteger minimumPaymentAmount;

    public PaymentHandler(ProxyConfig config, byte[] serverSecret, ShardRouter shardRouter) {
        this.paymentService = new PaymentService(config, shardRouter, serverSecret);
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
        this.objectMapper = ObjectMapperUtils.createObjectMapper();
        this.minimumPaymentAmount = config.getMinimumPaymentAmount();
    }

    public PaymentService getPaymentService() {
        return paymentService;
    }

    public void updateShardRouter(ShardRouter newRouter) {
        paymentService.updateShardRouter(newRouter);
        logger.info("PaymentHandler updated with new shard router");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
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
                handleGetPaymentPlans(response, callback);
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

        if (!validateInitiateRequest(response, callback, initiateRequest)) {
            return;
        }

        try {
            PaymentModels.InitiatePaymentResponse initiateResponse =
                paymentService.initiatePayment(initiateRequest);

            sendJsonResponse(response, callback, HttpStatus.OK_200, initiateResponse);

        } catch (ApiKeyRepository.LockConflictException e) {
            logger.info("Lock conflict during payment initiation: {}", e.getMessage());
            sendErrorResponse(response, callback, HttpStatus.CONFLICT_409,
                "Conflict", e.getMessage());
        } catch (IllegalArgumentException e) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", e.getMessage());
        } catch (IllegalStateException e) {
            sendErrorResponse(response, callback, HttpStatus.CONFLICT_409,
                "Conflict", e.getMessage());
        }
    }

    private boolean validateInitiateRequest(Response response, Callback callback, PaymentModels.InitiatePaymentRequest initiateRequest) {
        if (initiateRequest.getTargetPlanId() < 0) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Valid target plan ID is required");
            return false;
        }
        return true;
    }

    private void handleCompletePayment(Request request, Response response, Callback callback)
            throws Exception {

        String requestBody = Content.Source.asString(request, StandardCharsets.UTF_8);
        if (logger.isDebugEnabled()) {
            logger.debug("Complete payment request body: {}", requestBody);
        }
        PaymentModels.CompletePaymentRequest completeRequest =
            objectMapper.readValue(requestBody, PaymentModels.CompletePaymentRequest.class);

        if (!validateCompletePaymentRequest(response, callback, completeRequest)) {
            return;
        }

        try {
            PaymentModels.CompletePaymentResponse completeResponse =
                paymentService.completePayment(completeRequest);

            int statusCode = completeResponse.isSuccess() ?
                HttpStatus.OK_200 : HttpStatus.PAYMENT_REQUIRED_402;

            sendJsonResponse(response, callback, statusCode, completeResponse);

        } catch (ApiKeyRepository.LockConflictException e) {
            logger.info("Lock conflict during payment completion: {}", e.getMessage());
            sendErrorResponse(response, callback, HttpStatus.CONFLICT_409,
                "Conflict", e.getMessage());
        } catch (PaymentRepository.DuplicateRequestIdException e) {
            logger.info("Duplicate request ID detected during payment completion: {}", e.getMessage());
            sendErrorResponse(response, callback, HttpStatus.CONFLICT_409,
                "Conflict", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.info("Invalid payment completion request: {}", e.getMessage());
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", e.getMessage());
        } catch (Exception e) {
            logger.error("Error completing payment", e);
            sendErrorResponse(response, callback, HttpStatus.INTERNAL_SERVER_ERROR_500,
                "Internal Server Error", "Payment processing failed: " + e.getMessage());
        }
    }

    private boolean validateCompletePaymentRequest(Response response, Callback callback, PaymentModels.CompletePaymentRequest completeRequest) {
        if (completeRequest.getSessionId() == null) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Session ID is required");
            return false;
        }

        if (completeRequest.getSalt() == null || completeRequest.getSalt().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Salt is required");
            return false;
        }

        if (completeRequest.getTransferCommitmentJson() == null ||
            completeRequest.getTransferCommitmentJson().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Transfer commitment is required");
            return false;
        }

        if (completeRequest.getSourceTokenJson() == null ||
            completeRequest.getSourceTokenJson().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Source token is required");
            return false;
        }
        return true;
    }

    private void handleGetPaymentPlans(Response response, Callback callback) {
        try {
            var availablePlans = pricingPlanRepository.findAll().stream()
                    .map(plan -> new PaymentModels.PricingPlanInfo(
                            plan.id(),
                            plan.name(),
                            plan.requestsPerSecond(),
                            plan.requestsPerDay(),
                            plan.price().max(minimumPaymentAmount)
                    ))
                    .toList();
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

                var planNode = AdminHandler.createPricingPlanNode(pricingPlan, objectMapper);
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