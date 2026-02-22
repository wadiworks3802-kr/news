package com.wangbyul.gnd.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.StrategyConfigEntity;
import com.wangbyul.gnd.core.repository.StrategyConfigRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전략/리스크 설정 동적 저장소 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class StrategyConfigService {

    private final StrategyConfigRepository strategyConfigRepository;
    private final ObjectMapper objectMapper;

    public StrategyConfigService(
            StrategyConfigRepository strategyConfigRepository,
            ObjectMapper objectMapper) {
        this.strategyConfigRepository = strategyConfigRepository;
        this.objectMapper = objectMapper;
    }

    public BigDecimal getDecimal(String key, BigDecimal fallback) {
        return strategyConfigRepository.findById(key)
                .map(StrategyConfigEntity::getConfigValue)
                .map(value -> parseDecimal(value, fallback))
                .orElse(fallback);
    }

    public Integer getInteger(String key, Integer fallback) {
        return strategyConfigRepository.findById(key)
                .map(StrategyConfigEntity::getConfigValue)
                .map(value -> parseInteger(value, fallback))
                .orElse(fallback);
    }

    public Boolean getBoolean(String key, Boolean fallback) {
        return strategyConfigRepository.findById(key)
                .map(StrategyConfigEntity::getConfigValue)
                .map(value -> parseBoolean(value, fallback))
                .orElse(fallback);
    }

    public List<Integer> getIntegerList(String key, List<Integer> fallback) {
        return strategyConfigRepository.findById(key)
                .map(StrategyConfigEntity::getConfigValue)
                .map(value -> parseIntegerList(value, fallback))
                .orElse(fallback);
    }

    @Transactional
    public void putDecimal(String key, BigDecimal value) {
        upsert(key, value == null ? "0" : value.toPlainString(), "DECIMAL");
    }

    @Transactional
    public void putInteger(String key, Integer value) {
        upsert(key, value == null ? "0" : String.valueOf(value), "INTEGER");
    }

    @Transactional
    public void putBoolean(String key, Boolean value) {
        upsert(key, value == null ? "false" : String.valueOf(value), "BOOLEAN");
    }

    @Transactional
    public void putIntegerList(String key, List<Integer> values) {
        try {
            upsert(key, objectMapper.writeValueAsString(values), "INT_LIST");
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid integer list config", e);
        }
    }

    private void upsert(String key, String value, String type) {
        StrategyConfigEntity entity = strategyConfigRepository.findById(key).orElseGet(StrategyConfigEntity::new);
        entity.setConfigKey(key);
        entity.setConfigValue(value);
        entity.setValueType(type);
        strategyConfigRepository.save(entity);
    }

    private BigDecimal parseDecimal(String value, BigDecimal fallback) {
        try {
            return new BigDecimal(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Integer parseInteger(String value, Integer fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Boolean parseBoolean(String value, Boolean fallback) {
        try {
            return Boolean.parseBoolean(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private List<Integer> parseIntegerList(String value, List<Integer> fallback) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception ignored) {
            return fallback;
        }
    }
}

