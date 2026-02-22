package com.wangbyul.gnd.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;

@Getter
@Setter
@Entity
@Table(name = "news")
@Comment("정규화된 뉴스 본문과 파생 데이터 저장 테이블")
/**
 * 뉴스 정규화 저장 엔티티.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 *
 * 원문(raw) + 파생(ko 요약/분류/신뢰도/근거) 정보를 함께 보유한다.
 */
public class NewsEntity {

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sid", nullable = false)
    private SourceEntity source;

    @Column(nullable = false, length = 5)
    private String country;

    @Column(nullable = false, length = 8)
    private String lang;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CategoryType category;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    @Column(name = "title_raw", nullable = false, columnDefinition = "text")
    private String titleRaw;

    @Column(name = "body_raw", nullable = false, columnDefinition = "text")
    private String bodyRaw;

    @Column(name = "pub_utc")
    @Comment("원문 발행 시각(기존 호환 컬럼)")
    private OffsetDateTime pubUtc;

    @Column(name = "fetch_utc", nullable = false)
    @Comment("수집 시각(기존 호환 컬럼)")
    private OffsetDateTime fetchUtc;

    @Column(name = "published_at_utc")
    @Comment("원문 기사 발행 시각(시간 정렬 기준)")
    private OffsetDateTime publishedAtUtc;

    @Column(name = "fetched_at_utc")
    @Comment("외부 소스에서 기사 수집 완료 시각")
    private OffsetDateTime fetchedAtUtc;

    @Column(name = "translated_at_utc")
    @Comment("한국어 번역 완료 시각")
    private OffsetDateTime translatedAtUtc;

    @Column(name = "indexed_at_utc")
    @Comment("검색/시그널 인덱싱 완료 시각")
    private OffsetDateTime indexedAtUtc;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_time_source", length = 32)
    @Comment("대표 이벤트 시각 기준 컬럼 타입")
    private EventTimeSourceType eventTimeSource;

    @Column(nullable = false, length = 120)
    private String license;

    @Column(nullable = false)
    private Boolean robots;

    @Column(nullable = false)
    private Integer ttl;

    @Column(name = "url_norm", nullable = false, unique = true, columnDefinition = "text")
    private String urlNorm;

    @Column(name = "title_ko", columnDefinition = "text")
    private String titleKo;

    @Column(name = "summary_ko", columnDefinition = "text")
    private String summaryKo;

    @Column(name = "evidence_spans", nullable = false, columnDefinition = "jsonb")
    private String evidenceSpans = "[]";

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "simhash64")
    private Long simhash64;

    @Column(name = "dedup_group_id", length = 64)
    private String dedupGroupId;

    @Column(name = "trust_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal trustScore = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("레코드 생성 시각")
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        // 기본 시각 필드를 보정하여 null 저장을 방지한다.
        if (fetchUtc == null) {
            fetchUtc = OffsetDateTime.now();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (pubUtc == null) {
            pubUtc = fetchUtc;
        }
        if (publishedAtUtc == null) {
            publishedAtUtc = pubUtc;
        }
        if (fetchedAtUtc == null) {
            fetchedAtUtc = fetchUtc;
        }
        if (eventTimeSource == null) {
            eventTimeSource = publishedAtUtc != null ? EventTimeSourceType.PUBLISHED_AT_UTC : EventTimeSourceType.FETCH_UTC;
        }
    }
}
