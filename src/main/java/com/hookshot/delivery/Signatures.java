package com.hookshot.delivery;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

/**
 * Stripe-style webhook signatures: {@code X-Hookshot-Signature: t=<unix>,v1=<hex>} where
 * {@code v1 = HMAC_SHA256(secret, t + "." + body)}. Including the timestamp in the signed
 * payload lets receivers reject replayed requests.
 */
public final class Signatures {

    private static final String ALGORITHM = "HmacSHA256";

    private Signatures() {}

    public static String hmacHex(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmacHex(secret, timestamp, body);
    }

    /** Reference verifier, i.e. what a receiver should do. Uses a constant-time comparison. */
    public static boolean verify(String secret, String header, String body, long nowEpochSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signature = kv[1];
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        if (Math.abs(nowEpochSeconds - timestamp) > toleranceSeconds) {
            return false;
        }
        byte[] expected = hmacHex(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }
}
