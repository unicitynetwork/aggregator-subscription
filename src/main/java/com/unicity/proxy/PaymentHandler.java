package com.unicity.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicity.proxy.model.PaymentModels;
import com.unicity.proxy.service.PaymentService;
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
import java.util.Optional;
import java.util.UUID;

public class PaymentHandler extends Handler.Abstract {
    private static final Logger logger = LoggerFactory.getLogger(PaymentHandler.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentHandler(ProxyConfig config, byte[] serverSecret) {
        this.paymentService = new PaymentService(config, serverSecret);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules(); // For Java 8 time support
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        String path = request.getHttpURI().getPath();
        String method = request.getMethod();

        if (!path.startsWith("/api/payment/")) {
            return false;
        }

        try {
            if ("POST".equals(method) && "/api/payment/initiate".equals(path)) {
                handleInitiatePayment(request, response, callback);
                return true;
            } else if ("POST".equals(method) && "/api/payment/complete".equals(path)) {
                handleCompletePayment(request, response, callback);
                return true;
            } else if ("GET".equals(method) && path.startsWith("/api/payment/status/")) {
                handlePaymentStatus(request, response, callback);
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
        PaymentModels.InitiatePaymentRequest initiateRequest =
            objectMapper.readValue(requestBody, PaymentModels.InitiatePaymentRequest.class);

        if (initiateRequest.getApiKey() == null || initiateRequest.getApiKey().isEmpty()) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "API key is required");
            return;
        }

        if (initiateRequest.getTargetPlanId() < 0) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Valid target plan ID is required");
            return;
        }

        try {
            PaymentModels.InitiatePaymentResponse initiateResponse =
                paymentService.initiatePayment(initiateRequest);

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

    private void handlePaymentStatus(Request request, Response response, Callback callback) {
        String path = request.getHttpURI().getPath();
        String sessionIdStr = path.substring("/api/payment/status/".length());

        try {
            UUID sessionId = UUID.fromString(sessionIdStr);

            Optional<PaymentModels.PaymentStatusResponse> status =
                paymentService.getPaymentStatus(sessionId);

            if (status.isPresent()) {
                sendJsonResponse(response, callback, HttpStatus.OK_200, status.get());
            } else {
                sendErrorResponse(response, callback, HttpStatus.NOT_FOUND_404,
                    "Not Found", "Payment session not found");
            }

        } catch (IllegalArgumentException e) {
            sendErrorResponse(response, callback, HttpStatus.BAD_REQUEST_400,
                "Bad Request", "Invalid session ID format");
        }
    }

    private void sendJsonResponse(Response response, Callback callback,
                                 int statusCode, Object body) {
        try {
            response.setStatus(statusCode);
            response.getHeaders().put("Content-Type", "application/json");

            String json = objectMapper.writeValueAsString(body);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

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