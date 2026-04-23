package org.unicitylabs.proxy.shard;

/**
 * MSB-first bitstring prefix for BFT shard routing.
 *
 * <p>Prefix matching semantics (contract with the server-side aggregator):
 * <ul>
 *   <li>The stateId is a 32-byte raw value (no algorithm prefix).</li>
 *   <li>Bit {@code 0} of the prefix matches bit {@code 7} of byte {@code 0}
 *       (the most significant bit of the first byte).</li>
 *   <li>Bit {@code 1} of the prefix matches bit {@code 6} of byte {@code 0},
 *       and so on, descending through byte {@code 0} then byte {@code 1}, …,
 *       ending at bit {@code 0} of byte {@code 31}.</li>
 *   <li>Matching is performed on the raw bytes — never via {@code BigInteger}
 *       numeric interpretation — because a numeric decode would flip byte
 *       order.</li>
 * </ul>
 *
 * <p>This matches {@code types.ShardID.Comparator()} in bft-go-base and the
 * aggregator's bft-shard admission path.
 */
public final class BftShardPrefix {
    private final String bits;

    public BftShardPrefix(String bits) {
        if (bits == null) {
            throw new IllegalArgumentException("prefix bits cannot be null");
        }
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c != '0' && c != '1') {
                throw new IllegalArgumentException(
                    "prefix bits must be a binary bitstring (only '0' and '1'): '" + bits + "'");
            }
        }
        this.bits = bits;
    }

    public String bits() {
        return bits;
    }

    public int length() {
        return bits.length();
    }

    /**
     * Returns {@code true} if the prefix matches the leading {@link #length()}
     * bits of {@code keyBytes} in MSB-first order.
     *
     * @param keyBytes raw key bytes (no length constraint enforced here; the
     *                 caller is expected to have already validated length)
     * @return whether this prefix is a leading-bits prefix of {@code keyBytes}
     */
    public boolean matches(byte[] keyBytes) {
        if (keyBytes == null) {
            throw new IllegalArgumentException("keyBytes cannot be null");
        }
        if (length() > keyBytes.length * 8) {
            return false;
        }
        for (int i = 0; i < bits.length(); i++) {
            int byteIndex = i / 8;
            int bitIndex = 7 - (i % 8); // MSB-first within the byte
            int keyBit = (keyBytes[byteIndex] >> bitIndex) & 1;
            int prefixBit = bits.charAt(i) - '0';
            if (keyBit != prefixBit) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BftShardPrefix other)) return false;
        return bits.equals(other.bits);
    }

    @Override
    public int hashCode() {
        return bits.hashCode();
    }

    @Override
    public String toString() {
        return bits;
    }
}
