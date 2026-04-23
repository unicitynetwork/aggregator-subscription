package org.unicitylabs.proxy.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class BftShardPrefixTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    @DisplayName("empty prefix constructible; BftShardInfo is what rejects it at the config boundary")
    void emptyPrefixConstructible() {
        assertEquals(0, new BftShardPrefix("").length());
    }

    @Test
    @DisplayName("non-binary characters rejected")
    void nonBinaryRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BftShardPrefix("01x"));
        assertThrows(IllegalArgumentException.class, () -> new BftShardPrefix("abc"));
        assertThrows(IllegalArgumentException.class, () -> new BftShardPrefix("02"));
    }

    @Test
    @DisplayName("null rejected")
    void nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BftShardPrefix(null));
    }

    @Test
    @DisplayName("single-bit prefix 0 matches keys with MSB=0")
    void singleBitZero() {
        BftShardPrefix p = new BftShardPrefix("0");
        assertTrue(p.matches(hex("00")));
        assertTrue(p.matches(hex("7f")));     // 0111_1111
        assertFalse(p.matches(hex("80")));    // 1000_0000
        assertFalse(p.matches(hex("ff")));
    }

    @Test
    @DisplayName("single-bit prefix 1 matches keys with MSB=1")
    void singleBitOne() {
        BftShardPrefix p = new BftShardPrefix("1");
        assertFalse(p.matches(hex("00")));
        assertFalse(p.matches(hex("7f")));
        assertTrue(p.matches(hex("80")));
        assertTrue(p.matches(hex("ff")));
    }

    @Test
    @DisplayName("multi-bit prefix matches high-order bits in byte 0 (MSB-first)")
    void multiBitInByteZero() {
        // "101" means bit7=1, bit6=0, bit5=1, rest don't care
        BftShardPrefix p = new BftShardPrefix("101");
        assertTrue(p.matches(hex("a0")));   // 1010_0000
        assertTrue(p.matches(hex("af")));   // 1010_1111
        assertTrue(p.matches(hex("bf")));   // 1011_1111 (bit5=1 ✓)
        assertFalse(p.matches(hex("80")));  // 1000_0000 (bit5=0)
        assertFalse(p.matches(hex("20")));  // 0010_0000 (bit7=0)
    }

    @Test
    @DisplayName("8-bit prefix matches exact first byte")
    void exactByte() {
        BftShardPrefix p = new BftShardPrefix("11001100");
        assertTrue(p.matches(hex("cc00")));    // 0xcc = 1100_1100
        assertTrue(p.matches(hex("cccc")));
        assertTrue(p.matches(hex("ccff")));
        assertFalse(p.matches(hex("cd00")));  // 0xcd = 1100_1101 != 1100_1100
    }

    @Test
    @DisplayName("prefix crossing byte boundary matches correctly")
    void crossByteBoundary() {
        // "000000001" = byte 0 == 0x00, high bit of byte 1 == 1
        BftShardPrefix p = new BftShardPrefix("000000001");
        assertTrue(p.matches(hex("0080")));
        assertTrue(p.matches(hex("00ff")));
        assertFalse(p.matches(hex("0000")));  // bit at position 8 is 0
        assertFalse(p.matches(hex("0100")));  // byte 0 != 0
    }

    @Test
    @DisplayName("prefix longer than key bits returns false")
    void prefixLongerThanKey() {
        BftShardPrefix p = new BftShardPrefix("000000000");  // 9 bits
        assertFalse(p.matches(hex("00")));  // only 8 bits available
    }

    @Test
    @DisplayName("null key bytes rejected")
    void nullKeyRejected() {
        BftShardPrefix p = new BftShardPrefix("0");
        assertThrows(IllegalArgumentException.class, () -> p.matches(null));
    }

    @Test
    @DisplayName("matches against 32-byte state ID (typical case)")
    void fullSizeKey() {
        BftShardPrefix p0 = new BftShardPrefix("0");
        BftShardPrefix p1 = new BftShardPrefix("1");
        byte[] leadingZero = new byte[32];
        byte[] leadingOne = new byte[32];
        leadingOne[0] = (byte) 0x80;
        assertTrue(p0.matches(leadingZero));
        assertFalse(p1.matches(leadingZero));
        assertFalse(p0.matches(leadingOne));
        assertTrue(p1.matches(leadingOne));
    }

    private static byte[] hex(String s) {
        return HEX.parseHex(s);
    }
}
