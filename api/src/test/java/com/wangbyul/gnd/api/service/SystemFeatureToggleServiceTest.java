package com.wangbyul.gnd.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.wangbyul.gnd.core.repository.SystemFeatureToggleRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemFeatureToggleServiceTest {

    @Mock
    private SystemFeatureToggleRepository systemFeatureToggleRepository;

    private SystemFeatureToggleService service;

    @BeforeEach
    void setUp() {
        service = new SystemFeatureToggleService(systemFeatureToggleRepository);
        when(systemFeatureToggleRepository.findByFeatureKeyAndScopeTypeAndScopeValue(anyString(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(systemFeatureToggleRepository.findByFeatureKeyAndScopeTypeAndScopeValueIsNull(anyString(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void aliasAutoOrderWithApprovalShouldNormalizeToAdminApprovalKey() {
        boolean enabled = service.isFeatureEnabled("AUTO_ORDER_WITH_APPROVAL", "KR", "AI", "005930.KS");
        assertThat(enabled).isFalse();
    }
}
