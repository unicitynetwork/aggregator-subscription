package com.unicity.proxy.service;

import com.unicity.proxy.model.PaymentModels;
import com.unicity.proxy.model.PaymentModels.CreateApiKeyResponse.PricingPlanInfo;
import com.unicity.proxy.repository.ApiKeyRepository;
import com.unicity.proxy.repository.PricingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ApiKeyService {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository apiKeyRepository;
    private final PricingPlanRepository pricingPlanRepository;

    public ApiKeyService() {
        this.apiKeyRepository = new ApiKeyRepository();
        this.pricingPlanRepository = new PricingPlanRepository();
    }

    public PaymentModels.CreateApiKeyResponse createApiKeyWithoutPricingPlan() {
        String apiKey = generateApiKey();

        apiKeyRepository.createWithoutPlan(apiKey);
        logger.info("Created new API key without plan: {}", apiKey);

        List<PricingPlanInfo> availablePlans = pricingPlanRepository.findAll().stream()
            .map(plan -> new PricingPlanInfo(
                plan.getId(),
                plan.getName(),
                plan.getRequestsPerSecond(),
                plan.getRequestsPerDay(),
                plan.getPrice()
            ))
            .collect(Collectors.toList());

        return new PaymentModels.CreateApiKeyResponse(
            apiKey,
            "API key created successfully. Please purchase a plan to activate it.",
            availablePlans
        );
    }

    public static String generateApiKey() {
        return "sk_" + UUID.randomUUID().toString().replace("-", "");
    }
}