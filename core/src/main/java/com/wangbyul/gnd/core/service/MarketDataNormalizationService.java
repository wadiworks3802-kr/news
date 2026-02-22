package com.wangbyul.gnd.core.service;

import com.wangbyul.gnd.core.domain.MarketPriceBarEntity;
import com.wangbyul.gnd.core.domain.MarketQuoteSnapshotEntity;
import com.wangbyul.gnd.core.market.dto.MarketPriceBarDto;
import com.wangbyul.gnd.core.market.dto.MarketQuoteDto;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Provider 표준 DTO를 내부 JPA 엔티티로 정규화하는 서비스.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */
@Service
public class MarketDataNormalizationService {

    /**
     * 시세 스냅샷 DTO를 엔티티로 변환한다.
     * 필수값이 없으면 Optional.empty()를 반환하여 상위 수집 서비스에서 실패 건수로 집계한다.
     */
    public Optional<MarketQuoteSnapshotEntity> toQuoteEntity(MarketQuoteDto dto) {
        if (dto == null || dto.meta() == null) {
            return Optional.empty();
        }
        String assetCode = trim(dto.meta().assetCode());
        if (assetCode == null) {
            return Optional.empty();
        }

        MarketQuoteSnapshotEntity entity = new MarketQuoteSnapshotEntity();
        OffsetDateTime snapshotUtc = coalesce(dto.snapshotUtc(), dto.quoteTimeUtc(), OffsetDateTime.now());
        entity.setAssetCode(assetCode);
        entity.setSnapshotUtc(snapshotUtc);
        entity.setQuoteTimeUtc(coalesce(dto.quoteTimeUtc(), snapshotUtc, OffsetDateTime.now()));
        entity.setLastPrice(nonNegativeNullable(dto.lastPrice()));
        entity.setChangePct(dto.changePct());
        entity.setBidPrice(nonNegativeNullable(dto.bidPrice()));
        entity.setAskPrice(nonNegativeNullable(dto.askPrice()));
        entity.setBidSize(nonNegativeNullable(dto.bidSize()));
        entity.setAskSize(nonNegativeNullable(dto.askSize()));
        entity.setVolume(nonNegativeNullable(dto.volume()));
        entity.setProviderName(trim(dto.providerName()));

        if (entity.getBidPrice() != null && entity.getAskPrice() != null
                && entity.getBidPrice().compareTo(BigDecimal.ZERO) > 0
                && entity.getAskPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal mid = entity.getBidPrice().add(entity.getAskPrice())
                    .divide(BigDecimal.valueOf(2), 8, java.math.RoundingMode.HALF_UP);
            if (mid.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal spreadPct = entity.getAskPrice().subtract(entity.getBidPrice())
                        .divide(mid, 8, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                entity.setSpreadPct(spreadPct.setScale(4, java.math.RoundingMode.HALF_UP));
            }
        }
        entity.setIngestedAt(OffsetDateTime.now());
        return Optional.of(entity);
    }

    /**
     * OHLCV 바 DTO를 엔티티로 변환한다.
     */
    public Optional<MarketPriceBarEntity> toBarEntity(MarketPriceBarDto dto) {
        if (dto == null || dto.meta() == null) {
            return Optional.empty();
        }
        String assetCode = trim(dto.meta().assetCode());
        String timeframe = trim(dto.timeframe());
        if (assetCode == null || timeframe == null || dto.barTimeUtc() == null) {
            return Optional.empty();
        }
        if (dto.openPrice() == null || dto.highPrice() == null || dto.lowPrice() == null || dto.closePrice() == null) {
            return Optional.empty();
        }

        MarketPriceBarEntity entity = new MarketPriceBarEntity();
        entity.setAssetCode(assetCode);
        entity.setTimeframe(timeframe);
        entity.setBarTime(dto.barTimeUtc());
        entity.setBarTimeUtc(dto.barTimeUtc());
        entity.setOpenPrice(nonNegativeOrZero(dto.openPrice()));
        entity.setHighPrice(nonNegativeOrZero(dto.highPrice()));
        entity.setLowPrice(nonNegativeOrZero(dto.lowPrice()));
        entity.setClosePrice(nonNegativeOrZero(dto.closePrice()));
        entity.setVolume(nonNegativeOrZero(dto.volume()));
        entity.setProviderName(trim(dto.providerName()));
        entity.setIngestedAt(OffsetDateTime.now());
        return Optional.of(entity);
    }

    private OffsetDateTime coalesce(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return c;
    }

    private BigDecimal nonNegativeNullable(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonNegativeOrZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
