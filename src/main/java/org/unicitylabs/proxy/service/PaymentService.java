package org.unicitylabs.proxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import org.unicitylabs.proxy.ProxyConfig;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.model.PaymentSessionStatus;
import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.PaymentRepository;
import org.unicitylabs.proxy.repository.PaymentRepository.PaymentSession;
import org.unicitylabs.proxy.repository.PricingPlanRepository;
import org.unicitylabs.sdk.util.HexConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.DirectAddress;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.util.InclusionProofUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.unicitylabs.proxy.util.TimeUtils.currentTimeMillis;

public class PaymentService {
    private static final int SESSION_EXPIRY_MINUTES = 15;

    public static final int PAYMENT_VALIDITY_DAYS = 30;

    public static final BigInteger MINIMUM_PAYMENT_AMOUNT = BigInteger.valueOf(1000);

    // TODO: Testnet token type - fixed for all tokens on testnet
    public static final TokenType TESTNET_TOKEN_TYPE = new TokenType(HexConverter.decode(
        "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509"));

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PricingPlanRepository pricingPlanRepository;
    private final StateTransitionClient stateTransitionClient;
    private final ObjectMapper jsonMapper;
    private final SecureRandom secureRandom;
    private TimeMeter timeMeter;

    private final byte[] serverSecret;

    private final RootTrustBase trustBase;

    public PaymentService(ProxyConfig config, byte[] serverSecret) {
        this.paymentRepository = new PaymentRepository();
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
        this.jsonMapper = UnicityObjectMapper.JSON;
        this.secureRandom = new SecureRandom();
        this.timeMeter = TimeMeter.SYSTEM_MILLISECONDS;

        String aggregatorUrl = config.getTargetUrl(); // Use same aggregator as proxy target
        AggregatorClient aggregatorClient = new AggregatorClient(aggregatorUrl);
        this.stateTransitionClient = new StateTransitionClient(aggregatorClient);

        this.serverSecret = serverSecret;

        this.trustBase = loadRootTrustBase(config.getTrustBasePath());

        logger.info("PaymentService initialized with aggregator: {}", aggregatorUrl);
    }

    public void setTimeMeter(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
    }

    private RootTrustBase loadRootTrustBase(String trustBasePath) {
        try {
            // Load trust base from config path if provided, otherwise use default
            if (trustBasePath != null) {
                File trustBaseFile = new File(trustBasePath);
                if (!trustBaseFile.exists()) {
                    throw new RuntimeException("Trust base file not found: " + trustBasePath);
                }
                logger.info("Loading trust base from: {}", trustBasePath);
                return UnicityObjectMapper.JSON.readValue(
                        new FileInputStream(trustBaseFile),
                        RootTrustBase.class
                );
            } else {
                logger.info("Using default test trust base");
                return UnicityObjectMapper.JSON.readValue(
                        getClass().getResourceAsStream("/test-trust-base.json"),
                        RootTrustBase.class
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load trust base", e);
        }
    }

    public PaymentModels.InitiatePaymentResponse initiatePayment(
            PaymentModels.InitiatePaymentRequest request) {

        String apiKey = request.getApiKey();
        long targetPlanId = request.getTargetPlanId();
        boolean shouldCreateKey = (apiKey == null || apiKey.isEmpty());

        if (!shouldCreateKey && apiKeyRepository.findByKeyIfNotRevoked(apiKey).isEmpty()) {
            throw new IllegalArgumentException("Unknown API key");
        }

        if (pricingPlanRepository.findById(targetPlanId) == null) {
            throw new IllegalArgumentException("Invalid target plan ID");
        }

        if (!shouldCreateKey) {
            int cancelledCount = paymentRepository.cancelPendingSessions(apiKey);
            if (cancelledCount > 0) {
                logger.info("Cancelled {} pending payment session(s) for API key {} before creating new one",
                    cancelledCount, apiKey);
            }
        }

        byte[] receiverNonce = random32Bytes();

        SigningService signingService = SigningService.createFromMaskedSecret(
            serverSecret, receiverNonce
        );

        // Use a dummy tokenId for address generation (address doesn't depend on tokenId)
        TokenId dummyTokenId = new TokenId(new byte[32]);
        MaskedPredicate predicate = MaskedPredicate.create(
            dummyTokenId,
            TESTNET_TOKEN_TYPE,
            signingService,
            HashAlgorithm.SHA256,
            receiverNonce
        );

        String paymentAddress = generatePaymentAddress(predicate);

        BigInteger newPlanPrice = getRequiredAmount(targetPlanId);
        BigInteger refundAmount = BigInteger.ZERO;
        Instant sessionEndTime = Instant.ofEpochMilli(currentTimeMillis(timeMeter)).plusSeconds(SESSION_EXPIRY_MINUTES * 60);

        if (!shouldCreateKey) {
            var existingKeyInfo = apiKeyRepository.findByKeyIfNotRevoked(apiKey);
            if (existingKeyInfo.isPresent() && existingKeyInfo.get().pricingPlanId() != null
                    && existingKeyInfo.get().activeUntil() != null) {

                var currentPlan = pricingPlanRepository.findById(existingKeyInfo.get().pricingPlanId());
                if (currentPlan != null) {
                    refundAmount = calculateProRatedRefund(
                        currentPlan.price(),
                        existingKeyInfo.get().activeUntil().toInstant(),
                        sessionEndTime
                    );
                }
            }
        }

        BigInteger actualPaymentAmount = calculateActualPaymentAmount(newPlanPrice, refundAmount);

        Instant expiresAt = sessionEndTime;
        PaymentSession session = paymentRepository.createSessionWithOptionalKey(
            apiKey, paymentAddress, receiverNonce,
            targetPlanId, actualPaymentAmount, expiresAt,
            shouldCreateKey, refundAmount
        );

        if (session == null) {
            throw new RuntimeException("Failed to create payment session");
        }

        return new PaymentModels.InitiatePaymentResponse(
            session.id(),
            session.paymentAddress(),
            session.amountRequired(),
            session.expiresAt()
        );
    }

    public PaymentModels.CompletePaymentResponse completePayment(
            PaymentModels.CompletePaymentRequest request) {

        UUID sessionId = request.getSessionId();

        Optional<PaymentSession> sessionOpt = paymentRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            return new PaymentModels.CompletePaymentResponse(
                false, "Invalid session ID", null, null
            );
        }

        PaymentSession session = sessionOpt.get();

        if (session.status() != PaymentSessionStatus.PENDING) {
            return new PaymentModels.CompletePaymentResponse(
                false, "Session is not pending", null, null
            );
        }

        if (Instant.ofEpochMilli(currentTimeMillis(timeMeter)).isAfter(session.expiresAt())) {
            paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.EXPIRED, null);
            return new PaymentModels.CompletePaymentResponse(
                false, "Session has expired", null, null
            );
        }

        try {
            TransferCommitment transferCommitment = jsonMapper.readValue(
                request.getTransferCommitmentJson(), TransferCommitment.class
            );

            Token<?> sourceToken = jsonMapper.readValue(
                request.getSourceTokenJson(), Token.class
            );

            TokenId tokenId = sourceToken.getId();

            CompletableFuture<SubmitCommitmentResponse> futureResponse =
                stateTransitionClient.submitCommitment(transferCommitment);

            SubmitCommitmentResponse submitResponse =
                futureResponse.get(30, TimeUnit.SECONDS);

            if (submitResponse.getStatus() != SubmitCommitmentStatus.SUCCESS) {
                logger.error("Failed to submit transfer commitment: {}", submitResponse.getStatus());
                return new PaymentModels.CompletePaymentResponse(
                    false, "Failed to submit transfer: " + submitResponse.getStatus(), null, null
                );
            }

            CompletableFuture<InclusionProof> futureProof =
                InclusionProofUtils.waitInclusionProof(stateTransitionClient, trustBase, transferCommitment);

            InclusionProof inclusionProof = futureProof.get(60, TimeUnit.SECONDS);

            Transaction<TransferTransactionData> transferTransaction =
                transferCommitment.toTransaction(inclusionProof);

            SigningService receiverSigningService = SigningService.createFromMaskedSecret(
                serverSecret, session.receiverNonce()
            );

            MaskedPredicate receiverPredicate = MaskedPredicate.create(
                tokenId,
                TESTNET_TOKEN_TYPE,
                receiverSigningService,
                HashAlgorithm.SHA256,
                session.receiverNonce()
            );

            var receivedToken = stateTransitionClient.finalizeTransaction(
                trustBase,
                sourceToken,
                new TokenState(receiverPredicate, null),
                transferTransaction
            );

            if (!receivedToken.verify(trustBase).isSuccessful()) {
                logger.error("Received token verification failed");
                return new PaymentModels.CompletePaymentResponse(
                    false, "Token verification failed", null, null
                );
            }

            BigInteger receivedAmount = calculateTokenAmount(receivedToken);
            if (receivedAmount.compareTo(session.amountRequired()) < 0) {
                logger.warn("Insufficient payment amount: {} < {}",
                    receivedAmount, session.amountRequired());
                paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.FAILED,
                    jsonMapper.writeValueAsString(receivedToken));
                return new PaymentModels.CompletePaymentResponse(
                    false, "Insufficient payment amount", null, null
                );
            }

            String finalApiKey = session.apiKey();

            Instant newExpiry = Instant.ofEpochMilli(currentTimeMillis(timeMeter)).plus(PAYMENT_VALIDITY_DAYS, java.time.temporal.ChronoUnit.DAYS);

            if (session.shouldCreateKey()) {
                finalApiKey = ApiKeyService.generateApiKey();
                apiKeyRepository.insert(finalApiKey, session.targetPlanId(), newExpiry);
                // Update the session with the generated API key
                paymentRepository.updateSessionApiKey(sessionId, finalApiKey);
                logger.info("Created new API key {} for session {}", finalApiKey, sessionId);
            } else {
                // Update existing API key's pricing plan and set new fixed expiry (not extend)
                apiKeyRepository.updatePricingPlanAndSetExpiry(finalApiKey, session.targetPlanId(), newExpiry);
            }

            paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.COMPLETED,
                jsonMapper.writeValueAsString(receivedToken));

            logger.info("Payment completed successfully for session {} - API key {} with plan {}",
                sessionId, finalApiKey, session.targetPlanId());

            return new PaymentModels.CompletePaymentResponse(
                true,
                session.shouldCreateKey() ?
                        "Payment verified. New API key created successfully.":
                        "Payment verified. API key upgraded successfully.",
                session.targetPlanId(),
                finalApiKey
            );

        } catch (Exception e) {
            logger.error("Error processing payment for session {}", sessionId, e);
            paymentRepository.updateSessionStatus(sessionId, PaymentSessionStatus.FAILED, null);
            return new PaymentModels.CompletePaymentResponse(
                false, "Payment processing failed: " + e.getMessage(), null, null
            );
        }
    }

    private String generatePaymentAddress(MaskedPredicate predicate) {
        DirectAddress address = predicate.getReference().toAddress();
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
       return pricingPlanRepository.findById(planId).price();
    }

    private BigInteger calculateProRatedRefund(BigInteger currentPlanPrice, Instant currentExpiry, Instant sessionEndTime) {

        if (!currentExpiry.isAfter(sessionEndTime)) {
            return BigInteger.ZERO;
        }

        long totalPlanMillis = TimeUnit.DAYS.toMillis(PAYMENT_VALIDITY_DAYS);
        long remainingMillis = currentExpiry.toEpochMilli() - sessionEndTime.toEpochMilli();

        // Calculate proportional refund
        BigInteger refund = currentPlanPrice
            .multiply(BigInteger.valueOf(remainingMillis))
            .divide(BigInteger.valueOf(totalPlanMillis));

        return refund;
    }

    private BigInteger calculateActualPaymentAmount(BigInteger newPlanPrice, BigInteger refundAmount) {
        BigInteger actualAmount = newPlanPrice.subtract(refundAmount);

        if (actualAmount.compareTo(MINIMUM_PAYMENT_AMOUNT) < 0) {
            return MINIMUM_PAYMENT_AMOUNT;
        }

        return actualAmount;
    }
}