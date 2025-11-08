package org.unicitylabs.proxy.shard;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * Represents a parsed shard suffix from the configuration.
 * The hex value has an implicit leading '1' bit to allow representing leading zeros.
 * <p>
 * Examples:
 * - "1" → 0 bits (no sharding, matches everything)
 * - "2" → binary "10" → suffix "0" (1 bit)
 * - "3" → binary "11" → suffix "1" (1 bit)
 */
public class ShardSuffix {
    public static final Comparator<ShardSuffix> BIT_LENGTH_COMPARATOR = (s1, s2) -> Integer.compare(s2.getBitLength(), s1.getBitLength());

    private final String hexValue;
    private final BigInteger suffixBits;
    private final int bitLength;
    private final String targetUrl;

    public ShardSuffix(String hexValue, String targetUrl) {
        this.hexValue = hexValue;
        this.targetUrl = targetUrl;

        BigInteger value = new BigInteger(hexValue, 16);

        int totalBits = value.bitLength();

        this.bitLength = totalBits - 1;

        if (bitLength < 0) {
            throw new IllegalArgumentException("Invalid hex suffix: " + hexValue + " (must be at least '1')");
        }

        if (bitLength > 256) {
            throw new IllegalArgumentException("Suffix too long: " + hexValue + " (" + bitLength + " bits, max 256)");
        }

        // Extract suffix bits by clearing the leading '1'
        if (bitLength > 0) {
            BigInteger mask = BigInteger.ONE.shiftLeft(bitLength).subtract(BigInteger.ONE);
            this.suffixBits = value.and(mask);
        } else {
            // Special case: "1" means no suffix (0 bits)
            this.suffixBits = BigInteger.ZERO;
        }
    }

    /**
     * Check if this suffix matches the given request ID suffix.
     * @param requestIdHex Full request ID as hex string (64 hex chars for 256 bits)
     * @return true if the suffix of requestId matches this suffix
     */
    public boolean matches(String requestIdHex) {
        if (bitLength == 0) {
            // Special case: suffix "1" matches everything
            return true;
        }

        // Parse request ID
        BigInteger requestId = new BigInteger(requestIdHex, 16);

        // Extract the last 'bitLength' bits from request ID
        BigInteger mask = BigInteger.ONE.shiftLeft(bitLength).subtract(BigInteger.ONE);
        BigInteger requestIdSuffix = requestId.and(mask);

        // Compare with our suffix
        return requestIdSuffix.equals(suffixBits);
    }

    public String getHexValue() {
        return hexValue;
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
        return String.format("ShardSuffix{hex='%s', bits=%d, suffix=%s, url='%s'}",
            hexValue, bitLength, suffixBits.toString(2), targetUrl);
    }
}
