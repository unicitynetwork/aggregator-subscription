package com.unicity.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicity.proxy.model.PaymentModels;
import com.unicity.proxy.repository.ApiKeyRepository;
import com.unicity.proxy.service.PaymentService;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.address.AddressFactory;
import org.unicitylabs.sdk.address.DirectAddress;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.SubmitCommitmentResponse;
import org.unicitylabs.sdk.api.SubmitCommitmentStatus;
import org.unicitylabs.sdk.hash.HashAlgorithm;
import org.unicitylabs.sdk.predicate.MaskedPredicate;
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
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.unicity.proxy.model.PaymentSessionStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PaymentIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_API_KEY = "test-payment-key";
    private static final SecureRandom random = new SecureRandom();
    private static ObjectMapper objectMapper;

    private StateTransitionClient directAggregatorClient;

    private static final byte[] CLIENT_SECRET = randomBytes(32);
    private static final byte[] CLIENT_NONCE = randomBytes(32);
    private ApiKeyRepository apiKeyRepository;

    @BeforeAll
    static void setupObjectMapper() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
    }

    @Override
    protected void updateConfigForTests(ProxyConfig config) {
        super.updateConfigForTests(config);

        // Using a real aggregator here because our mock server is not capable of aggregating
        // transactions
        config.setPort(443);
        config.setTargetUrl(getAggregatorUrl());
    }

    @BeforeEach
    void setupTestApiKey() throws Exception {
        apiKeyRepository = new ApiKeyRepository();
        apiKeyRepository.save(TEST_API_KEY, 1);

        String aggregatorUrl = getAggregatorUrl();
        directAggregatorClient = new StateTransitionClient(new AggregatorClient(aggregatorUrl));
    }

    @Test
    @Order(1)
    @DisplayName("Test complete payment flow and polling")
    void testCompletePaymentWithPolling() throws Exception {
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSession(3);

        signAndSubmitPayment(paymentSession);

        pollUntilPaymentSucceeded(paymentSession.getSessionId());
    }

    @Test
    @Order(2)
    @DisplayName("Test payment status check: pending")
    void testCheckPaymentStatus() throws Exception {
        PaymentModels.InitiatePaymentResponse paymentSession = initiatePaymentSession(4);

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
            new PaymentModels.InitiatePaymentRequest("invalid-key", 3);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/api/payment/initiate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
            .timeout(Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());

        PaymentModels.ErrorResponse error =
            objectMapper.readValue(response.body(), PaymentModels.ErrorResponse.class);

        assertEquals("Bad Request", error.getError());
        assertThat(error.getMessage()).contains("Invalid or inactive API key");
    }

    @Test
    @Order(4)
    @DisplayName("Test invalid session ID handling")
    void testInvalidSessionId() throws Exception {
        UUID fakeSessionId = UUID.randomUUID();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/api/payment/status/" + fakeSessionId))
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

    private void signAndSubmitPayment(PaymentModels.InitiatePaymentResponse paymentSession) throws Exception {
        var token = mintInitialToken(paymentSession.getAmountRequired());

        SigningService clientSigningService = SigningService.createFromSecret( CLIENT_SECRET, CLIENT_NONCE );

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

    private PaymentModels.InitiatePaymentResponse initiatePaymentSession(int targetPlanId) throws IOException, InterruptedException {
        String initPaymentResponseBody = submitRequest(new PaymentModels.InitiatePaymentRequest(TEST_API_KEY, targetPlanId));
        PaymentModels.InitiatePaymentResponse paymentSession =
                objectMapper.readValue(initPaymentResponseBody, PaymentModels.InitiatePaymentResponse.class);
        return paymentSession;
    }

    private void pollUntilPaymentSucceeded(UUID sessionId) {
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    PaymentModels.PaymentStatusResponse status = getPaymentStatusResponse(sessionId);

                    assertThat(status.getStatus()).isEqualTo("completed");

                    ApiKeyRepository.ApiKeyInfo apiKeyInfo = apiKeyRepository.findByKeyIfActive(TEST_API_KEY).get();
                    assertThat(apiKeyInfo.pricingPlanId()).isEqualTo(3);
                });
    }

    private PaymentModels.PaymentStatusResponse getPaymentStatusResponse(UUID sessionId) throws IOException, InterruptedException {
        HttpRequest statusRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxyPort + "/api/payment/status/" + sessionId))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> statusResponse = httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusResponse.statusCode());

        return objectMapper.readValue(statusResponse.body(), PaymentModels.PaymentStatusResponse.class);
    }

    private String submitRequest(PaymentModels.InitiatePaymentRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxyPort + "/api/payment/initiate"))
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
                .uri(URI.create("http://localhost:" + proxyPort + "/api/payment/complete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private Token mintInitialToken(BigInteger amount) throws Exception {
        TokenId tokenId = new TokenId(randomBytes(32));
        TokenType tokenType = new TokenType(PaymentService.MOCK_TOKEN_TYPE);

        CoinId coinId = new CoinId(randomBytes(32));
        Map<CoinId, BigInteger> coins = Map.of(coinId, amount);
        TokenCoinData coinData = new TokenCoinData(coins);

        SigningService clientSigningService = SigningService.createFromSecret(
            CLIENT_SECRET, CLIENT_NONCE
        );
        MaskedPredicate clientPredicate = MaskedPredicate.create(
            clientSigningService,
            HashAlgorithm.SHA256,
            CLIENT_NONCE
        );

        DirectAddress clientAddress = clientPredicate.getReference(tokenType).toAddress();

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

        SubmitCommitmentResponse mintResponse = directAggregatorClient
            .submitCommitment(mintCommitment)
            .get();

        System.out.println("Mint response status: " + mintResponse.getStatus());
        if (mintResponse.getStatus() != SubmitCommitmentStatus.SUCCESS) {
            throw new Exception("Failed to mint initial token: " + mintResponse.getStatus());
        }

        System.out.println("Waiting for inclusion proof for mint commitment: " + mintCommitment.getRequestId());
        InclusionProof mintInclusionProof;
        try {
            mintInclusionProof = InclusionProofUtils.waitInclusionProof(
                directAggregatorClient,
                mintCommitment
            ).get(60, TimeUnit.SECONDS);
            System.out.println("Got inclusion proof: " + mintInclusionProof);
        } catch (Exception e) {
            System.err.println("Failed to get inclusion proof: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }

        TokenState tokenState = new TokenState(clientPredicate, null);

        return new Token<>(
            tokenState,
            mintCommitment.toTransaction(mintInclusionProof)
        );
    }

    private static byte @NotNull [] randomBytes(int count) {
        byte[] result = new byte[count];
        random.nextBytes(result);
        return result;
    }

    private @NotNull String getAggregatorUrl() {
        String aggregatorUrl = System.getenv("AGGREGATOR_URL");
        if (aggregatorUrl == null || aggregatorUrl.isEmpty()) {
            aggregatorUrl = "https://goggregator-test.unicity.network";
        }
        return aggregatorUrl;
    }
}