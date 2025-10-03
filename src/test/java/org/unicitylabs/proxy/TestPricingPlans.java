package org.unicitylabs.proxy;

import org.unicitylabs.proxy.repository.ApiKeyRepository;
import org.unicitylabs.proxy.repository.PricingPlanRepository;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class TestPricingPlans {
    public static long BASIC_PLAN_ID;
    public static long STANDARD_PLAN_ID;
    public static long PREMIUM_PLAN_ID;

    private static final List<Long> createdPlanIds = new ArrayList<>();
    private static final PricingPlanRepository repository = new PricingPlanRepository();
    private static final ApiKeyRepository apiKeyRepository = new ApiKeyRepository();
    
    public static void createTestPlans() {
        BASIC_PLAN_ID = repository.create("test-basic", 5, 50000, BigInteger.ONE);
        createdPlanIds.add(BASIC_PLAN_ID);
        
        STANDARD_PLAN_ID = repository.create("test-standard", 10, 100000, BigInteger.TWO);
        createdPlanIds.add(STANDARD_PLAN_ID);
        
        PREMIUM_PLAN_ID = repository.create("test-premium", 20, 500000, BigInteger.valueOf(3));
        createdPlanIds.add(PREMIUM_PLAN_ID);
    }
    
    public static void deleteTestPlansAndTheirApiKeys() {
        for (Long planId : createdPlanIds) {
            apiKeyRepository.deleteByPricingPlanId(planId);
        }
        for (Long planId : createdPlanIds) {
            repository.delete(planId);
        }
        createdPlanIds.clear();
    }
}