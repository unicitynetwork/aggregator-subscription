package org.unicitylabs.proxy.service;

import org.unicitylabs.proxy.model.PaymentModels;
import org.unicitylabs.proxy.model.PaymentModels.CreateApiKeyResponse.PricingPlanInfo;
import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.PricingPlanRepository;
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

    public List<PricingPlanInfo> getAvailablePlans() {
        return pricingPlanRepository.findAll().stream()
            .map(plan -> new PricingPlanInfo(
                plan.getId(),
                plan.getName(),
                plan.getRequestsPerSecond(),
                plan.getRequestsPerDay(),
                plan.getPrice()
            ))
            .collect(Collectors.toList());
    }

    public static String generateApiKey() {
        return "sk_" + UUID.randomUUID().toString().replace("-", "");
    }
}