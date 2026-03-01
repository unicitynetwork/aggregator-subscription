package org.unicitylabs.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.unicitylabs.proxy.model.ApiKeyUtils;
import org.unicitylabs.proxy.model.ObjectMapperUtils;
import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.service.ApiKeyService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import org.unicitylabs.sdk.bft.RootTrustBase;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.unicitylabs.proxy.service.PaymentService.canonicalRequestId;
import static org.unicitylabs.sdk.transaction.InclusionProofVerificationStatus.PATH_NOT_INCLUDED;

/**
 * End-to-end tests for payment functionality.
 * Runs against a deployed subscription gateway at AGGREGATOR_URL.
 * No local proxy, no testcontainers — purely admin API calls.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "AGGREGATOR_URL", matches = ".+")
public class PaymentE2eTest {

    private static final SecureRandom random = new SecureRandom();
    private static ObjectMapper objectMapper;
    private static String gatewayUrl;
    private static String aggregatorApiKey;
    private static String adminSessionToken;

    private static final byte[] CLIENT_SECRET = randomBytes(32);
    private static final byte[] CLIENT_NONCE = randomBytes(32);

    private static final CoinId TESTNET_COIN_ID = new CoinId(HexConverter.decode(
        "455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89"));

    private static final TokenType TESTNET_TOKEN_TYPE = new TokenType(HexConverter.decode(
        "f8aa13834268d29355ff12183066f0cb902003629bbc5eb9ef0efbe397867509"));

    private HttpClient httpClient;
    private StateTransitionClient directToAggregator;
    private AggregatorClient aggregatorClient;
    private RootTrustBase trustBase;

    // Track API keys created during each test for cleanup
    private final List<CreatedKey> createdKeys = new ArrayList<>();

    private record CreatedKey(Long id, String apiKey) {}

    @BeforeAll
    static void setUpClass() throws Exception {
        gatewayUrl = System.getenv("AGGREGATOR_URL");
        objectMapper = ObjectMapperUtils.createObjectMapper();
        adminSessionToken = loginToAdmin();
        aggregatorApiKey = obtainAggregatorApiKey();
    }

    private static String loginToAdmin() throws Exception {
        String adminPassword = System.getenv("ADMIN_PASSWORD") != null
                ? System.getenv("ADMIN_PASSWORD") : "admin";

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {

            String loginBody = objectMapper.writeValueAsString(Map.of("password", adminPassword));
            HttpRequest loginRequest = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl + "/admin/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> loginResponse = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
            if (loginResponse.statusCode() != 200) {
                throw new RuntimeException("Admin login failed (HTTP " + loginResponse.statusCode()
                        + "): " + loginResponse.body());
            }

            return objectMapper.readTree(loginResponse.body()).get("token").asText();
        }
    }

    private static String obtainAggregatorApiKey() throws Exception {
        String envKey = System.getenv("AGGREGATOR_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }

        // Auto-generate via admin API
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {

            String createBody = objectMapper.writeValueAsString(Map.of(
                    "description", "e2e-test-key",
                    "pricingPlanId", 1
            ));
            HttpRequest createKeyRequest = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl + "/admin/api/keys"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + adminSessionToken)
                    .POST(HttpRequest.BodyPublishers.ofString(createBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> createKeyResponse = client.send(createKeyRequest, HttpResponse.BodyHandlers.ofString());
            if (createKeyResponse.statusCode() != 201) {
                throw new RuntimeException("API key creation failed (HTTP " + createKeyResponse.statusCode()
                        + "): " + createKeyResponse.body());
            }

            String apiKey = objectMapper.readTree(createKeyResponse.body()).get("apiKey").asText();
            System.out.println("Auto-generated aggregator API key for e2e tests: " + apiKey.substring(0, 6) + "...");
            return apiKey;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        trustBase = UnicityObjectMapper.JSON.readValue(
                getClass().getResourceAsStream("/test-trust-base.json"),
                RootTrustBase.class
        );

        aggregatorClient = new JsonRpcAggregatorClient(gatewayUrl, aggregatorApiKey);
        directToAggregator = new StateTransitionClient(aggregatorClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Revoke all API keys created during this test
        for (CreatedKey key : createdKeys) {
            try {
                adminUpdateKey(key.id(), Map.of("status", "revoked"));
            } catch (Exception e) {
                System.err.println("Failed to revoke test key " + key.id() + ": " + e.getMessage());
            }
        }
        createdKeys.clear();
    }

    // ========== Admin API helpers ==========

    private String adminCreateKey(Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/admin/api/keys"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + adminSessionToken)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), "Create key failed: " + response.body());

        String apiKey = objectMapper.readTree(response.body()).get("apiKey").asText();

        // Look up the key's ID for tracking
        Long keyId = findKeyIdByApiKey(apiKey);
        createdKeys.add(new CreatedKey(keyId, apiKey));
        return apiKey;
    }

    private String adminCreateKeyWithPlan(long pricingPlanId) throws Exception {
        return adminCreateKey(Map.of("pricingPlanId", pricingPlanId));
    }

    private String adminCreateKeyWithoutPlan() throws Exception {
        return adminCreateKey(Map.of());
    }

    private void adminUpdateKey(Long keyId, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/admin/api/keys/" + keyId))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + adminSessionToken)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Update key failed: " + response.body());
    }

    private Long findKeyIdByApiKey(String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/admin/api/keys"))
                .header("Authorization", "Bearer " + adminSessionToken)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode keys = objectMapper.readTree(response.body());
        for (JsonNode key : keys) {
            if (apiKey.equals(key.get("apiKey").asText())) {
                return key.get("id").asLong();
            }
        }
        throw new RuntimeException("API key not found in admin listing: " + apiKey);
    }

    private JsonNode getKeyDetails(String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/api/payment/key/" + apiKey))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private JsonNode adminSearchPaymentSessions(String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/admin/api/payment-sessions/search?sessionId=" + sessionId))
                .header("Authorization", "Bearer " + adminSessionToken)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body());
    }

    // ========== Tests ==========

    @Test
    @Order(1)
    @DisplayName("Test complete payment flow with new API key creation")
    void testCompletePaymentWithNewKeyCreation() throws Exception {
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSessionWithoutKey(4); // enterprise

        var paymentResult = signAndSubmitPaymentWithDetails(paymentSession, tokenId);
        var paymentResponse = paymentResult.response();
        String createdApiKey = paymentResponse.getApiKey();
        assertTrue(createdApiKey.startsWith("sk_"), "API key should have correct format");

        // Track the created key for cleanup
        Long keyId = findKeyIdByApiKey(createdApiKey);
        createdKeys.add(new CreatedKey(keyId, createdApiKey));

        String requestIdHex = canonicalRequestId(paymentResult.transferCommitment().getRequestId());

        assertRequestIdStoredViaAdminApi(paymentSession.getSessionId(), requestIdHex);
        assertRequestIdAggregatedOnBlockchain(paymentResult.transferCommitment());

        // Test that the new API key works
        final StateTransitionClient proxyConnectionWithApiKey = new StateTransitionClient(
            new JsonRpcAggregatorClient(gatewayUrl, createdApiKey));
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);
    }

    @Test
    @Order(2)
    @DisplayName("Test payment flow with existing API key")
    void testPaymentWithExistingKey() throws Exception {
        String testKey = adminCreateKeyWithPlan(1); // basic plan
        Long keyId = createdKeys.get(createdKeys.size() - 1).id();

        // Pay for enterprise plan
        initiatePaymentSession(4, testKey);

        // Test that the API key is now authorized
        final StateTransitionClient proxyConnectionWithApiKey = new StateTransitionClient(
            new JsonRpcAggregatorClient(gatewayUrl, testKey));
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);

        // Backdate the key's activeUntil to the past to simulate expiry
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.MINUTES);
        adminUpdateKey(keyId, Map.of("activeUntil", pastExpiry.toString()));

        assertApiKeyUnauthorizedForMinting(proxyConnectionWithApiKey);

        // Renew with another payment
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSession(4, testKey);
        signAndSubmitPayment(paymentSession, tokenId);
        assertApiKeyAuthorizedForMinting(proxyConnectionWithApiKey);
    }

    @Test
    @Order(3)
    @DisplayName("Test invalid API key rejection")
    void testInvalidApiKeyRejection() throws Exception {
        PaymentModels.InitiatePaymentRequest request =
            new PaymentModels.InitiatePaymentRequest("invalid-key", 3); // premium

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(gatewayUrl + "/api/payment/initiate"))
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
        String testKey = adminCreateKeyWithPlan(1); // basic plan

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/api/payment/key/" + testKey))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("active", body.get("status").asText());
        assertNotNull(body.get("expiresAt").asText());
        assertFalse(body.get("expiresAt").isNull());
        assertEquals("basic", body.get("pricingPlan").get("name").asText());
        assertEquals(1, body.get("pricingPlan").get("id").asInt());
    }

    @Test
    @Order(5)
    @DisplayName("Test API key details endpoint, non-existent key")
    void testApiKeyDetailsEndpoint_nonExistentKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/api/payment/key/non-existent-key"))
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
        String keyWithoutPlan = adminCreateKeyWithoutPlan();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/api/payment/key/" + keyWithoutPlan))
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
        String revokedKey = adminCreateKeyWithPlan(1);
        Long keyId = createdKeys.get(createdKeys.size() - 1).id();

        adminUpdateKey(keyId, Map.of("status", "revoked"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/api/payment/key/" + revokedKey))
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
        String testKey = adminCreateKeyWithPlan(1); // basic

        // Initiate first payment session for plan 2 (standard)
        byte[] tokenId1 = randomBytes(32);
        PaymentModels.InitiatePaymentResponse session1 = initiatePaymentSession(2, testKey);
        assertNotNull(session1.getSessionId());
        UUID firstSessionId = session1.getSessionId();

        // Initiate second payment session for plan 3 (premium) — should cancel the first
        byte[] tokenId2 = randomBytes(32);
        PaymentModels.InitiatePaymentResponse session2 = initiatePaymentSession(3, testKey);
        assertNotNull(session2.getSessionId());
        assertNotEquals(firstSessionId, session2.getSessionId(), "Should create a new session");

        // Verify first session is cancelled by trying to complete it
        var token = mintInitialToken(session1.getPrice(), tokenId1);
        var transferData = createTransferCommitment(token, session1.getPaymentAddress());

        // Submit the transfer and wait for inclusion proof
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
            .uri(URI.create(gatewayUrl + "/api/payment/complete"))
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
            .uri(URI.create(gatewayUrl + "/api/payment/plans"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode body = objectMapper.readTree(response.body());
        JsonNode plans = body.get("availablePlans");
        assertNotNull(plans, "Response should have availablePlans");
        assertTrue(plans.isArray(), "availablePlans should be an array");
        assertTrue(plans.size() >= 4, "Should have at least 4 plans (basic, standard, premium, enterprise)");

        // Verify basic plan fields exist
        JsonNode firstPlan = plans.get(0);
        assertNotNull(firstPlan.get("planId"));
        assertNotNull(firstPlan.get("name"));
        assertNotNull(firstPlan.get("requestsPerSecond"));
        assertNotNull(firstPlan.get("requestsPerDay"));
        assertNotNull(firstPlan.get("price"));
    }

    @Test
    @Order(10)
    @DisplayName("Test pro-rated refund calculation for plan downgrade")
    void testProRatedRefundCalculationForPlanDowngrade() throws Exception {
        String testKey = adminCreateKeyWithPlan(3); // premium
        Long keyId = createdKeys.get(createdKeys.size() - 1).id();

        // Pay for premium to set activeUntil
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse premiumSession = initiatePaymentSession(3, testKey);
        signAndSubmitPayment(premiumSession, tokenId);

        // Downgrade to standard
        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(2, testKey);
        assertEquals(BigInteger.valueOf(1000), response.getPrice(),
            "Payment should be minimum amount when refund exceeds new price");
    }

    @Test
    @Order(11)
    @DisplayName("Test fixed duration payment for key without an API plan sets expiry")
    void testFixedDurationPaymentExpiry() throws Exception {
        String testKey = adminCreateKeyWithoutPlan();

        Instant timeBeforePayment = Instant.now();

        PaymentModels.InitiatePaymentResponse session = initiatePaymentSession(2, testKey);
        signAndSubmitPayment(session, randomBytes(32));

        // Check expiry via public API
        JsonNode keyDetails = getKeyDetails(testKey);
        assertNotNull(keyDetails.get("expiresAt"));
        assertFalse(keyDetails.get("expiresAt").isNull(), "expiresAt should be set after payment");

        Instant expiresAt = Instant.parse(keyDetails.get("expiresAt").asText());
        Instant expectedExpiry = timeBeforePayment.plus(ApiKeyUtils.PAYMENT_VALIDITY_DAYS, ChronoUnit.DAYS);

        // Allow tolerance for processing time (within 30 seconds)
        assertTrue(Math.abs(Duration.between(expiresAt, expectedExpiry).toSeconds()) < 30,
            "Expiry should be approximately " + ApiKeyUtils.PAYMENT_VALIDITY_DAYS + " days from payment time");
    }

    @Test
    @Order(13)
    @DisplayName("Test plan upgrade with refund")
    void testPlanUpgradeWithRefund() throws Exception {
        String testKey = adminCreateKeyWithPlan(2); // standard

        // Pay for standard to set activeUntil
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse standardSession = initiatePaymentSession(2, testKey);
        signAndSubmitPayment(standardSession, tokenId);

        // Upgrade to premium
        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(3, testKey);

        // Price should be premium price minus refund for remaining standard time
        // Exact amount depends on timing, so just verify it's less than full premium price
        BigInteger fullPremiumPrice = new BigInteger("10000000");
        assertTrue(response.getPrice().compareTo(fullPremiumPrice) < 0,
            "Upgrade price should be less than full premium price due to refund");
        assertTrue(response.getPrice().compareTo(BigInteger.ZERO) > 0,
            "Upgrade price should be positive");
    }

    @Test
    @Order(14)
    @DisplayName("Test partial refund calculation with time elapsed")
    void testPartialRefundCalculation() throws Exception {
        String testKey = adminCreateKeyWithPlan(3); // premium
        Long keyId = createdKeys.get(createdKeys.size() - 1).id();

        // Pay for premium to set activeUntil
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse premiumSession = initiatePaymentSession(3, testKey);
        signAndSubmitPayment(premiumSession, tokenId);

        // Backdate activeUntil so 15 days have elapsed (15 days remaining)
        Instant activeUntilBackdated = Instant.now().plus(15, ChronoUnit.DAYS);
        adminUpdateKey(keyId, Map.of("activeUntil", activeUntilBackdated.toString()));

        // Downgrade to standard plan
        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(2, testKey);

        // Should get partial refund — remaining premium value minus standard price
        // Exact amount depends on timing, just verify it's less than standard price
        BigInteger fullStandardPrice = new BigInteger("5000000");
        assertTrue(response.getPrice().compareTo(fullStandardPrice) < 0,
            "Payment should be less than standard price due to partial refund");
    }

    @Test
    @Order(15)
    @DisplayName("Test no refund for expired plan")
    void testNoRefundForExpiredPlan() throws Exception {
        String testKey = adminCreateKeyWithPlan(3); // premium
        Long keyId = createdKeys.get(createdKeys.size() - 1).id();

        // Pay for premium to set activeUntil
        byte[] tokenId = randomBytes(32);
        PaymentModels.InitiatePaymentResponse premiumSession = initiatePaymentSession(3, testKey);
        signAndSubmitPayment(premiumSession, tokenId);

        // Backdate activeUntil to the past (plan is expired)
        Instant expiredActiveUntil = Instant.now().minus(1, ChronoUnit.DAYS);
        adminUpdateKey(keyId, Map.of("activeUntil", expiredActiveUntil.toString()));

        // Switch to standard plan
        PaymentModels.InitiatePaymentResponse response = initiatePaymentSession(2, testKey);

        BigInteger fullStandardPrice = new BigInteger("5000000");
        assertEquals(fullStandardPrice, response.getPrice(),
            "Should pay full price when previous plan expired");
    }

    @Test
    @Order(16)
    @DisplayName("Test payment rejection with wrong coin ID")
    void testPaymentRejectionWithWrongCoinId() throws Exception {
        PaymentModels.InitiatePaymentResponse session = initiatePaymentSessionWithoutKey(3); // premium

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
        PaymentModels.InitiatePaymentResponse session = initiatePaymentSessionWithoutKey(3); // premium

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
        PaymentModels.InitiatePaymentResponse session = initiatePaymentSessionWithoutKey(3); // premium

        BigInteger overpaymentAmount = session.getPrice().add(BigInteger.valueOf(1000));
        Token<?> token = mintInitialToken(overpaymentAmount, randomBytes(32));

        PaymentAttemptResult result = attemptPaymentCompletionWithDetails(session, token);

        PaymentModels.CompletePaymentResponse response = result.response();

        assertFalse(response.isSuccess());
        assertThat(response.getMessage()).contains("Overpayment not accepted");
        assertThat(response.getMessage()).contains("exact amount");

        String requestIdHex = canonicalRequestId(result.transferCommitment().getRequestId());
        assertRequestIdStoredViaAdminApi(session.getSessionId(), requestIdHex);
        assertRequestIdNotAggregatedOnBlockchain(result.transferCommitment().getRequestId());
    }

    // ========== Payment helpers ==========

    private record PaymentResult(PaymentModels.CompletePaymentResponse response, TransferCommitment transferCommitment) {}
    private record PaymentAttemptResult(PaymentModels.CompletePaymentResponse response, TransferCommitment transferCommitment) {}

    private PaymentModels.CompletePaymentResponse signAndSubmitPayment(PaymentModels.InitiatePaymentResponse paymentSession, byte[] tokenId) throws Exception {
        return signAndSubmitPaymentWithDetails(paymentSession, tokenId).response();
    }

    private PaymentResult signAndSubmitPaymentWithDetails(PaymentModels.InitiatePaymentResponse paymentSession, byte[] tokenId) throws Exception {
        var token = mintInitialToken(paymentSession.getPrice(), tokenId);

        var transferData = createTransferCommitment(token, paymentSession.getPaymentAddress());

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
                null,
                null,
                clientSigningService
        );

        return new TransferData(transferCommitment, salt);
    }

    private PaymentModels.InitiatePaymentResponse initiatePaymentSessionWithoutKey(int targetPlanId)
            throws IOException, InterruptedException {
        PaymentModels.InitiatePaymentRequest request = new PaymentModels.InitiatePaymentRequest(
                null, targetPlanId);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/api/payment/initiate"))
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
                .uri(URI.create(gatewayUrl + "/api/payment/initiate"))
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
                .uri(URI.create(gatewayUrl + "/api/payment/complete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

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
            .uri(URI.create(gatewayUrl + "/api/payment/complete"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(completeRequest)))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        PaymentModels.CompletePaymentResponse paymentResponse = objectMapper.readValue(response.body(), PaymentModels.CompletePaymentResponse.class);
        return new PaymentAttemptResult(paymentResponse, transferData.commitment());
    }

    // ========== Minting helpers ==========

    private Token<?> mintInitialToken(BigInteger amount, byte[] tokenId) throws Exception {
        return mintInitialToken(amount, tokenId, TESTNET_COIN_ID);
    }

    private Token<?> mintInitialToken(BigInteger amount, byte[] tokenId, CoinId coinId) throws Exception {
        Map<CoinId, BigInteger> coins = Map.of(coinId, amount);
        return mintTokenWithMultipleCoins(tokenId, coins);
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

    private @NotNull MintResult attemptMinting(BigInteger amount, byte[] tokenIdBytes, StateTransitionClient aggregator, CoinId coinId) throws InterruptedException, ExecutionException {
        TokenId tokenId = new TokenId(tokenIdBytes);
        TokenType tokenType = TESTNET_TOKEN_TYPE;

        Map<CoinId, BigInteger> coins = Map.of(coinId, amount);
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

        return new MintResult(clientPredicate, mintCommitment, aggregator.submitCommitment(mintCommitment).get());
    }

    private record MintResult(MaskedPredicate clientPredicate, MintCommitment<MintTransactionReason> mintCommitment, SubmitCommitmentResponse mintResponse) {}

    // ========== Assertion helpers ==========

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

    private void assertRequestIdStoredViaAdminApi(UUID sessionId, String expectedRequestId) throws Exception {
        JsonNode searchResult = adminSearchPaymentSessions(sessionId.toString());
        JsonNode sessions = searchResult.get("sessions");
        assertNotNull(sessions, "Search result should contain sessions");
        assertTrue(sessions.size() > 0, "Should find at least one session");

        String storedRequestId = sessions.get(0).get("requestId").asText();
        assertEquals(expectedRequestId, storedRequestId, "Stored requestId should match expected value");
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

    private static byte @NotNull [] randomBytes(int count) {
        byte[] result = new byte[count];
        random.nextBytes(result);
        return result;
    }
}
