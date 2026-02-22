package com.wangbyul.gnd.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.StrategyConfigEntity;
import com.wangbyul.gnd.core.repository.StrategyConfigRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * StrategyConfigService 파싱/저장 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
class StrategyConfigServiceTest {

    private StrategyConfigRepository strategyConfigRepository;
    private StrategyConfigService strategyConfigService;

    @BeforeEach
    void setUp() {
        strategyConfigRepository = Mockito.mock(StrategyConfigRepository.class);
        strategyConfigService = new StrategyConfigService(strategyConfigRepository, new ObjectMapper());
    }

    @Test
    void getDecimalReturnsFallbackWhenMissing() {
        Mockito.when(strategyConfigRepository.findById("k")).thenReturn(Optional.empty());
        BigDecimal value = strategyConfigService.getDecimal("k", BigDecimal.valueOf(1.2d));
        Assertions.assertEquals(BigDecimal.valueOf(1.2d), value);
    }

    @Test
    void getDecimalParsesSavedValue() {
        StrategyConfigEntity entity = new StrategyConfigEntity();
        entity.setConfigKey("k");
        entity.setConfigValue("2.5");
        Mockito.when(strategyConfigRepository.findById("k")).thenReturn(Optional.of(entity));
        BigDecimal value = strategyConfigService.getDecimal("k", BigDecimal.ONE);
        Assertions.assertEquals(new BigDecimal("2.5"), value);
    }

    @Test
    void getIntegerParsesSavedValue() {
        StrategyConfigEntity entity = new StrategyConfigEntity();
        entity.setConfigKey("n");
        entity.setConfigValue("12");
        Mockito.when(strategyConfigRepository.findById("n")).thenReturn(Optional.of(entity));
        Integer value = strategyConfigService.getInteger("n", 1);
        Assertions.assertEquals(12, value);
    }

    @Test
    void getBooleanParsesSavedValue() {
        StrategyConfigEntity entity = new StrategyConfigEntity();
        entity.setConfigKey("b");
        entity.setConfigValue("true");
        Mockito.when(strategyConfigRepository.findById("b")).thenReturn(Optional.of(entity));
        Boolean value = strategyConfigService.getBoolean("b", false);
        Assertions.assertTrue(value);
    }

    @Test
    void getIntegerListParsesJsonArray() {
        StrategyConfigEntity entity = new StrategyConfigEntity();
        entity.setConfigKey("list");
        entity.setConfigValue("[30,30,40]");
        Mockito.when(strategyConfigRepository.findById("list")).thenReturn(Optional.of(entity));
        List<Integer> value = strategyConfigService.getIntegerList("list", List.of(1, 2));
        Assertions.assertEquals(List.of(30, 30, 40), value);
    }

    @Test
    void putDecimalSavesEntity() {
        Mockito.when(strategyConfigRepository.findById("d")).thenReturn(Optional.empty());
        strategyConfigService.putDecimal("d", BigDecimal.valueOf(3.14d));
        Mockito.verify(strategyConfigRepository).save(Mockito.any(StrategyConfigEntity.class));
    }

    @Test
    void putIntegerListSavesEntity() {
        Mockito.when(strategyConfigRepository.findById("l")).thenReturn(Optional.empty());
        strategyConfigService.putIntegerList("l", List.of(10, 20, 70));
        Mockito.verify(strategyConfigRepository).save(Mockito.any(StrategyConfigEntity.class));
    }
}

