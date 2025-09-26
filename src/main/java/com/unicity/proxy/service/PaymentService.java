package com.unicity.proxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicity.proxy.ProxyConfig;
import com.unicity.proxy.model.PaymentModels;
import com.unicity.proxy.model.PaymentSessionStatus;
import com.unicity.proxy.repository.ApiKeyRepository;
import com.unicity.proxy.repository.PaymentRepository;
import com.unicity.proxy.repository.PaymentRepository.PaymentSession;
import com.unicity.proxy.repository.PricingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.DirectAddress;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.MaskedPredicate;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.util.InclusionProofUtils;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PaymentService {
    // TODO: For production, we need to verify the token types
    public static final byte[] MOCK_TOKEN_TYPE = {3, 14, 15};

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PricingPlanRepository pricingPlanRepository;
    private final StateTransitionClient stateTransitionClient;
    private final ObjectMapper jsonMapper;
    private final SecureRandom secureRandom;

    private final byte[] serverSecret;

    private static final int SESSION_EXPIRY_MINUTES = 15;

    public PaymentService(ProxyConfig config, byte[] serverSecret) {
        this.paymentRepository = new PaymentRepository();
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
        this.jsonMapper = UnicityObjectMapper.JSON;
        this.secureRandom = new SecureRandom();

        String aggregatorUrl = config.getTargetUrl(); // Use same aggregator as proxy target
        AggregatorClient aggregatorClient = new AggregatorClient(aggregatorUrl);
        this.stateTransitionClient = new StateTransitionClient(aggregatorClient);

        this.serverSecret = serverSecret;

        logger.info("PaymentService initialized with aggregator: {}", aggregatorUrl);
    }

    public PaymentModels.InitiatePaymentResponse initiatePayment(
            PaymentModels.InitiatePaymentRequest request) {

        String apiKey = request.getApiKey();
        long targetPlanId = request.getTargetPlanId();

        if (apiKeyRepository.findByKeyIfNotRevoked(apiKey).isEmpty()) {
            throw new IllegalArgumentException("Unknown API key");
        }

        if (pricingPlanRepository.findById(targetPlanId) == null) {
            throw new IllegalArgumentException("Invalid target plan ID");
        }

        Optional<PaymentSession> existingSession = paymentRepository.findPendingByApiKey(apiKey);
        if (existingSession.isPresent()) {
            PaymentSession session = existingSession.get();
            return new PaymentModels.InitiatePaymentResponse(
                session.getId(),
                session.getPaymentAddress(),
                session.getAmountRequired(),
                session.getExpiresAt()
            );
        }

        byte[] receiverNonce = random32Bytes();

        SigningService signingService = SigningService.createFromMaskedSecret(
            serverSecret, receiverNonce
        );

        MaskedPredicate predicate = MaskedPredicate.create(
            signingService,
            HashAlgorithm.SHA256,
            receiverNonce
        );

        String paymentAddress = generatePaymentAddress(predicate);

        BigInteger amountRequired = getRequiredAmount(targetPlanId);

        Instant expiresAt = Instant.now().plusSeconds(SESSION_EXPIRY_MINUTES * 60);
        PaymentSession session = paymentRepository.createSession(
            apiKey, paymentAddress, receiverNonce,
            targetPlanId, amountRequired, expiresAt
        );

        if (session == null) {
            throw new RuntimeException("Failed to create payment session");
        }

        return new PaymentModels.InitiatePaymentResponse(
            session.getId(),
            session.getPaymentAddress(),
            session.getAmountRequired(),
            session.getExpiresAt()
        );
    }

    public PaymentModels.CompletePaymentResponse completePayment(
            PaymentModels.CompletePaymentRequest request) {

        UUID sessionId = request.getSessionId();

        Optional<PaymentSession> sessionOpt = paymentRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return new PaymentModels.CompletePaymentResponse(
                false, "Invalid session ID", null
            );
        }

        PaymentSession session = sessionOpt.get();

        if (session.getStatus() != PaymentSessionStatus.PENDING) {
            return new PaymentModels.CompletePaymentResponse(
                false, "Session is not pending", null
            );
        }

        if (Instant.now().isAfter(session.getExpiresAt())) {
            paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.EXPIRED, null);
            return new PaymentModels.CompletePaymentResponse(
                false, "Session has expired", null
            );
        }

        try {
            TransferCommitment transferCommitment = jsonMapper.readValue(
                request.getTransferCommitmentJson(), TransferCommitment.class
            );

            Token<?> sourceToken = jsonMapper.readValue(
                request.getSourceTokenJson(), Token.class
            );

            CompletableFuture<SubmitCommitmentResponse> futureResponse =
                stateTransitionClient.submitCommitment(sourceToken, transferCommitment);

            SubmitCommitmentResponse submitResponse =
                futureResponse.get(30, TimeUnit.SECONDS);

            if (submitResponse.getStatus() != SubmitCommitmentStatus.SUCCESS) {
                logger.error("Failed to submit transfer commitment: {}", submitResponse.getStatus());
                return new PaymentModels.CompletePaymentResponse(
                    false, "Failed to submit transfer: " + submitResponse.getStatus(), null
                );
            }

            CompletableFuture<InclusionProof> futureProof =
                InclusionProofUtils.waitInclusionProof(stateTransitionClient, transferCommitment);

            InclusionProof inclusionProof = futureProof.get(60, TimeUnit.SECONDS);

            Transaction<TransferTransactionData> transferTransaction =
                transferCommitment.toTransaction(sourceToken, inclusionProof);

            SigningService receiverSigningService = SigningService.createFromMaskedSecret(
                serverSecret, session.getReceiverNonce()
            );

            MaskedPredicate receiverPredicate = MaskedPredicate.create(
                receiverSigningService,
                HashAlgorithm.SHA256,
                session.getReceiverNonce()
            );

            var receivedToken = stateTransitionClient.finalizeTransaction(
                sourceToken,
                new TokenState(receiverPredicate, null),
                transferTransaction
            );

            if (!receivedToken.verify().isSuccessful()) {
                logger.error("Received token verification failed");
                return new PaymentModels.CompletePaymentResponse(
                    false, "Token verification failed", null
                );
            }

            BigInteger receivedAmount = calculateTokenAmount(receivedToken);
            if (receivedAmount.compareTo(session.getAmountRequired()) < 0) {
                logger.warn("Insufficient payment amount: {} < {}",
                    receivedAmount, session.getAmountRequired());
                paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.FAILED,
                    jsonMapper.writeValueAsString(receivedToken));
                return new PaymentModels.CompletePaymentResponse(
                    false, "Insufficient payment amount", null
                );
            }

            apiKeyRepository.updatePricingPlanAndExtendExpiry(
                session.getApiKey(), session.getTargetPlanId()
            );

            paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.COMPLETED,
                jsonMapper.writeValueAsString(receivedToken));

            logger.info("Payment completed successfully for session {} - API key {} upgraded to plan {}",
                sessionId, session.getApiKey(), session.getTargetPlanId());

            return new PaymentModels.CompletePaymentResponse(
                true,
                "Payment verified. API key upgraded successfully.",
                session.getTargetPlanId()
            );

        } catch (Exception e) {
            logger.error("Error processing payment for session {}", sessionId, e);
            paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.FAILED, null);
            return new PaymentModels.CompletePaymentResponse(
                false, "Payment processing failed: " + e.getMessage(), null
            );
        }
    }

    public Optional<PaymentModels.PaymentStatusResponse> getPaymentStatus(UUID sessionId) {
        return paymentRepository.findById(sessionId)
            .map(session -> new PaymentModels.PaymentStatusResponse(
                session.getId(),
                session.getStatus().getValue(),
                session.getAmountRequired(),
                session.getCreatedAt(),
                session.getCompletedAt(),
                session.getExpiresAt()
            ));
    }

    private String generatePaymentAddress(MaskedPredicate predicate) {
        TokenType tokenType = new TokenType(MOCK_TOKEN_TYPE);
        DirectAddress address = predicate.getReference(tokenType).toAddress();
        return address.getAddress();
    }

    private byte[] random32Bytes() {
        byte[] result = new byte[32];
        secureRandom.nextBytes(result);
        return result;
    }

    private BigInteger calculateTokenAmount(Token<?> token) {
        // TODO: Update the way to calculate token amounts for production.
        Optional<TokenCoinData> coinData = token.getGenesis().getData().getCoinData();
        if (coinData.isEmpty()) {
            throw new IllegalArgumentException("Missing coin data");
        }

        return coinData.get().getCoins().values()
                .stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private BigInteger getRequiredAmount(Long planId) {
       return pricingPlanRepository.findById(planId).getPrice();
    }
}