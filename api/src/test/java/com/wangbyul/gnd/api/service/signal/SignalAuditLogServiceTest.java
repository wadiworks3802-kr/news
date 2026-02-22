package com.wangbyul.gnd.api.service.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbyul.gnd.core.domain.AssetType;
import com.wangbyul.gnd.core.domain.AssetUniverseEntity;
import com.wangbyul.gnd.core.domain.SignalActionType;
import com.wangbyul.gnd.core.domain.SignalAuditLogEntity;
import com.wangbyul.gnd.core.domain.TradingSignalEntity;
import com.wangbyul.gnd.core.repository.SignalAuditLogRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SignalAuditLogService 단위 테스트 초안.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@ExtendWith(MockitoExtension.class)
class SignalAuditLogServiceTest {

    @Mock
    private SignalAuditLogRepository signalAuditLogRepository;

    @Test
    void saveFusionAuditLogShouldMaskSensitiveJsonValues() {
        SignalAuditLogService service = new SignalAuditLogService(signalAuditLogRepository, new ObjectMapper());

        TradingSignalEntity signal = new TradingSignalEntity();
        signal.setId("sig-1");
        signal.setModelVersion("policy-v7");

        AssetUniverseEntity asset = new AssetUniverseEntity();
        asset.setAssetCode("KR-TEST");
        asset.setAssetName("테스트");
        asset.setCountry("KR");
        asset.setAssetType(AssetType.STOCK);

        service.saveFusionAuditLog(
                signal,
                asset,
                SignalActionType.BUY_CANDIDATE,
                SignalActionType.HOLD,
                BigDecimal.valueOf(0.7d),
                BigDecimal.valueOf(0.5d),
                Map.of("token", "abc-secret", "news_count", 10),
                Map.of("password", "pw-secret", "rule_hit", true),
                Map.of("risk_checks", List.of("risk-ok")),
                "TEST_BLOCK",
                "policy-v7");

        ArgumentCaptor<SignalAuditLogEntity> captor = ArgumentCaptor.forClass(SignalAuditLogEntity.class);
        verify(signalAuditLogRepository).save(captor.capture());
        SignalAuditLogEntity saved = captor.getValue();
        assertThat(saved.getInputSnapshotJson()).contains("\"token\":\"***\"");
        assertThat(saved.getRuleHitsJson()).contains("\"password\":\"***\"");
        assertThat(saved.getRuleHitsJson()).doesNotContain("pw-secret");
    }
}
