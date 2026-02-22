package com.wangbyul.gnd.api.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.experimental.UtilityClass;
/**
 * NewsCacheKeyFactory 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@UtilityClass
public class NewsCacheKeyFactory {

    public String build(String country, String category, String sort, String period, int page, int size, String viewLang, String q) {
        String normalizedQ = q == null ? "" : q.trim().toLowerCase();
        String normalizedLang = viewLang == null ? "ko" : viewLang.trim().toLowerCase();
        return "news:list:%s:%s:%s:%s:%d:%d:%s:%s".formatted(
                country,
                category,
                sort,
                period,
                page,
                size,
                normalizedLang,
                shortHash(normalizedQ));
    }

    private String shortHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
