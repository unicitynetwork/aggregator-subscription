package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ShardSuffixTest {

    @Test
    @DisplayName("Test parsing single bit suffix '2' (binary '10', suffix '0')")
    void testParseSingleBitSuffix0() {
        ShardInfo shardInfo = new ShardInfo(2, "http://shard0.example.com");
        ShardSuffix suffix = new ShardSuffix(shardInfo);

        assertEquals(new BigInteger("2"), suffix.getSuffixValue());
        assertEquals(1, suffix.getBitLength());
        assertEquals(BigInteger.ZERO, suffix.getSuffixBits());
        assertEquals("http://shard0.example.com", suffix.getTargetUrl());
    }

    @Test
    @DisplayName("Test parsing single bit suffix '3' (binary '11', suffix '1')")
    void testParseSingleBitSuffix1() {
        ShardInfo shardInfo = new ShardInfo(3, "http://shard1.example.com");
        ShardSuffix suffix = new ShardSuffix(shardInfo);

        assertEquals(new BigInteger("3"), suffix.getSuffixValue());
        assertEquals(1, suffix.getBitLength());
        assertEquals(BigInteger.ONE, suffix.getSuffixBits());
    }

    @Test
    @DisplayName("Test parsing 2-bit suffix '4' (binary '100', suffix '00')")
    void testParse2BitSuffix00() {
        ShardInfo shardInfo = new ShardInfo(4, "http://shard-00.example.com");
        ShardSuffix suffix = new ShardSuffix(shardInfo);

        assertEquals(2, suffix.getBitLength());
        assertEquals(BigInteger.ZERO, suffix.getSuffixBits());
    }

    @Test
    @DisplayName("Test parsing 2-bit suffix '7' (binary '111', suffix '11')")
    void testParse2BitSuffix11() {
        ShardInfo shardInfo = new ShardInfo(7, "http://shard-11.example.com");
        ShardSuffix suffix = new ShardSuffix(shardInfo);

        assertEquals(2, suffix.getBitLength());
        assertEquals(new BigInteger("3"), suffix.getSuffixBits());
    }

    @Test
    @DisplayName("Test parsing 8-bit suffix '256' (binary '100000000', suffix '00000000')")
    void testParse8BitSuffix() {
        ShardInfo shardInfo = new ShardInfo(256, "http://shard-00.example.com");
        ShardSuffix suffix = new ShardSuffix(shardInfo);

        assertEquals(8, suffix.getBitLength());
        assertEquals(BigInteger.ZERO, suffix.getSuffixBits());
    }

    @Test
    @DisplayName("Test parsing no-shard suffix '1' (0 bits)")
    void testParseNoShardSuffix() {
        ShardInfo shardInfo = new ShardInfo(1, "http://single.example.com");
        ShardSuffix suffix = new ShardSuffix(shardInfo);

        assertEquals(0, suffix.getBitLength());
        assertNull(suffix.getSuffixBits());
    }

    @Test
    @DisplayName("Test invalid suffix throws exception")
    void testInvalidSuffix() {
        ShardInfo shardInfo = new ShardInfo(0, "http://example.com");
        assertThrows(IllegalArgumentException.class, () -> new ShardSuffix(shardInfo));
    }
}
