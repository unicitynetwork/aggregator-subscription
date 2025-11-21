package org.unicitylabs.proxy.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class CryptoUtils {
    public static boolean passwordsEqual(String inputPassword, String storedPassword) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] inputHash = digest.digest(inputPassword.getBytes(StandardCharsets.UTF_8));
            byte[] storedHash = digest.digest(storedPassword.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(inputHash, storedHash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
