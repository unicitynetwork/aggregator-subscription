package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.model.ApiKeyStatus;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.AddressFactory;
import org.unicitylabs.sdk.address.DirectAddress;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.jsonrpc.JsonRpcNetworkError;
import org.unicitylabs.sdk.predicate.embedded.MaskedPredicate;
import org.unicitylabs.sdk.serializer.UnicityObjectMapper;
import org.unicitylabs.sdk.signing.SigningService;
import org.unicitylabs.sdk.token.Token;
import org.unicitylabs.sdk.token.TokenId;
import org.unicitylabs.sdk.token.TokenState;
import org.unicitylabs.sdk.token.TokenType;
import org.unicitylabs.sdk.token.fungible.CoinId;
import org.unicitylabs.sdk.token.fungible.TokenCoinData;
import org.unicitylabs.sdk.transaction.InclusionProof;
import org.unicitylabs.sdk.transaction.MintCommitment;
import org.unicitylabs.sdk.transaction.MintTransactionData;
import org.unicitylabs.sdk.transaction.MintTransactionReason;
import org.unicitylabs.sdk.transaction.TransferCommitment;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.unicitylabs.proxy.model.PaymentSessionStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaymentIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_API_KEY = "test-payment-key";
    private static final SecureRandom random = new SecureRandom();
    private static ObjectMapper objectMapper;

    private StateTransitionClient directToAggregator;

    private static final byte[] CLIENT_SECRET = randomBytes(32);
    private static final byte[] CLIENT_NONCE = randomBytes(32);
    private static final byte[] MOCK_TOKEN_TYPE = {3, 14, 15}; // Test token type
    private ApiKeyRepository apiKeyRepository;

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
    void setUpTestApiKey() {
        apiKeyRepository = new ApiKeyRepository();
        apiKeyRepository.insert(TEST_API_KEY, 1);
        TestDatabaseSetup.markForDeletionDuringReset(TEST_API_KEY);

        String realAggregatorUrl = getRealAggregatorUrl();
        directToAggregator = new StateTransitionClient(new AggregatorClient(realAggregatorUrl));
    }

    @Test
    @Order(1)
    @DisplayName("Test creating an API key and the complete payment flow")
    void testCompletePaymentWithPolling() throws Exception {
        PaymentModels.CreateApiKeyResponse createResponse = createApiKeyWithoutPlan();
        final StateTransitionClient proxyConnectionWithApiKey = new StateTransitionClient(new AggregatorClient(getProxyUrl(), createResponse.getApiKey()));
        assertApiKeyUnauthorizedForMinting(proxyConnectionWithApiKey);

        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSession(3, createResponse.getApiKey(), tokenId);
        assertApiKeyUnauthorizedForMinting(proxyConnectionWithApiKey);

        signAndSubmitPayment(paymentSession, tokenId);

        pollUntilPaymentSucceeded(paymentSession.getSessionId(), createResponse.getApiKey(), 3);
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);

        // Make the key expire, then pay for it again
        // Adding extra time here to account for the test running time; this means that the test can mistakenly fail if it runs very long.
        testTimeMeter.setTime(Instant.ofEpochMilli(testTimeMeter.getTime()).atZone(ZoneId.systemDefault()).plusDays(ApiKeyRepository.getPaymentValidityDurationDays()).plusMinutes(1).toInstant().toEpochMilli());

        assertApiKeyUnauthorizedForMinting(proxyConnectionWithApiKey);

        tokenId = randomBytes(32); // New token for new payment
        paymentSession = initiatePaymentSession(3, createResponse.getApiKey(), tokenId);
        assertApiKeyUnauthorizedForMinting(proxyConnectionWithApiKey);

        signAndSubmitPayment(paymentSession, tokenId);

        pollUntilPaymentSucceeded(paymentSession.getSessionId(), createResponse.getApiKey(), 3);
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);
    }

    @Test
    @Order(2)
    @DisplayName("Test payment status check: pending")
    void testCheckPaymentStatus() throws Exception {
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSession(4, TEST_API_KEY, tokenId);

        PaymentModels.PaymentStatusResponse status = getPaymentStatusResponse(paymentSession.getSessionId());

        assertEquals(paymentSession.getSessionId(), status.getSessionId());
        assertEquals(PENDING.getValue(), status.getStatus());
        assertEquals(50_000_000L, status.getAmountRequired().longValueExact());
        assertNotNull(status.getCreatedAt());
        assertNull(status.getCompletedAt());
        assertNotNull(status.getExpiresAt());
    }

    @Test
    @Order(3)
    @DisplayName("Test invalid API key rejection")
    void testInvalidApiKeyRejection() throws Exception {
        PaymentModels.InitiatePaymentRequest request =
            new PaymentModels.InitiatePaymentRequest(
                    "invalid-key",
                    3,
                    Base64.getEncoder().encodeToString(randomBytes(32)),
                    Base64.getEncoder().encodeToString(MOCK_TOKEN_TYPE));

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
    void testApiKeyDetailsEndpoint_keyWithAPaymetPlan() throws Exception {
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
        String revokedKey = "test-revoked-key";
        apiKeyRepository.insert(revokedKey, 1);
        TestDatabaseSetup.markForDeletionDuringReset(revokedKey);
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
    @DisplayName("Test invalid session ID handling")
    void testInvalidSessionId() throws Exception {
        UUID fakeSessionId = UUID.randomUUID();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(getProxyUrl() + "/api/payment/status/" + fakeSessionId))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());

        PaymentModels.ErrorResponse error =
            objectMapper.readValue(response.body(), PaymentModels.ErrorResponse.class);

        assertEquals("Not Found", error.getError());
        assertThat(error.getMessage()).contains("Payment session not found");
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

        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(objectMapper.readTree(response.body()));

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
                    "price" : "1"
                  }, {
                    "planId" : 6,
                    "name" : "test-standard",
                    "requestsPerSecond" : 10,
                    "requestsPerDay" : 100000,
                    "price" : "2"
                  }, {
                    "planId" : 7,
                    "name" : "test-premium",
                    "requestsPerSecond" : 20,
                    "requestsPerDay" : 500000,
                    "price" : "3"
                  } ]
                }""",
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(objectMapper.readTree(response.body())));
    }

    private void signAndSubmitPayment(PaymentModels.InitiatePaymentResponse paymentSession, byte[] tokenId) throws Exception {
        var token = mintInitialToken(paymentSession.getAmountRequired(), tokenId);

        SigningService clientSigningService = SigningService.createFromMaskedSecret( CLIENT_SECRET, CLIENT_NONCE );

        DirectAddress paymentAddress = (DirectAddress) AddressFactory.createAddress(paymentSession.getPaymentAddress());
        byte[] salt = randomBytes(32);
        TransferCommitment transferCommitment = TransferCommitment.create(
                token,
                paymentAddress,
                salt,
                null, // no data hash
                null, // no message
                clientSigningService
        );

        String transferCommitmentJson = UnicityObjectMapper.JSON.writeValueAsString(transferCommitment);
        String sourceTokenJson = UnicityObjectMapper.JSON.writeValueAsString(token);

        submitRequest(new PaymentModels.CompletePaymentRequest(
                paymentSession.getSessionId(),
                Base64.getEncoder().encodeToString(salt),
                transferCommitmentJson,
                sourceTokenJson
        ));
    }

    private PaymentModels.CreateApiKeyResponse createApiKeyWithoutPlan() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/create-key"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        PaymentModels.CreateApiKeyResponse result = objectMapper.readValue(response.body(), PaymentModels.CreateApiKeyResponse.class);

        assertNotNull(result.getApiKey());
        assertFalse(result.getAvailablePlans().isEmpty());

        return result;
    }

    private PaymentModels.InitiatePaymentResponse initiatePaymentSession(int targetPlanId, String apiKey, byte[] tokenId) throws IOException, InterruptedException {
        String tokenIdBase64 = Base64.getEncoder().encodeToString(tokenId);
        String tokenTypeBase64 = Base64.getEncoder().encodeToString(MOCK_TOKEN_TYPE);
        PaymentModels.InitiatePaymentRequest request = new PaymentModels.InitiatePaymentRequest(apiKey, targetPlanId, tokenIdBase64, tokenTypeBase64);
        String initPaymentResponseBody = submitRequest(request);
        PaymentModels.InitiatePaymentResponse paymentSession =
                objectMapper.readValue(initPaymentResponseBody, PaymentModels.InitiatePaymentResponse.class);
        return paymentSession;
    }

    private void pollUntilPaymentSucceeded(UUID sessionId, String apiKey, int targetPlanId) {
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    PaymentModels.PaymentStatusResponse status = getPaymentStatusResponse(sessionId);

                    assertThat(status.getStatus()).isEqualTo("completed");

                    ApiKeyRepository.ApiKeyInfo apiKeyInfo = apiKeyRepository.findByKeyIfNotRevokedAndHasPaid(apiKey).get();
                    assertThat(apiKeyInfo.pricingPlanId()).isEqualTo(targetPlanId);
                });
    }

    private PaymentModels.PaymentStatusResponse getPaymentStatusResponse(UUID sessionId) throws IOException, InterruptedException {
        HttpRequest statusRequest = HttpRequest.newBuilder()
                .uri(URI.create(getProxyUrl() + "/api/payment/status/" + sessionId))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusResponse.statusCode());

        return objectMapper.readValue(statusResponse.body(), PaymentModels.PaymentStatusResponse.class);
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

    private Token mintInitialToken(BigInteger amount, byte[] tokenId) throws Exception {
        MintResult result = attemptMinting(amount, tokenId, directToAggregator);

        System.out.println("Mint response status: " + result.mintResponse().getStatus());
        if (result.mintResponse().getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to mint initial token: " + result.mintResponse().getStatus());
        }

        System.out.println("Waiting for inclusion proof for mint commitment: " + result.mintCommitment().getRequestId());
        InclusionProof mintInclusionProof;
        try {
            mintInclusionProof = InclusionProofUtils.waitInclusionProof(
                    directToAggregator,
                    trustBase,
                    result.mintCommitment()
            ).get(60, TimeUnit.SECONDS);
            System.out.println("Got inclusion proof: " + mintInclusionProof);
        } catch (Exception e) {
            System.err.println("Failed to get inclusion proof: " + e.getMessage());
            throw e;
        }

        TokenState tokenState = new TokenState(result.clientPredicate(), null);

        return new Token<>(
            tokenState,
            result.mintCommitment().toTransaction(mintInclusionProof),
            List.of(),
            List.of()
        );
    }

    private @NotNull PaymentIntegrationTest.MintResult attemptMinting(BigInteger amount, byte[] tokenIdBytes, StateTransitionClient aggregator) throws InterruptedException, ExecutionException {
        TokenId tokenId = new TokenId(tokenIdBytes);
        TokenType tokenType = new TokenType(MOCK_TOKEN_TYPE);

        CoinId coinId = new CoinId(randomBytes(32));
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

        MintTransactionData<MintTransactionReason> mintData = new MintTransactionData<>(
            tokenId,
            tokenType,
            new byte[0], // No custom data
            coinData,
            clientAddress,
            randomBytes(32), // salt
            null, // no data hash
            null  // no reason
        );

        MintCommitment<MintTransactionData<MintTransactionReason>> mintCommitment =
            MintCommitment.create(mintData);

        return new MintResult(clientPredicate, mintCommitment, aggregator.submitCommitment(mintCommitment).get());
    }

    private record MintResult(MaskedPredicate clientPredicate, MintCommitment<MintTransactionData<MintTransactionReason>> mintCommitment, SubmitCommitmentResponse mintResponse) {
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
        ExecutionException e = assertThrows(ExecutionException.class, () -> {
            attemptMinting(BigInteger.TEN, randomBytes(32), proxiedAggregator);
        });
        assertInstanceOf(JsonRpcNetworkError.class, e.getCause());
        assertEquals("Network error [401] occurred: Unauthorized", e.getCause().getMessage());
    }

    private void assertApiKeyAuthorizedForMinting(StateTransitionClient proxiedAggregator) {
        try {
            attemptMinting(BigInteger.TEN, randomBytes(32), proxiedAggregator);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}