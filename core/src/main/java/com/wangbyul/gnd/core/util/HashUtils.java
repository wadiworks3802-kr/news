package com.wangbyul.gnd.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.experimental.UtilityClass;
/**
 * HashUtils 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@UtilityClass
public class HashUtils {

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : encoded) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public long simHash64(String text) {
        int[] bits = new int[64];
        for (String token : text.split("\\s+")) {
            String tokenHash = sha256(token);
            long h = Long.parseUnsignedLong(tokenHash.substring(0, 16), 16);
            for (int i = 0; i < 64; i++) {
                if (((h >>> i) & 1L) == 1L) {
                    bits[i]++;
                } else {
                    bits[i]--;
                }
            }
        }

        long fingerprint = 0L;
        for (int i = 0; i < 64; i++) {
            if (bits[i] > 0) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
    }

    public double simHashSimilarity(long left, long right) {
        return 1d - ((double) Long.bitCount(left ^ right) / 64d);
    }
}
