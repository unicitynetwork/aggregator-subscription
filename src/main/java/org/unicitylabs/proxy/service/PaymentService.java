package org.unicitylabs.proxy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import org.unicitylabs.proxy.ProxyConfig;
import org.unicitylabs.proxy.model.ApiKeyUtils;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.model.PaymentSessionStatus;
import org.unicitylabs.proxy.repository.*;
import org.unicitylabs.proxy.repository.PaymentRepository.PaymentSession;
import org.unicitylabs.proxy.shard.ShardRouter;
import org.unicitylabs.proxy.util.TokenTypeLoader;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicateReference;
import org.unicitylabs.sdk.util.HexConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.bft.RootTrustBase;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.CoinId;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.util.InclusionProofUtils;
import org.unicitylabs.sdk.verification.VerificationException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.unicitylabs.proxy.model.ApiKeyUtils.getExpiryStartingFrom;
import static org.unicitylabs.proxy.util.TimeUtils.currentTimeMillis;

public class PaymentService {
    private static final int SESSION_EXPIRY_MINUTES = 15;

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PricingPlanRepository pricingPlanRepository;
    private final ObjectMapper jsonMapper;
    private final SecureRandom secureRandom;
    private volatile ShardRouter shardRouter;
    private TimeMeter timeMeter;

    private final byte[] serverSecret;

    private final RootTrustBase trustBase;

    private final CoinId acceptedCoinId;

    private final BigInteger minimumPaymentAmount;

    private final TransactionManager transactionManager;

    private final TokenType tokenType;

    public PaymentService(ProxyConfig config, ShardRouter shardRouter, byte[] serverSecret) {
        this.paymentRepository = new PaymentRepository();
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
        this.jsonMapper = UnicityObjectMapper.JSON;
        this.secureRandom = new SecureRandom();
        this.timeMeter = TimeMeter.SYSTEM_MILLISECONDS;
        this.transactionManager = new TransactionManager();

        this.shardRouter = shardRouter;

        this.serverSecret = serverSecret;

        this.trustBase = loadRootTrustBase(config.getTrustBasePath());

        this.acceptedCoinId = new CoinId(HexConverter.decode(config.getAcceptedCoinId()));

        this.minimumPaymentAmount = config.getMinimumPaymentAmount();

        this.tokenType = loadTokenType(config.getTokenTypeIdsUrl(), config.getTokenTypeName());

        logger.info("PaymentService initialized with aggregators: {}, accepted coin ID: {}, minimum payment: {}, token type: {}",
            shardRouter, config.getAcceptedCoinId(), this.minimumPaymentAmount, config.getTokenTypeName());
    }

    public void setTimeMeter(TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
    }

    public void updateShardRouter(ShardRouter newRouter) {
        this.shardRouter = newRouter;
        logger.info("PaymentService updated with new shard router ({} targets)", newRouter.getAllTargets().size());
    }

    private RootTrustBase loadRootTrustBase(String trustBasePath) {
        try {
            if (trustBasePath == null) {
                logger.info("Using default test trust base");
                return UnicityObjectMapper.JSON.readValue(
                        getClass().getResourceAsStream("/test-trust-base.json"),
                        RootTrustBase.class);
            }

            return loadTrustBaseFromFile(trustBasePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load trust base", e);
        }
    }

    private RootTrustBase loadTrustBaseFromFile(String trustBasePath) throws IOException {
        File trustBaseFile = new File(trustBasePath);
        if (!trustBaseFile.exists()) {
            throw new RuntimeException("Trust base file not found: " + trustBasePath);
        }
        logger.info("Loading trust base from: {}", trustBasePath);
        return UnicityObjectMapper.JSON.readValue(
                new FileInputStream(trustBaseFile),
                RootTrustBase.class);
    }

    private TokenType loadTokenType(String tokenTypeIdsUrl, String tokenTypeName) {
        try {
            return TokenTypeLoader.loadNonFungibleTokenType(tokenTypeIdsUrl, tokenTypeName);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load token type from " + tokenTypeIdsUrl, e);
        }
    }

    public PaymentModels.InitiatePaymentResponse initiatePayment(
            PaymentModels.InitiatePaymentRequest request) {

        return transactionManager.executeInTransaction(conn -> {
            try {
                String apiKey = request.getApiKey();
                long targetPlanId = request.getTargetPlanId();
                boolean shouldCreateKey = (apiKey == null || apiKey.isEmpty());

                if (!shouldCreateKey) {
                    if (apiKeyRepository.findByKeyIfNotRevoked(conn, apiKey).isEmpty()) {
                        throw new IllegalArgumentException("Unknown API key");
                    }

                    // Lock API key row if updating existing key (prevents concurrent payments)
                    apiKeyRepository.lockForUpdate(conn, apiKey);
                    logger.debug("Acquired lock for API key during payment initiation: {}", apiKey);
                }

                if (pricingPlanRepository.findById(conn, targetPlanId) == null) {
                    throw new IllegalArgumentException("Invalid target plan ID");
                }

                if (!shouldCreateKey) {
                    int cancelledCount = paymentRepository.cancelPendingSessions(conn, apiKey);
                    if (cancelledCount > 0) {
                        logger.info("Cancelled {} pending payment session(s) for API key {} before creating new one",
                            cancelledCount, apiKey);
                    }
                }

                byte[] receiverNonce = random32Bytes();

                String paymentAddress = generatePaymentAddress(
                        SigningService.createFromMaskedSecret(serverSecret, receiverNonce), 
                        receiverNonce);

                Instant expiresAt = Instant.ofEpochMilli(currentTimeMillis(timeMeter))
                        .plusSeconds(SESSION_EXPIRY_MINUTES * 60);

                PaymentAmount paymentAmount = calculateRequiredPayment(conn, targetPlanId, shouldCreateKey, apiKey, expiresAt);

                PaymentSession session = paymentRepository.createSessionWithOptionalKey(
                    conn, apiKey, paymentAddress, receiverNonce,
                    targetPlanId, paymentAmount.requiredPayment(), expiresAt,
                    shouldCreateKey, paymentAmount.refundAmount());

                return new PaymentModels.InitiatePaymentResponse(
                    session.id(),
                    session.paymentAddress(),
                    session.amountRequired(),
                    HexConverter.encode(acceptedCoinId.getBytes()),
                    session.expiresAt()
                );
            } catch (IllegalArgumentException e) {
                // Let validation errors propagate to HTTP layer for proper 400 response
                throw e;
            } catch (Exception e) {
                logger.error("Error initiating payment", e);
                throw new RuntimeException("Payment initiation failed: " + e.getMessage(), e);
            }
        });
    }

    private PaymentAmount calculateRequiredPayment(Connection conn, Long targetPlanId, boolean shouldCreateKey, String apiKey, Instant expiresAt) throws SQLException {
        BigInteger newPlanPrice = pricingPlanRepository.findById(conn, targetPlanId).price();
        BigInteger refundAmount = BigInteger.ZERO;

        if (!shouldCreateKey) {
            var existingKeyInfo = apiKeyRepository.findByKeyIfNotRevoked(conn, apiKey);
            if (existingKeyInfo.isPresent() && existingKeyInfo.get().pricingPlanId() != null
                    && existingKeyInfo.get().activeUntil() != null) {

                var currentPlan = pricingPlanRepository.findById(conn, existingKeyInfo.get().pricingPlanId());
                if (currentPlan != null) {
                    refundAmount = calculateProRatedProportionalRefund(
                        currentPlan.price(),
                        existingKeyInfo.get().activeUntil().toInstant(),
                            expiresAt
                    );
                }
            }
        }

        BigInteger requiredPayment = calculateActualPaymentAmount(newPlanPrice, refundAmount);
        return new PaymentAmount(refundAmount, requiredPayment);
    }

    private record PaymentAmount(BigInteger refundAmount, BigInteger requiredPayment) {
    }

    private String generatePaymentAddress(SigningService signingService, byte[] receiverNonce) {
        return MaskedPredicateReference.create(
                tokenType,
                signingService,
                HashAlgorithm.SHA256,
                receiverNonce
        ).toAddress().getAddress();
    }

    public PaymentModels.CompletePaymentResponse completePayment(PaymentModels.CompletePaymentRequest request) {

        final TransferCommitment transferCommitment = saveAndCommitRequestToSession(request);

        return transactionManager.executeInTransaction(conn -> {
            return tryCompletingThePayment(request, conn, transferCommitment);
        });
    }

    private PaymentModels.CompletePaymentResponse tryCompletingThePayment(PaymentModels.CompletePaymentRequest request, Connection conn, TransferCommitment transferCommitment) {
        try {
            UUID sessionId = request.getSessionId();

            Optional<PaymentSession> sessionOptUnlocked = paymentRepository.findById(conn, sessionId);
            if (sessionOptUnlocked.isEmpty()) {
                return new PaymentModels.CompletePaymentResponse(
                        false, "Invalid session ID", null, null
                );
            }

            PaymentSession session = acquireDbLocks(conn, sessionOptUnlocked.get().apiKey(), sessionId);

            PaymentModels.CompletePaymentResponse sessionValidationError = validateSessionForPaymentCompletion(conn, session);
            if (sessionValidationError != null) { return sessionValidationError; }

            Token<?> sourceToken = jsonMapper.readValue(request.getSourceTokenJson(), Token.class);

            PaymentModels.CompletePaymentResponse validationError = validateIncomingCoins(conn, sourceToken, session);
            if (validationError != null) { return validationError; }

            SubmitCommitmentResult submitCommitmentResult = submitAndFinaliseCommitment(transferCommitment, session, sourceToken);
            if (submitCommitmentResult.error != null) { return submitCommitmentResult.error; }

            final String apiKey = finaliseApiKeyAndPaymentPlan(conn, session, submitCommitmentResult);

            logger.info("Payment completed successfully for session {} - API key {} with plan {}",
                session.id(), apiKey, session.targetPlanId());

            return new PaymentModels.CompletePaymentResponse(
                    true,
                    session.shouldCreateKey() ?
                            "Payment verified. New API key created successfully." :
                            "Payment verified. API key upgraded successfully.",
                    session.targetPlanId(),
                    apiKey
            );

        } catch (Exception e) {
            logger.error("Error processing payment for session {}: {}", request.getSessionId(), e.getMessage());
            throw new RuntimeException("Payment processing failed: " + e.getMessage(), e);
        }
    }

    private String finaliseApiKeyAndPaymentPlan(Connection conn, PaymentSession session, SubmitCommitmentResult submitCommitmentResult) throws SQLException, JsonProcessingException {
        Instant newExpiry = getExpiryStartingFrom(timeMeter);

        final String apiKey;
        if (session.shouldCreateKey()) {
            apiKey = ApiKeyService.generateApiKey();
            apiKeyRepository.insert(conn, apiKey, session.targetPlanId(), newExpiry);
            // Update the session with the generated API key
            paymentRepository.updateSessionApiKey(conn, session.id(), apiKey);
            logger.info("Created new API key {} for session {}", apiKey, session.id());
        } else {
            apiKey = session.apiKey();
            // Update existing API key's pricing plan and set new fixed expiry (not extend)
            apiKeyRepository.updatePricingPlanAndSetExpiry(conn, apiKey, session.targetPlanId(), newExpiry);
        }

        paymentRepository.updateSessionStatus(conn, session.id(), PaymentSessionStatus.COMPLETED,
            jsonMapper.writeValueAsString(submitCommitmentResult.receivedToken));
        return apiKey;
    }

    private record SubmitCommitmentResult(Token<?> receivedToken, PaymentModels.CompletePaymentResponse error) {}
    
    private SubmitCommitmentResult submitAndFinaliseCommitment(TransferCommitment transferCommitment, PaymentSession session, Token<?> sourceToken) throws ExecutionException, InterruptedException, TimeoutException, VerificationException
    {
        String hexRequestId = HexConverter.encode(canonicalRequestId(transferCommitment.getRequestId().toBitString().toBigInteger()));
        JsonRpcAggregatorClient aggregatorClient = new JsonRpcAggregatorClient(shardRouter.routeByRequestId(hexRequestId));
        var stateTransitionClient = new StateTransitionClient(aggregatorClient);

        SubmitCommitmentResponse submitResponse = stateTransitionClient.submitCommitment(transferCommitment).get(30, TimeUnit.SECONDS);

        if (submitResponse.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            logger.error("Failed to submit transfer commitment: {}", submitResponse.getStatus());
            return new SubmitCommitmentResult(
                    null,
                    new PaymentModels.CompletePaymentResponse(
                    false, "Failed to submit transfer: " + submitResponse.getStatus(), null, null));
        }

        InclusionProof inclusionProof = InclusionProofUtils.waitInclusionProof(stateTransitionClient, trustBase, transferCommitment).get(60, TimeUnit.SECONDS);

        TransferTransaction transferTransaction = transferCommitment.toTransaction(inclusionProof);

        SigningService receiverSigningService = SigningService.createFromMaskedSecret(serverSecret, session.receiverNonce());

        MaskedPredicate receiverPredicate = MaskedPredicate.create(
                sourceToken.getId(),
                tokenType,
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
            return new SubmitCommitmentResult(
                    null,
                    new PaymentModels.CompletePaymentResponse(
                    false, "Token verification failed", null, null));
        }
        
        return new SubmitCommitmentResult(receivedToken, null);
    }

    private PaymentModels.CompletePaymentResponse validateIncomingCoins(Connection conn, Token<?> sourceToken, PaymentSession session) throws SQLException, JsonProcessingException {
        // Validate that all coins have the accepted coin ID
        if (!hasOnlyAcceptedCoins(sourceToken, acceptedCoinId)) {
            logger.warn("Payment contains coins with unaccepted coin IDs. Only coin ID {} is accepted.",
                    HexConverter.encode(acceptedCoinId.getBytes()));
            paymentRepository.updateSessionStatus(conn, session.id(), PaymentSessionStatus.FAILED,
                    jsonMapper.writeValueAsString(sourceToken));
            return new PaymentModels.CompletePaymentResponse(
                    false, "Payment contains unaccepted coin types. Only coin ID " +
                    HexConverter.encode(acceptedCoinId.getBytes()) + " is accepted.", null, null
            );
        }

        BigInteger receivedAmount = calculateTokenAmount(sourceToken, acceptedCoinId);
        BigInteger requiredAmount = session.amountRequired();

        // Validate exact payment amount
        if (receivedAmount.compareTo(requiredAmount) < 0) {
            logger.warn("Insufficient payment amount: {} < {}",
                    receivedAmount, requiredAmount);
            paymentRepository.updateSessionStatus(conn, session.id(), PaymentSessionStatus.FAILED,
                    jsonMapper.writeValueAsString(sourceToken));
            return new PaymentModels.CompletePaymentResponse(
                    false, "Insufficient payment amount. Required: " + requiredAmount +
                    ", received: " + receivedAmount, null, null
            );
        }

        if (receivedAmount.compareTo(requiredAmount) > 0) {
            logger.warn("Overpayment rejected: {} > {}",
                    receivedAmount, requiredAmount);
            paymentRepository.updateSessionStatus(conn, session.id(), PaymentSessionStatus.FAILED,
                    jsonMapper.writeValueAsString(sourceToken));
            return new PaymentModels.CompletePaymentResponse(
                    false, "Overpayment not accepted. Required: " + requiredAmount +
                    ", received: " + receivedAmount +
                    ". Please send the exact amount to protect against accidental overpayment.", null, null
            );
        }
        return null;
    }

    private PaymentModels.CompletePaymentResponse validateSessionForPaymentCompletion (Connection conn, PaymentSession session) throws SQLException {
        if (session.status() != PaymentSessionStatus.PENDING) {
            return new PaymentModels.CompletePaymentResponse(
                    false, "Session is not pending", null, null
            );
        }

        if (Instant.ofEpochMilli(currentTimeMillis(timeMeter)).isAfter(session.expiresAt())) {
            paymentRepository.updateSessionStatus(conn, session.id(), PaymentSessionStatus.EXPIRED, null);
            return new PaymentModels.CompletePaymentResponse(
                    false, "Session has expired", null, null
            );
        }
        return null;
    }

    private PaymentSession acquireDbLocks(Connection conn, String apiKey, UUID sessionId) throws SQLException {
        // STEP 2: Lock API key FIRST (if it exists) to maintain consistent lock ordering
        // Lock order must match initiatePayment(): API key → Session
        // This prevents deadlock between completePayment() and initiatePayment()
        if (apiKey != null && !apiKey.isEmpty()) {
            apiKeyRepository.lockForUpdate(conn, apiKey);
            logger.debug("Acquired lock for API key during payment completion: {}", apiKey);
        }

        // STEP 3: Now lock the session.
        // The session may have changed state between unlocked read and now, so we re-validate
        Optional<PaymentSession> sessionOpt = paymentRepository.findByIdAndLock(conn, sessionId);

        return sessionOpt.orElseThrow(() -> new RuntimeException("Payment session missing: " + sessionId));
    }

    private TransferCommitment saveAndCommitRequestToSession(PaymentModels.CompletePaymentRequest request) {
        final TransferCommitment transferCommitment;
        try {
            transferCommitment = jsonMapper.readValue(
                    request.getTransferCommitmentJson(), TransferCommitment.class
            );

            String requestId = HexConverter.encode(canonicalRequestId(transferCommitment.getRequestId().toBitString().toBigInteger()));

            // EARLY: Store completion request with unique request_id constraint
            // Even if later processing fails, we have this recorded
            // NOTE: This also prevents using the same requestId to pay for more than one session as
            //       there is a unique constraint in the database.
            try (Connection conn = DatabaseConfig.getConnection()) {
                paymentRepository.storeCompletionRequest(
                        conn,
                        request.getSessionId(),
                        requestId,
                        jsonMapper.writeValueAsString(request)
                );
                logger.info("Stored completion request for session {} with request_id {}",
                        request.getSessionId(), requestId);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return transferCommitment;
    }

    public static byte[] canonicalRequestId(BigInteger requestIdBitString) {
        String hex = requestIdBitString.toString(16);
        if (hex.startsWith("1") && hex.length() > 2) {
            // For a canonical Request ID value, remove e.g. the leading "1" from "10000..."
            hex = hex.substring(1);
        }
        return HexConverter.decode(hex);
    }

    private byte[] random32Bytes() {
        byte[] result = new byte[32];
        secureRandom.nextBytes(result);
        return result;
    }

    private BigInteger calculateTokenAmount(Token<?> token, CoinId acceptedCoinId) {
        Optional<TokenCoinData> coinData = token.getGenesis().getData().getCoinData();
        if (coinData.isEmpty()) {
            throw new IllegalArgumentException("Missing coin data");
        }

        return coinData.get().getCoins().entrySet()
                .stream()
                .filter(entry -> entry.getKey().equals(acceptedCoinId))
                .map(Map.Entry::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private boolean hasOnlyAcceptedCoins(Token<?> token, CoinId acceptedCoinId) {
        Optional<TokenCoinData> coinData = token.getGenesis().getData().getCoinData();
        return coinData.map(tokenCoinData -> tokenCoinData.getCoins().keySet()
                .stream()
                .allMatch(coinId -> coinId.equals(acceptedCoinId))).orElse(false);

    }

    private BigInteger calculateProRatedProportionalRefund(BigInteger currentPlanPrice, Instant currentExpiry, Instant sessionEndTime) {
        if (!currentExpiry.isAfter(sessionEndTime)) {
            return BigInteger.ZERO;
        }

        long totalPlanMillis = TimeUnit.DAYS.toMillis(ApiKeyUtils.PAYMENT_VALIDITY_DAYS);
        long remainingMillis = currentExpiry.toEpochMilli() - sessionEndTime.toEpochMilli();

        return currentPlanPrice
            .multiply(BigInteger.valueOf(remainingMillis))
            .divide(BigInteger.valueOf(totalPlanMillis));
    }

    private BigInteger calculateActualPaymentAmount(BigInteger newPlanPrice, BigInteger refundAmount) {
        BigInteger actualAmount = newPlanPrice.subtract(refundAmount);

        if (actualAmount.compareTo(minimumPaymentAmount) < 0) {
            return minimumPaymentAmount;
        }

        return actualAmount;
    }
}