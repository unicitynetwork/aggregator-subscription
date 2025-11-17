package org.unicitylabs.proxy.shard;

import java.math.BigInteger;

/**
 * Represents a parsed shard suffix from the configuration.
 * The suffix value has an implicit leading '1' bit to allow representing leading zeros.
 * <p>
 * Examples:
 * - 1 → 0 bits (no sharding, matches everything)
 * - 2 → binary "10" → suffix "0" (1 bit)
 * - 3 → binary "11" → suffix "1" (1 bit)
 */
public class ShardSuffix {
    private final BigInteger suffixValue;
    private final BigInteger suffixBits;
    private final int bitLength;
    private final String targetUrl;

    public ShardSuffix(ShardInfo shardInfo) {
        this.suffixValue = BigInteger.valueOf(shardInfo.id());
        this.targetUrl = shardInfo.url();

        int totalBits = suffixValue.bitLength();
        this.bitLength = totalBits - 1;

        if (bitLength < 0) {
            throw new IllegalArgumentException(
                "Invalid suffix: " + suffixValue + " (must be at least 1)");
        }

        if (bitLength > 256) {
            throw new IllegalArgumentException(
                "Suffix too long: " + suffixValue + " (" + bitLength + " bits, max 256)");
        }

        // Extract suffix bits by clearing the leading '1'
        if (bitLength > 0) {
            BigInteger mask = BigInteger.ONE.shiftLeft(bitLength).subtract(BigInteger.ONE);
            this.suffixBits = suffixValue.and(mask);
        } else {
            // Special case: "1" means no suffix (0 bits)
            this.suffixBits = null;
        }
    }

    public BigInteger getSuffixValue() {
        return suffixValue;
    }

    public int getBitLength() {
        return bitLength;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public BigInteger getSuffixBits() {
        return suffixBits;
    }

    @Override
    public String toString() {
        return String.format("ShardSuffix{value=%s, bits=%d, suffix=%s, url='%s'}",
            suffixValue,
            bitLength,
            suffixBits != null ? suffixBits.toString(2): "/none/",
            targetUrl);
    }
}
