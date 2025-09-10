package com.unicity.proxy;

import com.unicity.proxy.repository.ApiKeyRepository;
import com.unicity.proxy.repository.PricingPlansRepository;

import java.util.ArrayList;
import java.util.List;

public class TestPricingPlans {
    public static int BASIC_PLAN_ID;
    public static int STANDARD_PLAN_ID;
    public static int PREMIUM_PLAN_ID;

    private static final List<Integer> createdPlanIds = new ArrayList<>();
    private static final PricingPlansRepository repository = new PricingPlansRepository();
    private static final ApiKeyRepository apiKeyRepository = new ApiKeyRepository();
    
    public static void createTestPlans() {
        BASIC_PLAN_ID = repository.save("test-basic", 5, 50000);
        createdPlanIds.add(BASIC_PLAN_ID);
        
        STANDARD_PLAN_ID = repository.save("test-standard", 10, 100000);
        createdPlanIds.add(STANDARD_PLAN_ID);
        
        PREMIUM_PLAN_ID = repository.save("test-premium", 20, 500000);
        createdPlanIds.add(PREMIUM_PLAN_ID);
    }
    
    public static void deleteTestPlansAndTheirApiKeys() {
        for (Integer planId : createdPlanIds) {
            apiKeyRepository.deleteByPricingPlanId(planId);
        }
        for (Integer planId : createdPlanIds) {
            repository.delete(planId);
        }
        createdPlanIds.clear();
    }
}