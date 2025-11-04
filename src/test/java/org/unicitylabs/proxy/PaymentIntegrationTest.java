package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unicitylabs.proxy.model.ApiKeyUtils;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.model.ApiKeyStatus;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.PaymentRepository;
import org.unicitylabs.proxy.service.ApiKeyService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.AddressFactory;
import org.unicitylabs.sdk.address.DirectAddress;
import org.unicitylabs.sdk.api.*;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.jsonrpc.JsonRpcNetworkException;
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
import org.unicitylabs.sdk.util.HexConverter;
import org.unicitylabs.sdk.util.InclusionProofUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.unicitylabs.proxy.service.PaymentService.canonicalRequestId;
import static org.unicitylabs.proxy.util.TimeUtils.currentTimeMillis;
import static org.unicitylabs.sdk.transaction.InclusionProofVerificationStatus.PATH_NOT_INCLUDED;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaymentIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_API_KEY = "test-payment-key";
    private static final SecureRandom random = new SecureRandom();
    private static ObjectMapper objectMapper;

    private StateTransitionClient directToAggregator;
    private AggregatorClient aggregatorClient;

    private static final byte[] CLIENT_SECRET = randomBytes(32);
    private static final byte[] CLIENT_NONCE = randomBytes(32);

    private static final CoinId TESTNET_COIN_ID = new CoinId(org.unicitylabs.sdk.util.HexConverter.decode(
        "455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89"));

    // Token type ID for testnet (non-fungible unicity token)
    private static final TokenType TESTNET_TOKEN_TYPE = new TokenType(org.unicitylabs.sdk.util.HexConverter.decode(
        "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509"));

    private ApiKeyRepository apiKeyRepository;
    private PaymentRepository paymentRepository;

    @BeforeAll
    static void setupObjectMapper() {
        objectMapper = ObjectMapperUtils.createObjectMapper();
    }

    @Override
    protected void updateConfigForTests(ProxyConfig config) {
        super.updateConfigForTests(config);

        // Using a real aggregator here because our mock server is not capable of aggregating
        // transactions
        config.setPort(443);
        config.setTargetUrl(getRealAggregatorUrl());
    }

    @BeforeEach
    void setUpTestData() {
        apiKeyRepository = new ApiKeyRepository();
        apiKeyRepository.setTimeMeter(testTimeMeter);
        paymentRepository = new PaymentRepository();

        recreatePricingPlans();

        Instant expiry = Instant.ofEpochMilli(currentTimeMillis(testTimeMeter))
            .plus(ApiKeyUtils.PAYMENT_VALIDITY_DAYS, java.time.temporal.ChronoUnit.DAYS);
        apiKeyRepository.insert(TEST_API_KEY, PLAN_BASIC.id(), expiry);
        TestDatabaseSetup.markForDeletionDuringReset(TEST_API_KEY);

        String realAggregatorUrl = getRealAggregatorUrl();
        aggregatorClient = new JsonRpcAggregatorClient(realAggregatorUrl);
        directToAggregator = new StateTransitionClient(aggregatorClient);
    }

    @Test
    @Order(1)
    @DisplayName("Test complete payment flow with new API key creation")
    void testCompletePaymentWithNewKeyCreation() throws Exception {
        // Initiate payment without providing an API key - one will be created on successful payment
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSessionWithoutKey(PLAN_PREMIUM.id().intValue());

        var paymentResult = signAndSubmitPaymentWithDetails(paymentSession, tokenId);
        var paymentResponse = paymentResult.response();
        String createdApiKey = paymentResponse.getApiKey();
        assertTrue(createdApiKey.startsWith("sk_"), "API key should have correct format");

        String requestIdHex = HexConverter.encode(canonicalRequestId(paymentResult.transferCommitment().getRequestId().toBitString().toBigInteger()));

        assertRequestIdStoredInDatabase(paymentSession.getSessionId(), requestIdHex);
        assertRequestIdAggregatedOnBlockchain(paymentResult.transferCommitment());

        // Test that the new API key works
        final StateTransitionClient proxyConnectionWithApiKey = new StateTransitionClient(
            new JsonRpcAggregatorClient(getProxyUrl(), createdApiKey));
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);
    }

    @Test
    @Order(2)
    @DisplayName("Test payment flow with existing API key")
    void testPaymentWithExistingKey() throws Exception {
        // Use the pre-created TEST_API_KEY
        initiatePaymentSession(PLAN_PREMIUM.id().intValue(), TEST_API_KEY);

        // Test that the API key is now authorized
        final StateTransitionClient proxyConnectionWithApiKey = new StateTransitionClient(
            new JsonRpcAggregatorClient(getProxyUrl(), TEST_API_KEY));
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);

        // Make the key expire, then pay for it again
        testTimeMeter.setTime(Instant.ofEpochMilli(testTimeMeter.getTime())
            .atZone(ZoneId.systemDefault())
            .plusDays(ApiKeyUtils.PAYMENT_VALIDITY_DAYS)
            .plusMinutes(1)
            .toInstant()
            .toEpochMilli());

        assertApiKeyUnauthorizedForMinting(proxyConnectionWithApiKey);

        // Renew with another payment
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSession(PLAN_PREMIUM.id().intValue(), TEST_API_KEY);
        signAndSubmitPayment(paymentSession, tokenId);
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);
    }

    @Test
    @Order(3)
    @DisplayName("Test invalid API key rejection")
    void testInvalidApiKeyRejection() throws Exception {
        PaymentModels.InitiatePaymentRequest request =
            new PaymentModels.InitiatePaymentRequest(
                    "invalid-key",
                    PLAN_PREMIUM.id().intValue());

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/api/payment/initiate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
            .timeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());

        PaymentModels.ErrorResponse error =
            objectMapper.readValue(response.body(), PaymentModels.ErrorResponse.class);

        assertEquals("Bad Request", error.getError());
        assertThat(error.getMessage()).contains("Unknown API key");
    }

    @Test
    @Order(4)
    @DisplayName("Test API key details endpoint, key with a payment plan")
    void testApiKeyDetailsEndpoint_keyWithAPaymentPlan() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/key/" + TEST_API_KEY))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        assertEquals("""
                {
                  "status" : "active",
                  "expiresAt" : "%s",
                  "pricingPlan" : {
                    "id" : 1,
                    "name" : "basic",
                    "requestsPerSecond" : 5,
                    "requestsPerDay" : 50000,
                    "price" : "1000000"
                  }
                }""".formatted(apiKeyRepository.findByKeyIfNotRevoked(TEST_API_KEY).get().activeUntil().toInstant().toString()),
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(objectMapper.readTree(response.body())));
    }

    @Test
    @Order(5)
    @DisplayName("Test API key details endpoint, non-existent key")
    void testApiKeyDetailsEndpoint_nonExistentKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/key/non-existent-key"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());

        PaymentModels.ErrorResponse error =
                objectMapper.readValue(response.body(), PaymentModels.ErrorResponse.class);
        assertEquals("Not Found", error.getError());
        assertThat(error.getMessage()).contains("API key not found or revoked");
    }

    @Test
    @Order(6)
    @DisplayName("Test API key details endpoint, key without a pricing plan")
    void testApiKeyDetailsEndpoint_keyWithoutPricingPlan() throws Exception {
        // Create a key without payment plan using the repository directly
        String keyWithoutPlan = "test-key-no-plan";
        apiKeyRepository.createWithoutPlan(keyWithoutPlan);
        TestDatabaseSetup.markForDeletionDuringReset(keyWithoutPlan);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/key/" + keyWithoutPlan))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        assertEquals("""
                {
                  "status" : "active",
                  "expiresAt" : null,
                  "pricingPlan" : null,
                  "message" : "No active pricing plan. Payment required to activate API key."
                }""",
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(objectMapper.readTree(response.body())));
    }

    @Test
    @Order(7)
    @DisplayName("Test API key details endpoint, revoked key")
    void testApiKeyDetailsEndpoint_revokedKey() throws Exception {
        String revokedKey = insertNewPaymentKey("test-revoked-key", PLAN_BASIC.id());
        var keyDetail = apiKeyRepository.findByKey(revokedKey);

        apiKeyRepository.updateStatus(keyDetail.get().id(), ApiKeyStatus.REVOKED);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/key/" + revokedKey))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());

        PaymentModels.ErrorResponse error =
                objectMapper.readValue(response.body(), PaymentModels.ErrorResponse.class);
        assertEquals("Not Found", error.getError());
        assertThat(error.getMessage()).contains("API key not found or revoked");
    }

    @Test
    @Order(8)
    @DisplayName("Test session cancellation when initiating new payment")
    void testSessionCancellationOnNewPayment() throws Exception {
        // Create an API key with a plan
        String testKey = insertNewPaymentKey("test-cancellation-key", PLAN_BASIC.id());

        // Initiate first payment session for plan 2
        byte[] tokenId1 = randomBytes(32);
        PaymentModels.InitiatePaymentResponse session1 = initiatePaymentSession(PLAN_STANDARD.id().intValue(), testKey);
        assertNotNull(session1.getSessionId());
        UUID firstSessionId = session1.getSessionId();

        // Initiate second payment session for plan 3 (should cancel the first)
        byte[] tokenId2 = randomBytes(32);
        PaymentModels.InitiatePaymentResponse session2 = initiatePaymentSession(PLAN_PREMIUM.id().intValue(), testKey);
        assertNotNull(session2.getSessionId());
        assertNotEquals(firstSessionId, session2.getSessionId(), "Should create a new session");

        // Verify first session is cancelled by trying to complete it
        var token = mintInitialToken(session1.getPrice(), tokenId1);
        var transferData = createTransferCommitment(token, session1.getPaymentAddress());

        // Submit the transfer and wait for inclusion proof (this proves the payment was real)
        var submitResponse = directToAggregator.submitCommitment(transferData.commitment()).get(30, TimeUnit.SECONDS);
        assertEquals(SubmitCommitmentStatus.SUCCESS, submitResponse.getStatus());
        InclusionProofUtils.waitInclusionProof(directToAggregator, trustBase, transferData.commitment())
            .get(60, TimeUnit.SECONDS);

        // Try to complete the cancelled session
        PaymentModels.CompletePaymentRequest completeRequest = new PaymentModels.CompletePaymentRequest(
            firstSessionId,
            Base64.getEncoder().encodeToString(transferData.salt()),
            UnicityObjectMapper.JSON.writeValueAsString(transferData.commitment()),
            UnicityObjectMapper.JSON.writeValueAsString(token)
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/api/payment/complete"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(completeRequest)))
            .timeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(402, response.statusCode(), "Should reject payment for cancelled session");

        PaymentModels.CompletePaymentResponse completeResponse =
            objectMapper.readValue(response.body(), PaymentModels.CompletePaymentResponse.class);
        assertFalse(completeResponse.isSuccess());
        assertThat(completeResponse.getMessage()).contains("Session is not pending");

        // Verify second session is still valid and can be completed
        var paymentResponse = signAndSubmitPayment(session2, tokenId2);
        assertTrue(paymentResponse.isSuccess(), "Second session should complete successfully");
    }

    @Test
    @Order(9)
    @DisplayName("Test GET payment plans endpoint")
    void testGetPaymentPlans() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/api/payment/plans"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        assertEquals("""
                {
                  "availablePlans" : [ {
                    "planId" : 1,
                    "name" : "basic",
                    "requestsPerSecond" : 5,
                    "requestsPerDay" : 50000,
                    "price" : "1000000"
                  }, {
                    "planId" : 2,
                    "name" : "standard",
                    "requestsPerSecond" : 10,
                    "requestsPerDay" : 100000,
                    "price" : "5000000"
                  }, {
                    "planId" : 3,
                    "name" : "premium",
                    "requestsPerSecond" : 20,
                    "requestsPerDay" : 500000,
                    "price" : "10000000"
                  }, {
                    "planId" : 4,
                    "name" : "enterprise",
                    "requestsPerSecond" : 50,
                    "requestsPerDay" : 1000000,
                    "price" : "50000000"
                  }, {
                    "planId" : 5,
                    "name" : "test-basic",
                    "requestsPerSecond" : 5,
                    "requestsPerDay" : 50000,
                    "price" : "10000"
                  }, {
                    "planId" : 6,
                    "name" : "test-standard",
                    "requestsPerSecond" : 10,
                    "requestsPerDay" : 100000,
                    "price" : "20000"
                  }, {
                    "planId" : 7,
                    "name" : "test-premium",
                    "requestsPerSecond" : 20,
                    "requestsPerDay" : 500000,
                    "price" : "30000"
                  } ]
                }""",
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(objectMapper.readTree(response.body())));
    }

    @Test
    @Order(19)
    @DisplayName("Test GET payment plans endpoint with plan price below minimum")
    void testGetPaymentPlansWithBelowMinimumPrice() throws Exception {
        // Create a plan with price below minimum payment amount (1000)
        var pricingPlanRepository = new org.unicitylabs.proxy.repository.PricingPlanRepository();
        long planId = pricingPlanRepository.create("test-below-minimum", 1, 1000, BigInteger.valueOf(500));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/plans"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            // Parse response and verify the below-minimum plan shows minimum price (1000)
            var jsonNode = objectMapper.readTree(response.body());
            var plans = jsonNode.get("availablePlans");

            // Find our test plan
            boolean found = false;
            for (var plan : plans) {
                if (plan.get("planId").asLong() == planId) {
                    found = true;
                    // Verify price is the minimum (1000), not the database value (500)
                    assertEquals("1000", plan.get("price").asText(),
                        "Plan with price below minimum should display minimum payment amount");
                    break;
                }
            }
            assertTrue(found, "Test plan should be in response");
        } finally {
            TestPricingPlans.deleteTestPlansAndTheirApiKeys(List.of(planId));
        }
    }

    @Test
    @Order(20)
    @DisplayName("Test initPayment with plan price below minimum enforces minimum payment")
    void testInitPaymentWithBelowMinimumPlanPrice() throws Exception {
        // Create a plan with price below minimum payment amount (1000)
        var pricingPlanRepository = new org.unicitylabs.proxy.repository.PricingPlanRepository();
        long planId = pricingPlanRepository.create("test-init-below-minimum", 1, 1000, BigInteger.valueOf(100));

        try {
            // Initiate payment for this low-priced plan
            PaymentModels.InitiatePaymentResponse paymentSession =
                initiatePaymentSessionWithoutKey((int) planId);

            // Verify the amount required is the minimum (1000), not the plan price (100)
            assertEquals(config.getMinimumPaymentAmount(), paymentSession.getPrice(),
                "Payment amount should be minimum payment amount (1000), not plan price (100)");

            // Now complete the payment with the minimum amount and verify it succeeds
            byte[] tokenId = randomBytes(32);
            var paymentResponse = signAndSubmitPayment(paymentSession, tokenId);

            assertTrue(paymentResponse.isSuccess(),
                "Payment should succeed when paying the minimum amount");
            assertNotNull(paymentResponse.getApiKey(),
                "API key should be created after successful payment");
        } finally {
            TestPricingPlans.deleteTestPlansAndTheirApiKeys(List.of(planId));
        }
    }

    private record PaymentResult(PaymentModels.CompletePaymentResponse response, TransferCommitment transferCommitment) {}

    private PaymentModels.CompletePaymentResponse signAndSubmitPayment(PaymentModels.InitiatePaymentResponse paymentSession, byte[] tokenId) throws Exception {
        return signAndSubmitPaymentWithDetails(paymentSession, tokenId).response();
    }

    private PaymentResult signAndSubmitPaymentWithDetails(PaymentModels.InitiatePaymentResponse paymentSession, byte[] tokenId) throws Exception {
        var token = mintInitialToken(paymentSession.getPrice(), tokenId);

        // Create the transfer commitment
        var transferData = createTransferCommitment(token, paymentSession.getPaymentAddress());

        // Complete the payment
        String responseBody = submitRequest(new PaymentModels.CompletePaymentRequest(
                paymentSession.getSessionId(),
                Base64.getEncoder().encodeToString(transferData.salt()),
                UnicityObjectMapper.JSON.writeValueAsString(transferData.commitment()),
                UnicityObjectMapper.JSON.writeValueAsString(token)
        ));
        PaymentModels.CompletePaymentResponse response = objectMapper.readValue(responseBody, PaymentModels.CompletePaymentResponse.class);
        return new PaymentResult(response, transferData.commitment());
    }

    private record TransferData(TransferCommitment commitment, byte[] salt) {}

    private TransferData createTransferCommitment(Token<?> token, String paymentAddress) {
        SigningService clientSigningService = SigningService.createFromMaskedSecret(CLIENT_SECRET, CLIENT_NONCE);
        DirectAddress address = (DirectAddress) AddressFactory.createAddress(paymentAddress);
        byte[] salt = randomBytes(32);

        TransferCommitment transferCommitment = TransferCommitment.create(
                token,
                address,
                salt,
                null, // no data hash
                null, // no message
                clientSigningService
        );

        return new TransferData(transferCommitment, salt);
    }

    private PaymentModels.InitiatePaymentResponse initiatePaymentSessionWithoutKey(int targetPlanId)
            throws IOException, InterruptedException {
        // Create request without API key - it will be created on successful payment
        PaymentModels.InitiatePaymentRequest request = new PaymentModels.InitiatePaymentRequest(
                null, targetPlanId);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/initiate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        return objectMapper.readValue(response.body(), PaymentModels.InitiatePaymentResponse.class);
    }

    private PaymentModels.InitiatePaymentResponse initiatePaymentSession(int targetPlanId, String apiKey) throws IOException, InterruptedException {
        PaymentModels.InitiatePaymentRequest request = new PaymentModels.InitiatePaymentRequest(apiKey, targetPlanId);
        String initPaymentResponseBody = submitRequest(request);
        return objectMapper.readValue(initPaymentResponseBody, PaymentModels.InitiatePaymentResponse.class);
    }

    private String submitRequest(PaymentModels.InitiatePaymentRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/initiate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private String submitRequest(PaymentModels.CompletePaymentRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/complete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private Token<?> mintInitialToken(BigInteger amount, byte[] tokenId) throws Exception {
        return mintInitialToken(amount, tokenId, TESTNET_COIN_ID);
    }

    private Token<?> mintInitialToken(BigInteger amount, byte[] tokenId, CoinId coinId) throws Exception {
        Map<CoinId, BigInteger> coins = Map.of(coinId, amount);
        return mintTokenWithMultipleCoins(tokenId, coins);
    }

    private @NotNull PaymentIntegrationTest.MintResult attemptMinting(BigInteger amount, byte[] tokenIdBytes, StateTransitionClient aggregator, CoinId coinId) throws InterruptedException, ExecutionException {
        TokenId tokenId = new TokenId(tokenIdBytes);
        TokenType tokenType = TESTNET_TOKEN_TYPE;

        Map<CoinId, BigInteger> coins = Map.of(coinId, amount);
        TokenCoinData coinData = new TokenCoinData(coins);

        SigningService clientSigningService = SigningService.createFromMaskedSecret(
            CLIENT_SECRET, CLIENT_NONCE
        );
        MaskedPredicate clientPredicate = MaskedPredicate.create(
            tokenId,
            tokenType,
            clientSigningService,
            HashAlgorithm.SHA256,
            CLIENT_NONCE
        );

        DirectAddress clientAddress = clientPredicate.getReference().toAddress();

        MintTransaction.Data<MintTransactionReason> mintData = new MintTransaction.Data<>(
            tokenId,
            tokenType,
            new byte[0], // No custom data
            coinData,
            clientAddress,
            randomBytes(32), // salt
            null, // no data hash
            null  // no reason
        );

        MintCommitment<MintTransactionReason> mintCommitment =
            MintCommitment.create(mintData);

        return new MintResult(clientPredicate, mintCommitment, aggregator.submitCommitment(mintCommitment).get());
    }

    private record MintResult(MaskedPredicate clientPredicate, MintCommitment<MintTransactionReason> mintCommitment, SubmitCommitmentResponse mintResponse) {
    }

    private static byte @NotNull [] randomBytes(int count) {
        byte[] result = new byte[count];
        random.nextBytes(result);
        return result;
    }

    private @NotNull String getRealAggregatorUrl() {
        String aggregatorUrl = System.getenv("AGGREGATOR_URL");
        if (aggregatorUrl == null || aggregatorUrl.isEmpty()) {
            aggregatorUrl = "https://goggregator-test.unicity.network";
        }
        return aggregatorUrl;
    }

    private void assertApiKeyUnauthorizedForMinting(StateTransitionClient proxiedAggregator) {
        ExecutionException e = assertThrows(ExecutionException.class, () ->
                attemptMinting(BigInteger.TEN, randomBytes(32), proxiedAggregator, TESTNET_COIN_ID));
        assertInstanceOf(JsonRpcNetworkException.class, e.getCause());
        assertEquals("Network error [401] occurred: Unauthorized", e.getCause().getMessage());
    }

    private void assertApiKeyAuthorizedForMinting(StateTransitionClient proxiedAggregator) {
        try {
            attemptMinting(BigInteger.TEN, randomBytes(32), proxiedAggregator, TESTNET_COIN_ID);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Order(10)
    @DisplayName("Test pro-rated refund calculation for plan downgrade")
    void testProRatedRefundCalculationForPlanDowngrade() throws Exception {
        String testKey = insertNewPaymentKey(ApiKeyService.generateApiKey(), PLAN_PREMIUM.id());

        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(PLAN_STANDARD.id().intValue(), testKey);
        assertEquals(BigInteger.valueOf(1000), response.getPrice(),
            "Payment should be minimum amount when refund exceeds new price");
    }

    @Test
    @Order(11)
    @DisplayName("Test fixed duration payment for key without an API plan sets expiry to exactly 30 days")
    void testFixedDurationPaymentExpiry() throws Exception {
        String testKey = ApiKeyService.generateApiKey();
        apiKeyRepository.createWithoutPlan(testKey);
        TestDatabaseSetup.markForDeletionDuringReset(testKey);

        long timeBeforePayment = currentTimeMillis(testTimeMeter);

        PaymentModels.InitiatePaymentResponse session = initiatePaymentSession(PLAN_STANDARD.id().intValue(), testKey);
        signAndSubmitPayment(session, randomBytes(32));

        var keyInfo = apiKeyRepository.findByKeyIfNotRevoked(testKey);
        assertTrue(keyInfo.isPresent());
        assertNotNull(keyInfo.get().activeUntil());

        long expiryTime = keyInfo.get().activeUntil().getTime();
        long expectedExpiry = timeBeforePayment + TimeUnit.DAYS.toMillis(ApiKeyUtils.PAYMENT_VALIDITY_DAYS);

        // Allow small tolerance for processing time (within 1 second)
        assertTrue(Math.abs(expiryTime - expectedExpiry) < 1000,
            "Expiry should be exactly " + ApiKeyUtils.PAYMENT_VALIDITY_DAYS + " days from payment time");
    }

    @Test
    @Order(13)
    @DisplayName("Test plan upgrade with refund")
    void testPlanUpgradeWithRefund() throws Exception {
        String testKey = insertNewPaymentKey(ApiKeyService.generateApiKey(), PLAN_STANDARD.id());

        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(PLAN_PREMIUM.id().intValue(), testKey);

        assertEquals(new BigInteger("5001737"), response.getPrice());
    }

    @Test
    @Order(14)
    @DisplayName("Test partial refund calculation with time elapsed")
    void testPartialRefundCalculation() throws Exception {
        String testKey = insertNewPaymentKey(ApiKeyService.generateApiKey(), PLAN_PREMIUM.id());

        // Advance time by 15 days (half the period)
        testTimeMeter.setTime(Instant.ofEpochMilli(currentTimeMillis(testTimeMeter))
            .plus(15, java.time.temporal.ChronoUnit.DAYS)
            .toEpochMilli());

        // Downgrade to standard plan
        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(PLAN_STANDARD.id().intValue(), testKey);

        assertEquals(new BigInteger("3473"), response.getPrice(),
            "Payment should be standard price minus refund");
    }

    @Test
    @Order(15)
    @DisplayName("Test no refund for expired plan")
    void testNoRefundForExpiredPlan() throws Exception {
        String testKey = insertNewPaymentKey(ApiKeyService.generateApiKey(), PLAN_PREMIUM.id());

        // Advance time past expiry (31 days)
        testTimeMeter.setTime(Instant.ofEpochMilli(currentTimeMillis(testTimeMeter))
            .plus(31, java.time.temporal.ChronoUnit.DAYS)
            .toEpochMilli());

        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(PLAN_STANDARD.id().intValue(), testKey);

        assertEquals(PLAN_STANDARD.price(), response.getPrice(),
            "Should pay full price when previous plan expired");
    }

    private String insertNewPaymentKey(String testKey, Long paymentPlanId) {
        Instant keyExpiry = Instant.ofEpochMilli(currentTimeMillis(testTimeMeter))
                .plus(ApiKeyUtils.PAYMENT_VALIDITY_DAYS, java.time.temporal.ChronoUnit.DAYS);
        apiKeyRepository.insert(testKey, paymentPlanId, keyExpiry);
        TestDatabaseSetup.markForDeletionDuringReset(testKey);
        return testKey;
    }

    @Test
    @Order(16)
    @DisplayName("Test payment rejection with wrong coin ID")
    void testPaymentRejectionWithWrongCoinId() throws Exception {
        PaymentModels.InitiatePaymentResponse session = initiatePaymentSessionWithoutKey(PLAN_PREMIUM.id().intValue());

        CoinId wrongCoinId = new CoinId(randomBytes(32));
        Token<?> token = mintTokenWithSpecificCoinId(session.getPrice(), randomBytes(32), wrongCoinId);

        PaymentModels.CompletePaymentResponse response = attemptPaymentCompletion(session, token);

        assertFalse(response.isSuccess());
        assertThat(response.getMessage()).contains("unaccepted coin types");
        assertThat(response.getMessage()).contains(session.getAcceptedCoinId());
    }

    @Test
    @Order(17)
    @DisplayName("Test payment rejection with mixed coin IDs")
    void testPaymentRejectionWithMixedCoinIds() throws Exception {
        PaymentModels.InitiatePaymentResponse session = initiatePaymentSessionWithoutKey(PLAN_PREMIUM.id().intValue());

        CoinId wrongCoinId = new CoinId(randomBytes(32));

        Map<CoinId, BigInteger> mixedCoins = Map.of(
            TESTNET_COIN_ID, session.getPrice(),
            wrongCoinId, session.getPrice()
        );

        Token<?> token = mintTokenWithMultipleCoins(randomBytes(32), mixedCoins);

        PaymentModels.CompletePaymentResponse response = attemptPaymentCompletion(session, token);

        assertFalse(response.isSuccess());
        assertThat(response.getMessage()).contains("unaccepted coin types");
    }

    @Test
    @Order(18)
    @DisplayName("Test payment rejection with overpayment")
    void testPaymentRejectionWithOverpayment() throws Exception {
        PaymentModels.InitiatePaymentResponse session = initiatePaymentSessionWithoutKey(PLAN_PREMIUM.id().intValue());

        BigInteger overpaymentAmount = session.getPrice().add(BigInteger.valueOf(1000));
        Token<?> token = mintInitialToken(overpaymentAmount, randomBytes(32));

        PaymentAttemptResult result = attemptPaymentCompletionWithDetails(session, token);

        PaymentModels.CompletePaymentResponse response = result.response();

        assertFalse(response.isSuccess());
        assertThat(response.getMessage()).contains("Overpayment not accepted");
        assertThat(response.getMessage()).contains("exact amount");

        String requestIdHex = HexConverter.encode(canonicalRequestId(result.transferCommitment().getRequestId().toBitString().toBigInteger()));
        assertRequestIdStoredInDatabase(session.getSessionId(), requestIdHex);
        assertRequestIdNotAggregatedOnBlockchain(result.transferCommitment().getRequestId());
    }

    private record PaymentAttemptResult(PaymentModels.CompletePaymentResponse response, TransferCommitment transferCommitment) {}

    private PaymentModels.CompletePaymentResponse attemptPaymentCompletion(
            PaymentModels.InitiatePaymentResponse session, Token<?> token) throws Exception {
        return attemptPaymentCompletionWithDetails(session, token).response();
    }

    private PaymentAttemptResult attemptPaymentCompletionWithDetails(
            PaymentModels.InitiatePaymentResponse session, Token<?> token) throws Exception {
        var transferData = createTransferCommitment(token, session.getPaymentAddress());

        PaymentModels.CompletePaymentRequest completeRequest = new PaymentModels.CompletePaymentRequest(
            session.getSessionId(),
            Base64.getEncoder().encodeToString(transferData.salt()),
            UnicityObjectMapper.JSON.writeValueAsString(transferData.commitment()),
            UnicityObjectMapper.JSON.writeValueAsString(token)
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/api/payment/complete"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(completeRequest)))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        PaymentModels.CompletePaymentResponse paymentResponse = objectMapper.readValue(response.body(), PaymentModels.CompletePaymentResponse.class);
        return new PaymentAttemptResult(paymentResponse, transferData.commitment());
    }

    private Token<?> mintTokenWithSpecificCoinId(BigInteger amount, byte[] tokenIdBytes, CoinId coinId) throws Exception {
        Map<CoinId, BigInteger> coins = Map.of(coinId, amount);
        return mintTokenWithMultipleCoins(tokenIdBytes, coins);
    }

    private Token<?> mintTokenWithMultipleCoins(byte[] tokenIdBytes, Map<CoinId, BigInteger> coins) throws Exception {
        TokenId tokenId = new TokenId(tokenIdBytes);
        TokenType tokenType = TESTNET_TOKEN_TYPE;
        TokenCoinData coinData = new TokenCoinData(coins);

        SigningService clientSigningService = SigningService.createFromMaskedSecret(CLIENT_SECRET, CLIENT_NONCE);
        MaskedPredicate clientPredicate = MaskedPredicate.create(
            tokenId, tokenType, clientSigningService, HashAlgorithm.SHA256, CLIENT_NONCE
        );

        DirectAddress clientAddress = clientPredicate.getReference().toAddress();

        MintTransaction.Data<MintTransactionReason> mintData = new MintTransaction.Data<>(
            tokenId, tokenType, new byte[0], coinData, clientAddress,
            randomBytes(32), null, null
        );

        MintCommitment<MintTransactionReason> mintCommitment = MintCommitment.create(mintData);

        SubmitCommitmentResponse mintResponse = directToAggregator.submitCommitment(mintCommitment).get(30, TimeUnit.SECONDS);
        if (mintResponse.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to mint token: " + mintResponse.getStatus());
        }

        InclusionProof mintInclusionProof = InclusionProofUtils.waitInclusionProof(
            directToAggregator, trustBase, mintCommitment
        ).get(60, TimeUnit.SECONDS);

        TokenState tokenState = new TokenState(clientPredicate, null);
        return Token.create(trustBase, tokenState, mintCommitment.toTransaction(mintInclusionProof), List.of());
    }

    private void assertRequestIdStoredInDatabase(UUID sessionId, String expectedRequestId) throws Exception {
        try (java.sql.Connection conn = org.unicitylabs.proxy.repository.DatabaseConfig.getConnection()) {
            var session = paymentRepository.findById(conn, sessionId);
            assertTrue(session.isPresent(), "Payment session should exist");
            String storedRequestId = session.get().requestId();
            assertEquals(expectedRequestId, storedRequestId, "Stored requestId should match expected value");
        }
    }

    private void assertRequestIdAggregatedOnBlockchain(TransferCommitment commitment) throws Exception {
        InclusionProofUtils.waitInclusionProof(
                directToAggregator, trustBase, commitment
        ).get(60, TimeUnit.SECONDS);
    }

    private void assertRequestIdNotAggregatedOnBlockchain(RequestId requestId) throws Exception {
        InclusionProofResponse response = aggregatorClient
                .getInclusionProof(requestId).get(10, TimeUnit.SECONDS);

        assertEquals(PATH_NOT_INCLUDED, response.getInclusionProof().verify(requestId, trustBase),
                "RequestId should NOT be aggregated on the blockchain");
    }
}