package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ShardSuffixTest {

    @Test
    @DisplayName("Test parsing single bit prefix '2' (binary '10', suffix '0')")
    void testParseSingleBitPrefix0() {
        ShardSuffix prefix = new ShardSuffix("2", "http://shard0.example.com");

        assertEquals("2", prefix.getHexValue());
        assertEquals(1, prefix.getBitLength());
        assertEquals(BigInteger.ZERO, prefix.getSuffixBits());
        assertEquals("http://shard0.example.com", prefix.getTargetUrl());
    }

    @Test
    @DisplayName("Test parsing single bit prefix '3' (binary '11', suffix '1')")
    void testParseSingleBitPrefix1() {
        ShardSuffix prefix = new ShardSuffix("3", "http://shard1.example.com");

        assertEquals("3", prefix.getHexValue());
        assertEquals(1, prefix.getBitLength());
        assertEquals(BigInteger.ONE, prefix.getSuffixBits());
    }

    @Test
    @DisplayName("Test parsing 2-bit prefix '4' (binary '100', suffix '00')")
    void testParse2BitPrefix00() {
        ShardSuffix prefix = new ShardSuffix("4", "http://shard-00.example.com");

        assertEquals(2, prefix.getBitLength());
        assertEquals(BigInteger.ZERO, prefix.getSuffixBits());
    }

    @Test
    @DisplayName("Test parsing 2-bit prefix '7' (binary '111', suffix '11')")
    void testParse2BitPrefix11() {
        ShardSuffix prefix = new ShardSuffix("7", "http://shard-11.example.com");

        assertEquals(2, prefix.getBitLength());
        assertEquals(new BigInteger("3"), prefix.getSuffixBits()); // binary 11 = 3
    }

    @Test
    @DisplayName("Test parsing 8-bit prefix '100' (binary '100000000', suffix '00000000')")
    void testParse8BitPrefix() {
        ShardSuffix prefix = new ShardSuffix("100", "http://shard-00.example.com");

        assertEquals(8, prefix.getBitLength());
        assertEquals(BigInteger.ZERO, prefix.getSuffixBits());
    }

    @Test
    @DisplayName("Test parsing no-shard prefix '1' (0 bits)")
    void testParseNoShardPrefix() {
        ShardSuffix prefix = new ShardSuffix("1", "http://single.example.com");

        assertEquals(0, prefix.getBitLength());
        assertEquals(BigInteger.ZERO, prefix.getSuffixBits());
    }

    @Test
    @DisplayName("Test matching request ID with 1-bit prefix")
    void testMatching1Bit() {
        ShardSuffix prefix0 = new ShardSuffix("2", "http://shard0.example.com"); // suffix "0"
        ShardSuffix prefix1 = new ShardSuffix("3", "http://shard1.example.com"); // suffix "1"

        // Request ID ending in even hex digit (last bit = 0)
        String requestIdEven = "0000000000000000000000000000000000000000000000000000000000000000";
        assertTrue(prefix0.matches(requestIdEven));
        assertFalse(prefix1.matches(requestIdEven));

        // Request ID ending in odd hex digit (last bit = 1)
        String requestIdOdd = "000000000000000000000000000000000000000000000000000000000000000F";
        assertFalse(prefix0.matches(requestIdOdd));
        assertTrue(prefix1.matches(requestIdOdd));
    }

    @Test
    @DisplayName("Test no-shard prefix matches everything")
    void testNoShardMatchesAll() {
        ShardSuffix prefix = new ShardSuffix("1", "http://single.example.com");

        assertTrue(prefix.matches("0000000000000000000000000000000000000000000000000000000000000000"));
        assertTrue(prefix.matches("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"));
        assertTrue(prefix.matches("1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF"));
    }

    @Test
    @DisplayName("Test invalid prefix throws exception")
    void testInvalidPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new ShardSuffix("0", "http://example.com"));
    }

    @Test
    @DisplayName("Test prefix sorting by bit length (longest first)")
    void testPrefixSorting() {
        ShardSuffix prefix1bit = new ShardSuffix("2", "http://example.com");
        ShardSuffix prefix2bit = new ShardSuffix("4", "http://example.com");
        ShardSuffix prefix0bit = new ShardSuffix("1", "http://example.com");

        // Longer prefixes should come first
        assertTrue(ShardSuffix.BIT_LENGTH_COMPARATOR.compare(prefix2bit, prefix1bit) < 0);
        assertTrue(ShardSuffix.BIT_LENGTH_COMPARATOR.compare(prefix1bit, prefix0bit) < 0);
        assertTrue(ShardSuffix.BIT_LENGTH_COMPARATOR.compare(prefix2bit, prefix0bit) < 0);
    }
}
