package org.unicitylabs.proxy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import org.unicitylabs.proxy.ProxyConfig;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.model.PaymentSessionStatus;
import org.unicitylabs.proxy.repository.*;
import org.unicitylabs.proxy.repository.PaymentRepository.PaymentSession;
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
import org.unicitylabs.sdk.token.fungible.CoinId;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.*;
import org.unicitylabs.sdk.util.InclusionProofUtils;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.unicitylabs.proxy.util.TimeUtils.currentTimeMillis;

public class PaymentService {
    private static final int SESSION_EXPIRY_MINUTES = 15;

    public static final int PAYMENT_VALIDITY_DAYS = 30;

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

    private final CoinId acceptedCoinId;

    private final BigInteger minimumPaymentAmount;

    private final TransactionManager transactionManager;

    public PaymentService(ProxyConfig config, byte[] serverSecret) {
        this.paymentRepository = new PaymentRepository();
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
        this.jsonMapper = UnicityObjectMapper.JSON;
        this.secureRandom = new SecureRandom();
        this.timeMeter = TimeMeter.SYSTEM_MILLISECONDS;
        this.transactionManager = new TransactionManager();

        String aggregatorUrl = config.getTargetUrl(); // Use same aggregator as proxy target
        AggregatorClient aggregatorClient = new AggregatorClient(aggregatorUrl);
        this.stateTransitionClient = new StateTransitionClient(aggregatorClient);

        this.serverSecret = serverSecret;

        this.trustBase = loadRootTrustBase(config.getTrustBasePath());

        this.acceptedCoinId = new CoinId(HexConverter.decode(config.getAcceptedCoinId()));

        this.minimumPaymentAmount = config.getMinimumPaymentAmount();

        logger.info("PaymentService initialized with aggregator: {}, accepted coin ID: {}, minimum payment: {}",
            aggregatorUrl, config.getAcceptedCoinId(), this.minimumPaymentAmount);
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

                BigInteger newPlanPrice = getRequiredAmount(conn, targetPlanId);
                BigInteger refundAmount = BigInteger.ZERO;
                Instant expiresAt = Instant.ofEpochMilli(currentTimeMillis(timeMeter))
                    .plusSeconds(SESSION_EXPIRY_MINUTES * 60);

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

                BigInteger actualPaymentAmount = calculateActualPaymentAmount(newPlanPrice, refundAmount);

                PaymentSession session = paymentRepository.createSessionWithOptionalKey(
                    conn, apiKey, paymentAddress, receiverNonce,
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

    public PaymentModels.CompletePaymentResponse completePayment(
            PaymentModels.CompletePaymentRequest request) {

        final TransferCommitment transferCommitment;
        try {
            transferCommitment = jsonMapper.readValue(
                    request.getTransferCommitmentJson(), TransferCommitment.class
            );
            String requestId = HexConverter.encode(transferCommitment.getRequestId().toBitString().toBytes());

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

        return transactionManager.executeInTransaction(conn -> {
            try {
                UUID sessionId = request.getSessionId();

                // STEP 1: Read session (unlocked) to determine if we need to lock an API key
                Optional<PaymentSession> sessionOptUnlocked = paymentRepository.findById(conn, sessionId);
                if (sessionOptUnlocked.isEmpty()) {
                    return new PaymentModels.CompletePaymentResponse(
                        false, "Invalid session ID", null, null
                    );
                }

                // STEP 2: Lock API key FIRST (if it exists) to maintain consistent lock ordering
                // Lock order must match initiatePayment(): API key → Session
                // This prevents deadlock between completePayment() and initiatePayment()
                String apiKey = sessionOptUnlocked.get().apiKey();
                if (apiKey != null && !apiKey.isEmpty()) {
                    apiKeyRepository.lockForUpdate(conn, apiKey);
                    logger.debug("Acquired lock for API key during payment completion: {}", apiKey);
                }

                // STEP 3: Now lock the session.
                // The session may have changed state between unlocked read and now, so we re-validate
                Optional<PaymentSession> sessionOpt = paymentRepository.findByIdAndLock(conn, sessionId);
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
                    paymentRepository.updateSessionStatus(conn, sessionId, PaymentSessionStatus.EXPIRED, null);
                    return new PaymentModels.CompletePaymentResponse(
                        false, "Session has expired", null, null
                    );
                }

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

                // Validate that all coins have the accepted coin ID
                if (!hasOnlyAcceptedCoins(receivedToken, acceptedCoinId)) {
                    logger.warn("Payment contains coins with unaccepted coin IDs. Only coin ID {} is accepted.",
                        HexConverter.encode(acceptedCoinId.getBytes()));
                    paymentRepository.updateSessionStatus(conn, sessionId, PaymentSessionStatus.FAILED,
                        jsonMapper.writeValueAsString(receivedToken));
                    return new PaymentModels.CompletePaymentResponse(
                        false, "Payment contains unaccepted coin types. Only coin ID " +
                            HexConverter.encode(acceptedCoinId.getBytes()) + " is accepted.", null, null
                    );
                }

                BigInteger receivedAmount = calculateTokenAmount(receivedToken, acceptedCoinId);
                BigInteger requiredAmount = session.amountRequired();

                // Validate exact payment amount
                if (receivedAmount.compareTo(requiredAmount) < 0) {
                    logger.warn("Insufficient payment amount: {} < {}",
                        receivedAmount, requiredAmount);
                    paymentRepository.updateSessionStatus(conn, sessionId, PaymentSessionStatus.FAILED,
                        jsonMapper.writeValueAsString(receivedToken));
                    return new PaymentModels.CompletePaymentResponse(
                        false, "Insufficient payment amount. Required: " + requiredAmount +
                            ", received: " + receivedAmount, null, null
                    );
                }

                if (receivedAmount.compareTo(requiredAmount) > 0) {
                    logger.warn("Overpayment rejected: {} > {}",
                        receivedAmount, requiredAmount);
                    paymentRepository.updateSessionStatus(conn, sessionId, PaymentSessionStatus.FAILED,
                        jsonMapper.writeValueAsString(receivedToken));
                    return new PaymentModels.CompletePaymentResponse(
                        false, "Overpayment not accepted. Required: " + requiredAmount +
                            ", received: " + receivedAmount +
                            ". Please send the exact amount to protect against accidental overpayment.", null, null
                    );
                }

                String finalApiKey = session.apiKey();

                Instant newExpiry = Instant.ofEpochMilli(currentTimeMillis(timeMeter))
                    .plus(PAYMENT_VALIDITY_DAYS, java.time.temporal.ChronoUnit.DAYS);

                if (session.shouldCreateKey()) {
                    finalApiKey = ApiKeyService.generateApiKey();
                    apiKeyRepository.insert(conn, finalApiKey, session.targetPlanId(), newExpiry);
                    // Update the session with the generated API key
                    paymentRepository.updateSessionApiKey(conn, sessionId, finalApiKey);
                    logger.info("Created new API key {} for session {}", finalApiKey, sessionId);
                } else {
                    // Update existing API key's pricing plan and set new fixed expiry (not extend)
                    apiKeyRepository.updatePricingPlanAndSetExpiry(conn, finalApiKey, session.targetPlanId(), newExpiry);
                }

                paymentRepository.updateSessionStatus(conn, sessionId, PaymentSessionStatus.COMPLETED,
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
                logger.error("Error processing payment for session {}: {}", request.getSessionId(), e.getMessage());
                throw new RuntimeException("Payment processing failed: " + e.getMessage(), e);
            }
        });
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

    private BigInteger getRequiredAmount(Connection conn, Long planId) throws SQLException {
       return pricingPlanRepository.findById(conn, planId).price();
    }

    private BigInteger calculateProRatedProportionalRefund(BigInteger currentPlanPrice, Instant currentExpiry, Instant sessionEndTime) {
        if (!currentExpiry.isAfter(sessionEndTime)) {
            return BigInteger.ZERO;
        }

        long totalPlanMillis = TimeUnit.DAYS.toMillis(PAYMENT_VALIDITY_DAYS);
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