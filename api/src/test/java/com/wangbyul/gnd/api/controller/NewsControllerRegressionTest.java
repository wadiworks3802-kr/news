package com.wangbyul.gnd.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wangbyul.gnd.api.config.SecurityConfig;
import com.wangbyul.gnd.api.security.ApiKeyAuthenticationFilter;
import com.wangbyul.gnd.api.service.NewsLocalizationService;
import com.wangbyul.gnd.core.domain.CategoryType;
import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.domain.SourceEntity;
import com.wangbyul.gnd.core.repository.NewsRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 뉴스 홈 API 회귀 테스트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@WebMvcTest(controllers = NewsController.class)
@Import({SecurityConfig.class, ApiKeyAuthenticationFilter.class})
@TestPropertySource(properties = {
        "app.security.admin-api-key=test-admin-key",
        "app.security.cors-allowlist=http://localhost:8081"
})
@SuppressWarnings("removal")
class NewsControllerRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NewsRepository newsRepository;

    @MockBean
    private NewsLocalizationService newsLocalizationService;

    @Test
    void newsListShouldKeepHomeResponseShape() throws Exception {
        SourceEntity source = new SourceEntity();
        source.setSid("rss-test");

        NewsEntity news = new NewsEntity();
        news.setId("news-1");
        news.setCountry("KR");
        news.setCategory(CategoryType.BRK);
        news.setLang("en");
        news.setSource(source);
        news.setUrl("https://example.com/news-1");
        news.setTitleRaw("raw-title");
        news.setBodyRaw("<p>raw-body</p>");
        news.setPubUtc(OffsetDateTime.now().minusMinutes(10));
        news.setFetchUtc(OffsetDateTime.now().minusMinutes(9));
        news.setCreatedAt(OffsetDateTime.now().minusMinutes(8));
        news.setTrustScore(BigDecimal.valueOf(0.74d));
        news.setEvidenceSpans("[\"s1\"]");

        when(newsRepository.findByCountryAndCategoryAndPubUtcBetween(anyString(), any(CategoryType.class), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(news)));
        when(newsLocalizationService.prefetchTranslations(any())).thenReturn(0);
        when(newsLocalizationService.localizeForView(any(), anyString()))
                .thenReturn(new NewsLocalizationService.LocalizedContent("테스트 제목", "테스트 요약", false));

        mockMvc.perform(get("/api/news")
                        .queryParam("country", "KR")
                        .queryParam("category", "BRK")
                        .queryParam("sort", "latest")
                        .queryParam("period", "24h")
                        .queryParam("page", "1")
                        .queryParam("size", "3")
                        .queryParam("view_lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("news-1"))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.cached").value(false))
                .andExpect(jsonPath("$.trace_id").exists());
    }
}
