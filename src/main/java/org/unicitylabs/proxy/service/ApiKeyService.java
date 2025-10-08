package org.unicitylabs.proxy.service;

import org.unicitylabs.proxy.model.PaymentModels.CreateApiKeyResponse.PricingPlanInfo;
import org.unicitylabs.proxy.repository.PricingPlanRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ApiKeyService {
    private final PricingPlanRepository pricingPlanRepository;

    public ApiKeyService() {
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