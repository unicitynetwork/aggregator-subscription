package org.unicitylabs.proxy.service;

import java.util.UUID;

public class ApiKeyService {
    public static String generateApiKey() {
        return "sk_" + UUID.randomUUID().toString().replace("-", "");
    }
}