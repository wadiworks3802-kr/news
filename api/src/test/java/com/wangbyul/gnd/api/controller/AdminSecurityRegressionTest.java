package com.wangbyul.gnd.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wangbyul.gnd.api.GndApiApplication;
import com.wangbyul.gnd.api.dto.FeatureToggleDto;
import com.wangbyul.gnd.api.service.AdminDiagnosticsService;
import com.wangbyul.gnd.api.service.SystemFeatureToggleService;
import com.wangbyul.gnd.core.domain.FeatureScopeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 API 권한/회귀 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@SpringBootTest(classes = GndApiApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-security-regression;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.security.admin-api-key=test-admin-key",
        "app.security.cors-allowlist=http://localhost:8081",
        "app.translation.ko.prefetch-enabled=false",
        "app.signal.auto-generate-enabled=false"
})
@SuppressWarnings("removal")
class AdminSecurityRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminDiagnosticsService adminDiagnosticsService;

    @MockBean
    private SystemFeatureToggleService systemFeatureToggleService;

    @Test
    void adminEndpointShouldRejectWhenApiKeyMissing() throws Exception {
        mockMvc.perform(get("/api/admin/diagnostics/universe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminDiagnosticsShouldWorkWithValidApiKey() throws Exception {
        when(adminDiagnosticsService.getUniverseDiagnostics(any(), any(), anyInt()))
                .thenReturn(Map.of("total_assets", 3, "top_repeated_families", Map.of()));
        when(adminDiagnosticsService.diagnosticMeta(anyString(), anyInt()))
                .thenReturn(Map.of("generated_at", OffsetDateTime.now(), "source_window", "test", "warning_count", 0));

        mockMvc.perform(get("/api/admin/diagnostics/universe")
                        .header("X-API-KEY", "test-admin-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_assets").value(3))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void featureToggleEndpointsShouldRemainRoleAdminProtected() throws Exception {
        when(systemFeatureToggleService.list(any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), any(), anyInt()))
                .thenReturn(List.of(FeatureToggleDto.builder()
                        .id(1L)
                        .featureKey("SIGNAL_GENERATION")
                        .enabled(true)
                        .scopeType(FeatureScopeType.GLOBAL)
                        .scopeValue(null)
                        .updatedAt(OffsetDateTime.now())
                        .traceId("trace-test")
                        .build()));

        mockMvc.perform(get("/api/admin/feature-toggles"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/feature-toggles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "feature_key":"SIGNAL_GENERATION",
                                  "enabled":false,
                                  "scope_type":"GLOBAL",
                                  "reason":"test"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }
}
