package org.unicitylabs.proxy.shard;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

public class ShardConfigValidator {
    public static void validate(ShardRouter router, ShardConfig config) {
        if (config.getTargets() == null || config.getTargets().isEmpty()) {
            throw new IllegalArgumentException("Shard configuration has no targets");
        }

        int maxBitLength = getMaxBitLength(config);

        List<String> failedPatterns = new ArrayList<>();

        testAllRequestIdsInASmallRange(router, maxBitLength, failedPatterns);
        testRandomRequestIds(router, failedPatterns);

        if (!failedPatterns.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("Incomplete shard configuration: %d patterns uncovered at %d-bit depth. " +
                    "First uncovered patterns: %s",
                    failedPatterns.size(),
                    maxBitLength,
                    failedPatterns.stream().limit(10).toList())
            );
        }
    }

    private static void testAllRequestIdsInASmallRange(ShardRouter router, int maxBitLength, List<String> failedPatterns) {
        BigInteger totalPatternsToTest = BigInteger.ONE.shiftLeft(maxBitLength);
        // For speed, let's not check too many options
        totalPatternsToTest = totalPatternsToTest.min(BigInteger.valueOf(100_000L));

        for (BigInteger i = BigInteger.ZERO; i.compareTo(totalPatternsToTest) < 0; i = i.add(BigInteger.ONE)) {
            String testRequestId = padLeftWithZeroes(i.toString(16), 64);

            testRoute(testRequestId, router, failedPatterns);
        }
    }

    private static void testRandomRequestIds(ShardRouter router, List<String> failedPatterns) {
        final SecureRandom random = new SecureRandom();
        for (int i = 0; i < 10_000; i++) {
            testRoute(nextRandomRequestId(random), router, failedPatterns);
        }
    }

    private static String nextRandomRequestId(SecureRandom random) {
        return padLeftWithZeroes(
                new BigInteger(
                        1,
                        nextRandomBytes(random, 32)
                ).toString(16),
                64);
    }

    private static byte[] nextRandomBytes(SecureRandom random, int count) {
        byte[] requestId = new byte[count];
        random.nextBytes(requestId);
        return requestId;
    }

    private static int getMaxBitLength(ShardConfig config) {
        int maxBitLength = 0;
        for (String hexPrefix : config.getTargets().keySet()) {
            try {
                BigInteger value = new BigInteger(hexPrefix, 16);
                int bitLength = value.bitLength() - 1; // Subtract 1 for implicit leading '1'
                if (bitLength > maxBitLength) {
                    maxBitLength = bitLength;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid hex prefix: " + hexPrefix, e);
            }
        }
        return maxBitLength;
    }

    private static void testRoute(String testRequestId, ShardRouter router, List<String> failedPatterns) {
        try {
            String targetUrl = router.routeByRequestId(testRequestId);
            if (targetUrl == null) {
                failedPatterns.add(testRequestId);
            }
        } catch (Exception e) {
            failedPatterns.add(testRequestId);
        }
    }

    private static String padLeftWithZeroes(String str, int length) {
        StringBuilder strBuilder = new StringBuilder(str);
        while (strBuilder.length() < length) {
            strBuilder.insert(0, "0");
        }
        return strBuilder.toString();
    }
}
